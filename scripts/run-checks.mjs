#!/usr/bin/env node
// Shared checkout-only checks. Runtime, browser, JVM and installed-plugin gates stay separate.
import { spawn } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isAbsolute, resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const nodeCheck = (name, script, profiles = ['static', 'frontend'], options = {}) => ({
  name, argv: [process.execPath, `scripts/${script}.mjs`], profiles, timeoutMs: name === 'js-types' ? 150_000 : 120_000,
  ...options,
});

// 设计规范单源外置（single-source-rehome 批 2026-11-18）：规范正文（毛玻璃 / 输入面 /
// 面板 / 按钮 / 披露 / 文案 六篇 + 几何刻度账本）唯一一份在 stand-dev 插件包内，本仓
// 不保留第二份拷贝；这些闸靠 scripts/lib/design-spec-source.mjs 定位它。规范面缺席
// （CI 检出、未装插件）时闸以退出码 2 结束 —— 标 envSkip 后这里记为 SKIP（不假绿成
// PASS，CI 仍能跑完）。
const SPEC_EXTERNAL = { envSkip: true };

const scriptTests = readdirSync(new URL('./tests/', import.meta.url))
  .filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/tests/${name}`);
if (!scriptTests.length) throw new Error('No script unit tests found; refusing broad test discovery');
// 本目录即收集面（平目录，不递归）：2026-11-19 普查收编批曾把三个普查红文件停在
// scripts/tests/pending/ 躲避收集；作者裁定「改写重锚后注册、不许删」后，三条
// （canvas-interactions-repair / canvas-search-batch-review / forms-repair）已重锚回本目录
// 并随本腿注册，pending/ 随之删除。
const weixinTests = readdirSync(new URL('../tools/weixin-sidecar/', import.meta.url))
  .filter(name => name.endsWith('.test.mjs')).sort().map(name => `tools/weixin-sidecar/${name}`);
if (!weixinTests.length) throw new Error('No weixin-sidecar unit tests found; refusing broad test discovery');

// Membership is declared once here, not copied into Make or CI command lists.
export const CHECKS = [
  nodeCheck('test-hermetic', 'check-test-hermetic', ['static']),
  nodeCheck('css-structure', 'check-css-structure'),
  nodeCheck('floating-clearance', 'check-floating-clearance'),
  nodeCheck('floating-discovery', 'check-floating-discovery'),
  nodeCheck('mobile-floating-inventory', 'check-mobile-floating-inventory'),
  // no-clip floating（作者令 2026-11-18 22:01）：浮层/卡四边完整可见（顶部尤其）+
  // 文本不截断 + 高度上限必配可滚轴。既有三道 floating-* 闸是「声明式」（clearance 契约 /
  // positioned selector 棘轮 / 清册声明），不查裁切组合 ⇒ 本条补位其能力缺口，非第二套体系。
  // 静态腿自带具名台账（scripts/noclip-floating-baseline.json，只许缩短、新增须作者裁定）；
  // 运行时几何腿在真全屏手机形态下测视口四边 + 自裁 + 祖先 overflow/clip-path/contain。
  nodeCheck('noclip-floating', 'check-noclip-floating'),
  { name: 'noclip-floating-geometry', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/noclip-floating-geometry.spec.mjs'] },
  nodeCheck('design-tokens', 'check-design-tokens'),
  nodeCheck('message-style', 'check-msgstyle-single-source'),
  nodeCheck('message-item-unify', 'check-message-item-unify'),
  // notice-unify 批（2026-11-18，作者令「形成 lint 检查…统一到 2-3 种风格和类型的通知」）：
  // 通知族只允许声明的三型（toast 瞬时浮动 / banner 常驻横幅 / inline 就地提示）；
  // 未声明的通知类名或非单源的 toast/banner 样式定义即红（第四种风格 / 内联手写通知）。
  nodeCheck('notice-unify', 'check-notice-unify'),
  // 待办收件箱 UI 批（作者令 2026-11-21 ①「次级面板的输入框抽成标准组件复用，并加
  // 检查」）：次级 LLM 输入框只有一个装配点（js/input/secondaryComposer.js）——每个
  // 消费面（ask / 待办详情页回复条 / 快速决策卡页悬浮控件带）都必须走它，自拼
  // textarea + 发送键即红；造型只许令牌、发送键只许 ds-turn-btn--send。仓内规范正文
  // 落仓前 S6 打印「接口预留」事实行，落仓后自动接管判据。
  nodeCheck('secondary-composer', 'check-secondary-composer'),
  nodeCheck('chat-ui', 'check-chat-ui'),
  { name: 'chat-ui-computed', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/chat-ui-computed.mjs'] },
  { name: 'mobile-canvas-hit-clearance', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-canvas-hit-clearance.spec.mjs'] },
  // canvas 底部控件行「可见且可点」闸（作者报障 2026-11-18：左下两钮 + 中间三点被
  // 手机 dock 整片盖住）。既有 mobile-canvas-hit-clearance 主动跳过不可点控件、
  // 既有 mobile-canvas-toolbar-layout 长期红着没人当闸 ⇒ 这类缺陷在闸下不可见。
  { name: 'canvas-controls-dock-clearance', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/canvas-controls-dock-clearance.spec.mjs'] },
  // canvas 底部控件带**全族逐钮命中**闸 + 负控（作者令 2026-11-19）：11-18 把左下两钮 /
  // 中页点条 / 右气泡钮收进底锚后，11-19 新增的审阅全量动作带 `.nf-dv-allbar` 漏在带外
  // ——流内末件贴视口底 ⇒ 整条落进 #mobile-dock，看不见也点不到。上面那道闸只跑文档画布
  // 页（不含审阅面），且它是「逐件过判据」不是「族清单覆盖率」。本闸把族清单（唯一真源 =
  // css/mobileLayer.css 的同名注释块）编成 BAND_FAMILY，逐钮取中心点 elementFromPoint，
  // 并带**负控**（撤掉本次修复 ⇒ 同一判据必红，否则判据对缺陷不敏感 = 闸自己红）。
  { name: 'canvas-band-hittability', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/canvas-band-hittability.spec.mjs'] },
  // 手机层 canvas 页「内容区恒非空」闸 + 负控（作者令 2026-11-19）：审阅页签收口时
  // closeTab 把邻位签激活，邻位可能是面板签（PANEL_TAB_IDS）⇒ activeDocId() 为 null
  // ⇒ host 无 pane 可收编、空态又因 docs 非空而 hidden ⇒ 整片空白（没有 splash 可等）。
  // 本闸用真产品路径搭同一失效位形，判据 = 内容区可见 pane ≥1 + 文本非空 + 中心点命中
  // 在 pane 内；负控（中和自愈闸）必须复现空白面，否则判据对该缺陷不敏感 = 闸自己红。
  { name: 'canvas-tab-handover', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/canvas-tab-handover.spec.mjs'] },
  { name: 'mobile-decision-preview-occlusion', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-decision-preview-occlusion.spec.mjs'] },
  // 滚域末端净空单源批（2026-11-18 作者令「滚到底底部内容必须完整显示」）：17 个
  // 全屏页逐页 E1–E5（过底 / 末行 ≤ dock 上缘 − 8 / 净空在内容级且 = token /
  // token 非零 / dock 带内无卡死件）+ E0 静态单源。改到哪页漏净空即红。
  { name: 'mobile-scroll-end', profiles: ['frontend'], timeoutMs: 240_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/mobile-scroll-end.spec.mjs', '--workers=1'] },
  { name: 'media-gallery', profiles: ['frontend'], timeoutMs: 120_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/mobile-image-gallery.spec.mjs', 'tests/media-click-unify.spec.mjs', '--workers=1'] },
  // 账户栏整件退役（作者令 2026-11-18）**渲染级**闸：真实形态（手机全屏设置页 +
  // 桌面设置页 + 个人主页）断言该栏零命中；源码级同判据 = check-panel-uniform。
  { name: 'settings-account-removed', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/settings-account-removed.mjs'] },
  // 媒体查看器缩放批（2026-11-18 作者令）：手机捏合/平移 + 桌面滚轮锚点/触控板 + 视频，
  // 以及「既有手势未被吃掉」的回归面。纯状态机/闭包边界的单测在 script-tests 的
  // scripts/tests/media-zoom.test.mjs（static 档）。
  { name: 'media-viewer-zoom', profiles: ['frontend'], timeoutMs: 240_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/media-viewer-zoom.spec.mjs', '--workers=1'] },
  // 遮罩点按关闭回归闸（2026-11-18）：#7 缩放批引入的间歇红 —— 平移→关→重开之后第一次
  // 点背景关不掉（原文是用 160ms 时间窗判「刚拖完」，重开后的点背景被窗吞掉）。
  // 现在把「拖动松手→关→重开→点背景」放进同一 JS 任务 ⇒ 与机器快慢无关；桌面 + 手机
  // 全屏页两形态都跑（tests/media-backdrop-close.spec.mjs）。
  { name: 'media-backdrop-close', profiles: ['frontend'], timeoutMs: 240_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/media-backdrop-close.spec.mjs', '--workers=1'] },
  nodeCheck('bubble-side', 'check-bubble-side'),
  // 溢出转待办批（2026-11-18 作者令）：判据单点 / 帧构造单点 / 模板单点 / 定位面
  // 不交叉 / 不新增通知族——「待办已答而原卡仍可作答」的不对称态在源码面就不成立。
  nodeCheck('ask-todo-lifecycle', 'check-ask-todo-lifecycle'),
  nodeCheck('self-explaining', 'check-self-explaining'),
  nodeCheck('badge-words', 'check-badge-words'),
  nodeCheck('circular', 'check-circular'),
  nodeCheck('fixture-secrets', 'check-fixture-secrets'),
  nodeCheck('api-routes', 'check-api-routes'),
  nodeCheck('provider-panel', 'check-provider-panel'),
  // 统一模型不可用卡批（2026-11-18）：一套 kind（InteractionKind.ModelUnavailable）+ 一套
  // 拼帧点，全槽共用（small-llm/kernel/node/dispatcher/subagent）；切换就地写回槽
  // model.preferred 并重试原请求；卡内「测试 LLM」真实最小调用；300s（可配置）回报根会话；
  // fail-closed 缺省不得被本批翻面。前后端两面都断言 ⇒ static + frontend 双 profile。
  nodeCheck('model-fallback-card', 'check-model-fallback-card'),
  nodeCheck('freeze-state', 'check-freeze-state'),
  nodeCheck('flat-buttons', 'check-flat-buttons', ['static', 'frontend'], SPEC_EXTERNAL),
  nodeCheck('glass', 'check-glass-unify', ['static', 'frontend'], SPEC_EXTERNAL),
  { name: 'glass-runtime', profiles: ['frontend'], timeoutMs: 300_000,
    argv: [process.execPath, 'scripts/check-glass-runtime.mjs'] },
  // 边框权重闸（dark-border-weight 批 2026-11-18）：暗档「边框太明显」——
  // 同一条白细边在亮底 1.03/ΔE00 0.89、在暗底 3.24/ΔE00 27.11；本闸量暗档绝对
  // 上限 + 暗亮同权重 + 玻璃细边单源 + 近白字面量台账。B3/B4 纯静态，B1/B2 需渲染。
  nodeCheck('border-weight', 'check-border-weight'),
  nodeCheck('freeze-silence', 'check-freeze-silence'),
  nodeCheck('freeze-replay', 'check-freeze-replay-silence'),
  nodeCheck('canvas-hint', 'check-canvas-hint-tokens'),
  nodeCheck('canvas-buttons', 'check-canvas-buttons'),
  // canvas 保存失败通知批（2026-11-18 P0「老是出现 canvas 保存失败的通知」）：
  // 静态腿钉「跨刷新去重 / 正文逐字 / 可执行下一步 / 可关闭」四条声明不变量。
  nodeCheck('canvas-save-notice', 'check-canvas-save-notice'),
  // canvas 席位静默接管批（2026-11-19 作者裁定「直接静默吧」；动机「现在通知很烦」）：
  // 「静默 + 不丢」两条同时成立 —— 服务端申请即授予（无非抢占栅栏）、定向通知被顶下
  // 写者、失权端零通知落本机草稿 + 事后取回入口；顺带钉活性惰性腿已整体移除。
  // 行为腿另见 scripts/tests/canvas-seat-draft.test.mjs（在 script-tests 里跑）。
  nodeCheck('canvas-seat-draft', 'check-canvas-seat-draft'),
  { name: 'canvas-save-failure-dedup', profiles: ['frontend'], timeoutMs: 180_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/canvas-save-failure-dedup.spec.mjs', '--workers=1'] },
  { name: 'canvas-button-geom', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/canvas-button-unify-geom.spec.mjs'] },
  { name: 'canvas-button-diff', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/canvas-button-unify-diff.spec.mjs'] },
  // 手机看板页展开档「引用」钮闸（canvas-tab-reference 批 2026-11-18）。为什么必须是
  // 真手指路径：该面改前是「看起来在、手指点不到」的死控件 —— 只读 DOM 的断言对
  // 改前形态（死控件 / 装饰符箭头）会全绿。本闸打在 touchscreen tap → composer 出
  // 引用 chip → 提交帧 refs 上（另含无 composer 明确提示 / 不误触 / 桌面不回归）。
  { name: 'canvas-tab-reference', profiles: ['frontend'], timeoutMs: 300_000,
    argv: [process.execPath, 'tests/canvas-tab-reference.spec.mjs'] },
  // 桌面端 canvas 页签右键「在访达/资源管理器/文件管理器中打开」闸
  // （canvas-reveal-in-finder 批 2026-11-19，作者令 19:39「电脑端的canvas标签页右键，
  // 多一个选项，在访达/文件浏览器打开」）。为什么必须真右键 + 真请求：文案若被搬回
  // 前端猜平台、菜单项长到 diff/URL/面板页签上、文件删了还能点、或为了弹菜单把
  // document 级 contextmenu 吃掉，四种错法都能在「只读一个按钮文案」的断言下全绿
  // ⇒ 本闸逐平台验文案、验非文件页签零命中、验置灰+原因、验空白处右键不被拦截。
  { name: 'canvas-reveal-in-finder', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/canvas-reveal-in-finder.spec.mjs'] },
  // 转回合圆钮「主会话 ⇄ Canvas Ask 同源」闸（ask-button-unify 2026-11-18；作者令
  // 「Ask 的中止按钮中间的方框应该和主会话的一致 发送按钮也和主会话一致吧」）：
  // 逐值同源 + 主会话冻结常数 + 源码消重（C 腿）+ 真状态机（busy 臂/点停帧）。
  { name: 'ask-button-unify', profiles: ['frontend'], timeoutMs: 300_000,
    argv: [process.execPath, 'tests/ask-button-unify.spec.mjs'] },
  nodeCheck('canvas-families', 'check-canvas-families', ['static']),
  // PDF note/ask 入口单源闸（文本层批 2026-11-19，作者逐字纠形「pdf note and ask
  // 不是在侧边栏加一个控件，是让 canvas 的 note 和 ask 能够支持 pdf 的阅读」）：
  // 侧边栏件删净（盘上无件 + 身份串零命中，含孤儿 CSS 选择器）+ PDF 内容模型
  // 单源（走 canvas 两颗钮）+ PDF 可选中（text layer）+ **零新路由**。
  nodeCheck('pdf-entry-single-source', 'check-pdf-entry-single-source', ['static']),
  // 待办选项闸（待办数据模型与命名规范批 2026-11-18，作者令「每个待办必须要有选项」）：
  // 源码级断言「写侧强制点在且先于创建 / 强制面只绑待办 / 媒体是引用且可多张」。
  nodeCheck('todo-options', 'check-todo-options', ['static']),
  // Pop 文件卡乐观打开接线闸（pop-optimistic-canvas 批 2026-11-18）：tab-first 时序 /
  // 环单源 / 取消真 abort / 失败可见 / 缓存 / 禁静默回落 ——「先加载、后建 tab」的回归
  // 只有浏览器里看得见，故把不变量做成静态判据，扩进既有闸体系（不新起第二套）。
  nodeCheck('pop-optimistic-open', 'check-pop-optimistic-open'),
  nodeCheck('toggle', 'check-toggle-unify'),
  nodeCheck('scroll-pill', 'check-scroll-pill-center'),
  // Canvas Ask 状态可见性与失败上报闸（2026-11-18）：状态判定单点 + 失败不许静默
  // + 文案完整 + 凭据不出诊断线（形态见 scripts/check-ask-status.mjs 头注）。
  nodeCheck('ask-status', 'check-ask-status'),
  nodeCheck('input-surface', 'check-input-surface', ['static', 'frontend'], SPEC_EXTERNAL),
  nodeCheck('dropdown-single-control', 'check-dropdown-single-control'),
  nodeCheck('mobile-canvas-strip', 'check-mobile-canvas-strip'),
  nodeCheck('panel-unify', 'check-panel-unify', ['static', 'frontend'], SPEC_EXTERNAL),
  nodeCheck('settings-ios-rows', 'check-settings-ios-rows'),
  // 前端设计要求批（ui-design-requirements 2026-11-18）：作者令「浮现式披露 / 不要有重复的
  // 功能和信息 / 统一按钮 / 解释性文字写作要求」四条规范的可执行面。三条都是纯静态扫描
  // （无浏览器、无实例），故与其它前端闸同在 static+frontend 两个 profile 下跑。
  // 规范正文（改判据改规范，脚本只是执行器）唯一一份在 stand-dev 插件包内
  // （references/frontend/ 的 disclosure-dedup-spec.md / explanatory-copy-spec.md /
  //  flat-button-spec.md §5/§6），本仓无第二份拷贝 —— 故 SPEC_EXTERNAL。
  nodeCheck('ui-structure', 'check-ui-structure', ['static', 'frontend'], SPEC_EXTERNAL),
  nodeCheck('copy-budget', 'check-copy-budget', ['static', 'frontend'], SPEC_EXTERNAL),
  nodeCheck('button-source', 'check-button-source', ['static', 'frontend'], SPEC_EXTERNAL),
  // 陈旧面板行批（2026-11-18）：后台智能体面板「活跃行 ⊆ 引擎活跃快照」不变量——
  // durable store 的重启打断行（pending-recovery）曾被折成「进行中」+ 690h 时钟。
  nodeCheck('bg-panel-active-liveness', 'check-bg-panel-active-liveness'),
  // 附件持久化批（2026-11-18 P0「我发送出去的文件过一会还是会出现这种失效的问题」）：
  // 用户附件落盘身份 / 读取面授权键 / attJson 延迟求值 / footer 下载键四条链的源码闸
  // （端到端回归见 tests/attachment-persistence.spec.mjs，需真实例）。
  nodeCheck('attachment-persistence', 'check-attachment-persistence'),
  // 设置页结构治理批（2026-11-18）：栏目名去重（section 头 vs 卡内标题逐对核）+
  // 按钮形态统一（退出 / 后台智能体恢复·停 登记进设置页动作族；按钮规则禁字面量几何）。
  nodeCheck('settings-section-dedupe', 'check-settings-section-dedupe', ['static']),
  nodeCheck('settings-button-uniform', 'check-settings-button-uniform', ['static']),
  ...[
    ['js-types', 'check-js-types'], ['ime', 'check-ime-guard'],
    ['component-contract', 'check-component-contract'], ['takeover-identify', 'check-takeover-identify'],
    ['design-spec', 'check-design-spec-sync', SPEC_EXTERNAL], ['prompt-source', 'check-prompt-single-source'],
    ['prompt-imports', 'check-prompt-catalog-imports'], ['provider-models', 'check-provider-modellist'],
    ['local-coupling', 'check-local-coupling'], ['tag-format', 'check-tag-format'],
    ['evidence-manifests', 'check-evidence-manifests'], ['scala-layers', 'check-scala-layers'],
    ['dispatcher-seal', 'check-dispatcher-seal'],
    // dispatcher 重设计 P0「抽件」外壳闸（2026-11-19）：只断言子会话原语的
    // 抽取面 + 单源（行为断言在 Scala 行为闸 SubagentPrimitiveSpec.scala）。
    ['subagent-primitives', 'check-subagent-primitives'],
    // dispatcher 重设计 P1「放开原语可见性」外壳闸（2026-11-19）：只断言复用面
    // 五个成员恰好放宽为 `private[core]` + 本节点零新读者（行为件另派）。
    ['subagent-primitive-visibility', 'check-subagent-primitive-visibility'],
    ['node-stuck-notify', 'check-node-stuck-notify'],
    // boot 复活只留手动腿（作者令 2026-11-19 10:39「boot 还是手动吧」；延续
    // 2026-11-06 20:36「不自动恢复、不通知」令）：四个 boot 调用点必须读
    // Defaults.RestartRecoveryManual（缺省手动），不得写死 manual 字面量；收殓腿
    // 零通知；手动腿（ManualRevival + REST 臂）必须在场。改回开启（自动）即红。
    ['boot-revival-manual', 'check-boot-revival-manual'],
    ['delegate-mode-slot', 'check-delegate-mode-slot'],
    ['perm-presentation', 'check-perm-presentation'],
    ['permission-gate', 'check-permission-gate'],
    // ask 写面收窄闸：ask 身份只能编辑它绑定的那份文档。
    ['ask-write-scope', 'check-ask-write-scope'],
  ].map(([name, script, options]) => nodeCheck(name, script, ['static'], options)),
  // 审批卡三要素前端闸；负控验证断言有效。
  { name: 'perm-card-detail', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/perm-card-detail.spec.mjs'] },
  { name: 'tooling-lint', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, 'node_modules/eslint/bin/eslint.js', 'eslint.config.mjs',
      'scripts/check-js-types.mjs', 'scripts/run-checks.mjs', 'scripts/check-sdd.mjs', 'scripts/check-openspec.mjs', 'scripts/check-message-item-unify.mjs',
      // 前端设计要求批（2026-11-18）：三条新闸与它们的共享判定库同受 lint 覆盖。
      'scripts/check-ui-structure.mjs', 'scripts/check-copy-budget.mjs', 'scripts/check-button-source.mjs',
      // 写时复制 M1 闸（2026-11-19）：新闸本体同受 lint 覆盖。
      'scripts/check-wt-cow.mjs',
      'scripts/lib/', 'scripts/tests/'] },
  { name: 'script-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, '--test', ...scriptTests] },
  { name: 'worktree-manifest', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/check-delivery.py', '--manifest-only'] },
  // 合并即清树兜底闸（作者裁定 2026-11-19 20:31「合并就清树」；规范面 branch-governance
  // BG-REQ-003 / CONTRIBUTING「Landing rules」）。判据＝已并入整合线（main / feat/base）的
  // 分支仍挂着一棵工作树 ⇒ 红。**为什么单列 `worktree` 档而不是并入 static**：这条判的是
  // 本机工作树存量（2026-11-19 读数：181 棵链接工作树，其中 41 棵已并入却仍挂着），
  // 存量回收前它在本机恒红；放进 static 会让任何开发机的静态档长红，把真正的代码红淹掉。
  // CI/托管主检出没有链接工作树 ⇒ 无对象可判 ⇒ PASS（见脚本头注释的退出码策略）。
  // 存量清完可提级并入 static —— 那是一行改动，由作者决定。
  { name: 'worktree-cleanup', profiles: ['worktree'], timeoutMs: 120_000,
    argv: [process.execPath, 'scripts/check-worktree-cleanup.mjs'] },
  { name: 'cli-logo', profiles: ['static'], timeoutMs: 150_000,
    argv: ['python3', '-B', 'scripts/check-logo-matrix.py'] },
  { name: 'cli-logo-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/test-cli-banner-payload.py'] },
  // 2026-11-19 普查收编批：三个 python 单测此前谁都不跑（check:commit-attribution
  // 只在 CI 跑脚本本体、其单测连 CI 也没跑）。这里按名注册为 static 档闸。
  { name: 'commit-attribution-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/commit-attribution.test.py'] },
  { name: 'visual-report-paths-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/test-visual-report-paths.py'] },
  { name: 'stand-wt-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/test_stand_wt.py'] },
  // 隔离启动守卫（panel-glass-align 落支批 2026-11-20 残差移植）：本仓的隔离跑法全靠
  // 约定（--home / 环境变量 / JVM 属性三处必须同指一个隔离根），漏一处就把日志与数据
  // 写进生产 ~/.stand 而无人发现。该守卫在 Java（进而 Logback）启动前拒掉越界组合，
  // 单测覆盖四类拒绝面；不注册则等于没有这道闸。
  { name: 'isolated-java-launch-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/test-isolated-java-launch.py'] },
  // 写时复制建树 M1 闸（workspace-compute R1a，作者令 2026-11-19）。envSkip：非 darwin
  // 下 clonefile(2) 不存在，闸以退出码 2 显式 SKIP 并打印原因 —— 记 SKIP 而不是 PASS。
  // 判据与负控口径见脚本文件头（①②③④ + 三条回退面；STAND_WT_DEGRADE 为负控开关）。
  { name: 'wt-cow', profiles: ['static'], timeoutMs: 600_000, envSkip: true,
    argv: [process.execPath, 'scripts/check-wt-cow.mjs'] },
  // 写时复制后端的单元测试（种子索引、回退原因枚举、中断恢复），与 stand-wt 单测同档。
  { name: 'stand-wt-cow-tests', profiles: ['static'], timeoutMs: 300_000, envSkip: true,
    argv: ['python3', '-B', 'scripts/tests/test-stand-wt-cow.py'] },
  // 微信侧车单测（九个 *.test.mjs）按目录点名收集，防目录新增文件漏跑。
  { name: 'weixin-sidecar-tests', profiles: ['static'], timeoutMs: 180_000,
    argv: [process.execPath, '--test', ...weixinTests] },
  // delegate-chain-throttle：顶层用 vm.SourceTextModule，必须带 --experimental-vm-modules，
  // 否则纯 node 跑出 TypeError: vm.SourceTextModule is not a constructor（runner 旗标问题，
  // 不是测试缺陷）。
  { name: 'delegate-chain-throttle', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, '--experimental-vm-modules', 'tests/delegate-chain-throttle.test.mjs'] },
  // delegate-chain-bulk-load（重启重连风暴修复批 2026-11-21）：锁住「一轮刷新 = 1 行面
  // + 1 批量摘要 + 有预算的详情腿」「缓存复用（同 TTL 窗内不重放）」「稳态重连零详情
  // 请求」「429 退避不成紧循环」四条不变量。不注册 = 这条事故防线等于没有闸。
  // 与上面同因：顶层用 vm.SourceTextModule ⇒ 必须带 --experimental-vm-modules。
  { name: 'delegate-chain-bulk-load', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, '--experimental-vm-modules', 'tests/delegate-chain-bulk-load.test.mjs'] },
  // 未读计数单源（fix/unread-count-unify 独立审查 2026-11-20）：该支的实现在内核抽取批
  // （2026-11-18）之后已被 base 完整吸收（unreadCount.js 同字节、utils.rec.unreadRows、
  // askDialog.settleCol 同式），唯独验收件仍锚在已搬空的 askDock.js ⇒ 长期红着没人跑、
  // 也没注册成闸。本处补两件：把断言重锚到 askDialog.js 的 settleCol（同一份源码切片，
  // 无二次书写），并把这条测试注册进 static 档 —— 不注册就等于没有这道闸。
  { name: 'unread-count', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, '--test', 'tests/unread-count.test.mjs'] },
  nodeCheck('sdd', 'check-sdd', ['static']),
  nodeCheck('sdd-changed', 'check-sdd', ['sdd-changed']),
];

export function selectChecks(profile, base, manifestTmp) {
  // `worktree` 是本机工作树生命周期档（worktree-cleanup）：显式 --profile worktree 才跑，
  // 不进 static/frontend —— 它的红是本机存量事实，不是代码缺陷（见该闸上的注释）。
  if (!['static', 'frontend', 'sdd-changed', 'worktree'].includes(profile)) throw new Error(`Unknown profile: ${profile}`);
  if (profile === 'sdd-changed' && !base) throw new Error('sdd-changed requires an explicit --base');
  if (profile !== 'sdd-changed' && base) throw new Error('--base is only valid with sdd-changed');
  if (manifestTmp && (profile !== 'static' || !isAbsolute(manifestTmp))) throw new Error('--manifest-tmp requires an absolute path and the static profile');
  return CHECKS.filter(check => check.profiles.includes(profile)).map(check => ({
    ...check, argv: [...check.argv, ...(base ? ['--base', base] : []),
      ...(manifestTmp && check.name === 'worktree-manifest' ? ['--tmp', manifestTmp] : [])],
  }));
}

// Each POSIX child owns its process group; never signal the caller's group.
async function terminateTree(child) {
  if (!Number.isInteger(child?.pid) || child.pid <= 1 || child.pid === process.pid) return;
  if (process.platform !== 'win32') {
    try { process.kill(-child.pid, 'SIGKILL'); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
    return;
  }
  await new Promise((resolveCleanup, rejectCleanup) => {
    const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', shell: false });
    const timer = setTimeout(() => {
      killer.kill();
      rejectCleanup(new Error('Process-tree cleanup timed out'));
    }, 5000);
    killer.once('error', error => { clearTimeout(timer); rejectCleanup(error); });
    killer.once('close', code => {
      clearTimeout(timer);
      if (code === 0) resolveCleanup();
      else rejectCleanup(new Error(`Process-tree cleanup failed: taskkill exit ${code}`));
    });
  });
}

export async function runCheck(check, { cwd = ROOT, log = console.log } = {}) {
  if (!Array.isArray(check.argv) || !check.argv.length || !check.argv.every(arg => typeof arg === 'string') ||
      !Number.isFinite(check.timeoutMs) || check.timeoutMs <= 0) throw new Error('Invalid check declaration');
  const start = performance.now();
  log(`START ${check.name}`);
  return new Promise(resolveResult => {
    let timedOut = false;
    let interrupted;
    let failure;
    let child;
    let timer;
    let heartbeat;
    let finished = false;
    let stopping = false;
    let cleanup;
    const finish = async (code, signal) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      clearInterval(heartbeat);
      await cleanup;
      process.removeListener('SIGINT', onInterrupt);
      process.removeListener('SIGTERM', onTerminate);
      const elapsedMs = Math.round(performance.now() - start);
      // envSkip 闸的退出码 2 = 规范面（stand-dev 插件包）缺席的环境错误，不是违规 ——
      // 记为 SKIP 而非 FAIL，也不假绿成 PASS（CI 仍能跑完；standalone 直跑仍是退出码 2）。
      const skipped = check.envSkip === true && !failure && !timedOut && !interrupted && !signal && code === 2;
      const ok = !failure && !timedOut && !interrupted && !signal && (code === 0 || skipped);
      const reason = interrupted ?? (timedOut ? 'timeout' : failure ? failure.message : signal ? `signal ${signal}` : `exit ${code}`);
      log(`${ok ? (skipped ? 'SKIP' : 'PASS') : 'FAIL'} ${check.name} (${elapsedMs}ms; ${reason})`);
      resolveResult({ name: check.name, ok, skipped, code, signal, interrupted, timedOut, error: failure?.message, elapsedMs });
    };
    const stop = () => {
      if (stopping || finished) return;
      stopping = true;
      cleanup = terminateTree(child).catch(error => { failure = error; });
      // Do not wait forever for close, even if inherited descriptors stay open.
      void cleanup.then(() => finish(null, interrupted ?? 'SIGKILL'));
    };
    const onInterrupt = () => { interrupted = 'SIGINT'; stop(); };
    const onTerminate = () => { interrupted = 'SIGTERM'; stop(); };
    process.on('SIGINT', onInterrupt);
    process.on('SIGTERM', onTerminate);
    try {
      child = spawn(check.argv[0], check.argv.slice(1), {
        cwd, stdio: 'inherit', shell: false, detached: process.platform !== 'win32',
      });
    } catch (error) {
      failure = error;
      void finish(null, null);
      return;
    }
    child.once('error', error => { failure = error; });
    child.once('close', (code, signal) => { void finish(code, signal); });
    timer = setTimeout(() => { timedOut = true; stop(); }, check.timeoutMs);
    heartbeat = setInterval(() => log(`RUNNING ${check.name} (${Math.round(performance.now() - start)}ms)`), 15_000);
  });
}

export async function main(args = process.argv.slice(2)) {
  let profile = 'static';
  let base;
  let manifestTmp;
  let list = false;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--list' || arg === '--dry-run') list = true;
    else if (arg === '--profile' || arg === '--base' || arg === '--manifest-tmp') {
      const value = args[++index];
      if (!value || value.startsWith('--')) throw new Error(`Missing value for ${arg}`);
      if (arg === '--profile') profile = value;
      else if (arg === '--base') base = value;
      else manifestTmp = value;
    } else throw new Error(`Unknown argument: ${arg}`);
  }
  const checks = selectChecks(profile, base, manifestTmp);
  if (list) {
    for (const check of checks) console.log(`${check.name}: ${JSON.stringify(check.argv)} (timeout ${check.timeoutMs}ms)`);
    return 0;
  }
  let failures = 0;
  let skipped = 0;
  for (const check of checks) {
    const result = await runCheck(check);
    if (result.interrupted) return result.interrupted === 'SIGINT' ? 130 : 143;
    if (result.skipped) skipped++;
    if (!result.ok) failures++;
  }
  const tail = skipped ? ` (${skipped} skipped: 规范面缺席 — 见 scripts/lib/design-spec-source.mjs)` : '';
  console.log(`${profile}: ${checks.length - failures}/${checks.length} checks passed${tail}`);
  return failures ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(code => { process.exitCode = code; }).catch(error => {
    console.error(`Checks ERROR: ${error.message}`);
    process.exitCode = 2;
  });
}
