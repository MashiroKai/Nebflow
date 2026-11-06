/* liquidMorph.js — 「液态形变 / Liquid Morph」动效语言的**唯一数据源**（单源模块）。
   命名裁定与完整规范：design/liquid-morph__20261106/DESIGN.md；术语表：GLOSSARY.md §14。

   ── 一句话 ────────────────────────────────────────────────────────────
   同一个 DOM 节点在**两端几何之间连续插值**（长条 ⇄ 圆钮、行 ⇄ 面板、列表行 ⇄
   互动框），一段过渡、一条曲线、一个时长；不是「闪一下换一个元素」。

   ── 原型（唯一灵感来源，不许另造缓动）──────────────────────────────────
   css/mobileSearchFlat.css —— 手机搜索「扁平长条 ⇄ 右上角圆钮」一次性形变：
     · :74-75      时长/曲线出生点（--mhsc-dur: 280ms / --mhsc-ease: cubic-bezier(0.4, 0, 0.2, 1)）
     · :149-176    transition 属性白名单（条本体 11 条 + 图标 2 条 + 输入行 2 条）
     · :133-144    展开两端几何（44×343 / radius 12）
     · :183-196    收起两端几何（48×48 / radius 50% / 输入行 opacity 0 + max-width 0）
     · :207-212    reduced-motion 降级（--mhsc-dur: 1ms）
     · :214-227    实测读数归档（两端逐值）
   本模块与 css/design-system.css 的 `--lm-*` 组逐值同源（tests/liquid-morph.spec.mjs
   有一条断言把两侧钉在一起：改一边不改另一边即红）。

   ── 三条纪律（谁改谁读）───────────────────────────────────────────────
   ① **单一数据来源**：时长 / 曲线 / 可过渡属性集只在这里（JS 消费面）与
      design-system.css 的 `--lm-*`（CSS 消费面）出现，两者互为镜像；任何面
      （css / js / 文档）不得再写第二份 280ms、第二份 cubic-bezier、第二份属性清单。
   ② **两端几何可插值**：过渡属性必须是「两端都是长度/比例/颜色、浏览器能逐帧插值」
      的；`display` / `height: auto` / `content` 这类不可插值（或触发布局的）属性
      一律不得进 PROPERTY_WHITELIST —— morphTransition() 对白名单外的属性直接抛错。
   ③ **零第二套缓动**：全语料只有 EASING 一条曲线。既有 `--ease-enter`
      (cubic-bezier(0.16,1,0.3,1)) / `--panel-ease` 属**其它**动效语言（入场/面板），
      不许被搬进液态形变；本语言也不许把 spring 逐帧（JS 定时器）当实现手段。

   ── 谁在消费 ─────────────────────────────────────────────────────────
   · 本批 4 处（design/liquid-morph__20261106/DESIGN.md §复用清单）：
     ① ⋯ 菜单展开 ② 二级面板浮起 ③ 条目 → 互动框 ④ 新建表单从顶部展开
   · CSS 侧消费方式（属性清单可直接吃令牌）：
       transition-property: var(--lm-props);
       transition-duration: var(--lm-dur);
       transition-timing-function: var(--lm-ease);
   · JS 侧消费方式（需要属性子集 / 动态时长时）：
       el.style.transition = morphTransition(['width', 'border-radius'], { duration: 'fast' });

   ── 边界：零 DOM、零副作用 ─────────────────────────────────────────────
   本模块不读 document、不挂监听、不写内联样式、不发请求；唯一的环境读点是
   reducedMotion()（`window.matchMedia`，无 window 时返回 false），且它只在被调用
   时读一次（用户可在运行中切换偏好）。因此 node 可直接 import 做单测：
       node tests/liquid-morph.spec.mjs
*/

/**
 * 时长三档（毫秒）。每一档都有既有读数锚点，无一是拍脑袋定的：
 * · `fast` = 160 —— base.css:183 的 `--dur-2`（= `--ds-dur-hover`，色彩/描边/底纹
 *   档）；本仓 morph 交接腿亦取 160ms（tests/_mobile-panel-settle.mjs:9 记
 *   floatController「.on 翻转 → 160ms 延时 → morphIn」）。用于**不改变几何**的子腿
 *   （opacity / border-color / background-color / box-shadow）。
 * · `base` = 280 —— 原型读数（mobileSearchFlat.css:74 `--mhsc-dur`）。几何形变主腿，
 *   默认档。独立收敛到同一档的既有读数另有四处：令牌声明 projectPanel.css:300
 *   （`--project-card-out-ms`）/ mobileLayer.css:1792（`--dur-switch`），同值用法
 *   mobileLayer.css:1783（`var(--dur-switch, 280ms)` 回退档）/ timeWheel.css:107
 *   （tw-sheet-in 280ms）。四个面各自走到 280 是既有事实，本语言把它立成唯一基准。
 * · `slow` = 400 —— design-system.css:241 `--ds-dur-slow`（Carbon duration-slow-01，
 *   注释语义即「大展开/重要通知档」）。只给**大位移**腿（≥ 半屏的整块浮起 / 从顶部
 *   展开）；默认档恒为 base。
 * 降级：prefers-reduced-motion ⇒ 1ms（与原型 :210 的 `--mhsc-dur: 1ms` 同值同语义）。
 * @type {Readonly<{ fast: number, base: number, slow: number }>}
 */
export const DURATION = Object.freeze({ fast: 160, base: 280, slow: 400 });

/** 全语言唯一缓动曲线 = base.css:186 的 `--ease-std`（原型 :75 逐字同值）。 */
export const EASING = 'cubic-bezier(0.4, 0, 0.2, 1)';

/**
 * 可过渡属性白名单 —— 逐项抄自原型 transition 清单，顺序与原文件一致：
 * 条本体 mobileSearchFlat.css:150-161 的 11 条 → 图标 :166-168（width/height 已含）
 * → 输入行 :173-175（opacity / max-width）。原型之外**追加两项**（各自申报理由，见下）：
 * `transform`（浮件宿主位移腿）与 `padding-top` / `padding-bottom`（同框同边的行 ⇄ 详情框
 * 形变腿，schedule-todo-unify 批 2026-11-06 按 DESIGN.md §3 的「加项须申报」流程补入）。
 * @type {ReadonlyArray<string>}
 */
export const PROPERTY_WHITELIST = Object.freeze([
  // ① 条本体 11 条（:150-161）：宽度/高度 = 44→48、343→48 的几何主腿
  'width',
  'height',
  // ② 落位腿（:153-156）：收起态靠 margin-left: calc(100% - 16 - 48) 把条推到右上角，
  //    左右内距 16→0 让图标居中 —— 位移由 margin 承担（原型不吃 transform）。
  'margin-left',
  'margin-right',
  'padding-left',
  'padding-right',
  // ③ 形态腿（:157-161）：圆角 12 → 50%、描边/底色/浮影/毛玻璃整套换档
  'border-radius',
  'border-color',
  'background-color',
  'box-shadow',
  'backdrop-filter',
  // ④ 输入行退场（:173-175）：宽度收到 0 + 淡出，同一过渡段
  'opacity',
  'max-width',
  // ⑤ 原型之外追加（唯一一项，须申报理由）：
  //    原型是「流内单节点」，位移天然由 margin 表达；本批 4 处复用面里 ②二级面板浮起、
  //    ④新建表单从顶部展开 的宿主是 absolute/fixed 浮件（layout 盒不许动）⇒ 位移只能走
  //    transform（合成器腿，不触发布局/不整页重排）。既有读数亦支持：mobileLayer.css:2238
  //    的手机档面板形变腿就是 transform + --dur-2。transform 两端均可插值
  //    （translate/scale/rotate 同族），且不与 whitelist 内任何属性冲突。
  'transform',
  // ⑥ 追加项②（schedule-todo-unify 批 2026-11-06；申报理由见 DESIGN.md §3「追加项」）：
  //    复用面⑤「条目行 → 带详情的互动框」是同**框同边**长大 —— 行内距两端都由
  //    padding 承担（收起 = 单行内距、展开 = 详情框内距）。原型只有水平内距腿
  //    （padding-left/right :155-156），缺竖直档；两端都是长度、逐帧可插值，
  //    且宿主几何变化被「同一条内距」吸收（不新增布局通道，不触发布局抖动 ——
  //    触发布局 ≠ 不可插值，本项在两端之间只改内边距，浏览器按布局动画处理）。
  'padding-top',
  'padding-bottom',
]);

/** 降级时长（与原型 :210 同值：二值语义保留，过渡压近零）。 */
const REDUCED_MS = 1;

/**
 * 生成 transition 串。
 * @param {ReadonlyArray<string>|null|undefined} [props] 属性子集（必须是 PROPERTY_WHITELIST 的子集）；
 *   省略/null = 全量白名单。
 * @param {{ duration?: number|'fast'|'base'|'slow', easing?: string, delay?: number, reduced?: boolean }} [opts]
 *   duration：毫秒数或档名（默认 'base' = 280）；easing：默认 EASING（不许传第二条曲线给
 *   本语言——传了也必须是 EASING 同值，否则抛错）；delay：ms（默认 0）；reduced：true ⇒ 1ms。
 * @returns {string} 可直接赋给 `style.transition` 的串，逐属性列出（永不含 `all`）。
 */
export function morphTransition(props, opts = {}) {
  const list = props == null ? PROPERTY_WHITELIST : props;
  if (!Array.isArray(list) || list.length === 0) {
    throw new TypeError('morphTransition: props 必须是非空数组（省略 = 全量白名单）');
  }
  for (const p of list) {
    if (!PROPERTY_WHITELIST.includes(p)) {
      throw new TypeError(
        `morphTransition: "${p}" 不在液态形变白名单内（不可插值 / 会触发布局 / 未申报）——`
        + ` 合法集 = ${PROPERTY_WHITELIST.join(', ')}`,
      );
    }
  }

  const rawDur = opts.duration == null ? 'base' : opts.duration;
  let ms;
  if (typeof rawDur === 'number') {
    if (!Number.isFinite(rawDur) || rawDur <= 0) throw new TypeError(`morphTransition: duration 必须是正数毫秒，收到 ${rawDur}`);
    ms = rawDur;
  } else if (Object.prototype.hasOwnProperty.call(DURATION, rawDur)) {
    ms = DURATION[rawDur];
  } else {
    throw new TypeError(`morphTransition: duration 档 "${rawDur}" 不存在（合法 = fast/base/slow 或正数毫秒）`);
  }
  if (opts.reduced === true) ms = REDUCED_MS;

  const easing = opts.easing == null ? EASING : opts.easing;
  if (easing !== EASING) {
    throw new TypeError(`morphTransition: 本语言只有一条曲线 ${EASING}，收到 "${easing}"（禁止第二套缓动）`);
  }

  const delay = opts.delay == null ? 0 : opts.delay;
  if (!Number.isFinite(delay) || delay < 0) throw new TypeError(`morphTransition: delay 必须是非负毫秒，收到 ${delay}`);

  const tail = `${ms}ms ${easing}${delay ? ` ${delay}ms` : ''}`;
  return list.map((p) => `${p} ${tail}`).join(', ');
}

/**
 * 读用户「减少动效」偏好（每次调用读一次：偏好在运行中可切换）。
 * 非浏览器环境（node 单测 / SSR）恒 false —— 本模块因此可被 node 直接 import。
 * @returns {boolean}
 */
export function reducedMotion() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches === true;
  } catch {
    return false;
  }
}
