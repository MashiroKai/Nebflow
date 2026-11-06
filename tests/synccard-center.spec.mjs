// synccard-center.spec.mjs — 同步卡状态文字居中（作者令 2026-11-02 09:19 ②「把
// 这个同步卡片的下面的状态 居中」）的验收网。
//
// 现场事实（本批改前实测，见 docs/20261102_stackcard-ref-and-synccard-center__*§1）：
//   · 卡顶部的动态状态标题 .nf-entry-title 本来就居中（文字盒中心 719.99 vs 卡中
//     线 720，Δ0.008px）——它不在本批改动面内；
//   · 作者所指「下面的状态」= 标题下方那四行真实加载项清单（.nf-entry-items /
//     .nf-entry-item，行基样式 .nfc-steps/.nfc-step），改前 `text-align:left` + 行
//     左对齐 ⇒ 文字盒中心贴在行首。
//
// 契约钉（本 spec，机械判据）：
//   S1  三态（done / active / pending）各一行：**状态文字自身**的水平中心 ↔ 卡
//       内容中线 ≤1px（文字盒 = Range.getClientRects() 的第一个矩形，不是行盒）；
//   S2  状态点不被居中断掉：点的左缘仍在文字左侧、点在行内垂直居中（点中心 ↔
//       文字盒中心 ≤1px），三态几何勾/叉不改变行高（行高与 14px 正文档一致）；
//   S3  垂直间距不被本批改动顶偏：清单块距标题 = --nfc-gap-title（16px）原值、
//       行间 gap = 8px；
//   S4  同类面点名核对：失败卡 .nf-boot-card（.nf-boot-card-title/hint）与接管
//       弹窗 .nfc-body —— 标题/正文的水平中心同样对齐卡中线（≤1px），无需改动则
//       作为回归钉留存；
//   S5  双端同一份 DOM：手机 375×812 与桌面 1440×900 各验一次（无移动层覆写）；
//   S6  亮/暗两档样张存证 + 零 console error。
//
// Run: node node_modules/@playwright/test/cli.js test tests/synccard-center.spec.mjs
import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
// 真页面 = 仓内 web 树（与 entry-veil.spec 同一条静态腿；根路径没有 index.html）。
const APP_PATH = '/src/main/resources/web/index.html';
const SHOTS = path.join(REPO_ROOT, 'evidence', '20261102_stackcard-ref-and-synccard-center', 'after');

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], { stdio: 'ignore' });
}

async function waitUntilUp(url, tries = 400) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${APP_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

/* 入场卡真身：把门脚本的四项清单按三态摆一遍，再量几何。**用真页面真样式**
   （cards.css + 门内联块），只跳过网络腿 —— 三态是 DOM 状态，不是数据依赖。 */
async function openEntryCard(browser, { width, height, colorScheme }) {
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  // 零异常判据分两层（静态资源腿没有后端，404 是环境噪声不是本批缺陷）：
  //   · pageerror（未捕获 JS 异常）必须为空 —— 真回归面；
  //   · console error 只允许**环境噪声**（静态服务器没有路由/后端：404 资源、
  //     ws 握手 404、Monaco 编辑器加载失败）—— 与本批样式改动无关。
  const errors = [];
  const NOISE = /Failed to load resource|WebSocket connection|handshake|Monaco Editor|Monaco|404 \(File not found\)|status of 404|sw\.js|service.?worker|manifest|bad HTTP response code/i;
  page.on('pageerror', e => {
    const txt = 'PAGEERROR ' + String(e);
    if (NOISE.test(txt)) return;
    errors.push(txt);
  });
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const txt = m.text();
    if (NOISE.test(txt)) return;
    errors.push(txt);
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  // ws 腿挡住（无 upgrade ⇒ 握手被拒 ⇒ 入场卡立即让位）：本 spec 不看网络进度，
  // 只要「卡 + 四行清单」的真实 DOM 与样式，先让卡面在 DOMContentLoaded 后建起来。
  await page.goto(`http://127.0.0.1:${port}${APP_PATH}`);
  await page.waitForSelector('#nf-boot-gate .nf-entry-item', { timeout: 20000 });
  // 摆三态 + 把脸钉在「入场中」：门脚本在握手被拒的 fail 腿里 reveal(true)，260ms
  // 后 `face.style.display='none'`。为了让几何可量，页内挂一个 40ms 的守卫把
  // 三态与 data-entry=on 反复摆回（幂等、零副作用：只改 DOM 状态，不改布局）。
  await page.evaluate(() => {
    const pin = () => {
      const rows = Array.from(document.querySelectorAll('#nf-boot-gate .nf-entry-item'));
      const st = ['done', 'active', 'pending', 'fail'];
      rows.forEach((r, i) => {
        if (r.getAttribute('data-state') !== st[i % st.length]) r.setAttribute('data-state', st[i % st.length]);
      });
      const face = document.getElementById('nf-boot-gate');
      if (face) {
        if (face.style.display === 'none') face.style.display = '';
        if (face.getAttribute('data-entry') !== 'on') face.setAttribute('data-entry', 'on');
        // 门脚本的 fail 腿会置 data-state="failed"，而
        // `#nf-boot-gate[data-state="failed"] .nf-entry-card { display:none }`
        // 正是「文字盒不可量（Range 无矩形）」的成因 —— 守卫必须把它摘掉，
        // 否则卡面在量测窗里被换成了失败卡。
        if (face.hasAttribute('data-state')) face.removeAttribute('data-state');
      }
      const root = document.documentElement;
      // 门脚本在「全 ok」腿里会摘掉 html.nf-boot-gate（面随之 display:none；
      // `html.nf-boot-gate body{visibility:hidden}` 同理）。钉住类名 + 撤揭幕。
      if (!root.classList.contains('nf-boot-gate')) root.classList.add('nf-boot-gate');
      root.classList.remove('nf-boot-reveal');
    };
    pin();
    window.__pinEntry = setInterval(pin, 40);
  });
  const card = page.locator('#nf-boot-gate .nf-entry-card');
  let visible = false;
  for (let i = 0; i < 60; i++) {
    if (await card.isVisible().catch(() => false)) { visible = true; break; }
    await page.waitForTimeout(50);
  }
  expect(visible, '入场卡面可见（守卫按 data-entry=on 摆回）').toBe(true);
  await page.waitForTimeout(150); // 行色 transition 0.2s 落定
  return { ctx, page, errors };
}

/** 量：卡内容中线 vs 每行**文字盒**中心（Range 矩形，不是行盒）；点的位置；
 *  垂直间距；同类面（失败卡标题/正文、接管弹窗正文）。 */
async function measure(page) {
  return page.evaluate(() => {
    const card = document.querySelector('#nf-boot-gate .nf-entry-card');
    const cr = card.getBoundingClientRect();
    // 卡内容中线 = 卡盒中线（左右 padding 对称 32px ⇒ 内容盒中线同值）
    const cardCx = cr.x + cr.width / 2;
    const textBox = (el) => {
      const rg = document.createRange();
      rg.selectNodeContents(el);
      const rects = Array.from(rg.getClientRects()).filter(r => r.width > 0);
      if (!rects.length) return null;
      const r = rects[0];
      return { x: r.x, w: r.width, cx: r.x + r.width / 2, top: r.top, bottom: r.bottom, h: r.height };
    };
    const title = document.querySelector('#nf-boot-gate .nf-entry-title');
    const items = document.querySelector('#nf-boot-gate .nf-entry-items');
    const rows = Array.from(document.querySelectorAll('#nf-boot-gate .nf-entry-item'));
    const rowData = rows.map((row) => {
      const label = row.querySelector('.nf-entry-label');
      const dot = row.querySelector('.nf-entry-dot');
      const tb = textBox(label);
      const dr = dot.getBoundingClientRect();
      const rr = row.getBoundingClientRect();
      return {
        state: row.getAttribute('data-state'),
        text: label ? label.textContent : '',
        textBox: tb,
        dot: { cx: dr.x + dr.width / 2, cy: dr.y + dr.height / 2, w: dr.width, h: dr.height, x: dr.x },
        rowH: rr.height,
        rowCx: rr.x + rr.width / 2,
        lineHeight: getComputedStyle(row).lineHeight,
        fontSize: getComputedStyle(row).fontSize,
        align: getComputedStyle(row).justifyContent,
      };
    });
    const tRect = title.getBoundingClientRect();
    const iRect = items.getBoundingClientRect();
    const rowGaps = [];
    for (let i = 1; i < rows.length; i++) {
      rowGaps.push(Math.round((rows[i].getBoundingClientRect().top - rows[i - 1].getBoundingClientRect().bottom) * 100) / 100);
    }
    return {
      card: { x: cr.x, w: cr.width, cx: cardCx },
      title: { ...textBox(title), rectCx: tRect.x + tRect.width / 2 },
      itemsCx: iRect.x + iRect.width / 2,
      gapTitleToItems: Math.round((iRect.top - tRect.bottom) * 100) / 100,
      rowGaps,
      rows: rowData,
      stepsAlign: getComputedStyle(items).textAlign,
      cardAlign: getComputedStyle(card).alignItems,
      cardTextAlign: getComputedStyle(card).textAlign,
    };
  });
}

/** 同类面：失败卡（内联样式）+ 接管弹窗（.nfc 家族）——标题/正文中心 vs 卡中线。 */
async function measureKin(page) {
  return page.evaluate(() => {
    const probe = (rootSel, innerSel) => {
      const root = document.querySelector(rootSel);
      const inner = root && root.querySelector(innerSel);
      if (!root || !inner) return null;
      const rr = root.getBoundingClientRect();
      const rg = document.createRange();
      rg.selectNodeContents(inner);
      const rects = Array.from(rg.getClientRects()).filter(r => r.width > 0);
      if (!rects.length) return null;
      const r = rects[0];
      return {
        cardCx: rr.x + rr.width / 2,
        textCx: r.x + r.width / 2,
        delta: Math.abs(rr.x + rr.width / 2 - (r.x + r.width / 2)),
        align: getComputedStyle(root).textAlign,
        innerAlign: getComputedStyle(inner).textAlign,
      };
    };
    return {
      failTitle: probe('#nf-boot-gate .nf-boot-card', '.nf-boot-card-title'),
      failHint: probe('#nf-boot-gate .nf-boot-card', '.nf-boot-card-hint'),
      takeoverBody: probe('#takeover-box', '#takeover-msg'),
      takeoverTitle: probe('#takeover-box', '#takeover-title'),
    };
  });
}

const CENTER_TOL = 1; // px（作者令：≤1px）

for (const vp of [{ name: 'mobile', width: 375, height: 812 }, { name: 'desktop', width: 1440, height: 900 }]) {
  for (const scheme of ['light', 'dark']) {
    test(`S1-S6 同步卡状态居中 @ ${vp.name}/${scheme}`, async ({ browser }) => {
      const { ctx, page, errors } = await openEntryCard(browser, { width: vp.width, height: vp.height, colorScheme: scheme });
      const m = await measure(page);

      // S1 三态逐行：文字盒中心 = 卡内容中线（≤1px）
      for (const row of m.rows) {
        expect(row.textBox, `${row.state} 行文字盒可量`).toBeTruthy();
        const delta = Math.abs(row.textBox.cx - m.card.cx);
        expect(delta, `${row.state} 行「${row.text}」文字中心 ${row.textBox.cx.toFixed(2)} vs 卡中线 ${m.card.cx.toFixed(2)} (Δ${delta.toFixed(3)}px)`)
          .toBeLessThanOrEqual(CENTER_TOL);
      }
      // S2 点仍贴文字左侧 + 行内垂直居中；行高 = 14px 正文 1.5 行高（21px）
      for (const row of m.rows) {
        expect(row.dot.x, `${row.state} 点在文字左侧`).toBeLessThan(row.textBox.x);
        const dy = Math.abs(row.dot.cy - (row.textBox.top + row.textBox.bottom) / 2);
        expect(dy, `${row.state} 点垂直居中 (Δ${dy.toFixed(2)}px)`).toBeLessThanOrEqual(CENTER_TOL);
        expect(row.rowH, `${row.state} 行高 21px（正文档，勾/叉不顶高）`).toBeCloseTo(21, 1);
        expect(row.fontSize).toBe('14px');
        expect(row.lineHeight).toBe('21px');
      }
      // 行内点 + 文案的对称留白 ⇒ 文案盒真的居中（不是靠 text-align 蒙对）
      expect(m.rows.every(r => r.align === 'center'), '行内 flex 居中').toBe(true);
      // S3 垂直间距：清单块距标题 16px（--nfc-gap-title），行间 8px
      expect(m.gapTitleToItems, `清单距标题 ${m.gapTitleToItems}px`).toBeCloseTo(16, 0);
      for (const g of m.rowGaps) expect(g, `行间 ${g}px`).toBeCloseTo(8, 0);
      // 标题本来居中（回归钉）
      expect(Math.abs(m.title.cx - m.card.cx), '标题文字中心 vs 卡中线').toBeLessThanOrEqual(CENTER_TOL);
      // S4 同类面
      const kin = await measureKin(page);
      for (const [k, v] of Object.entries(kin)) {
        if (!v) continue;
        expect(v.delta, `${k} 文字中心 vs 卡中线 (Δ${v.delta.toFixed(2)}px)`).toBeLessThanOrEqual(CENTER_TOL);
      }
      // S6 零 console error + 样张
      expect(errors, errors.join(' | ')).toEqual([]);
      // 样张：卡盒矩形**页内取**（+scroll 偏移）再 clip。不用 locator.boundingBox()
      // —— 它在动画/守卫改写 display 的同一帧会返回 null；也不用元素截图——本卡是
      // position:fixed 面，元素截图路径会受 fixed 坐标换算影响。clip 用页面坐标。
      await page.waitForTimeout(200);
      const rect = await page.evaluate(() => {
        const r = document.querySelector('#nf-boot-gate .nf-entry-card').getBoundingClientRect();
        return { x: r.x + window.scrollX, y: r.y + window.scrollY, width: r.width, height: r.height };
      });
      await page.screenshot({ path: path.join(SHOTS, `synccard-center-${vp.name}-${scheme}.png`), clip: rect });
      await ctx.close();
    });
  }
}
