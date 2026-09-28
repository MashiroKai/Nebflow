package nebflow.neblink

import munit.FunSuite
import nebflow.shared.{Branding, PathUtil}

import java.nio.file.Files

/**
 * 案 b①（2026-09-20 作者令 · 测试卫生）**放行通道**的解析面机械钉。
 *
 * 为什么要一个专用 spec：放行通道的端到端腿（隔离实例 + `NEBFLOW_ALLOW_PROD_ENROLL=1`
 * ⇒ 请求真的打到生产默认）与本批第一不变量「隔离实例**零生产出网**」冲突 ——
 * 自验里选择不触碰生产网（见节点报告的未做项），于是该腿钉在**解析面**：
 *
 *   · 置 env 跑一次本 spec（`NEBFLOW_ALLOW_PROD_ENROLL=1 sbt testOnly ...`）⇒ 真执行并断言放行；
 *   · 未置 env（常规 `sbt test`）⇒ 第一条判据被 `assume` **跳过**（不伪装成绿），
 *     第二条（默认数据根零行为变化）照常执行。
 *
 * 判据与 [[EnrollGuard.prodFallbackRefusal]] 的纯函数腿同向：live 读数 = 纯判据
 * + (`DeviceIdentity.isNonDefaultHome`, `EnrollGuard.explicitAllowEnv`) 两个输入。
 */
class EnrollGuardProdFallbackSwitchSpec extends FunSuite:

  private def withRoot[A](root: os.Path)(f: => A): A =
    val saved = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    try f
    finally PathUtil.setDataRoot(saved)

  test("案 b① 放行开关：置 env 时隔离数据根仍拿得到生产默认目标（= 改前行为）") {
    assume(
      EnrollGuard.explicitAllowEnv,
      "本判据需要以 NEBFLOW_ALLOW_PROD_ENROLL=1 跑（放行腿）；未置 env 时跳过，不伪装成绿"
    )
    val home = Files.createTempDirectory("nb-prod-enroll-switch")
    withRoot(os.Path(home, os.pwd)) {
      assertEquals(DeviceIdentity.isNonDefaultHome, true, "本判据必须跑在重定向 data root 上")
      assertEquals(EnrollGuard.prodFallbackRefusal, None, "显式开关 ⇒ 不拦（放行路径原样）")
      assertEquals(EnrollGuard.prodDefaultTarget, Some(Branding.serverUrl))
    }
  }

  test("案 b① 默认数据根：无论开关与否都拿得到默认目标（主实例零行为变化）") {
    withRoot(os.home / Branding.homeDirName) {
      assertEquals(DeviceIdentity.isNonDefaultHome, false)
      assertEquals(EnrollGuard.prodFallbackRefusal, None)
      assertEquals(EnrollGuard.prodDefaultTarget, Some(Branding.serverUrl))
    }
  }
end EnrollGuardProdFallbackSwitchSpec
