# 8 需求方案 · 汇总导览（4 文档待确认）

> 2026-08-15 · 4 份方案文档全齐，本文是导览与拍板清单。确认后按批次定时交 team 实施。

---

## 一、文档地图

| 文档 | 覆盖需求 | 核心结论 | 工作量 |
|------|---------|---------|--------|
| F1 会话入口+串台 | ①Header 弹窗全条目可点 ②消息串台/更新不及时 | **两需求同根**：批次 B 把 `msg.sessionId`（实为 Nebula 主会话 sid）误读为子会话 id → 点击加载 Nebula 消息（串台实锤）+ team/dag 条目不可点；更新不及时 = popup ChatView 未注册进全局表 | M（2-2.5 天）4 文件改动 |
| F2 多选/自启/Onboarding | ③文件多选 ④开机自启 ⑥首开引导 | **自启 CLI 已完整实现**（launchd 三平台），只差设置面板 toggle；Onboarding 复用 AskUserQuestion 全链路 + 图景落 User.md 零新存储 | F2=M(0.5d) F1=M(1.5d) F3=L(最小 M) |
| F3 Canvas 插件+Hook | ⑤查看器插件化 ⑦Hook 完善 | Canvas 注册表已存在但编译期硬编码 + **ext→itemType 映射后端重复 3 份**（真痛点）；Hook 引擎 8 事件完整接线，缺工程面（零测试/热重载/子 agent 事件/可见性） | P1=M P2=M P3 可选；Hook P0=S P1=M |
| F4 发布体系桌面版 | ⑧桌面打包/Beta 隐藏/日期版本/下载 | **jpackage**（JDK 自带零新栈）；依赖检测 CLI 已有；checkUpdate 字符串比较与日期版零改动兼容；**官网 installation 页的 dmg/exe 说明是虚构占位**（P1 顺带治） | P0=M(1d) P1=L(3-4d) P2 可选 |

## 二、侦察发现的三件"已存在能力"（省工作量）

1. `nebflow autostart` CLI 完整实现（launchd plist 三平台）——需求④只剩 UI 桥接
2. install.sh/ps1 已有 JDK 17+ 自动检测安装——需求⑧的"依赖检测"CLI 渠道现成
3. Onboarding 的首跑检测（`isConfigured`）+ AskUserQuestion 渲染链路完整——需求⑥零新机制

## 三、待你拍板的项

### F2 的 6 条风险项（均附建议，可整体批）
| # | 项 | 建议 |
|---|---|---|
| 1 | 批量删除遇 agent 并发写 | 后端逐项容忍（failed 数组），前端汇总 toast |
| 2 | sbt run 误开自启 | resolveJarPath None 时**硬禁用**（非提示） |
| 3 | 桌面版形态切换后 plist 指旧 jar | 本期就实现 .app 路径形态检测 |
| 4 | Onboarding 打招呼消耗 token | 向导末步明示「完成后 Nebula 将发送第一条消息」 |
| 5 | AskUser 问题非确定性 | 验收只断言「出现卡片+答案进 User.md」 |
| 6 | 老用户升级是否也弹打招呼 | **默认只弹一次带「不用了」**（建议） |

### F4 的四大决策（方案已给结论，确认即可）
jpackage 选型 / P0 无签名+Gatekeeper 图文引导（P2 购证公证）/ 日期制 `YYYY.MM.DD[-beta.N]` 一次性切换（semver 冻结）/ Beta 靠官网撤入口+GitHub prerelease

### F3 的分期取舍
Canvas：P1 协议+内置迁移+三处重复收敛 → P2 外部目录+懒加载 → P3 sandbox（可选，v1 信任模型=本地文件系统）
Hook：P0 补测试+文档（S）→ P1 热重载+SubagentStop/UserPromptSubmit+可观测性

## 四、实施批次建议（确认后挂定时）

| 批次 | 内容 | 预估 | 依赖 |
|------|------|------|------|
| **B1 速赢** | F2-自启 toggle（0.5d）+ F1 串台修复（同根两需求一起）+ Hook P0 测试 | ~2.5 天量 | 无 |
| **B2 体验** | F2-文件多选（最小版 0.5d→全量 1.5d）+ Canvas P1 | ~3 天 | 无 |
| **B3 Onboarding** | F2-F3 引导向导（最小版 1d） | 1-2 天 | 建议在 provider 配置体验稳定后 |
| **B4 发布体系** | F4 P0 手动 dmg（1d）→ P1 CI 三平台+官网改造（3-4d） | 4-5 天 | P1 与发版节奏协调 |
| **B5 长尾** | Canvas P2/P3 + Hook P1 + F4 P2 | 按需 | 前序跑稳 |

## 五、文档全文

F1 `/tmp/feat-f1-session-views.md` · F2 `/tmp/feat-f2-fb-autostart-onboarding.md` · F3 `/tmp/feat-f3-canvas-plugins-hooks.md` · F4 `/tmp/feat-f4-release-desktop.md`
