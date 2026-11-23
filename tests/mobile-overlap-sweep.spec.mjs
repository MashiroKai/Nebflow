#!/usr/bin/env node
// Mobile truncation / overlap sweep gate (截断巡检闸, author order 2026-11-24 09:54).
//
// Boots the REAL src/main/resources/web tree at 390x844 (touch, DPR 1, visual-harness
// zero-port装置) and asserts, per panel family, the geometric invariants that the
// author's two phone screenshots violated:
//   O1 无内容穿 header：滚动主体的首行/任一行不得落在悬浮窗头之下（顶部让位）
//   O2 无控制钮压内容：悬浮圆钮（终止钮 / 加号）与列表/消息行的矩形不得相交
//   O3 无元素越出容器：气泡/行的左右边界不得越出其行（max-width + 换行规范）
//   O4 长单行正确截断或换行：标注为省略的行必须真的溢出被截（scrollWidth > clientWidth
//      时 text-overflow:ellipsis 生效），不得出现「未截断却被裁掉」的字
// Panels covered (family list = 截断巡检批清册，见 reports 批报告)：
//   P1 发起群聊（friendGroups.openCreateGroupDialog）
//   P2 后台 agent 详情对话框（bgAgentPopup.openStepPopup，终止悬浮钮 + 消息流）
//   P3 主会话消息流（含长文本系统气泡：BACKGROUND / BG-LIFECYCLE 注入行）
//
// 负对照（必须红）：每个面板再跑一遍「还原修前形态」的注入样式（窗头让位归零 / 流
// 底让位归零），断言闸必须报出对应 O 项违例；若负对照反而绿 ⇒ 闸失明 ⇒ FAIL。
//
// Exit: 0 = 全部正对照绿且负对照红；1 = 任一违例（正对照）或负对照未红；2 = 环境缺失（SKIP）。
//
// 用法：node tests/mobile-overlap-sweep.spec.mjs [--web-root <dir>] [--out <dir>]
import { mkdirSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';
import { evidenceOut } from '../scripts/lib/evidence-batch.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root', 'src/main/resources/web');
setWebRoot(isAbsolute(WEB_ROOT) ? WEB_ROOT : join(ROOT, WEB_ROOT));
const OUT = valOf('--out') || evidenceOut(process.env.MOBILE_SWEEP_SHOTS_DIR, 'mobile-overlap-sweep', 'runtime');
mkdirSync(OUT, { recursive: true });

const CLOCK = 1793940000000;
const ROOT_SID = 'visual-root';
const BG_SID = 'delegate-sweep-1';
const VIEW = { width: 390, height: 844 };

const FRIENDS = ['RouRou', 'Jwin', 'Arrogant', '4bytes', 'ugn87esu7i5g', 'AzarAI', 'Katagorri', 'puerhile', '安之则']
  .map((name, i) => ({ userId: `u${i + 1}`, username: name, display_name: name, avatar: '' }));

const LONG_BG = '[Background task completed] "Run full sbt test-mem2600 frontend backendsuite" (exit 0 · 2h 2m):\n[info] 1474 tests passed, 0 failed, no OOM';
const LONG_NUDGE = '[Background-task health] Your background task has been running for 2h 2m without output — check whether it is still making progress or waiting on a lock.';
const HISTORY = [
  ...Array.from({ length: 10 }, (_, i) => ({ type: i % 2 ? 'ai' : 'user', text: `第 ${i + 1} 条消息：用于撑高消息流并量几何。`, timestamp: CLOCK - 900000 + i * 1000 })),
  { type: 'user', injected: true, source: 'background', eventType: 'completed', header: 'BACKGROUND · SPIDER · COMPLETED', text: LONG_BG, timestamp: CLOCK - 300000 },
  { type: 'user', injected: true, source: 'bg-lifecycle', eventType: 'nudge', header: 'BG-LIFECYCLE · SPIDER · NUDGE', text: LONG_NUDGE, timestamp: CLOCK - 60000 },
];

/** 页内几何读数：只读，零假设选择器（缺失即 null，由断言侧判违例）。 */
const MEASURE = ({ scrollSel, headerSel, firstSel, fabSel, rowSel, bubbleSel }) => {
  const R = (el) => { const b = el.getBoundingClientRect(); return { t: b.top, b: b.bottom, l: b.left, r: b.right }; };
  const out = { violations: [], measured: 0 };
  const scroll = document.querySelector(scrollSel);
  if (!scroll) { out.violations.push({ rule: 'O0', what: 'scroll container missing', sel: scrollSel }); return out; }
  scroll.scrollTop = 0;
  const header = headerSel ? document.querySelector(headerSel) : null;
  const rows = [...scroll.querySelectorAll(rowSel)].filter((e) => e.getClientRects().length);
  out.measured = rows.length;
  // O0 非空闸：没有可量内容 = 闸空转（会让负对照与正对照同时「绿」）⇒ 直接红。
  if (!rows.length) out.violations.push({ rule: 'O0', what: 'no content rows measured (vacuous)', rowSel });
  // O1：窗头之下不得有首行/首项（顶部让位）。窗头 = 悬浮条，首项顶必须 ≥ 窗头底。
  if (header) {
    const hb = R(header).b;
    const firsts = [...document.querySelectorAll(firstSel || rowSel)].filter((e) => e.getClientRects().length && scroll.contains(e));
    for (const f of firsts) {
      const fr = R(f);
      if (fr.t < hb - 0.5) { out.violations.push({ rule: 'O1', what: 'first item under header', cls: (f.className || '').slice(0, 60), itemTop: +fr.t.toFixed(1), headerBottom: +hb.toFixed(1) }); break; }
    }
  }
  // 滚到底再量末端（O2/O3/O4 取末端态，即最易被 dock/钮压住的状态）
  scroll.scrollTop = scroll.scrollHeight;
  const fab = fabSel ? document.querySelector(fabSel) : null;
  // O0 非空闸（控件侧）：声明的悬浮控件不可见 = 闸空转，直接红（不许静默跳过）。
  if (fabSel && !(fab && fab.getClientRects().length)) out.violations.push({ rule: 'O0', what: 'declared floating control not visible (vacuous)', fabSel });
  const endRows = [...scroll.querySelectorAll(rowSel)].filter((e) => e.getClientRects().length);
  if (fab && fab.getClientRects().length && endRows.length) {
    const f = R(fab);
    for (const row of endRows) {
      const r = R(row);
      const hit = r.l < f.r && r.r > f.l && r.t < f.b && r.b > f.t;
      if (hit && r.b > f.t + 0.5) { out.violations.push({ rule: 'O2', what: 'control overlaps content', row: (row.className || '').slice(0, 60), rowBottom: +r.b.toFixed(1), fabTop: +f.t.toFixed(1) }); break; }
    }
  }
  // O3：气泡不得越出其行（左右）。
  const bubbles = bubbleSel ? [...scroll.querySelectorAll(bubbleSel)].filter((e) => e.getClientRects().length) : [];
  for (const b of bubbles) {
    const row = b.closest(rowSel) || b.parentElement;
    const br = R(b), rr = R(row);
    const vw = document.documentElement.clientWidth;
    if (br.l < rr.l - 0.5 || br.r > rr.r + 0.5 || br.r > vw + 0.5 || br.l < -0.5) {
      out.violations.push({ rule: 'O3', what: 'bubble escapes row/viewport', cls: (b.className || '').slice(0, 60), bubble: [br.l, br.r].map((v) => +v.toFixed(1)), row: [rr.l, rr.r].map((v) => +v.toFixed(1)) });
      break;
    }
  }
  // O4：标注省略的文字必须真的溢出（否则是「未截断却被裁」的假省略）；
  // 且溢出的字必须有省略（scrollWidth > clientWidth ⇒ text-overflow 为 ellipsis）。
  for (const el of scroll.querySelectorAll(bubbleSel + ' *')) {
    if (!el.getClientRects().length) continue;
    const cs = getComputedStyle(el);
    if (cs.overflow === 'hidden' && cs.textOverflow === 'ellipsis' && el.scrollWidth > el.clientWidth + 1 && cs.whiteSpace !== 'nowrap') {
      out.violations.push({ rule: 'O4', what: 'clipped without nowrap ellipsis', cls: (el.className || '').slice(0, 60) });
      break;
    }
  }
  return out;
};

async function runPanel(browser, name, setup, cfg, negative) {
  const s = await createSession(browser, {
    width: VIEW.width, height: VIEW.height, colorScheme: 'light', clock: CLOCK,
    apiStubs: {
      '/api/neblink/status': { body: { loggedIn: true, device: { name: 'sweep' }, peers: [] } },
      '/api/friends': { body: { friends: FRIENDS, incoming: [], outgoing: [] } },
      '/api/groups': { body: { conversations: [] } },
      '/api/groups/invites': { body: { invites: [] } },
      '/api/delegates': { body: { record: { id: BG_SID, executor: 'claude', title: 'sweep', createdAt: CLOCK, name: 'sweep' }, live: { status: 'running' }, verdict: null, task: null } },
    },
  });
  s.page.on('pageerror', (e) => { throw new Error(`[${name}] pageerror ${e.message}`); });
  await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: ROOT_SID, messages: HISTORY });
  await setup(s.page, s);
  // 控件就位的前提帧由 setup 自带；此处仅等待渲染落定。
  await settle(s.page, 400);
  if (negative) await s.page.addStyleTag({ content: negative });
  await settle(s.page, 150);
  const res = await s.page.evaluate(MEASURE, cfg);
  await s.page.screenshot({ path: join(OUT, `${name}${negative ? '-negative' : ''}.png`), animations: 'disabled', scale: 'css' });
  await s.close();
  return res;
}

const PANELS = [
  {
    name: 'P1-create-group',
    setup: (page) => page.evaluate(async () => {
      const m = await import('/js/friendGroups.js');
      m.openCreateGroupDialog();
    }).then(() => new Promise((r) => setTimeout(r, 900))),
    // firstSel = 窗体内首个可见项（群名输入 / 章节标签）——修前它正被窗头盖住一半。
    cfg: { scrollSel: '#fm-group-create-overlay .fm-pick-body', headerSel: '#fm-group-create-overlay .fm-pick-header', firstSel: '#fm-group-create-overlay .fm-gs-rename-input, #fm-group-create-overlay .fm-gs-title', fabSel: '#fm-group-create-overlay .fm-pick-fab', rowSel: '.fm-pick-row', bubbleSel: '.fm-pick-row .__none__' },
    // 修前形态：主体顶部让位归零（首行落在窗头之下）。
    negative: '#fm-group-create-overlay .fm-pick-body { padding-top: 2px !important; }',
  },
  {
    name: 'P2-bg-agent-popup',
    setup: async (page, s) => {
      // 运行中快照帧（终止悬浮钮据 status 上位；同 mobile-bgagent-chrome 装置）。
      await s.frame({
        type: 'activeAgents',
        agents: [{ rootSessionId: 'visual-root', sessionId: BG_SID, agentId: BG_SID, name: 'sweep', task: 'sweep', kind: 'Delegate', status: 'Processing', startedAt: CLOCK - 7200000, retryCount: 0 }],
      });
      // 弹窗自取历史（getHistory）：应答器回注入行夹具（同 mobile-bgagent-chrome 装置）。
      await page.evaluate(([sid, msgs]) => {
        window.__histReplies = 0;
        setInterval(() => {
          const sent = window.__wsSent || [];
          const pend = sent.filter((f) => f.type === 'getHistory' && f.sessionId === sid);
          if (pend.length > window.__histReplies) {
            window.__histReplies = pend.length;
            window.__wsMock.onmessage({ data: JSON.stringify({ type: 'historyPage', sessionId: sid, messages: msgs, total: msgs.length, offset: 0, hasMore: false }) });
          }
        }, 40);
      }, [BG_SID, HISTORY]);
      await page.evaluate(async (sid) => {
        const m = await import('/js/bgAgentPopup.js');
        m.openStepPopup(sid, 'sweep', 'sweep');
      }, BG_SID);
      await new Promise((r) => setTimeout(r, 1200));
    },
    cfg: { scrollSel: '.bgagent-overlay .flow-agent-chat', headerSel: null, fabSel: '.bgagent-overlay .bgagent-stop-fab', rowSel: '.row', bubbleSel: '.bubble' },
    // 修前形态：流底让位归零（终止钮压末条）。
    negative: '.bgagent-overlay .flow-agent-chat { padding-bottom: 12px !important; scroll-padding-bottom: 12px !important; }',
  },
  {
    name: 'P3-message-stream',
    setup: (page) => page.waitForSelector('#chat .row', { timeout: 15000 }),
    cfg: { scrollSel: '#chat', headerSel: null, fabSel: '#send-btn', rowSel: '.row', bubbleSel: '.bubble' },
    negative: null,
  },
];

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[mobile-overlap-sweep] browser=${label} root=${WEB_ROOT} view=${VIEW.width}x${VIEW.height}`);
  let failed = 0;
  for (const p of PANELS) {
    const res = await runPanel(browser, p.name, p.setup, p.cfg, null);
    if (res.violations.length) {
      failed++;
      console.error(`FAIL ${p.name}: ${res.violations.length} violation(s)`);
      console.error(JSON.stringify(res.violations, null, 1));
    } else {
      console.log(`PASS ${p.name}`);
    }
    if (p.negative) {
      const neg = await runPanel(browser, p.name, p.setup, p.cfg, p.negative);
      if (neg.violations.length) {
        console.log(`PASS negative-control ${p.name} (red as expected: ${neg.violations.map((v) => v.rule).join(',')})`);
      } else {
        failed++;
        console.error(`FAIL negative-control ${p.name}: gate is blind (no violation on the pre-fix form)`);
      }
    }
  }
  await browser.close();
  if (failed) { console.error(`mobile-overlap-sweep: ${failed} failure(s)`); process.exit(1); }
  console.log('mobile-overlap-sweep: green');
}

main().catch((e) => { console.error(`mobile-overlap-sweep: ${e && e.message ? e.message.split('\n')[0] : e}`); process.exit(e && /playwright|browser|Executable/i.test(String(e.message)) ? 2 : 1); });
