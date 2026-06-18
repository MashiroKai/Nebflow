package nebflow.mesh

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import nebflow.core.NebflowLogger
import nebflow.shared.{*, given}

import java.util.Base64
import scala.collection.mutable

/**
 * Content-addressable blob sync service.
 *
 * Each piece of content (message, UI message, file) is hashed and stored as a blob
 * in CloudBase cloud storage (COS). Sync only transfers blobs the other side doesn't have.
 *
 * This is the "git object store" equivalent: content is identified by hash,
 * and transfer is always incremental.
 */
class BlobSyncService private (meshService: MeshService):
  private val logger = NebflowLogger.forName("nebflow.blob-sync")

  // Local cache: hash → decoded content, avoids re-downloading
  private val localCache = mutable.Map.empty[String, Array[Byte]]

  /** Compute SHA-256 hash of content (first 12 hex chars — enough for dedup at our scale). */
  def hash(content: Array[Byte]): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(content)
    digest.digest().take(6).map(b => String.format("%02x", b)).mkString

  /** Compute hash for a JSON-serializable value. */
  def hashJson[T: io.circe.Encoder](value: T): String =
    hash(value.asJson.noSpaces.getBytes("UTF-8"))

  /**
   * Upload blobs that don't exist in cloud.
   * Returns the list of hashes that were actually uploaded.
   *
   * @param blobs Map of hash → content (raw bytes)
   */
  def uploadMissing(blobs: Map[String, Array[Byte]]): IO[List[String]] =
    if blobs.isEmpty then IO.pure(Nil)
    else
      for
        // Phase 1: batch check which hashes are missing from cloud
        missing <- batchCheck(blobs.keys.toList)
        // Phase 2: upload missing blobs one by one
        uploaded <- missing.traverseFilter { h =>
          blobs.get(h) match
            case Some(content) => uploadOne(h, content).as(Some(h))
            case None => IO.pure(None)
        }
      yield uploaded

  /**
   * Download blobs by hash. Returns map of hash → content.
   * Skips blobs already in local cache.
   */
  def download(hashes: List[String]): IO[Map[String, Array[Byte]]] =
    if hashes.isEmpty then IO.pure(Map.empty)
    else
      // Check local cache first
      val (cached, toDownload) = hashes.partition(localCache.contains)
      val cachedResults = cached.flatMap(h => localCache.get(h).map(h -> _)).toMap

      if toDownload.isEmpty then IO.pure(cachedResults)
      else
        for
          downloaded <- batchDownload(toDownload)
          // Cache downloaded blobs
          _ = downloaded.foreach { case (h, content) => localCache.put(h, content) }
        yield cachedResults ++ downloaded

  /** Check which hashes exist in local cache. */
  def hasLocally(hash: String): Boolean = localCache.contains(hash)

  /** Add content to local cache (e.g., after local creation, before upload). */
  def cacheLocal(hash: String, content: Array[Byte]): Unit =
    localCache.put(hash, content)

  // ---- Cloud API calls ----

  private def batchCheck(hashes: List[String]): IO[List[String]] =
    if hashes.isEmpty then IO.pure(Nil)
    else
      meshService.callCloudFunction("blob/batch-check", "hashes" -> hashes.asJson).map { resp =>
        resp.hcursor.downField("missing").as[List[String]].getOrElse(Nil)
      }

  private def uploadOne(hash: String, content: Array[Byte]): IO[Unit] =
    val b64 = Base64.getEncoder.encodeToString(content)
    meshService.callCloudFunction("blob/upload", "hash" -> hash.asJson, "content" -> b64.asJson).void

  private def batchDownload(hashes: List[String]): IO[Map[String, Array[Byte]]] =
    meshService.callCloudFunction("blob/batch-download", "hashes" -> hashes.asJson).map { resp =>
      val blobMap = resp.hcursor.downField("blobs").as[Map[String, String]].getOrElse(Map.empty)
      blobMap.map { case (h, b64) =>
        h -> Base64.getDecoder.decode(b64)
      }
    }

end BlobSyncService

object BlobSyncService:
  def apply(meshService: MeshService): BlobSyncService = new BlobSyncService(meshService)
