---
type: fix-report
status: published
date: 2026-10-30
scope: 新建项目流（projectTab.js / workspacePicker.js / WsTasksWorkspaceHandlers.scala / locales zh+en）+ 设备平级设计语言（上升为长期裁定）
---

# 新建项目面板三修批（2026-10-30 09:31 作者报障 + 设计语言裁定）

作者三张现场截图（远端目录弹窗开箱 `Invalid path: \` / 设备选择器下横杆残影 / 设备下拉「本机（这台 Mac） self」特殊首行）+ 原话裁定「所有设备是平等的」（上升为设计语言）。

## 三修结论（根因一句话/落点）

### 修① 远端目录选择器开箱报错

- **根因**：device 模式初始路径发 POSIX 根 `/`（`projectTab.js` openPathPicker）→ Windows 对端 `PathUtil.expandTilde` 内的 `normalizeSeparators` 把 `/` 折成 `\` → `PathUtil.isAbsolute("\")=false` → `os.RelPath` 抛 → `FileTransferAction.handleList` 回 `Invalid path: \`。
- **落点**：
  - `src/main/resources/web/js/projectTab.js` openPathPicker：device 模式 `startPath: pickedPath || '~'`（`~` 由接收端展开为它对家目录）。
  - `src/main/scala/nebflow/gateway/WsTasksWorkspaceHandlers.scala` browseRemoteDirs：additive `resolvedPath` 键透传对端解析后的真实绝对路径（初始 `~` 的面包屑展开点；老帧无该键前端回退原 path，零回归）。
  - `src/main/resources/web/js/workspacePicker.js`：renderResult 优先 `res.resolvedPath`；buildCrumbs/renderResult 上级行/子路径拼接全部按对端分隔符（`[/\\]+` 分段、`sepOf` 拼接、盘符根 crumb = `C:\` 可直达、根处隐上级行）；白名单拒绝（`outside the device share whitelist`）映射为本地化可读态 `workspacePicker.deviceShareDenied`（未共享 + deviceShare.extraPaths 出路），其余错误照旧透传。
- **验收**：对 Windows 对端开箱 = chip + `C: / Users / PC` 面包屑 + 家目录列表，零错误行；拒绝态 = 中文可读句（见 F1 证据截图）。

### 修② 横杆残影

- **根因**：`.dev-menu` author 样式 `display:flex` 压过 UA `[hidden]{display:none}`——关态空菜单以 min-width:300px 玻璃细条残留在设备按钮下方、盖住语义提示行（作者 UI 缩放 ~2x ⇒ 读作 600px「输入框状横杆」；条左的「spinner」= 被玻璃压住的提示行 info 图标）。
- **落点**：`.dev-menu[hidden]{display:none}` 守卫（projectPanel.css）——**已先于本批由 78cd30b0f（2026-10-31 归因批）落在分支**；本批补 playwright 断言钉住（首开/远端选中/选择器往返/菜单开关后四态 display:none + 零占位）。
- **现场说明**：作者实例为守卫合入前的构建，重启部署后即消。

### 修③ 设备平级设计语言（作者裁定，上升为设计语言）

- **落点**（`projectTab.js` + locales zh/en）：
  - 菜单行 = 名称 + 在线点 + 勾选，**无 self 特殊首行/文案、无行间 `self/direct/relay` 标签**（连接方式降级为行 `title` tooltip）；self 显示名 = 花名册本名。
  - 选择器按钮单一 hard-drive 图标、**删除 `is-remote` 分级 class 与 sapphire 着色**；self/远端/缺省同一视觉权重。
  - 提示行 self/远端同一行位：远端「项目将存放在 {device}，由该设备执行；仅登记指针，文件按需查看。」/ self 短句同句式无指针从句——**不点名本机**。
  - 文案中性化：`deviceSelf` 兜底值（本机（这台 Mac）→这台设备 / This Mac→This device）、`createErrSwitchLocal`（换成本机→改用这台设备 / Use this Mac→Use this device）、`deviceMenuFoot` 去 peers 行话；删除 `deviceNoteSelf` 键与元素。
  - 提交线语义零变化：self 选中仍 = 零 device 键（机制面缺省腿）；顺带补首开渲染（原首开设备钮空白到首次点击）。
- **断言**：新 spec `tests/projcreate-device-flat.spec.mjs` F3（行集合/零 .sub/title tooltip/同一 class 形态/图标相等/无分级 class/文案无本机/self 提交零 device 键）+ 既有 `ui-device-faces.spec.mjs` R-A/R-A7 同步改写。

## 审计清单（其他界面 self/local 分级残留 —— 本批只列不改，待后批）

1. `src/main/resources/web/js/locales/zh-CN.js:1862` `neblink.thisDevice: '本机'` —— NebLink 互联页/设置面板 self 段标题（dropbox.js:312 引用）。
2. `src/main/resources/web/js/locales/zh-CN.js:1875-1884` `neblink.updateCard.self: '本机'` + 三张「本机统一更新卡」文案（updateCard.js:135 笔记本图标 self 特殊卡）。
3. `src/main/resources/web/js/locales/zh-CN.js:2378-2380` `messages.deviceServerUnavailable / deviceSendUnavailable` —— 设备会话回落横幅「已切换为本机历史/本机设备身份不可用」。
4. `src/main/resources/web/js/projectTab.js:919-944,974` 面C 项目卡徽标：远端项目带 `.dev-badge`、**self 项目零徽标** = self 分级视觉（本批动它越出「新建项目流」范围；若平级彻底化，可考虑 self 也带本名徽标或全部不带，待作者裁定）。
5. `src/main/resources/web/js/explorer.js`（面B 头部 `.dev-chip`）：远端根常显 chip、self 根无标识——同 4 的分级族。
6. 事实性「本机」文案（非设备分级语义，低优先）：`login.reason.*`/`login.action.*`（:573-597，本机凭据）、`settings.sttApiKeyPlaceholder`（:728）、`askUser.secretHint`（:1454，本机 secrets）、`perception.footerPrivacy/backoffDesc`（:2603,2614）。
7. 已合规面（无需后批）：聊天工具卡设备角标 `utils.js:557 deviceTagHtml` 已把 local/self 字面解析为本机设备名（作者 2026-10-27 令），与平级设计语言同向。

## 验证

- 新 spec `tests/projcreate-device-flat.spec.mjs` 3/3 绿（F1 开箱无错/拒绝态可读、F2 四态无横杆、F3 平级下拉+self 零 device 键）。
- 回归：ui-device-faces 4/4、projcreate-fab 9/9、projcreate-redesign 4/4、pickeruni-unified 23/23、ui-device-faces-shots 1/1 全绿；sbt WsBrowseDeviceLegSpec + ProjectCreatePanelSpec 见批内跑分。
- 证据：`.nebflow/evidence/20261030_projcreate-device-flat/`（F1 开箱零错截图 / F1 拒绝态截图 / F3 平级 self 态截图 + JSON 读数）。
- checkJs 总量 259 < 基线 260，本批触碰文件零新增；闸列出的 bgMergedPanel/plugins/sidebar 三文件为存量漂移（本批零触碰）。

相关：[[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]、[[glossary]]
