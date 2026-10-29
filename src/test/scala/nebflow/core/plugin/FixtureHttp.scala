package nebflow.core.plugin

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetSocketAddress, ServerSocket}

import scala.collection.mutable

/**
 * 测试夹具 HTTP 服务器（loopback 随机端口；P2 源引用批的 fixture 源伺服面）：
 * 路径 → (status, bytes) 注册表 + 请求路径日志（读序/回落次序的断言面——
 * 「官网→OSS 双跳」的正反例都靠「哪些路径被请求过、按什么序」取证）。
 * 未注册路径一律 404（= 端点不可达负例的伺服形态）。
 */
final class FixtureHttp:
  private val routes   = mutable.Map.empty[String, (Int, Array[Byte])]
  private val requests = mutable.ListBuffer.empty[String]
  private var server: HttpServer = null

  def port: Int = server.getAddress.getPort

  def base: String = s"http://127.0.0.1:$port"

  def serve(path: String, status: Int, body: String): Unit =
    routes(path) = (status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  def serveBytes(path: String, status: Int, body: Array[Byte]): Unit =
    routes(path) = (status, body)

  def requested: List[String] = requests.synchronized(requests.toList)

  def requestedTimes(path: String): Int = requests.synchronized(requests.count(_ == path))

  def start(): this.type =
    val probe = new ServerSocket(0)
    val port0 = probe.getLocalPort
    probe.close()
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port0), 0)
    val exec = java.util.concurrent.Executors.newCachedThreadPool()
    server.setExecutor(exec)
    server.createContext("/", (ex: HttpExchange) => {
      val path = ex.getRequestURI.getPath
      requests.synchronized { requests += path }
      routes.get(path) match
        case Some((status, bytes)) =>
          ex.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length)
          if bytes.nonEmpty then ex.getResponseBody.write(bytes)
          ex.close()
        case None =>
          ex.sendResponseHeaders(404, -1)
          ex.close()
    })
    server.start()
    this

  def stop(): Unit =
    if server != null then server.stop(0)

end FixtureHttp

object FixtureHttp:

  /** 预约一个大概率拒绝连接的死端口（预留后立即释放——夹具端点不可达负例用）。 */
  def deadPort(): Int =
    val ss = new ServerSocket(0)
    val p = ss.getLocalPort
    ss.close()
    p

end FixtureHttp
