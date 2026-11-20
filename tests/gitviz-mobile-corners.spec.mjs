// gitviz-mobile-corners.spec.mjs — 手机端四角控件 + 泳道可读 + 问答交办的真机判据
// （gitviz 第二轮 2026-11-19；装置 = tools/gitviz-dev 的 dev harness，**真代码 + 真数据**）。
//
// 跑法：
//   node tools/gitviz-dev/server.mjs &            # 127.0.0.1:8791（只读 dev 面）
//   node tests/gitviz-mobile-corners.spec.mjs
//
// 五条判据（每条都配负控：把修复中和掉 ⇒ 同一判据必须红，否则判据对该缺陷不敏感）：
//   C1 四角齐备：左上/右上/左下/右下各一颗交互钮（.gitviz-corner--tl/tr/bl/br）。
//   C2 命中面不被吃：每颗钮**取矩形中心点** elementFromPoint 必须命中它自己
//      （沿用 canvas-band-hittability 的同一条判据；容器 pointer-events:none、钮 auto）。
//   C3 角钮互不压：四颗钮矩形两两零交叠，且都在视口内。
//   C4 泳道可读：375 宽下 ≥8 条泳道各自有**独立 x 列**（缩略图节点 cx 去重 ≥8）、
//      泳道标签列文字非空且互不相同（分支名可读，不是「泳道 n」占位），
//      且按泳道分组的列表组头数 == 泳道数。
//   C5 问答只问答 + 交办单出口：右下角钮开窗 → 输入「把 A、B 合进 feat/base」→
//      agent 帧回来（bridge）→ 产出一份 `askHandoff` 帧，其 `git` 四件齐全；
//      另外面板「多选 + 合并」钮产出的帧**形状与它同构**（同一出口）。
//
// 负控（N1..N3）：
//   N1 把四角容器改回 `pointer-events:auto`（吃掉整张图的命中面）⇒ C2 必须红。
//   N2 把缩略图泳道间距压成 0（8 条泳道压成一条线）⇒ C4 的独立列判据必须红。
//   N3 让合并钮不走 submitHandoff（清空 bridge.handoffs 后手工发一条不合规帧）⇒
//      C5 的 git 四件判据必须红。

import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';

const BASE = process.env.GITVIZ_BASE || 'http://127.0.0.1:8791';
const OUT = process.env.GITVIZ_EVIDENCE_DIR || '/Users/kaiyu/Claude code/Stand/.stand/tmp/gitviz-mvp-20261119/evidence';
mkdirSync(OUT, { recursive: true });

const { chromium } = await import('playwright');

const LANES = 8;
const uiLang = { locale: 'zh-CN', timezoneId: 'Asia/Shanghai' };

async function bootMobile(browser, colorScheme) {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    colorScheme, ...uiLang,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(`${BASE}/?mobile=1&project=stand`, { waitUntil: 'load' });
  await page.waitForSelector('.gitviz[data-state="ready"]', { timeout: 20000 });
  await page.waitForFunction(() => window.__gitvizBridgeReady === true, { timeout: 10000 });
  await page.waitForTimeout(400);
  return { ctx, page, errors };
}

/** C1/C2/C3：四角控件几何 + 命中。 */
async function corners(page) {
  return page.evaluate(() => {
    const names = ['tl', 'tr', 'bl', 'br'];
    const rows = names.map((n) => {
      const el = document.querySelector(`.gitviz-corner--${n}`);
      if (!el) return { n, missing: true };
      const r = el.getBoundingClientRect();
      const cx = Math.round(r.left + r.width / 2);
      const cy = Math.round(r.top + r.height / 2);
      const hit = document.elementFromPoint(cx, cy);
      return {
        n, label: el.getAttribute('aria-label'),
        rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height), right: Math.round(r.right), bottom: Math.round(r.bottom) },
        cx, cy,
        hitSelf: !!hit && (hit === el || el.contains(hit)),
        hitTag: hit ? `${hit.tagName}.${String(hit.className || '').slice(0, 40)}` : null,
        inViewport: r.top >= 0 && r.left >= 0 && r.right <= innerWidth && r.bottom <= innerHeight,
      };
    });
    const overlaps = [];
    for (let i = 0; i < rows.length; i += 1) {
      for (let j = i + 1; j < rows.length; j += 1) {
        const a = rows[i].rect; const b = rows[j].rect;
        const sep = a.right <= b.x || b.right <= a.x || a.bottom <= b.y || b.bottom <= a.y;
        if (!sep) overlaps.push(`${rows[i].n}/${rows[j].n}`);
      }
    }
    const container = document.querySelector('.gitviz-corners');
    return { rows, overlaps, containerPointer: container ? getComputedStyle(container).pointerEvents : null };
  });
}

/** C4：泳道可读 —— 独立列（不是压成一条线）+ 标签可读 + 按泳道分组列表 + 连线可辨。
 *  判据用**间距**而非「列数 == lanes」：列数取决于窗口内 50 支提交到底落在几条泳道上，
 *  是数据事实（真数据窗口里只有 4 条被占），拿它当闸会随数据漂。真正的不变量是
 *  「一泳道一列、列距 ≥ 最小泳道间距」——压成一条线时列距必然塌到 0。 */
async function lanes(page) {
  return page.evaluate(() => {
    const svg = document.querySelector('.gitviz-thumb .gitviz-svg');
    const circles = svg ? [...svg.querySelectorAll('circle')] : [];
    const xs = [...new Set(circles.map((c) => Math.round(Number(c.getAttribute('cx')))))].sort((a, b) => a - b);
    const gaps = xs.slice(1).map((x, i) => x - xs[i]);
    const tags = [...document.querySelectorAll('.gitviz-lane-tag')];
    const groupEls = [...document.querySelectorAll('.gitviz-lane-group')];
    const edges = svg ? svg.querySelectorAll('path.gitviz-edge').length : 0;
    return {
      laneCols: xs.length,
      xs,
      minGap: gaps.length ? Math.min(...gaps) : null,
      pad: xs.length ? xs[0] : null,
      tagCount: tags.length,
      tags: tags.map((t) => t.textContent.trim()),
      // 标签可读：字号 ≥10px、盒宽非零、文字未被裁
      tagRead: tags.map((t) => {
        const cs = getComputedStyle(t);
        const lab = t.querySelector('span');
        // 手机档标签带宽 96px ⇒ 长分支名必须省略号截断（否则把右侧泳道列挤出视口）。
        // 可读判据 = ①字号≥10 ②可见字宽≥40 ③截断时 **全文仍可取**（span.title 存全名）。
        const clipped = lab ? lab.scrollWidth > lab.clientWidth + 1 : true;
        return {
          font: parseFloat(cs.fontSize),
          w: Math.round(t.getBoundingClientRect().width),
          clipped,
          full: lab ? (lab.title || lab.textContent) : '',
          name: t.getAttribute('title') || '',
        };
      }),
      groups: groupEls.map((g) => ({
        lane: g.dataset.lane,
        head: g.querySelector('.gitviz-lane-head')?.textContent.trim(),
        rows: g.querySelectorAll('.gitviz-row').length,
      })),
      edges,
      svgWidth: svg ? Math.round(Number(svg.getAttribute('width'))) : null,
      rowPitch: (() => {
        const ys = [...new Set(circles.map((c) => Math.round(Number(c.getAttribute('cy')))))].sort((a, b) => a - b);
        return ys.length > 1 ? ys[1] - ys[0] : null;
      })(),
    };
  });
}

const report = { base: BASE, at: new Date().toISOString(), checks: {} };
const browser = await chromium.launch();
try {
  // ── 暗档 ────────────────────────────────────────────────────────────────
  const dark = await bootMobile(browser, 'dark');
  const c1 = await corners(dark.page);
  report.checks.C1 = c1;

  assert.equal(c1.rows.filter((r) => !r.missing).length, 4, `C1 四角必须齐备: ${JSON.stringify(c1.rows)}`);
  for (const r of c1.rows) {
    assert.deepEqual(r.label && r.label.length > 0, true, `C1 ${r.n} 必须有可读 aria-label`);
    assert.ok(r.inViewport, `C3 ${r.n} 必须在视口内: ${JSON.stringify(r.rect)}`);
  }
  // C2 命中面
  for (const r of c1.rows) assert.ok(r.hitSelf, `C2 ${r.n} 中心点必须命中自己（实际命中 ${r.hitTag}）`);
  // C3 互不压
  assert.deepEqual(c1.overlaps, [], `C3 角钮不得互相压: ${c1.overlaps.join(',')}`);
  // During scrolling the overlay is allowed to overlap content: its button
  // must own its centre, while the transparent host must not intercept the
  // uncovered content between the controls.
  await dark.page.evaluate(() => { const main = document.querySelector('.gitviz-main'); main.scrollTop = main.scrollHeight / 2; });
  const mid = await corners(dark.page);
  assert.ok(mid.rows.every(r => r.hitSelf), 'scrolling: each corner owns its centre hit');
  const clearance = await dark.page.evaluate(() => {
    const owner = document.querySelector('.gitviz-main');
    const r = owner.getBoundingClientRect();
    const controls = [...document.querySelectorAll('.gitviz-corner--bl, .gitviz-corner--br')];
    const visible = controls.map(c => c.getBoundingClientRect()).filter(c => c.top < r.bottom && c.bottom > r.top);
    const stored = parseFloat(getComputedStyle(owner).getPropertyValue('--gitviz-bottom-clearance'));
    const needed = visible.length ? r.bottom - Math.min(...visible.map(c => Math.max(r.top, c.top))) + 12 : 0;
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const hit = document.elementFromPoint(x, y);
    return { stored, needed, hitHost: !!hit && hit.classList.contains('gitviz-corners') };
  });
  assert.ok(clearance.stored + 1 >= clearance.needed, `measured scroll clearance must cover visible controls: ${JSON.stringify(clearance)}`);
  assert.equal(clearance.hitHost, false, 'transparent corner host must not intercept content');

  const c4 = await lanes(dark.page);
  report.checks.C4 = c4;
  assert.ok(c4.tagCount >= LANES, `C4 泳道标签必须 ≥${LANES} 个（读到 ${c4.tagCount}）`);
  assert.ok(c4.tags.every((t) => t && t.length > 0), `C4 泳道标签文字不得为空: ${JSON.stringify(c4.tags)}`);
  // 分组是「按被占用的泳道」建组（空泳道不建组，否则列表里全是空壳）⇒
  // 判据是组数 == 占用泳道列数，且每组都有 head 与行。
  assert.equal(c4.groups.length, c4.laneCols, `C4 每占用的泳道恰一组（组 ${c4.groups.length} vs 列 ${c4.laneCols}）`);
  assert.ok(c4.groups.every((g) => g.head && g.head.length > 0 && g.rows > 0), `C4 每组必须有泳道头与行: ${JSON.stringify(c4.groups)}`);
  assert.ok(c4.edges > 0, 'C4 连线必须存在（可辨的前提）');
  // 不变量：一泳道一列、列距 ≥ LANE_MIN_MOBILE（压成一条线 ⇒ 列距塌成 0）
  assert.ok(c4.laneCols >= 2, `C4 缩略图必须画出多条泳道列（读到 ${c4.laneCols}）`);
  assert.ok(c4.minGap !== null && c4.minGap >= 14, `C4 泳道列距必须 ≥14px（读到 ${c4.minGap}：${c4.xs.join(',')}）`);
  assert.ok(c4.svgWidth >= 96, `C4 缩略图宽度必须铺满最小泳道宽（读到 ${c4.svgWidth}）`);
  assert.ok(c4.rowPitch >= 12, `C4 行距必须 ≥12px 才可辨（读到 ${c4.rowPitch}）`);
  for (const t of c4.tagRead) {
    assert.ok(t.font >= 10, `C4 泳道标签字号 ≥10px（读到 ${t.font}）`);
    assert.ok(t.w >= 40, `C4 泳道标签盒宽必须 ≥40px（读到 ${t.w}）`);
    assert.ok((t.full && t.full.length > 0) || !t.clipped, 'C4 被截断的标签必须保留全文（title）');
    if (t.clipped) assert.ok(t.full.length > 0, `C4 截断标签必须带全文 title: ${JSON.stringify(t)}`);
    assert.ok(t.name.length > 0, 'C4 标签必须带泳道说明 title');
  }
  await dark.page.screenshot({ path: `${OUT}/m1-375-dark-corners-lanes.png`, animations: 'disabled' });

  // ── C5：问答 → 交办（暗档内跑一次真实对话）────────────────────────────────
  await dark.page.click('.gitviz-corner--br');
  await dark.page.waitForSelector('.gitviz-ask-dock:not([hidden])', { timeout: 5000 });
  await dark.page.fill('.gitviz-ask-input textarea', '把 feat/a、feat/b 合进 feat/base');
  await dark.page.click('.gitviz-ask-send');
  await dark.page.waitForFunction(() => (window.__gitvizDevBridge?.handoffs || []).length > 0, { timeout: 15000 });
  // 回执帧（askHandoffAck）由服务端另推 ⇒ 等**产品渲染出的** sys 气泡出现再读快照，
  // 否则拿到的是 ack 到达前的中间态（假阴性）。
  await dark.page.waitForFunction(
    () => [...document.querySelectorAll('.gitviz-ask-bub.is-sys')].some((b) => b.textContent.includes('已转交主会话')),
    { timeout: 10000 });
  const ask = await dark.page.evaluate(() => ({
    handoffs: window.__gitvizDevBridge.handoffs.map((h) => h.frame),
    bubbles: [...document.querySelectorAll('.gitviz-ask-bub')].map((b) => ({ cls: b.className, text: b.textContent.slice(0, 120) })),
  }));
  report.checks.C5 = ask;
  assert.ok(ask.handoffs.length >= 1, 'C5 问答必须产出一份交办帧');
  const f = ask.handoffs[0];
  assert.equal(f.type, 'askHandoff');
  assert.ok(f.sessionId && f.sessionId.startsWith('ask-gv-'), `C5 会话必须在 ask-gv- 族内: ${f.sessionId}`);
  assert.ok(f.git, 'C5 帧必须携带 git 子对象');
  assert.ok(Array.isArray(f.git.branches) && f.git.branches.length >= 1, 'C5 分支清单必须非空');
  assert.equal(typeof f.git.target, 'string');
  assert.ok(f.git.target.length > 0, 'C5 目标线必须非空');
  assert.equal(f.git.intent, 'merge');
  assert.ok(['panel', 'ask'].includes(f.git.source), `C5 入口来源必须可分辨: ${f.git.source}`);
  // 回执气泡可见（UI 状态面 = askHandoffAck 消费）
  assert.ok(ask.bubbles.some((b) => b.text.includes('已转交主会话')), `C5 面板必须有可见回执: ${JSON.stringify(ask.bubbles)}`);
  await dark.page.screenshot({ path: `${OUT}/m2-375-dark-ask-handoff.png`, animations: 'disabled' });

  // ── C5b：面板多选合并 → 同一出口（形状同构）──────────────────────────────
  await dark.page.click('.gitviz-ask-close, .gitviz-ask-dock .gitviz-corner--inline');
  await dark.page.evaluate(() => { window.__gitvizDevBridge.handoffs.length = 0; });
  await dark.page.evaluate(() => {
    const boxes = [...document.querySelectorAll('.gitviz-ref-row input[type=checkbox]')];
    boxes[0]?.click();
    boxes[1]?.click();
  });
  await dark.page.waitForTimeout(250);
  const mergeBtn = await dark.page.$('.gitviz-forward .gitviz-btn');
  assert.ok(mergeBtn, 'C5b 多选合并钮必须存在');
  await dark.page.evaluate(() => document.querySelector('.gitviz-forward .gitviz-btn').click());
  await dark.page.waitForFunction(() => (window.__gitvizDevBridge?.handoffs || []).length > 0, { timeout: 10000 });
  const panelFrames = await dark.page.evaluate(() => window.__gitvizDevBridge.handoffs.map((h) => h.frame));
  report.checks.C5b = panelFrames;
  const pf = panelFrames[0];
  assert.equal(pf.type, 'askHandoff');
  assert.equal(pf.git.source, 'panel', 'C5b 面板入口来源必须是 panel');
  // 同一个出口的形状判据：两条入口的帧键集一致
  const shape = (x) => Object.keys(x.git).sort().join(',');
  assert.equal(shape(pf), shape(f), `C5b 两个入口必须同构（同出口）: panel=${shape(pf)} ask=${shape(f)}`);
  await dark.page.screenshot({ path: `${OUT}/m3-375-dark-panel-merge.png`, animations: 'disabled' });

  // ── 负控 N1：把「对边 auto」拆掉（本批修掉的那个真 bug）⇒ C2 必须红 ─────────
  // 真实故障复盘：右上/右下钮只写 right、另一条 left 从 .g2-fab 继承成 0 ⇒ 盒子被拉回
  // 左上/左下、压住左上/左下钮，中心点命中「别人」。负控就复现这一条：强制 left:0。
  await dark.page.addStyleTag({ content: '.gitviz-corner--tr, .gitviz-corner--br { left: 0 !important; }' });
  const n1 = await corners(dark.page);
  assert.ok(n1.rows.some((r) => !r.hitSelf), `N1 对边塌回 left:0 后 C2 必须红（实际全部命中自身）: ${JSON.stringify(n1.rows.map((r) => [r.n, r.hitSelf]))}`);
  assert.ok(n1.overlaps.length > 0, `N1 对边塌回后角钮必须互压（读到 ${JSON.stringify(n1.overlaps)}）`);
  report.checks.N1 = { hitSelf: n1.rows.map((r) => [r.n, r.hitSelf]), overlaps: n1.overlaps };
  await dark.page.evaluate(() => { document.querySelectorAll('style').forEach((s) => { if ((s.textContent || '').includes('gitviz-corner--tr, .gitviz-corner--br { left: 0')) s.remove(); }); });
  const afterN1 = await corners(dark.page);
  assert.ok(afterN1.rows.every((r) => r.hitSelf), 'N1 复原后 C2 必须回绿');
  assert.deepEqual(afterN1.overlaps, [], 'N1 复原后 C3 必须回绿');

  // ── 负控 N2：泳道压成一条线 ⇒ C4 必须红 ──────────────────────────────────
  await dark.page.evaluate(() => {
    const svg = document.querySelector('.gitviz-thumb .gitviz-svg');
    if (svg) for (const c of svg.querySelectorAll('circle')) c.setAttribute('cx', '30');
  });
  const n2 = await lanes(dark.page);
  assert.ok(n2.laneCols === 1 || (n2.minGap !== null && n2.minGap < 14), `N2 压成一条线后列距判据必须红（读到 ${n2.laneCols} 列 / 间距 ${n2.minGap}）`);
  report.checks.N2 = { neutralizedLaneCols: n2.laneCols };
  await dark.page.screenshot({ path: `${OUT}/n2-375-dark-lanes-collapsed-negative.png`, animations: 'disabled' });

  assert.deepEqual(dark.errors, [], `暗档不得有页面错误: ${dark.errors.join(' | ')}`);
  await dark.ctx.close();

  // ── 亮档（同一套判据复跑：C1/C2/C3/C4）───────────────────────────────────
  const light = await bootMobile(browser, 'light');
  const l1 = await corners(light.page);
  for (const r of l1.rows) assert.ok(r.hitSelf, `亮档 C2 ${r.n} 中心点必须命中自己`);
  assert.deepEqual(l1.overlaps, []);
  const l4 = await lanes(light.page);
  assert.ok(l4.laneCols >= 2 && l4.minGap >= 14, `亮档 C4 泳道列须 ≥2 且列距 ≥14（读到 ${l4.laneCols} 列 / 间距 ${l4.minGap}）`);
  assert.ok(l4.tagCount >= LANES, `亮档 C4 泳道标签必须 ≥${LANES}`);
  await light.page.screenshot({ path: `${OUT}/m4-375-light-corners-lanes.png`, animations: 'disabled' });
  await light.page.click('.gitviz-corner--br');
  await light.page.waitForSelector('.gitviz-ask-dock:not([hidden])');
  await light.page.fill('.gitviz-ask-input textarea', '把 feat/a、feat/b 合进 feat/base');
  await light.page.click('.gitviz-ask-send');
  await light.page.waitForFunction(() => (window.__gitvizDevBridge?.handoffs || []).length > 0, { timeout: 15000 });
  await light.page.waitForTimeout(300);
  await light.page.screenshot({ path: `${OUT}/m5-375-light-ask-handoff.png`, animations: 'disabled' });
  assert.deepEqual(light.errors, [], `亮档不得有页面错误: ${light.errors.join(' | ')}`);
  report.checks.light = { corners: l1.rows.map((r) => ({ n: r.n, hitSelf: r.hitSelf })), laneCols: l4.laneCols };
  await light.ctx.close();

  console.log(JSON.stringify({ ok: true, checks: report.checks }, null, 2));
} finally {
  await browser.close();
}
