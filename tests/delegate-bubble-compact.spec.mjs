// delegate-bubble-compact.spec.mjs — delegate 结果气泡 UI 紧凑化验收
// （2026-10-28 17:3x 作者报障：气泡读作「DELEGATE · NEBULA · NEBFLOW · COMPLETED」
//  + 右侧「12 files +908 −202」胶囊，整泡肥大）。
//
// 两件验收（作者令）：
//   ① 信息提纯：内置执行器（sender/executor id = nebflow，引擎
//      ExecutorRegistry.DefaultId）头部一律显示 INTERNAL ⇒
//      `DELEGATE · INTERNAL · COMPLETED`；外部执行器（zcode/claude-code/codex…）
//      显示各自 id（全大写，同四段式排版）；PROJECT 段不再进头部（项目上下文
//      已由会话 chrome 展示，重复=零信息量）。只改显示文案组装——帧/落盘行的
//      header、sender（executor id）、project 数据原样保留。
//      降级：sender 缺席（旧历史行/旧夹具）⇒ 无法判定执行器 ⇒ header 逐字不变。
//   ② 尺寸回归：单行内容时泡高 ≤ 普通一行消息泡高（量化：baseline 一行 user
//      文本泡高 + 1px 容差）；折叠态泡高 = 常规上下 padding + 单行 header 行高
//      （±1px，徽章行不独立占行、无残留 margin）；chip 高 ≤ label 行高 + 1px
//      （胶囊不撑行）；多行/展开内容按内容自然生长。
//
// 被测真实链路（同 tests/chat-diff-canvas.spec.mjs 夹具：真 web 树 + 真 WS 桩，
// 零伪造 state）：WS 帧 → main.js onMessage('user') → renderInjectedBubble →
// injectedRows.buildInjectedRow。
//
// 用法：
//   PHASE=before node tests/delegate-bubble-compact.spec.mjs  # 改前取证（预期红）
//   PHASE=after  node tests/delegate-bubble-compact.spec.mjs  # 改后验收（预期全绿）
// 产物：evidence/20261028_delegate-bubble-compact__delegate-nebflow-8a509c8d/
//   shots/<phase>-*.png（暗色前后对照）+ readings.<phase>.json（量化读数）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const OUT = join(HERE, '..', 'evidence', '20261028_delegate-bubble-compact__delegate-nebflow-8a509c8d');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'delegate-compact-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// 12 files +908 −202（与作者报障胶囊读数一致；diffText 不需要——本 spec 不点开 diff）。
const PAYLOAD_12 = {
  nodeId: 'deleg-compact-12', generatedAt: 1793167000000,
  root: '/Users/kaiyu/Claude code/Stand',
  files: Array.from({ length: 12 }, (_, i) => ({
    path: `src/main/resources/web/js/chat/fixture-file-${String(i).padStart(2, '0')}.js`,
    changeType: 'modified',
    added: i === 11 ? 72 : 76, removed: i === 11 ? 15 : 17,
    source: 'tool-ledger',
  })),
};

const FRAMES = [
  {
    key: 'internal',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: '委托完成：结果气泡信息提纯 + 尺寸回归。', fileChanges: PAYLOAD_12,
    },
    expectLabel: 'DELEGATE · INTERNAL · COMPLETED',
  },
  {
    key: 'external',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'claude-code',
      header: 'DELEGATE · NEBULA · CLAUDE-CODE · COMPLETED',
      text: '外部执行器委托完成。',
    },
    expectLabel: 'DELEGATE · CLAUDE-CODE · COMPLETED',
  },
  {
    key: 'legacy',
    msg: {
      source: 'delegate', eventType: 'completed',
      header: 'DELEGATE · stand · diff-split · COMPLETED',
      text: '旧历史行第一行\n旧历史行第二行\n旧历史行第三行',
      fileChanges: PAYLOAD_12,
    },
    expectLabel: 'DELEGATE · stand · diff-split · COMPLETED', // sender 缺席 ⇒ 逐字降级
  },
  {
    key: 'agentDelegate',
    msg: {
      source: 'delegate', eventType: 'result', sender: 'Explorer',
      header: 'DELEGATE · NEBFLOW · EXPLORER · RESULT',
      text: '自定义 agent 委托（sender = agentDef.name，非 executor id）。',
    },
    // 边界（作者令只覆盖内置/外部 executor）：自定义 agent 委托的 SUBJECT 段
    // 有信息量 ⇒ 逐字不动（PROJECT 段同保留）。
    expectLabel: 'DELEGATE · NEBFLOW · EXPLORER · RESULT',
  },
];

const browser = await chromium.launch();

async function boot(width, height) {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme: 'dark', deviceScaleFactor: 2,
  })).newPage();
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

const measureAll = (page) => page.evaluate(() => {
  const measureBubble = (bubble) => {
    const label = bubble.querySelector('.injected-source-label');
    const chip = bubble.querySelector('.nf-diff-chip');
    const headline = bubble.querySelector('.nf-diff-headline');
    const cs = getComputedStyle(bubble);
    const hcs = headline ? getComputedStyle(headline) : null;
    const lcs = label ? getComputedStyle(label) : null;
    return {
      headerText: label?.textContent || '',
      bubbleH: bubble.getBoundingClientRect().height,
      labelH: label ? label.getBoundingClientRect().height : 0,
      labelMt: lcs ? parseFloat(lcs.marginTop) : 0,
      labelMb: lcs ? parseFloat(lcs.marginBottom) : 0,
      chipH: chip ? chip.getBoundingClientRect().height : 0,
      chipW: chip ? chip.getBoundingClientRect().width : 0,
      headlineH: headline ? headline.getBoundingClientRect().height : (label ? label.getBoundingClientRect().height : 0),
      headlineMt: hcs ? parseFloat(hcs.marginTop) : 0,
      headlineMb: hcs ? parseFloat(hcs.marginBottom) : 0,
      padT: parseFloat(cs.paddingTop), padB: parseFloat(cs.paddingBottom),
    };
  };
  const baseline = document.querySelector('.row.user .bubble.user:not(.injected)');
  return {
    baselineH: baseline ? baseline.getBoundingClientRect().height : 0,
    bubbles: [...document.querySelectorAll('.bubble.injected')].map(measureBubble),
  };
});

async function addBaseline(page) {
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const row = document.createElement('div');
    row.className = 'row user';
    const b = document.createElement('div');
    b.className = 'bubble user';
    b.textContent = '一行普通消息（基线）。';
    row.appendChild(b);
    chat.appendChild(row);
  });
}

const readings = { phase: PHASE, tiers: {} };

// ── 主档：1440 暗色，三条 delegate 帧 + 一行基线泡 ─────────────────────
{
  const page = await boot(1440, 900);
  await addBaseline(page);
  for (const f of FRAMES) await inject(page, f.msg);
  await raf(page);

  const m = await measureAll(page);
  readings.tiers.desktop = m;
  ok('基线一行 user 泡高已测得', m.baselineH > 0, `baseline=${m.baselineH.toFixed(2)}px`);
  const byKey = Object.fromEntries(FRAMES.map((f, i) => [f.key, m.bubbles[i]]));

  for (const f of FRAMES) {
    const b = byKey[f.key];
    ok(`① [${f.key}] 头部文案 = ${f.expectLabel}`, b.headerText === f.expectLabel, `actual="${b.headerText}"`);
    ok(`② [${f.key}] 单行泡高 ≤ 一行基线+1px`, b.bubbleH <= m.baselineH + 1,
      `bubble=${b.bubbleH.toFixed(2)}px baseline=${m.baselineH.toFixed(2)}px`);
    ok(`② [${f.key}] 折叠泡高 = 常规padding+单行header行高（±1px）`,
      Math.abs(b.bubbleH - (b.padT + b.padB + b.headlineH + b.headlineMt + b.headlineMb + b.labelMt + b.labelMb)) < 1
      && b.headlineMt + b.headlineMb <= 1,
      `bubble=${b.bubbleH.toFixed(2)} pad=${(b.padT + b.padB).toFixed(1)} headline=${b.headlineH.toFixed(2)} mt=${b.headlineMt} mb=${b.headlineMb} labelMb=${b.labelMb}`);
    if (b.chipH > 0) {
      ok(`② [${f.key}] chip 不撑行（chip高 ≤ label行高+1px）`, b.chipH <= b.labelH + 1,
        `chip=${b.chipH.toFixed(2)}px label=${b.labelH.toFixed(2)}px`);
      ok(`② [${f.key}] header+chip 同一行（行高=max(label,chip)±1px）`,
        Math.abs(b.headlineH - Math.max(b.labelH, b.chipH)) < 1,
        `headline=${b.headlineH.toFixed(2)}px`);
    }
  }

  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-1-desktop-chat-dark.png`) });
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-2-internal-closeup-dark.png`) });
  await page.locator('.bubble.injected').nth(1).screenshot({ path: join(SHOTS, `${PHASE}-3-external-closeup-dark.png`) });

  // 多行/展开自然生长：展开 internal（单行正文）与 legacy（三行正文）。
  const collapsedInternal = byKey.internal.bubbleH;
  const collapsedLegacy = byKey.legacy.bubbleH;
  await page.locator('.bubble.injected .injected-source-label').nth(0).click();
  await page.locator('.bubble.injected .injected-source-label').nth(2).click();
  await raf(page);
  const expanded = await measureAll(page);
  readings.tiers.desktopExpanded = expanded;
  ok('② 展开单行正文泡高自然生长', expanded.bubbles[0].bubbleH > collapsedInternal,
    `${collapsedInternal.toFixed(1)} → ${expanded.bubbles[0].bubbleH.toFixed(1)}px`);
  ok('② 展开多行正文按内容生长（三行 > 一行展开）',
    expanded.bubbles[2].bubbleH > expanded.bubbles[0].bubbleH,
    `multiline=${expanded.bubbles[2].bubbleH.toFixed(1)} singleline=${expanded.bubbles[0].bubbleH.toFixed(1)}px`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-4-internal-expanded-dark.png`) });
  await page.locator('.bubble.injected .injected-source-label').nth(0).click();
  await page.locator('.bubble.injected .injected-source-label').nth(2).click();
  await raf(page);

  // 窄面板：320px 仍一行 + chip 本征宽零变形 + label 省略号截断。
  await page.locator('.bubble.injected').nth(0).evaluate((el) => { el.style.maxWidth = '320px'; });
  await raf(page);
  const narrow = await page.locator('.bubble.injected').nth(0).evaluate((bubble) => {
    const label = bubble.querySelector('.injected-source-label');
    const chip = bubble.querySelector('.nf-diff-chip');
    const headline = bubble.querySelector('.nf-diff-headline');
    return {
      headlineH: headline.getBoundingClientRect().height,
      chipW: chip.getBoundingClientRect().width,
      labelScroll: label.scrollWidth, labelClient: label.clientWidth,
    };
  });
  readings.tiers.narrow320 = narrow;
  ok('② 窄320px 仍单行（行高与宽档一致±1px）', Math.abs(narrow.headlineH - byKey.internal.headlineH) < 1,
    `narrow=${narrow.headlineH.toFixed(2)} wide=${byKey.internal.headlineH.toFixed(2)}px`);
  ok('② 窄320px chip 本征宽零变形', Math.abs(narrow.chipW - byKey.internal.chipW) < 1,
    `narrow=${narrow.chipW.toFixed(1)} wide=${byKey.internal.chipW.toFixed(1)}px`);
  ok('② 窄320px label 省略号截断生效', narrow.labelScroll > narrow.labelClient,
    `scroll=${narrow.labelScroll} client=${narrow.labelClient}`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-5-narrow320-dark.png`) });
  await page.close();
}

// ── 响应式另两档：768 / 390（internal 帧，同量化断言）─────────────────────
for (const [tier, width, height] of [['tablet768', 768, 1024], ['mobile390', 390, 844]]) {
  const page = await boot(width, height);
  await addBaseline(page);
  await inject(page, FRAMES[0].msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers[tier] = m;
  const b = m.bubbles[0];
  ok(`① [${tier}] 头部文案 = DELEGATE · INTERNAL · COMPLETED`, b.headerText === FRAMES[0].expectLabel, `actual="${b.headerText}"`);
  ok(`② [${tier}] 单行泡高 ≤ 一行基线+1px`, b.bubbleH <= m.baselineH + 1,
    `bubble=${b.bubbleH.toFixed(2)}px baseline=${m.baselineH.toFixed(2)}px`);
  ok(`② [${tier}] chip 不撑行`, b.chipH > 0 && b.chipH <= b.labelH + 1,
    `chip=${b.chipH.toFixed(2)}px label=${b.labelH.toFixed(2)}px`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-6-${tier}-dark.png`) });
  await page.close();
}

await browser.close();
writeFileSync(join(OUT, `readings.${PHASE}.json`), JSON.stringify(readings, null, 2));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
