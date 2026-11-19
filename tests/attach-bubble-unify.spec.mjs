// attach-bubble-unify.spec.mjs — 用户发送附件与气泡设计语言统一 (2026-10-18
// author ruling 「我发的图片/文件附件卡统一到用户消息气泡的同套设计语言——同圆角/
// 同底色/同对齐/同宽度约束，微信式：附件是气泡的一部分而非独立异形卡」).
//
// 2026-10-22 修订（作者令「聊天输入两缺陷批」）：**文件附件卡**脱离气泡语言，改入
// 统一 chip 族（与 AskUser 附件卡/Pop 文件卡同族：中性灰玻璃底 + 细边框 + 10px
// 圆角 + 28px 图标档；修前「气泡绿底无边框大卡 = 大绿块」形态退役）。图片/视频
// 媒体面**不受本批影响**，继续钉气泡剪影（U1/U2 的 flat/stack 腿原样保留）。
//
// What the ruling pins (each assertion below is one of these, binary):
//   U1 同圆角 — media faces (flat image/video, stack layers) carry the USER
//        BUBBLE silhouette: border-radius 16px + the 4px bottom-right tail.
//   U2 同底色 — the same media faces back on var(--color-bubble-user); the
//        media element itself goes transparent so the bubble color carries.
//        🔴 2026-10-22 起 **仅媒体面**：file card 不再落 bubble-user（见 F1/F2）。
//   F1 chip 族 — the user file card rides the unified chip family: neutral
//        glass backing (AskUser 同款 --color-surface-2 面)、fine etched border、
//        10px radius、28px icon tier、max-width 264px（紧凑，非满宽）。
//   F2 零气泡绿 — the file card's backing is NOT the bubble green in EITHER
//        theme (computed-style, both colorSchemes).
//   U3 同对齐 — unchanged (attach-side-align 2026-10-06 already pins the user
//        row to .row.user's flex-end side); re-asserted here as the regression
//        guard: the media row still follows the user row, right-flush.
//   U4 同宽度约束 — .row.user-media .pop-group caps at 80% (the .bubble cap),
//        not the artifact family's 78%.
//   U5 去异形卡 chrome — no standing border / drop shadow on the user MEDIA
//        faces (bubbles carry none); hover keeps the artifact family's shadow
//        feedback as the click affordance.
//   A0 agent 不动 — the LLM Pop faces (.row.pop-artifact WITHOUT .user) keep
//        their verbatim design: radius 14px/12px, #17191f / surface backing,
//        standing shadows, dark-theme #101318 override. Asserted by COMPUTED
//        style in both themes, not by grep alone.
//
// The spec drives the REAL render modules through
// tests/fixtures/pop-artifacts/harness.html (same self-spawned static server
// as pop-media-alignment.spec.mjs — never the host instance). CSS text is
// asserted against the real src/main/resources/web/css/chat.css.
//
// Run: node node_modules/@playwright/test/cli.js test tests/attach-bubble-unify.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';
const GREEN = 'rgb(149, 236, 105)'; // --color-bubble-user, light AND dark

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
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 60) {
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
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

async function newPage(browser, colorScheme = 'light') {
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, colorScheme });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return { context, page, pageErrors };
}

/** Pull the paint-relevant computed styles of one element — INLINED into the
 *  browser context by every evaluate below (closure helpers don't survive the
 *  context boundary). */
const readFacesInPage = (sels) => {
  const faceRead = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return {
      borderRadius: cs.borderTopLeftRadius,
      tailRadius: cs.borderBottomRightRadius,
      bg: cs.backgroundColor,
      shadow: cs.boxShadow,
      borderStyle: cs.borderLeftStyle,
    };
  };
  const out = {};
  for (const [key, sel] of Object.entries(sels)) out[key] = faceRead(sel);
  return out;
};

/* ═══════════ CSS source — the scoped bubble-language block exists ═══════════ */

test.describe('CSS source assertions (chat.css)', () => {
  const css = readFileSync(path.join(REPO_ROOT, 'src/main/resources/web/css/chat.css'), 'utf8');

  const ruleBody = (selector) => {
    const re = new RegExp(selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([^}]*)\\}');
    const m = css.match(re);
    return m ? m[1] : null;
  };

  test('U1/U2 — user MEDIA faces scoped to the bubble language (flat + stack only)', () => {
    const flat = ruleBody('.row.user-media .pop-media-flat');
    expect(flat, 'scoped flat-face rule exists').toBeTruthy();
    expect(flat).toContain('border-radius: 16px');
    expect(flat).toContain('border-bottom-right-radius: 4px');
    expect(flat).toContain('box-shadow: none');
    expect(flat).toContain('background: var(--color-bubble-user)');

    const layer = ruleBody('.row.user-media .pop-stack-view .media-layer');
    expect(layer, 'scoped stack-layer rule exists').toBeTruthy();
    expect(layer).toContain('border-radius: 16px');
    expect(layer).toContain('border-bottom-right-radius: 4px');
    expect(layer).toContain('box-shadow: none');
    expect(layer).toContain('background: var(--color-bubble-user)');
  });

  test('F1 — user file card rides the unified chip family (2026-10-22 author ruling)', () => {
    const card = ruleBody('.row.user-media .file-card');
    expect(card, 'scoped file-card rule exists').toBeTruthy();
    // chip 族面：中性玻璃底（AskUser 同款 token 面）+ 细边框 + 紧凑。
    expect(card).toContain('background: var(--color-surface-2');
    expect(card).toContain('border: 1px solid');
    expect(card).toContain('border-radius: 10px');
    expect(card).toContain('max-width: 264px');
    expect(card).toContain('width: auto');
    // 修前形态逐项禁入（大绿块四件）：
    expect(card).not.toContain('var(--color-bubble-user)');
    expect(card).not.toContain('border: none');
    expect(card).not.toContain('border-radius: 16px');
    expect(card).not.toContain('width: 300px');
    // 28px 图标档（统一 chip 语言尺寸档）。
    const icon = ruleBody('.row.user-media .file-card .fc-icon');
    expect(icon, 'scoped icon-tier rule exists').toBeTruthy();
    expect(icon).toContain('width: 28px');
    expect(icon).toContain('height: 28px');
    // 墨水回归 token（修前 #111 硬编码随绿底退役）。
    expect(ruleBody('.row.user-media .fc-name')).toContain('var(--color-text)');
    expect(ruleBody('.row.user-media .fc-sub')).toContain('var(--color-text-muted)');
  });

  test('U4 — user media group caps at the bubble width (80%)', () => {
    expect(css).toMatch(/\.row\.user-media \.pop-group\s*\{\s*max-width: 80%;\s*\}/);
  });

  test('A0 — the agent faces keep their base design (negative assertions)', () => {
    const baseFlat = ruleBody('.pop-media-flat');
    expect(baseFlat, 'base flat rule still present').toBeTruthy();
    expect(baseFlat).toContain('border-radius: 14px');
    expect(baseFlat).toContain('background: #17191f');
    expect(baseFlat).not.toContain('bubble-user');

    const baseLayer = ruleBody('.media-layer');
    expect(baseLayer, 'base layer rule still present').toBeTruthy();
    expect(baseLayer).toContain('border-radius: 14px');

    const baseCard = ruleBody('.file-card');
    expect(baseCard, 'base file-card rule still present').toBeTruthy();
    expect(baseCard).toContain('background: var(--color-surface)');
    expect(baseCard).toContain('border-radius: 12px');
    expect(baseCard).not.toContain('bubble-user');
  });
});

/* ═══════════ DOM structure / marker classes (渲染 spec: 类名/结构) ═══════════ */

test.describe('user attachment rendering spec — classes & structure', () => {
  test('live: text bubble stays clean; media row is .row.pop-artifact.user.user-media after it', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaLive());
    const read = await page.evaluate(() => {
      const userRow = document.querySelector('#chat > .row.user');
      const mediaRow = document.querySelector('#chat > .row.user-media');
      return {
        userRowCls: userRow ? userRow.className : '',
        mediaRowCls: mediaRow ? mediaRow.className : '',
        mediaRightAfterUser: mediaRow ? mediaRow.previousElementSibling === userRow : false,
        bubbleClean: userRow ? !userRow.querySelector('.att-img, .att-file-tag') : false,
        groupCls: mediaRow && mediaRow.querySelector('.pop-group') ? mediaRow.querySelector('.pop-group').className : '',
        stackLayers: mediaRow ? mediaRow.querySelectorAll('.pop-stack-view .media-layer').length : 0,
        fileCards: mediaRow ? mediaRow.querySelectorAll('.file-card').length : 0,
      };
    });
    expect(read.userRowCls).toBe('row user');
    expect(read.mediaRowCls).toBe('row pop-artifact user user-media');
    expect(read.mediaRightAfterUser).toBe(true);
    expect(read.bubbleClean, 'no in-bubble attachment faces on the text bubble').toBe(true);
    expect(read.groupCls).toBe('pop-group');
    expect(read.stackLayers).toBe(2);
    expect(read.fileCards).toBe(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('single image → flat face (no stack); lone file → file card', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userSingleLive());
    const single = await page.evaluate(() => ({
      flat: !!document.querySelector('#chat > .row.user-media .pop-media-flat'),
      stack: !!document.querySelector('#chat > .row.user-media .pop-stack-view'),
    }));
    expect(single.flat).toBe(true);
    expect(single.stack).toBe(false);

    await page.evaluate(() => { window.__clearChat(); window.__userFileLive(); });
    const file = await page.evaluate(() => {
      const row = document.querySelector('#chat > .row.user-media');
      return {
        card: !!row && !!row.querySelector('.file-card'),
        media: row ? row.querySelectorAll('.pop-img, .pop-video').length : -1,
      };
    });
    expect(file.card).toBe(true);
    expect(file.media).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ Computed styles — light theme ═══════════ */

test.describe('computed paint — light theme', () => {
  test('U1/U2/U5 — user faces read the bubble silhouette; text bubble parity', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userSingleLive());
    const read = await page.evaluate(readFacesInPage, {
      flat: '#chat > .row.user-media .pop-media-flat',
      img: '#chat > .row.user-media .pop-img',
      bubble: '#chat > .row.user .bubble.user',
    });
    expect(read.flat.borderRadius).toBe('16px');
    expect(read.flat.tailRadius).toBe('4px');
    expect(read.flat.bg).toBe(GREEN);
    expect(read.flat.shadow).toBe('none');
    expect(read.img.bg, 'media element transparent — bubble color carries').toBe('rgba(0, 0, 0, 0)');
    expect(read.bubble.borderRadius, 'same radius as the text bubble').toBe('16px');
    expect(read.bubble.tailRadius, 'same tail corner as the text bubble').toBe('4px');
    expect(read.bubble.bg, 'same backing token as the text bubble').toBe(GREEN);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('F1/F2 — user file card: unified chip family, zero bubble green (light)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => { window.__clearChat(); window.__userFileLive(); });
    const read = await page.evaluate(() => {
      const read1 = (sel) => {
        const el = document.querySelector(sel);
        if (!el) return null;
        const cs = getComputedStyle(el);
        return { borderRadius: cs.borderTopLeftRadius, tailRadius: cs.borderBottomRightRadius,
          bg: cs.backgroundColor, shadow: cs.boxShadow, borderStyle: cs.borderLeftStyle,
          borderWidth: cs.borderLeftWidth, maxWidth: cs.maxWidth,
          iconW: el.querySelector('.fc-icon') ? getComputedStyle(el.querySelector('.fc-icon')).width : '' };
      };
      const color = (sel) => {
        const el = document.querySelector(sel);
        return el ? getComputedStyle(el).color : null;
      };
      return {
        card: read1('#chat > .row.user-media .file-card'),
        name: color('#chat > .row.user-media .fc-name'),
        sub: color('#chat > .row.user-media .fc-sub'),
      };
    });
    // chip 族（F1）：细边框 + 10px 圆角 + 紧凑帽 + 28px 图标档。
    expect(read.card.borderStyle, 'fine chip border').toBe('solid');
    expect(read.card.borderWidth).toBe('1px');
    expect(read.card.borderRadius).toBe('10px');
    expect(read.card.maxWidth).toBe('264px');
    expect(read.card.iconW).toBe('28px');
    expect(read.card.shadow).toBe('none');
    // F2 零气泡绿：底 = 中性玻璃（AskUser 面 token），亮主题也非绿。
    expect(read.card.bg).not.toBe(GREEN);
    expect(read.card.bg, 'AskUser family neutral glass face').toBe('rgba(127, 127, 127, 0.08)');
    // 墨水 = token（修前 #111 / alpha-black 随绿底退役）。
    expect(read.name, 'ink = text token').toBe('rgb(27, 30, 38)');
    expect(read.sub, 'sub line = muted token').toBe('rgb(139, 142, 150)');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('U3 — multi-image stack: front layer in bubble paint, geometry intact', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__userMediaLive());
    const read = await page.evaluate(() => {
      const front = document.querySelector('#chat > .row.user-media .pop-stack-view .media-layer.layer-front');
      const pill = document.querySelector('#chat > .row.user-media .pop-stack-area > .pill');
      const row = document.querySelector('#chat > .row.user-media');
      const rowRect = row.getBoundingClientRect();
      const frontRect = front.getBoundingClientRect();
      return {
        frontRadius: front ? getComputedStyle(front).borderTopLeftRadius : '',
        frontTail: front ? getComputedStyle(front).borderBottomRightRadius : '',
        frontBg: front ? getComputedStyle(front).backgroundColor : '',
        pillSide: pill && front ? (pill.getBoundingClientRect().right <= frontRect.left + 1 ? 'left' : 'right') : '',
        rowRightFlush: Math.abs(rowRect.right - frontRect.right) < 2,
      };
    });
    expect(read.frontRadius).toBe('16px');
    expect(read.frontTail).toBe('4px');
    expect(read.frontBg).toBe(GREEN);
    expect(read.pillSide, 'user-side pill stays LEFT of the stack (uifix3 ① geometry)').toBe('left');
    expect(read.rowRightFlush, 'right edge pinned at the bubble line (U3 同对齐)').toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('A0 — the agent Pop faces are UNCHANGED in light theme', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__popSingleWide());
    const read = await page.evaluate(readFacesInPage, {
      flat: '#chat > .row.pop-artifact:not(.user) .pop-media-flat',
      img: '#chat > .row.pop-artifact:not(.user) .pop-img',
    });
    expect(read.flat.borderRadius, 'agent keeps 14px').toBe('14px');
    expect(read.flat.tailRadius).toBe('14px');
    expect(read.flat.bg, 'agent keeps #17191f').toBe('rgb(23, 25, 31)');
    expect(read.flat.shadow, 'agent keeps its standing shadow').not.toBe('none');
    expect(read.img.bg, 'agent media keeps its own backing').toBe('rgb(23, 25, 31)');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ Computed styles — dark theme ═══════════ */

test.describe('computed paint — dark theme', () => {
  test('user faces stay the bubble green (the (0,1,0) dark override must not win)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, 'dark');
    await page.evaluate(() => window.__userSingleLive());
    const read = await page.evaluate(readFacesInPage, {
      flat: '#chat > .row.user-media .pop-media-flat',
      bubble: '#chat > .row.user .bubble.user',
    });
    expect(read.flat.bg, 'user media = user bubble green in dark too').toBe(GREEN);
    expect(read.flat.borderRadius).toBe('16px');
    expect(read.flat.shadow).toBe('none');
    expect(read.bubble.bg).toBe(GREEN);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('A0 — agent Pop faces keep the dark override design', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, 'dark');
    await page.evaluate(() => window.__popSingleWide());
    const read = await page.evaluate(readFacesInPage, { flat: '#chat > .row.pop-artifact:not(.user) .pop-media-flat' });
    expect(read.flat.bg, 'agent keeps the dark #101318 backing').toBe('rgb(16, 19, 24)');
    expect(read.flat.shadow).not.toBe('none');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('F2 — user file card in dark: same chip family, zero bubble green', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, 'dark');
    await page.evaluate(() => { window.__clearChat(); window.__userFileLive(); });
    const read = await page.evaluate(readFacesInPage, { card: '#chat > .row.user-media .file-card' });
    expect(read.card.bg, 'dark chip face stays neutral glass — never the bubble green').not.toBe(GREEN);
    expect(read.card.borderStyle, 'fine chip border survives dark').toBe('solid');
    expect(read.card.borderRadius).toBe('10px');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
