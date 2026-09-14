package nebflow.neblink

import java.io.RandomAccessFile
import java.security.MessageDigest

/** 附件上传/下载腿的**文件 IO 单点**（4b 腿 A）。
  *
  * 为什么单独一处：整件 sha256（E1 声明值，§B.1）与分块读取（E2）必须与
  * `dropbox/AttachContract.plan` 的 offset/长度**逐字对齐**——两处各写一套读法
  * 就会出现「发出去的块」与「算出的整件 digest」不是同一份字节的静默缺陷。
  * 摘要算法与十六进制形态复用 `dropbox/ChunkedTransfer.sha256Hex`（同一符号，
  * 不新增第二套 hex 口径）。
  */
object NeblinkFiles:

  /** 整件 sha256（**流式**：8 MiB 缓冲，永不把整件读进内存）。
    *
    * 与服务端 `WHOLE_DIGEST_MISMATCH` 判定同口径：64 位小写 hex（§B.1 E1）。 */
  def sha256OfFile(path: os.Path): String =
    val md     = MessageDigest.getInstance("SHA-256")
    val stream = java.nio.file.Files.newInputStream(path.toNIO)
    try
      val buf = new Array[Byte](8 * 1024 * 1024)
      var n   = stream.read(buf)
      while n > 0 do
        md.update(buf, 0, n)
        n = stream.read(buf)
      md.digest().map(b => f"$b%02x").mkString
    finally stream.close()

  /** 读 `[offset, offset + count)` 的原始字节（E2 单块的**唯一**读法）。
    * 越界/短读 ⇒ 抛异常（调用方按显式失败处理，禁补齐零字节 —— 补齐会得到一个
    * 与整件 digest 不一致的块，属静默损坏）。 */
  def readRange(path: os.Path, offset: Long, count: Int): Array[Byte] =
    val raf = new RandomAccessFile(path.toNIO.toFile, "r")
    try
      val buf = new Array[Byte](count)
      raf.seek(offset)
      raf.readFully(buf)
      buf
    finally raf.close()
