package nebflow.core.tools

import munit.FunSuite

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * G6: ReadTool.prepareImage — oversized-image compression matching the
 * frontend policy (long edge <= 1920, JPEG 0.8). Covers the dimension
 * trigger, the byte-size trigger (no upscale), GIF exemption, undecodable
 * fallback, and alpha flattening onto white.
 */
class ReadImageCompressSpec extends FunSuite:

  private def pngBytes(img: BufferedImage): Array[Byte] =
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    baos.toByteArray

  private def decode(bytes: Array[Byte]): BufferedImage =
    ImageIO.read(new ByteArrayInputStream(bytes))

  private def noiseImage(w: Int, h: Int): BufferedImage =
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val rnd = new java.util.Random(42)
    for
      x <- 0 until w
      y <- 0 until h
    do img.setRGB(x, y, rnd.nextInt(0x1000000))
    img

  test("small image passes through unchanged"):
    val orig = pngBytes(noiseImage(64, 64))
    ReadTool.prepareImage(orig, "image/png", "small.png") match
      case ReadTool.Prepared(bytes, mediaType, note) =>
        assert(bytes.sameElements(orig))
        assertEquals(mediaType, "image/png")
        assert(note.isEmpty)
      case other => fail(s"Expected Prepared, got $other")

  test("long edge > 1920 is downscaled to 1920 and re-encoded as JPEG"):
    val orig = pngBytes(noiseImage(2500, 1800))
    assert(orig.length > ReadTool.COMPRESS_TRIGGER_BYTES) // sanity: real image
    ReadTool.prepareImage(orig, "image/png", "big.png") match
      case ReadTool.Prepared(bytes, mediaType, note) =>
        assertEquals(mediaType, "image/jpeg")
        val decoded = decode(bytes)
        assertEquals(math.max(decoded.getWidth, decoded.getHeight), ReadTool.COMPRESS_MAX_EDGE)
        assertEquals(math.min(decoded.getWidth, decoded.getHeight), 1800 * 1920 / 2500)
        assert(bytes.length < orig.length)
        assert(note.exists(_.contains("compressed")))
      case other => fail(s"Expected Prepared, got $other")

  test("byte-size trigger on small dimensions re-encodes without upscaling"):
    // 1000x800 noise PNG is > 2MB but under the pixel cap — must NOT resize
    val orig = pngBytes(noiseImage(1000, 800))
    assert(orig.length > ReadTool.COMPRESS_TRIGGER_BYTES)
    ReadTool.prepareImage(orig, "image/png", "heavy.png") match
      case ReadTool.Prepared(bytes, mediaType, _) =>
        assertEquals(mediaType, "image/jpeg")
        val decoded = decode(bytes)
        assertEquals(decoded.getWidth, 1000)
        assertEquals(decoded.getHeight, 800)
      case other => fail(s"Expected Prepared, got $other")

  test("GIF is exempt from compression"):
    val orig = Array.fill(3 * 1024 * 1024)(0x00.toByte) // >2MB, gif media type
    ReadTool.prepareImage(orig, "image/gif", "anim.gif") match
      case ReadTool.Prepared(bytes, mediaType, note) =>
        assert(bytes.sameElements(orig))
        assertEquals(mediaType, "image/gif")
        assert(note.isEmpty)
      case other => fail(s"Expected Prepared, got $other")

  test("undecodable bytes fall back to the original payload"):
    val garbage = Array.tabulate(64)(i => (i * 7).toByte)
    ReadTool.prepareImage(garbage, "image/png", "broken.png") match
      case ReadTool.Prepared(bytes, mediaType, note) =>
        assert(bytes.sameElements(garbage))
        assertEquals(mediaType, "image/png")
        assert(note.isEmpty)
      case other => fail(s"Expected Prepared, got $other")

  test("PNG transparency is flattened onto white, not black"):
    // ARGB image: fully transparent center pixel on blue background
    val img = new BufferedImage(2500, 2500, BufferedImage.TYPE_INT_ARGB)
    for
      x <- 0 until 2500
      y <- 0 until 2500
    do img.setRGB(x, y, 0xFF0000FF) // opaque blue
    for
      x <- 1000 until 1500
      y <- 1000 until 1500
    do img.setRGB(x, y, 0x00000000) // transparent center
    val orig = pngBytes(img)
    ReadTool.prepareImage(orig, "image/png", "alpha.png") match
      case ReadTool.Prepared(bytes, mediaType, _) =>
        assertEquals(mediaType, "image/jpeg")
        val decoded = decode(bytes)
        // Sample well inside the old transparent zone: (900, 900) maps back to
        // source (1171, 1171), safely within [1000, 1500) — the zone edge at
        // 1152 would land exactly on the blue boundary under bilinear scaling
        val center = new java.awt.Color(decoded.getRGB(900, 900))
        // White background under JPEG lossiness — near-white, never black
        assert(center.getRed > 240, s"red=${center.getRed}")
        assert(center.getGreen > 240, s"green=${center.getGreen}")
        assert(center.getBlue > 240, s"blue=${center.getBlue}")
      case other => fail(s"Expected Prepared, got $other")

end ReadImageCompressSpec
