// mobile-download-save.spec.mjs — 手机端「下载落点」批验收（mobile-download 批 ·
// 2026-11-18 作者令逐字「手机端的下载我不知道存哪去了 能不能直接存进相册（图片视频的
// 话）或者指定下载目录」）。
//
// 覆盖（standalone runner；`node tests/mobile-download-save.spec.mjs`，双引擎默认）：
//   D1 手机层 + 分享能力可用（navigator.canShare({files})=true）+ 图片 →
//      `navigator.share({files})` 被调用，且**文件句柄参数正确**（名/类型/字节长）
//   D2 手机层 + 分享能力**不可用** → 保留原有 `<a download>`（真实 download 事件 +
//      文件名）+ 落点提示通知（可发现性兜底）
//   D3 手机层 + 非媒体（pdf）→ share 携带 `application/pdf`（=「存储到文件」可指定位置）
//   D4 桌面形态（1280）→ **零触碰**：share 零调用、原 `<a download>`、零通知
//   D5 分享面板取消（AbortError）→ route='cancelled'：零回落下载、零通知（尊重取消）
//   D6 分享调用被拒（NotAllowedError，用户手势窗口过期）→ 回落原下载（可见降级，不静默）
//      + 落点提示
//   D7 真实 UI 集成：手机层画廊「下载」键 → 经落点单点（share 参数 = 真字节）
//   D8 相册档判据（图片/视频 = 相册档；其它 = 非）
//
// 诚实边界：相册写入与系统分享面板本身是 **OS 行为**——本 spec 只能断言「在正确时机、
// 用正确的文件（名/类型/字节）调用了分享接口」；「照片 App 里真出现了」需**真机亲验**。
//
// 环境变量：ENGINE（chromium|webkit|chromium+webkit）、SHOTS_DIR、HEADLESS=0。

import { chromium, webkit } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mds-root-session';
const ENGINES = (process.env.ENGINE || 'chromium+webkit').split('+');
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const HEADLESS = process.env.HEADLESS !== '0';

// 确定性字节（本 spec 只断言「句柄/类型/字节长」，不断言图像解码）。
const PNG_BYTES = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82];
const PDF_BYTES = Array.from(Buffer.from('%PDF-1.7\n1 0 obj\n<<>>\nendobj\n', 'utf8'));

const ALBUM_TOAST = '已打开系统分享面板 · 选「存储到照片」即入相册';
const FOLDER_TOAST = '已打开系统分享面板 · 选「存储到文件」可指定保存位置';
const LANDING_TEXTS = ['已下载 · iPhone 上存于「文件」App ›「下载」', '已下载到浏览器下载目录'];

let failures = 0;
let checks = 0;
function ok(name, cond, detail = '') {
  checks += 1;
  if (cond) { console.log(`  PASS  ${name}`); return true; }
  failures += 1;
  console.log(`  FAIL  ${name}${detail ? '  — ' + detail : ''}`);
  return false;
}

/** 分享能力桩（addInitScript）：装置 = window.__nfShare。
 *  mode    'files' | 'none'（canShare 返回 false）
 *  reject  '' | 'AbortError' | 'NotAllowedError'（share 抛出该名错误）
 *  calls   每次 share 的读数；canShare 每次 canShare 的读数。
 *  这是**桩**——真机 = 系统分享面板；桩只能验「调用了 + 参数对」。 */
const SHARE_STUB = () => {
  window.__nfShare = { mode: 'files', reject: '', calls: [], canShare: [] };
  Object.defineProperty(navigator, 'canShare', {
    configurable: true,
    value: (data) => {
      window.__nfShare.canShare.push({ hasFiles: !!(data && data.files), count: data && data.files ? data.files.length : 0 });
      return window.__nfShare.mode === 'files';
    },
  });
  Object.defineProperty(navigator, 'share', {
    configurable: true,
    value: async (data) => {
      const f = data && data.files && data.files[0];
      window.__nfShare.calls.push({
        count: data && data.files ? data.files.length : 0,
        name: f && f.name, type: f && f.type, size: f && f.size,
        isFile: f instanceof File,
      });
      if (window.__nfShare.reject) { const e = new Error('stub share rejected'); e.name = window.__nfShare.reject; throw e; }
    },
  });
};

const INIT = (bytes) => {
  window.__mdsBytes = bytes;
  localStorage.setItem('nebflow_token', 'e2e-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_sw', '0');
  Object.defineProperty(navigator.serviceWorker, 'register', {
    value: () => new Promise(() => { /* 永不决 = 注册面静默空转 */ }), configurable: true,
  });
};

/** 一次 boot：route 静态树 + /api 兜底 + 票 mock + WS 夹具（空历史）。 */
async function boot(page) {
  await page.addInitScript(INIT, PNG_BYTES.slice());
  await page.addInitScript(SHARE_STUB);
  await page.route('**/*', (route) => {
    const raw = route.request().url();
    // WebKit 会把页内 `fetch(blob:)` 暴露给 page.route（URL 仍是 `blob:http://…/<uuid>`）；
    // 拦下它会把 blob 内容替换成 mock 应答（实测 size 被改写）——非 http(s) 一律放行。
    if (!/^https?:/i.test(raw)) return route.continue();
    let p = decodeURIComponent(new URL(raw).pathname);
    if (p === '/') p = '/index.html';
    // 身份面最小夹具（mobile-dock.spec 同款）：缺它 = pageerror 噪声 404。
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p.startsWith('/api/nf-file')) {
        return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: Buffer.from(PNG_BYTES) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      if (process.env.MDS_DEBUG) console.log('  404 →', p);
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  // 惰性原点（无监听）：静态面与 /api 全走 page.route。**端口不能取 1**——WebKit 对
  // 低端口直接拒连（不派 route），实测 `localhost:1` goto 超时；取 WebKit 安全带内的端口。
  await page.goto('http://localhost:8917/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
}

/** 调用落点单点（模块级驱动；与真实调用方同序：saveFileBlob → notifyLanding）。
 *  模块缺失/加载失败 ⇒ 返回 `error` 而非抛出——验红面要给**逐条 FAIL 读数**，
 *  不是一句未捕获异常（修前树直跑即靠此报红）。 */
async function callSave(page, mime, name, bytes) {
  return page.evaluate(async ({ mimeArg, nameArg, bytesArg }) => {
    const empty = { res: { route: '__module-missing__', album: false }, calls: [], canShare: [] };
    try {
      const mod = await import('/js/downloadSave.js');
      window.__nfShare.calls.length = 0;
      window.__nfShare.canShare.length = 0;
      const blob = new Blob([new Uint8Array(bytesArg)], { type: mimeArg });
      const res = await mod.saveFileBlob(blob, nameArg);
      mod.notifyLanding(res);
      return { res, calls: window.__nfShare.calls, canShare: window.__nfShare.canShare };
    } catch (e) { return { ...empty, error: String(e) }; }
  }, { mimeArg: mime, nameArg: name, bytesArg: bytes });
}

/** 通知栈读数（落点提示）：最后一条 toast 的类型 + 文案。 */
async function lastToast(page) {
  return page.evaluate(() => {
    const toasts = Array.from(document.querySelectorAll('.nebflow-toast'));
    const t = toasts[toasts.length - 1];
    if (!t) return null;
    return { cls: t.className, text: t.querySelector('.nebflow-toast-msg')?.textContent || '' };
  });
}

/** 短窗内是否出现 download 事件（用于「零回落下载」断言）。 */
async function downloadFires(page, ms) {
  return page.waitForEvent('download', { timeout: ms }).then(() => true).catch(() => false);
}

async function runMobileChecks(engineName, page) {
  // ── D8 相册档判据（纯函数）────────────────────────────────────────────
  {
    const r = await page.evaluate(async () => {
      try {
        const mod = await import('/js/downloadSave.js');
        return {
          png: mod.isAlbumKind('a.png', 'image/png'),
          mp4: mod.isAlbumKind('v.mp4', 'video/mp4'),
          jpgNoMime: mod.isAlbumKind('photo.jpg', ''),
          pdf: mod.isAlbumKind('r.pdf', 'application/pdf'),
          zip: mod.isAlbumKind('a.zip', 'application/zip'),
        };
      } catch (e) { return { error: String(e) }; }
    });
    ok(`${engineName} D8 相册档：png/mp4/jpg(扩展名腿)=true`, r.png && r.mp4 && r.jpgNoMime, JSON.stringify(r));
    ok(`${engineName} D8 相册档：pdf/zip=false`, !r.pdf && !r.zip, JSON.stringify(r));
  }
  // ── D1 图片 → share（参数读数）────────────────────────────────────────
  {
    const r = await callSave(page, 'image/png', 'photo.png', PNG_BYTES);
    const c = r.calls[0] || {};
    ok(`${engineName} D1 route='shared'（非下载腿）`, r.res.route === 'shared', JSON.stringify(r.res));
    ok(`${engineName} D1 album=true（图片=相册档）`, r.res.album === true);
    ok(`${engineName} D1 share 恰调用 1 次`, r.calls.length === 1, `calls=${r.calls.length}`);
    ok(`${engineName} D1 share 载 1 件 File`, c.isFile === true && c.count === 1, JSON.stringify(c));
    ok(`${engineName} D1 文件名读数 = photo.png`, c.name === 'photo.png', String(c.name));
    ok(`${engineName} D1 MIME 读数 = image/png`, c.type === 'image/png', String(c.type));
    ok(`${engineName} D1 字节长读数 = ${PNG_BYTES.length}`, c.size === PNG_BYTES.length, String(c.size));
    ok(`${engineName} D1 canShare 以 {files:[1]} 探测`, r.canShare[0] && r.canShare[0].hasFiles === true && r.canShare[0].count === 1, JSON.stringify(r.canShare));
    const toast = await lastToast(page);
    ok(`${engineName} D1 落点提示=「分享→相册」文案`, !!toast && toast.text === ALBUM_TOAST, toast ? toast.text : 'no toast');
    const noDl = await downloadFires(page, 500);
    ok(`${engineName} D1 share 腿零 <a download> 回落`, noDl === false);
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile-375-d1-album-toast.png`) });
  }
  // ── D3 非媒体（pdf）→ share（「存储到文件」= 可指定位置）───────────────
  {
    const r = await callSave(page, 'application/pdf', 'report.pdf', PDF_BYTES);
    const c = r.calls[0] || {};
    ok(`${engineName} D3 route='shared'`, r.res.route === 'shared', JSON.stringify(r.res));
    ok(`${engineName} D3 album=false（pdf 非相册档）`, r.res.album === false);
    ok(`${engineName} D3 MIME 读数 = application/pdf`, c.type === 'application/pdf', String(c.type));
    ok(`${engineName} D3 字节长读数 = ${PDF_BYTES.length}`, c.size === PDF_BYTES.length, String(c.size));
    const toast = await lastToast(page);
    ok(`${engineName} D3 落点提示=「分享→存储到文件」文案`, !!toast && toast.text === FOLDER_TOAST, toast ? toast.text : 'no toast');
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile-375-d3-folder-toast.png`) });
  }
  // ── D2 分享能力不可用 → 原 <a download> + 落点提示 ────────────────────
  {
    await page.evaluate(() => { window.__nfShare.mode = 'none'; });
    const dlPromise = page.waitForEvent('download', { timeout: 8000 }).catch(() => null);
    const r = await callSave(page, 'image/png', 'fallback.png', PNG_BYTES);
    const dl = await dlPromise;
    ok(`${engineName} D2 route='downloaded'（回落原行为）`, r.res.route === 'downloaded', JSON.stringify(r.res));
    ok(`${engineName} D2 share 零调用（能力不支持）`, r.calls.length === 0, `calls=${r.calls.length}`);
    ok(`${engineName} D2 真实 download 事件 + 文件名 fallback.png`, !!dl && dl.suggestedFilename() === 'fallback.png', dl ? dl.suggestedFilename() : 'no download event');
    const toast = await lastToast(page);
    ok(`${engineName} D2 落点提示在场（可发现性兜底）`, !!toast && LANDING_TEXTS.includes(toast.text), toast ? toast.text : 'no toast');
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile-375-d2-fallback-toast.png`) });
    await page.evaluate(() => { window.__nfShare.mode = 'files'; });
  }
  // ── D5 分享面板取消（AbortError）→ cancelled：零回落、零提示 ───────────
  {
    await page.evaluate(() => { window.__nfShare.reject = 'AbortError'; document.querySelectorAll('.nebflow-toast').forEach((n) => n.remove()); });
    const r = await callSave(page, 'image/png', 'cancelled.png', PNG_BYTES);
    ok(`${engineName} D5 route='cancelled'`, r.res.route === 'cancelled', JSON.stringify(r.res));
    const noDl = await downloadFires(page, 700);
    ok(`${engineName} D5 取消腿零回落下载`, noDl === false);
    const toast = await lastToast(page);
    ok(`${engineName} D5 取消腿零通知（尊重用户取消）`, toast === null, toast ? toast.text : '');
    await page.evaluate(() => { window.__nfShare.reject = ''; });
  }
  // ── D6 分享调用被拒（NotAllowedError）→ 回落原下载 + 提示 ──────────────
  {
    await page.evaluate(() => { window.__nfShare.reject = 'NotAllowedError'; document.querySelectorAll('.nebflow-toast').forEach((n) => n.remove()); });
    const dlPromise = page.waitForEvent('download', { timeout: 8000 }).catch(() => null);
    const r = await callSave(page, 'image/png', 'denied.png', PNG_BYTES);
    const dl = await dlPromise;
    ok(`${engineName} D6 route='downloaded'（可见降级，不静默）`, r.res.route === 'downloaded', JSON.stringify(r.res));
    ok(`${engineName} D6 回落 download 事件 + 文件名 denied.png`, !!dl && dl.suggestedFilename() === 'denied.png', dl ? dl.suggestedFilename() : 'no download event');
    const toast = await lastToast(page);
    ok(`${engineName} D6 落点提示在场`, !!toast && LANDING_TEXTS.includes(toast.text), toast ? toast.text : 'no toast');
    await page.evaluate(() => { window.__nfShare.reject = ''; });
  }
  // ── D7 真实 UI 集成：画廊「下载」键 → 落点单点（真字节）───────────────
  {
    await page.evaluate(() => { document.querySelectorAll('.nebflow-toast').forEach((n) => n.remove()); });
    const opened = await page.evaluate(async () => {
      const lb = await import('/js/lightbox.js');
      const blob = new Blob([new Uint8Array(window.__mdsBytes)], { type: 'image/png' });
      window.__nfShare.calls.length = 0;
      return lb.openImageGallery([{ kind: 'image', name: 'gallery-pic.png', blob }], 0);
    }).catch((e) => String(e));
    ok(`${engineName} D7 画廊可开（openImageGallery=true）`, opened === true, String(opened));
    await page.waitForFunction(() => document.querySelector('.nf-lightbox')?.classList.contains('on'), null, { timeout: 8000 }).catch(() => {});
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile-375-d7-gallery.png`) });
    await page.locator('.nf-lightbox .nf-lightbox-opentab').click();
    await page.waitForFunction(() => (window.__nfShare.calls || []).length > 0, null, { timeout: 8000 }).catch(() => {});
    const r = await page.evaluate(() => ({
      calls: window.__nfShare.calls || [],
      status: document.querySelector('.nf-lightbox-status')?.textContent || '',
      toast: document.querySelector('.nebflow-toast .nebflow-toast-msg')?.textContent || '',
    }));
    const c = r.calls[0] || {};
    ok(`${engineName} D7 画廊下载键触发 share（真 UI 集成）`, r.calls.length === 1, `calls=${r.calls.length}`);
    ok(`${engineName} D7 share 参数 = 画廊件（名/类型/字节长）`,
      c.name === 'gallery-pic.png' && c.type === 'image/png' && c.size === PNG_BYTES.length, JSON.stringify(c));
    ok(`${engineName} D7 落点提示随真实 UI 腿出现`, r.toast === ALBUM_TOAST, JSON.stringify(r.toast));
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, `${engineName}-mobile-375-d7-shared-toast.png`) });
  }
}

async function runDesktopChecks(engineName, browser) {
  console.log(`\n── ${engineName} 桌面 1280×900（零触碰腿）──────────────────`);
  const dctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark', acceptDownloads: true, locale: 'zh-CN' });
  const dpage = await dctx.newPage();
  const derrs = [];
  dpage.on('console', (m) => { if (m.type() === 'error') derrs.push(m.text()); });
  dpage.on('pageerror', (e) => derrs.push('pageerror: ' + e.message));
  await boot(dpage);
  const isMobile = await dpage.evaluate(() => document.body.classList.contains('mobile-layer'));
  ok(`${engineName} D4 桌面形态未激活手机层`, isMobile === false);
  const dlPromise = dpage.waitForEvent('download', { timeout: 8000 }).catch(() => null);
  const r = await callSave(dpage, 'image/png', 'desktop.png', PNG_BYTES);
  const dl = await dlPromise;
  ok(`${engineName} D4 桌面 share 零调用（零触碰）`, r.calls.length === 0, `calls=${r.calls.length}`);
  ok(`${engineName} D4 桌面仍走 <a download>（真实 download 事件）`, !!dl && dl.suggestedFilename() === 'desktop.png', dl ? dl.suggestedFilename() : 'no download event');
  const toast = await lastToast(dpage);
  ok(`${engineName} D4 桌面零通知（零触碰）`, toast === null, toast ? toast.text : '');
  ok(`${engineName} D4 桌面零 console error`, derrs.length === 0, derrs.slice(0, 3).join(' | '));
  if (SHOTS_DIR) await dpage.screenshot({ path: join(SHOTS_DIR, `${engineName}-desktop-1280-d4.png`) });
  await dctx.close();
}

async function runEngine(engineName, engine) {
  const browser = await engine.launch({ headless: HEADLESS });
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: 'dark',
    acceptDownloads: true, locale: 'zh-CN', deviceScaleFactor: 2,
  });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

  console.log(`\n── ${engineName} 手机层 375×812 ───────────────────────────`);
  await boot(page);
  await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
  ok(`${engineName} 手机层已激活（body.mobile-layer）`, true);

  await runMobileChecks(engineName, page);

  const errs = consoleErrors.splice(0);
  ok(`${engineName} 手机层零 console error / pageerror`, errs.length === 0, errs.slice(0, 3).join(' | '));

  await runDesktopChecks(engineName, browser);

  await browser.close();
}

if (SHOTS_DIR) mkdirSync(SHOTS_DIR, { recursive: true });

for (const engineName of ENGINES) {
  const engine = engineName === 'webkit' ? webkit : chromium;
  await runEngine(engineName, engine);
}

console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILURES'}  (checks=${checks})`);
process.exit(failures === 0 ? 0 : 1);
