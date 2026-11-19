package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.index.{TaskSearchIndex, TaskSearchRerank}
import nebflow.core.project.TaskLedgerStore
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * task-rag 批（#1227 / §Batch 7）验收：TaskSearch 工具面（Nebula 面）。
 *
 * 中心性质：返回结构 = {task #N, 标题, 状态, 执行器图标, address(delegate:<id>),
 * snippet}（S14 反转后语义不变——icon 留显示、address=delegate:<id>）；Mail 地址
 * 面提示「检索到的地址可直接续派」在场；未配 embedding = 词法降级同结构；重排
 * 注册器缺省（未注册）= 跳过照常返回。🔴 零真实端点：词法路径直驱。
 */
class TaskSearchToolSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-tsearch-")
    PathUtil.setDataRoot(tmp)
    // 🔴 不在此 clear 全局重排端口——生产面（SharedResources 安装行）与并行 suite
    // 共享该单点；拆卸只还 dataRoot。端口语义的安装/清理在用例内部成对完成。
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def jsonObject(pairs: (String, io.circe.Json)*): io.circe.JsonObject =
    io.circe.JsonObject.fromIterable(pairs.map { case (k, v) => k -> v })

  test("search result carries the full return shape: task #N, title, status, executor, address, snippet") {
    withTempDataRoot { tmp =>
      IO {
        val id = TaskLedgerStore
          .open()
          .createSyncReturningId(title = "Ship the billing exporter")
          .fold(e => fail(e.message), identity)
        DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-zcode-1234abcd",
            executor = "zcode",
            cwd = "/tmp/ws",
            project = None,
            taskId = Some(id),
            parentSessionId = "root-1",
            title = "Ship the billing exporter",
            createdAt = 1L
          )
        )
        val res = TaskSearchTool
          .call(
            jsonObject("query" -> io.circe.Json.fromString("billing exporter")),
            ToolContext(projectRoot = tmp.toString)
          )
          .unsafeRunSync()
        val out = res.fold(e => fail(e.message), identity)
        // Mail 地址面提示句（§16 串）在场
        assert(out.contains("Mail address face:"), s"缺地址面提示句: $out")
        assert(out.contains("delegate:<id>") && out.contains("follow-up dispatch"), "提示句含续派指引")
        // JSON 命中数组：解析后逐字段钉死（task/delegate 两条命中各有其面）
        val jsonStart = out.indexOf("[")
        val jsonEnd = out.lastIndexOf("]")
        val hits = parser.parse(out.substring(jsonStart, jsonEnd + 1)).fold(throw _, identity).as[List[io.circe.Json]]
        val arr = hits.fold(e => fail(e.getMessage), identity)
        val taskHit = arr.map(_.hcursor).find(_.downField("kind").as[String] == Right("task"))
        val delegateHit = arr.map(_.hcursor).find(_.downField("kind").as[String] == Right("delegate"))
        assert(taskHit.isDefined && delegateHit.isDefined, s"双命中（task + delegate）在场: $out")
        // task 面：task #N 形态 + 投影的图标/地址（S14 反转后语义不变）
        assertEquals(taskHit.get.downField("task").as[String], Right(s"#$id"), "task #N 形态（带 #）")
        assertEquals(taskHit.get.downField("title").as[String], Right("Ship the billing exporter"))
        assertEquals(taskHit.get.downField("status").as[String], Right("open"))
        assertEquals(taskHit.get.downField("executor").as[String], Right("zcode"), "执行器图标键 = 绑定执行器投影")
        assertEquals(
          taskHit.get.downField("address").as[String],
          Right("delegate:delegate-zcode-1234abcd"),
          "address = delegate:<id>"
        )
        assert(taskHit.get.downField("snippet").as[String].isRight)
        // delegate 面：同地址同执行器，状态 = 申报槽（未申报 = null）
        assertEquals(delegateHit.get.downField("address").as[String], Right("delegate:delegate-zcode-1234abcd"))
        assertEquals(delegateHit.get.downField("executor").as[String], Right("zcode"))
        assertEquals(delegateHit.get.downField("status").as[Option[String]], Right(None), "无申报 = 状态空（不冒充 live 态）")
      }
    }.unsafeRunSync()
  }

  test("lexical degradation: no embedding config → mode=lexical, same shape, Mail hint present") {
    withTempDataRoot { tmp =>
      IO {
        TaskLedgerStore
          .open()
          .createSyncReturningId(title = "Refactor the widget cache")
          .fold(e => fail(e.message), identity)
        val out = TaskSearchTool
          .call(
            jsonObject("query" -> io.circe.Json.fromString("widget cache")),
            ToolContext(projectRoot = tmp.toString)
          )
          .unsafeRunSync()
          .fold(e => fail(e.message), identity)
        assert(out.contains("mode=lexical"), s"词法降级模式标注: $out")
        assert(out.contains("Mail address face:"), "降级路径同一地址面提示")
        assert(out.contains("Refactor the widget cache"))
      }
    }.unsafeRunSync()
  }

  test("empty result and blank query are explicit, well-shaped failures") {
    withTempDataRoot { tmp =>
      IO {
        val none = TaskSearchTool
          .call(
            jsonObject("query" -> io.circe.Json.fromString("zzzyyxxx")),
            ToolContext(projectRoot = tmp.toString)
          )
          .unsafeRunSync()
          .fold(e => fail(e.message), identity)
        assert(none.contains("no hits"), s"空命中显式措辞: $none")
        assert(none.contains("mode=lexical"))
        val blank = TaskSearchTool
          .call(
            jsonObject("query" -> io.circe.Json.fromString("  ")),
            ToolContext(projectRoot = tmp.toString)
          )
          .unsafeRunSync()
        assert(blank.isLeft, "空白 query = 显式错误")
      }
    }.unsafeRunSync()
  }

  test("rerank port default semantics: uninstalled = skip; error = skip; installed face drives order") {
    withTempDataRoot { tmp =>
      IO {
        // 缺省（未注册）：跳过照常返回
        assertEquals(
          TaskSearchRerank.rerank("q", List(TaskSearchRerank.Candidate("task:1", "t"))).unsafeRunSync(),
          None
        )

        // 执行体异常：吞掉 → None（检索面绝不炸）
        TaskSearchRerank.install { (q, c) => IO.raiseError[Option[List[String]]](new RuntimeException("boom")) }
        assertEquals(
          TaskSearchRerank.rerank("q", List(TaskSearchRerank.Candidate("task:1", "t"))).unsafeRunSync(),
          None
        )
        TaskSearchRerank.clear()

        // 在册 face：排列透传
        TaskSearchRerank.install { (q, c) => IO.pure(Some(c.map(_.id).reverse)) }
        val got = TaskSearchRerank
          .rerank("q", List(TaskSearchRerank.Candidate("task:1", "t"), TaskSearchRerank.Candidate("task:2", "u")))
          .unsafeRunSync()
        assertEquals(got, Some(List("task:2", "task:1")))
      }
    }.unsafeRunSync()
  }

  test("TaskSearch is registry-present and schema-shaped (query required, k optional)") {
    withTempDataRoot { tmp =>
      IO {
        assertEquals(TaskSearchTool.name, "TaskSearch")
        val schema = TaskSearchTool.inputSchema
        val props = schema("properties").map(_.asObject).getOrElse(None)
        assert(props.exists(_.contains("query")), "query 参数在场")
        assert(props.exists(_.contains("k")), "k 参数在场")
        assert(schema("required").exists(_.asArray.exists(_.contains(io.circe.Json.fromString("query")))), "query 必填")
        assert(TaskSearchTool.description.contains("Mail(address=\"delegate:<id>\")"), "工具描述含地址面指引")
        assert(TaskSearchTool.description.contains("read-only"), "只读声明在场")
      }
    }.unsafeRunSync()
  }

end TaskSearchToolSpec
