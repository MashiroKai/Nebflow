#!/usr/bin/env node
// perm-card-shots.mjs — 权限卡「前后对比」实拍 + 重复项读数（瘦身批 2026-11-21）。
//
// 驱动的是**真产品** `chat.js renderPermissionPrompt`（不是 harness 复刻），喂同一份
// 真帧 fixture（tests/fixtures/perm-card-detail/frames.json：Bash L2 / Write L1），
// 两端（1440×900 桌面 / 375×812 手机）各截一张整卡，并对卡内每条「事实」数出现次数。
//
// 用法：
//   LABEL=before ROOT=<基线树根> node tests/perm-card-shots.mjs
//   LABEL=after  ROOT=<feat 树根> node tests/perm-card-shots.mjs     (ROOT 缺省 = 本仓)
// 产物：OUT（缺省 .stand/tmp/chat-input-perm-card/evidence/perm-card/<LABEL>/）
//   <vp>-<tag>.png        整卡截图
//   readings.json         每张卡：各事实出现次数（重复项 = 次数 > 1）

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(HERE);
const ROOT = process.env.ROOT ? normalize(process.env.ROOT) : REPO;
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const FIXTURE = join(REPO, 'tests', 'fixtures', 'perm-card-detail', 'frames.json');
const LABEL = process.env.LABEL || 'after';
const OUT = process.env.OUT || join(REPO, '.stand', 'tmp', 'chat-input-perm-card', 'evidence', 'perm-card', LABEL);
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'perm-shots-session';

const HTML_HEAD = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="/src/main/resources/web/css/base.css">
<link rel="stylesheet" href="/src/main/resources/web/css/chat.css">
<link rel="stylesheet" href="/src/main/resources/web/css/modal.css">
<link rel="stylesheet" href="/src/main/resources/web/css/sapphire.css">
<style>html,body{margin:0;background:var(--color-bg,#101418)}#chat{width:680px;max-width:94vw;margin:0 auto;padding:16px 0 36px}</style>
</head><body><div id="chat"></div>
<script type="module">
const WEB = '/src/main/resources/web/js';
const { chatViews, ChatView, setActiveView } = await import(\`\${WEB}/chatView.js\`);
const state = (await import(\`\${WEB}/state.js\`)).default;
const chat = await import(\`\${WEB}/chat.js\`);
const chatEl = document.getElementById('chat');
chatViews.primary = new ChatView('primary', { chat: chatEl });
chatViews.primary.sessionId = '${SID}';
setActiveView(chatViews.primary);
state.activeSessionId = '${SID}';
state.ws = { readyState: 1, send: () => {} };
const data = await (await fetch('/__fixture.json')).json();
window.__render = (i) => {
  chatEl.textContent = '';
  const f = data.frames[i];
  chat.renderPermissionPrompt(f.toolName, '', f.input, '${SID}', f.permDetail.reason.level,
    f.sourceAgent, f.sourceSession, null, 'req-shot-' + i, 'confirm-edits', undefined, f.permDetail);
  return true;
};
window.__READY = true;
</script></body></html>`;

function serve() {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      const url = decodeURIComponent((req.url || '/').split('?')[0]);
      if (url === '/__shots.html') { res.writeHead(200, { 'content-type': MIME['.html'] }); res.end(HTML_HEAD); return; }
      if (url === '/__fixture.json') { res.writeHead(200, { 'content-type': MIME['.json'] }); res.end(readFileSync(FIXTURE)); return; }
      const file = normalize(join(ROOT, url));
      if (!file.startsWith(ROOT) || !existsSync(file)) { res.writeHead(404).end(); return; }
      res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(readFileSync(file));
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

// 事实清单（每条 = 一个要被卡内「只出现一次」的事实，取自 fixture 的真值）
function factsOf(frame) {
  const d = frame.permDetail;
  const facts = [];
  facts.push({ id: 'tool', text: frame.toolName, note: '工具名' });
  if (d.reason && d.reason.label) facts.push({ id: 'label', text: d.reason.label, note: '危险类型·人话标签' });
  if (d.reason && d.reason.ruleId) facts.push({ id: 'rule', text: d.reason.ruleId, note: '判定规则 id' });
  if (d.scope && d.scope.device) facts.push({ id: 'device', text: d.scope.device, note: '设备' });
  if (d.scope && d.scope.cwd) facts.push({ id: 'cwd', text: d.scope.cwd, note: '工作目录' });
  if (d.scope && d.scope.target) facts.push({ id: 'target', text: d.scope.target, note: '目标路径' });
  const body = frame.toolName === 'Write' && frame.input && frame.input.content ? frame.input.content : (frame.input && frame.input.command) || '';
  if (body) facts.push({ id: 'body', text: body.split('\n')[0], note: '正文首行' });
  return facts;
}

const VIEWPORTS = [
  { name: 'desktop-1440x900', width: 1440, height: 900, mobile: false },
  { name: 'mobile-375x812', width: 375, height: 812, mobile: true },
];

const server = await serve();
const port = server.address().port;
const browser = await chromium.launch({ headless: true });
const readings = [];
const errors = [];
try {
  for (const vp of VIEWPORTS) {
    const ctx = await browser.newContext({
      viewport: { width: vp.width, height: vp.height }, deviceScaleFactor: 2,
      isMobile: vp.mobile, hasTouch: vp.mobile, locale: 'zh-CN',
    });
    await ctx.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
    });
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(`${vp.name}: ${String(e)}`));
    await page.goto(`http://127.0.0.1:${port}/__shots.html`, { waitUntil: 'load' });
    await page.waitForFunction(() => window.__READY === true, null, { timeout: 20000 });
    const fixture = JSON.parse(readFileSync(FIXTURE, 'utf8'));
    for (let i = 0; i < fixture.frames.length; i++) {
      const f = fixture.frames[i];
      const tag = f.toolName.toLowerCase();
      await page.evaluate((idx) => window.__render(idx), i);
      await page.waitForTimeout(120);
      const box = page.locator('#chat .option-box');
      const bubble = page.locator('#chat .bubble');
      const shotPath = join(OUT, `${vp.name}-${tag}.png`);
      await bubble.first().screenshot({ path: shotPath });
      const text = await bubble.first().innerText();
      const hostText = await page.evaluate(() => document.querySelector('#chat .bubble')?.textContent || '');
      const facts = factsOf(f).map((fa) => {
        const count = hostText.split(fa.text).length - 1;
        return { id: fa.id, note: fa.note, text: fa.text.length > 60 ? fa.text.slice(0, 60) + '…' : fa.text, count, duplicate: count > 1 };
      });
      readings.push({ vp: vp.name, tag, shot: shotPath, optionBoxPresent: (await box.count()) > 0, facts, visibleChars: text.length });
    }
    await ctx.close();
  }
} finally {
  await browser.close().catch(() => {});
  server.close();
}

const dup = readings.flatMap((r) => r.facts.filter((f) => f.duplicate).map((f) => `${r.vp}/${r.tag}:${f.id}×${f.count}`));
const out = { label: LABEL, root: ROOT, generatedAt: new Date().toISOString(), pageErrors: errors, duplicates: dup, readings };
writeFileSync(join(OUT, 'readings.json'), JSON.stringify(out, null, 2) + '\n');
console.log(`perm-card-shots [${LABEL}] root=${ROOT}`);
for (const r of readings) {
  console.log(`  ${r.vp} ${r.tag}: ` + r.facts.map((f) => `${f.id}×${f.count}`).join(' '));
}
console.log(`  duplicates: ${dup.length ? dup.join(', ') : 'none'}`);
console.log(`  pageErrors: ${errors.length}`);
console.log(`  shots → ${OUT}`);
process.exit(errors.length ? 1 : 0);
