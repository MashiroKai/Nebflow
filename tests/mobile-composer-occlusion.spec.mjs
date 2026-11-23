#!/usr/bin/env node
// mobile-composer-occlusion.spec.mjs — 手机消息流末条被输入框遮挡闸（作者截图 IMG_2036 · 2026-11-23，
// 作者令「我发送的消息无法完全显示，会被输入框挡住一半 · 说了很多次了」）。
//
// 为何新增：既有 mobile-overlap-sweep 的 P3/D2 以 #send-btn（发送钮）为控件矩形；而被遮挡的是
// 整个 #input-area 输入框（固定底栏，顶缘 = 输入卡上缘）。发送钮位于输入框底部，末条消息落在
// 输入框顶与发送钮之间时闸照样绿 —— 闸失明。本闸以 #input-area 整框为控件矩形。
//
// 场景（真实形，非空会话）：390×844 手机视口，灌 20 条长消息（多行、含长段落），滚到底。
// 断言：
//   O1 末条消息 .row 的 bottom ≤ #input-area 的 top（末条完整位于输入框上方，不被遮）。
//   O2 滚到底后 #chat 可滚距离内，末条也不被遮（同 O1，已滚底）。
// 负对照：还原修前形态（#chat padding-bottom 归 8px，inline important）⇒ O1 必红，否则闸空转。
//
// 用法：node tests/mobile-composer-occlusion.spec.mjs
// Exit：0 = 正对照绿且负对照红；1 = 违例或负对照未红；2 = 环境缺失。

import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';

setWebRoot(join(ROOT, 'src/main/resources/web'));
const OUT = join(ROOT, 'tmp', 'mobile-composer-occlusion');
mkdirSync(OUT, { recursive: true });

const CLOCK = 1793940000000;
const VIEW = { width: 390, height: 844 };
const LONG = '这是一段较长的测试消息，用来把消息流撑出滚动并量末条的几何位置。'.repeat(3);
const HISTORY = Array.from({ length: 20 }, (_, i) => ({
  type: i % 2 ? 'ai' : 'user',
  text: `第 ${i + 1} 条：${LONG}`,
  timestamp: CLOCK - 3600000 + i * 60000,
}));

const MEASURE = () => {
  const R = (el) => el.getBoundingClientRect();
  const chat = document.getElementById('chat');
  const ia = document.getElementById('input-area');
  const rows = [...chat.querySelectorAll('.row')].filter((e) => e.getClientRects().length);
  chat.style.scrollBehavior = 'auto';
  chat.scrollTop = chat.scrollHeight;
  const last = rows[rows.length - 1];
  const out = { rows: rows.length, scrollable: chat.scrollHeight > chat.clientHeight, violations: [] };
  if (!last || !ia) { out.violations.push({ rule: 'O0', what: 'row or input-area missing (vacuous)' }); return out; }
  const lr = R(last), ir = R(ia);
  out.lastBottom = +lr.bottom.toFixed(1);
  out.inputTop = +ir.top.toFixed(1);
  out.overlapPx = +(lr.bottom - ir.top).toFixed(1);
  if (lr.bottom > ir.top + 0.5) out.violations.push({ rule: 'O1', what: 'last message under composer', overlapPx: out.overlapPx });
  // O2（项 2 数值闸）：真实 reserve（#chat inline padding-bottom）必须 ≥ 输入框实际高度，
  // 否则末条按构造即被遮。reserve 与输入框高度的差值逐态记录，不靠推断。
  out.reserve = parseFloat(chat.style.paddingBottom) || 0;
  out.inputH = +ir.height.toFixed(1);
  if (out.reserve + 0.5 < out.inputH) out.violations.push({ rule: 'O2', what: 'reserve < input height', reserve: out.reserve, inputH: out.inputH });
  return out;
};

async function runOnce(browser, negative, draftLines = 0) {
  const s = await createSession(browser, {
    width: VIEW.width, height: VIEW.height, colorScheme: 'light', clock: CLOCK,
  });
  s.page.on('pageerror', (e) => { throw new Error('pageerror ' + e.message); });
  await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'occlusion-root', messages: HISTORY });
  await settle(s.page, 400);
  if (negative) {
    // 陈旧 reserve 负对照：输入框被外部上移 60px（键盘/dock 位移）且**不发任何 resize 事件**，
    // 尺寸不变 ⇒ RO 不触发、reserve 停在 132。末条必被挡（O1 必红），闸据此证明非空转。
    // （早先的 inline 8px 负对照会被 #chat 的 RO 立即重算覆盖，已弃用。）
    await s.page.evaluate(() => { document.getElementById('input-area').style.bottom = '60px'; });
  }
  if (draftLines) {
    // 多行草稿态：输入框变高 → reserve 必须随之实时跟进（RO 观察输入框）。
    await s.page.evaluate((n) => {
      const t = document.getElementById('input');
      t.value = Array(n).fill('草稿行一段文字').join('\n');
      t.dispatchEvent(new Event('input', { bubbles: true }));
    }, draftLines);
    await settle(s.page, 400);
  }
  await settle(s.page, 150);
  const res = await s.page.evaluate(MEASURE);
  const tag = `${negative ? '-negative' : ''}${draftLines ? `-draft${draftLines}` : ''}`;
  await s.page.screenshot({ path: join(OUT, `composer-occlusion${tag}.png`), animations: 'disabled', scale: 'css' });
  await s.close();
  return res;
}

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[mobile-composer-occlusion] browser=${label} view=${VIEW.width}x${VIEW.height}`);
  let failed = 0;
  const pos = await runOnce(browser, false);
  if (pos.rows < 10) { failed++; console.error(`FAIL vacuous: only ${pos.rows} rows`); }
  if (!pos.scrollable) { failed++; console.error('FAIL vacuous: stream not scrollable'); }
  if (pos.violations.length) { failed++; console.error('FAIL positive:', JSON.stringify(pos)); }
  else console.log(`PASS positive: rows=${pos.rows} lastBottom=${pos.lastBottom} inputTop=${pos.inputTop} reserve=${pos.reserve} inputH=${pos.inputH}`);
  // 多行草稿（8 行）：输入框变高后 reserve 须实时跟进到 ≥ 输入框高度（O2），末条不被遮（O1）。
  const draft = await runOnce(browser, false, 8);
  if (draft.violations.length) { failed++; console.error('FAIL draft8:', JSON.stringify(draft)); }
  else console.log(`PASS draft8: lastBottom=${draft.lastBottom} inputTop=${draft.inputTop} reserve=${draft.reserve} inputH=${draft.inputH}`);
  const neg = await runOnce(browser, true);
  if (neg.violations.length) console.log(`PASS negative-control (red as expected: ${neg.violations.map((v) => v.rule).join(',')})`);
  else { failed++; console.error('FAIL negative-control: gate blind (no overlap on pre-fix form)', JSON.stringify(neg)); }
  await browser.close();
  if (failed) { console.error(`mobile-composer-occlusion: ${failed} failure(s)`); process.exit(1); }
  console.log('mobile-composer-occlusion: green');
}

main().catch((e) => {
  console.error(`mobile-composer-occlusion: ${e && e.message ? e.message.split('\n')[0] : e}`);
  process.exit(e && /playwright|browser|Executable/i.test(String(e.message)) ? 2 : 1);
});
