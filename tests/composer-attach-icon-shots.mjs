// composer-attach-icon-shots.mjs — 输入框附件图标化批（作者令 2026-11-02
// 「输入框里的附件条不显示文件名，用与图片附件缩略图一样大小的图标代替」）
// 的验收证据 + 几何契约回归。
//
// 本批形状（手机层优先，桌面共组件先不动）：
//   · 文件附件 chip 在 body.mobile-layer 下 = **扩展名族图标盒**：28×28 定盒，
//     与图片附件缩略图 .att-thumb **逐值同盒**（宽/高/圆角/描边/border-box）；
//     文件名文字褪出条面（信息不丢：chip title + DOM 文本仍在）。
//   · 多个附件并排同规则（图片仍是图片缩略图，形态零回归）。
//   · ✕ 删除角标与发送链零触碰；添加附件后占位提示文字**不变**（仍为
//     「输入消息...」——手机层不再走 `input.ph.attachments` 文字提示）。
//   · 桌面（1280）零触碰：chip 仍是「线框图标 + 文件名」胶囊、占位仍是
//     `input.ph.attachments`（本批申报项，D 腿钉住原值）。
//
// 用法：node tests/composer-attach-icon-shots.mjs
// 产物：evidence/20261102_composer-attach-icon/*.png + stdout 断言清单。
// 形制同 tests/composer-mobile-slim-shots.mjs（真 web 树 + 真 WS 桩，零伪造 state）。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// 改动前/后对照开关（验红用，同 tests/msgqueue-recall-attach.spec.mjs 形制）：
// 未设 = 本仓 src/main/resources/web（改后树）；设为一份改前 web 树 ⇒ 图标化
// 判据逐条应红（桌面档原形态），证明绿是改动带来的、不是夹具自证。
const WEB = process.env.NEBFLOW_WEB_ROOT
  || join(HERE, '..', 'src', 'main', 'resources', 'web');
const SHOTS_DIR = process.env.SHOTS_DIR || join(HERE, '..', 'evidence', '20261102_composer-attach-icon');
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-atticon';
const NOW = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 1×1 真 PNG（可解码；与 msgqueue-recall-attach 夹具同源字面量）。
const B64_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const PREVIEW_PNG = 'data:image/png;base64,' + B64_PNG;

const HISTORY = [
  { type: 'ai', text: '附件图标批：手机层输入条的文件附件已改为扩展名族图标盒。', timestamp: NOW - 60_000 },
  { type: 'user', text: '收到，先看单独 PDF 的形态。', timestamp: NOW - 50_000 },
];

const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

async function boot(colorScheme, viewport, hasTouch, tag) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2, hasTouch });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${tag}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${tag}] pageerror: ${e.message}`));
  await page.routeWebSocket(/\/ws/, (ws) => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);
  return { ctx, page };
}

/** 往待发附件里灌夹具并重渲附件条（真实渲染腿 = chat.js renderAttachmentPreview）。 */
async function seed(page, atts) {
  await page.evaluate(async (list) => {
    const chatMod = await import('/js/chat.js');
    const { activeView } = await import('/js/chatView.js');
    activeView.pendingAttachments.length = 0;
    for (const a of list) activeView.pendingAttachments.push(a);
    chatMod.renderAttachmentPreview();
  }, atts);
  await sleep(320);
}

const FILE_PDF = { type: 'text', mimeType: 'application/pdf', data: 'JVBERi0xLjQ=', name: '季度报告-2026Q3.pdf', hash: '', size: 8 };
const FILE_XLS = { type: 'text', mimeType: 'application/vnd.ms-excel', data: 'UEsDBA==', name: '预算表.xlsx', hash: '', size: 8 };
const FILE_ZIP = { type: 'text', mimeType: 'application/zip', data: 'UEsDBA==', name: '证据包.zip', hash: '', size: 8 };
const FILE_MD = { type: 'text', mimeType: 'text/markdown', data: 'IyB0', name: '输入卡收窄对照说明.md', hash: '', size: 4 };
const IMG = { type: 'image', mimeType: 'image/png', data: B64_PNG, name: 'mobile-evidence.png', preview: PREVIEW_PNG };

/** 附件条几何探针：chip 盒 / 图标盒 / 缩略图盒 / 占位文案 全量读数。 */
async function probe(page) {
  return page.evaluate(() => {
    const strip = document.getElementById('attachment-preview');
    const input = document.getElementById('input');
    const cs = (el) => (el ? getComputedStyle(el) : null);
    const box = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      const s = cs(el);
      return {
        w: +r.width.toFixed(2), h: +r.height.toFixed(2),
        radius: s.borderTopLeftRadius, border: s.borderTopWidth,
        boxSizing: s.boxSizing, display: s.display,
        bgImage: s.backgroundImage === 'none' ? 'none' : 'gradient',
        x: +r.x.toFixed(1), y: +r.y.toFixed(1),
      };
    };
    const chips = [...strip.children].filter((c) => !c.classList.contains('mh-att-more'));
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      placeholder: input.placeholder,
      chipCount: chips.length,
      stripH: +strip.getBoundingClientRect().height.toFixed(1),
      chips: chips.map((c) => {
        const isFile = c.classList.contains('att-file');
        const icon = c.querySelector('.att-file-icon');
        const nameEl = c.querySelector('.att-file-name');
        const glyph = c.querySelector('.att-file-glyph');
        const thumb = c.querySelector('.att-thumb');
        return {
          kind: isFile ? 'file' : (thumb ? 'image' : 'other'),
          cls: c.className,
          chip: box(c),
          icon: box(icon),
          iconCls: icon ? icon.className : null,
          iconText: icon ? icon.textContent : null,
          thumb: box(thumb),
          nameDisplay: nameEl ? cs(nameEl).display : null,
          nameText: nameEl ? nameEl.textContent : null,
          glyphDisplay: glyph ? cs(glyph).display : null,
          title: c.title || '',
          hasRemove: !!c.querySelector('.att-remove'),
        };
      }),
    };
  });
}

const MOBILE = { width: 375, height: 812 };
const DESKTOP = { width: 1280, height: 860 };

for (const scheme of ['light', 'dark']) {
  const tag = `mobile-${scheme}`;
  const { ctx, page } = await boot(scheme, MOBILE, true, tag);

  // ── 帧 0：空态（占位原文）────────────────────────────────────────────
  await page.waitForFunction(() => window.__nfMobileLayer && window.__nfMobileLayer.active === true, null, { timeout: 15000 });
  await sleep(600);
  const p0 = await probe(page);
  ok(`${tag} M0 手机层已激活`, p0.mobileLayer === true);
  ok(`${tag} P0 空态占位 = 请输入消息...`, p0.placeholder === '输入消息...', JSON.stringify(p0.placeholder));
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-0-empty.png`) });

  // ── 帧 1：单独 PDF ──────────────────────────────────────────────────
  await seed(page, [FILE_PDF]);
  const p1 = await probe(page);
  const pdf = p1.chips[0] || null;
  ok(`${tag} A1 单 PDF：chip 存在且是 .att-file`, !!pdf && pdf.kind === 'file' && p1.chipCount === 1);
  ok(`${tag} A1 单 PDF：chip 盒 28×28`, !!pdf && pdf.chip.w === 28 && pdf.chip.h === 28, JSON.stringify(pdf && pdf.chip));
  ok(`${tag} A1 单 PDF：图标盒 28×28（角标 PDF 色族）`,
    !!pdf && pdf.icon && pdf.icon.w === 28 && pdf.icon.h === 28 && /pdf/.test(pdf.iconCls || '') && pdf.icon.bgImage === 'gradient',
    JSON.stringify(pdf && { icon: pdf.icon, cls: pdf.iconCls, text: pdf.iconText }));
  ok(`${tag} A1 单 PDF：文件名文字已褪出条面（display:none）`,
    !!pdf && pdf.nameDisplay === 'none' && pdf.glyphDisplay === 'none',
    JSON.stringify(pdf && { name: pdf.nameDisplay, glyph: pdf.glyphDisplay }));
  ok(`${tag} A1 单 PDF：名字信息保留（chip.title）`, !!pdf && pdf.title.includes('季度报告'), JSON.stringify(pdf && pdf.title));
  ok(`${tag} A1 单 PDF：✕ 删除角标仍在`, !!pdf && pdf.hasRemove === true);
  ok(`${tag} P1 有 PDF 附件时占位仍 = 请输入消息...`, p1.placeholder === '输入消息...', JSON.stringify(p1.placeholder));
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-1-pdf.png`) });

  // ── 帧 2：单独图片（形态零回归 + 与图标盒同尺寸断言基线）──────────────
  await seed(page, [IMG]);
  const p2 = await probe(page);
  const img = p2.chips[0] || null;
  ok(`${tag} A2 单图片：仍是 .att-thumb 缩略图（28×28）`,
    !!img && img.kind === 'image' && img.thumb.w === 28 && img.thumb.h === 28, JSON.stringify(img && img.thumb));
  ok(`${tag} P2 有图片附件时占位仍 = 请输入消息...`, p2.placeholder === '输入消息...', JSON.stringify(p2.placeholder));
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-2-image.png`) });

  // ── 帧 3：混合（PDF + 表格 + 压缩 + 图片）：图标盒 == 图片缩略图盒 ────────
  await seed(page, [FILE_PDF, FILE_XLS, FILE_ZIP, IMG]);
  const p3 = await probe(page);
  const files3 = p3.chips.filter((c) => c.kind === 'file');
  const imgs3 = p3.chips.filter((c) => c.kind === 'image');
  /** 几何基线只取有盒的 file/image chip（0×0 盒 = 未布局件，不作基线）。 */
  const laidOut = p3.chips.filter((c) => {
    const b = c.icon || c.thumb;
    return c.kind !== 'other' && !!b && b.w > 0;
  });
  ok(`${tag} A3 混合三帧：3 文件图标 + 1 图片缩略`, files3.length === 3 && imgs3.length === 1,
    JSON.stringify(p3.chips.map((c) => c.kind)));
  const refThumb = (imgs3[0] && imgs3[0].thumb && imgs3[0].thumb.w > 0) ? imgs3[0].thumb : null;
  ok(`${tag} A3 混合：图标盒与图片缩略图**逐值同盒**（w/h/圆角/描边/border-box）`,
    !!refThumb && files3.every((c) => c.icon
      && c.icon.w === refThumb.w && c.icon.h === refThumb.h
      && c.icon.radius === refThumb.radius && c.icon.border === refThumb.border
      && c.icon.boxSizing === refThumb.boxSizing),
    JSON.stringify({ thumb: refThumb, icons: files3.map((c) => c.icon) }));
  ok(`${tag} A3 混合：色族按扩展名族分派（PDF 红 / 表格绿 / 压缩棕）`,
    files3.length === 3
      && /pdf/.test(files3[0].iconCls) && /xls/.test(files3[1].iconCls) && /zip/.test(files3[2].iconCls)
      && files3.every((c) => c.icon.bgImage === 'gradient'),
    JSON.stringify(files3.map((c) => c.iconCls)));
  ok(`${tag} A3 混合：多附件并排同基线（同一 y，同一行带内）`,
    !!refThumb && laidOut.length > 0
      && laidOut.every((c) => Math.abs((c.icon || c.thumb).y - refThumb.y) <= 1),
    JSON.stringify(laidOut.map((c) => (c.icon || c.thumb).y)));
  ok(`${tag} A3 混合：全部文件名文字褪出（无可见名文字）`,
    files3.every((c) => c.nameDisplay === 'none'));
  ok(`${tag} P3 混合态占位仍 = 请输入消息...`, p3.placeholder === '输入消息...', JSON.stringify(p3.placeholder));
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-3-mixed.png`) });

  // ── 移除回归：点 ✕ 掉一个文件 chip ──────────────────────────────────
  const beforeRemove = (await probe(page)).chipCount;
  await page.evaluate(() => {
    const rm = document.querySelectorAll('#attachment-preview .att-file .att-remove');
    if (rm[0]) rm[0].click();
  });
  await sleep(320);
  const afterRemove = await probe(page);
  const pendingAfterRemove = await page.evaluate(async () => {
    const { activeView } = await import('/js/chatView.js');
    return activeView.pendingAttachments.length;
  });
  ok(`${tag} R1 ✕ 移除：条带少一枚且 pending 同步`,
    afterRemove.chipCount === beforeRemove - 1 && pendingAfterRemove === 3,
    JSON.stringify({ before: beforeRemove, after: afterRemove.chipCount, pending: pendingAfterRemove }));
  ok(`${tag} R2 ✕ 移除后占位仍 = 请输入消息...（仍余附件）`, afterRemove.placeholder === '输入消息...', JSON.stringify(afterRemove.placeholder));
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-4-after-remove.png`) });

  // ── 发送回归：清空条带 + 占位复位 ───────────────────────────────────
  await page.evaluate(() => { document.getElementById('send-btn').click(); });
  await sleep(900);
  const afterSend = await probe(page);
  const sentState = await page.evaluate(async () => {
    const { activeView } = await import('/js/chatView.js');
    return { pending: activeView.pendingAttachments.length, text: document.getElementById('input').value };
  });
  ok(`${tag} S1 发送：条带清空 + pending 清零`,
    afterSend.chipCount === 0 && sentState.pending === 0, JSON.stringify({ chips: afterSend.chipCount, ...sentState }));
  ok(`${tag} S2 发送后占位 = 请输入消息...`, afterSend.placeholder === '输入消息...', JSON.stringify(afterSend.placeholder));
  await page.screenshot({ path: join(SHOTS_DIR, `${tag}-5-sent.png`) });

  await ctx.close();
}

// ── 桌面零触碰腿（1280）：chip 仍是「线框图标 + 文件名」胶囊，占位仍是附件文案 ──
{
  const { ctx, page } = await boot('light', DESKTOP, false, 'desktop-light');
  await seed(page, [FILE_PDF, IMG]);
  const d = await probe(page);
  const f = d.chips.find((c) => c.kind === 'file');
  ok('desktop D0 非手机层（桌面应用树）', d.mobileLayer === false);
  ok('desktop D1 桌面文件 chip 仍为胶囊（图标盒不上桌 / 名字可见）',
    !!f && f.nameDisplay !== 'none' && (!f.icon || f.icon.display === 'none'),
    JSON.stringify(f && { name: f.nameDisplay, icon: f.icon && f.icon.display }));
  ok('desktop D2 桌面附件态占位仍是既有附件文案（不在本批范围）',
    d.placeholder === '已附加文件——输入一句话一起发送', JSON.stringify(d.placeholder));
  ok('desktop D3 桌面 chip 高度仍 28px（原值钉）', !!f && f.chip.h === 28, JSON.stringify(f && f.chip));
  await page.screenshot({ path: join(SHOTS_DIR, 'desktop-light-mixed.png') });
  await ctx.close();
}

await browser.close();
await new Promise((r) => server.close(r));
const errs = consoleErrors.filter((t) => !t.includes('favicon') && !/Failed to load resource.*404/.test(t));
console.log(`done → ${SHOTS_DIR}`);
console.log(errs.length ? `console errors (${errs.length}):\n` + errs.slice(0, 10).join('\n') : 'console: no errors');
ok('C1 console 零 error', errs.length === 0, errs.slice(0, 3).join(' | '));
console.log(failures === 0 ? 'RESULT: ALL PASS' : `RESULT: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
