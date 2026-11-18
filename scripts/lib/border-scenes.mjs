// border-scenes.mjs — dark-border-weight 批的场景单一来源（audit / 闸 / 采集共用）。
//
// 为什么收成一份：本批要同时产出 ①清点表（audit）②可执行闸（check）③前后对照
// 证据（capture）。三处若各写一份场景与打桩配方，就会重演本仓历史上「改前改后是
// 两个人两次手感」的老问题 —— 对照不成立。故场景表、夹具、以及页内探针
// （COLLECT_BORDERS_FN）只有这一份。
//
// 装置 = scripts/lib/visual-harness.mjs（零端口：page.route 从磁盘服务 web 根 +
// MockWS 走真实 ws.js 分发 + 真 Chromium）。因此读数与截图都是真渲染真 DOM。

/** 对话流夹具（与 visual-baseline 同形，短行以免画面高度漂移）。 */
export const MESSAGES = [
  { type: 'user', text: '把这份报告的重点整理成三条，并说明数据来源。', timestamp: 1757000000000 },
  {
    type: 'ai',
    text: '好的，三条重点如下：\n1. 先清点边框，给暗档一个可判定的口径；\n2. 再收敛到单一令牌来源；\n3. 最后立一条可执行闸。\n\n---\n\n数据来源：tests/fixtures/history-replay/fixture.json',
  },
  { type: 'tool', label: 'Read("scripts/check-glass-unify.mjs")', summary: '128 lines', isError: false, input: '{"path":"scripts/check-glass-unify.mjs"}', content: '1\t// gate\n' },
  { type: 'user', text: '继续。', timestamp: 1757000060000 },
  { type: 'ai', text: '已按计划推进：先给清点表，再收敛令牌。' },
];

export const CANVAS_MD = [
  '# 边框清点说明',
  '',
  '这段正文用来验证面板/卡片的边界在暗档下的可见度：**粗体**、`行内代码`、列表。',
  '',
  '- 清点口径：边框色（合成后）÷ 相邻背景，WCAG 与 ΔE00 两条读数都给',
  '- 判定：暗档同一面的边框-背景对比度不得显著高于亮档',
  '',
  '> 判定：收敛后卡片/行的边界仍要能被感知。',
].join('\n');

export const CANVAS_CODE = [
  '// border fixture — 仅用于渲染对照',
  'export function borderWeight(theme) {',
  '  return theme === "dark" ? "hairline" : "hairline";',
  '}',
].join('\n');

/**
 * 场景表。
 * - setup(s)：建页后、探针前的操作（复用 visual-baseline 的入口点击，不直写 DOM）。
 * - viewports：宽度/高度（手机档是真手机视口 = 作者在手机上看到的全屏页）。
 */
export const BORDER_SCENES = [
  {
    name: 'shell-chat',
    why: '主壳 + 对话流（顶栏/侧栏/气泡/工具卡/输入条）',
    viewports: [{ width: 1440, height: 900 }],
    setup: async (s) => { await s.boot({ messages: MESSAGES }); },
  },
  {
    name: 'settings',
    why: '设置面板（卡/分组/行/输入件）',
    viewports: [{ width: 1440, height: 900 }],
    setup: async (s) => {
      await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
      // 程序化点击 = 产品自身的 click 处理链（手机档下 #activity-bar 被
      // body.mobile-layer 隐藏，物理点击不可达；面板本体同一份 #settings-overlay）。
      await s.page.evaluate(() => document.getElementById('settings-btn').click());
      await s.page.waitForSelector('#settings-overlay:not(.hidden)', { timeout: 10000 }).catch(() => {});
      await s.page.waitForTimeout(700);
    },
  },
  {
    name: 'dropdown',
    why: '后台任务下拉（浮层/行/角标）',
    viewports: [{ width: 1440, height: 900 }],
    clock: 1757000553000,
    setup: async (s) => {
      const sid = await s.boot();
      for (const a of [
        { agentId: 'a1', name: 'explorer', description: '扫描前端依赖', kind: 'local' },
        { agentId: 'a2', name: 'reviewer', description: '三维审查', kind: 'remote' },
      ]) await s.frame({ type: 'agentStart', sessionId: sid, rootSessionId: sid, ...a });
      for (const t of [
        { taskId: 't1', description: '构建前端 bundle', startedAt: 1757000000000, kind: 'local', origin: 'nebula', originLabel: 'Nebula' },
        { taskId: 't2', description: '跑 e2e 冒烟', startedAt: 1757000001000, kind: 'remote', origin: 'node', originLabel: 'DESKTOP-V7EUCHT' },
      ]) await s.frame({
        type: 'backgroundTaskUpdate', sessionId: sid, rootSessionId: sid,
        taskId: t.taskId, description: t.description, status: 'running',
        startedAt: t.startedAt, heartbeat: null, kind: t.kind, origin: t.origin, originLabel: t.originLabel,
      });
      await s.page.waitForTimeout(300);
      await s.page.click('#bg-indicator');
      await s.page.waitForSelector('#bg-dropdown:not(.hidden) .bg-task-row', { timeout: 8000 });
    },
  },
  {
    name: 'modal',
    why: '确认模态（遮罩 + 玻璃卡 + 按钮）',
    viewports: [{ width: 1440, height: 900 }],
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate(() => { window.__showConfirm('删除后不可恢复，确定要删除这 3 个文件吗？', '删除确认', () => {}, {}); });
      await s.page.waitForSelector('#modal-overlay.on', { timeout: 8000 });
    },
  },
  {
    name: 'canvas',
    why: 'canvas 页签 + markdown 阅读面（面板/页签/文档体）',
    viewports: [{ width: 1440, height: 900 }],
    setup: async (s) => {
      await s.boot();
      await s.page.evaluate((content) => {
        window.dispatchEvent(new CustomEvent('workspace-open-item', {
          detail: { id: 'file:/tmp/border-note.md', itemType: 'markdown', title: 'border-note.md', content, absPath: '/tmp/border-note.md', size: content.length, mtimeMs: 1757000000000 },
        }));
      }, CANVAS_MD);
      await s.page.waitForSelector('#canvas-panel .canvas-tab-pane[data-tab-id="file:/tmp/border-note.md"]', { state: 'visible', timeout: 20000 });
      await s.page.waitForTimeout(900);
    },
  },
  {
    name: 'explorer',
    why: '文件树（行/缩进/选中态）',
    viewports: [{ width: 1440, height: 900 }],
    setup: async (s) => {
      await s.boot();
      await s.page.waitForSelector('#explorer-tree .explorer-item', { timeout: 10000 });
      await s.page.click('#explorer-tree .explorer-folder[data-path="docs"]');
      await s.page.waitForSelector('.explorer-dir-wrapper[data-path="docs"] .explorer-children .explorer-item', { timeout: 10000 });
    },
  },
  {
    name: 'mobile-chat',
    why: '手机全屏主壳（作者真机所见形态）',
    viewports: [{ width: 390, height: 844, mobile: true }],
    setup: async (s) => { await s.boot({ messages: MESSAGES }); },
  },
  {
    name: 'mobile-settings',
    why: '手机全屏设置页',
    viewports: [{ width: 390, height: 844, mobile: true }],
    setup: async (s) => {
      await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
      // 程序化点击 = 产品自身的 click 处理链（手机档下 #activity-bar 被
      // body.mobile-layer 隐藏，物理点击不可达；面板本体同一份 #settings-overlay）。
      await s.page.evaluate(() => document.getElementById('settings-btn').click());
      await s.page.waitForSelector('#settings-overlay:not(.hidden)', { timeout: 10000 }).catch(() => {});
      await s.page.waitForTimeout(700);
    },
  },
];

/** 手机层激活断点（= js/mobile/bootstrap.js 的 MOBILE_MQ 唯一判据 max-width:768px）。 */
export const MOBILE_BREAKPOINT = 768;

/**
 * 手机档形态自证：`body.mobile-layer` 是否真在场。
 *
 * 为什么必须有这一步（本批实测的坑）：手机交互层的激活判据**只是视口宽度**
 * （matchMedia('(max-width: 768px)')，见 js/mobile/bootstrap.js:44），不看 UA、
 * 不看触屏能力；所以 Playwright 里只要 viewport ≤768 就会真进手机层。但反过来，
 * 一旦某次采集把手机场景当窄桌面窗宽渲染（视口 >768，或扩展点把 mobile-layer
 * 判定换掉），产出物会是一张「窄桌面截图」而看的人以为那是手机形态 —— 历史上
 * 这类「验收形态不是作者真实看到的页」正是返工源。故手机场景在拍/量之前先自证
 * 该层在场，并把读数写进证据。
 * @returns {{active:boolean, hasMobileHtmlLayer:boolean, viewport:string, dpr:number, probe:any}}
 */
export async function mobileLayerState(page, { waitMs = 3000 } = {}) {
  const read = () => page.evaluate(() => ({
    active: document.body.classList.contains('mobile-layer'),
    hasMobileHtmlLayer: document.documentElement.classList.contains('mobile-html-layer'),
    viewport: `${window.innerWidth}x${window.innerHeight}`,
    dpr: window.devicePixelRatio,
    probe: (window.__nfMobileLayer && { active: window.__nfMobileLayer.active, version: window.__nfMobileLayer.version }) || null,
  }));
  const deadline = Date.now() + waitMs;
  let s = await read();
  while (!s.active && Date.now() < deadline) { await page.waitForTimeout(100); s = await read(); }
  return s;
}

/** 手机场景的形态自证（非手机视口返回 null）。 */
export async function assertMobileForm(page, vp, sceneName, onFail) {
  if (!(vp.width <= MOBILE_BREAKPOINT)) return null;
  const st = await mobileLayerState(page);
  const ok = st.active && st.viewport === `${vp.width}x${vp.height}`;
  if (!ok) onFail(`手机形态不成立：${sceneName} 视口 ${vp.width}x${vp.height} ⇒ body.mobile-layer=${st.active}, 实渲染 ${st.viewport}`);
  return { scene: sceneName, ...st, ok };
}

/**
 * 页内探针：枚举可见元素的边框类视觉，读 computed 原文与相邻底色。
 * 在页面上下文执行（page.evaluate）—— 传回可 JSON 化的行。
 *
 * 边框类视觉四种全收：
 *   1 真 border（四边各自判：宽 >= 0.5px 且 style 非 none/hidden 且色 alpha > 0.02）
 *   2 outline（同上）
 *   3 box-shadow 里的 inset 描边环（inset 0 0 0 Npx COLOR 形态，N >= 0.5）
 *   4 用 background-image 画的发丝线（本探针不登记；它没有独立 border-color，
 *     由静态清点负责 —— 见 report 另表）
 *
 * 相邻底色两条：
 *   bgSelf  = 从根到本元素的 background-color 层叠合成（含本元素自身）
 *   bgOuter = 从根到父元素的层叠合成（不含本元素，即边框外侧看到的底）
 */
export const COLLECT_BORDERS_FN = () => {
  const px = (v) => { const n = parseFloat(v); return Number.isFinite(n) ? n : 0; };
  const parse = (s) => {
    if (!s) return null;
    const m = /^rgba?\(([^)]+)\)$/.exec(s);
    if (!m) return null;
    const p = m[1].split(/[\s,/]+/).filter(Boolean);
    const r = parseFloat(p[0]); const g = parseFloat(p[1]); const b = parseFloat(p[2]);
    const a = p.length >= 4 ? parseFloat(p[3]) : 1;
    return [r, g, b, Number.isFinite(a) ? a : 1];
  };
  const over = (fg, bg) => { const a = fg[3]; return [0, 1, 2].map((i) => fg[i] * a + bg[i] * (1 - a)); };
  const chain = (el) => {
    const stack = [];
    let n = el;
    while (n) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c[3] > 0) stack.push(c);
      n = n.parentElement;
    }
    let acc = [255, 255, 255];
    for (let i = stack.length - 1; i >= 0; i--) acc = over(stack[i], acc);
    return acc;
  };
  const className = (el) => (el.className && typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 4).join('.') : '');
  const sig = (el) => `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${className(el) ? '.' + className(el) : ''}`;
  const insetShadows = (shadow) => {
    if (!shadow || shadow === 'none') return [];
    const out = [];
    const parts = []; let depth = 0; let cur = '';
    for (const ch of shadow) {
      if (ch === '(') depth++;
      if (ch === ')') depth--;
      if (ch === ',' && depth === 0) { parts.push(cur); cur = ''; continue; }
      cur += ch;
    }
    parts.push(cur);
    for (const raw of parts) {
      const t = raw.trim();
      if (!/^inset\b/.test(t)) continue;
      const lens = [...t.matchAll(/(-?[\d.]+)px/g)].map((mm) => parseFloat(mm[1]));
      const color = t.replace(/^inset\b/, '').replace(/(-?[\d.]+)px/g, '').trim();
      // 描边环（4 长值且 spread > 0）与**单边高光线**（inset 0 Npx 0 0 COLOR）
      // 都算「用 box-shadow 模拟的描边」，两类各自登记。
      const spread = lens.length >= 4 ? lens[3] : 0;
      const dx = lens[0] || 0; const dy = lens[1] || 0; const blur = lens[2] || 0;
      if (spread >= 0.5) out.push({ sub: 'ring', width: spread, color, raw: t, edge: 'ring' });
      else if (dx === 0 && blur === 0 && Math.abs(dy) >= 0.5) out.push({ sub: 'edge', width: Math.abs(dy), color, raw: t, edge: dy < 0 ? 'bottom' : 'top' });
      else if (dy === 0 && blur === 0 && Math.abs(dx) >= 0.5) out.push({ sub: 'edge', width: Math.abs(dx), color, raw: t, edge: dx < 0 ? 'right' : 'left' });
    }
    return out;
  };

  const rows = [];
  const vpW = window.innerWidth; const vpH = window.innerHeight;
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect();
    // 面/线判定：两个方向都至少 1px，且至少要有一条边 >= 4px —— 后者放行
    // **1px 单边线**（hr / border-top 分隔线），它们正是本批要清的「分隔线」一档；
    // 纯 1x1 的角件/装饰点不进表。
    if (r.width < 1 || r.height < 1) continue;
    if (r.width < 4 && r.height < 4) continue;
    if (r.bottom < 0 || r.right < 0 || r.top > vpH || r.left > vpW) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.05) continue;
    const bgSelf = chain(el);
    const bgOuter = el.parentElement ? chain(el.parentElement) : [255, 255, 255];
    const base = { sig: sig(el), tag: el.tagName.toLowerCase(), w: Math.round(r.width), h: Math.round(r.height), bgSelf, bgOuter };

    for (const side of ['Top', 'Right', 'Bottom', 'Left']) {
      const style = cs[`border${side}Style`];
      const w = px(cs[`border${side}Width`]);
      const color = cs[`border${side}Color`];
      if (style === 'none' || style === 'hidden' || w < 0.5) continue;
      const c = parse(color);
      if (!c || c[3] <= 0.02) continue;
      rows.push({ ...base, kind: 'border', prop: `border-${side.toLowerCase()}`, width: w, color: c, edge: String(side) });
    }
    const ow = px(cs.outlineWidth); const os = cs.outlineStyle;
    if (os !== 'none' && ow >= 0.5) {
      const c = parse(cs.outlineColor);
      if (c && c[3] > 0.02) rows.push({ ...base, kind: 'outline', prop: 'outline', width: ow, color: c, edge: 'all' });
    }
    for (const ring of insetShadows(cs.boxShadow)) {
      const c = parse(ring.color);
      if (!c || c[3] <= 0.02) continue;
      rows.push({ ...base, kind: ring.sub === 'ring' ? 'inset-ring' : 'inset-edge', prop: `box-shadow(${ring.edge})`, width: ring.width, color: c, edge: ring.edge, raw: ring.raw });
    }
  }
  return rows;
};
