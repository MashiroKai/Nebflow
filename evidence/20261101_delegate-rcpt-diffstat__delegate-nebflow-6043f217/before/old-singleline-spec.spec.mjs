// delegate-receipt-card.spec.mjs — delegate 完工通知卡**单行化+hover 批**验收
// （2026-10-31 22:29 作者令三件套，承继 2026-10-31 重设计批 / 语义色批）：
//
//   作者令（逐字）：
//     ① 蓝色完工卡（DELEGATE · INTERNAL · COMPLETED | +366 −43 那行）就这么一行
//        显示——卡内多行内容收成单行呈现（照现字面结构：类型·范围·状态 |
//        diff 计数），不展开不换行溢出省略；
//     ② hover 可点击感：整条 hover 有明确的可点击反馈（背景/描边微变+手型）；
//     ③ 分块 hover 效果：卡内分段（DELEGATE 段 / INTERNAL 段 / COMPLETED 段 /
//        diff 计数段）各自的 hover 高亮分块显示——鼠标移到哪个块哪个块亮。
//   约束：双端一致；现有点击行为（点开详情）不动；截图取证。
//
// 断言面（目标全绿）：
//   A1 批次短名 = KIND · EXECUTOR 口径（内置 nebflow→INTERNAL、外部各自 id 全
//      大写、自定义 agent/sender 缺席去判词段逐字保留）——分段 span 承载后
//      textContent 逐字节保持；
//   A2 判词徽标：completed/failed 同色（墨族单色构造性断言）；merged 推导；
//   A3 摘要行收进展开态：折叠 display:none（卡面恒单行），点卡头展开后
//      摘要/短哈希可见（点开详情语义不动）；
//   A4 灰字小注：+x/−y、零背景零边框零 padding（rest 构造性断言）、右缘组
//      （note 右缘贴卡内容盒右缘 ±1px）、aria-pressed 初始 false；
//   A5 旧胶囊彻底退役：DOM 零 .nf-diff-chip / 零 .nf-diff-headline；
//   B1 单行化：卡面行高 = 一行（≤24px，含行 padding）；名字/徽标/pipe/小注
//      同处一行带内不重叠、字面次序「类型·范围·状态 | diff 计数」；窄 320/200px
//      短名省略号截断且徽标/小注不坠行；
//   B2 整条 hover 可点击感：cursor:pointer + 背景透明→sapphire 微底 + inset
//      描边微变（rest vs hover 构造性对比）；
//   B3 分块 hover：hover 短名段 ⇒ 仅该段底色变深、徽标/小注底色不变；hover
//      徽标 ⇒ 仅徽标变；hover 小注 ⇒ 仅小注变（块间隔离断言 + 各块态截图）；
//   A7/A8 几何/双主题：768/390 响应式档同构；暗/亮两档结构同构 + hover 反馈
//      双档在场。
//
// 被测真实链路（零伪造 state）：WS 帧 → main.js onMessage('user') →
//   renderInjectedBubble → injectedRows.buildInjectedRow。
// 谱系：delegate-bubble-compact.spec.mjs（2026-10-28 退役）→ 本 spec 前身
//   （2026-10-31 重设计批）→ 本批（单行化+hover）随批重钉。
//
// 用法：
//   PHASE=before node tests/delegate-receipt-card.spec.mjs  # 改前取证（预期红）
//   PHASE=after  node tests/delegate-receipt-card.spec.mjs  # 改后验收（预期全绿）
// 产物：evidence/20261031_delegate-rcpt-singleline-hover__delegate-nebflow-c2937ee7/
//   shots/<phase>-*.png（常态单行 + 分块 hover 各块态 + 展开 + 窄省略 + 双主题
//   + 响应式档）+ readings.<phase>.json。
import { chromium } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const OUT = join(HERE, '..', 'evidence', '20261031_delegate-rcpt-singleline-hover__delegate-nebflow-c2937ee7');
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
    expectNote: '+908 −202',
  },
  {
    key: 'external',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'claude-code',
      header: 'DELEGATE · NEBULA · CLAUDE-CODE · COMPLETED',
      text: '外部执行器委托完成。',
    },
    expectName: 'DELEGATE · CLAUDE-CODE', expectBadge: 'COMPLETED',
    expectSummary: '外部执行器委托完成。', expectHash: false, expectNote: '',
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
    expectSummary: '旧历史行第一行', expectHash: false, expectNote: '+908 −202',
  },
  {
    key: 'agentDelegate',
    msg: {
      source: 'delegate', eventType: 'result', sender: 'Explorer',
      header: 'DELEGATE · NEBFLOW · EXPLORER · RESULT',
      text: '自定义 agent 委托（sender = agentDef.name，非 executor id）。',
    },
    expectName: 'DELEGATE · NEBFLOW · EXPLORER', expectBadge: 'RESULT',
    expectSummary: '自定义 agent 委托（sender = agentDef.name，非 executor id）。', expectHash: false, expectNote: '',
  },
  {
    key: 'merged',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: MERGE_TEXT,
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'MERGED',
    expectSummary: 'merged --no-ff into main (e633bcdbe)', expectHash: 'e633bcd', expectNote: '',
  },
  {
    key: 'failed',
    msg: {
      source: 'delegate', eventType: 'failed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · FAILED',
      text: 'merge round aborted: lane occupied by another batch',
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'FAILED',
    expectSummary: 'merge round aborted: lane occupied by another batch', expectHash: false, expectNote: '',
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

/** hover 过渡沉降等时：分块 hover 底色有 0.15s transition——hover 后读数前必须
 *  等过渡走完（否则读到鼠标路径途经块的返程中值，隔离断言假性失败）。 */
const settle = (page) => page.waitForTimeout(280);

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
    const pipe = q('.nf-rcpt-pipe');
    const head = q('.nf-rcpt-head');
    const summary = q('.nf-rcpt-summary');
    const bcs = getComputedStyle(bubble);
    const bubbleContentRight = bubble.getBoundingClientRect().right - parseFloat(bcs.paddingRight);
    // 单行化：所有段与卡面行带**实质重叠**（同处一行——容纳各行本征行高差，
    // 不用包含断言：徽标 line-height 1.3 与名字行盒高差 >±1px 属正常铅空）。
    const inBand = (rr) => {
      const hr = r(head);
      return !!rr && !!hr && rr.top < hr.bottom - 2 && rr.bottom > hr.top + 2;
    };
    return {
      name: q('.nf-rcpt-name')?.textContent || '',
      segs: [...(q('.nf-rcpt-name')?.querySelectorAll('.nf-rcpt-seg') || [])].map((s) => s.textContent),
      badge: badge?.textContent || '',
      summary: summary?.textContent.trim() || '',
      summaryDisplay: summary ? getComputedStyle(summary).display : 'absent',
      hash: q('.nf-rcpt-hash')?.textContent || '',
      pipe: pipe?.textContent || '',
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
        const hr = r(head);
        const nameR = r(q('.nf-rcpt-name'));
        const badgeR = r(badge);
        const pipeR = r(pipe);
        const noteR = r(note);
        return {
          head: hr, name: nameR, badge: badgeR, pipe: pipeR, note: noteR,
          headH: hr ? hr.height : null,
          // 单行化：所有在场段同处卡面行带（不坠行；缺席段——零载荷帧无
          // pipe/小注——不算违例）。
          oneLine: [nameR, badgeR, pipeR, noteR].every((rr) => rr === null || inBand(rr)),
          // 字面次序「类型·范围·状态 | diff 计数」。
          order: nameR && badgeR && (!pipeR || (badgeR.left < pipeR.left)) && (!noteR || (!pipeR || pipeR.left < noteR.left)),
          noteRight: noteR ? Math.abs(noteR.right - bubbleContentRight) : null,
          nameScroll: q('.nf-rcpt-name') ? q('.nf-rcpt-name').scrollWidth : null,
          nameClient: q('.nf-rcpt-name') ? q('.nf-rcpt-name').clientWidth : null,
        };
      })(),
    };
  };
  return [...document.querySelectorAll('.bubble.injected')].map(read);
});

/** rest/hover 对比读数（B2 整条 hover 可点击感 + B3 分块 hover 隔离）。 */
const hoverReads = (page, n) => page.evaluate((idx) => {
  const bubble = document.querySelectorAll('.bubble.injected')[idx];
  const head = bubble.querySelector('.nf-rcpt-head');
  const segs = [...bubble.querySelectorAll('.nf-rcpt-name .nf-rcpt-seg')];
  const badge = bubble.querySelector('.nf-rcpt-badge');
  const note = bubble.querySelector('.nf-rcpt-note');
  const bg = (el) => (el ? getComputedStyle(el).backgroundColor : 'absent');
  const hcs = getComputedStyle(head);
  return {
    rest: {
      headCursor: hcs.cursor, headBg: hcs.backgroundColor, headShadow: hcs.boxShadow,
      segBgs: segs.map(bg), badgeBg: bg(badge), noteBg: bg(note),
    },
    // 以下三组由调用方在 hover 各目标后分别覆写。
  };
}, n);

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
    ok(`A1 [${f.key}] 短名分段 span 拼接一致`, b.segs.join(' · ') === f.expectName,
      `segs=${JSON.stringify(b.segs)}`);
    ok(`A2 [${f.key}] 判词徽标 = ${f.expectBadge}`, b.badge === f.expectBadge, `actual="${b.badge}"`);
    // A3 摘要行收进展开态：折叠态 display:none（文本仍在，展开后可见）。
    ok(`A3 [${f.key}] 折叠态摘要行隐藏（卡面恒单行）`, b.summaryDisplay === 'none',
      `display=${b.summaryDisplay}`);
    ok(`A3 [${f.key}] 摘要文本在场（展开后可见）`, b.summary.startsWith(f.expectSummary.slice(0, 12)),
      `actual="${b.summary.slice(0, 48)}"`);
  }
  // A2 墨族单色：completed 与 failed 徽标同色（无色相语义）。
  ok('A2 判词徽标墨族单色（completed == failed 同色）', byKey.internal.badgeColor === byKey.failed.badgeColor,
    `completed=${byKey.internal.badgeColor} failed=${byKey.failed.badgeColor}`);
  // A4 灰字小注（internal/legacy 两帧带载荷）。
  for (const key of ['internal', 'legacy']) {
    const b = byKey[key];
    ok(`A4 [${key}] 小注 = +908 −202 计数`, b.noteText === '+908 −202', `actual="${b.noteText}"`);
    ok(`A4 [${key}] 小注零背景零边框零 padding（rest 构造性）`,
      b.noteLook && b.noteLook.border === '0px' && (b.noteLook.bg === 'rgba(0, 0, 0, 0)' || b.noteLook.bg === 'transparent') && b.noteLook.pad === '0px',
      JSON.stringify(b.noteLook));
    ok(`A4 [${key}] 小注 aria-pressed 初始 false`, b.noteLook?.pressed === 'false');
    ok(`A4 [${key}] 「|」字面分隔段在场`, b.pipe === '|');
    ok(`A4 [${key}] 小注右缘贴卡内容盒右缘（±1px）`, b.geo.noteRight !== null && b.geo.noteRight < 1,
      `delta=${b.geo.noteRight}`);
  }
  // 零载荷帧（external/agentDelegate/merged/failed）⇒ 无小注无 pipe。
  for (const key of ['external', 'agentDelegate', 'merged', 'failed']) {
    ok(`A4 [${key}] 零载荷 ⇒ 无小注无 pipe`, byKey[key].noteText === '' && byKey[key].pipe === '');
  }
  // A5 旧胶囊彻底退役。
  ok('A5 DOM 零 .nf-diff-chip', m.every((b) => b.chipCount === 0));
  ok('A5 DOM 零 .nf-diff-headline', m.every((b) => b.headlineCount === 0));
  // A6 折叠默认（正文 + 摘要 display:none 在场）。
  ok('A6 六帧折叠态默认（正文 display:none）', m.every((b) => b.contentDisplay === 'none'));
  // B1 单行化：六帧卡面行高 = 一行、分段同带、字面次序。
  ok('B1 六帧卡面行高 = 单行（≤24px）', m.every((b) => b.geo.headH !== null && b.geo.headH <= 24),
    m.map((b) => b.geo.headH).join(','));
  ok('B1 六帧名字/徽标/pipe/小注同处一行带（不坠行）', m.every((b) => b.geo.oneLine));
  ok('B1 六帧字面次序「类型·范围·状态 | diff 计数」', m.every((b) => b.geo.order));

  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-1-desktop-dark.png`) });
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-2-internal-singleline-dark.png`) });

  // ── B2 整条 hover 可点击感（internal 帧）───────────────────────────────
  const rest = (await hoverReads(page, 0)).rest;
  ok('B2 rest 行底透明/无描边（反馈纯 hover 态）',
    (rest.headBg === 'rgba(0, 0, 0, 0)' || rest.headBg === 'transparent') && rest.headShadow === 'none',
    `bg=${rest.headBg} shadow=${rest.headShadow.slice(0, 40)}`);
  await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-head').hover();
  await settle(page);
  const rowHover = await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-head').evaluate((el) => {
    const cs = getComputedStyle(el);
    return { cursor: cs.cursor, bg: cs.backgroundColor, shadow: cs.boxShadow };
  });
  readings.tiers.rowHover = { rest, hover: rowHover };
  ok('B2 行 hover 手型（cursor:pointer）', rowHover.cursor === 'pointer', `cursor=${rowHover.cursor}`);
  ok('B2 行 hover 背景微变（透明 → sapphire 微底）',
    rowHover.bg !== rest.headBg && rowHover.bg !== 'rgba(0, 0, 0, 0)' && rowHover.bg !== 'transparent',
    `rest=${rest.headBg} hover=${rowHover.bg}`);
  ok('B2 行 hover 描边微变（inset hairline 出场）', rowHover.shadow !== 'none', `shadow=${rowHover.shadow.slice(0, 60)}`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-3-hover-row-dark.png`) });

  // ── B3 分块 hover：hover 哪个块哪个块亮（块间隔离）────────────────────
  const segBgOf = async (which) => {
    await page.locator('.bubble.injected').nth(0).locator(`.nf-rcpt-name .nf-rcpt-seg`).nth(which).hover();
    await settle(page);
    return page.locator('.bubble.injected').nth(0).evaluate(() => {
      const bubble = document.querySelectorAll('.bubble.injected')[0];
      const bg = (el) => (el ? getComputedStyle(el).backgroundColor : 'absent');
      return {
        seg0: bg(bubble.querySelectorAll('.nf-rcpt-name .nf-rcpt-seg')[0]),
        seg1: bg(bubble.querySelectorAll('.nf-rcpt-name .nf-rcpt-seg')[1]),
        badge: bg(bubble.querySelector('.nf-rcpt-badge')),
        note: bg(bubble.querySelector('.nf-rcpt-note')),
      };
    });
  };
  const TRANSPARENT = (v) => v === 'rgba(0, 0, 0, 0)' || v === 'transparent';
  const bSeg0 = await segBgOf(0);
  ok('B3 hover DELEGATE 段 ⇒ 仅该段亮', !TRANSPARENT(bSeg0.seg0) && TRANSPARENT(bSeg0.seg1) && TRANSPARENT(bSeg0.badge) && TRANSPARENT(bSeg0.note),
    JSON.stringify(bSeg0));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-4-hover-seg-delegate-dark.png`) });
  const bSeg1 = await segBgOf(1);
  ok('B3 hover INTERNAL 段 ⇒ 仅该段亮', TRANSPARENT(bSeg1.seg0) && !TRANSPARENT(bSeg1.seg1) && TRANSPARENT(bSeg1.badge) && TRANSPARENT(bSeg1.note),
    JSON.stringify(bSeg1));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-5-hover-seg-internal-dark.png`) });
  const badgeBg = await (async () => {
    await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-badge').hover();
    await settle(page);
    return page.locator('.bubble.injected').nth(0).evaluate(() => {
      const bubble = document.querySelectorAll('.bubble.injected')[0];
      const bg = (el) => (el ? getComputedStyle(el).backgroundColor : 'absent');
      return {
        seg0: bg(bubble.querySelectorAll('.nf-rcpt-name .nf-rcpt-seg')[0]),
        badge: bg(bubble.querySelector('.nf-rcpt-badge')),
        note: bg(bubble.querySelector('.nf-rcpt-note')),
      };
    });
  })();
  ok('B3 hover COMPLETED 徽标 ⇒ 仅徽标亮', TRANSPARENT(badgeBg.seg0) && !TRANSPARENT(badgeBg.badge) && TRANSPARENT(badgeBg.note),
    JSON.stringify(badgeBg));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-6-hover-seg-completed-dark.png`) });
  const noteBg = await (async () => {
    await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-note').hover();
    await settle(page);
    return page.locator('.bubble.injected').nth(0).evaluate(() => {
      const bubble = document.querySelectorAll('.bubble.injected')[0];
      const bg = (el) => (el ? getComputedStyle(el).backgroundColor : 'absent');
      return {
        seg0: bg(bubble.querySelectorAll('.nf-rcpt-name .nf-rcpt-seg')[0]),
        badge: bg(bubble.querySelector('.nf-rcpt-badge')),
        note: bg(bubble.querySelector('.nf-rcpt-note')),
      };
    });
  })();
  ok('B3 hover diff 计数段 ⇒ 仅小注亮', TRANSPARENT(noteBg.seg0) && TRANSPARENT(noteBg.badge) && !TRANSPARENT(noteBg.note),
    JSON.stringify(noteBg));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-7-hover-seg-diffnote-dark.png`) });

  // ── A6 展开语义：点卡头 ⇒ 正文 + 摘要出（点开详情行为不动）─────────────
  await page.mouse.move(2, 2); // 移开 hover，拍展开前净态。
  await page.locator('.bubble.injected .nf-rcpt-top').nth(0).click();
  await raf(page);
  const expanded = await page.evaluate(() => ({
    content: getComputedStyle(document.querySelectorAll('.bubble.injected .injected-content')[0]).display,
    summary: getComputedStyle(document.querySelectorAll('.bubble.injected .nf-rcpt-summary')[0]).display,
    summaryText: document.querySelectorAll('.bubble.injected .nf-rcpt-summary')[0].textContent.trim(),
  }));
  ok('A6 点卡头展开正文', expanded.content !== 'none', `display=${expanded.content}`);
  ok('A6 展开后摘要行可见', expanded.summary !== 'none', `display=${expanded.summary}`);
  ok('A6 展开后摘要文本 = payload 首行', expanded.summaryText.startsWith('委托完成'), `actual="${expanded.summaryText.slice(0, 40)}"`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-8-internal-expanded-dark.png`) });
  // 点小注不触发折叠切换（stopPropagation 新冒泡面收口）。
  await page.locator('.bubble.injected .nf-rcpt-note').nth(0).click();
  await raf(page);
  const afterNoteClick = await page.evaluate(() => getComputedStyle(document.querySelectorAll('.bubble.injected .injected-content')[0]).display);
  ok('A6 点小注不叠加触发卡头折叠（stopPropagation）', afterNoteClick === expanded.content,
    `content=${afterNoteClick}`);

  // A7 窄面板：legacy 帧短名（DELEGATE · stand · diff-split）压 200px 泡宽 ⇒
  // 省略号截断；徽标/小注仍处一行带、小注仍贴右缘。
  await page.locator('.bubble.injected').nth(2).evaluate((el) => { el.style.maxWidth = '200px'; });
  await raf(page);
  const narrow = await page.locator('.bubble.injected').nth(2).evaluate((bubble) => {
    const name = bubble.querySelector('.nf-rcpt-name');
    const badge = bubble.querySelector('.nf-rcpt-badge');
    const note = bubble.querySelector('.nf-rcpt-note');
    const head = bubble.querySelector('.nf-rcpt-head');
    const inBand = (el) => {
      const rr = el.getBoundingClientRect();
      const hr = head.getBoundingClientRect();
      return rr.top < hr.bottom - 2 && rr.bottom > hr.top + 2;
    };
    return {
      nameScroll: name.scrollWidth, nameClient: name.clientWidth,
      oneLine: inBand(badge) && (!note || inBand(note)),
      noteRight: note ? Math.abs(note.getBoundingClientRect().right - (bubble.getBoundingClientRect().right - parseFloat(getComputedStyle(bubble).paddingRight))) : null,
    };
  });
  readings.tiers.narrow200 = narrow;
  ok('A7/B1 窄200px 短名省略号截断生效', narrow.nameScroll > narrow.nameClient,
    `scroll=${narrow.nameScroll} client=${narrow.nameClient}`);
  ok('A7/B1 窄200px 徽标/小注不坠行', narrow.oneLine);
  ok('A7/A4 窄200px 小注仍贴卡缘', narrow.noteRight !== null && narrow.noteRight < 1, `delta=${narrow.noteRight}`);
  await page.locator('.bubble.injected').nth(2).screenshot({ path: join(SHOTS, `${PHASE}-9-narrow200-dark.png`) });
  await page.close();
}

// ── A8 亮档对照：1440 × light，结构同构 + hover 反馈双档在场 ─────────────
{
  const page = await boot(1440, 900, 'light');
  for (const f of FRAMES.slice(0, 2)) await inject(page, f.msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers.desktopLight = m;
  ok('A8 亮档结构同构（短名/判词/小注逐字一致 + 单行化）',
    m[0].name === FRAMES[0].expectName && m[0].badge === 'COMPLETED'
    && m[0].summary.startsWith(FRAMES[0].expectSummary.slice(0, 12))
    && /^\+\d+ −\d+$/.test(m[0].noteText) && m[0].geo.oneLine && m[0].summaryDisplay === 'none',
    `${m[0].name} | ${m[0].badge} | ${m[0].summary.slice(0, 24)} | ${m[0].noteText}`);
  ok('A8 亮档徽标/小注颜色非透明（双档可读）',
    m[0].badgeColor !== '' && m[0].badgeColor !== 'rgba(0, 0, 0, 0)' && m[0].noteLook && m[0].noteLook.color !== 'rgba(0, 0, 0, 0)');
  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-10-desktop-light.png`) });
  // 亮档 hover 反馈在场（B2 双主题）。
  await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-head').hover();
  await settle(page);
  const lightHover = await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-head').evaluate((el) => {
    const cs = getComputedStyle(el);
    return { cursor: cs.cursor, bg: cs.backgroundColor, shadow: cs.boxShadow };
  });
  ok('A8/B2 亮档行 hover 反馈在场（手型+微底+描边）',
    lightHover.cursor === 'pointer' && lightHover.bg !== 'rgba(0, 0, 0, 0)' && lightHover.bg !== 'transparent' && lightHover.shadow !== 'none',
    JSON.stringify(lightHover));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-11-hover-row-light.png`) });
  await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-note').hover();
  await settle(page);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-12-hover-seg-diffnote-light.png`) });
  await page.close();
}

// ── 响应式另两档：768 / 390（internal+merged 帧，暗色；双端一致面）────────
for (const [tier, width, height] of [['tablet768', 768, 1024], ['mobile390', 390, 844]]) {
  const page = await boot(width, height, 'dark');
  await inject(page, FRAMES[0].msg);
  await inject(page, FRAMES[4].msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers[tier] = m;
  ok(`[${tier}] 卡面单行化 + 判词/小注齐备`,
    m[0].name === FRAMES[0].expectName && m[0].badge === 'COMPLETED' && m[0].noteText !== ''
    && m[0].geo.oneLine && m[0].geo.headH <= 24,
    `oneLine=${m.map((b) => b.geo.oneLine).join(',')} headH=${m.map((b) => b.geo.headH).join(',')}`);
  ok(`[${tier}] merged 帧判词齐备（无小注无 pipe，单行不坠）`,
    m[1].badge === 'MERGED' && m[1].noteText === '' && m[1].pipe === '' && m[1].geo.oneLine,
    `oneLine=${m[1].geo.oneLine} headH=${m[1].geo.headH}`);
  ok(`[${tier}] 折叠态摘要/正文隐藏`, m.every((b) => b.summaryDisplay === 'none' && b.contentDisplay === 'none'));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-13-${tier}-dark.png`) });
  await page.close();
}

await browser.close();
ok('console/pageerror 零新增错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
writeFileSync(join(OUT, `readings.${PHASE}.json`), JSON.stringify(readings, null, 2));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
