// delegate-receipt-card.spec.mjs — delegate 完工通知卡**diff 统计恢复+版式批**验收
// （2026-11-01 作者令版式四条，承继 10-31 重设计批 / 语义色批 / 单行化+hover 批）：
//
//   作者令（逐字四条）：
//     ① 恢复 diff 统计：完工卡显示该批**合并**的代码量变化（+新增 −删除）。数据
//        源 = 载荷 `merge` 锚键（后端 DelegateWorkReport.mergeDiffStat 现算的合并
//        提交第一亲 diff 统计）优先，files 加删和回落（旧历史行）；无统计的批只
//        出归因块（两种卡面形态）。
//     ② 版式：归因+状态「DELEGATE · INTERNAL · COMPLETED」合成**一个块**（一颗
//        胶囊芯片 .nf-rcpt-pill）；diff 统计**旁边单独一颗块**（.nf-rcpt-note），
//        两块之间用「|」分隔（.nf-rcpt-pipe）。
//     ③ diff 块内部：+N 和 −M 在同一颗块里，+ 与 − 颜色区分（+ 绿 / − 红，
//        --status-success-icon / --status-error-icon 语义 token，双主题达标）。
//     ④ hover：块要有 hover 态（本批样式家族：块底 sapphire tint + 描边微变）。
//   验收：真实渲染两种形态（有 diff 的批 + 无 diff 的批——真实完工行载荷），
//   深浅双主题 × 双引擎（Chromium+WebKit），零 console error；手机档同组件自查。
//
// 断言面（目标全绿）：
//   A1 批次短名 = KIND · EXECUTOR 口径（分段 span textContent 逐字）；
//   A2 判词徽标：completed/failed 同色（墨族单色）；merged 推导；
//   A3 摘要行收进展开态：折叠 display:none，点卡头展开（点开详情语义不动）；
//   A4 diff 统计块：合并统计优先（+445 −51，真实合并提交 b6ceb9d0e 实数）；
//      files 和回落（+908 −202）；merge-only 载荷 = span 静态块（无 aria-pressed
//      无点击面）；零载荷帧无 pipe 无块；
//   A5 旧胶囊彻底退役：DOM 零 .nf-diff-chip / 零 .nf-diff-headline；
//   A6 胶囊构造：pill/note 均为胶囊（hairline 边框 + 999px 圆角 + 横向 padding
//      ——版式批「单独一颗块」口径；22:29 批零构造/右缘贴卡口径随批退役）；
//   B1 单行化：卡面行高 ≤24px；pill/pipe/note 同带不坠行；字面次序
//      「胶囊 | 统计块」相邻（pipe 无 auto 边距——右对齐口径退役）；
//   B2 整条 hover 可点击感：cursor:pointer + sapphire 微底 + inset 描边微变；
//   B3 块级 hover 隔离：hover pill ⇒ 仅 pill 亮、note 不亮；hover note ⇒ 仅 note
//      亮、pill 不亮；hover 不褪 +/− 语义色（恒驻——22:29 批褪色口径退役）；
//   A7 窄 200px：短名省略号截断、pipe/note 不坠行；
//   A8 亮档对照：结构同构 + hover 双档在场；
//   A9 双引擎：WebKit 腿结构同构（真实链路逐字同测）+ 双主题 + 零 console；
//   A10 手机档 390 同组件自查。
//
// 被测真实链路（零伪造 state）：WS 帧 → main.js onMessage('user') →
//   renderInjectedBubble → injectedRows.buildInjectedRow。
// 真实载荷：REAL_ROW_1437 = 宿主 11-01 14:37 stand-dev 二批完工行（13 文件真实
//   载荷 + 真实合并提交 b6ceb9d0e numstat +445 −51）；REAL_ROW_1503 = 宿主
//   11-01 15:03 按钮设计系统包完工行（零 fileChanges——作者实拍的「无 diff」形态）。
// 谱系：delegate-bubble-compact.spec.mjs（2026-10-28 退役）→ 重设计批前身 →
//   单行化+hover 批（2026-10-31）→ 本批（diff 统计恢复+版式）随批重钉。
//
// 用法：
//   PHASE=before node tests/delegate-receipt-card.spec.mjs  # 改前取证（预期红）
//   PHASE=after  node tests/delegate-receipt-card.spec.mjs  # 改后验收（预期全绿）
// 产物：evidence/20261101_delegate-rcpt-diffstat__delegate-nebflow-6043f217/
//   shots/<phase>-*.png + readings.<phase>.json。
import { chromium, webkit } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const PHASE = process.env.PHASE === 'before' ? 'before' : 'after';
const OUT = join(HERE, '..', 'evidence', '20261101_delegate-rcpt-diffstat__delegate-nebflow-6043f217');
const SHOTS = join(OUT, 'shots');
mkdirSync(SHOTS, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'delegate-receipt-root';
// mock 源端口：路由层全量拦截（不发起真连接），端口本身任意。**不可用 1**——
// WebKit/Safari bad-port 保护封禁低危端口表（1=tcpmux 在列），导航直接不发出
// （A9 双引擎腿 TimeoutError 实证）；8899 不在封禁表，双引擎同源。
const ORIGIN = 'http://localhost:8899';

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

// 12 files +908 −202（files 回落链的合成夹具；与作者报障胶囊读数同族口径）。
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

// 真实载荷（宿主 11-01 14:37 完工行，13 文件，diffText 略——本 spec 不点开面板）；
// merge 锚键 = 该批真实合并提交 b6ceb9d0e 的第一亲 diff numstat 实数 +445 −51
// （旧链 files 和 +449 −51 与之有差 ⇒ 合并统计优先读数在本帧可构造性区分）。
const REAL_MERGE_COMMIT = 'b6ceb9d0e';
const REAL_ROW_1437 = {
  nodeId: 'delegate-nebflow-cc246cd7', generatedAt: 1793515065507,
  root: '/Users/kaiyu/Claude code/Stand',
  // 真 13 文件（宿主 .ui.json 实载荷，diffText 略），加删和 = +449 −51——与合并
  // 统计 +445 −51 同帧不同值，合并统计优先读数在本帧可构造性区分。
  files: [
    { path: "docs/20261101_143000_stand-dev-methods-design-merge__delegate-nebflow-cc246cd7.md", changeType: "added", added: 58, removed: 0, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/Plugin.md", changeType: "modified", added: 23, removed: 11, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/plugin.json", changeType: "modified", added: 9, removed: 3, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/SKILL.md", changeType: "modified", added: 76, removed: 34, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/backend/nebflow-backend.md", changeType: "modified", added: 2, removed: 1, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/design/design-spec.md", changeType: "added", added: 90, removed: 0, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/engine/nebflow-docs.md", changeType: "modified", added: 1, removed: 0, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/engine/review.md", changeType: "modified", added: 1, removed: 1, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/frontend/design-system.md", changeType: "modified", added: 2, removed: 0, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/frontend/nebflow-frontend.md", changeType: "modified", added: 3, removed: 1, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/methods/grill.md", changeType: "added", added: 117, removed: 0, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/methods/parallel-research.md", changeType: "added", added: 65, removed: 0, source: "tool-ledger" },
    { path: "src/main/resources/seed/plugins/stand-dev/skills/stand-dev/references/qa/nebflow-qa-frontend.md", changeType: "modified", added: 2, removed: 0, source: "tool-ledger" },
  ],
  merge: { commit: REAL_MERGE_COMMIT, added: 445, removed: 51 },
};

const MERGE_TEXT = [
  '工作结果全文：通知卡重设计实现 + 回归。',
  '',
  '[delegate-judge] verdict: completed.',
  '[delegate-finish] merge status: merged --no-ff into main (e633bcdbe)',
  '[delegate-finish] work result (preserved from the work phase, before the merge round):',
  '（工作段……）',
].join('\n');

// 真实完工行（宿主 11-01 15:03 按钮设计系统包批——作者实拍的无 diff 形态原文）。
const REAL_TEXT_1503 = '"统一按钮设计从稿变实施：建**按钮设计系统包**，全控制台按钮收编。作者令原文要点：\u300c把这个按钮设计变成一个包，让...\u300d:\n阶段〇已排进计划，全链已启动。\n\n<output>\n收到追加令，玻璃包已排为阶段〇，排最前、按钮包直接消费它。';

const FRAMES = [
  {
    key: 'internal',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: '委托完成：通知卡重设计 + 回归。', fileChanges: REAL_ROW_1437,
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'COMPLETED',
    expectSummary: '委托完成：通知卡重设计 + 回归。', expectHash: false,
    // 合并统计优先（真实合并提交实数），不是 files 和 +449 −51。
    expectNote: '+445 −51', expectNoteTag: 'BUTTON',
  },
  {
    key: 'mergeOnly',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: '合并落地（纯收尾批——仓内文件零改动，合并提交统计随行）。',
      fileChanges: { nodeId: 'deleg-merge-only', generatedAt: 1793515065507, root: '/Users/kaiyu/Claude code/Stand', files: [], merge: { commit: 'b6ceb9d0e', added: 123, removed: 45 } },
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'COMPLETED',
    expectSummary: '合并落地（纯收尾批——仓内文件零改动，合并提交统计随行）。', expectHash: false,
    expectNote: '+123 −45', expectNoteTag: 'SPAN',
  },
  {
    key: 'filesOnly',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: '非合并轮完成（旧数据链回落：files 加删和）。',
      fileChanges: PAYLOAD_12,
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'COMPLETED',
    expectSummary: '非合并轮完成（旧数据链回落：files 加删和）。', expectHash: false,
    expectNote: '+908 −202', expectNoteTag: 'BUTTON',
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
    expectSummary: '旧历史行第一行', expectHash: false, expectNote: '+908 −202', expectNoteTag: 'BUTTON',
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
  {
    // 作者实拍形态（真实完工行）：无 fileChanges ⇒ 归因胶囊单块，无 pipe 无统计块。
    key: 'realNoDiff',
    msg: {
      source: 'delegate', eventType: 'completed', sender: 'nebflow',
      header: 'DELEGATE · NEBULA · NEBFLOW · COMPLETED',
      text: REAL_TEXT_1503,
    },
    expectName: 'DELEGATE · INTERNAL', expectBadge: 'COMPLETED',
    expectSummary: '"统一按钮设计从稿变实施', expectHash: false, expectNote: '',
  },
];

const browser = await chromium.launch();

async function boot(width, height, scheme = 'dark', engine = chromium) {
  const page = await (await engine.launch()).newContext({
    viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 2,
  }).then((ctx) => ctx.newPage());
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
  await page.goto(`${ORIGIN}/`);
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  return page;
}

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

/** hover 过渡沉降等时：块 hover 底色有 0.15s transition——hover 后读数前必须
 *  等过渡走完（否则读到返程中值，隔离断言假性失败）。 */
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
    const pill = q('.nf-rcpt-pill');
    const note = q('.nf-rcpt-note');
    const pipe = q('.nf-rcpt-pipe');
    const head = q('.nf-rcpt-head');
    const summary = q('.nf-rcpt-summary');
    const look = (el) => {
      if (!el) return null;
      const cs = getComputedStyle(el);
      return {
        border: cs.borderTopWidth, radius: cs.borderRadius,
        padX: cs.paddingLeft, bg: cs.backgroundColor, color: cs.color,
        marginLeft: cs.marginLeft,
      };
    };
    const bcs = getComputedStyle(bubble);
    const bubbleContentRight = bubble.getBoundingClientRect().right - parseFloat(bcs.paddingRight);
    // 单行化：所有段与卡面行带**实质重叠**（同处一行——容纳各行本征行高差）。
    const inBand = (rr) => {
      const hr = r(head);
      return !!rr && !!hr && rr.top < hr.bottom - 2 && rr.bottom > hr.top + 2;
    };
    const addEl = q('.nf-rcpt-note-add');
    const delEl = q('.nf-rcpt-note-del');
    return {
      name: q('.nf-rcpt-name')?.textContent || '',
      pillText: pill?.textContent.trim() || '',
      segs: [...(q('.nf-rcpt-name')?.querySelectorAll('.nf-rcpt-seg') || [])].map((s) => s.textContent),
      badge: badge?.textContent || '',
      summary: summary?.textContent.trim() || '',
      summaryDisplay: summary ? getComputedStyle(summary).display : 'absent',
      hash: q('.nf-rcpt-hash')?.textContent || '',
      pipe: pipe?.textContent || '',
      noteText: note?.textContent.trim() || '',
      noteTag: note ? note.tagName : '',
      notePressed: note?.getAttribute('aria-pressed') ?? null,
      contentDisplay: getComputedStyle(q('.injected-content')).display,
      chipCount: bubble.querySelectorAll('.nf-diff-chip').length,
      headlineCount: bubble.querySelectorAll('.nf-diff-headline').length,
      badgeColor: badge ? getComputedStyle(badge).color : '',
      addColor: addEl ? getComputedStyle(addEl).color : '',
      delColor: delEl ? getComputedStyle(delEl).color : '',
      pillLook: look(pill),
      noteLook: look(note),
      geo: (() => {
        const hr = r(head);
        const pillR = r(pill);
        const pipeR = r(pipe);
        const noteR = r(note);
        const nameR = r(q('.nf-rcpt-name'));
        return {
          head: hr, pill: pillR, pipe: pipeR, note: noteR, name: nameR,
          headH: hr ? hr.height : null,
          // 单行化：所有在场段同处卡面行带（缺席段不算违例）。
          oneLine: [pillR, pipeR, noteR].every((rr) => rr === null || inBand(rr)),
          // 字面次序「胶囊 | 统计块」+ 相邻（各缝 ≤12px）。
          order: pillR && (!pipeR || pipeR.left >= pillR.left) && (!noteR || (!pipeR || pipeR.left < noteR.left)),
          gapPillPipe: pillR && pipeR ? pipeR.left - pillR.right : null,
          gapPipeNote: pipeR && noteR ? noteR.left - pipeR.right : null,
          nameScroll: q('.nf-rcpt-name') ? q('.nf-rcpt-name').scrollWidth : null,
          nameClient: q('.nf-rcpt-name') ? q('.nf-rcpt-name').clientWidth : null,
          noteRightDelta: noteR ? noteR.right - bubbleContentRight : null,
        };
      })(),
    };
  };
  return [...document.querySelectorAll('.bubble.injected')].map(read);
});

/** rest/hover 块级读数（B2 整条 + B3 块级隔离）。 */
const blockReads = (page, n) => page.evaluate((idx) => {
  const bubble = document.querySelectorAll('.bubble.injected')[idx];
  const bg = (el) => (el ? getComputedStyle(el).backgroundColor : 'absent');
  const bd = (el) => (el ? getComputedStyle(el).borderTopColor : 'absent');
  const head = bubble.querySelector('.nf-rcpt-head');
  const pill = bubble.querySelector('.nf-rcpt-pill');
  const note = bubble.querySelector('.nf-rcpt-note');
  const add = bubble.querySelector('.nf-rcpt-note-add');
  const hcs = getComputedStyle(head);
  return {
    headCursor: hcs.cursor, headBg: hcs.backgroundColor, headShadow: hcs.boxShadow,
    pillBg: bg(pill), pillBorder: bd(pill), noteBg: bg(note), noteBorder: bd(note),
    addColor: add ? getComputedStyle(add).color : 'absent',
  };
}, n);

const readings = { phase: PHASE, tiers: {} };
const TRANSPARENT = (v) => v === 'rgba(0, 0, 0, 0)' || v === 'transparent';

// ── 主档：1440 桌面，八帧 × 暗色（Chromium）───────────────────────────────
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
    // 版式②：归因+状态一颗胶囊块，textContent 逐字「KIND · EXECUTOR · 判词」。
    ok(`版式② [${f.key}] 归因+状态胶囊块字面 = 「${f.expectName} · ${f.expectBadge}」`,
      b.pillText === `${f.expectName} · ${f.expectBadge}`, `actual="${b.pillText}"`);
    // A3 摘要行收进展开态：折叠态 display:none（文本仍在，展开后可见）。
    ok(`A3 [${f.key}] 折叠态摘要行隐藏（卡面恒单行）`, b.summaryDisplay === 'none',
      `display=${b.summaryDisplay}`);
    ok(`A3 [${f.key}] 摘要文本在场（展开后可见）`, b.summary.startsWith(f.expectSummary.slice(0, 12)),
      `actual="${b.summary.slice(0, 48)}"`);
  }
  // A2 墨族单色：completed 与 failed 徽标同色（无色相语义）。
  ok('A2 判词徽标墨族单色（completed == failed 同色）', byKey.internal.badgeColor === byKey.failed.badgeColor,
    `completed=${byKey.internal.badgeColor} failed=${byKey.failed.badgeColor}`);

  // ①③ diff 统计块：合并统计优先 / files 和回落 / merge-only span / 零载荷无块。
  for (const f of FRAMES.filter((x) => x.expectNote)) {
    const key = f.key;
    const b = byKey[key];
    ok(`① [${key}] diff 统计块 = ${f.expectNote}`, b.noteText === f.expectNote, `actual="${b.noteText}"`);
    ok(`① [${key}] pipe「|」在场`, b.pipe === '|', `actual="${b.pipe}"`);
    ok(`① [${key}] 块标签 = ${f.expectNoteTag}${f.expectNoteTag === 'SPAN' ? '（merge-only 无点击面）' : ''}`,
      b.noteTag === f.expectNoteTag, `tag=${b.noteTag}`);
    if (b.noteTag === 'BUTTON') {
      ok(`① [${key}] aria-pressed 初始 false`, b.notePressed === 'false');
    } else {
      ok(`① [${key}] merge-only 无 aria-pressed`, b.notePressed === null, `pressed=${b.notePressed}`);
    }
    // ③ 同块异色：+ 绿 / − 红且互不相同、非透明（语义 token 双主题读数）。
    ok(`③ [${key}] +/− 同块异色（add≠del 且非透明）`,
      b.addColor !== '' && b.delColor !== '' && b.addColor !== b.delColor
      && b.addColor !== 'rgba(0, 0, 0, 0)' && b.delColor !== 'rgba(0, 0, 0, 0)',
      `add=${b.addColor} del=${b.delColor}`);
  }
  for (const key of ['external', 'merged', 'failed', 'realNoDiff']) {
    ok(`① [${key}] 零统计载荷 ⇒ 无 pipe 无统计块（无 diff 形态）`,
      byKey[key].noteText === '' && byKey[key].pipe === '');
  }
  // A5 旧胶囊彻底退役。
  ok('A5 DOM 零 .nf-diff-chip', m.every((b) => b.chipCount === 0));
  ok('A5 DOM 零 .nf-diff-headline', m.every((b) => b.headlineCount === 0));
  // A6 折叠默认（正文 + 摘要 display:none 在场）。
  ok('A6 八帧折叠态默认（正文 display:none）', m.every((b) => b.contentDisplay === 'none'));
  // A6 胶囊构造：pill/note 均为胶囊（hairline 边框 + 999px 圆角 + 横向 padding）。
  for (const key of ['internal', 'mergeOnly', 'filesOnly']) {
    const p = byKey[key].pillLook; const n = byKey[key].noteLook;
    ok(`A6 [${key}] pill 胶囊构造（边框 1px + 999px 圆角 + 横向 padding）`,
      p && p.border === '1px' && p.radius.includes('999') && parseFloat(p.padX) > 0,
      JSON.stringify(p));
    ok(`A6 [${key}] note 胶囊构造（边框 1px + 999px 圆角 + 横向 padding）`,
      n && n.border === '1px' && n.radius.includes('999') && parseFloat(n.padX) > 0,
      JSON.stringify(n));
  }
  // B1 单行化：八帧卡面行高 = 一行、同带、字面次序 + 相邻 + pipe 无 auto 边距。
  ok('B1 八帧卡面行高 = 单行（≤24px）', m.every((b) => b.geo.headH !== null && b.geo.headH <= 24),
    m.map((b) => b.geo.headH).join(','));
  ok('B1 八帧 pill/pipe/note 同处一行带（不坠行）', m.every((b) => b.geo.oneLine));
  ok('B1 八帧字面次序「胶囊 | 统计块」', m.every((b) => b.geo.order));
  ok('B1 有统计帧两块相邻（pill-|、|-note 各缝 ≤12px）',
    ['internal', 'mergeOnly', 'filesOnly', 'legacy'].every((k) =>
      byKey[k].geo.gapPillPipe !== null && byKey[k].geo.gapPillPipe >= -1 && byKey[k].geo.gapPillPipe <= 12
      && byKey[k].geo.gapPipeNote !== null && byKey[k].geo.gapPipeNote >= -1 && byKey[k].geo.gapPipeNote <= 12),
    JSON.stringify(['internal', 'mergeOnly', 'filesOnly', 'legacy'].map((k) => [byKey[k].geo.gapPillPipe, byKey[k].geo.gapPipeNote])));
  const pipeMargins = await page.evaluate(() => [...document.querySelectorAll('.nf-rcpt-pipe')].map((el) => getComputedStyle(el).marginLeft));
  ok('B1 pipe margin-left 恒 0px（非 auto，右缘贴卡口径退役）',
    pipeMargins.every((v) => v === '0px'), pipeMargins.join(','));

  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-1-desktop-dark.png`) });
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-2-internal-dark.png`) });
  await page.locator('.bubble.injected').nth(7).screenshot({ path: join(SHOTS, `${PHASE}-3-realtrow-nodiff-dark.png`) });

  // ── B2 整条 hover 可点击感（internal 帧）───────────────────────────────
  const rest = await blockReads(page, 0);
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
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-4-hover-row-dark.png`) });

  // ── B3 块级 hover 隔离（版式④）：hover 胶囊 ⇒ 仅胶囊亮；hover 统计块 ⇒ 仅块亮；
  //    hover 不褪 +/− 语义色。 ────────────────────────────────────────────
  await page.mouse.move(2, 2); await settle(page);
  const restBlocks = await blockReads(page, 0);
  ok('B3 rest 两块零底（hover 反馈纯 hover 态）',
    TRANSPARENT(restBlocks.pillBg) && TRANSPARENT(restBlocks.noteBg), JSON.stringify(restBlocks));
  await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-pill').hover();
  await settle(page);
  const pillHover = await blockReads(page, 0);
  ok('B3 hover 胶囊 ⇒ 仅胶囊亮（块底+描边微变，统计块不亮）',
    !TRANSPARENT(pillHover.pillBg) && pillHover.pillBorder !== restBlocks.pillBorder && TRANSPARENT(pillHover.noteBg),
    JSON.stringify(pillHover));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-5-hover-pill-dark.png`) });
  await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-note').hover();
  await settle(page);
  const noteHover = await blockReads(page, 0);
  ok('B3 hover 统计块 ⇒ 仅块亮（胶囊不亮）',
    !TRANSPARENT(noteHover.noteBg) && TRANSPARENT(noteHover.pillBg),
    JSON.stringify(noteHover));
  ok('B3 hover 不褪 +/− 语义色（恒驻）', noteHover.addColor === restBlocks.addColor,
    `rest=${restBlocks.addColor} hover=${noteHover.addColor}`);
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-6-hover-note-dark.png`) });

  // ── A6 展开语义：点卡头 ⇒ 正文 + 摘要出（点开详情行为不动）─────────────
  await page.mouse.move(2, 2);
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
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-7-internal-expanded-dark.png`) });
  // 点统计块（button 形态）不触发折叠切换（stopPropagation 收口）。
  await page.locator('.bubble.injected .nf-rcpt-note').nth(0).click();
  await raf(page);
  const afterNoteClick = await page.evaluate(() => getComputedStyle(document.querySelectorAll('.bubble.injected .injected-content')[0]).display);
  ok('A6 点统计块不叠加触发卡头折叠（stopPropagation）', afterNoteClick === expanded.content,
    `content=${afterNoteClick}`);

  // A7 窄面板：legacy 帧短名（DELEGATE · stand · diff-split）压 200px 泡宽 ⇒
  // 省略号截断；pipe/统计块仍处一行带。
  await page.locator('.bubble.injected').nth(4).evaluate((el) => { el.style.maxWidth = '200px'; });
  await raf(page);
  const narrow = await page.locator('.bubble.injected').nth(4).evaluate((bubble) => {
    const name = bubble.querySelector('.nf-rcpt-name');
    const pill = bubble.querySelector('.nf-rcpt-pill');
    const note = bubble.querySelector('.nf-rcpt-note');
    const pipe = bubble.querySelector('.nf-rcpt-pipe');
    const head = bubble.querySelector('.nf-rcpt-head');
    const inBand = (el) => {
      const rr = el.getBoundingClientRect();
      const hr = head.getBoundingClientRect();
      return rr.top < hr.bottom - 2 && rr.bottom > hr.top + 2;
    };
    return {
      nameScroll: name.scrollWidth, nameClient: name.clientWidth,
      oneLine: inBand(pill) && (!pipe || inBand(pipe)) && (!note || inBand(note)),
    };
  });
  readings.tiers.narrow200 = narrow;
  ok('A7/B1 窄200px 短名省略号截断生效', narrow.nameScroll > narrow.nameClient,
    `scroll=${narrow.nameScroll} client=${narrow.nameClient}`);
  ok('A7/B1 窄200px 胶囊/pipe/统计块不坠行', narrow.oneLine);
  await page.locator('.bubble.injected').nth(4).screenshot({ path: join(SHOTS, `${PHASE}-8-narrow200-dark.png`) });
  await page.close();
}

// ── A8 亮档对照：1440 × light，结构同构 + hover 反馈双档在场 ─────────────
{
  const page = await boot(1440, 900, 'light');
  for (const f of [FRAMES[0], FRAMES[1], FRAMES[7]]) await inject(page, f.msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers.desktopLight = m;
  ok('A8 亮档结构同构（胶囊/判词/统计逐字一致 + 单行化 + 合并统计优先）',
    m[0].pillText === 'DELEGATE · INTERNAL · COMPLETED' && m[0].badge === 'COMPLETED'
    && m[0].noteText === '+445 −51' && m[0].geo.oneLine && m[0].summaryDisplay === 'none',
    `${m[0].pillText} | ${m[0].noteText}`);
  ok('A8 亮档 merge-only span 形态在场', m[1].noteTag === 'SPAN' && m[1].noteText === '+123 −45');
  ok('A8 亮档无 diff 形态（真实 15:03 行）单块无 pipe', m[2].pipe === '' && m[2].noteText === '');
  ok('A8 亮档 +/− 颜色非透明（双档可读）',
    m[0].addColor !== 'rgba(0, 0, 0, 0)' && m[0].delColor !== 'rgba(0, 0, 0, 0)' && m[0].addColor !== m[0].delColor,
    `add=${m[0].addColor} del=${m[0].delColor}`);
  await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-9-desktop-light.png`) });
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
  await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-pill').hover();
  await settle(page);
  const lightPillHover = await blockReads(page, 0);
  ok('A8/B3 亮档胶囊 hover 态在场', !TRANSPARENT(lightPillHover.pillBg), JSON.stringify(lightPillHover));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-10-hover-pill-light.png`) });
  await page.close();
}

// ── 响应式另两档：768 / 390（internal+merged 帧，暗色；手机档同组件自查）──
for (const [tier, width, height] of [['tablet768', 768, 1024], ['mobile390', 390, 844]]) {
  const page = await boot(width, height, 'dark');
  await inject(page, FRAMES[0].msg);
  await inject(page, FRAMES[5].msg);
  await raf(page);
  const m = await measureAll(page);
  readings.tiers[tier] = m;
  ok(`[${tier}] 卡面单行化 + 胶囊/统计齐备（合并统计优先）`,
    m[0].pillText === 'DELEGATE · INTERNAL · COMPLETED' && m[0].noteText === '+445 −51'
    && m[0].geo.oneLine && m[0].geo.headH <= 24,
    `oneLine=${m.map((b) => b.geo.oneLine).join(',')} headH=${m.map((b) => b.geo.headH).join(',')}`);
  ok(`[${tier}] merged 帧判词齐备（无 pipe 无统计块，单行不坠）`,
    m[1].badge === 'MERGED' && m[1].noteText === '' && m[1].pipe === '' && m[1].geo.oneLine,
    `oneLine=${m[1].geo.oneLine} headH=${m[1].geo.headH}`);
  ok(`[${tier}] 折叠态摘要/正文隐藏`, m.every((b) => b.summaryDisplay === 'none' && b.contentDisplay === 'none'));
  await page.locator('.bubble.injected').nth(0).screenshot({ path: join(SHOTS, `${PHASE}-11-${tier}-dark.png`) });
  await page.close();
}

// ── A9 双引擎：WebKit 腿——核心结构同构（真实链路逐字同测）+ 双主题 ──────
{
  for (const [scheme, tag] of [['dark', 'webkit-dark'], ['light', 'webkit-light']]) {
    const page = await boot(1440, 900, scheme, webkit);
    for (const f of [FRAMES[0], FRAMES[1], FRAMES[7]]) await inject(page, f.msg);
    await raf(page);
    const m = await measureAll(page);
    readings.tiers[tag] = m;
    ok(`A9 [${tag}] 胶囊字面/判词/合并统计优先同构`,
      m[0].pillText === 'DELEGATE · INTERNAL · COMPLETED' && m[0].badge === 'COMPLETED'
      && m[0].noteText === '+445 −51' && m[0].pipe === '|' && m[0].geo.oneLine);
    ok(`A9 [${tag}] merge-only span + 无 diff 形态同构`,
      m[1].noteTag === 'SPAN' && m[1].noteText === '+123 −45' && m[2].pipe === '' && m[2].noteText === '');
    ok(`A9 [${tag}] +/− 同块异色非透明`,
      m[0].addColor !== m[0].delColor && m[0].addColor !== 'rgba(0, 0, 0, 0)');
    await page.locator('.bubble.injected').nth(0).locator('.nf-rcpt-pill').hover();
    await settle(page);
    const wkPillHover = await blockReads(page, 0);
    ok(`A9 [${tag}] 胶囊 hover 态在场（版式④双引擎）`, !TRANSPARENT(wkPillHover.pillBg),
      JSON.stringify(wkPillHover));
    await page.locator('#chat').screenshot({ path: join(SHOTS, `${PHASE}-12-${tag}.png`) });
    await page.close();
  }
}

await browser.close();
ok('console/pageerror 零新增错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
writeFileSync(join(OUT, `readings.${PHASE}.json`), JSON.stringify(readings, null, 2));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
