// liquid-glass.spec.mjs — 液体玻璃 v2 批（作者 10-31 08:43 裁定 + 09:03 拍板
// + 追加折射令）设计稿 §9 可断言验收点全量转 Playwright。
//
// 腿：
//   M* = 手机视口 375×812（body.mobile-layer 激活态；顶栏退役 + 渐隐带 + 圆钮）
//   D* = 桌面视口 1280×800（按钮族换代 + #header 零触碰回归）
//   F* = 降级腿（html.no-backdrop 类契约 = scrollUnderGlass.js 探测器同款输出；
//         真引擎探测在真机旧 WebView 生效，headless 用类注入测 CSS 契约）
//   R* = 边缘光折射腿（追加批：feDisplacementMap 滤镜驱动 + 类开关 + 不糊字）
//
// 数据面：全 mock（bootApp 同 topline-clear 族：page.route 供真实 web 文件 +
// WS configData/sessionList/historyPage 三帧），零后端、零 sbt。
// Run: node <stand>/node_modules/@playwright/test/cli.js test tests/liquid-glass.spec.mjs

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

const ROOT_SID = 'lg2-root-session';

async function bootApp(page, { width = 375, height = 812, colorScheme = 'light' } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/') || p.startsWith('/agents/')) {
      // /agents/<name>/agent.json：桌面引导活动栏/agent 面探测，mock 给空 JSON
      // （404 会污染零 console 错误断言）。
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
      sessions: [{ id: ROOT_SID, name: 'lg2', agentName: 'Nebula' }],
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
  await page.emulateMedia({ colorScheme });
  await page.setViewportSize({ width, height });
  await page.goto('http://localhost:1/');
  if (width <= 768) {
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  }
  await page.waitForTimeout(300);
}

function watchErrors(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    // 环境本底噪声（main 上同样存在，git stash 实证与本批零关联）：Chromium 对
    // route 拦截下偶发的 module preload 取消报 "An unknown error occurred when
    // fetching the script."，无 URL 无请求失败伴生。过滤该条，其余 console 错误
    // 照常收集（零新增 console 错误纪律不变）。
    if (m.text() === 'An unknown error occurred when fetching the script.') return;
    errors.push(`console: ${m.text()}`);
  });
  return errors;
}

/** 追加一个 g2 探针元素（桌面/手机同用），返回清理句柄由 evaluate 内自清理。 */
async function probeStyles(page, className, cssProps) {
  return page.evaluate(async ([cls, props]) => {
    const el = document.createElement('button');
    el.type = 'button';
    el.className = cls;
    el.textContent = '探';
    el.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:1;';
    document.body.appendChild(el);
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const cs = getComputedStyle(el);
    const out = {};
    for (const p of props) out[p] = cs.getPropertyValue(p);
    return out;
  }, [className, cssProps]);
}

test.describe('手机视口（mobile-layer）', () => {
  test('M1 §9-① 顶栏容器退役 + 渐隐带在场不拦截', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    const r = await page.evaluate(() => {
      const header = document.getElementById('header');
      const fade = document.getElementById('g2-edge-fade');
      return {
        headerDisplay: getComputedStyle(header).display,
        fadeExists: !!fade,
        fadeDisplay: fade ? getComputedStyle(fade).display : null,
        fadePointerEvents: fade ? getComputedStyle(fade).pointerEvents : null,
        fadeAriaHidden: fade ? fade.getAttribute('aria-hidden') : null,
        layerExists: !!document.getElementById('mobile-fab-layer'),
        chatHeadExists: !!document.querySelector('#chat > .g2-chat-head'),
      };
    });
    expect(r.headerDisplay).toBe('none'); // 容器不渲染（DOM 保留，桌面簇读数源零扰动）
    expect(r.fadeExists).toBe(true);
    expect(r.fadeDisplay).toBe('block');
    expect(r.fadePointerEvents).toBe('none');
    expect(r.fadeAriaHidden).toBe('true');
    expect(r.layerExists).toBe(true);
    expect(r.chatHeadExists).toBe(true);
    expect(errors).toEqual([]);
  });

  test('M2 §9-② 渐隐带 blur(18px) saturate(1.5) + 两段式 mask', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    const r = await page.evaluate(() => {
      const cs = getComputedStyle(document.getElementById('g2-edge-fade'));
      return {
        backdrop: cs.backdropFilter || cs.webkitBackdropFilter,
        mask: cs.maskImage || cs.webkitMaskImage,
        veil: cs.backgroundImage,
      };
    });
    expect(String(r.backdrop)).toContain('blur(18px)');
    expect(String(r.backdrop)).toContain('saturate(1.5)');
    expect(r.mask).not.toBe('none');
    expect(String(r.mask)).toContain('linear-gradient');
    expect(String(r.veil)).toContain('linear-gradient');
    expect(errors).toEqual([]);
  });

  test('M3 §9-③ 悬浮圆钮正圆 48px（移动档）；折射层 blur 20 / sat 2.2（v3 §D）；图标 20px', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    const r = await page.evaluate(() => {
      const btn = document.querySelector('#mobile-fab-layer .mh-chrome-btn');
      const cs = getComputedStyle(btn);
      const bcs = getComputedStyle(btn, '::before'); // 折射层在伪元素（liquid-glass.css）
      const svg = btn.querySelector('svg') || btn.querySelector('i');
      const scs = svg ? getComputedStyle(svg) : null;
      return {
        radius: cs.borderRadius,
        w: cs.width,
        h: cs.height,
        bg: cs.backgroundColor,
        blur: bcs.backdropFilter || bcs.webkitBackdropFilter,
        highlight: bcs.backgroundImage,
        svgW: scs ? scs.width : null,
      };
    });
    expect(r.radius).toBe('50%');
    expect(r.w).toBe('48px');
    expect(r.h).toBe('48px');
    // v3 §D 令牌单源（stand-tokens.css --g2-fab-blur/sat）：20px / 2.2。
    expect(String(r.blur)).toContain('blur(20px)');
    expect(String(r.blur)).toContain('saturate(2.2)');
    expect(r.highlight).toContain('linear-gradient'); // 上半镜面高光层
    expect(r.svgW).toBe('20px');
    expect(errors).toEqual([]);
  });

  test('M4 §9-④ CSSOM：圆钮 :active scale(.88) / 药丸 scale(.95)，过渡时长 ≈180ms', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    const r = await page.evaluate(() => {
      const grab = (sel, wantDuration) => {
        for (const sheet of document.styleSheets) {
          let rules;
          try { rules = sheet.cssRules; } catch { continue; }
          for (const rule of rules) {
            if (!rule.selectorText) continue;
            const sels = rule.selectorText.split(',').map((s) => s.trim());
            if (!sels.includes(sel)) continue;
            if (wantDuration && !rule.style.transitionDuration) continue;
            return { transform: rule.style.transform, duration: rule.style.transitionDuration };
          }
        }
        return null;
      };
      return {
        // 形变值在 :active 规则；spring 时长在基类 transition（:active 规则本身无时长）。
        fab: grab('.g2-fab:active'),
        pill: grab('.g2-pill:active'),
        fabBase: grab('.g2-fab', true),
        pillBase: grab('.g2-pill', true),
      };
    });
    expect(r.fab?.transform).toBe('scale(0.88)');
    expect(r.pill?.transform).toBe('scale(0.95)');
    for (const base of [r.fabBase, r.pillBase]) {
      const secs = String(base?.duration || '').split(',').map((s) => parseFloat(s));
      expect(secs.some((s) => Math.abs(s - 0.18) < 0.02)).toBe(true);
    }
    expect(errors).toEqual([]);
  });

  test('M5 §9-④ 发送键纯圆化（收形批⑥ 作者令 10-31 22:50）：r50% 36×36 圆钮，绿保留（真元素断言）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page);
    const r = await page.evaluate(() => {
      const cs = getComputedStyle(document.getElementById('send-btn'));
      return { radius: cs.borderRadius, w: cs.width, h: cs.height, bg: cs.backgroundColor, color: cs.color };
    });
    expect(r.radius).toBe('50%');
    expect(r.w).toBe('36px');
    expect(r.h).toBe('36px');
    expect(errors).toEqual([]);
  });
});

test.describe('桌面视口（桌面按钮族换代，#header 不动）', () => {
  test('D1 §9-⑧ 桌面 #header 维持 10-30 基线（零触碰指纹）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { width: 1280, height: 800 });
    const r = await page.evaluate(() => {
      const cs = getComputedStyle(document.getElementById('header'));
      const fade = document.getElementById('g2-edge-fade');
      return {
        backdrop: cs.backdropFilter || cs.webkitBackdropFilter,
        radius: cs.borderRadius,
        display: cs.display,
        position: cs.position,
        fadeDisplay: fade ? getComputedStyle(fade).display : null,
        bodyClass: document.body.className,
      };
    });
    expect(r.backdrop).toContain('blur(24px)');
    expect(r.backdrop).toContain('saturate(1.15)');
    expect(r.radius).toBe('20px');
    expect(r.display).toBe('flex');
    expect(r.position).toBe('absolute');
    expect(r.fadeDisplay).toBe('none'); // 渐隐带仅手机端
    expect(r.bodyClass).not.toContain('mobile-layer');
    expect(errors).toEqual([]);
  });

  test('D2 §9-③ 桌面圆钮档 40px 正圆；§9-④ 药丸 r999（CSSOM 探针）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { width: 1280, height: 800 });
    const fab = await probeStyles(page, 'g2-fab', ['border-radius', 'width', 'height']);
    expect(fab['border-radius']).toBe('50%');
    expect(fab['width']).toBe('40px');
    expect(fab['height']).toBe('40px');
    const pill = await probeStyles(page, 'g2-pill', ['border-radius', 'height']);
    expect(pill['border-radius']).toBe('999px');
    expect(pill['height']).toBe('36px');
    const cfg = await probeStyles(page, 'cfg-btn', ['border-radius']);
    expect(cfg['border-radius']).toBe('999px');
    expect(errors).toEqual([]);
  });

  test('D3 §9-⑤ 亮档 hover 填充 Δ ≥ 8/255（.g2-pill 探针实测）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { width: 1280, height: 800, colorScheme: 'light' });
    await page.evaluate(() => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'g2-pill';
      el.textContent = 'HoverProbe';
      el.style.cssText = 'position:fixed;left:400px;top:300px;z-index:5;';
      document.body.appendChild(el);
    });
    const rest = await page.evaluate(() => getComputedStyle(document.querySelector('.g2-pill')).backgroundColor);
    await page.hover('.g2-pill');
    await page.waitForTimeout(250); // 等 :hover 样式落定（实测零等待时读数偶发未切换）
    const hover = await page.evaluate(() => getComputedStyle(document.querySelector('.g2-pill')).backgroundColor);
    const alpha = (rgba) => {
      const m = /rgba\(\s*\d+,\s*\d+,\s*\d+,\s*([\d.]+)\s*\)/.exec(rgba);
      return m ? parseFloat(m[1]) : 1;
    };
    const delta = Math.abs(alpha(hover) - alpha(rest));
    expect(delta).toBeGreaterThanOrEqual(8 / 255);
    expect(errors).toEqual([]);
  });
});

test.describe('三态 token 解析（§9-⑥）', () => {
  test('T1 --g2-fab-bg 三态可解析且亮暗不同', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { width: 1280, height: 800, colorScheme: 'light' });
    const read = () => page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--g2-fab-bg').trim());
    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'));
    const light = await read();
    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
    const dark = await read();
    await page.evaluate(() => document.documentElement.removeAttribute('data-theme'));
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForTimeout(120);
    const fallbackDark = await read();
    // v3 §D：--g2-fab-bg 亮 0.30 / 暗 0.10（stand-tokens.css 三态块）。
    expect(light).toContain('0.30');
    expect(dark).toContain('0.10');
    expect(fallbackDark).toContain('0.10');
    expect(light).not.toBe(dark);
    expect(errors).toEqual([]);
  });
});

test.describe('no-backdrop 降级（§9-⑦）', () => {
  test('F1 圆钮实色回落：亮 alpha ≥ .55 / 暗 alpha ≥ .20（v3 --g2-fab-solid），高光投影保留', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { width: 1280, height: 800, colorScheme: 'light' });
    const read = () => page.evaluate(() => {
      let el = document.querySelector('.g2-fab.probe');
      if (!el) {
        el = document.createElement('button');
        el.type = 'button';
        el.className = 'g2-fab probe';
        el.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:1;';
        document.body.appendChild(el);
      }
      const cs = getComputedStyle(el);
      return { bg: cs.backgroundColor, shadow: cs.boxShadow };
    });
    await page.evaluate(() => document.documentElement.classList.add('no-backdrop'));
    const light = await read();
    expect(parseFloat(/,\s*([\d.]+)\)$/.exec(light.bg)[1])).toBeGreaterThanOrEqual(0.55 - 1e-6);
    expect(light.shadow).toContain('inset'); // 高光/浮影保留（不裸断）
    await page.evaluate(() => {
      document.documentElement.setAttribute('data-theme', 'dark');
    });
    const dark = await read();
    expect(parseFloat(/,\s*([\d.]+)\)$/.exec(dark.bg)[1])).toBeGreaterThanOrEqual(0.20 - 1e-6);
    expect(errors).toEqual([]);
  });
});

test.describe('边缘光折射（追加批）', () => {
  test('R1 能力探测类开关 + 折射层滤镜契约 + 摘除后保留高光（不裸断）', async ({ page }) => {
    const errors = watchErrors(page);
    await bootApp(page, { width: 1280, height: 800 });
    const on = await page.evaluate(() => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'g2-fab';
      el.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:1;';
      document.body.appendChild(el);
      const before = getComputedStyle(el, '::before');
      return {
        cls: document.documentElement.classList.contains('g2-refract-on'),
        filter: before.filter,
        backdrop: before.backdropFilter || before.webkitBackdropFilter,
        highlight: before.backgroundImage,
      };
    });
    // headless Chromium 支持 SVG 滤镜 ⇒ 类开关应挂上（defs 在场双判据）。
    expect(on.cls).toBe(true);
    expect(String(on.filter)).toContain('url(');
    expect(String(on.backdrop)).toContain('blur(20px)');
    expect(String(on.backdrop)).toContain('saturate(2.2)');
    expect(on.highlight).toContain('linear-gradient'); // 镜面高光层常驻
    // 摘除类 = 旧 WebView 降级面：滤镜消失、高光/直 blur 保留。
    const off = await page.evaluate(() => {
      document.documentElement.classList.remove('g2-refract-on');
      const before = getComputedStyle(document.querySelector('.g2-fab'), '::before');
      return { filter: before.filter, backdrop: before.backdropFilter || before.webkitBackdropFilter, highlight: before.backgroundImage };
    });
    expect(String(off.filter)).toBe('none');
    expect(String(off.backdrop)).toContain('blur(20px)');
    expect(off.highlight).toContain('linear-gradient');
    expect(errors).toEqual([]);
  });
});
