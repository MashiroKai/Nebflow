#!/usr/bin/env node
// profile-complete-shots.mjs — 个人主页资料补全 + 头像跳官网的**对照证据**生成器
// （作者令 2026-11-18 16:45）。
//
// 载体与边界（照实声明，勿读成真机证据）：
//   · 零实例、零生产数据、零受保护端口：web 根 = 本地文件直读，全部 REST/WS 输入走
//     Playwright 路由桩，页面只 goto `http://localhost:1/`（不会有任何监听）。
//   · 「补全前」= **改动前那 4 个文件的真实字节**（`git show <base>:<path>` 直取），
//     其余文件两侧同为工作区版本 ⇒ 画面差异只可能来自本批那几笔改动，不是手绘对照。
//   · 描边/说明是**证据标注层**（截图前注入的 DOM 覆盖层），不是产品界面的一部分；
//     每张图都会写明「标注为证据所加」。
//   · 读数夹具 = 作者本机 2026-11-18 的真实值（本机 credential store 里读到：
//     id_token 的 name/email/username claim + `device.json` 的 `githubLogin`），
//     不是编出来的样例值。真机联网行为未证（见交付报告的「已实测 / 待真机亲验」）。
//
// 产出：
//   $PROFILE_COMPLETE_OUT（默认 <repo>/delivery/profile-complete）
//     before.png / after.png            —— 手机全屏个人主页（同一视口、同一场景）
//     desktop-before.png / desktop-after.png
//     screenshots.md                    —— CONTRIBUTING §147 要求的三段式说明
//   $PROFILE_COMPLETE_EVIDENCE（默认 <repo>/.stand/tmp/<wt>/evidence/profile-complete）
//     readings.json                     —— 逐字段读数（前/后 × 两个视口）
//     fields-before-after.gif           —— 字段「未知 → 真实值」动态对照
//     avatar-jump.gif                   —— 点头像 → 新标签页开官网（本页不动）
//     各关键帧 PNG
//
// Run: PROFILE_COMPLETE_BASE=<base-commit> node tests/profile-complete-shots.mjs
// Exit: 0 = 全部判据成立且产物落盘；1 = 有判据不成立（逐条打印）。

import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = process.env.PROFILE_COMPLETE_OUT || join(ROOT, 'delivery', 'profile-complete');
const EVID = process.env.PROFILE_COMPLETE_EVIDENCE
  || join(ROOT, '.stand', 'tmp', 'stand-profile-complete-20261118', 'evidence', 'profile-complete');
const FRAMES = join(EVID, 'frames');
const BASE = process.env.PROFILE_COMPLETE_BASE || 'HEAD';
const HOME = 'https://mystand.space/';

/** 本批改动的 4 个前端文件（「补全前」逐字取 base 版本；其余走工作区）。 */
const CHANGED = [
  'src/main/resources/web/js/brand.js',
  'src/main/resources/web/js/neblink.js',
  'src/main/resources/web/js/settingsIdentity.js',
  'src/main/resources/web/js/mobile/meFace.js',
];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const HOME_STUB_BODY = '<!doctype html><meta charset="utf-8"><title>mystand.space</title>'
  + '<body style="font:15px system-ui;padding:28px;color:#111;background:#fafafa">'
  + '<h1 style="margin:0 0 8px">mystand.space</h1>'
  + '<p style="color:#555">destination stand-in — this harness never contacts the live site</p></body>';
const HOME_URL = /^https:\/\/mystand\.space(\/|$|\?)/;

const SELF = {
  id: 'dev-self', name: 'Mashiros-MacBook-Pro-iso-a659864a', platform: 'macos',
  displayName: 'Mashiro', email: 'kaimashiro@qq.com', avatarUrl: '',
  githubLogin: 'Mashiro',
};

const results = [];
const failures = [];
const shots = [];
const check = (ok, name, detail = '') => {
  results.push({ name, ok, detail });
  if (!ok) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
};

function gitShow(ref, path) {
  return execFileSync('git', ['-C', ROOT, 'show', `${ref}:${path}`], { maxBuffer: 32 * 1024 * 1024 });
}

/** base 版本文件内容（相对 URL 路径 → Buffer）。 */
function basePayloads(ref) {
  const map = new Map();
  for (const p of CHANGED) map.set(`/${p.slice('src/main/resources/web/'.length)}`, gitShow(ref, p));
  return map;
}

async function newSession(browser, { mobile, base }) {
  const viewport = mobile ? { width: 390, height: 844 } : { width: 1280, height: 800 };
  const context = await browser.newContext({ viewport, hasTouch: mobile, colorScheme: 'dark' });
  const page = await context.newPage();

  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('neblink_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    window.__openCalls = [];
    window.open = (...a) => { window.__openCalls.push(a); return null; };
    window.__navs = [];
    window.addEventListener('beforeunload', () => { window.__navs.push('unload'); });
  });

  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const raw = route.request().url();
    const url = new URL(raw);
    if (HOME_URL.test(raw)) {
      return route.fulfill({ status: 200, contentType: 'text/html', body: HOME_STUB_BODY });
    }
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: 'pc-session', name: 'root', agentName: 'Nebula' }], activeId: 'pc-session' }),
        });
      }
      if (p === '/api/neblink/status') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ loggedIn: true, device: SELF, peers: [] }),
        });
      }
      if (p === '/api/users/search') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            found: true,
            user: { userId: 'u-self', username: 'Mashiro', display_name: 'Mashiro', avatar: null },
            relation_status: 'self',
          }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    // 「补全前」腿：本批那 4 个文件逐字用 base 版本。
    if (base && base.has(p)) {
      return route.fulfill({ status: 200, contentType: 'text/javascript', body: base.get(p) });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  // 官网出口的桩必须挂 context —— `page.route` 拦不到 `target="_blank"` 开出来的新页。
  await context.route(HOME_URL, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: HOME_STUB_BODY }));

  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'pc-session', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'pc-session' }));
    cfg();
    ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
    });
  });

  await page.goto('http://localhost:1/');
  if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'));
  else await page.waitForSelector('#settings-btn', { timeout: 20000 });
  await page.waitForTimeout(350);
  return { context, page, consoleErrors };
}

/** 证据标注层（说明文字；明说不是产品界面）。 */
async function annotate(target, text) {
  await target.evaluate((label) => {
    let el = document.getElementById('pc-evidence-caption');
    if (!el) {
      el = document.createElement('div');
      el.id = 'pc-evidence-caption';
      el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;'
        + 'background:rgba(12,14,18,.88);color:#fff;font:600 12px/1.5 system-ui;padding:8px 12px;'
        + 'text-align:center;letter-spacing:.2px;pointer-events:none';
      document.body.appendChild(el);
    }
    el.textContent = label;
  }, text);
}

async function openMobileMe(page) {
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await page.waitForFunction(() => document.querySelector('.mobile-page[data-page="me"]')?.classList.contains('live'));
  await page.waitForTimeout(450);
}

async function openDesktopFace(page) {
  await page.evaluate(async () => (await import('/js/settingsIdentity.js')).openProfileFace());
  await page.waitForTimeout(500);
}

const mobileReadings = (page) => page.evaluate(() => {
  const out = {};
  for (const r of document.querySelectorAll('.mobile-page[data-page="me"] .mf-me-field')) {
    out[r.querySelector('.mf-me-field-label')?.textContent?.trim() || '?'] =
      r.querySelector('.mf-me-field-value')?.textContent?.trim() || '';
  }
  const gap = document.querySelector('.mobile-page[data-page="me"] .mf-me-gapnote');
  const av = document.querySelector('.mobile-page[data-page="me"] .mf-me-avatar-frame.huge');
  return {
    fields: out,
    gap: gap && !gap.hidden ? (gap.textContent || '').trim() : '',
    avatar: av ? { tag: av.tagName, href: av.getAttribute('href'), target: av.getAttribute('target'), rel: av.getAttribute('rel') } : null,
  };
});

const desktopReadings = (page) => page.evaluate(() => {
  const ov = document.getElementById('settings-profile-overlay');
  const f = (id) => ov?.querySelector(`.sp-field[data-sp-field="${id}"] .sp-value`)?.textContent?.trim() || '';
  const note = ov?.querySelector('#settings-profile-bindnote');
  return {
    fields: {
      '用户名': f('username'), '昵称': f('nickname'), '邮箱': f('email'),
      'GitHub 绑定': f('github'), 'Google 绑定': f('google'),
    },
    gap: note && !note.hidden ? (note.textContent || '').trim() : '',
  };
});

function ffmpeg(args) {
  return execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...args], { stdio: ['ignore', 'inherit', 'inherit'] });
}

function buildGif(framePattern, out, { fps = 1 } = {}) {
  ffmpeg([
    '-framerate', String(fps), '-i', framePattern,
    '-vf', 'scale=iw:ih:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse',
    '-loop', '0', out,
  ]);
}

function probe(file) {
  const out = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries',
    'stream=width,height,nb_frames,avg_frame_rate', '-show_entries', 'format=duration,size',
    '-of', 'json', file], { encoding: 'utf8' });
  const j = JSON.parse(out);
  return {
    width: j.streams[0].width, height: j.streams[0].height,
    frames: Number(j.streams[0].nb_frames),
    fps: j.streams[0].avg_frame_rate,
    durationSec: Number(j.format.duration), bytes: Number(j.format.size),
  };
}

async function main() {
  for (const dir of [OUT, EVID, FRAMES]) mkdirSync(dir, { recursive: true });
  const base = basePayloads(BASE);
  const browser = await chromium.launch({ headless: true });
  const readings = { base: BASE, fixture: SELF, viewports: {} };
  const consoleErrors = [];

  try {
    // ── 手机全屏（390×844）：前 / 后 ────────────────────────────────────
    const legs = {};
    for (const mode of ['before', 'after']) {
      const s = await newSession(browser, { mobile: true, base: mode === 'before' ? base : null });
      legs[mode] = s;
      await openMobileMe(s.page);
      const r = await mobileReadings(s.page);
      readings.viewports[`mobile-390x844-${mode}`] = r;
      if (mode === 'before') {
        // 「补全前」= 三个「未知」；头像零行为（照实记录，证明是新增不是改写）。
        check(r.fields['用户名'] === '未知', 'before/手机：用户名 = 未知（探针从未发起）', JSON.stringify(r.fields));
        check(r.fields['GitHub 绑定'] === '未知', 'before/手机：GitHub 绑定 = 未知', JSON.stringify(r.fields));
        check(r.fields['Google 绑定'] === '未知', 'before/手机：Google 绑定 = 未知', JSON.stringify(r.fields));
        check(r.avatar && r.avatar.tag === 'DIV', 'before/手机：大头像不是链接（零行为）', JSON.stringify(r.avatar));
        await annotate(s.page, '① 补全前 · 手机个人主页（全屏）· 用户名 / GitHub 绑定 / Google 绑定 = 未知');
        await s.page.screenshot({ path: join(OUT, 'before.png') });
        await s.page.screenshot({ path: join(FRAMES, 'fields-01.png') });
        shots.push(join(OUT, 'before.png'));
      } else {
        check(r.fields['用户名'] === 'Mashiro', 'after/手机：用户名 = 真实值', JSON.stringify(r.fields));
        check(r.fields['GitHub 绑定'] === 'Mashiro', 'after/手机：GitHub 绑定 = 真实值', JSON.stringify(r.fields));
        check(r.fields['Google 绑定'] === '未知', 'after/手机：Google 绑定仍为未知（本机无来源）');
        check(/Google/.test(r.gap) && /官网账号页/.test(r.gap), 'after/手机：未知位附缺口说明', r.gap);
        check(r.avatar && r.avatar.tag === 'A' && r.avatar.href === 'https://mystand.space'
          && r.avatar.target === '_blank' && r.avatar.rel === 'noopener noreferrer',
          'after/手机：大头像 = 官网出口锚点（三属性齐备）', JSON.stringify(r.avatar));
        await annotate(s.page, '② 补全后 · 手机个人主页（全屏）· 用户名 / GitHub 绑定 = 真实值；Google 未知 + 缺口说明');
        await s.page.screenshot({ path: join(OUT, 'after.png') });
        await s.page.screenshot({ path: join(FRAMES, 'fields-02.png') });
        shots.push(join(OUT, 'after.png'));
      }
      consoleErrors.push(...s.consoleErrors.map((e) => `mobile-${mode}: ${e}`));
    }

    // ── 头像跳官网（手机）：点 → 新标签页 → 本页不动 ─────────────────────
    {
      const s = legs.after;
      await s.page.locator('.mobile-page[data-page="me"] .mf-me-avatar-frame.huge')
        .evaluate((el) => { el.style.outline = '3px solid #ff5c8a'; el.style.outlineOffset = '3px'; });
      await annotate(s.page, '③ 点头像（粉圈 = 证据标注）');
      await s.page.screenshot({ path: join(FRAMES, 'avatar-01.png') });

      const before = s.page.url();
      const popupPromise = s.context.waitForEvent('page');
      await s.page.locator('.mobile-page[data-page="me"] .mf-me-avatar-frame.huge').click();
      const popup = await popupPromise;
      await popup.waitForLoadState('domcontentloaded').catch(() => {});
      const popupUrl = popup.url();
      check(popupUrl === HOME, 'after/手机：新标签页落到官网', popupUrl);
      await annotate(popup, '④ 新标签页 = https://mystand.space（落地占位桩，真站零接触）');
      await popup.screenshot({ path: join(FRAMES, 'avatar-02.png') });
      check(s.page.url() === before, 'after/手机：本页不导航（URL 逐字不变）', s.page.url());
      await annotate(s.page, '⑤ 本页原地不动（个人主页仍在，未跳走）');
      await s.page.screenshot({ path: join(FRAMES, 'avatar-03.png') });
      check(await s.page.evaluate(() => window.__openCalls.length) === 0, 'after/手机：零 window.open（走锚点）');
      readings.avatarJump = { href: 'https://mystand.space', target: '_blank', rel: 'noopener noreferrer', popupUrl, pageUrl: s.page.url() };
      await popup.close();
    }

    // ── 桌面（1280×800）：前 / 后 ────────────────────────────────────────
    for (const mode of ['before', 'after']) {
      const s = await newSession(browser, { mobile: false, base: mode === 'before' ? base : null });
      await openDesktopFace(s.page);
      const r = await desktopReadings(s.page);
      readings.viewports[`desktop-1280x800-${mode}`] = r;
      if (mode === 'before') {
        check(r.fields['GitHub 绑定'] === '未知', 'before/桌面：GitHub 绑定 = 未知', JSON.stringify(r.fields));
        check(r.gap === '', 'before/桌面：无缺口说明（旧版没有这一句）', r.gap);
        await annotate(s.page, '⑥ 补全前 · 桌面个人资料面（1280×800）· GitHub 绑定 = 未知');
        await s.page.screenshot({ path: join(OUT, 'desktop-before.png') });
      } else {
        check(r.fields['GitHub 绑定'] === 'Mashiro', 'after/桌面：GitHub 绑定 = 真实值', JSON.stringify(r.fields));
        check(r.fields['用户名'] === 'Mashiro', 'after/桌面：用户名 = 真实值');
        check(/官网账号页/.test(r.gap), 'after/桌面：未知位附缺口说明（与手机面同源）', r.gap);
        await annotate(s.page, '⑦ 补全后 · 桌面个人资料面（1280×800）· GitHub 绑定 = 真实值 + 缺口说明');
        await s.page.screenshot({ path: join(OUT, 'desktop-after.png') });
      }
      consoleErrors.push(...s.consoleErrors.map((e) => `desktop-${mode}: ${e}`));
      await s.context.close();
    }
    await legs.before.context.close();

    // ── 动态证据（GIF）──────────────────────────────────────────────────
    buildGif(join(FRAMES, 'fields-%02d.png'), join(EVID, 'fields-before-after.gif'), { fps: 1 });
    buildGif(join(FRAMES, 'avatar-%02d.png'), join(EVID, 'avatar-jump.gif'), { fps: 1 });
    readings.media = {
      fieldsGif: probe(join(EVID, 'fields-before-after.gif')),
      avatarGif: probe(join(EVID, 'avatar-jump.gif')),
      files: {
        before: probe(join(OUT, 'before.png')), after: probe(join(OUT, 'after.png')),
        desktopBefore: probe(join(OUT, 'desktop-before.png')), desktopAfter: probe(join(OUT, 'desktop-after.png')),
      },
    };
    check(readings.media.fieldsGif.bytes < 10 * 1024 * 1024, 'GIF ≤ 10MB（fields）', String(readings.media.fieldsGif.bytes));
    check(readings.media.avatarGif.bytes < 10 * 1024 * 1024, 'GIF ≤ 10MB（avatar）', String(readings.media.avatarGif.bytes));
    check(consoleErrors.length === 0, '全程零 console error', consoleErrors.join(' | '));

    writeFileSync(join(EVID, 'readings.json'), JSON.stringify({
      base: BASE, fixture: SELF, checks: results, consoleErrors, readings,
    }, null, 2));

    writeFileSync(join(OUT, 'screenshots.md'), `# Personal profile completion + avatar website entry

- **Viewport:** mobile full-screen page 390 × 844 CSS px (device scale factor 1, dark) for
  \`before.png\` / \`after.png\`; desktop profile face 1280 × 800 CSS px for
  \`desktop-before.png\` / \`desktop-after.png\`. Real pixel sizes are printed by
  \`ffprobe\` in the evidence \`readings.json\`.
- **Scenario:** the same stubbed signed-in device readout on both sides — device
  \`displayName\` / \`email\` / \`githubLogin\` and the self-probe answer are the author's
  real 2026-11-18 readings (captured from this machine's credential store), not invented
  sample values. \`before*.png\` serves the **base-commit bytes of the four behaviour files**
  this change rewrites (\`js/brand.js\`, \`js/neblink.js\`, \`js/settingsIdentity.js\`,
  \`js/mobile/meFace.js\` — \`git show <base>:<path>\`); \`after*.png\` serves this branch. The
  other two touched files stay at branch state in both legs because their delta is purely
  additive and inert without those four (a \`css/mobileLayer.css\` rule whose element does not
  exist in the base bytes, and two new locale keys). Only the dashed caption strip is added by
  the evidence harness — it is annotation, not product UI.
- **Fixture or command:** \`PROFILE_COMPLETE_BASE=<base-commit> node tests/profile-complete-shots.mjs\`
  (zero-port harness: local file serving + Playwright route stubs, page only ever visits
  \`http://localhost:1/\`). The same judgments run mechanically in
  \`tests/profile-complete.spec.mjs\`.

These screenshots demonstrate the rendered profile readouts and the avatar entry target.
They do not prove live-account data, a real device's own GitHub binding state, or the live
website's behaviour.

## Real pixel measurements (\`ffprobe\`, this run)

| File | Bytes | px | frames | frame rate | duration |
| --- | --- | --- | --- | --- | --- |
| \`before.png\` | ${readings.media.files.before.bytes} | ${readings.media.files.before.width} × ${readings.media.files.before.height} | still | — | — |
| \`after.png\` | ${readings.media.files.after.bytes} | ${readings.media.files.after.width} × ${readings.media.files.after.height} | still | — | — |
| \`desktop-before.png\` | ${readings.media.files.desktopBefore.bytes} | ${readings.media.files.desktopBefore.width} × ${readings.media.files.desktopBefore.height} | still | — | — |
| \`desktop-after.png\` | ${readings.media.files.desktopAfter.bytes} | ${readings.media.files.desktopAfter.width} × ${readings.media.files.desktopAfter.height} | still | — | — |
| \`fields-before-after.gif\` | ${readings.media.fieldsGif.bytes} | ${readings.media.fieldsGif.width} × ${readings.media.fieldsGif.height} | ${readings.media.fieldsGif.frames} | ${readings.media.fieldsGif.fps} (avg) | ${readings.media.fieldsGif.durationSec} s |
| \`avatar-jump.gif\` | ${readings.media.avatarGif.bytes} | ${readings.media.avatarGif.width} × ${readings.media.avatarGif.height} | ${readings.media.avatarGif.frames} | ${readings.media.avatarGif.fps} (avg) | ${readings.media.avatarGif.durationSec} s |

Both animations are well under 10 MB, so neither needed an MP4 rendition.

## Animated evidence

- \`fields-before-after.gif\` — the same mobile profile scroll, frame 1 = base bytes, frame 2 =
  this branch: \`用户名 未知 → Mashiro\`, \`GitHub 绑定 未知 → Mashiro\`, \`Google 绑定\` stays
  \`未知\` and gains the gap sentence naming the account page that owns it.
- \`avatar-jump.gif\` — frame 1 the mobile profile page at rest; frame 2 the tab that the
  activation opened, addressed \`https://mystand.space/\` and served by a local stand-in page
  (the harness never contacts the live site — the frame says so itself); frame 3 the profile
  page after the activation, still on its own address with its readouts intact. The new tab is
  intercepted with \`context.route\`: a \`page.route\` cannot see a target-\`_blank\` popup
  (measured, not assumed — the first attempt leaked to the real site).

The animated files, \`readings.json\` and the break/restore gate logs live in the worktree
manifest staging area (default
\`<primary checkout>/.stand/tmp/stand-profile-complete-20261118/evidence/profile-complete/\`,
overridable with \`PROFILE_COMPLETE_EVIDENCE\`).
`);

    console.log('\n=== profile-complete evidence ===');
    for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? ` :: ${r.detail}` : ''}`);
    console.log('media:', JSON.stringify(readings.media));
    console.log(`delivery: ${OUT}`);
    console.log(`evidence: ${EVID}`);
  } finally {
    await browser.close();
  }

  if (failures.length) {
    console.error(`\n${failures.length} judgment(s) failed`);
    process.exitCode = 1;
  }
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
