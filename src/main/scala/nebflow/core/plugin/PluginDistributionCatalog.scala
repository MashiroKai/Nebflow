package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.PathUtil
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{KeyFactory, Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import scala.util.control.NonFatal

/** Signed distribution catalog. Catalog trust roots are independent of publisher keys. */
object PluginDistributionCatalog:
  val MaxCatalogBytes = 4L * 1024 * 1024
  val CodeCatalogUntrusted = "CATALOG_UNTRUSTED"
  val CodeCatalogSourceUnknown = "CATALOG_SOURCE_UNKNOWN"
  val CodeCatalogExpired = "CATALOG_EXPIRED"
  val CodeCatalogStale = "CATALOG_STALE"
  val CodeCatalogOversize = "CATALOG_OVERSIZE"

  final case class CatalogVersion(version: String, channel: String, platform: String, artifact: String,
      sha256: String, size: Long, yanked: Boolean, releaseNotes: Option[String])
  final case class CatalogPackage(id: String, publisherKeyId: String, versions: List[CatalogVersion],
      publisherClass: Option[PluginDistributionTrust.PublisherClass], reviewId: Option[String], listing: Option[Json])
  final case class CatalogDoc(protocolVersion: Int, catalogRevision: Long, validUntil: Option[Long], packages: List[CatalogPackage])
  final case class SignedCatalog(doc: CatalogDoc, catalog: Json, algorithm: String, keyId: String, signature: String)
  final case class VerifiedCatalog(sourceId: String, doc: CatalogDoc, location: String, isDir: Boolean)
  final case class Resolved(pkg: CatalogPackage, v: CatalogVersion)
  private final case class Source(location: String, isDir: Boolean)

  def canonicalSigningBytes(catalog: Json): Array[Byte] = catalog.noSpaces.getBytes(UTF_8)
  private def bad(message: String): String = s"$message ($CodeCatalogUntrusted)"
  private def str(c: io.circe.HCursor, name: String): Either[String, String] =
    c.get[String](name).left.map(_ => bad(s"invalid $name")).flatMap(s => Either.cond(s.nonEmpty && s == s.trim, s, bad(s"invalid $name")))
  private def optionalStr(c: io.circe.HCursor, name: String): Either[String, Option[String]] =
    if !c.keys.exists(_.toSet.contains(name)) then Right(None)
    else str(c, name).map(Some(_))
  private def version(j: Json): Either[String, CatalogVersion] =
    val c = j.hcursor
    for
      v <- str(c, "version")
      channel <- str(c, "channel")
      platform <- str(c, "platform")
      artifact <- str(c, "artifact")
      sha <- str(c, "sha256").flatMap(s => Either.cond(s.matches("[0-9a-fA-F]{64}"), s.toLowerCase, bad("invalid sha256")))
      size <- c.get[Long]("size").left.map(_ => bad("invalid size")).flatMap(n => Either.cond(n >= 0, n, bad("invalid size")))
      yanked <- c.get[Boolean]("yanked").left.map(_ => bad("invalid yanked"))
      notes <- optionalStr(c, "releaseNotes")
    yield CatalogVersion(v, channel, platform, artifact, sha, size, yanked, notes)
  private def pkg(j: Json): Either[String, CatalogPackage] =
    val c = j.hcursor
    for
      id <- str(c, "id").flatMap(s => Either.cond(s.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+"), s, bad("invalid package id")))
      key <- str(c, "publisherKeyId")
      vs <- c.get[List[Json]]("versions").left.map(_ => bad("invalid versions"))
      versions <- traverse(vs)(version)
      cls <- optionalStr(c, "publisherClass").flatMap {
        case None => Right(None)
        case Some(s) => PluginDistributionTrust.PublisherClass.fromLabel(s).map(x => Some(x)).toRight(bad("invalid publisherClass"))
      }
      review <- optionalStr(c, "reviewId")
      listing = c.downField("listing").focus
    yield CatalogPackage(id, key, versions, cls, review, listing)
  private def traverse[A, B](xs: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    xs.foldRight[Either[String, List[B]]](Right(Nil))((x, acc) => for a <- f(x); tail <- acc yield a :: tail)

  def decodeSigned(raw: String): Either[String, SignedCatalog] =
    for
      envelope <- io.circe.parser.parse(raw).left.map(e => bad(e.message))
      catalog <- envelope.hcursor.downField("catalog").focus.filter(_.isObject).toRight(bad("invalid catalog"))
      c = catalog.hcursor
      pv <- c.get[Int]("protocolVersion").left.map(_ => bad("invalid protocolVersion"))
      _ <- Either.cond(pv == 1, (), bad("unsupported protocolVersion"))
      rev <- c.get[Long]("catalogRevision").left.map(_ => bad("invalid catalogRevision"))
      _ <- Either.cond(rev >= 0, (), bad("invalid catalogRevision"))
      until <- if c.keys.exists(_.toSet.contains("validUntil")) then c.get[Long]("validUntil").left.map(_ => bad("invalid validUntil")).map(Some(_)) else Right(None)
      packages <- c.get[List[Json]]("packages").left.map(_ => bad("invalid packages")).flatMap(traverse(_)(pkg))
      sig <- envelope.hcursor.downField("signature").focus.filter(_.isObject).toRight(bad("invalid signature"))
      algorithm <- str(sig.hcursor, "algorithm")
      key <- str(sig.hcursor, "keyId")
      value <- str(sig.hcursor, "value")
    yield SignedCatalog(CatalogDoc(pv, rev, until, packages), catalog, algorithm, key, value)

  def verifySignature(signed: SignedCatalog, roots: Map[String, String]): Either[String, CatalogDoc] =
    try
      val hex = roots.getOrElse(signed.keyId, "")
      if signed.algorithm != "ed25519" || !hex.matches("[0-9a-fA-F]{64}") then Left(bad("unknown root or algorithm"))
      else
        val prefix = "302a300506032b6570032100"
        val der = (prefix + hex).grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(canonicalSigningBytes(signed.catalog))
        Either.cond(verifier.verify(Base64.getDecoder.decode(signed.signature)), signed.doc, bad("signature mismatch"))
    catch case NonFatal(_) => Left(bad("invalid signature or key"))

  private def config: Json =
    try io.circe.parser.parse(os.read(PathUtil.configJsonReadPath(PathUtil.dataRoot))).getOrElse(Json.obj())
    catch case NonFatal(_) => Json.obj()
  private def sources: Map[String, Source] =
    config.hcursor.downField("plugins").downField("catalogs").focus.flatMap(_.asObject).toList.flatMap(_.toList).flatMap { (id, j) =>
      val c = j.hcursor
      val url = c.get[String]("url").toOption.filter(_.nonEmpty)
      val dir = c.get[String]("dir").toOption.filter(_.nonEmpty)
      if !id.matches("[a-zA-Z0-9_-]+") || c.get[Boolean]("enabled").toOption.contains(false) then None
      else (url, dir) match
        case (Some(u), None) => Some(id -> Source(u, false))
        case (None, Some(d)) => Some(id -> Source(d, true))
        case _ => None
    }.toMap
  def configuredSourceIds: List[String] = sources.keys.toList.sorted
  private def roots: Map[String, String] =
    config.hcursor.downField("plugins").downField("catalogTrust").downField("roots").focus.flatMap(_.asObject)
      .toList.flatMap(_.toList).flatMap((k, v) => v.asString.map(k -> _)).toMap
  private def fetch(source: Source): Either[String, String] =
    try
      val uri = if source.isDir then (os.Path(source.location) / "catalog.json").toNIO.toUri else java.net.URI.create(source.location)
      val conn = uri.toURL.openConnection()
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(15000)
      conn match
        case h: java.net.HttpURLConnection =>
          h.setInstanceFollowRedirects(false)
          val status = h.getResponseCode
          if status < 200 || status >= 300 then throw new IllegalArgumentException(s"HTTP $status")
        case _ => ()
      val in = conn.getInputStream
      try
        val bytes = in.readNBytes((MaxCatalogBytes + 1).toInt)
        if bytes.length > MaxCatalogBytes then Left(s"catalog exceeds size limit ($CodeCatalogOversize)")
        else Right(new String(bytes, UTF_8))
      finally in.close()
    catch case NonFatal(e) => Left(s"catalog fetch failed: ${e.getMessage} ($CodeCatalogSourceUnknown)")

  private def checkRevision(id: String, revision: Long, advance: Boolean): Either[String, Unit] = synchronized {
    def check(): Either[String, Unit] =
      val path = PathUtil.dataRoot / "plugin-catalog-state" / s"$id.json"
      val previous: Either[String, Option[Long]] =
        if !os.exists(path) then Right(None)
        else try
          io.circe.parser.parse(os.read(path)).flatMap(_.hcursor.get[Long]("lastCatalogRevision"))
            .left.map(_ => bad("invalid catalog state")).map(Some(_))
        catch case NonFatal(_) => Left(bad("unreadable catalog state"))
      previous.flatMap { old =>
        if old.exists(_ > revision) then Left(s"catalog revision rollback ($CodeCatalogStale)")
        else
          if advance && !old.contains(revision) then AtomicJson.writeSync(path, Json.obj("lastCatalogRevision" -> revision.asJson).noSpaces)
          Right(())
      }
    try
      if !advance then check()
      else
        val lockDir = PathUtil.dataRoot / "plugin-catalog-state"
        os.makeDir.all(lockDir)
        val channel = java.nio.channels.FileChannel.open((lockDir / s"$id.lock").toNIO,
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        try
          val lock = channel.lock()
          try check() finally lock.release()
        finally channel.close()
    catch case NonFatal(e) => Left(bad(s"catalog state failure: ${e.getMessage}"))
  }

  def loadVerified(sourceId: String, advance: Boolean = true): IO[Either[String, VerifiedCatalog]] = IO.blocking {
    for
      source <- sources.get(sourceId).toRight(s"unknown catalog source ($CodeCatalogSourceUnknown)")
      raw <- fetch(source)
      signed <- decodeSigned(raw)
      doc <- verifySignature(signed, roots)
      _ <- Either.cond(!doc.validUntil.exists(_ <= System.currentTimeMillis() / 1000L), (), s"catalog expired ($CodeCatalogExpired)")
      _ <- checkRevision(sourceId, doc.catalogRevision, advance)
    yield VerifiedCatalog(sourceId, doc, source.location, source.isDir)
  }
  def resolve(doc: CatalogDoc, id: String, version: String, sha256: String): Either[String, Resolved] =
    doc.packages.find(_.id == id).flatMap(p => p.versions.find(v => v.version == version && v.sha256.equalsIgnoreCase(sha256) && !v.yanked).map(Resolved(p, _)))
      .toRight(s"$id version $version and sha256 not in verified catalog")
end PluginDistributionCatalog
