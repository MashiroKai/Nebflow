package nebflow.core.browser

import io.circe.syntax.*
import io.circe.{Json, parser}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentHashMap, ExecutionException, TimeUnit, TimeoutException}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Event callbacks must be non-blocking; never call synchronous CDP send in a listener. */
private[browser] final class CdpEventSubscriptions:
  private val listeners = new ConcurrentHashMap[String, java.util.concurrent.CopyOnWriteArrayList[Json => Unit]]()
  def subscribe(method: String, callback: Json => Unit): () => Unit =
    val list = listeners.computeIfAbsent(method, _ => new java.util.concurrent.CopyOnWriteArrayList[Json => Unit]())
    list.add(callback)
    () => { list.remove(callback); () }
  def dispatch(method: String, params: Json): Unit =
    Option(listeners.get(method)).foreach(_.asScala.foreach(cb => scala.util.Try(cb(params))))

/** CDP 调用失败（协议错误 / 超时 / 连接已断）。消息只含方法名与协议错误，不含页面内容。 */
final class CdpException(message: String) extends RuntimeException(message)

/**
 * 单条 CDP WebSocket 连接（页面目标或浏览器目标）。JSON-RPC：请求带自增 id，应答按 id
 * 回填 CompletableFuture；无 id 的协议事件分派给已注册的非阻塞回调。
 *
 * send 线程安全：WebSocket 不允许并发挂起 sendText，写端串行化；读端由 Listener 单线程回调。
 * 断开纪律：close 只关本 socket，不替浏览器做任何收尾（登录态与页面原样保留）。
 */
final class CdpConnection private (
  val url: String,
  ws: WebSocket,
  pending: ConcurrentHashMap[Long, CompletableFuture[Json]],
  closedFlag: AtomicBoolean,
  events: CdpEventSubscriptions
):
  private val seq = new AtomicLong(0L)
  private val writeLock = new Object

  def isOpen: Boolean = !closedFlag.get()

  private[nebflow] def subscribe(method: String)(callback: Json => Unit): () => Unit =
    events.subscribe(method, callback)

  def send(method: String, params: Json = Json.obj(), timeout: FiniteDuration = 15.seconds): Json =
    if !isOpen then throw new CdpException(s"CDP 连接已断开（$method）")
    val id = seq.incrementAndGet()
    val fut = new CompletableFuture[Json]()
    pending.put(id, fut)
    val msg = Json.obj("id" -> id.asJson, "method" -> method.asJson, "params" -> params).noSpaces
    val reply: Json =
      try
        writeLock.synchronized {
          ws.sendText(msg, true).get(5, TimeUnit.SECONDS)
        }
        fut.get(timeout.toMillis, TimeUnit.MILLISECONDS)
      catch
        case _: TimeoutException =>
          pending.remove(id)
          throw new CdpException(s"CDP 超时（$method）")
        case _: ExecutionException =>
          pending.remove(id)
          throw new CdpException(s"CDP 连接失败（$method）")
    reply.hcursor.downField("error").focus match
      case Some(err) =>
        val m = err.hcursor.get[String]("message").getOrElse("unknown")
        throw new CdpException(s"CDP 错误（$method）：$m")
      case None =>
        reply.hcursor.downField("result").focus.getOrElse(Json.obj())

  def close(): Unit =
    if closedFlag.compareAndSet(false, true) then
      try ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS)
      catch case _: Exception => ()
      CdpConnection.failAll(pending, "CDP 连接已关闭")

end CdpConnection

/** 一个可挂接的页面目标（/json/list 中 type=page 的条目）。 */
final case class PageTarget(id: String, url: String, title: String, wsUrl: String)

object CdpConnection:

  private val http: HttpClient =
    HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

  private final class Listener(
    pending: ConcurrentHashMap[Long, CompletableFuture[Json]],
    closedFlag: AtomicBoolean,
    events: CdpEventSubscriptions
  ) extends WebSocket.Listener:
    private val buf = new StringBuilder

    override def onOpen(webSocket: WebSocket): Unit =
      webSocket.request(1)

    override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      buf.append(data)
      if last then
        val text = buf.toString
        buf.clear()
        parser.parse(text).toOption.foreach { j =>
          j.hcursor.get[Long]("id").toOption match
            case Some(id) => Option(pending.remove(id)).foreach(_.complete(j))
            case None => j.hcursor.get[String]("method").toOption.foreach { method =>
              events.dispatch(method, j.hcursor.downField("params").focus.getOrElse(Json.obj()))
            }
        }
      webSocket.request(1)
      null

    override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
      closedFlag.set(true)
      failAll(pending, "CDP 连接已关闭")
      null

    override def onError(webSocket: WebSocket, error: Throwable): Unit =
      closedFlag.set(true)
      failAll(pending, "CDP 连接错误")

  private def failAll(pending: ConcurrentHashMap[Long, CompletableFuture[Json]], msg: String): Unit =
    pending.values.asScala.foreach(_.completeExceptionally(new CdpException(msg)))
    pending.clear()

  /** 建立 WebSocket 连接（目标 URL 来自 /json 发现接口）。 */
  def connect(wsUrl: String, timeout: FiniteDuration = 10.seconds): CdpConnection =
    val pending = new ConcurrentHashMap[Long, CompletableFuture[Json]]()
    val closedFlag = new AtomicBoolean(false)
    val events = new CdpEventSubscriptions
    val listener = new Listener(pending, closedFlag, events)
    val ws =
      try http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), listener).get(timeout.toMillis, TimeUnit.MILLISECONDS)
      catch case _: Exception => throw new CdpException("CDP WebSocket 连接失败")
    new CdpConnection(wsUrl, ws, pending, closedFlag, events)

  /** 本机 HTTP 发现接口（/json 路径族）。任何失败返回 None（调用方据此判定会话不在线）。 */
  def httpJson(url: String, method: String = "GET", timeout: FiniteDuration = 3.seconds): Option[Json] =
    try
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(java.time.Duration.ofMillis(timeout.toMillis))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() / 100 == 2 then parser.parse(resp.body()).toOption else None
    catch case _: Exception => None

  def cdpAlive(port: Int): Boolean =
    httpJson(s"http://127.0.0.1:$port/json/version", timeout = 2.seconds).isDefined

  def browserWsUrl(port: Int): Option[String] =
    httpJson(s"http://127.0.0.1:$port/json/version").flatMap(_.hcursor.get[String]("webSocketDebuggerUrl").toOption)

  private def parseTarget(t: Json): Option[PageTarget] =
    val c = t.hcursor
    val isPage = c.get[String]("type").toOption.contains("page")
    val ws = c.get[String]("webSocketDebuggerUrl").toOption
    if isPage && ws.isDefined then
      Some(
        PageTarget(
          id = c.get[String]("id").getOrElse(""),
          url = c.get[String]("url").getOrElse(""),
          title = c.get[String]("title").getOrElse(""),
          wsUrl = ws.get
        )
      )
    else None

  def pageTargets(port: Int): List[PageTarget] =
    val items = httpJson(s"http://127.0.0.1:$port/json/list").flatMap(_.asArray).map(_.toList).getOrElse(Nil)
    items.flatMap(parseTarget)

  /** 没有页面时由 Chrome 新开一个 about:blank 页签。 */
  def newPageTarget(port: Int): Option[PageTarget] =
    httpJson(s"http://127.0.0.1:$port/json/new?about:blank", method = "PUT", timeout = 5.seconds).flatMap(parseTarget)

  /** 系统分配一个空闲本机端口（CDP 调试端口）。 */
  def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

end CdpConnection
