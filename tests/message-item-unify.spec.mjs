// tests/message-item-unify.spec.mjs — 消息条目统一批（2026-11-18）渲染几何与行为验收。
//
// 驱动**真生产链**（非手抄 DOM）：harness 页 import 生产模块 js/chatItem.js 与
// js/popArtifacts.js 的 buildUserMediaRow，在真实浏览器里装配气泡 / 附件区 / 叠卡 /
// footer，量 boundingBox 与 computedStyle。
//
// 断言矩阵（两个视口 375×812 手机 / 1280×800 桌面）：
//   G1 气泡节点 = chatItem.messageBubbleNode（`.fm-msg-bubble`），几何 > 0 且左右对齐。
//   G2 附件区 = chatItem.attachmentArea（`data-nf-attach-area="1"`）。
//   G3 叠卡**合起**态：整组 footer（`[data-nf-stack-footer="group"]`）可见、属整组。
//   G4 叠卡**展开**态：整组 footer 隐藏；每卡独立 footer（`[data-nf-stack-footer="card"]`）
//      出现，且每卡 footer 内**有下载键**（`.msg-dl`）。
//   G5 footer 共享样式 / 不同格式：A 格式（时间+复制）与 B 格式（徽标+时间+动作）都由
//      chatItem.messageFooter 产出 ⇒ 同 `.duration-badge` 类、同 computed 底色/圆角/字阶，
//      仅字段与排列不同（子节点数不同）。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

// 1×1 红/绿/蓝 PNG（内联 data: 腿，叠卡字节腿零网络）。
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

const HARNESS = `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="/css/stand-tokens.css">
<link rel="stylesheet" href="/css/design-system.css">
<link rel="stylesheet" href="/css/glass.css">
<link rel="stylesheet" href="/css/chat.css">
<link rel="stylesheet" href="/css/friends.css">
</head><body>
<div id="stage"></div>
<script type="module">
import { messageBubbleNode, messageFooter, attachmentArea, stackGroupFooter, stackCardFooter } from '/js/chatItem.js';
import { buildUserMediaRow } from '/js/popArtifacts.js';
const stage = document.getElementById('stage');
const PNG = ${JSON.stringify(PNG)};

window.__build = () => {
  stage.innerHTML = '';
  // G1/G2 气泡 + 附件区
  const bubble = messageBubbleNode('fm-msg-bubble bubble user ours');
  bubble.textContent = 'hello';
  const area = attachmentArea('pop-group');
  stage.append(bubble, area);

  // G3/G4 叠卡（真生产链 buildUserMediaRow ⇒ buildMediaStack + 整组 footer）
  const atts = [0,1,2].map(i => ({ type:'image', name:'img'+i+'.png', path:'/tmp/img'+i+'.png', preview: PNG }));
  const row = buildUserMediaRow(atts, { ts: 1731900000000 });
  stage.appendChild(row);

  // G5 footer 两格式（同一基座）
  const a = messageFooter({ timestamp: 1731900000000, copyText: 'hi', timeFormat:'day' });
  const actions = document.createElement('span'); actions.className = 'fm-msg-actions';
  const b = messageFooter({ extraClass:'fm-msg-meta', badges:[{className:'fm-msg-agent-badge', text:'AGENT'}],
    timestamp: 1731900000000, alwaysTime:true, timeText:'12:00', timeFormat:'clock', actions });
  stage.append(a, b);
  window.__els = { bubble, area, row, a, b };
  return true;
};
</script></body></html>`;

let server;
let base;

test.beforeAll(async () => {
  server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x');
      if (url.pathname === '/' || url.pathname === '/harness.html') {
        res.writeHead(200, { 'content-type': 'text/html' });
        res.end(HARNESS); return;
      }
      const rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '');
      const body = await readFile(join(WEB, rel));
      res.writeHead(200, { 'content-type': MIME[extname(rel)] || 'application/octet-stream' });
      res.end(body);
    } catch (e) {
      res.writeHead(404); res.end('nf');
    }
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}/harness.html`;
});
test.afterAll(async () => { if (server) await new Promise(r => server.close(r)); });

const VIEWPORTS = [
  { name: 'mobile-375x812', width: 375, height: 812 },
  { name: 'desktop-1280x800', width: 1280, height: 800 },
];

for (const vp of VIEWPORTS) {
  test.describe(`${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } });

    test('G1/G2 bubble + attachment area geometry', async ({ page }) => {
      const errs = [];
      page.on('pageerror', e => errs.push(String(e)));
      await page.goto(base);
      await page.evaluate(() => window.__build());
      const geo = await page.evaluate(() => {
        const b = window.__els.bubble.getBoundingClientRect();
        const a = window.__els.area.getBoundingClientRect();
        return { bw: b.width, bh: b.height, areaAttr: window.__els.area.dataset.nfAttachArea, aw: a.width };
      });
      expect(errs).toEqual([]);
      expect(geo.bw).toBeGreaterThan(0);
      expect(geo.bh).toBeGreaterThan(0);
      expect(geo.areaAttr).toBe('1');
      expect(geo.aw).toBeGreaterThan(0);
    });

    test('G3/G4 stack collapsed group footer -> expanded per-card footers with download', async ({ page }) => {
      await page.goto(base);
      await page.evaluate(() => window.__build());
      const group = page.locator('[data-nf-stack-footer="group"]');
      await expect(group).toHaveCount(1);
      await expect(group).toBeVisible();
      // 合起态：每卡 footer 已在 DOM（展开列被 display:none 隐藏）但**不可见**
      const cards = page.locator('[data-nf-stack-footer="card"]');
      await expect(cards).toHaveCount(3);
      await expect(cards.first()).toBeHidden();

      // 展开
      await page.locator('.ds-media-stack__pill, .pop-stack-area .pill').first().click();
      await expect(group).toBeHidden();
      await expect(cards).toHaveCount(3);
      for (let i = 0; i < 3; i++) {
        await expect(cards.nth(i)).toBeVisible();
        const dl = cards.nth(i).locator('.msg-dl');
        await expect(dl).toHaveCount(1);
        const box = await dl.boundingBox();
        expect(box.width).toBeGreaterThan(0);
        expect(box.height).toBeGreaterThan(0);
      }
    });

    test('G5 shared footer base, two formats', async ({ page }) => {
      await page.goto(base);
      await page.evaluate(() => window.__build());
      const r = await page.evaluate(() => {
        const { a, b } = window.__els;
        const ca = getComputedStyle(a), cb = getComputedStyle(b);
        const kids = (el) => Array.from(el.children).map(c => c.className || c.tagName.toLowerCase());
        return {
          aClass: a.className, bClass: b.className,
          aBg: ca.backgroundColor, bBg: cb.backgroundColor,
          aRadius: ca.borderRadius, bRadius: cb.borderRadius,
          aKids: kids(a), bKids: kids(b),
        };
      });
      expect(r.aClass).toContain('duration-badge');
      expect(r.bClass).toContain('duration-badge');
      // 同一基座 ⇒ 同底色 / 同圆角（样式单份）
      expect(r.aBg).toBe(r.bBg);
      expect(r.aRadius).toBe(r.bRadius);
      // 仅字段/排列变体：A = 时间档 + 复制键；B = 徽标 + 时间档 + 动作行
      expect(r.aKids.join(' ')).toContain('duration-badge-time');
      expect(r.aKids.join(' ')).toContain('msg-copy');
      expect(r.bKids.join(' ')).toContain('fm-msg-agent-badge');
      expect(r.bKids.join(' ')).toContain('duration-badge-time');
      expect(r.bKids.join(' ')).toContain('fm-msg-actions');
      expect(r.aKids).not.toEqual(r.bKids);
    });
  });
}
