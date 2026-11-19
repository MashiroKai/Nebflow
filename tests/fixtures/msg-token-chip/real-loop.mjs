#!/usr/bin/env node
// real-loop.mjs — msgchip 批验收①「真实会话流实拍」驱动（作者令 2026-11-01 20:17）。
//
// 前置：run-isolated.sh 已起隔离实例（BASE_URL/TOKEN env 注入；mock LLM 回复正文
// 引最新铸牌的带短名形态）。本件走**真实前端 + 真实引擎全链**：
//   作者发消息（真实输入框 → WS → 引擎铸牌）→ mock LLM 回复引牌带短名 →
//   chip 渲染短名 → 点击弹小窗（真实引擎端点 GET /api/sessions/msg-token/{core}
//   取原文）→ 桌面 1440 + 手机 375 实拍落证。
//
// 断言面：铸牌 jsonl 在册 / 引擎端点 200 全文 / chip 在场且牌面=短名 / popover
// 全文命中 / 零 console error（资源噪声豁免）/ 截图 ×4。
//
// Run： BASE_URL=http://127.0.0.1:18473 TOKEN=$(cat <home>/auth.json) \
//         SHOTS_DIR=<dir> node tests/fixtures/msg-token-chip/real-loop.mjs

import { chromium } from 'playwright';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { establishSession } from '../../helpers/session-rail.mjs';

const BASE = process.env.BASE_URL || 'http://127.0.0.1:18473';
const TOKEN = (process.env.TOKEN || '').trim();
const SHOTS = process.env.SHOTS_DIR || join(process.cwd(), '.nebflow', 'evidence', '20261101_msgchip_real');
if (!TOKEN) { console.error('FATAL: TOKEN required'); process.exit(1); }
mkdirSync(SHOTS, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 引擎 API 面（铸牌与端点直证）─────────────────────────────────────────────
async function api(path, opts = {}) {
  const r = await fetch(`${BASE}/api${path}`, {
    ...opts,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}`, ...(opts.headers || {}) },
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* leave null */ }
  return { status: r.status, json, text };
}

let pass = 0;
const fails = [];
const ok = (c, label) => { if (c) { pass++; console.log(`  PASS ${label}`); } else { fails.push(label); console.error(`  FAIL ${label}`); } };

// ── 浏览器 ───────────────────────────────────────────────────────────────────
const browser = await chromium.launch();
const errors = [];

async function newPage(viewport) {
  const ctx = await browser.newContext({ viewport, colorScheme: 'light', reducedMotion: 'reduce' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message || e).slice(0, 160)}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().includes('Failed to load resource')) errors.push(`console: ${m.text().slice(0, 160)}`);
  });
  // 浏览器唯一建立链 = console-ticket（Stage D 后 URL/本地长期凭据均退役）：
  // Bearer file token 铸一次性 ticket → /?ticket= 落 HttpOnly 会话 cookie，同源
  // fetch 与 /ws 握手自动骑会话 cookie（session-rail.mjs 单一实现）。
  await establishSession(BASE, TOKEN, page);
  return { ctx, page };
}

// ── 全链 ─────────────────────────────────────────────────────────────────────
console.log('== 1. 建会话（REST，真实引擎） ==');
const created = await api('/sessions', { method: 'POST', body: JSON.stringify({ name: 'msgchip 实拍' }) });
ok(created.status === 200 && created.json && created.json.id, `REST 建会话（status=${created.status}）`);
const SID = created.json && created.json.id;
if (!SID) { console.error('FATAL: 无会话号'); process.exit(1); }

console.log('== 1.5 注入 mock provider（PATCH /api/config 热更，Settings 保存同链） ==');
// 夹具 home 的 llm 节在隔离实例首启后会被既有 boot 写面吃掉（msgchip 实拍批
// 2026-11-01 观察项，证据见批文档 §3）；此处走 Settings UI 保存同一条链
// （PATCH /api/config → ConfigService.updateConfig 深合并 → providerRegistry
// reloadConfig 热载）注入 mock provider，零引擎改动。
const MOCK_BASE = process.env.MOCK_BASE || 'http://127.0.0.1:18990/v1/';
const patchRes = await api('/config', {
  method: 'PATCH',
  body: JSON.stringify({
    config: JSON.stringify({
      llm: { providers: { mock: { baseUrl: MOCK_BASE, apiKey: 'sk-fixture-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
    }),
  }),
});
ok(patchRes.status === 200 && patchRes.json && patchRes.json.updated === true, `PATCH /api/config 热更（status=${patchRes.status}）`);
const cfgAfter = await api('/config');
const cfgRaw = (cfgAfter.json || {}).config;
const cfgBody = typeof cfgRaw === 'string' ? JSON.parse(cfgRaw) : (cfgRaw || cfgAfter.json || {});
const provNames = Object.keys(((cfgBody.llm || {}).providers || {}));
ok(provNames.includes('mock'), `provider 注册读回（got=${provNames.join(',')}）`);
ok((cfgAfter.json || {}).configured === true, `configured 标志 = true（got=${(cfgAfter.json || {}).configured}）`);

// ── 页面上下文裸 WS 帧（骑会话 cookie 同源握手；fire-and-forget：服务端不回执不关连）──
async function wsFrame(pageIn, frame) {
  return pageIn.evaluate((f) => new Promise((resolve, reject) => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws`);
    const t = setTimeout(() => { try { ws.close(); } catch {} resolve(true); }, 800);
    ws.onopen = () => ws.send(JSON.stringify(f));
    ws.onmessage = (ev) => {
      try {
        const j = JSON.parse(ev.data);
        if (j.type === 'error') { clearTimeout(t); try { ws.close(); } catch {} reject(new Error('ws error frame: ' + (j.message || ''))); }
      } catch { /* ignore */ }
    };
    ws.onerror = () => { clearTimeout(t); reject(new Error('ws error: ' + f.type)); };
  }), frame);
}

console.log('== 2. 作者消息一（真实输入框腿；引擎语义：带设备标记的块消息不铸牌） ==');
const { ctx: ctx1, page } = await newPage({ width: 1440, height: 900 });
await sleep(3000); // WS 连接 + configData/sessionList 到齐
// 关掉默认开着的 Canvas 面板（夹具 home 无 localStorage ⇒ 默认面板面），露出聊天区
await page.locator('#canvas-close-btn').click({ timeout: 3000 }).catch(() => {});
// 引擎 boot 会自建默认会话并置 activeId ⇒ 本驱动的会话需显式激活（真实 switchSession 帧；
// 侧栏会话面板默认不在前 ⇒ 行点击不可靠）。
const active = await api('/sessions');
const activeId = active.json && active.json.activeId;
if (activeId !== SID) {
  await wsFrame(page, { type: 'switchSession', sessionId: SID });
  await sleep(1200);
  const active2 = await api('/sessions');
  ok(active2.json && active2.json.activeId === SID, `switchSession 激活本驱动会话（activeId=${active2.json && active2.json.activeId}）`);
  // 页面视图跟随：switchSession 只持久化引擎 activeId，已打开页面的 UI 会话态不回写
  // ⇒ reload 重启 boot 读数（boot 活动会话 = activeId = SID），聊天视图落到本会话。
  await page.reload({ waitUntil: 'load' });
  await sleep(3000);
  await page.locator('#canvas-close-btn').click({ timeout: 3000 }).catch(() => {});
} else {
  ok(true, 'boot 活动会话已是本驱动会话');
}
const input = page.locator('#input');
await input.fill('输入统一令（实拍·打字腿）：所有面向作者的回复都要短句直给、结论先行。');
await page.locator('#send-btn').click();
console.log('  已发送消息一（打字腿），等待回复…');
// 打字腿 = console 渠道腿：网页帧自动携 device 声明（花名册自动绑定）⇒ 引擎前插
// 设备来源标记块 ⇒ msgtokens 批「blocks 在场不铸」语义 ⇒ 此腿回复无牌（mock 回
// 纯文本）。此处断言该既有语义本身（非 chip 批改动面），随后铸牌走 immediateInput
// 腿（CLI 同腿：帧无 device 键 ⇒ 无标记块 ⇒ fromUser 纯文本 ⇒ 铸牌）。
await page.waitForFunction(() => document.querySelectorAll('.row.ai').length >= 1, null, { timeout: 60000 });
await sleep(600);
const typedChips = await page.evaluate(() => document.querySelectorAll('.row.ai .msg-token-chip').length);
ok(typedChips === 0, `打字腿回复无 chip（引擎既有不铸语义，got=${typedChips}）`);

// ── immediateInput 腿（CLI ChatSend 同腿；真人 fromUser=true、帧无 device ⇒ 铸牌）──
async function sendImmediate(pageIn, sid, content) {
  return wsFrame(pageIn, { type: 'immediateInput', sessionId: sid, content });
}

console.log('== 3. 作者消息二（immediateInput 铸牌腿一：回复引牌带短名） ==');
await sendImmediate(page, SID, '输入统一令（实拍）：所有面向作者的回复都要短句直给、结论先行。');
await page.waitForFunction(() => document.querySelector('.row.ai .msg-token-chip'), null, { timeout: 60000 });
await sleep(600);
const chip1 = await page.evaluate(() => {
  const c = document.querySelector('.row.ai .msg-token-chip');
  return c ? { token: c.dataset.msgToken, name: c.textContent } : null;
});
ok(!!chip1, `回复二 chip 在场（name=${chip1 && chip1.name}）`);
ok(chip1 && /^\{\{msg:[0-9a-f]{8}-\d{6}\|[^{}\n|]{1,40}\}\}$/.test(chip1.token), '回复二 chip 牌面 = 引擎实铸带名形态');

console.log('== 4. 作者消息三（铸牌腿二：逐条引牌面） ==');
await sendImmediate(page, SID, '迁移三裁定（实拍）：合并 --no-ff、tag 打在 merge commit、不 push。');
await page.waitForFunction(() => document.querySelectorAll('.row.ai .msg-token-chip').length >= 2, null, { timeout: 60000 });
await sleep(600);
const chipCount = await page.evaluate(() => document.querySelectorAll('.row.ai .msg-token-chip').length);
ok(chipCount >= 2, `回复三累计 chip ≥2（got=${chipCount}）`);

console.log('== 5. 铸牌 jsonl 直证（引擎实铸落盘） ==');
const FIXTURE_HOME = process.env.FIXTURE_HOME || '/tmp/qa-msgchip/home';
const jsonlPath = join(FIXTURE_HOME, 'sessions', SID, 'msg-tokens.jsonl');
let mintLines = [];
try {
  mintLines = readFileSync(jsonlPath, 'utf8').split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
} catch { /* absent */ }
ok(mintLines.length >= 2, `铸牌 jsonl ≥2 行（got=${mintLines.length} @ ${jsonlPath}）`);
ok(mintLines.some((m) => chip1 && m.token && chip1.token.startsWith(m.token.replace(/\}\}$/, ''))), 'chip 牌面 ↔ jsonl 首铸同源');

console.log('== 6. 引擎端点直证（铸牌 jsonl ↔ 只读端点一致） ==');
// 牌号 core = 裸 `sid8-seq6`（chip data-msg-token 携带完整带名形态 ⇒ 剥壳 + 剥短名）。
const core = chip1.token.replace(/^\{\{msg:/, '').replace(/\}\}$/, '').split('|')[0];
const lookup = await api(`/sessions/msg-token/${encodeURIComponent(core)}`);
ok(lookup.status === 200 && lookup.json && lookup.json.found === true, `引擎端点 200 found（core=${core}）`);
ok(lookup.json && typeof lookup.json.text === 'string' && lookup.json.text.includes('输入统一令'), '引擎端点全文命中（铸牌原文）');
ok(lookup.json && lookup.json.sessionName === 'msgchip 实拍', `引擎端点会话名回带（got=${lookup.json && lookup.json.sessionName}）`);

console.log('== 7. 点击 chip → popover 全文（真实端点腿） ==');
await page.click('.row.ai .msg-token-chip');
await page.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; }, null, { timeout: 8000 });
await sleep(300);
const pop = await page.evaluate(() => {
  const p = document.querySelector('.msg-token-pop');
  return {
    sender: p.querySelector('.msg-token-pop-sender').textContent,
    body: p.querySelector('.msg-token-pop-body').textContent,
    foot: p.querySelector('.msg-token-pop-foot').textContent,
    rect: p.getBoundingClientRect().toJSON(),
  };
});
ok(pop.sender === '我', `popover 发送者=我（got=${pop.sender}）`);
ok(pop.body.includes('输入统一令（实拍）'), 'popover 正文 = 原消息全文');
ok(pop.foot.includes(core), 'popover 牌号脚注一致');
ok(pop.rect.left >= 0 && pop.rect.right <= 1440 && pop.rect.top >= 0 && pop.rect.bottom <= 900, 'popover 在视口内');
await page.screenshot({ path: join(SHOTS, 'real-desktop1440-popover.png') });
// 点外关闭回归
await page.mouse.click(30, 450);
await sleep(150);
ok(await page.evaluate(() => !document.querySelector('.msg-token-pop')), '点外关闭（真实 UI）');
await page.screenshot({ path: join(SHOTS, 'real-desktop1440-chips.png') });

console.log('== 8. 手机 375 实拍 ==');
const { ctx: ctx2, page: page2 } = await newPage({ width: 375, height: 812 });
await sleep(3000);
await page2.locator('#canvas-close-btn').click({ timeout: 3000 }).catch(() => {});
await sleep(800);
await page2.waitForFunction(() => document.querySelector('.row.ai .msg-token-chip'), null, { timeout: 30000 });
await page2.click('.row.ai .msg-token-chip');
await page2.waitForFunction(() => { const p = document.querySelector('.msg-token-pop'); return p && p.dataset.state === 'ok'; }, null, { timeout: 8000 });
await sleep(300);
const pop2 = await page2.evaluate(() => {
  const p = document.querySelector('.msg-token-pop');
  return p ? p.getBoundingClientRect().toJSON() : null;
});
ok(pop2 && pop2.width <= 375 - 24 && pop2.left >= 0 && pop2.right <= 375 && pop2.top >= 0 && pop2.bottom <= 812, '手机档 popover 视口夹取');
await page2.screenshot({ path: join(SHOTS, 'real-mobile375-popover.png') });

console.log(`== 结果：${pass} PASS / ${fails.length} FAIL ==`);
writeFileSync(join(SHOTS, 'result.json'), JSON.stringify({ pass, fails, errors, sid: SID, core }, null, 2));
if (errors.length) console.error('console/page errors:\n' + errors.join('\n'));
ok(errors.length === 0, '零 console/page error（资源噪声豁免）');
await ctx1.close().catch(() => {});
await ctx2.close().catch(() => {});
await browser.close();
process.exit(fails.length ? 1 : 0);
