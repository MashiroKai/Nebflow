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

// 设计规范正文（毛玻璃 / 输入面 / 面板 / 按钮 / 披露 / 文案 六篇 + 几何刻度账本 + 令牌
// 账本 css）单源在**本仓** `design-language/`，随提交分发；这些闸靠
// scripts/lib/design-spec-source.mjs 定位它。2026-11-21 裁定批起没有「规范面缺席」这一
// 档：正文恒在 checkout 里，闸缺席 = 判据消失 = 必须可见的红（原先的 envSkip/SKIP 口径
// 已撤销，退出码 2 一律按 FAIL 计）。

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
  { name: 'ask-option-highlight', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/ask-option-highlight.spec.mjs'] },
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
  // 截断/重叠巡检闸（作者令 2026-11-24 09:54 + 继续令「一次建全」2026-11-25）：
  // 真 Chromium 390×844 量全面板清册——发起群聊 / 后台 agent 弹窗 / 主会话消息流 /
  // 联系人 / 设置 / 待办列表 / 看板文档阅读面 / AskUser 决策卡（P1–P8），另加桌面
  // 1280×800 同面板族抽检（D1–D2）；每面配负对照（还原修前形态必须红，否则闸失明
  // ⇒ FAIL）。判据 O0–O4 与修前同尺，不因面板增多而放宽。
  { name: 'mobile-overlap-sweep', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/mobile-overlap-sweep.spec.mjs'] },
  { name: 'mobile-overlap-contract', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, '--test', 'tests/mobile-overlap-contract.test.mjs'] },
  // 手机图片「点开大图预览失败」闸（2026-11-23 · IMG_2039）：原图腿败 + 卡面缩略在场 ⇒ 仍开灯箱
  // 不 toast；原图腿通 ⇒ 灯箱图解码；负对照无缩略 ⇒ toast 必现（路径活着）。
  { name: 'mobile-image-preview-fallback', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-image-preview-fallback.spec.mjs'] },
  // 手机消息流末条被输入框遮挡闸（2026-11-23 · IMG_2036）：#input-area 整框为控件矩形；
  // O2 断言真实 reserve ≥ 输入框高度（静止 / 8 行草稿两态），负对照 = 陈旧 reserve。
  { name: 'mobile-composer-occlusion', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-composer-occlusion.spec.mjs'] },
  // 失败卡「恢复即过期」（项 3 · 2026-11-23）：真实 WS 桩驱动 digest + delegateWoken，断言转灰/计数/负对照。
  { name: 'provider-failure-expired', profiles: ['frontend'], timeoutMs: 180_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/provider-failure-expired.spec.mjs', '--workers=1'] },
  // 后台面板历史卡堆叠折叠（项 1 · 2026-11-23）：H1–H6。
  { name: 'bg-panel-history-fold', profiles: ['frontend'], timeoutMs: 180_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/bg-panel-history-fold.spec.mjs', '--workers=1'] },
  nodeCheck('design-tokens', 'check-design-tokens'),
  // 全面板圆角令牌覆盖（2026-11-23 作者第三次点名「按钮是方的」）：全局扫描，任何未接令牌的
  // 方角 / 字面量圆角命中即红，除非登记放行理由（ALLOW 表）。
  nodeCheck('radius-tokens', 'check-radius-tokens'),
  nodeCheck('sidebar-top', 'check-sidebar-top'),
  nodeCheck('page-back-tokens', 'check-page-back-tokens'),
  // 文档收藏纯逻辑闸（2026-11-23 前端功能批）：收藏切换 / 同文档去重=置顶 / 归一化 / 上限。
  // 主会话多选合并 Ask 现场探针（loopback mock LLM + 隔离网关；CI 安全）。
  { name: 'selection-ask-multi-live', profiles: ['static'], timeoutMs: 300_000,
    argv: [process.execPath, 'tests/selection-ask-multi-live.mjs'] },
  { name: 'canvas-favorites-core', profiles: ['static'], timeoutMs: 60_000,
    argv: [process.execPath, '--test', 'tests/canvas-favorites-core.test.mjs'] },
  // 面板缺陷批（2026-11-23）防回归·静态档：失败卡服务商名读回（④）+ 标题回填契约（③⑤）。
  { name: 'provider-label-resolve', profiles: ['static'], timeoutMs: 60_000,
    argv: [process.execPath, '--test', 'tests/provider-label-resolve.test.mjs'] },
  { name: 'failure-card-title-chain', profiles: ['static'], timeoutMs: 60_000,
    argv: [process.execPath, '--test', 'tests/failure-card-title-chain.test.mjs'] },
  // 文档收藏渲染级闸（真 Chromium，三端）：需浏览器 ⇒ frontend 腿（与其它 spec 同档）。
  { name: 'canvas-favorites-render', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/canvas-favorites-render.spec.mjs'] },
  // Canvas 文档首行零缩进及首续行对齐：桌面、手机真实浏览器。
  { name: 'canvas-doc-indent-desktop', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/indent-revert-shots.mjs'] },
  { name: 'canvas-doc-indent-mobile', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/indent-revert-shots.mjs', '--mobile'] },
  // 按钮样式统一闸（button-style-unify-canvas-ref 批 2026-11-22）：全站实底静止按钮面
  // 消费统一阴影令牌 var(--btn-shadow)（canvas 基准）；裸写投影/字面量圆角即红。
  nodeCheck('button-style-unify', 'check-button-style-unify'),
  // 主会话交互卡统一风格（作者裁定 B，2026-11-21）：卡族（.option-box / llm-failure-card / .nfc）
  // 卡面表面属性只许经 var(--…) 令牌承载；新造字面量卡面即红。与 check-component-contract
  // 判据不重叠（本闸只读 CSS 卡面声明，不看 JS DOM 写法）。
  nodeCheck('interaction-card-family', 'check-interaction-card-family'),
  // LLM 失败卡「点按消失」接线静态闸（2026-11-22）+ Chromium 手机视口三态 spec。
  nodeCheck('llm-failure-dismiss', 'check-llm-failure-dismiss'),
  { name: 'llm-failure-card-dismiss', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/llm-failure-card-dismiss.spec.mjs'] },
  // 服务商受限通知「卡片化收编」静态闸（provider-notice-card-unify 批 2026-11-23）：
  // 失败组走 llm-failure-card 家族卡面（不再裸横幅）；窗口受限型卡态 + provider id 兜底。
  nodeCheck('provider-notice-unify', 'check-provider-notice-unify'),
  // 双档令牌镜像闸（2026-11-21）：base.css 的亮/暗显式档 ≡ 媒体暗档，逐令牌；与卡族闸同档。
  nodeCheck('theme-token-mirror', 'check-theme-token-mirror'),
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
  // 叠卡视图一次性轴锁闸（作者 2026-11-23 10:23 报：斜滑被误判成纵向）：真触屏 CDP
  // 序列 A1–A4 + 负对照 N1 + touch-action 静态钉；修前 A3/A3b 红、修后全绿。
  { name: 'mobile-stack-swipe-axis-lock', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/mobile-stack-swipe-axis-lock.spec.mjs'] },
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
  // ack-badge-pool 批（2026-11-23）：零 report 块回合 ✻ 字句池（ack 池）门禁 —
  // 池非空/两语言对齐/{d} 占位与字数/取词分派单点/轮换同构。
  nodeCheck('ack-phrases', 'check-ack-phrases'),
  nodeCheck('circular', 'check-circular'),
  nodeCheck('fixture-secrets', 'check-fixture-secrets'),
  nodeCheck('api-routes', 'check-api-routes'),
  // 性能设计红线批（2026-11-21 11:37 作者令「把这次卡顿的教训落成性能测试 spec 红线
  // 自动化——包括网络、内存、加载速度等设计红线」）。
  //
  // 动机：诊断批（panel-mobile-perf-diagnostics）实测手机面板主线程被压到 2.1fps，
  // 三条主因全部**零编译错、零类型错、零既有测试红**——它们是形态缺陷：
  //   ① 历史接口写死 `getUiMessages(sid, 0, 0)`（`?limit=50` 与不传参字节完全相同，
  //      10 991 449B 裸传）；② `/api` 全族 JSON 无压缩；③ 面板每渲一轮逐父发子代理
  //      请求（一拍 116+ 条）。
  // 两道腿各管一面，不可互相替代：
  //   perf-redlines         = **静态**源码形态（整表读 / 压缩管线 / 超频轮询），零浏览器；
  //   perf-redlines-runtime = **运行时**字节与压缩覆盖（真起浏览器量出向字节）。
  //     运行时腿默认打 6138 活面板；无实例时退出码 2 ⇒ 记为 SKIP（不假绿成 PASS）。
  nodeCheck('perf-redlines', 'check-perf-redlines'),
  {
    name: 'perf-redlines-runtime',
    profiles: ['frontend'],
    timeoutMs: 240_000,
    argv: [process.execPath, 'tests/perf-redlines-runtime.mjs'],
    envSkip: true,
  },
  nodeCheck('provider-panel', 'check-provider-panel'),
  // 统一模型不可用卡批（2026-11-18）：一套 kind（InteractionKind.ModelUnavailable）+ 一套
  // 拼帧点，全槽共用（small-llm/kernel/node/dispatcher/subagent）；切换就地写回槽
  // model.preferred 并重试原请求；卡内「测试 LLM」真实最小调用；300s（可配置）回报根会话；
  // fail-closed 缺省不得被本批翻面。前后端两面都断言 ⇒ static + frontend 双 profile。
  nodeCheck('model-fallback-card', 'check-model-fallback-card'),
  nodeCheck('freeze-state', 'check-freeze-state'),
  nodeCheck('flat-buttons', 'check-flat-buttons', ['static', 'frontend']),
  nodeCheck('glass', 'check-glass-unify', ['static', 'frontend']),
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
  { name: 'favorites-path-clamp', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/favorites-path-clamp.spec.mjs'] },
  { name: 'favorites-slot-text', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/favorites-slot-text.spec.mjs'] },
  { name: 'favorites-slot-geometry', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/favorites-slot-geometry.spec.mjs'] },
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
  // Ask 回答正文相对路径图片走票据闸（ask-image-ticket 批 2026-11-24，作者报
  // 「Ask 回答正文相对路径图片看不了」）：S1 Pop 文档卡重开旧票自愈（d6e1a349b
  // 断言）/ S2 同目录·子目录·无文档上下文三形 / S3 迟到结果竞争闸（askState 重渲
  // 与切文档两形，旧轮结果不回填、不对已弃图取字节）。双核（chromium+webkit）。
  { name: 'ask-image-ticket', profiles: ['frontend'], timeoutMs: 300_000,
    argv: [process.execPath, 'tests/ask-image-ticket.spec.mjs'] },
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
  nodeCheck('input-surface', 'check-input-surface', ['static', 'frontend']),
  // 焦点环统一批（focus-ring-unify-impl 2026-11-22，作者已批 B 案）：全站键盘焦点指示器
  // 一条规则 + 一个色源（--nf-focus-ring #9ca3af · 内缩 2px · 禁光晕）。静态腿判「源码
  // 写的是不是那一条」（F1–F6，含别名收编 0 引用与豁免台账只减）；运行时腿判「屏幕画出来
  // 的是不是那一条」（P-1–P-4，差分像素 + 对比读数 + 玻璃弹层内可读）。规范正文 =
  // design-language/references/frontend/focus-ring-spec.md。round2（2026-11-22 作者令
  // 「我说了要圆角的框」/「落为标准」）：静态腿加 F7（圆角标准 + 方角登记机制废除）与
  // F8（焦点规则不得改圆角），运行时腿加 P-5（计算圆角 ≥6px，微格/胶囊档放行）。
  nodeCheck('focus-ring', 'check-focus-ring', ['static', 'frontend']),
  { name: 'focus-ring-runtime', profiles: ['frontend'], timeoutMs: 600_000,
    argv: [process.execPath, 'scripts/check-focus-ring-runtime.mjs'] },
  // 全面圆角批（作者令 2026-11-22 10:17「还是存在部分不是圆角的 得全面圆角 而且成为规范
  // 和自动化/lint检查」）：F7/P-5 只判**焦点环基座**（可交互件）；本闸判**全树画框面**
  // （徽章 / chip / 统计卡 / 提示条 / 分隔面 / 媒体占位等不参与 Tab 序的方角）。
  // 静态腿 = G1–G4（画框规则必须有圆角档，结构全幅按几何具名豁免）；运行时腿 = P-6
  // （check-focus-ring-runtime.mjs 内的计算样式普查）。规范正文同 §2.6/§3/§4。
  nodeCheck('round-corners', 'check-round-corners', ['static', 'frontend']),
  nodeCheck('dropdown-single-control', 'check-dropdown-single-control'),
  nodeCheck('mobile-canvas-strip', 'check-mobile-canvas-strip'),
  // dock 悬浮批（作者裁定 2026-11-21 10:41「手机端 dock 在其他页面都是悬浮效果，但主会话
  // 却是截断的——落成标准和规范，进行自动化检查，都得是悬浮的」）：这是**第 9 道设计闸**
  // （前 8 道 = 读数/造型/面板/披露/文案/令牌那批），判据面与前 8 道不重叠 —— 前 8 道
  // 全部只读「件自己的声明」，而本条的缺陷在**件与宿主之间**：`#mobile-pages` 容器上色
  // 把 dock 带区变垫层，聊天根内容被硬切。为什么不能只扫 `#mobile-dock`：改前实测五个
  // 页面的胶囊几何逐值正确（r999/三边 inset/blur 全在档）⇒ 只扫本体必全绿，而作者看到的
  // 红就在带区。单源正文 = design-language/references/frontend/mobile-chrome-spec.md。
  nodeCheck('mobile-dock-float', 'check-mobile-dock-float', ['static', 'frontend']),
  nodeCheck('panel-unify', 'check-panel-unify', ['static', 'frontend']),
  nodeCheck('settings-ios-rows', 'check-settings-ios-rows'),
  // 前端设计要求批（ui-design-requirements 2026-11-18）：作者令「浮现式披露 / 不要有重复的
  // 功能和信息 / 统一按钮 / 解释性文字写作要求」四条规范的可执行面。三条都是纯静态扫描
  // （无浏览器、无实例），故与其它前端闸同在 static+frontend 两个 profile 下跑。
  // 规范正文（改判据改规范，脚本只是执行器）单源在仓内 design-language/references/frontend/
  // （disclosure-dedup-spec.md / explanatory-copy-spec.md / flat-button-spec.md §5/§6）。
  nodeCheck('ui-structure', 'check-ui-structure', ['static', 'frontend']),
  nodeCheck('copy-budget', 'check-copy-budget', ['static', 'frontend']),
  nodeCheck('button-source', 'check-button-source', ['static', 'frontend']),
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
    ['design-spec', 'check-design-spec-sync'], ['prompt-source', 'check-prompt-single-source'],
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
      // dock 悬浮批（2026-11-21，第 9 道设计闸）
      'scripts/check-mobile-dock-float.mjs',
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
      // 没有「跳过」这一档：任何非零退出（含规范面缺失的退出码 2）都是失败的检查。
      const ok = !failure && !timedOut && !interrupted && !signal && code === 0;
      const reason = interrupted ?? (timedOut ? 'timeout' : failure ? failure.message : signal ? `signal ${signal}` : `exit ${code}`);
      log(`${ok ? 'PASS' : 'FAIL'} ${check.name} (${elapsedMs}ms; ${reason})`);
      resolveResult({ name: check.name, ok, code, signal, interrupted, timedOut, error: failure?.message, elapsedMs });
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
  for (const check of checks) {
    const result = await runCheck(check);
    if (result.interrupted) return result.interrupted === 'SIGINT' ? 130 : 143;
    if (!result.ok) failures++;
  }
  console.log(`${profile}: ${checks.length - failures}/${checks.length} checks passed`);
  return failures ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(code => { process.exitCode = code; }).catch(error => {
    console.error(`Checks ERROR: ${error.message}`);
    process.exitCode = 2;
  });
}
