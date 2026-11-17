// ui-band-unify-geom.mjs — A/G/H 批（顶底三区带几何 + 毛玻璃 + 底带不遮挡）取证腿。
//
// 用法（node 直跑，零后端 / 零 sbt / 不碰 :6138）：
//   node tests/ui-band-unify-geom.mjs --tag=before|after
// 产物（EVID_DIR，缺省 evidence/20261117_ui-unify-panels/<tag>/）：
//   · 六张正片：chat-m / tasks-m / desk × light|dark（另附顶带特写与 item 页加拍）
//   · geom-<tag>.json：三面 × 两档数值表 + A/G/H 断言结果
// 断言面（本批验收口径，全部 getBoundingClientRect/getComputedStyle 机械读数）：
//   A1 直径：手机 ∈ [36,44] 且 = clamp(36px,10vw,44px)@vw ±0.6；桌面 = 40 ±0.6
//   A2 胶囊高 = 圆钮直径 ±1；A3 带内图标 = 直径 45% ±0.6
//   A4 三区中心线偏差 ≤1px（带内全部可见子件 cy 极差）
//   A5 无动作槽位 = 等宽 invisible spacer（在场、visibility hidden、不可点）
//   G  毛玻璃：α ≥ 0.92 / blur ≥ 20px / 图标↔玻璃底 WCAG 对比度 ≥ 3
//   H  底带：滚到头时末行底缘 ≤ 底带上缘（chat=input-area / tasks=FAB / item=ta-tool）
// 数据面 = tests/_todo-face-harness.mjs 路由桩 + todoStore.__seed 夹具（真实模块图 + 真实 CSS）。

import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootTodoFace, itemFixture, TAG_FIXTURE } from './_todo-face-harness.mjs';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const ARGV = Object.fromEntries(process.argv.slice(2).map((a) => {
  const m = a.match(/^--([^=]+)=(.*)$/); return m ? [m[1], m[2]] : [a, true];
}));
const TAG = ARGV.tag || 'after';
const OUT = ARGV.out || join(ROOT, 'evidence', '20261117_ui-unify-panels', TAG);
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 页内几何读数：带内每个子件矩形/中心线/图标/毛玻璃三数 + 末行避让原始件。 */
const PROBE = () => {
  const rect = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return {
      x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2),
      cx: +((b.x + b.width / 2).toFixed(2)), cy: +((b.y + b.height / 2).toFixed(2)),
    };
  };
  const glass = (el) => {
    if (!el) return null;
    const s = getComputedStyle(el);
    const a = s.backgroundColor.match(/rgba?\(([^)]+)\)/);
    let alpha = null;
    if (a) {
      const parts = a[1].split(',').map((v) => parseFloat(v));
      alpha = parts.length === 4 ? parts[3] : 1;
    }
    const f = s.backdropFilter || s.webkitBackdropFilter || '';
    const blur = f.match(/blur\(([\d.]+)px\)/);
    // WCAG 对比度：前景（图标/文字墨色）↔ 玻璃底（不透明混合后的实底近似：
    // α 混合到页面底 --color-bg 上，量的是「最终可读性」，不是悬浮态错觉）。
    const lum = (rgb) => {
      const [r, g, b] = rgb.map((v) => {
        const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const parse = (str) => (str.match(/rgba?\(([^)]+)\)/) || [])[1]?.split(',').map(Number).slice(0, 3) || null;
    let contrast = null;
    const fg = parse(s.color);
    const bg = parse(s.backgroundColor);
    const pageBg = parse(getComputedStyle(document.body).backgroundColor)
      || parse(getComputedStyle(document.documentElement).backgroundColor);
    if (fg && bg && bg.length === 3) {
      const mixed = alpha != null && alpha < 1 && pageBg
        ? bg.map((c, i) => c * alpha + pageBg[i] * (1 - alpha))
        : bg;
      const l1 = lum(fg); const l2 = lum(mixed);
      contrast = +((Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)).toFixed(2);
    }
    return { alpha, blurPx: blur ? +blur[1] : null, bg: s.backgroundColor, color: s.color, contrast };
  };
  const zone = (el) => {
    if (!el) return null;
    const svg = el.querySelector('svg');
    const sr = svg ? svg.getBoundingClientRect() : null;
    return {
      rect: rect(el), tag: el.tagName.toLowerCase(), cls: String(el.className),
      icon: sr ? { w: +sr.width.toFixed(2), h: +sr.height.toFixed(2) } : null,
      glass: glass(el), radius: getComputedStyle(el).borderRadius,
      interactive: el.tagName === 'BUTTON' || el.getAttribute('role') === 'button',
      visible: !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)
        && getComputedStyle(el).visibility !== 'hidden',
    };
  };
  const band = (rootSel, childSel) => {
    const root = document.querySelector(rootSel);
    if (!root) return null;
    return {
      rootSel, root: rect(root), rootGlass: glass(root),
      children: [...root.querySelectorAll(childSel)].map(zone),
    };
  };
  const bySel = (sels) => sels.map((s) => ({ sel: s, ...zone(document.querySelector(s)) }));
  return {
    viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
    bandD: getComputedStyle(document.body).getPropertyValue('--band-d').trim() || null,
    cvSizeCircle: getComputedStyle(document.documentElement).getPropertyValue('--cv-size-circle').trim() || null,
    chatTopBand: band('#mobile-fab-layer', '.mh-chrome-btn'),
    chatDock: band('#mobile-dock', 'button, a, .dock-item'),
    taCluster: band('.ta-cluster', '.ta-pill, .ta-pill-btn, .ta-slot-spacer, .ta-save, .mobile-back-btn'),
    taTool: band('.ta-tool', '.ta-tool-btn'),
    taPill: zone(document.querySelector('.ta-pill')),
    taFab: zone(document.querySelector('.ta-fab')),
    taSlotSpacer: zone(document.querySelector('.ta-slot-spacer')),
    idhead: zone(document.querySelector('.mh-idhead')),
    desktopPanelFab: zone(document.querySelector('.sp-panel-header > .g2-fab')),
    // C 批（ui-unify-panels 2026-11-17）「+」等控制按钮单源：待办加号钮读数
    //（直径/圆角/描边/图标占比；--glass-border 期望值用同 var 探针对照）。
    tflFab: (() => {
      const el = document.querySelector('.tfl-fab-add');
      if (!el) return null;
      const s = getComputedStyle(el);
      const svg = el.querySelector('svg');
      const sr = svg ? svg.getBoundingClientRect() : null;
      const p = document.createElement('span');
      p.style.border = '1px solid var(--glass-border)';
      document.body.appendChild(p);
      const wantBorder = getComputedStyle(p).borderColor;
      p.remove();
      return {
        rect: rect(el), cls: String(el.className),
        radius: s.borderRadius, borderWidth: s.borderWidth, borderColor: s.borderColor, wantBorder,
        icon: sr ? { w: +sr.width.toFixed(2), h: +sr.height.toFixed(2) } : null,
        visible: !!(el.offsetWidth || el.offsetHeight) && s.visibility !== 'hidden',
      };
    })(),
    misc: bySel(['#input-bar', '#input-area', '#agent-avatar-btn']),
  };
};

/** H 探针：滚到头后，正文末行底缘 vs 底带上缘（chat / tasks / item 三腿）。 */
const OVERLAP_PROBE = () => {
  const r = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { top: +b.top.toFixed(2), bottom: +b.bottom.toFixed(2) };
  };
  const livePage = document.querySelector('.mobile-page.live');
  const lastVisible = (sel, root) => {
    const rows = [...(root || document).querySelectorAll(sel)].filter((e) => e.offsetParent);
    return rows.length ? r(rows[rows.length - 1]) : null;
  };
  return {
    chat: {
      inputArea: r(document.querySelector('#input-area')),
      lastRow: lastVisible('#chat .row'),
      chatScroll: (() => { const c = document.querySelector('#chat'); return c ? { sh: c.scrollHeight, ch: c.clientHeight } : null; })(),
    },
    tasks: {
      fab: r(livePage?.querySelector('.ta-fab')),
      lastRow: lastVisible('.ta-row, .ta-listcard .ta-row-tag', livePage),
      scroller: (() => { const c = livePage?.querySelector('.mobile-page-body') || livePage; return c ? { sh: c.scrollHeight, ch: c.clientHeight } : null; })(),
    },
    item: {
      tool: r(livePage?.querySelector('.ta-tool')),
      lastField: (() => {
        if (!livePage) return null;
        const els = [...livePage.querySelectorAll('.ta-note, textarea, input, .ta-field, .ta-edit, .ta-body button, .ta-body .ta-item, .ta-body > *')];
        const vis = els.filter((e) => e.offsetParent);
        return vis.length ? r(vis[vis.length - 1]) : null;
      })(),
      scroller: (() => { const c = livePage?.querySelector('.mobile-page-body'); return c ? { sh: c.scrollHeight, ch: c.clientHeight } : null; })(),
    },
  };
};

const browser = await chromium.launch();
const results = {};
const asserts = [];

function check(id, ok, detail) {
  asserts.push({ id, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id} — ${detail}`);
}

/** A 断言：一组带件（圆钮/胶囊）直径、图标比、中心线。胶囊（宽≠高件）只进
 *  中心线与 A2（高=直径），不进 A1 直径断言。 */
function assertBand(face, bandObj, { expectD }) {
  if (!bandObj || !bandObj.children?.length) return;
  const kids = bandObj.children.filter((c) => c && c.visible && c.rect && c.rect.w > 1 && c.rect.h > 1);
  if (!kids.length) return;
  const cys = kids.map((k) => k.rect.cy);
  const spread = Math.max(...cys) - Math.min(...cys);
  check(`A4 ${face} 中心线极差 ≤1px`, spread <= 1, `spread=${spread.toFixed(2)} cy=[${cys.map((c) => c.toFixed(2)).join(',')}]`);
  for (const k of kids) {
    const isCapsule = /\bta-pill\b/.test(k.cls) && !/ta-pill-btn/.test(k.cls);
    if (!isCapsule) {
      const d = k.rect.w;
      check(`A1 ${face} 直径 ${k.cls.split(' ')[0]} = ${expectD}`, Math.abs(d - expectD) <= 0.6 && Math.abs(k.rect.h - d) <= 0.6, `${k.rect.w}×${k.rect.h}`);
    }
    if (k.icon) {
      check(`A3 ${face} 图标 = 45%×${expectD}`, Math.abs(k.icon.w - expectD * 0.45) <= 0.6, `icon=${k.icon.w}`);
    }
    const g = k.glass || {};
    if (g.alpha != null && k.cls.includes('g2-fab')) {
      check(`G ${face} ${k.cls.split(' ')[0]} α≥0.92 blur≥20 对比度≥3`,
        g.alpha >= 0.92 && (g.blurPx || 0) >= 20 && (g.contrast || 0) >= 3,
        `α=${g.alpha} blur=${g.blurPx} contrast=${g.contrast}`);
    }
  }
}

async function leg(name, { w, h, mobile, scheme, nav }) {
  const ctx = await browser.newContext({
    viewport: { width: w, height: h },
    deviceScaleFactor: mobile ? 3 : 2,
    isMobile: mobile, hasTouch: mobile,
    colorScheme: scheme,
  });
  const page = await ctx.newPage();
  try {
    if (!nav) {
      // chat 腿：_chat-face-harness 的 WS 桩原生带长历史（30 条 ⇒ 滚动列 + 末行可量）。
      await bootChat(page, { history: historyFixture(30) });
    } else {
      await bootTodoFace(page, { tags: TAG_FIXTURE, items: itemFixture(), mobile });
    }
    let itemLeg = false;
    if (nav === 'tasks') {
      await page.evaluate(async () => {
        const nav = await import('/js/mobile/navController.js');
        nav.pushPage('tasks');
      });
      await page.waitForSelector('.ta-cluster', { state: 'attached', timeout: 12000 });
      await sleep(1200);
    }
    if (nav === 'item') {
      await page.evaluate(async () => {
        const nav = await import('/js/mobile/navController.js');
        nav.pushPage('tasks');
      });
      await page.waitForSelector('.ta-row-all', { state: 'attached', timeout: 12000 });
      await page.click('.mobile-page[data-page="tasks"] .ta-row-all');
      await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
      await page.click('.mobile-page[data-page="todo-apple-list"] .ta-item-body');
      await page.waitForSelector('.mobile-page[data-page="todo-apple-item"].live', { timeout: 10000 });
      await sleep(900);
      itemLeg = true;
    }
    await sleep(400);
    const expectD = mobile ? Math.min(44, Math.max(36, w * 0.1)) : 40;
    const probe = await page.evaluate(PROBE);
    results[name] = probe;

    // H：滚到头再读末行 vs 底带（live 页的滚动面优先，逐个滚到底）。
    await page.evaluate(() => {
      for (const s of ['.mobile-page.live .mobile-page-body', '.mobile-page.live', '#chat', '.ta-root']) {
        for (const el of document.querySelectorAll(s)) { el.scrollTop = el.scrollHeight; }
      }
    });
    await sleep(350);
    results[name].overlap = await page.evaluate(OVERLAP_PROBE);

    // ── 断言 ──
    if (probe.chatTopBand?.children?.some((c) => c && c.visible)) {
      assertBand(`${name}:chat顶带`, probe.chatTopBand, { expectD });
    }
    if (probe.taCluster?.children?.some((c) => c && c.visible)) {
      assertBand(`${name}:ta顶带`, probe.taCluster, { expectD });
      const pill = probe.taPill;
      if (pill && pill.rect) {
        check(`A2 ${name} 胶囊高 = 圆钮直径`, Math.abs(pill.rect.h - expectD) <= 1, `pillH=${pill.rect.h} expect=${expectD}`);
      }
    }
    if (probe.taFab && probe.taFab.rect) {
      check(`A1 ${name} FAB 直径`, Math.abs(probe.taFab.rect.w - expectD) <= 0.6, `${probe.taFab.rect.w}`);
    }
    if (probe.desktopPanelFab && probe.desktopPanelFab.rect && probe.desktopPanelFab.rect.w > 1) {
      check(`A1 ${name} 桌面面板头圆钮 = 40`, Math.abs(probe.desktopPanelFab.rect.w - 40) <= 0.6, `${probe.desktopPanelFab.rect.w}`);
    }
    if (!mobile && probe.cvSizeCircle) {
      // 桌面档单源读数：--cv-size-circle 必须仍是 40px（桌面带族消费的同一把尺）。
      check(`A1 ${name} 桌面直径 token --cv-size-circle = 40px`, /40px/.test(probe.cvSizeCircle), probe.cvSizeCircle);
    }
    if (probe.taSlotSpacer) {
      check(`A5 ${name} 左槽 spacer 在场/隐形/不可点`,
        probe.taSlotSpacer.rect && !probe.taSlotSpacer.visible && !probe.taSlotSpacer.interactive,
        JSON.stringify(probe.taSlotSpacer));
    }
    // C 批断言：待办「+」控制钮 = 一把尺（直径档 / 50% 正圆 / 1px --glass-border
    // 描边 / 图标占比 45%）。手机档经 .g2-fab 吃 --band-d，桌面吃 --cv-size-circle-lg。
    if (probe.tflFab && probe.tflFab.rect && probe.tflFab.visible) {
      const f = probe.tflFab;
      const d = f.rect.w;
      check(`C1 ${name} 待办「+」直径 = 档（mobile --band-d / desk --cv-size-circle-lg）`,
        Math.abs(d - expectD) <= 1 && Math.abs(f.rect.h - expectD) <= 1,
        `w=${f.rect.w} h=${f.rect.h} expect=${expectD.toFixed(1)} cls=${f.cls}`);
      check(`C2 ${name} 待办「+」圆角 50% + 描边 1px --glass-border`,
        f.radius === '50%' && parseFloat(f.borderWidth) === 1 && f.borderColor === f.wantBorder,
        `r=${f.radius} bw=${f.borderWidth} bc=${f.borderColor} want=${f.wantBorder}`);
      check(`C3 ${name} 待办「+」图标占比 45%`,
        !!f.icon && Math.abs(f.icon.w / d - 0.45) <= 0.02,
        f.icon ? `icon=${f.icon.w} d=${d} ratio=${(f.icon.w / d).toFixed(3)}` : 'no-icon');
    }
    // H 断言
    const ov = results[name].overlap || {};
    if (ov.chat?.lastRow && ov.chat?.inputArea) {
      check(`H ${name} chat 末行不进输入区`, ov.chat.lastRow.bottom <= ov.chat.inputArea.top + 0.5,
        `rowBottom=${ov.chat.lastRow.bottom} inputTop=${ov.chat.inputArea.top}`);
    }
    if (ov.tasks?.lastRow && ov.tasks?.fab) {
      check(`H ${name} tasks 末行不进 FAB 带`, ov.tasks.lastRow.bottom <= ov.tasks.fab.top + 0.5,
        `rowBottom=${ov.tasks.lastRow.bottom} fabTop=${ov.tasks.fab.top}`);
    }
    if (itemLeg && ov.item?.lastField && ov.item?.tool) {
      check(`H ${name} item 末字段不进工具带`, ov.item.lastField.bottom <= ov.item.tool.top + 0.5,
        `fieldBottom=${ov.item.lastField.bottom} toolTop=${ov.item.tool.top}`);
    }

    await page.screenshot({ path: join(OUT, `${name}-${scheme}.png`) });
    if (mobile) {
      await page.screenshot({ path: join(OUT, `${name}-${scheme}-top.png`), clip: { x: 0, y: 0, width: w, height: 130 } });
    }
    console.log(`SHOT ${name}-${scheme}`);
  } catch (e) {
    results[name] = { error: e.message.split('\n')[0] };
    console.error(`FAIL ${name}-${scheme}: ${e.message.split('\n')[0]}`);
    process.exitCode = 1;
  } finally {
    await ctx.close();
  }
}

await leg('chat-m', { w: 375, h: 812, mobile: true, scheme: 'light' });
await leg('chat-m', { w: 375, h: 812, mobile: true, scheme: 'dark' });
await leg('tasks-m', { w: 375, h: 812, mobile: true, scheme: 'light', nav: 'tasks' });
await leg('tasks-m', { w: 375, h: 812, mobile: true, scheme: 'dark', nav: 'tasks' });
await leg('desk', { w: 1440, h: 900, mobile: false, scheme: 'light' });
await leg('desk', { w: 1440, h: 900, mobile: false, scheme: 'dark' });
await leg('item-m', { w: 375, h: 812, mobile: true, scheme: 'light', nav: 'item' });
// C 批桌面证据位 = desk 腿（侧栏待办胶囊展开态里 .tfl-fab-add 可见，C1–C3 直接
// 断言）；手机「+」= ta-fab / reminder-fab-add（.g2-fab 族），A1 直径断言已覆盖。

const failed = asserts.filter((a) => !a.ok).length;
writeFileSync(join(OUT, `geom-${TAG}.json`), JSON.stringify({ results, asserts }, null, 2));
writeFileSync(join(OUT, `asserts-${TAG}.json`), JSON.stringify(asserts, null, 2));
await browser.close();
console.log(`WROTE ${join(OUT, `geom-${TAG}.json`)}`);
console.log(`ASSERTS ${asserts.length} failed=${failed}`);
if (failed) process.exitCode = 1;
