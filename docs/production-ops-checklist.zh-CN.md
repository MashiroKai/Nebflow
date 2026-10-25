---
type: runbook
status: current
---

# 生产操作检查单（五起事故蒸馏 · zh-CN）

- **目的**：把 zcode 四会话审计（`/tmp/zcode-audit-report.md`）中的五起生产面自造事故蒸馏为**动笔前逐项核对的检查单**。生产操作链路此前靠 agent 纪律兜底（审计共性风险 2 的原话：「宜沉淀为检查单/护栏机制」），本件即该沉淀。
- **适用面**：凡触**生产面/宿主面/入口层/凭据面**的写动作——Caddy（入口层 web 服务器/反向代理，配置件 Caddyfile）与入口配置、进程 kill/重启、env/secret/凭据、生产部署与发布、生产域名变更。QA 隔离实例（隔离验证用临时引擎实例，语义见 [[parallel-session-resources.zh-CN]] §2）的常规操作走 AGENTS.md 第 9 条配方即可，本单针对的是**面向真实生产与作者在用实例**的动作。
- **用法**：动笔前逐节过一遍，每项可机械核对；「出处」列可溯源——事故台账见 §0，细机制见 `/tmp/zcode-audit/audit-*.md` 与 `digest-sess_*.txt`。本单是操作面细化，**不取代** AGENTS.md 既有条（第 9 条运行安全、第 12 条回报纪律等照常适用）。
- **配套**：并行会话资源面（worktree/端口/实例归属）见 [[parallel-session-resources.zh-CN]]；文档产出门禁方案见 [[visual-report-gate-plan.zh-CN]]。

---

## 0. 事故台账（S1–S5）

| # | 时刻 | 会话 | 事故 | 影响 | 当场处置 |
|---|------|------|------|------|---------|
| S1 | 10-13 23:22 | a751494c | 生产 relay 注入 `CONSOLE_DOMAIN=neblink.nebflow.space` 同域后，仓库 Caddyfile 的 SITE 块与 portal 块解析出**同一主机名** → Caddy「ambiguous site definition」崩溃循环 | 生产 443 全黑 ≈2 分钟 | VPS 原地手术：删冗余 portal 块（合并进 SITE 块，保留承载 per-IP ConnGuard（连接防护闸）的 `X-Real-IP`）；Caddyfile 同步入仓（39228b2） |
| S2 | 10-13 23:44 | a751494c | 部署时 VPS clone 拉了**仓库版 Caddyfile**，而仓库版早已丢失 staging（预发布环境）站点块（两周来线上跑的旧文件才有它）→ 重启后 staging TLS 没了 | staging TLS 丢失 | 补回 staging 块（带 `X-Real-IP`），仓库与 VPS 同步修复（eaa8a0f） |
| S3 | 10-14 08:02 | a751494c | QA 实例重启命令 `kill $(cat /tmp/qa-dev2-pid.txt) 95305 2>/dev/null`——把生产宿主 pid 95305 **硬编码附带**进了批量 kill，且 `2>/dev/null` 吞掉一切报错 | 误杀生产 8080，宕机 ≈1 分钟（恢复 pid 61089） | 立即以新 jar 拉起恢复，随后才继续原调试 |
| S4 | 10-10（期 0.6） | 50950c4e | `vercel env rm <name> preview`（vercel＝官网托管平台的 CLI）的 CLI 真实语义是**删整键**（含 Production 值），与方案预期「只删 preview」不符 → `LOGTO_APP_ID/SECRET` 从 Production 消失 | 官网 Production 缺键 ≈4 分钟 | 用官网仓 `.env.preview` 快照（mtime 与 env 创建同期）恢复 Production 值；Logto（官网登录的身份认证服务）侧 id 对照验证（app id 一致）；secret 无法从 Logto 回读，以快照为权威＋同组 6 键 39 天零改动佐证 |
| S5 | 10-10（期 0.6） | 50950c4e | staging app 首个 secret 值出现在一次调试输出中（零回显红线瞬时违背） | 凭据暴露面 | 立即轮换作废：新建 staging-active、DELETE 已暴露值（204）；此后最终 secret 零再回显 |

五起共性：**全部发生在「未按检查单先核对」的写动作上**——S1/S2 改入口前没验配置等价性与仓库-现网漂移；S3 kill 前没验 pid 归属；S4 写敏感面没先快照＋没验 CLI 语义；S5 调试输出没过零回显闸。

---

## A. Caddy / 入口层变更（出处 S1、S2）

- **A1 · 改前先 diff 仓库版 vs 现网版**〔出处 S2〕：任何 Caddyfile 下发/覆盖前，先取 VPS 在跑版本与仓库版逐行 diff。仓库版允许落后于线上手工修复——直接下发仓库版会把线上独有的站点块（如 staging）抹掉。两版不一致时，**先把差异合并回仓库版再部署**，禁「以仓库版为准强行覆盖」。
  ```bash
  ssh <vps> 'cat /etc/caddy/Caddyfile' > /tmp/caddy-live.conf
  diff -u /tmp/caddy-live.conf <repo>/Caddyfile   # 差异必须逐行归因后才能继续
  ```
- **A2 · `caddy validate` 通过后才准 reload/重启**〔出处 S1〕：S1 的「ambiguous site definition」是 validate 能机械拦住的确定性错误。改完先 validate，红了不部署。
  ```bash
  caddy validate --config /etc/caddy/Caddyfile    # 退出码非 0 ⇒ 停手
  ```
- **A3 · 改前备份原文件**〔出处 S1、S2〕：`cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak.$(date +%Y%m%dT%H%M%S)`——回滚单步的物料。
- **A4 · 域名/同域类改动先枚举全部 site 块 host 查重**〔出处 S1〕：给站点加域名或改环境变量注入的域名前，先列出 Caddyfile 里**所有** site 块的 host，确认目标主机名不出现在两个块里（Caddy 同 host 双块 = ambiguous 崩溃）。环境变量（如 `CONSOLE_DOMAIN`）改变块内 host 解析结果时，按**改变后**的值查重。
  ```bash
  grep -nE '^[A-Za-z0-9.*-]+(,|\s*\{)' /etc/caddy/Caddyfile   # 枚举 site 块 host 粗查
  ```
- **A5 · reload 后逐域终验**〔出处 S1、S2〕：每个受影响域名都验 TLS 握手 + HTTP 200（或预期状态码），staging 与生产两个面都验，黑一个域即回滚。
- **A6 · 入口黑窗 = 最小回滚单步 + 立即上报**〔出处 S1；依据项目记忆 09-13 裁的紧急例外〕：站点不可用时的唯一例外通道 = 恢复 A3 备份或删除刚加的块这类**单步回滚**，并立即上报作者；不顺手做任何其他写动作。

## B. 进程 / kill 操作（出处 S3）

- **B1 · 禁按端口号猜杀；kill 前先验归属**〔出处 S3；AGENTS.md 第 9 条「kill 前必须 PID 验身」；正面先例 9da47683「隔离实例先验身再 kill」〕：`lsof -ti :<port>` 只给 pid，**不给归属**。目标 pid 必须有归属证据三件套＋父链（四项全对上才准动手）：
  ```bash
  ps -p <pid> -o lstart=        # ① 启动时刻：与该实例的启动记录/pid 文件时刻吻合
  lsof -p <pid> | grep cwd      # ② cwd：指向该实例自己的 home（隔离实例 = 自己的 /tmp/qa-* home）
  ps -p <pid> -o command=       # ③ 命令行：--home/--port 参数与目标实例一致
  ps -p <pid> -o ppid=,lstart=  # ④ 父链：父进程形态（心跳 daemon 拉起 / launcher）符合预期
  ```
  同时核**端口用途**：`lsof -nP -iTCP:<port> -sTCP:LISTEN` 的结果与「我以为在这个端口上的东西」一致——端口≠身份，S3 就是「以为 95305 是 QA 实例」的猜杀。
- **B2 · kill 命令单 pid；禁批量拼 pid；禁吞错**〔出处 S3 直接机制〕：一条 kill 只带**一个**已经 B1 验身的 pid。禁 `kill $(cat pid.txt) <另一个字面 pid>` 式的顺手附带；禁 `2>/dev/null` 掩盖「pid 不存在/无权限」——那两类报错本身就是验身失败信号。
- **B3 · 非目标端口零触碰清单**〔出处 AGENTS.md 第 9 条＋尾注＋S3 同类史〕：保留段端口（8080/8090/8097/8099/443/80，全表见 [[parallel-session-resources.zh-CN]] §2）与注入环境表的宿主 PID 一律**零 kill、零 signal、零 restart**。命令里出现这些端口/pid 的行，写完先重读一遍再回车。
- **B4 · 自起进程登记 + 收尾自清 + 8080 集合断言**〔出处 AGENTS.md 第 9 条隔离配方〕：自起实例记 pid 文件；`trap 'cleanup' EXIT` 收尾；启动前后对宿主 8080 监听集合做快照断言（`H8080_BEFORE`/`H8080_AFTER` 相等），集合变了 = 立即停手上报。
- **B5 · 判活用 `ps -p` 二次确认**〔出处 AGENTS.md §2 内嵌片段 §7.1 v2 修正先例〕：凭 pid 判死前必须 `ps -p <pid>` 二次确认（rc=0 存活 / rc=1 确证不存在 / rc≥2 无法确证 ⇒ 停手上报）；`pid_start` 文本只是参考值，永不单独判死。

## C. env / secret / 凭据操作（出处 S4、S5）

- **C1 · env/secret 写操作先快照**〔出处 S4〕：`vercel env` 批量改、`.env` 类文件修改、Logto 应用配置变更前，先把**全部相关键**导出落盘（值＋`created`/`mtime`），再动手。恢复时以快照为权威，并用可独立对照的 id（如 Logto app id）二次验证，不信单一来源。
  ```bash
  vercel env ls --environment production --format json > /tmp/vercel-env-snap-$(date +%s).json
  cp .env .env.bak.$(date +%Y%m%dT%H%M%S) && ls -l .env*   # mtime 一并入档
  ```
- **C2 · CLI 破坏性语义先验证再批量**〔出处 S4〕：`vercel env rm <name> <env>` 删的是**整键**而非单环境值——与直觉相反。凡破坏性命令（rm/revoke/delete/overwrite）在敏感面批量使用前，先在 staging 或空键上实测语义，或在 `--help`/dry-run 里钉死作用范围。
- **C3 · 回显即轮换；零回显红线**〔出处 S5；反面教训 a751494c deviceToken 未脱敏打印后轮换承诺落空、staging 密码明文入会话〕：secret/token/密码出现在任何输出（调试打印、日志、报错体）中 = 立即轮换作废该值，轮换动作当场完成不挂账。查询类操作用「source 进环境变量」姿势，禁 `cat` 凭据文件进会话：
  ```bash
  set -a; source ~/.nebflow/secrets/<cred>; set +a   # token 只进 env，不回显
  ```
- **C4 · 凭据传递纪律（一次性通道约定）**〔出处：凭据卫生批新约定＋S5/a751494c 密码明文在案教训〕：
  1. **新凭据只落 `.nebflow/secrets/`，`chmod 600`**——一切密码/token/secret/api key 的落点唯一。
  2. **会话/报告/日志引用凭据 = 文件路径＋sha256 前 8 位指纹**，禁贴值本身。
  3. **聊天明文传递零容忍**；确需在会话向作者出示的旧值，**出示即轮换**（出示与轮换同一轮完成，不承诺「稍后」）。
  4. 交叉引用：本条与 C1 快照条配套使用——先快照（C1）再动改，动改后任何暴露走 C3/C4 轮换。
- **C5 · staging/生产凭据分域存放**〔出处 S4 恢复手法＋S5 落点〕：staging 专用凭据独立文件（如既有 `neblink-staging-logto-app.env` 600），与生产凭据物理分开；轮换以「新建别名值 → DELETE 旧值」的双步走，删旧值要拿到 2xx 确认。

## D. 生产写动作时机（五起共性防线）

- **D1 · 先报后动：作者在场且明确知悉**〔出处：项目记忆 09-13 裁 [T1]；S1–S5 全部为未报先动产物〕：生产面写动作（部署、重启、入口变更、凭据与 env 写、生产仓库 push）执行前必须作者在场且明确知悉该动作；作者睡下/离场时除已授权只读动作外，一切生产写排到作者醒后。紧急例外仅「站点不可用 ⇒ 最小回滚单步＋立即上报」（A6）。授权灰区按 50950c4e C5 正面先例处理：**明示自己对授权的解读再执行**，禁默默扩张。
- **D2 · 断言不过即刻回滚停手，禁再试**〔出处：项目记忆 09-13 裁〕：部署/变更后的验证断言红了，第一动作是回滚到 A3/C1 快照并停手上报——禁在故障态上连续重试「再部署一次看看」。
- **D3 · 重试禁无限**〔出处：AGENTS.md 第 10 条第 5 款〕：持续无输出/持续失败 ⇒ 换方法，禁重试同一调用；重试上限与间隔在任务书里写明。
- **D4 · 生产面动作单独报**〔出处：50950c4e D1 边界定调正面先例〕：并行自治窗内「本地合并照协议走、生产面仍单独报」的边界划分是有效先例——委托扩展不等于生产写放行。

## E. 部署终验（交付断层防线）

- **E1 · 部署后必须终验：别名 API + HTML 指纹逐字节对比**〔出处：a7a04f4e M5 双域终验先例〕：发布完成后，用部署别名 URL 与正式域名各取一遍关键 API 响应与 HTML，逐字节（hash）对比确认域名已指到本次部署——307（临时重定向状态码）之类的预期重定向先排除再比。「READY」不等于「域名已生效」。
  ```bash
  curl -s https://<deployment-url>/ -o /tmp/a.html; curl -s https://<domain>/ -o /tmp/b.html
  shasum -a 256 /tmp/a.html /tmp/b.html    # 两值必须一致（排除已知重定向/时变片段后）
  ```
- **E2 · 双域都验（staging + 生产）**〔出处：a7a04f4e M5；S2 反例——staging 面被改动后无人立即复验 TLS〕：staging 与生产是两条链路，动过任何一面，两面都要终验。
- **E3 · 「代码完成 ≠ 线上生效」显式收口**〔出处：审计共性风险 1（8080 修复断在未换 jar，跨两会话未闭合）＋AGENTS.md 第 12 条回报纪律〕：交付报告里，凡改动需要重启/换 jar/换部署才生效的，必须显式标注「**待重启生效**」并登记发版窗口待办；禁让作者误以为线上已生效。
- **E4 · 生产宿主重启窗归作者/Nebula 统一安排**〔出处：AGENTS.md 第 12 条主仓特殊形态〕：8080 宿主的前端/修复生效依赖宿主重启，重启窗口统一安排，禁为「让修复生效」擅自重启生产宿主（那属于 D1 先报后动面）。

---

## 附 · 本单与其他护栏件的关系

- **AGENTS.md**：既有条（第 9 条运行安全、第 10 条长跑纪律、第 12 条回报纪律）继续优先；本单是其**操作面展开**，冲突时以 AGENTS.md 为准并回报勘误。
- **`docs/parallel-session-resources.zh-CN.md` → [[parallel-session-resources.zh-CN]]**：并行会话场景下的 worktree/端口/实例分配与「先验身再 kill」完整铁则在彼件；本单 B 节只留操作时点上的引用。
- **`docs/design/visual-report-gate-plan.zh-CN.md` → [[visual-report-gate-plan.zh-CN]]**：文档产出的机械门禁方案件，与本单无直接耦合，指针并列于此仅为三件互查方便。
