# Apple 系统听写（FN 长按 Dictation）集成进 Nebflow 输入框 — 可行性分析

> 域：Nebflow · 文件：`20260830_apple-dictation-integration.md` · 类型：阶段文档（只分析未改码，完成即冻结）
> 作者裁定（2026-08-30 09:38）：苹果平台用系统听写（FN 触发、结果直入对话框）；其他平台保持现有 STT 体系。

---

## 0. 结论速览（TL;DR）

| 项 | 结论 |
|---|---|
| **webview 技术栈定论** | **不存在 webview**。桌面分发 = jpackage 打包的 Java 服务端（fat jar + jlink runtime），启动 `--server` 后 `openBrowser` 执行 `open <url>` 打开**系统默认浏览器**。UI 实际运行在 Safari/Chrome 里（本机默认 Safari 26.4，Chrome 152 并存）。无 JCEF / 无 JavaFX WebView / 无 Electron / 无 Tauri。`NebflowIsland`（DerivedData 残留）是 2026-04 的动态岛提醒小工具（SwiftUI+EventKit，无 WebKit），源码已随 04-19 基线整理删除，与主 UI 无关 |
| **复制中转运根因** | macOS 听写只把文本插进「聚焦的可编辑目标」（NSTextInputClient）。**长按 FN 那一刻输入框没有焦点**（用户确认：无光标）→ macOS 26 进入「独立转写浮窗」模式（带复制按钮）。这是 OS 层行为，不是 Nebflow bug |
| **推荐方案** | **平台条件分支，零原生改动起步**：macOS → 前端保证「听写前焦点在输入框」+ 处理 `beforeinput`/`insertDictation` 事件 + 听写插入后同步 UI 状态；非 macOS → 维持现有 STT（Web Speech / 云端 MiMo）不变。备选（未来）：macOS 26 `SpeechAnalyzer` 原生 helper 直接捕获本地识别，绕开「焦点」依赖 |
| **主要代价** | 方案 A（推荐）：纯前端，~1 天级；方案 D（SpeechAnalyzer helper）：原生组件，数天级，需 macOS 26+ |

---

## 1. 现状核实（读码确认，全部有据）

### 1.1 桌面分发形态：jpackage 服务端 + 系统默认浏览器，无内嵌 webview

- `packaging/build-dmg.sh`：`jpackage --main-class nebflow.Main --arguments --server`，runtime 由 `packaging/jlink-modules.txt` 裁剪（`java.base,java.desktop,...`——**没有 javafx.web / jdk.jfr / CEF**，纯 JVM 模块）。
- `src/main/scala/nebflow/gateway/GatewayMain.scala:76-81`：`openBrowser` 在 macOS 执行 `Seq("open", url)` → 打开系统默认浏览器。
- `/Applications/Nebflow.app` 实查：`Contents/app/nebflow-assembly-1.4.1-beta.51.jar` + `Nebflow.cfg`（`arguments=--server`）+ jlink runtime。**无任何内嵌浏览器引擎**。
- 本机默认浏览器：`LaunchServices` 无 http/https handler 覆盖 → **系统默认 = Safari 26.4**；Chrome 152 已安装并运行。
- 用户原话（2026-08-30）："那个是 mac 的全局的 长按 FN 键触发。说明 mac 应该是有个本地语音识别的模型之类的"——确认听写是 macOS 系统级全局功能，浏览器内无感知。

### 1.2 输入框实现：标准 `<textarea>`，一等可编辑目标

- `src/main/resources/web/index.html:257`：`<textarea id="input" rows="1" placeholder="Type a message..." autocomplete="off">` —— 原生 textarea，**不是** contenteditable、不是自定义编辑器。
- `web/js/input.js`：`input` 事件只做自动增高（L1166-1169）、`compositionstart/end` 维护 `view.composing`（L1187-1188）、Enter 发送（L1318-1325）、方向键历史。**没有** `beforeinput` 拦截、没有 `preventDefault` 掉系统插入。
- **结论：textarea 本身是 macOS 听写可以直接插入的目标**——只要它有焦点，Safari/Chrome 里 FN 听写结果应能直插。

### 1.3 现有 STT 体系（作者要求"其他平台保持"的现状）

- `web/js/voiceEngine.js`：双模式——① 云端 STT（`state.stt.sttConfigured`，WS `transcribe` 代理，用户配过小米 MiMo）；② 浏览器 Web Speech（默认零配置，`webkitSpeechRecognition`）。
- 麦克风气泡 UI：`mic-bubble-spec.md` + `web/js/micOrb.js`（9 态 WebGL 液态光球，输入栏左端按钮）。
- STT 配置：Settings → STT（advance 折叠面板，`sidebar.js renderSttSection`），落盘 `~/.nebflow/stt-config.json`；后端 `SttService` 双协议（MiMo chat / OpenAI multipart）。

### 1.4 用户实测确认（AskUserQuestion，2026-08-30）

- 触发方式：**macOS 全局长按 FN**（系统级，非浏览器内）。
- 按下 FN 那一刻：**输入框内没有光标**（没有焦点）。
- 现象：**独立浮窗**承载识别文本，需手动复制中转——与「macOS 听写找不到可编辑插入目标 → 独立转写面板」的已知行为完全吻合。

---

## 2. 根因分析：为什么现在要"复制中转"

![根因决策图](/tmp/dictation_flow.svg)

### 2.1 macOS 听写插入机制（OS 层设计，公开资料+系统行为）

- macOS 听写（DictationIM / Tahoe 的 SpeechRecognitionCore）通过 **NSTextInputClient** 协议把文本交给当前**聚焦的可编辑目标**（`insertText:replacementRange:` / `setMarkedText:`），与 IME 合成同一通道。
- 若按下听写键时**没有有效的可编辑目标**（焦点不在文本框），macOS 26 会显示**独立转写浮窗**（带复制按钮）——文本停留在浮窗内，需要手动复制。这正是作者遇到的场景。
- 已知佐证（同类问题）：
  - WebKit bug 261764：macOS Safari 桌面端听写会触发 `compositionstart/update/end` + `beforeinput/input`——说明 WebKit 对听写插入支持完整。
  - chromium 40239051「Chromium does not properly implement NSTextInputContext」：Chromium 系对 NSTextInputClient 桥接不完整，Electron 应用（craft-agents-oss#466、winit#4666）出现听写文本丢失/延迟的已知缺陷——**webview 系风险点**（本项目无 webview，仅 Chrome 浏览器，且 Chrome 直插 textarea 一般可用，但 live 文本可能不显示）。

### 2.2 因此根因 = 「FN 按下时刻输入框无焦点」，而非「输入框不被支持」

- 输入框是标准 `<textarea>`，浏览器（Safari/Chrome）下聚焦时听写**应当**能直插。
- 我们（前端）**无法**在 FN 按下瞬间主动抢焦点——FN 是系统级快捷键，浏览器收不到这个 keydown。
- 用户习惯流：「打开对话框 → 直接长按 FN」→ 焦点还停在页面/body → macOS 认为没有插入目标 → 浮窗+复制。

---

## 3. 方案空间（逐条可行性 + 代价）

### 方案 A：让输入框成为「一等可编辑目标 + 焦点引导」，FN 听写直插（推荐起步）

- 可行性：**高**。textarea 已是原生可编辑元素，无需改输入框实现。
- 前端改动：
  1. **焦点卫生**：macOS 平台下，进入聊天视图/发送后/点击会话后把焦点还给 `#input`（input.js 已有大量 `input.focus()` 调用，需补"发送后回焦"等场景）；在输入区附近加轻提示「在 Mac 上可直接长按 FN 听写」。
  2. **事件适配**：监听 `beforeinput` / `input` 事件，识别 `inputType === 'insertDictation'`（Safari 发 composition 系、Chromium 发 insertDictation），用于：a) 听写插入后同步自动增高/保存草稿；b) 让麦克风气泡短暂显示 listening 态（可选联动）。
  3. **兜底**：若未来出现 webview 壳（JCEF/JavaFX/WKWebView），需按 webview 的 NSTextInputClient 桥接能力验证（Chromium 系有已知缺陷）——当前无 webview，不适用。
- 代价：纯前端，~1 天级；不改后端、不改打包。
- 局限：**无法强制 macOS 在无焦点时直插**。若用户在聊天区点击后直接长按 FN，OS 仍会走浮窗——所以需要「焦点引导」（见 §4.1）。

### 方案 B：Web Speech API 路线（Safari webkitSpeechRecognition = Apple 引擎）

- 现状：`voiceEngine.js` 已有 Web Speech 默认路径；Safari 的 `webkitSpeechRecognition` 后端 = Apple 识别引擎（与系统听写同源准确度）；Chromium 系 Web Speech 依赖 Google 服务（国内可用性存疑）。
- 可行性：**已存在，零改动**——但触发方式是「点击麦克风气泡」，不是 FN 长按。它是现有 STT 体系的一部分（作者说"其他平台保持现有 STT"，苹果平台要的是 FN 直插）。
- 定位：**不是替代 FN 听写的方案**，而是保留的兜底通道（气泡点击 → Apple 引擎 → 结果经 voiceEngine 回调进输入框，这个通道已经打通且可直接复用 UI）。
- 代价：无（已实现）；若未来 webview 壳出现，Chromium 系 WKWebView 里 webkitSpeechRecognition 可能不可用（WebKit bug 261764 提及 iOS 侧差异）——预留判断即可。

### 方案 C：原生桥接捕获听写结果文本

- 结论：**不可行**。NSSpeechRecognizer 是命令词识别（另一回事）；macOS 没有公开 API 捕获系统听写文本（听写结果只经 NSTextInputClient 送给聚焦目标，无订阅/读取通道）。这与作者判断一致（"大概率没有，给结论"）。
- 除非：未来做一个原生 helper 直接调用系统听写会话（私有 API，不可上架、不稳定）——**不建议**。

### 方案 D：macOS 26 `SpeechAnalyzer` 原生 helper（作者认可方向，备选/未来）

- macOS 26（Tahoe）公开了 `SpeechAnalyzer` / `SpeechTranscriber` 框架（WWDC25）：**系统听写同款本地识别引擎**，公开 API，支持中文，首个语言模型可下载。
- 形态：一个小型 macOS 原生组件（菜单栏 helper 或随应用分发的可执行文件），自捕获麦克风 → SpeechAnalyzer 本地识别 → 经 WS/HTTP 把文本注入 Nebflow 输入框（或直接注入聚焦控件）。
- 可行性：**技术可行**（公开 API + 本地引擎 + 现有 WS 通道可复用）；但引入原生构建链（Swift + 签名/公证 + 麦克风权限描述），与现有纯 JVM+浏览器的分发形态冲突，代价数天级。
- 用户原话："我感觉我就是这个意思，利用好系统本地的资源。而且我实测效果很好。"——用户认可此方向，但作为**备选**列示，先行方案 A（零原生改动）。

### 方案空间小结

| 方案 | 可行性 | 原生改动 | 前端改动 | 定位 |
|---|---|---|---|---|
| A. 焦点引导 + insertDictation 适配 | 高 | 无 | ~1 天 | **推荐起步**（苹果平台主路径） |
| B. Web Speech（Safari=Apple 引擎） | 已存在 | 无 | 无 | 保留的兜底/非苹果平台 |
| C. 原生桥接捕获听写文本 | **不可行** | — | — | 排除（无公开 API） |
| D. SpeechAnalyzer 原生 helper | 中高 | 数天级 | 小 | 备选/未来（macOS 26+，绕开焦点依赖） |

---

## 4. 推荐方案：平台条件分支落到现有体系

### 4.1 苹果平台（macOS）→ 系统听写直插

1. **焦点引导（核心）**：macOS 下保证听写触发时输入框大概率有焦点——
   - 发送消息后、切换会话后、点击输入区周边时回焦 `#input`（input.js 补 `input.focus()`）；
   - 输入栏内（或 orb 附近）增加可忽略的提示文案（i18n）："macOS：将光标置于输入框后长按 🌐/FN 可直接听写"。
2. **insertDictation 事件适配**：监听 `beforeinput`/`input` 的 `insertDictation`/composition 事件，听写插入后触发自动增高 + 草稿保存（复用现有 input 监听，仅补类型判断，避免被历史/快捷逻辑吞掉）。
3. **麦克风气泡共存**：orb 语义不变（idle/listening/...）；听写直插是可选的并行通道，不与 orb 冲突。可选增强：检测到 insertDictation 时 orb 短暂闪 listening 以给出反馈（纯前端，不强制）。
4. **Settings STT advance 面板**：新增一行平台提示（macOS 显示"系统听写（FN）直插可用"；非 macOS 不显示）——不新增配置项，纯展示 + 引导；云端 STT 配置原样保留。

### 4.2 其他平台 → 现有 STT 体系不变

- voiceEngine.js 双模式（Web Speech 默认 + 云端 MiMo 可配）、orb 9 态、Settings STT 面板全部不动。
- 仅当 `navigator.platform`/UA 判定为 Mac 时启用 §4.1 的引导与事件适配。

### 4.3 落地文件范围（预估）

| 文件 | 改动 |
|---|---|
| `web/js/input.js` | 补发送后回焦；insertDictation/composition 事件识别（听写插入后的增高/草稿联动） |
| `web/js/i18n.js` + `locales/{en,zh-CN}.js` | macOS 听写引导文案 key |
| `web/js/sidebar.js` | STT advance 面板平台提示行（macOS 判定显示） |
| `web/js/micOrb.js` | 可选：insertDictation 触发短暂 listening 态 |
| 后端 / 打包 / 配置 | 零改动 |

---

## 5. 验收条件（可断言）

**冒烟测试（硬性条件）**
1. `sbt run`（或打包版）启动 → Safari 打开 `http://localhost:8080` → 光标点入输入框 → 长按 FN 说一句话 → **断言：文本直接进入 `#input`（`document.activeElement === #input` 且 value 非空），无独立浮窗出现**；按 Enter 可正常发送。

**端到端 / 行为验证**
2. 同一流程在 Chrome 152 下重复 → 文本直插 `#input`（Chromium 对 textarea 的 insertText 路径）→ 发送成功；若 Chrome 出现 live 文本不显示（已知 NSTextInputContext 缺陷）但最终插入成功，记录为已知限制并给出提示。
3. 非 macOS（Linux/Windows 或 UA 伪装非 Mac）→ Settings STT 面板不显示 macOS 引导行；voiceEngine 走原有 Web Speech / 云端逻辑（回归：`useCloud()` 分支、orb 9 态、MiMo 转写全部保持原行为）。
4. `beforeinput`/`input` 事件：听写插入后 `input` 事件触发 → 自动增高执行、草稿保存不丢字（对比：发送前后 value 一致性）。
5. 焦点卫生：发送消息后 `document.activeElement === document.querySelector('#input')`（Playwright 断言）；切换会话后焦点回输入框。
6. 无 JS 错误：听写插入过程中 console 无未捕获异常；orb 状态不卡死（若实现 insertDictation→listening 联动，需在 5s 内回落 idle）。

**备选方案 D（若实施）**
7. macOS 26+ 上 SpeechAnalyzer helper 可获取麦克风权限、首个语言模型下载后离线可用；WS 注入文本到 `#input`；非 macOS 平台 helper 不启动；打包含 Swift 组件 + 麦克风用途描述。

---

## 6. 风险与开放问题

| 风险 | 说明 | 缓解 |
|---|---|---|
| Chrome 听写 live 文本不显示 | chromium 40239051：NSTextInputContext 实现缺陷家族 | 验收条件 2 记录已知限制；主推 Safari |
| FN 时刻无焦点仍会浮窗 | OS 层行为，前端无法强制 | 焦点引导 + 文案提示；长远可上方案 D |
| 未来 webview 壳（Tauri/WKWebView） | Chromium 系 NSTextInputClient 桥接有缺陷（Electron 案例） | 预留：壳出现时按 webview 技术栈专项验证（JCEF/JavaFX 需看其文本输入桥接） |
| 方案 D 引入原生链 | Swift 组件 + 签名/公证/权限描述，与纯 JVM+浏览器分发冲突 | 列为备选，独立立项再评 |

---

## 7. 参考来源

- WebKit bug 261764（iOS dictation 不触发 composition；macOS Safari 桌面端触发完整 composition 事件序列）
- Chromium issue 40239051（[Apple] Chromium does not properly implement NSTextInputContext）
- craft-agents-oss issue #466 / rust-windowing winit issue #4666（Electron/原生壳听写 setMarkedText/selectedRange 缺陷）
- Apple WWDC25：SpeechAnalyzer/SpeechTranscriber（macOS 26 公开本地识别框架，系统听写同源引擎）
- MDN Web Speech API（Chrome 系语音识别走 Google 服务器，离线不可用）
