# 批3（L3 运行时兼容层）整体验收报告 — @7ad03281

被测 jar: target/scala-3.5.2/nebflow-assembly-1.4.1-beta.51.jar（sha256 26e68053…a85a7）
夹具: /tmp/rebrand-qa3/fixture（真实 ~/.nebflow 只读复制子集：auth.json / client-id / device.json / nebflow.json / neblink/{config,device,peers}.json / 2 真实会话，manifest 带 sha256）
全部实例端口 8099，逐轮 ps 核对后 kill，端口已释放。未触碰真实 ~/.nebflow、8080、8098。

## Run A — 当前值空转（-Duser.home=fixture，默认解析同名分支）
- A1 会话保留 PASS：GET /api/sessions（fixture token）返回 2 个夹具会话（nebflow-project/Docs、nebflow-project/Manager）+ 实例自建 Nebula
- A2 认证保留 PASS：无 token 403 / fixture token 200
- A3 身份字节保留 PASS：auth.json / client-id / device.json / nebflow.json / neblink/device.json 运行后 sha256 与 manifest 全等
- A4 neblink 凭据装载 PASS：实例日志 "Logged into NebLink Server: 0 peer(s)"（凭据真实通过服务端认证，强于 403 证据）
- A5 零迁移副作用 PASS：无 .rebrand-migrated 标记、fixture 根目录无新增目录

## Run B — NEBFLOW_HOME env 覆盖路径
- B PASS：NEBFLOW_HOME=fixture/.nebflow 起实例，403/200 认证一致、夹具会话全部 served
- 注：任务书写"NEBFLOW_HOME 指向夹具父目录"，按 paths.scala 实际语义 NEBFLOW_HOME 是数据根本身（os.Path(home)），已按代码实际语义执行，特此注明

## Run C — 改名日迁移黑盒（jar 手术：副本 brand.conf homeDirName→.neblinkqa，主线 jar 未动）
- C1 迁移触发 PASS：.neblinkqa 创建为 .nebflow 的**拷贝**（legacy 完整保留），legacy 内写入 .rebrand-migrated 标记（"migrated to …/.neblinkqa"）
- C2 client-id 字节保留 PASS：legacy 与迁移副本 sha256 全等（14a88fb3…）；auth.json 同样字节保留
- C3 迁移后服务 PASS：实例 dataRoot 切到新目录（新会话落在 .neblinkqa），夹具 2 会话 served，旧 token 403/200 认证一致
- C4 __BRAND__ 贯通 PASS：注入 homeDirName=".neblinkqa" 随 brand.conf 生效
- 覆盖边界说明：envPrefix/configFileName 双读的改名日行为由 RebrandCompatSpec 16/16 钉死（Scala 层），黑盒层本轮覆盖 homeDirName 目录迁移主线

## Playwright 冒烟链 + web 迁移（Run A 实例上执行，W1-W7 全 PASS）
- W1 __BRAND__ 四字段严格相等（homeDirName=".nebflow"）
- W2 #input 可见
- W3 零 console error / pageerror（含 reload 双轮）
- W4 WS OPEN（state.connected=true, readyState=1）
- W5 登录态保留：reload 后 shell 直进、nebflow_token 仍在
- W6 旧拼写归一（branding.js §2 每次启动执行）：nebflow-task-collapsed→nebflow_task_collapsed、nebflow:timeFormat→nebflow_time_format，旧键删除
- W7 当前值零变化：token 键仍为 nebflow_token，无意外重命名

## 独立证据复跑
- tests/branding-migration.spec.mjs：首次用全局 @playwright/test CLI 跑报 "No tests found"（全局 CLI 与仓内 node_modules/playwright 双装冲突）；改用仓内 node_modules/playwright/cli.js 重跑 **2/2 PASS**（today 零变化+旧拼写归一 870ms / rename-day 迁移 496ms）
