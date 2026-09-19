// subagent-panel-open-geometry.spec.mjs — 子智能体窗「开场几何/动效」验收（#subpanel 缺陷最小修）。
//
// 缺陷（作者 2026-09-19 04:35）：点开子智能体面板时「先现大窗口再缩小为小窗口」。
// 诊断位（subpanel-diag / n-6904fdd7）五路取证否证了几何两段（首现帧即终态），
// 唯一定位的候选 = **整屏遮罩层 0.2s 不透明度淡入**（.flow-agent-overlay 上的
// `animation: fa-fade-in`）。修法 = 该面板去掉这处开场淡入（两处 animation 声明同删）。
//
// 本 spec 把判据固化为可真断言（非截图比对、非 hardcode 快照）：
//   A1 目标遮罩层在**首采样帧** computed opacity == 1；
//   A2 该元素的 computed animation-name 不含 fa-fade-in；
//   A3 采样窗内**每一帧** opacity 均为 1（无 0→1 斜坡）；
//   A4 几何首帧即终态：modal rect 首帧 == 末帧，且**无任何帧**超过终态（容差 1px）；
//   A5 多轮（3 次点击）均成立，且终态几何跨轮一致；
//   B1 静态规则锁：目标面板 POPUP_CSS 内 `animation: fa-fade-in` 零处、
//      `@keyframes fa-fade-in` 已清零（作者 2026-09-19 死定义清理令；原「保守保留」锁作废归档）、fullscreen 卡片几何四则一字未动、
//      无 width/height transition；
//   B2 静态规则锁：**其他面板**（.bgt-* 真源 bgTaskOutputPopup.js）自己的
//      bgt-fade-in 与其 reduced-motion 降级仍在（未被牵连）。
//
// 采样密度：headless 默认 rAF 被 vsync 限到 ~17Hz（0–600ms 仅 ~10 帧），因此本 spec
// 以 `--disable-frame-rate-limit --disable-gpu-vsync` 启动（实测 0–600ms ≈ 170 帧）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/subagent-panel-open-geometry.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'opengeom-root-session';
const DELEGATE_SID = 'delegate-OpenGeom-1a2b3c4d';
const TOL = 1;              // px 渲染抖动容差（与证据件一致：≤1px）
const SAMPLE_MS = 900;      // 采样窗（覆盖判据要求的 0–600ms，留并行抢核余量）

test.use({
  launchOptions: {
    args: ['--disable-frame-rate-limit', '--disable-gpu-vsync'],
  },
});

function agentStartFrame(sessionId) {
  return {
    type: 'agentStart',
    agentId: sessionId,
    name: 'OpenGeom',
    agentType: 'project-dispatcher',
    taskDescription: '开场几何验收',
    sessionId: ROOT_SID,
    rootSessionId: ROOT_SID,
    nodeSessionId: sessionId,
  };
}

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({
          type: 'historyPage', sessionId: msg.sessionId, messages: [], hasMore: false, offset: 0,
        }));
      } else if (msg.type === 'getActiveAgents') {
        // 快照必须**含**被测 agent：main.js 的 activeAgents 处理器以
        // `state.sessionBgAgents = {}` 全量重建（后端真值），空快照会把行与指示器
        // 一并清掉（下拉同帧收起）——那不是几何环境，无法做多轮采样。
        // 形状按 main.js:4146 的契约：{ sessionId, agentId, agentName, rootSessionId, kind }。
        ws.send(JSON.stringify({
          type: 'activeAgents',
          agents: [{
            sessionId: DELEGATE_SID, agentId: DELEGATE_SID, agentName: 'OpenGeom',
            rootSessionId: ROOT_SID, kind: 'Delegate', status: 'Processing',
          }],
        }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

function inject(page, obj) {
  return page.evaluate(async (o) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify(o) });
  }, obj);
}

const dropdownOpen = (page) => page.evaluate(() => {
  const d = document.getElementById('bgagent-dropdown');
  return !!d && !d.classList.contains('hidden');
});

/** 打开 Sub-Agents 面板（indicator 点击 → dropdown 展开）；幂等：已开则不点（再点会 toggle 关闭）。 */
async function openPanel(page) {
  await page.waitForSelector('#bgagent-indicator:not(.hidden)', { timeout: 8000 });
  for (let i = 0; i < 4; i++) {
    if (await dropdownOpen(page)) return;
    await page.click('#bgagent-indicator');
    await page.waitForTimeout(150);
  }
  expect(await dropdownOpen(page), 'Sub-Agents 下拉未能展开').toBe(true);
}

/**
 * 逐 requestAnimationFrame 采样器（点击前装好）。
 * 记录：目标遮罩层与卡片的 getBoundingClientRect + computed(opacity / animation-name /
 * width / height / max-width / position / transform) + 目标遮罩层 DOM 节点计数。
 */
const SAMPLER = () => {
  window.__og = { on: false, frames: [] };
  const rect = (el) => { const r = el.getBoundingClientRect();
    return { x: +r.x.toFixed(1), y: +r.y.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
  const one = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return Object.assign(rect(el), {
      op: parseFloat(cs.opacity), anim: cs.animationName, cw: cs.width, ch: cs.height,
      mw: cs.maxWidth, mh: cs.maxHeight, pos: cs.position, tr: cs.transform,
      disp: cs.display, vis: cs.visibility,
    });
  };
  const snap = (t0) => {
    window.__og.frames.push({
      t: +(performance.now() - t0).toFixed(1),
      overlay: one('.flow-agent-overlay'),
      overlayCount: document.querySelectorAll('.flow-agent-overlay').length,
      modal: one('.flow-agent-modal'),
    });
  };
  window.__og.start = () => {
    window.__og.frames = [];
    window.__og.on = true;
    const t0 = performance.now();
    const loop = () => { if (!window.__og.on) return; snap(t0); requestAnimationFrame(loop); };
    requestAnimationFrame(loop);
  };
  window.__og.stop = () => { window.__og.on = false; return window.__og.frames; };
};

/** 一轮：装采样器 → 点行开窗（真鼠标）→ 采样 → 关窗。返回采样帧。 */
async function sampleOpen(page, rowSel) {
  await openPanel(page);
  await page.evaluate(SAMPLER);
  await page.evaluate(() => window.__og.start());
  await page.click(rowSel, { timeout: 5000, force: true });
  await page.waitForTimeout(SAMPLE_MS);
  const frames = await page.evaluate(() => window.__og.stop());
  await page.evaluate(() => {
    const c = document.getElementById('bgagent-close') || document.getElementById('flow-agent-close');
    if (c) c.click();
  });
  await page.waitForTimeout(250);
  return frames;
}

function assertOpenGeometry(frames, round) {
  const withOverlay = frames.filter((f) => f.overlay);
  expect(withOverlay.length, `r${round}: 采样窗内未见目标遮罩层（frames=${frames.length}）`).toBeGreaterThan(0);
  const first = withOverlay[0];
  // A1 首采样帧即终态不透明度
  expect(first.overlay.op, `r${round}: 首现帧 overlay opacity 应为 1（实测 ${first.overlay.op}）`).toBe(1);
  // A2 无进场淡入动画
  expect(first.overlay.anim, `r${round}: overlay animation-name 不应含 fa-fade-in（实测 ${first.overlay.anim}）`)
    .not.toContain('fa-fade-in');
  // A3 全程无 0→1 斜坡
  const minOp = Math.min(...withOverlay.map((f) => f.overlay.op));
  expect(minOp, `r${round}: 采样窗内 overlay opacity 最小值应为 1（有斜坡）`).toBe(1);
  // 双挂载否证：可见层目标遮罩层节点计数恒 1
  expect(Math.max(...frames.map((f) => f.overlayCount))).toBeLessThanOrEqual(1);
  // A4 几何首帧即终态
  const md = frames.filter((f) => f.modal);
  expect(md.length, `r${round}: 未见 .flow-agent-modal`).toBeGreaterThan(0);
  const firstRect = md[0].modal;
  const lastRect = md[md.length - 1].modal;
  expect(Math.abs(firstRect.w - lastRect.w), `r${round}: modal 首帧宽 ${firstRect.w} != 末帧 ${lastRect.w}`)
    .toBeLessThanOrEqual(TOL);
  expect(Math.abs(firstRect.h - lastRect.h), `r${round}: modal 首帧高 ${firstRect.h} != 末帧 ${lastRect.h}`)
    .toBeLessThanOrEqual(TOL);
  const bigger = md.filter((f) => f.modal.w > lastRect.w + TOL || f.modal.h > lastRect.h + TOL);
  expect(bigger.length, `r${round}: 存在比终态更大的中间帧（大窗帧）`).toBe(0);
  return { frames: frames.length, sampled600: frames.filter((f) => f.t <= 600).length, first, firstRect, lastRect };
}

test.describe('子智能体窗 · 开场即终态（无两段几何 / 无进场淡入）', () => {
  test('3 轮点击：首帧 opacity=1、无 fa-fade-in、几何首帧即终态', async ({ page }) => {
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.message));
    await bootApp(page);
    await inject(page, agentStartFrame(DELEGATE_SID));
    await openPanel(page);
    await page.waitForSelector(`.bg-task-row[data-node-session-id="${DELEGATE_SID}"]`, { timeout: 8000 });

    const ROW = `.bg-task-row[data-node-session-id="${DELEGATE_SID}"]`;
    const report = [];
    for (let r = 1; r <= 3; r++) {
      // 每轮重放 agentStart：mock 的 getActiveAgents 快照恒空（面板展开时会把合成行清掉、
      // 计数归零 ⇒ indicator 隐藏），重放一帧即恢复行与指示器——与真实 agentStart 帧同构。
      await inject(page, agentStartFrame(DELEGATE_SID));
      await page.waitForTimeout(200);
      const frames = await sampleOpen(page, ROW);
      const s = assertOpenGeometry(frames, r);
      // 采样密度（采样充分性，不是产品判据）：整窗 ≥30 帧，且 0–600ms 段确实采到
      // （阈值取 10 而非 30：本 spec 与并行套件同跑时渲染线程被抢占，rAF 密度会掉档——
      //  产品判据 A1–A4 与帧密度无关，密度只用于保证「首帧」确实是被采到的首帧）。
      expect(s.frames, `r${r}: 采样帧数过少（${s.frames}）`).toBeGreaterThanOrEqual(30);
      expect(s.sampled600, `r${r}: 0–600ms 采样帧数过少（${s.sampled600}）`).toBeGreaterThanOrEqual(10);
      report.push(s);
    }
    // 终态几何跨轮稳定（1440×900 视口下 = max-width:720px × 80vh）
    for (const s of report) {
      expect(s.lastRect.w).toBe(report[0].lastRect.w);
      expect(s.lastRect.h).toBe(report[0].lastRect.h);
    }
    expect(pageErrors).toEqual([]);
  });
});

test.describe('子智能体窗 · 静态规则锁（改法面 + 其他面板未牵连）', () => {
  test('目标面板 POPUP_CSS：无 animation: fa-fade-in 用法、keyframes 已清零、几何四则未动', () => {
    const src = readFileSync(join(WEB, 'js', 'flowAgentPopup.js'), 'utf8');
    const css = src.slice(src.indexOf('const POPUP_CSS'), src.indexOf('</style>`'));
    // 用法零处（规则文本口径，不依赖行号）
    expect(css).not.toMatch(/animation:\s*fa-fade-in/);
    // 关键帧定义已由 2026-09-19 死定义清理令清零（原「保守保留」锁作废归档，非新增开放项）
    expect(css).not.toMatch(/@keyframes fa-fade-in\s*\{/);
    // fullscreen 卡片几何四则一字未动
    expect(css).toMatch(/\.flow-agent-overlay\.fullscreen \.flow-agent-modal \{[\s\S]*?width: 90%; max-width: 720px;/);
    expect(css).toMatch(/\.flow-agent-overlay\.fullscreen \.flow-agent-modal \{[\s\S]*?height: 80vh; max-height: 85vh;/);
    // 未引入 width/height 过渡（禁「量测后收缩」观感）
    expect(css).not.toMatch(/transition:[^;]*\b(width|height)\b/);
    // 其他面板的样式真源不在本文件
    expect(css).not.toMatch(/bgt-/);
  });

  test('其他面板 .bgt-*（bgTaskOutputPopup.js）：自己的 bgt-fade-in 与 reduced-motion 降级仍在', () => {
    const src = readFileSync(join(WEB, 'js', 'bgTaskOutputPopup.js'), 'utf8');
    expect(src).toMatch(/\.bgt-overlay \{[\s\S]*?animation: bgt-fade-in 0\.2s ease;/);
    expect(src).toMatch(/@keyframes bgt-fade-in\s*\{/);
    expect(src).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.bgt-overlay \{ animation: none; \}/);
    // 本缺陷的改法不得落到对照面板上
    expect(src).not.toMatch(/fa-fade-in/);
  });
});
