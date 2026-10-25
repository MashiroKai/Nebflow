# canvas 胶囊 / 附件卡片 / 感知面板 六件 + 引擎一件 —— 交付报告

- 批次：作者 2026-10-26 01:07 反馈批（七件）
- 工作区：worktree `delegate-nebflow-4180ab95`（分支 `delegate/delegate-nebflow-4180ab95`，未触 main）
- 前沿基线：main HEAD `0d26ed654`（before 截图全部取自该 HEAD 真实资产）
- 纪律核对：纯前端改动刷新即生效（js/css，无构建步骤）；checkJs 260 条 = 基线零新增；全程零回退

## 逐件前后对照

### 1. 附件卡片样式统一（用户侧 att-ref-card ↔ 引擎侧 file-card）

- 改动：`js/popArtifacts.js`（`fileIconStyle` 导出）；`js/reference.js`（import 之；新增 `buildFileLangRefCard`：fc-icon 色块〔文本=meta.typeLabel，色族=extSrc 基础名→回落路径扩展名〕+ fc-name + fc-sub 来源行 + preview；`renderMessageRef` 对 file/document 提前分流 file-lang）；`css/chat.css`（`.att-ref-card.file-lang` 与 `.file-card` 同值 chrome：300px 宽 / 12px 圆角 / `--color-surface` / 11×13 padding / 暗色 border）
- 前后对照（同 DARK 主题、同 chat.css 注入、同 mock 数据）：
  - before：`evidence/before/item1-card-file.png` —— 旧卡：行内小图标 + 文件名 + 右缘 SCALA 文本徽标
  - after：`evidence/after/item1-card-file.png` —— 新卡：引擎同款方形 SCALA 色块 + 文件名 + 路径行
  - document（PDF）同款：`evidence/before/item1-card-document.png` → `evidence/after/item1-card-document.png`
- 钉：`tests/reftag-redesign.spec.mjs` T8b 改钉 fileLang+SCALA(=typeLabel)；harness `card()` 增 fileLang/iconCls、title/badge 回落。ref 族 4 套件 24/24 绿。

### 2. 胶囊标题溢出（任何状态不得出界）

- 改动：`css/split.css` `.cr-capsule` 加 `overflow:hidden`（`.cr-name` ellipsis 原已具备 → 截断+省略号闭环）
- 前后对照（ring 开环态，dark）：`evidence/before/capsule/1-ring-open-dark.png`（main HEAD）→ `evidence/20261023_capsule-six/shots/1-ring-open-dark.png`（本批）——标准 fixture 两貌相同（该 fixture 本不溢出，明证由钉承载）；溢出回归全数由长标题钉覆盖：CR9-1~4 + R0c（42 PASS，唯一 FAIL 为 CR2-5 main HEAD 既有红）
- 钉：`tests/canvas-capsule-ring.spec.mjs` 追加 CR9-1~4 + R0c 双卡钉；ring 套件 42 PASS / 1 FAIL（**CR2-5 材质钉为 main HEAD 既有红**，前批合并 message 已记档「CR2-5 材质=HEAD stash 逐位同败既有基点」，非本批回归）

### 3. 胶囊 <3 卡不能滑 → 按实际卡数退化

- 改动：`js/canvas.js` ringState 增 `lastDir`；`layout()` n===2 时非游标胶囊按 lastDir 落位；滚轮换选处 `ringState.lastDir = dir`；`openRing` 初始化 `lastDir:1` —— 两卡也成环（滚/切/甩全可用），单卡自然无环
- 钉：CR9 系四钉 + R0c 双卡钉全绿；`canvas-capsule-six` 19/0、`canvas-cardhead-capsule` 29/0

### 4. 感知弹窗锚点错位 → 从入口按钮锚定弹出

- 改动：`js/perception.js` `positionPanel` 重写：锚 `#perception-btn`（右缘对齐按钮右缘 + 顶缘贴按钮下缘 +8px；下方越界整体翻上方；双侧 8px 内缝钳制；按钮缺席回落旧 top56/right16）；open 后挂 window resize 重钳（`closePanel` 移除）；`renderPanel` 尾部与手风琴点击后重钳
- 前后对照：before `evidence/before/item456-panel.png`（main HEAD：面板固定 top-right，与入口无关）；after `evidence/after/item456-panel.png`（面板锚按钮：右缘对齐、下方放不下 → 翻按钮上方、内缝钳制——同既有弹出菜单姿势）
- 钉：`tests/mail-perception.spec.mjs` P1b 锚定钉（右缘对齐 ≤2px、翻上方、零 pageError）绿

### 5. 感知面板风格对齐官网 mystand.space

- 改动：`css/stand-tokens.css` 追加官网尺度档（`--st-fs-header:15px / --st-fs-row:13px / --st-fs-btn:12.5px / --st-fs-label:11.5px / --st-fs-meta:11px / --st-radius-window:20px / --st-radius-card:10px / --st-radius-btn:8px / --st-icon-chip:28px / --st-row-pad:8px 10px / --st-btn-pad:7px 14px / --st-row-gap:10px`——逐值转录自 mystand.space `.nfd` chunk 实抓，存档 `evidence/mystand-nfd-chunk.css`）；`css/perception.css` 面板脸+配置卡对表（窗体 20px 圆角、头 14/16 + 标题 15px、行卡 10px 圆角 + 8×10、图标座 28px 圆形、按钮 12.5px/500 显式）
- 对照：before/after 面板整幅见件 4 两图（after 头部字号/行高/圆角/留白收紧为官网档）；`evidence/after/mail-perception-1-panel.png` 为新字号近景（同截图见 worktree evidence 拷贝）
- 钉：mail-perception 套件 8/8 绿（含零统计行、两问配置卡）

### 6. 删占位：感知面板只留邮件

- 改动：`js/perception.js` 删 `reservedEntryHtml` 两处调用 + 函数本体 + `:not(.reserved)` 选择器；`css/perception.css` 删 reserved 相关 3 段；`locales/zh-CN.js` / `en.js` 各删 3 键（reservedFile/reservedCalendar/reservedTag）
- 前后对照：before `evidence/before/item456-panel.png`（文件/日历「即将推出」占位在列）→ after `evidence/after/item456-panel.png`（零占位，只承载真实条目）
- 钉：P1 改钉零 reserved + 无「即将推出」绿

### 7.（引擎件）心跳进程与感知合并 + boot-smoke 必须过

- 现状判读：感知现为**网关进程内** 4 loops（poll/sweep/digest/spool；GatewayMain 启动链内联 boot），并非独立 OS 进程；「心跳进程」= `#daemon-btn` 的 DaemonService 面板（daemons.json 为空）。架构歧义已 AskUserQuestion 问作者（非阻塞），答案未到；本批先交付**对任何解读都有益且零回退**的 boot 加固，merge 终形态待作者定夺（见文末待决）
- 改动（boot-lesson 加固）：
  - `PerceptionService.scala`：新增 `bootGate: Ref[IO,Boolean]` 单飞闸——boot 可能从两条腿并发到达（GatewayMain 端口门后的脱离 fiber + routes 侧 ensureBoot 兜底），`Ref.modify` 原子仲裁只放第一腿进 `doBoot`（双 boot = 双 loops 是本单例最重回归面，从根上关死）；`ensureBoot` 闸内另一腿在途时短退避（50ms）重探一次
  - `GatewayMain.scala`：`perceptionBoot` 改 `perceptionBootDetached`——**移出关键启动链**，端口门放行后才以脱离 fiber 拉起。教训实体：sweepLoop 构造期 SOE 曾越过 handleErrorWith 把整条启动链带走（2/2 listener 拒起）；现任何感知 boot 故障（含 Error 级）最多带走这根 fiber，listener 必起；routes 侧 `ensureBoot` 幂等兜底；sweep/digest/spool 语义逐字不变（感知能力零回退）
- 验证：`sbt compile` rc=0；`PerceptionCoreSpec` **34/34 绿**；`sbt assembly` rc=0（jar=stand-assembly-2026.10.21-beta.2.jar, hash 4c8b1c8a）；**boot-smoke PASS ×2**（`BOOT-SMOKE: PASS jar=stand-assembly-2026.10.21-beta.2.jar port=8089 boot_ready=5s/4s checks=11 (portal+health+ws-or-degraded+teardown)`，teardown 零残留；证据 `evidence-branch-assembly-boot-smoke.log`）；--keep-home 复跑在冒烟实例 `logs/stand.log` 实证 **`perception daemon booted (poll/sweep/digest/spool loops live)`** —— 脱离 fiber 在端口门后真起、四 loops 全活（冒烟临时 home 已清理）
- 单飞闸注：singleton boot 路径未加并发 spec（boot 硬编码 `PerceptionStore.defaultRoot`——spec 化会触碰真实用户信箱盘；以编译+评审背书）

## 测试与门禁读数（本批全量）

| 门禁 | 读数 |
|---|---|
| checkJs | 260 条 = 基线，零新增 |
| mail-perception | 8/8 绿（含 P1b 锚定新钉） |
| canvas-capsule-ring | 42 PASS / 1 FAIL（CR2-5 材质 = main HEAD 既有红，非本批） |
| canvas-capsule-six | 19/0 绿 |
| canvas-cardhead-capsule | 29/0 绿 |
| reftag+quote+msgmenu+fwdguard | 24/24 绿 |
| sbt compile | rc=0 |
| PerceptionCoreSpec | 34/34 绿 |
| sbt assembly | rc=0（jar hash 4c8b1c8a） |
| boot-smoke | **PASS ×2**（boot_ready 5s/4s，checks=11，teardown 零残留；四感知 loops 实证起） |

## 待决（非阻塞）

1. **件 7 merge 终形态**：作者问询在途。两案：A=感知注册为 DaemonService（心跳面板）托管条目（面板可见启停，进程仍单进程承载）；B=仅面板入口合并（daemon 面板加感知入口，无托管语义）。本批交付的 boot 单飞+脱链对两案皆为基础件，落地任一案均为增量。
2. CR2-5 材质钉既有红：main HEAD 基点问题，建议另开小批处理。
