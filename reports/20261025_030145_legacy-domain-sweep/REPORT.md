# 旧域字面量清扫 · 主仓批（#831 跨仓清单 · stand 主仓部分）

日期：2026-10-25 03:01 · 批类：小批（清扫 + 防回归守卫）· 分支：delegate/delegate-nebflow-33192857

## ① 背景与判据

品牌迁移后浏览器入口统一切 mystand.space，旧域 `nebflow.space` 退役。本批把 #831 跨仓清单的主仓部分落地：**裸域字面量逐处清扫 + 防回归静态门禁（白名单制）**。判据（沿 REBRAND.md 裁定）：

- **浏览器入口 / 安装桥**（install.sh/ps1、/device、/profile）→ 改指 `mystand.space`（brand.conf 单一来源派生）；
- **服务子域**（`neblink.` / `auth.` / `api.`…nebflow.space）= 服务地址不是浏览器入口，**不随品牌迁移，保留**；
- **守卫/工具语义**（EnrollGuard ProdDomains、rebrand.sh、coldstart.sh）= 旧域是其识别/替换对象，**保留**；
- **历史封存件**（reports/、evidence/、tests/fixtures/、official-fixtures）= 改写即毁证，**保留**。

## ② 改动清单（17 文件修正 + 1 新增门禁 + 1 CI job）

**四处任务点名（裸域 → mystand.space）：**

| 位置 | 改动 |
|---|---|
| `brand.conf:15` | `installUrl = https://mystand.space/install.sh`（连带派生面 installPs1Url/uninstallUrl/uninstallPs1Url 经 `Branding.swapInstallScript` 同步换域，零额外改动点） |
| `README.md:88,94` | curl install.sh / irm install.ps1 → mystand.space |
| `src/main/scala/nebflow/cli/LoginCommand.scala:124` | `DevicePromptAddress` → `https://mystand.space/device`（地址位 = 静态品牌域裁定不变，仅值切换） |
| `scripts/verify-profile-url.mjs` | FALLBACK + 三条 case + 头注释 → mystand.space（契约 = `from=client` hint 跨仓约定，载体域随真值切换） |

**复核补漏（grep 全仓裸域，#831 清单之外的同判据项）：**

| 位置 | 改动 |
|---|---|
| `src/test/scala/nebflow/cli/LoginCommandSpec.scala:68,77` | 镜像断言同步 |
| `src/test/scala/nebflow/shared/BrandingSpec.scala:48,72` | installUrl 断言 + academicSearchUserAgent（host 从 installUrl 派生 → `research@mystand.space`） |
| `src/test/scala/nebflow/shared/BrandingSpec.scala:79-84` | dualEnv env 覆盖样例值 → example.org（任意值样例，语义中立化） |
| `src/test/scala/nebflow/shared/RebrandCompatSpec.scala:169-171` | install 伴生 URL 断言同步 |
| `src/main/scala/nebflow/shared/Branding.scala:89,97,104` | scaladoc 陈旧默认值描述（写「nebflow.space（brand.conf 真值）」，与 conf 现值矛盾）→ mystand.space |
| `src/main/scala/nebflow/gateway/StaticRoutes.scala:154` | 同上陈旧注释 → mystand.space |
| `src/main/scala/nebflow/neblink/CredentialDiagnostics.scala:288` | 负控样例 detail 里的生产服务 URL → `https://link.mystand.space`（现役 serverUrl 真值；行为零变化） |
| `src/test/scala/nebflow/neblink/CredentialDiagnosticsSpec.scala:309` | 同上镜像样例 |
| `src/test/scala/nebflow/gateway/BrandInjectionSpec.scala:45` | 注入样例 profileUrl → mystand.space（任意值） |
| `src/test/scala/nebflow/neblink/EnrollGuardExplicitLoginSpec.scala:32` | `ProdUrl` → `https://link.mystand.space`（仍属生产域族，isProdHost 判定不变，省一条白名单） |
| `tests/fixtures/reftag-harness.html:92` + `tests/reftag-redesign.spec.mjs:130` | 渲染夹具样例 doc URL → mystand.space（任意值，夹具+断言成对切换） |
| `src/main/resources/web/js/neblink.js:3,381` | 头注释现役流程 → mystand.space；历史注释「nebflow.space/connect 重定向」→「旧域 /connect 重定向」（史实保留、字面清除） |
| `src/main/resources/web/js/plugins.js:18` | ⑦ 清扫史注释 → 「旧域品牌残留」 |

**防回归守卫（钉进现有静态门禁族，白名单制）：**

- 新增 `scripts/check-legacy-domain.mjs`：git 跟踪文件全扫**裸域** `(?<![\w.-])nebflow\.space(?![\w-])`（与面板 ⑦ 门禁 `bare` 正则同源）；二进制跳过；**白名单 12 条、逐条带豁免理由**（自排除 / reports / evidence / fixtures / official-fixtures / rebrand.sh / coldstart.sh / EnrollGuard.scala / DeviceIdentitySpec.scala / branding-migration.spec.mjs / plugins-panel ⑦ / deploy-website SKILL.md）。
- `.github/workflows/ci.yml` 新增 `legacy-domain-gate` fail-fast job（与 user-knowledge/jdk-baseline/installer-home 门禁同族同形态，紧随 installer-home-gate 之后）；YAML 已验解析。

## ③ 验证

- `node scripts/check-legacy-domain.mjs` → **clean**（白名单外裸域 = 0；32 处命中全部落在白名单）。负控：向跟踪文件注入裸域 → **exit 1** 且逐行报犯；同文件追加 `auth.nebflow.space` 子域 → 不新增命中（子域豁免验证）；恢复后 clean。门禁自举险情已排：ci.yml 注释与门禁自身正则字面均被自家门禁捕获后改写/自排除。
- `node scripts/verify-profile-url.mjs` → **8/8 PASS**（FALLBACK 已切 mystand.space）。
- `node --check` 门禁脚本 + neblink.js + plugins.js 语法 OK；ci.yml YAML 解析 OK。
- **sbt 编译/spec 由主会话代跑**（KAI 迁移纪律）。预期绿：本批 Scala 改动 = 字符串字面量/注释切换 + 测试断言镜像同步，无签名/行为变化；`BrandingSpec`/`RebrandCompatSpec`/`LoginCommandSpec`/`CredentialDiagnosticsSpec`/`EnrollGuardExplicitLoginSpec`/`BrandInjectionSpec` 六个 spec 已逐一核对断言与新值同源。

## ④ 边界与后续

- 服务子域（neblink/auth/api.nebflow.space）按裁定保留，不在清扫判据内；`REBRAND.md` 对此有专段，无需改。
- `EnrollGuard.ProdDomains` 双域保留：旧域安装仍须被生产闸拦下（DeviceIdentitySpec:233 语义锚钉住）。
- 跨仓项（website 仓）不在本批：`mystand.space/install.sh` 服务真值由 website 仓发布桥保证（#831 跨仓清单承担）。
