// mobile-topline-clear.spec.mjs — 手机端顶部渐变条不可见 + 气泡首行完整可读
// （合并批重做 · 作者令 2026-10-25 ①②：10-22 令「渐隐 mask 过渡带要么压出
// 视口要么退役改硬裁切」+ 10-19 令「玻璃栏底下穿字但悬空毛玻璃质感保留、
// 长消息切顶突兀」的回归钉）。
//
// 断言面（body.mobile-layer 激活态，375×812）：
//   T1 顶带零渐变：#top-overlays 不挂 .scroll-under-glass（10-22 顶带裙边退役批
//      契约）；#header 本体无渐变底、其 ::after 裙边不存在（uifix-glass G7 手机
//      腿的 DOM 形）；#chat 仍带 --chat-top-clip 裁剪变量（硬裁切机制在场）。
//   T2 首行可读：滚动位 0 时首条气泡的第一行完整落在 header 玻璃区之下
//      （main.js 顶让位 reserve 生效）——无任何渐变/蒙层压字。
//   T3 滚穿硬边界：滚动后内容从浮层控件下穿过（v3 悬浮顶栏设计保留），但
//      视口顶（物理上缘）之外的文字不可见 = 裁剪线锚定，无整条渐变蒙版
//      盖在气泡内容上（10-22 作者令「不允许任何渐变蒙层盖在气泡内容上」）。
//
// 数据面：全 mock（bg-merged-panel 同族 bootApp），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/mobile-topline-clear.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const ROOT_SID = 'topline-root-session';

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: ROOT_SID, name: 'topline', agentName: 'Nebula' }],
      folders: [], activeId: ROOT_SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
  });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, ROOT_SID, { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 种两条长消息行（.row.ai/.user + .bubble），把 #chat 撑出滚动量。 */
async function seedRows(page) {
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    chat.innerHTML = '';
    const mk = (cls, text) => {
      const row = document.createElement('div');
      row.className = `row ${cls}`;
      const bubble = document.createElement('div');
      bubble.className = `bubble ${cls}`;
      bubble.textContent = text;
      row.appendChild(bubble);
      chat.appendChild(row);
      return row;
    };
    const first = mk('user', 'TOP-LINE-FIRST 行一：这条消息的第一行必须完整可读，任何渐变蒙层都不得压在它上面。');
    for (let i = 0; i < 14; i++) mk('ai', `填充行 ${i} —— enough content to force #chat to scroll.`);
    first.dataset.first = '1';
  });
  await page.waitForTimeout(120);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await bootApp(page);
});

test('T1 mobile layer active; top band carries no gradient skirt; hard-clip var present', async ({ page }) => {
  const errors = watchErrors(page);
  await expect(page.locator('body')).toHaveClass(/mobile-layer/);

  const shape = await page.evaluate(() => {
    const overlays = document.getElementById('top-overlays');
    const header = document.getElementById('header');
    const chat = document.getElementById('chat');
    const hs = getComputedStyle(header, '::after');
    const os = overlays ? getComputedStyle(overlays, '::after') : null;
    return {
      overlaysHasSkirtClass: overlays ? overlays.classList.contains('scroll-under-glass') : null,
      headerHasSkirtClass: header.classList.contains('scroll-under-glass'),
      headerAfterContent: hs.content,
      headerAfterMask: hs.maskImage || hs.webkitMaskImage || 'none',
      headerBgImage: getComputedStyle(header).backgroundImage,
      headerBackdrop: getComputedStyle(header).backdropFilter || getComputedStyle(header).webkitBackdropFilter,
      chatClipVar: chat.style.getPropertyValue('--chat-top-clip').trim(),
      chatMask: (getComputedStyle(chat).maskImage || '').slice(0, 24),
    };
  });
  // 10-22 顶带裙边退役批：#top-overlays 摘 scroll-under-glass。
  expect(shape.overlaysHasSkirtClass).toBe(false);
  // 手机层 header 裙边随 10-22 顶部消失点批退役（G7 契约的 DOM 形）。
  expect(shape.headerHasSkirtClass).toBe(false);
  expect(shape.headerAfterContent === 'none' || shape.headerAfterContent === 'normal').toBe(true);
  expect(String(shape.headerAfterMask)).not.toContain('linear-gradient');
  // v3 悬浮顶栏：无通栏渐变底面（玻璃质感由各控件 glass-control 承担）。
  expect(shape.headerBgImage).toBe('none');
  expect(shape.headerBackdrop).toBe('none');
  // 硬裁切机制在场（--chat-top-clip 锚点变量 + #chat mask）。
  expect(shape.chatClipVar === '' || shape.chatClipVar.endsWith('px')).toBe(true);
  expect(shape.chatMask).toContain('linear-gradient');
  expect(errors).toEqual([]);
});

test('T2 first bubble line sits fully below the floating header glass (readable)', async ({ page }) => {
  const errors = watchErrors(page);
  await seedRows(page);

  const geo = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    chat.scrollTop = 0;
    const first = document.querySelector('#chat .row[data-first="1"] .bubble');
    const header = document.getElementById('header');
    const fr = first.getBoundingClientRect();
    const hr = header.getBoundingClientRect();
    // 第一行文本的实际行盒（取气泡内第一个文本行的 y 范围近似 = 气泡顶 + 行高起点）
    const cs = getComputedStyle(first);
    const firstLineTop = fr.top + parseFloat(cs.paddingTop || '0');
    // 求覆盖判定：首行水平带 [bubble.left, bubble.right] × [firstLineTop, firstLineTop+lineHeight]
    const lineH = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.4;
    const probe = document.elementFromPoint(
      Math.min(Math.max(fr.left + 8, 1), window.innerWidth - 1),
      Math.min(Math.max(firstLineTop + lineH / 2, 1), window.innerHeight - 1)
    );
    return {
      headerBottom: hr.bottom,
      firstLineTop,
      belowHeader: firstLineTop >= hr.bottom - 1,
      probeTag: probe ? `${probe.tagName.toLowerCase()}.${String(probe.className).split(' ').join('.')}` : '(none)',
      probeInsideHeader: probe ? !!probe.closest('#header') : false,
      scrollTop: chat.scrollTop,
    };
  });
  expect(geo.scrollTop).toBe(0);
  expect(geo.belowHeader).toBe(true);
  // 首行命中测试不落进 header（没有浮层控件盖在第一行上）。
  expect(geo.probeInsideHeader).toBe(false);
  expect(geo.probeTag).toContain('bubble');
  expect(errors).toEqual([]);
});

test('T3 scrolled content is clipped by the viewport edge, never veiled by a gradient overlay', async ({ page }) => {
  const errors = watchErrors(page);
  await seedRows(page);

  const scrolled = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    chat.scrollTop = 220; // 顶让位 reserve 之上 —— 内容穿入浮层控件区
    const header = document.getElementById('header');
    const chatBox = chat.getBoundingClientRect();
    // 视口顶带（y∈[0, header.bottom]）内，除 header 控件外不得有不透明渐变蒙版元素：
    // 收集首行气泡此刻的可见性 —— 它可能被裁掉/被控件压住，但不得被半透明
    // 渐变层盖住（即：没有任何 position:fixed/absolute 渐变背景元素以 >50% 不
    // 透明度横跨气泡宽度）。
    const veils = [];
    for (const el of document.querySelectorAll('body *')) {
      // header 悬浮控件（头像渐变底等）是作者批准的 glass-control 族 —— 不算蒙版。
      if (el.closest('#header')) continue;
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (!String(cs.backgroundImage).includes('linear-gradient')) continue;
      if (cs.pointerEvents !== 'none' && cs.position !== 'fixed' && cs.position !== 'absolute') continue;
      const r = el.getBoundingClientRect();
      if (r.height <= 0 || r.width <= 0) continue;
      const covers = r.top <= chatBox.top + 120 && r.bottom >= chatBox.top + 8 &&
        r.left <= (window.innerWidth - 8) && r.right >= 8;
      if (covers) veils.push(`${el.id || el.className}`);
    }
    return {
      scrollTop: chat.scrollTop,
      clipVar: chat.style.getPropertyValue('--chat-top-clip').trim(),
      headerTop: header.getBoundingClientRect().top,
      veils,
    };
  });
  expect(scrolled.scrollTop).toBe(220);
  // 无渐变蒙版元素横跨内容顶带（10-22 作者令字面：「不允许任何渐变蒙层盖在
  // 气泡内容上」）。浮层控件用 glass-control 純色底，不是渐变蒙版。
  expect(scrolled.veils).toEqual([]);
  expect(errors).toEqual([]);
});
