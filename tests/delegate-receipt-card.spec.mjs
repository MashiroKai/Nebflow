// delegate-receipt-card.spec.mjs — delegate 完工通知卡重设计验收
// （2026-10-31 12:32 作者令「这个delegate完成通知优化一下 那个diff胶囊很丑
//  你来决定吧」——设计决定已下，本 spec 钉其落地）：
//
//   决定（作者批，逐字）：
//     1. diff 胶囊（改动文件清单 chip）整个移除；
//     2. 首行 = 批次短名 + 判词（completed/merged/failed 单色徽标，墨族语言）；
//     3. 次行 = 一句话结果摘要（有 merge 则给 merge commit 短哈希，无则给失败
//        原因一行）；
//     4. 文件改动数（+x/−y）允许保留为一行灰字小注，不做 chip 不做胶囊；
//     5. 整体卡片样式对齐现行气泡/通知设计语言（圆角/留白/暗档），双主题。
//   改动面 = 完工通知渲染组件（delegate 回执气泡），不动投递内容本身。
//
// 断言面（28/0 目标）：
//   A1 卡头：批次短名 = KIND · EXECUTOR 口径（内置 nebflow→INTERNAL、外部各自
//      id 全大写、自定义 agent/sender 缺席去判词段逐字保留）；
//   A2 判词徽标：completed/failed 徽标 **同色**（墨族单色构造性断言——failed 不
//      红 completed 不绿）；merged = completed × merge status 行推导；
//   A3 摘要行：completed = payload 首行（跳过引擎注记行）；failed = 失败原因首行；
//      merged = merge status 行 + 短哈希独立段（7 位 hex，不随省略号截断）；
//   A4 灰字小注：+x/−y、零背景零边框零 padding（不是 chip 不是胶囊的构造性
//      断言）、点击唤起 diff 面板入口保留；
//   A5 旧胶囊彻底退役：DOM 零 .nf-diff-chip / 零 .nf-diff-headline；
//   A6 折叠语义：卡头/摘要行点击展开正文（默认折叠）；
//   A7 几何：卡头/摘要/小注纵列不重叠，徽标贴卡头行右缘，窄 320px 短名省略号；
//   A8 双主题：暗/亮两档结构同构 + 判词徽标/小注颜色双档可读（截图对照）。
//
// 被测真实链路（零伪造 state）：WS 帧 → main.js onMessage('user') →
//   renderInjectedBubble → injectedRows.buildInjectedRow。
// 谱系：delegate-bubble-compact.spec.mjs（2026-10-28，label 串行 + chip 同线）
//   随本批换代退役；T3+ 面板/阅读面链路回归由 chat-diff-canvas.spec.mjs 承载。
//
// 用法：
//   PHASE=before node tests/delegate-receipt-card.spec.mjs  # 改前取证（预期红）
//   PHASE=after  node tests/delegate-receipt-card.spec.mjs  # 改后验收（预期全绿）
// 产物：evidence/20261031_delegate-receipt-card__delegate-nebflow-670cc1e1/
//   shots/<phase>-*.png（暗/亮×桌面/移动对照）+ readings.<phase>.json。
import { chromium } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const OUT = join(HERE, '..', 'evidence', '20261031_delegate-receipt-card__delegate-nebflow-670cc1e1');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'delegate-receipt-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// Console/pageerror 捕获（改后验证流硬判据：零新增错误）。存量基线噪声过滤：
// mock 静态路由不伺服 favicon-*.png ⇒ 资源 404/fetch 噪声与渲染链无关，只计
// 未捕获 pageerror 与非资源类的 console.error。
const consoleErrors = [];
const NOISE = /Failed to load resource|fetching the script/i;
const watchErrors = (page) => {
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !NOISE.test(m.text())) consoleErrors.push(`console.error: ${m.text()}`);
  });
};

// 12 files +908 −202（与作者报障胶囊读数一致；diffText 不需要——本 spec 不点开 diff）。
const PAYLOAD_12 = {
  nodeId: 'deleg-receipt-12', generatedAt: 1793167000000,
  root: '/Users/kaiyu/Claude code/Stand',
  files: Array.from({ length: 12 }, (_, i) => ({
    path: `src/main/resources/web/js/chat/fixture-file-${String(i).padStart(2, '0')}.js`,
    changeType: 'modified',
    added: i === 11 ? 72 : 76, removed: i === 11 ? 15 : 17,
    source: 'tool-ledger',
  })),
};

const MERGE_TEXT = [
  '工作结果全文：通知卡重设计实现 + 回归。',
  '',
  '[delegate-judge] verdict: completed.',
  '[delegate-finish] merge status: merged --no-ff into main (e633bcdbe)',
  '[delegate-finish] work result (preserved from the work phase, before the merge round):',
  '（工作段……）',
].join('\n');

const FRAMES = [
  {
    key: 'internal',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: '委托完成：通知卡重设计 + 回归。', fileChanges: PAYLOAD_12,
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'COMPLETED',
    expectSummary: '委托完成：通知卡重设计 + 回归。', expectHash: false,
  },
  {
    key: 'external',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'claude-code',
      header: 'DELEGATE · NEBULA · CLAUDE-CODE · COMPLETED',
      text: '外部执行器委托完成。',
    },
    expectName: 'DELEGATE · CLAUDE-CODE', expectBadge: 'COMPLETED',
    expectSummary: '外部执行器委托完成。', expectHash: false,
  },
  {
    key: 'legacy',
    msg: {
      source: 'delegate', eventType: 'completed',
      header: 'DELEGATE · stand · diff-split · COMPLETED',
      text: '旧历史行第一行\n旧历史行第二行\n旧历史行第三行',
      fileChanges: PAYLOAD_12,
    },
    // sender 缺席 ⇒ 无法判定执行器 ⇒ header 去判词段逐字保留。
    expectName: 'DELEGATE · stand · diff-split', expectBadge: 'COMPLETED',
    expectSummary: '旧历史行第一行', expectHash: false,
  },
  {
    key: 'agentDelegate',
    msg: {
      source: 'delegate', eventType: 'result', sender: 'Explorer',
      header: 'DELEGATE · NEBFLOW · EXPLORER · RESULT',
      text: '自定义 agent 委托（sender = agentDef.name，非 executor id）。',
    },
    expectName: 'DELEGATE · NEBFLOW · EXPLORER', expectBadge: 'RESULT',
    expectSummary: '自定义 agent 委托（sender = agentDef.name，非 executor id）。', expectHash: false,
  },
  {
    key: 'merged',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: MERGE_TEXT,
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'MERGED',
    expectSummary: 'merged --no-ff into main (e633bcdbe)', expectHash: 'e633bcd',
  },
  {
    key: 'failed',
    msg: {
      source: 'delegate', eventType: 'failed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · FAILED',
      text: 'merge round aborted: lane occupied by another batch',
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'FAILED',
    expectSummary: 'merge round aborted: lane occupied by another batch', expectHash: false,
  },
];

const browser = await chromium.launch();

async function boot(width, height, scheme = 'dark') {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 2,
  })).newPage();
  watchErrors(page);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  return page;
}

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

const inject = (page, msg) => page.evaluate((m) => import('/js/state.js').then((mod) => {
  const s = mod.default;
  s.ws.onmessage({
    data: JSON.stringify({
      type: 'user', injected: true, sessionId: s.activeSessionId,
      timestamp: Date.now(), ...m,
    }),
  });
}), msg);

/** 一帧卡面的全量读数。 */
const measureAll = (page) => page.evaluate(() => {
  const read = (bubble) => {
    const q = (sel) => bubble.querySelector(sel);
    const r = (el) => (el ? el.getBoundingClientRect() : null);
    const badge = q('.nf-rcpt-badge');
    const note = q('.nf-rcpt-note');
    return {
      name: q('.nf-rcpt-name')?.textContent || '',
      badge: badge?.textContent || '',
      summary: q('.nf-rcpt-summary')?.textContent.trim() || '',
      hash: q('.nf-rcpt-hash')?.textContent || '',
      noteText: note?.textContent.trim() || '',
      contentDisplay: getComputedStyle(q('.injected-content')).display,
      chipCount: bubble.querySelectorAll('.nf-diff-chip').length,
      headlineCount: bubble.querySelectorAll('.nf-diff-headline').length,
      labelCount: bubble.querySelectorAll('.injected-source-label').length,
      badgeColor: badge ? getComputedStyle(badge).color : '',
      noteLook: note ? (() => {
        const cs = getComputedStyle(note);
        return {
          border: cs.borderTopWidth, bg: cs.backgroundColor, pad: cs.paddingLeft,
          color: cs.color,
          pressed: note.getAttribute('aria-pressed'),
        };
      })() : null,
      geo: (() => {
        const head = r(q('.nf-rcpt-head'));
        const summary = r(q('.nf-rcpt-summary'));
        const noteR = r(note);
        const nameR = r(q('.nf-rcpt-name'));
        const badgeR = r(badge);
        return {
          head, summary, note: noteR, name: nameR, badge: badgeR,
          stacked: (!summary || summary.top >= head.bottom - 0.5)
            && (!noteR || noteR.top >= (summary ? summary.bottom : head.bottom) - 0.5),
          badgeRight: badgeR && nameR ? Math.abs(badgeR.right - head.right) < 1 : null,
        };
      })(),
    };
  };
  return [...document.querySelectorAll('.bubble.injected')].map(read);
});

const readings = { phase: PHASE, tiers: {} };

// ── 主档：1440 桌面，六帧 × 暗色 ─────────────────────────────────────────
{
  const page = await boot(1440, 900, 'dark');
  for (const f of FRAMES) await inject(page, f.msg);
  await raf(page);

  const m = await measureAll(page);
  readings.tiers.desktopDark = m;
  const byKey = Object.fromEntries(FRAMES.map((f, i) => [f.key, m[i]]));

  for (const f of FRAMES) {
    const b = byKey[f.key];
    ok(`A1 [${f.key}] 批次短名 = ${f.expectName}`, b.name === f.expectName, `actual="${b.name}"`);
    ok(`A2 [${f.key}] 判词徽标 = ${f.expectBadge}`, b.badge === f.expectBadge, `actual="${b.badge}"`);
    ok(`A3 [${f.key}] 摘要行`, b.summary.startsWith(f.expectSummary.slice(0, 12)),
      `actual="${b.summary.slice(0, 48)}"`);
    ok(`A3 [${f.key}] 短哈希${f.expectHash ? ' = ' + f.expectHash : '缺席'}`, f.expectHash ? b.hash === f.expectHash : b.hash === '');
  }
  // A2 墨族单色：completed 与 failed 徽标同色（无色相语义）。
  ok('A2 判词徽标墨族单色（completed == failed 同色）', byKey.internal.badgeColor === byKey.failed.badgeColor,
    `completed=${byKey.internal.badgeColor} failed=${byKey.failed.badgeColor}`);
  // A4 灰字小注（internal/legacy 两帧带载荷）。
  for (const key of ['internal', 'legacy']) {
    const b = byKey[key];
    ok(`A4 [${key}] 小注 = +908 −202 计数`, b.noteText === '+908 −202', `actual="${b.noteText}"`);
    ok(`A4 [${key}] 小注零背景零边框零 padding（非胶囊构造性）`,
      b.noteLook && b.noteLook.border === '0px' && (b.noteLook.bg === 'rgba(0, 0, 0, 0)' || b.noteLook.bg === 'transparent') && b.noteLook.pad === '0px',
      JSON.stringify(b.noteLook));
    ok(`A4 [${key}] 小注 aria-pressed 初始 false`, b.noteLook?.pressed === 'false');
  }
  // 零载荷帧（external/agentDelegate/merged/failed）⇒ 无小注行。
  for (const key of ['external', 'agentDelegate', 'merged', 'failed']) {
    ok(`A4 [${key}] 零载荷 ⇒ 无小注`, byKey[key].noteText === '');
  }
  // A5 旧胶囊彻底退役。
  ok('A5 DOM 零 .nf-diff-chip', m.every((b) => b.chipCount === 0));
  ok('A5 DOM 零 .nf-diff-headline', m.every((b) => b.headlineCount === 0));
  // A6 折叠默认（正文 display:none 在场）。
  ok('A6 六帧折叠态默认（正文 display:none）', m.every((b) => b.contentDisplay === 'none'));
  // A7 几何：卡头/摘要/小注纵列不重叠 + 徽标贴行右缘。
  ok('A7 六帧卡面纵列不重叠', m.every((b) => b.geo.stacked));
  ok('A7 徽标贴卡头行右缘（±1px）', m.every((b) => b.geo.badgeRight !== false));

  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-1-desktop-dark.png`) });
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-2-internal-dark.png`) });
  await page.locator('.bubble.injected').nth(4).screenshot({ path: join(SHOTS, `${PHASE}-3-merged-dark.png`) });
  await page.locator('.bubble.injected').nth(5).screenshot({ path: join(SHOTS, `${PHASE}-4-failed-dark.png`) });

  // A6 展开语义：点卡头 → 正文出。
  await page.locator('.bubble.injected .nf-rcpt-top').nth(0).click();
  await raf(page);
  const expanded = await page.evaluate(() => getComputedStyle(document.querySelectorAll('.bubble.injected .injected-content')[0]).display);
  ok('A6 点卡头展开正文', expanded !== 'none', `display=${expanded}`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-5-internal-expanded-dark.png`) });

  // A7 窄面板：legacy 帧短名（DELEGATE · stand · diff-split）压 200px 泡宽 ⇒
  // 省略号截断；徽标本征宽零变形。
  await page.locator('.bubble.injected').nth(2).evaluate((el) => { el.style.maxWidth = '200px'; });
  await raf(page);
  const narrow = await page.locator('.bubble.injected').nth(2).evaluate((bubble) => {
    const name = bubble.querySelector('.nf-rcpt-name');
    const badge = bubble.querySelector('.nf-rcpt-badge');
    return {
      nameScroll: name.scrollWidth, nameClient: name.clientWidth,
      badgeW: badge.getBoundingClientRect().width,
      badgeRightDelta: Math.abs(badge.getBoundingClientRect().right - bubble.querySelector('.nf-rcpt-head').getBoundingClientRect().right),
    };
  });
  readings.tiers.narrow200 = narrow;
  ok('A7 窄200px 短名省略号截断生效', narrow.nameScroll > narrow.nameClient,
    `scroll=${narrow.nameScroll} client=${narrow.nameClient}`);
  ok('A7 窄200px 徽标仍贴行右缘', narrow.badgeRightDelta < 1, `delta=${narrow.badgeRightDelta}`);
  await page.locator('.bubble.injected').nth(2).screenshot({ path: join(SHOTS, `${PHASE}-6-narrow200-dark.png`) });
  await page.close();
}

// ── A8 亮档对照：1440 × light，结构同构 + 颜色双档可读 ───────────────────
{
  const page = await boot(1440, 900, 'light');
  for (const f of FRAMES.slice(0, 2)) await inject(page, f.msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers.desktopLight = m;
  ok('A8 亮档结构同构（短名/判词/摘要与小注逐字一致）',
    m[0].name === FRAMES[0].expectName && m[0].badge === 'COMPLETED'
    && m[0].summary === FRAMES[0].expectSummary && /^\+\d+ −\d+$/.test(m[0].noteText),
    `${m[0].name} | ${m[0].badge} | ${m[0].summary} | ${m[0].noteText}`);
  ok('A8 亮档徽标/小注颜色非透明（双档可读）',
    m[0].badgeColor !== '' && m[0].badgeColor !== 'rgba(0, 0, 0, 0)' && m[0].noteLook && m[0].noteLook.color !== 'rgba(0, 0, 0, 0)');
  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-7-desktop-light.png`) });
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-8-internal-light.png`) });
  await page.close();
}

// ── 响应式另两档：768 / 390（internal+merged 帧，暗色）───────────────────
for (const [tier, width, height] of [['tablet768', 768, 1024], ['mobile390', 390, 844]]) {
  const page = await boot(width, height, 'dark');
  await inject(page, FRAMES[0].msg);
  await inject(page, FRAMES[4].msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers[tier] = m;
  ok(`[${tier}] 卡面三行齐备（短名/判词/摘要/小注）`,
    m[0].name === FRAMES[0].expectName && m[0].badge === 'COMPLETED' && m[0].noteText !== '');
  ok(`[${tier}] merged 帧判词/哈希齐备`, m[1].badge === 'MERGED' && m[1].hash === 'e633bcd');
  ok(`[${tier}] 纵列不重叠`, m.every((b) => b.geo.stacked));
  await page.locator('.bubble.injected').nth(1).screenshot({ path: join(SHOTS, `${PHASE}-9-${tier}-dark.png`) });
  await page.close();
}

await browser.close();
ok('console/pageerror 零新增错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
writeFileSync(join(OUT, `readings.${PHASE}.json`), JSON.stringify(readings, null, 2));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
