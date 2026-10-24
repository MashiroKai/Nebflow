/* Office 预览转换腿（canvas-pptx-convert 批，作者令 2026-10-24 14:35「支持 pptx
 * 在 canvas 查看……服务端转换优先」）。
 *
 * `GET /api/nf-office?path=&ticket=` — 把本机 Office 文档（ppt 族为主，doc/xls 族
 * 顺带）转换成浏览器可直接呈现的 PDF（首选）或首页预览 PNG（Mac 兜底），转换结果
 * 落盘缓存——二次点击零重转。鉴权与 `GET /api/nf-file` 同一条判据链（同一 verdict
 * + 同一票闸：铸票用的 realpath 与本端点解出的 realpath 必然一致，不可读的路径
 * 不可转换，C1-5 单一权威不变）。本端点**不新增凭据面**：票仍由 `POST /api/nf-ticket`
 * 铸造，前端复用 viewer 既有铸票腿。
 *
 * 转换器阶梯（legs 按序尝试，首个产出即胜；全败 → 502）：
 *   1. soffice（LibreOffice headless）→ 整件 PDF，保真最优。
 *      - macOS：**必须经 `open -n -a LibreOffice --args …` 走 launchd GUI 会话拉起**
 *        ——本机实测（macOS 26.4 + LO 26.2.4.2）直接 ProcessBuilder 拉起的 soffice
 *        在 `sal_detail_initialize` 自旋挂死（11 分钟零输出，连 `--version` 都挂），
 *        而 `open` 拉起同一转换 ~12-18s 出件。代价：`open` 拉起的实例转换完成后
 *        **不自退且持续烧 CPU**——每次转换携独立 `-env:UserInstallation`（并发安全
 *        +不碰用户在跑的 GUI LibreOffice），出件/超时后按该 profile 串 `pkill` 收尸。
 *      - Linux/其他：直接 `soffice --headless --convert-to pdf`（常规自退）。
 *   2. qlmanage（macOS Quick Look）→ 首页 PNG（`-t` 缩略图语义，多页档只有首页），
 *      soffice 缺席或转换失败时的 Mac 兜底（作者令「Mac 端兜底」）。
 *
 * 缓存：`<dataRoot>/cache/office-preview/<sha256(realpath|size|mtime)>.<kind>.<ext>`
 * —— 键含 size+mtime，源件一变自然换键（无失效广播面）；文件名携带产出腿 kind 与
 * 产物扩展名，缓存命中时一并回读（`X-Nf-Office-Converter` 照实）。转换完成顺手
 * 清理 30 天前旧条目（>200 条时按 mtime 截断），防无界增长。
 *
 * 状态语义（`X-Nf-Reason` 头与 nf-file 同族，前端据此分流）：
 *   200 PDF/PNG（+`X-Nf-Office-Converter`、`X-Nf-Office-Cache: hit|miss`）
 *   400 缺 path / 401 缺 ticket / 403 verdict 或票据拒绝（nf-file 同族文案）
 *   413 源件超尺寸闸（source-too-large）
 *   415 扩展名不在支持表（unsupported-type）
 *   501 转换器缺席（converter-absent）→ 前端回落纯前端渲染腿
 *   502 全部转换腿失败（conversion-failed）→ 前端回落纯前端渲染腿
 */
package nebflow.gateway

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all.*
import nebflow.gateway.NfFilePolicy.*
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

object NfOfficeRoutes:

  /** 支持的源扩展名（小写无点）。ppt 族主目标；doc/xls 族尽力而为（作者令「顺带」）。 */
  val SupportedExt: Set[String] = Set("ppt", "pptx", "doc", "docx", "xls", "xlsx", "xlsm")

  /** 单腿转换超时。实测 13MB 重 deck ~18s 出件；150s 覆盖慢机冷启动面。 */
  val ConvertTimeout: FiniteDuration = 150.seconds

  /** 源件尺寸闸：转换在服务端内存里走，不占浏览器 tab 内存——闸给得比纯前端
   *  渲染腿（viewers/pptx.js 50MB）宽，但仍要防 pathology。 */
  val MaxSourceBytes: Long = 200L * 1024 * 1024

  /** qlmanage 缩略图最长边。1600px 在 4K 半屏预览与生成耗时之间取中（实测 7.7s）。 */
  private val QlmanageMaxPx = 1600

  /** qlmanage 进程超时（毫秒，os.proc.call 语义）。实测 ~8s；2 分钟封顶。 */
  private val QlmanageTimeoutMs = 120000L

  /** 转换器探测结果的缓存窗（防每次点击都跑探测面）。 */
  private val ProbeCacheTtl: FiniteDuration = 60.seconds

  /** 同时在飞的转换数上限（soffice 实例重；单飞已按文件去重，此闸防多文件并发）。 */
  private val MaxConcurrentConverts = 2

  /** 缓存清理阈值（转换完成路径上的顺手清理，永不拖垮主腿）。 */
  private val CacheSweepAge: FiniteDuration = 30.days
  private val CacheSweepCap = 200

  private val CorsHeaders: List[Header.ToRaw] = List(
    Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Origin"), "*"),
    Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Private-Network"), "true"),
    Header.Raw(
      org.typelevel.ci.CIString("Access-Control-Expose-Headers"),
      "x-nf-reason, x-nf-office-converter, x-nf-office-cache"
    )
  )

  private def plain(status: Status, body: String): Response[IO] =
    Response[IO](status)
      .putHeaders(`Content-Type`(MediaType.text.plain, Charset.`UTF-8`))
      .withBodyStream(fs2.Stream.emit(body).through(fs2.text.utf8.encode))

  private def reasonHead(reason: String): Header.Raw =
    Header.Raw(org.typelevel.ci.CIString("X-Nf-Reason"), reason)

  // ──────────────────────────────────────────────────────────────────────
  // 转换腿模型
  // ──────────────────────────────────────────────────────────────────────

  /** 一条转换腿：kind 是诊断读数（`X-Nf-Office-Converter`），run 把 `src` 转换进
   *  `outDir`，返回产出的唯一文件（PDF 或 PNG），失败 = IO.raiseError。 */
  final case class Leg(kind: String, run: (os.Path, os.Path) => IO[os.Path])

  /**
   * 转换器在场性探测（有序）。实现按平台罗列 legs，缺席的腿不上表——**空表 =
   * 501 converter-absent**。探测结果按 [[ProbeCacheTtl]] 记忆（AtomicReference 单值）。
   */
  trait Legs:
    def probe: IO[List[Leg]]

  object Legs:

    /** 生产实现（按本机平台组装）。 */
    def system(): Legs = new Legs:
      private val probeRef = new AtomicReference((0L, List.empty[Leg]))
      def probe: IO[List[Leg]] =
        IO.realTime.map(_.toMillis).flatMap { now =>
          val cached = probeRef.get
          if now - cached._1 < ProbeCacheTtl.toMillis then IO.pure(cached._2)
          else
            IO.blocking(if isMac then macLegs else linuxLegs).attempt.flatMap {
              case Right(legs) => IO(probeRef.set((now, legs))).as(legs)
              case Left(_)     => IO.pure(Nil)
            }
        }
    end system

    private def isMac: Boolean = sys.props("os.name").toLowerCase.contains("mac")

    /** macOS：soffice（经 `open` 拉起，见文件头注）+ qlmanage 兜底。 */
    private[gateway] def macLegs: List[Leg] =
      val sofficeLeg = sofficeMacApp.map { app =>
        Leg("soffice", (src, outDir) => runSofficeMac(app, src, outDir))
      }
      val qlLeg =
        Option.when(os.exists(os.Path("/usr/bin/qlmanage")))(
          Leg("qlmanage", (src, outDir) =>
            IO.blocking {
              os.proc(
                "qlmanage", "-t", "-s", QlmanageMaxPx.toString, "-o", outDir.toString, src.toString
              ).call(check = false, mergeErrIntoOut = true, timeout = QlmanageTimeoutMs)
            } *> IO.blocking {
              os.list(outDir).find(_.ext == "png")
                .getOrElse(throw new java.io.IOException("qlmanage produced no thumbnail"))
            }
          )
        )
      sofficeLeg.toList ::: qlLeg.toList
    end macLegs

    /** macOS soffice 腿：`open` 拉起（launchd GUI 会话）→ 轮询出件 → 收尸。 */
    private[gateway] def runSofficeMac(app: String, src: os.Path, outDir: os.Path): IO[os.Path] =
      IO.blocking {
        val runDir = os.temp.dir(prefix = "nf-office-run-")
        val profile = runDir / "profile"
        os.makeDir.all(profile)
        (runDir, profile)
      }.flatMap { case (runDir, profile) =>
        val convert = IO.blocking {
          os.proc(
            "open", "-n", "-a", app, "--args",
            "--headless",
            s"-env:UserInstallation=file://${profile}",
            "--convert-to", "pdf",
            "--outdir", outDir.toString,
            src.toString
          ).call(check = false, mergeErrIntoOut = true, timeout = 30000L)
        }
        // 出件/超时都收尸（pkill 只杀本转换 profile 串命中的进程）。
        convert *> pollFor(outDir, "pdf", ConvertTimeout).guarantee(reap(profile, runDir))
      }
    end runSofficeMac

    /** 非 macOS：soffice 直接拉起（Linux 上 headless convert-to 常规自退）。 */
    private[gateway] def linuxLegs: List[Leg] =
      val bin = sys.env.getOrElse("SOFFICE_BIN", "soffice")
      val found =
        try os.exists(os.Path(bin)) || inPath(bin)
        catch case _: Throwable => false
      Option.when(found)(Leg("soffice", (src, outDir) =>
        IO.blocking {
          val runDir = os.temp.dir(prefix = "nf-office-run-")
          val profile = runDir / "profile"
          os.makeDir.all(profile)
          os.proc(
            bin,
            "--headless",
            s"-env:UserInstallation=file://${profile}",
            "--convert-to", "pdf",
            "--outdir", outDir.toString,
            src.toString
          ).call(check = false, mergeErrIntoOut = true, timeout = ConvertTimeout.toMillis)
          os.remove.all(runDir)
        } *> pollFor(outDir, "pdf", ConvertTimeout)
      )).toList
    end linuxLegs

    /** macOS 上 LibreOffice 的 .app 位（`open -a` 接受 .app 路径或应用名）。 */
    private[gateway] def sofficeMacApp: Option[String] =
      List(
        "/Applications/LibreOffice.app",
        os.home.toString + "/Applications/LibreOffice.app"
      ).find(p => os.exists(os.Path(p))).orElse {
        Option.when(inPath("soffice"))("LibreOffice") // shim 存在 ≈ app 已装
      }

    private def inPath(name: String): Boolean =
      val path = sys.env.getOrElse("PATH", "")
      path.split(java.io.File.pathSeparator).exists { d =>
        d.nonEmpty && os.exists(os.Path(d) / name)
      }

    /** 轮询 `outDir` 里首个 `ext` 产出（500ms 步进，超时即败）。 */
    private[gateway] def pollFor(outDir: os.Path, ext: String, deadline: FiniteDuration): IO[os.Path] =
      def step(left: FiniteDuration): IO[os.Path] =
        IO.blocking(os.list(outDir).find(_.ext == ext)).flatMap {
          case Some(f) => IO.pure(f)
          case None =>
            if left <= 0.millis then
              IO.raiseError(new java.io.IOException(s"conversion produced no .$ext within $deadline"))
            else IO.sleep(500.millis) *> step(left - 500.millis)
        }
      step(deadline)
    end pollFor

    /** `open` 拉起的 headless 实例不自退（实测持续烧 CPU）——按本次 profile 串
     *  精确收尸（`-env:UserInstallation=file://<profile>` 是唯一定位串，pkill 只
     *  杀本转换拉起的进程，绝不波及用户自己的 GUI LibreOffice）。 */
    private def reap(profile: os.Path, runDir: os.Path): IO[Unit] =
      IO.blocking {
        os.proc("pkill", "-f", s"UserInstallation=file://${profile}").call(
          check = false,
          mergeErrIntoOut = true
        )
        ()
      }.attempt *> IO.blocking(os.remove.all(runDir)).attempt.void
  end Legs

  // ──────────────────────────────────────────────────────────────────────
  // 缓存
  // ──────────────────────────────────────────────────────────────────────

  /** 缓存键：realpath+size+mtime（+legs 品类签名）→ sha256 前 32 hex。源件一变
   *  自然换键；转换器阶梯变了也换键（不会拿旧阶梯的产物冒充新阶梯）。 */
  def cacheKey(real: os.Path, legsKind: String): String =
    val meta =
      try
        val st = os.stat(real)
        s"${real}|${st.size}|${st.mtime.toMillis}|$legsKind"
      catch case _: Throwable => s"${real}|-|-|$legsKind"
    val digest = MessageDigest.getInstance("SHA-256").digest(meta.getBytes("UTF-8"))
    digest.take(16).map("%02x".format(_)).mkString
  end cacheKey

  /** 缓存文件名形如 `<key>.<kind>.<ext>`（kind/ext 在转换落盘时定，命中时回读）。 */
  private def cachedLookup(cacheDir: os.Path, key: String): Option[(os.Path, String, String)] =
    try
      if !os.exists(cacheDir) then None
      else
        os.list(cacheDir).collectFirst {
          case p if p.last.startsWith(key + ".") =>
            val rest = p.last.drop(key.length + 1) // "<kind>.<ext>"
            val kind = rest.takeWhile(_ != '.')
            val ext  = rest.drop(kind.length + 1)
            (p, kind, ext)
        }
    catch case _: Throwable => None
  end cachedLookup

  private def sweep(cacheDir: os.Path): Unit =
    try
      if os.exists(cacheDir) then
        val entries = os.list(cacheDir).sortBy(f => os.mtime(f).toMillis)
        val now = System.currentTimeMillis()
        entries.filter(f => os.mtime(f).toMillis < now - CacheSweepAge.toMillis).foreach(os.remove.all)
        entries.drop(CacheSweepCap).foreach(os.remove.all)
    catch case _: Throwable => () // 清理面永不拖垮主腿
  end sweep

  // ──────────────────────────────────────────────────────────────────────
  // 路由
  // ──────────────────────────────────────────────────────────────────────

  def officeRoutes(
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized(),
    home: java.nio.file.Path = NfFilePolicy.processHome,
    legs: Legs = Legs.system(),
    cacheDir: os.Path = PathUtil.dataRoot / "cache" / "office-preview"
  ): HttpRoutes[IO] =
    val inflight: Ref[IO, Map[String, Deferred[IO, Either[String, os.Path]]]] = Ref.unsafe(Map.empty)
    val permits: Ref[IO, Int] = Ref.unsafe(0)

    /** 粗粒度并发闸（poll 版 Semaphore——MaxConcurrentConverts 小，竞争可忽略）。 */
    def acquire: IO[Unit] =
      permits.modify { c => if c < MaxConcurrentConverts then (c + 1, true) else (c, false) }.flatMap {
        case true  => IO.unit
        case false => IO.sleep(250.millis) *> acquire
      }
    def release: IO[Unit] = permits.update(_ - 1)

    /** 同键单飞：首个请求跑转换，其余等同一 Deferred 拿同一结果。 */
    def singleFlight(key: String, fa: IO[Either[String, os.Path]]): IO[Either[String, os.Path]] =
      Deferred[IO, Either[String, os.Path]].flatMap { d =>
        inflight.modify { cur =>
          cur.get(key) match
            case Some(existing) => (cur, Left(existing))
            case None           => (cur.updated(key, d), Right(d))
        }.flatMap {
          case Left(existing) => existing.get
          case Right(mine) =>
            fa.attempt
              .flatMap {
                case Left(t)  => IO.pure(Left(String.valueOf(t.getMessage)))
                case Right(r) => IO.pure(r)
              }
              .flatMap { r => mine.complete(r).attempt *> IO.pure(r) }
              .guarantee(inflight.update(_ - key))
        }
      }
    end singleFlight

    /** 按阶梯逐腿转换；产出移入缓存。Left = 全败文案。 */
    def convertVia(ls: List[Leg], real: os.Path, key: String): IO[Either[String, os.Path]] =
      ls match
        case Nil => IO.pure(Left("conversion-failed: all legs failed"))
        case leg :: rest =>
          IO.blocking {
            val tmp = os.temp.dir(prefix = "nf-office-")
            val outDir = tmp / "out"
            os.makeDir.all(outDir)
            (tmp, outDir)
          }.flatMap { case (tmp, outDir) =>
            leg.run(real, outDir)
              .attempt
              .flatMap {
                case Right(produced) =>
                  IO.blocking {
                    os.makeDir.all(cacheDir)
                    val target = cacheDir / s"$key.${leg.kind}.${produced.ext}"
                    os.move(produced, target, replaceExisting = true)
                    sweep(cacheDir)
                    target
                  }.map(Right(_))
                case Left(_) => IO.pure(Left("leg-failed"))
              }
              .guarantee(IO.blocking(os.remove.all(tmp)).attempt.void)
              .flatMap {
                case Right(p)     => IO.pure(Right(p))
                case Left(_)      => convertVia(rest, real, key)
              }
          }
      end match
    end convertVia

    def serve(file: os.Path, kind: String, cacheState: String, req: Request[IO]): IO[Response[IO]] =
      IO.defer {
        StaticFile
          .fromPath(fs2.io.file.Path.fromNioPath(file.toNIO), Some(req))
          .map(
            _.putHeaders(
              Header.Raw(org.typelevel.ci.CIString("X-Nf-Office-Converter"), kind),
              Header.Raw(org.typelevel.ci.CIString("X-Nf-Office-Cache"), cacheState),
              Header.Raw(org.typelevel.ci.CIString("Cache-Control"), "private, max-age=300")
            ).putHeaders(CorsHeaders*)
          )
          .getOrElse(plain(Status.NotFound, "converted artifact vanished"))
      }

    HttpRoutes.of[IO] {
      case OPTIONS -> Root / "api" / "nf-office" =>
        NoContent().map(_.putHeaders(CorsHeaders*))

      case req @ GET -> Root / "api" / "nf-office" =>
        val rawPath = req.params.get("path").getOrElse("")
        val ticket  = req.params.get("ticket").getOrElse("")
        val corsWrap = (io: IO[Response[IO]]) => io.map(_.putHeaders(CorsHeaders*))
        corsWrap(
          if rawPath.isEmpty then IO.pure(plain(Status.BadRequest, "Missing 'path' parameter"))
          else if ticket.isEmpty then
            IO.pure(plain(Status.Unauthorized, "Missing 'ticket' parameter").putHeaders(reasonHead("missing-ticket")))
          else
            NfFilePolicy.nfFileVerdictTolerant(rawPath, policy, home).flatMap {
              case denied: NfVerdict.Denied =>
                IO.pure(
                  plain(denied.status, s"${denied.reason}: ${denied.message}")
                    .putHeaders(reasonHead(denied.reason))
                )
              case NfVerdict.Allowed(real, _) =>
                store.verifyAndConsume(ticket, real.toString).flatMap {
                  case Left(reason) =>
                    IO.pure(
                      plain(Status.Forbidden, s"$reason: ticket rejected")
                        .putHeaders(reasonHead(reason))
                    )
                  case Right(_) =>
                    val realP  = os.Path(real)
                    val srcExt = realP.ext.toLowerCase
                    if !SupportedExt.contains(srcExt) then
                      IO.pure(
                        plain(Status.UnsupportedMediaType, s"unsupported source type: .$srcExt")
                          .putHeaders(reasonHead("unsupported-type"))
                      )
                    else
                      IO.blocking(os.stat(realP).size).attempt.flatMap {
                        case Right(size) if size > MaxSourceBytes =>
                          IO.pure(
                            plain(Status.PayloadTooLarge, s"source exceeds ${MaxSourceBytes}B")
                              .putHeaders(reasonHead("source-too-large"))
                          )
                        case Left(_) =>
                          IO.pure(plain(Status.NotFound, "source vanished").putHeaders(reasonHead("not-found")))
                        case Right(_) =>
                          legs.probe.flatMap {
                            case Nil =>
                              IO.pure(
                                plain(Status.NotImplemented, "no office converter on this machine")
                                  .putHeaders(reasonHead("converter-absent"))
                              )
                            case list =>
                              val key = cacheKey(realP, list.map(_.kind).mkString(","))
                              cachedLookup(cacheDir, key) match
                                case Some((hit, kind, _)) => serve(hit, kind, "hit", req)
                                case None =>
                                  val kinds = list.map(_.kind).mkString(",")
                                  singleFlight(key, acquire *> convertVia(list, realP, key).guarantee(release))
                                    .flatMap {
                                      case Right(produced) =>
                                        // 产出文件名携带 kind（convertVia 已按 leg 命名）
                                        val kind = produced.last.drop(key.length + 1).takeWhile(_ != '.')
                                        serve(produced, kind, "miss", req)
                                      case Left(_) =>
                                        IO.pure(
                                          plain(Status.BadGateway, s"all converter legs failed ($kinds)")
                                            .putHeaders(reasonHead("conversion-failed"))
                                        )
                                    }
                          }
                      }
                }
            }
        )
    }
  end officeRoutes

end NfOfficeRoutes
