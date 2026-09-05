# 00:00 开跑指令附件：tool-engineer / prompt-engineer 任务书（Manager 预写，00:00 直接发送）

## 开跑指令模板（发 Frontend/Backend）

「00:00 开跑——按已发任务书从任务 1 开始，纪律不变（每项 commit+Mail 回报+遇阻 20 分钟换项）。」

**Backend 追加项（W4-1 转交，随开跑指令一起发，归入任务 6 Hook P0 同批或独立 commit 均可）**：
CompactService.scala val `ManagerSaveMemoryReminder`（:145 起），在 RECORD 第 4 项（:157 "...point users/agents at results without re-searching"）之后、`|VERIFY & CLEAR`（:159）之前插入第 5 项（stripMargin 格式）：
```
5. PERIOD SELF-CHECK — count [USER-RULING] mails in your team's flow-mailbox
   (~/.nebflow/sessions/*/flow-mailbox/ — Grep pattern:"[USER-RULING]") since
   your last save turn, and summarize direct member-to-member collaboration
   (pairing/handoff/review) in one line. Record counts and one-liners ONLY —
   never full mail bodies.
```
（来源：prompt-engineer W4 侦查定位，文案已审。）

## tool-engineer 任务书

今晚整晚批次（00:00 起，明早 09:00 汇总）。双工作区：主仓 **/tmp/nb-onight-release**（feat/onight-release @7408a4b4）+ 官网仓 **~/.nebflow/projects/nebflow-website**（先 `git checkout -b feat/onight-website`）。方案文档必读：/tmp/feat-f4-release-desktop.md。每项完成即 commit（两仓分开 commit）。

**用户修正（验收硬条件）**：桌面版严格区分系统——dmg（macOS）/msi（Windows）按平台独立产物，官网按平台给下载按钮。

### 任务 1（P0）：jpackage 手动出 arm64 dmg
按方案 §2.5 命令草案：sbt assembly → jdeps 算模块清单（结果固化 packaging/jlink-modules.txt 提交进仓）→ jlink 裁剪 JRE（build/runtime）→ jpackage --type dmg（含 --arguments --server、--java-options '--add-opens java.base/java.lang=ALL-UNNAMED'、--app-version 从 VERSION 派生去横线、--runtime-image）。产物 Nebflow-<date>-arm64.dmg 到 build/dist。**验收（命令化照跑）**：①挂载 dmg 将 .app 拖到 /tmp 测试目录直接启动（bundle 内嵌 JRE=天然无 JDK 验证）→ 浏览器到 localhost:8080 + `curl -fsS localhost:8080/api/health` 200（注意端口别撞 8080 主实例——jpackage app 的 server 端口若是固定 8080 会冲突：先起测试 app 前停用主实例端口占用检查，若冲突用 --arguments 传 --port 8097 之类验证，记录进 report）②发一条真实消息获 LLM 回复（用测试 home：--arguments 追加 --home /tmp/nb-dmg-home，避免污染 ~/.nebflow）③dmg ≤ 90MB（超了检查 jlink compress/strip）④退出后端口释放无残留进程（pgrep 检查）。图标 P0 可用默认。commit（packaging/ 脚本+jlink-modules.txt 固化）。

### 任务 2（P1）：CI 三平台
auto-release.yml 扩展按方案 §5.1 骨架：build-jar（ubuntu，现状保留）→ package-macos（matrix macos-14/macos-13）→ package-windows（windows-latest，--type msi --win-menu --win-shortcut，version 纯数字点分段）→ release job（needs 三者，tag+GH Release jar+2dmg+msi+COS 全资产+latest-version.txt+VERSION 回写 main）。beta workflow（release.yml）不动（jar-only）。本地验证：yaml parse+逻辑走查（无 actionlint 就 python yaml.safe_load），真实 runner 明早 push 后首验。commit。

### 任务 3（P1）：官网改造（feat/onight-website 分支，**不 push 不部署**——明早用户预览）
按方案 §4：①Hero.tsx 重构：删 channel 切换改平台 tab（macOS/Windows/Linux·CLI），macOS/Windows 大按钮直链 dmg/msi（GitHub Release asset 直链为默认+中文区域探测时 COS 镜像并列「国内镜像」副链）+CLI 折叠命令保留 ②版本号展示：客户端 fetch COS latest-version.txt（失败回落 GitHub API /releases/latest），显示 `2026.08.15 · 8月15日发布` ③CTA.tsx 追加「下载桌面版 →」锚到 Hero ④installation.mdx 修正：删虚构 AppImage/Snap，改真实两渠道（桌面 dmg/msi+Gatekeeper/SmartScreen 首开图文引导；CLI install.sh/ps1+JDK 自动安装）⑤下载页版本号旁注「2026.08 起版本号即发布日期」。视觉复用现有设计（不造新轮子），`npm run build` 本地通过+截图（亮暗）入 report。commit（官网仓）。

### 任务 4：日期版本制主仓侧
README/docs 加一句「2026.08 起版本号即发布日期（YYYY.MM.DD[-beta.N]）」+ VERSION 文件不动（beta.51 冻结点，首个日期版随下次发布）。commit（主仓 release worktree）。

纪律：禁止 kill/sbt run 主实例（assembly 是构建不启动）；每项 Mail 我[RESULT]（hash+要点+验收数据）；遇阻 20 分钟换项。

## prompt-engineer 任务书

今晚批次小任务（entities 仓 ~/.nebflow，main 直接 commit，粒度照旧）。

### 任务 1：release-stable coder 日期版本规则（F4 §3.3-4 唯一规则处）
flows/release-stable/agents/coder/system.md 的 VERSION bump 规则段改日期制：Stable=`YYYY.MM.DD`（tag v 前缀/JAR/dmg 命名同步）；Beta=`YYYY.MM.DD-beta.N`（同日递增 N，次日日期自动前进）；同日多次 Stable 追加 `-2` 序号不覆盖已发布 tag；骨架保留（beta N+1/stable 去后缀逻辑不变，X.Y.Z 换当日日期）。只改版本规则段+必要澄清句，不动 flow 拓扑。附回滚命令。

### 任务 2（可选低优先，判断成本后做）：Nebula onboarding 引导段
~/.nebflow/agents/Nebula/system.md（或其 prompt 文件——自查实际位置）加一小段：收到含 `/onboarding` 的消息时，用 2-3 句话简短自我介绍（不调用工具、不反问），然后提示用户即将出现偏好问卷。修正版设计里问卷由前端固定渲染不经 Nebula，此段只是让打招呼行为稳定。若 system.md 结构不适合加（如该文件非 prompt 本体），报告实际结构再定。附回滚命令。

完成后 Mail 我[RESULT]。不 push 不合并。
