#!/usr/bin/env node
// e2e-header-collision.cjs — Header 按钮碰撞体积 v2 批（2026-09-09 11:36 作者裁定）
// 多宽度扫描 + 优先级前缀 + 迟滞 + 内容驱动回归 + before/after 截图对照。
//
// 缺陷背景：①旧实现固定集含 memory/用量环/后台两胶囊——窄宽时永不隐藏 →
// 绝对居中的 center 与右簇重叠/挤压（作者 11:36 截图实证）；②11:36 裁定
// 后台任务/后台agent 优先级提到最高可隐藏档（最后才隐藏），memory/用量环
// 降为中档，右簇 P1→P5 为低档；③碰撞体积全实测（废除 memW 兜底 28 /
// CS_GAP=8 经验常数）；④临界宽迟滞防抖。
//
// 自包含打桩（同 shot-header-name.cjs 路线）：page.route 从磁盘服务真实前端
// 文件（零端口零进程，不碰 8080），MockWebSocket 注入真实 ws.js 分发路径；
// 用量环/后台任务/后台agent/pending-ask 全部走生产帧链路（usageUpdate /
// activeBgTasks / activeAgents / askUser），内存按钮按 shot 先例 seeded。
//
// 用法：
//   node scripts/e2e-header-collision.cjs             # after——当前磁盘树（硬断言，失败 exit 1）
//   node scripts/e2e-header-collision.cjs --baseline  # before——js/main.js 以 git
//                                                     #   show HEAD:<path> 应答（复现缺陷，仅报告）
//
// 断言（扫描宽 360→1920 步进 20，每宽执行）：
//   O1 零重叠：#header 内全部可见叶子控件两两 getBoundingClientRect 盒检测（1px 容差）
//   O2 前缀不变量：隐藏集 = 优先级链（CHAIN，与 main.js 保持同步）在内容可见
//      候选上的后缀——低优先级先隐，不高低倒挂
//   O3 胶囊生存：hw ≥ 实测地板（固定集+两胶囊可入座的物理下限）时
//      #bg-indicator 与 #bgagent-indicator 必须可见（11:36 裁定：最后才隐藏）
//   O4 无换行：header 高度全程恒定（flex-wrap 禁用）
//   O5 名牌全宽：#session-name Range 宽 ≤ 盒宽+1 且无横向溢出（0907 裁定）
//   H1 迟滞：bypass 隐藏临界宽 ±60px 逐像素下/上扫描，各恰一次切换，且
//      显示点 > 隐藏点（死区非空）；死区内 ±1px 抖动 ×6 状态不变
//   C1 内容驱动：清空后台任务/agent/pending 帧 → 胶囊退场仍零重叠；重灌 →
//      胶囊以最高可隐藏档回归且前缀不变量保持
//
// 产出：.nebflow/shots-headercollision/{before|after}-W<width>-{dark|light}.png
// （关键宽度 1920/1280/1049*/900/720/560/375 × 明暗；*作者 11:36 截图同款宽度）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT_DIR = join(ROOT, '.nebflow', 'shots-headercollision');
const MODE = process.argv.includes('--baseline') ? 'before' : 'after';
const BASELINE_COMMIT = 'HEAD';
// 被本批修改、baseline 模式需用 git blob 应答的文件
const CHANGED = ['js/main.js'];
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'shot-root';
const SHOT_WIDTHS = [1920, 1280, 1049, 900, 720, 560, 524, 375];
// 扫描梯：360→1920 步进 20（验收口径）
const LADDER = [];
for (let w = 360; w <= 1920; w += 20) LADDER.push(w);
// 优先级链（生存优先级 高→低）——与 main.js CHAIN 严格同步
const CHAIN = ['bg-indicator', 'bgagent-indicator', 'pending-asks-indicator',
  'memory-btn', 'header-model-info', 'daemon-btn', 'reminder-btn',
  'search-btn', 'voice-toggle-btn', 'bypass-dropdown'];
// 迟滞带宽——与 main.js HYST_PX 严格同步
const HYST_PX = 12;

const baselineBlob = {};
if (MODE === 'before') {
  for (const rel of CHANGED) {
    baselineBlob['/' + rel] = execFileSync(
      'git', ['show', `${BASELINE_COMMIT}:src/main/resources/web/${rel}`],
      { cwd: ROOT, maxBuffer: 30 * 1024 * 1024 });
  }
}

/** In-page probe: header geometry + all assertions' raw data (DOM-derived,
 *  works on both old and new implementations — no __headerLayout dependency). */
const PROBE = () => {
  const CHAIN = ['bg-indicator', 'bgagent-indicator', 'pending-asks-indicator',
    'memory-btn', 'header-model-info', 'daemon-btn', 'reminder-btn',
    'search-btn', 'voice-toggle-btn', 'bypass-dropdown'];
  const LEAVES = ['sidebar-toggle', 'header-model-info', 'session-name', 'memory-btn',
    'search-btn', 'bypass-toggle', 'voice-toggle-btn', 'reminder-btn', 'daemon-btn',
    'bg-indicator', 'pending-asks-indicator', 'bgagent-indicator', 'canvas-toggle-btn'];
  const vis = (el) => {
    if (!el) return false;
    const cs = getComputedStyle(el);
    return cs.display !== 'none' && cs.visibility !== 'hidden' && el.getBoundingClientRect().width > 0.5;
  };
  const rect = (id) => { const el = document.getElementById(id); return el ? el.getBoundingClientRect() : null; };
  const header = document.getElementById('header');
  const hr = header.getBoundingClientRect();
  // O1 pairwise overlap among visible leaves (1px tolerance)
  const present = LEAVES.map((id) => { const el = document.getElementById(id); return el && vis(el) ? id : null; }).filter(Boolean);
  const overlaps = [];
  for (let i = 0; i < present.length; i++) {
    for (let j = i + 1; j < present.length; j++) {
      const a = rect(present[i]); const b = rect(present[j]);
      if (!a || !b) continue;
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) {
        overlaps.push([present[i], present[j]]);
      }
    }
  }
  // chain candidate (content-visible) + visible sets for O2 prefix invariant.
  // Candidate semantics mirror main.js inContent: content flags take an item
  // out, and so does not-rendering-at-all (shelved #voice-toggle-btn is
  // display:none via css/voice.css → dormant chain slot).
  const chainContent = CHAIN.filter((id) => {
    const el = document.getElementById(id);
    if (!el || el.hidden || el.classList.contains('hidden')) return false;
    return el.offsetWidth > 0 || el.classList.contains('nb-header-hide');
  });
  const chainShown = chainContent.filter((id) => vis(document.getElementById(id)));
  // O3 pills
  const bgVisible = vis(document.getElementById('bg-indicator'));
  const baVisible = vis(document.getElementById('bgagent-indicator'));
  // O5 session-name full width (Range = rendered text box)
  const name = document.getElementById('session-name');
  const range = document.createRange();
  range.selectNodeContents(name);
  const nameTruncated = range.getBoundingClientRect().width > name.getBoundingClientRect().width + 1
    || name.scrollWidth > name.clientWidth + 1;
  // O3 floor (axis-aware, all measured): the centered box seats the full name
  // only when hw − 2×extent ≥ need, where extent = the right-cluster span of
  // the floor state (padding + canvas toggle + the bg/bgagent capsules, real
  // computed gap) and need = the name width (memory/ring hide before the
  // capsules). Show-in is additionally delayed by the hysteresis band.
  // HYST_PX: keep in sync with main.js (12).
  const HYST_PX = 12;
  const csH = getComputedStyle(header);
  const padR = parseFloat(csH.paddingRight) || 0;
  const gapR = parseFloat(getComputedStyle(document.querySelector('.header-right')).columnGap) || 0;
  const wOf = (id) => { const el = document.getElementById(id); return el ? el.offsetWidth : 0; };
  const nameW = Math.round(range.getBoundingClientRect().width);
  const extent = padR + wOf('canvas-toggle-btn') + gapR
    + wOf('bgagent-indicator') + gapR + wOf('bg-indicator');
  const floor = Math.ceil(nameW + 2 * extent);
  return {
    hw: Math.round(hr.width), hh: Math.round(hr.height), overlaps, chainContent, chainShown,
    bgVisible, baVisible, nameTruncated, floor,
    dbg: window.__headerLayout || null,
  };
};

async function setState(page, { bg, ba, pa, ring }) {
  const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
  await df(ring
    ? { type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 21000, contextWindow: 128000, model: 'qa-model' }
    : { type: 'usageUpdate', sessionId: ROOT_SID, inputTokens: 0, contextWindow: 0 });
  await df({ type: 'activeBgTasks', tasks: bg ? {
    [ROOT_SID]: [1, 2].map((n) => ({ taskId: 'qa-t' + n, description: 'qa task ' + n, status: 'running' })),
  } : {} });
  await df({ type: 'activeAgents', agents: ba ? [1, 2, 3].map((n) => ({
    sessionId: ROOT_SID + '-sub' + n, agentId: 'qa-a' + n, agentName: 'qa-agent-' + n, rootSessionId: ROOT_SID, kind: 'Delegate',
  })) : [] });
  if (pa) {
    await df({ type: 'askUser', requestId: 'qa-ask-1', sessionId: ROOT_SID, agentName: 'QA', items: [{ question: 'qa?' }] });
  } else {
    await df({ type: 'askUserClosed', requestId: 'qa-ask-1', sessionId: ROOT_SID });
  }
  await page.waitForTimeout(150);
}

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  let hardFail = 0;
  const failures = [];
  const note = (msg) => { failures.push(msg); hardFail++; };
  for (const colorScheme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'shot-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      window.__shotErrors = [];
      window.addEventListener('error', (e) => window.__shotErrors.push(String(e.message)));
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send() {} close() {} onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
    });
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/')) {
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
      }
      if (MODE === 'before' && baselineBlob[p]) {
        return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: baselineBlob[p] });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: readFileSync(file) });
      } catch {
        return route.fulfill({ status: 404, body: 'not found' });
      }
    });
    await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);
    const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
    await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
    await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    await page.waitForTimeout(400);
    // 压力形态：memory-btn 在场（Nebula 有记忆）——作者实例同款
    await page.evaluate(() => { document.getElementById('memory-btn')?.removeAttribute('hidden'); });
    await setState(page, { bg: true, ba: true, pa: true, ring: true });
    // 作者 11:36 压力形态：侧栏收起 + canvas 关（#main 最宽）
    await page.click('#sidebar-toggle');
    await page.waitForTimeout(300);
    await page.evaluate(() => import('/js/canvas.js').then((m) => m.closeCanvas()));
    await page.waitForTimeout(300);

    // ── 多宽度扫描 360→1920 步进 20 ──
    let o1 = 0, o2 = 0, o3 = 0, o4 = 0, o5 = 0;
    const boot = await page.evaluate(PROBE);          // measured at 1280, all visible
    const floorPx = boot.floor;                        // pills visible here → widths valid
    const hhRef = boot.hh;
    const rows = [];
    for (const W of LADDER) {
      await page.setViewportSize({ width: W, height: 800 });
      await page.waitForTimeout(60); // viewport resize → ResizeObserver → rAF layout
      const m = await page.evaluate(PROBE);
      const f1 = m.overlaps.length === 0;
      // O2 prefix: shown set must be a prefix of the content-visible chain
      const shownPrefix = m.chainShown.every((id, i) => m.chainContent[i] === id)
        && m.chainShown.length <= m.chainContent.length;
      const f2 = shownPrefix;
      // O3: pills must be visible once hw clears the measured floor + hysteresis
      // band (show-in is delayed by the dead zone); below it = pathological zone.
      const f3 = (m.hw >= floorPx + HYST_PX + 2) ? (m.bgVisible && m.baVisible) : true;
      const f4 = Math.abs(m.hh - hhRef) <= 1;
      const f5 = !m.nameTruncated;
      o1 += f1 ? 1 : 0; o2 += f2 ? 1 : 0; o3 += f3 ? 1 : 0; o4 += f4 ? 1 : 0; o5 += f5 ? 1 : 0;
      const pass = f1 && f2 && f3 && f4 && f5;
      rows.push({ W, hw: m.hw, pass, o1: f1, o2: f2, o3: f3, o4: f4, o5: f5,
        overlaps: m.overlaps, shown: m.chainShown, hidden: m.chainContent.slice(m.chainShown.length),
        floor: floorPx, dbg: m.dbg });
      if (MODE === 'after' && !pass) {
        note(`[${colorScheme} W${W}] o1=${f1} o2=${f2} o3=${f3} o4=${f4} o5=${f5} overlaps=${JSON.stringify(m.overlaps)} shown=${JSON.stringify(m.chainShown)} hidden=${JSON.stringify(m.chainContent.slice(m.chainShown.length))}`);
      }
      if (SHOT_WIDTHS.includes(W)) {
        const out = join(OUT_DIR, `${MODE}-W${W}-${colorScheme}.png`);
        await page.locator('#header').screenshot({ path: out });
      }
    }
    // 非梯内关键宽度补拍（1049=作者 11:36 截图同款宽度；375=移动档）
    for (const W of SHOT_WIDTHS.filter((w) => !LADDER.includes(w))) {
      await page.setViewportSize({ width: W, height: 800 });
      await page.waitForTimeout(80);
      const out = join(OUT_DIR, `${MODE}-W${W}-${colorScheme}.png`);
      await page.locator('#header').screenshot({ path: out });
    }
    const tag = colorScheme === 'dark' ? 'dark ' : 'light';
    const fCounts = rows.filter((r) => !r.pass);
    console.log(`[${MODE} ${tag}] scan 360→1920/20: ${rows.length} widths | pass=${rows.length - fCounts.length} fail=${fCounts.length}`);
    console.log(`  O1 zero-overlap : ${o1}/${rows.length}`);
    console.log(`  O2 prefix-chain : ${o2}/${rows.length}`);
    console.log(`  O3 pills-survive: ${o3}/${rows.length}`);
    console.log(`  O4 no-wrap      : ${o4}/${rows.length}`);
    console.log(`  O5 name-full    : ${o5}/${rows.length}`);
    if (fCounts.length) {
      for (const r of fCounts.slice(0, 6)) {
        console.log(`  FAIL W${r.W} (hw=${r.hw}) o1=${r.o1} o2=${r.o2} o3=${r.o3} o4=${r.o4} o5=${r.o5} overlaps=${JSON.stringify(r.overlaps)} shown=${JSON.stringify(r.shown)}`);
      }
    }

    if (colorScheme === 'dark') {
      // ── H1 迟滞：bypass 隐藏临界 ±60px 逐像素扫描 ──
      const byW = new Map(rows.map((r) => [r.W, r]));
      const hiddenAt = (W) => { const r = byW.get(W); return r ? !r.shown.includes('bypass-dropdown') : null; };
      let wd = null;
      for (const W of LADDER) { if (hiddenAt(W)) wd = W; }   // max width where bypass hidden
      if (wd == null) {
        console.log(`[${MODE} ${tag}] H1 hysteresis: bypass never hidden in ladder — skip`);
      } else {
        const sig = () => page.evaluate(() => {
          const el = document.getElementById('bypass-dropdown');
          return el ? !el.classList.contains('nb-header-hide') : null;
        });
        const fine = [];
        const wStart = wd + 60; const wEnd = Math.max(360, wd - 60);
        await page.setViewportSize({ width: wStart, height: 800 });
        await page.waitForTimeout(80);
        for (let w = wStart; w >= wEnd; w--) {
          await page.setViewportSize({ width: w, height: 800 });
          await page.waitForTimeout(35);
          fine.push({ w, v: await sig() });
        }
        const downs = [];
        for (let i = 1; i < fine.length; i++) if (fine[i - 1].v && !fine[i].v) downs.push(fine[i].w);
        // re-walk upward recording show transitions
        const upTrans = [];
        let prevV = null;
        for (let i = fine.length - 1; i >= 0; i--) {
          await page.setViewportSize({ width: fine[i].w, height: 800 });
          await page.waitForTimeout(35);
          const v = await sig();
          if (prevV === false && v === true) upTrans.push(fine[i].w);
          prevV = v;
        }
        const downOK = downs.length === 1;
        const upOK = upTrans.length === 1;
        const gapOK = downOK && upOK && (upTrans[0] > downs[0]);
        console.log(`[${MODE} ${tag}] H1 hysteresis @bypass: hide@W${downs.join(',')} show@W${upTrans.join(',')} | singleHide=${downOK} singleShow=${upOK} deadZone>0=${gapOK}`);
        if (MODE === 'after' && (!downOK || !upOK || !gapOK)) {
          note(`H1 hysteresis: downs=${JSON.stringify(downs)} ups=${JSON.stringify(upTrans)}`);
        }
        // 死区内 ±1px 抖动 ×6 状态不变
        if (downOK && upOK && gapOK) {
          const mid = Math.round((downs[0] + upTrans[0]) / 2);
          await page.setViewportSize({ width: mid, height: 800 });
          await page.waitForTimeout(80);
          const v0 = await sig();
          let stable = true;
          for (let k = 0; k < 6; k++) {
            await page.setViewportSize({ width: mid + (k % 2 === 0 ? 1 : -1), height: 800 });
            await page.waitForTimeout(35);
            if ((await sig()) !== v0) stable = false;
          }
          console.log(`[${MODE} ${tag}] H1 dead-zone wiggle @W${mid}: stable=${stable}`);
          if (MODE === 'after' && !stable) note(`H1 wiggle unstable @W${mid}`);
        }
      }

      // ── C1 内容驱动：清空 → 胶囊退场仍零重叠；重灌 → 最高档回归 ──
      await page.setViewportSize({ width: 900, height: 800 });
      await page.waitForTimeout(120);
      await setState(page, { bg: false, ba: false, pa: false, ring: false });
      await page.waitForTimeout(200);
      const mCleared = await page.evaluate(PROBE);
      const c1a = mCleared.overlaps.length === 0 && !mCleared.bgVisible && !mCleared.baVisible;
      await setState(page, { bg: true, ba: true, pa: true, ring: true });
      await page.waitForTimeout(200);
      const mBack = await page.evaluate(PROBE);
      const prefixBack = mBack.chainShown.every((id, i) => mBack.chainContent[i] === id);
      const c1b = mBack.overlaps.length === 0 && mBack.bgVisible && mBack.baVisible && prefixBack;
      console.log(`[${MODE} ${tag}] C1 content-driven: cleared(${c1a}: overlap=${mCleared.overlaps.length}, bg=${mCleared.bgVisible}) reAdd(${c1b}: overlap=${mBack.overlaps.length}, bg=${mBack.bgVisible}, ba=${mBack.baVisible}, prefix=${prefixBack})`);
      if (MODE === 'after' && (!c1a || !c1b)) note(`C1 content-driven: cleared=${c1a} reAdd=${c1b} detail=${JSON.stringify({ mCleared, mBack })}`);
    }

    const errors = await page.evaluate(() => (window.__shotErrors || []));
    if (errors.length) { console.log(`[${MODE} ${tag}] console errors:`, errors.slice(0, 5)); if (MODE === 'after') note(`console errors: ${errors.slice(0, 3).join(' | ')}`); }
    await page.close();
  }
  await browser.close();
  console.log(`[${MODE}] done — hardFail=${hardFail}`);
  if (MODE === 'after' && hardFail) { console.error(`e2e-header-collision: ${hardFail} assertion failure(s)`); process.exit(1); }
})().catch((e) => { console.error(e); process.exit(1); });
