#!/usr/bin/env node
// e2e-avatar-flicker.cjs — 「默认头像重挂载闪一下」验红钉（2026-09-15 uiflick 批，件①）。
//
// 缺陷形态：头像框是「logo / 照片」双态单槽位。两个消费端
// （activityBar.js renderAvatar、sidebar.js renderSettingsAvatar）都在**同一个
// 同步任务里**先 `photoEl.hidden = false` 再写 `src`，最后 `logoEl.hidden = true`
// ——可见态在照片**可绘制之前**就翻转了。于是任何需要真加载的一遍（重挂载 /
// 缓存未命中回落远端 URL / 未命中→命中的 src 换帧 / 加载失败窗口）都会画出一帧
// 甚至一串「logo 已隐藏 + 照片还没解码」= 空环。数据 URI 热命中时窗口极短，
// 用户"很容易会闪一下"、不容易稳定复现，正是本钉要把它变成**可判读的红态读数**。
//
// 本 harness 自包含打桩：page.route + 从磁盘服务真实前端文件（不起端口、不碰
// 8080、不装依赖；沿用 scripts/e2e-friends-req-error.cjs 同路线）。
//
// 三条断言（都对**图片字节就绪时刻**敏感）：
//   A 热缓存 data URI  → 控制组（改前改后都应为绿：证明钉不是恒红）
//   B 冷缓存 → 远端 URL 直拉（模拟真实网络往返）→ 改前红：空环帧串
//   C 未命中 → 命中的 src 换帧（远端 URL → data URI）→ 改前红
//
// 读数 = ① rAF 逐帧 DOM 中间态（logoHidden && 照片不可绘）② 逐帧像素快照
// （与「纯 logo」/「稳定照片」两张参考帧做字节比对，既非前者也非后者 = 中间态）。
//
// Run（worktree 根）：
//   node scripts/e2e-avatar-flicker.cjs                     # 跑本树（期望绿）
//   N6_WEB=<基线 web 树> node scripts/e2e-avatar-flicker.cjs # 跑基线副本（期望红）
// 可选：N6_OUT=<dir> 落盘逐帧读数 / N6_SHOTS=<dir> 落盘截图 / N6_MATRIX=0 跳过矩阵
const { chromium } = require('playwright');
const { readFileSync, mkdirSync, writeFileSync } = require('node:fs');
const { join, extname, resolve } = require('node:path');
const { deflateSync, inflateSync } = require('node:zlib');

const WEB = process.env.N6_WEB
  ? resolve(process.env.N6_WEB)
  : join(__dirname, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.N6_OUT ? resolve(process.env.N6_OUT) : '';
const SHOTS = process.env.N6_SHOTS ? resolve(process.env.N6_SHOTS) : '';
const LABEL = process.env.N6_LABEL || WEB;
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

// ── 测试头像：随机噪声 PNG（压缩率低 = 解码成本真实；纯色图会掩盖问题）──
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = c ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
/** 256x256 噪声 PNG（品红系，便于与像素图 logo 区分）。 */
function noisePng(size, seed = 7) {
  const raw = Buffer.alloc(size * (size * 3 + 1));
  let s = seed;
  for (let y = 0; y < size; y++) {
    const off = y * (size * 3 + 1);
    raw[off] = 0;
    for (let x = 0; x < size; x++) {
      s = (s * 1103515245 + 12345) & 0x7fffffff;
      raw[off + 1 + x * 3] = 200 + ((s >> 16) & 0x37);
      raw[off + 2 + x * 3] = (s >> 8) & 0x2f;
      raw[off + 3 + x * 3] = 190 + (s & 0x3f);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 1 })), chunk('IEND', Buffer.alloc(0)),
  ]);
}

const AVATAR_PNG = noisePng(256);
const AVATAR_B64 = AVATAR_PNG.toString('base64');
const AVATAR_DATAURL = 'data:image/png;base64,' + AVATAR_B64;
const AVATAR_URL = 'https://avatars.test/a.png';
const LS_CACHE_KEY = 'nebflow_avatar_cache';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 最小 PNG 解码（8bit RGB/RGBA，无隔行）——只为拿真实像素做位置无关的分类 ──
function pngPixels(buf) {
  let off = 8, w = 0, h = 0, colorType = 6, idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      colorType = data[9];
      if (data[8] !== 8 || data[12] !== 0) throw new Error('unsupported PNG (bitDepth/interlace)');
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  const bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : (() => { throw new Error('unsupported colorType ' + colorType); })();
  const raw = inflateSync(Buffer.concat(idat));
  const stride = w * bpp;
  const out = Buffer.alloc(h * stride);
  let pos = 0;
  for (let y = 0; y < h; y++) {
    const f = raw[pos++];
    const row = raw.subarray(pos, pos + stride); pos += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? cur[i - bpp] : 0;
      const b = prev ? prev[i] : 0;
      const c = (prev && i >= bpp) ? prev[i - bpp] : 0;
      let v = row[i];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); }
      cur[i] = v & 0xff;
    }
  }
  return { w, h, bpp, data: out };
}

/**
 * 颜色签名（位置无关）：品红系像素占比（测试头像色）+ 品牌绿像素占比（logo 色）。
 * 判据与裁剪框的像素级抖动解耦——只问「这片区域里有没有照片色 / 有没有 logo 色」。
 */
function signature(buf) {
  const { w, h, bpp, data } = pngPixels(buf);
  let magenta = 0, green = 0, bright = 0, n = w * h;
  for (let i = 0; i < n; i++) {
    const r = data[i * bpp], g = data[i * bpp + 1], b = data[i * bpp + 2];
    const luma = 0.299 * r + 0.587 * g + 0.114 * b;
    if (r > 140 && b > 130 && g < r - 40 && g < b - 20) magenta++;
    else if (g > r + 25 && g > b + 25 && g > 80) green++;
    if (luma > 90) bright++;      // logo 是亮色像素画（退出态被 grayscale 去色，仍然亮）
  }
  return { magenta: magenta / n, green: green / n, bright: bright / n };
}

/** 用签名把每一帧像素快照分成 L（纯 logo）/ P（稳定照片）/ ?（空环=第三态）。 */
function classifyShots(shots, sigLogo, sigPhoto, tag) {
  const tM = (sigLogo.magenta + sigPhoto.magenta) / 2;   // 有照片色 = 照片
  const tB = sigLogo.bright * 0.45;                      // 有亮色像素画 = logo；两者皆无 = 空环
  const seq = shots.map((s) => {
    const sig = signature(s);
    if (sig.magenta > tM) return 'P';
    if (sig.bright > tB) return 'L';
    return '?';
  }).join('');
  if (tag) console.log(`   [sig ${tag}] tM=${tM.toFixed(4)} tB=${tB.toFixed(4)} refL=${JSON.stringify(sigLogo)} refP=${JSON.stringify(sigPhoto)}`);
  return seq;
}

/**
 * 逐帧像素快照的**状态游程**：把连续字节相同的帧折成一段，段与参考帧比对。
 * 这是阈值/位置无关的判据——冷字节路径必然出现三段：
 *   纯 logo（段1） → 空环（段2，既非 logo 亦非照片 = 第三态） → 稳定照片（段3）。
 */
function runs(shots, refLogo, refPhoto) {
  const out = [];
  for (const b of shots) {
    const last = out[out.length - 1];
    if (last && last.buf.equals(b)) { last.len++; continue; }
    out.push({ buf: b, len: 1 });
  }
  return out.map((r) => {
    const sig = signature(r.buf);
    const state = sig.magenta > 0.3 ? 'P' : sig.green >= 0.015 ? 'L' : '?';
    return { len: r.len, state, sig };
  });
}

/** 用签名序列找「L→P 窗口」内的空环帧数（段外 = 面板未挂载/淡入，不算）。 */
function blankWindowBySig(shots, sigLogo, sigPhoto) {
  const seq = classifyShots(shots, sigLogo, sigPhoto);
  const iL = seq.indexOf('L'), iP = seq.indexOf('P');
  if (iP < 0) return { seq, blanks: -1, from: iL, to: iP };
  if (iL < 0 || iL > iP) return { seq, blanks: 0, from: iL, to: iP };
  let blanks = 0;
  for (let i = iL + 1; i < iP; i++) if (seq[i] === '?') blanks++;
  return { seq, blanks, from: iL, to: iP };
}



// ── 页面装配 ─────────────────────────────────────────────
/**
 * @param {import('playwright').Browser} browser
 * @param {{theme?: string, viewport?: {width:number,height:number}, cache?: 'warm'|'cold',
 *          avatarDelayMs?: number, noAvatar?: boolean}} opts
 */
async function newPage(browser, opts = {}) {
  const p = await browser.newPage({ viewport: opts.viewport || { width: 1440, height: 900 } });
  await p.emulateMedia({ colorScheme: opts.theme === 'dark' ? 'dark' : 'light' });
  const pageErrors = [];
  p.on('pageerror', (e) => pageErrors.push(e.message));

  const avatarDelay = opts.avatarDelayMs ?? 250;
  const status = () => ({
    loggedIn: true,
    device: {
      id: 'd1', name: 'Mashiros-MacBook-Pro', platform: 'macos', userDescription: '',
      avatarUrl: opts.noAvatar ? '' : AVATAR_URL,
    },
    peers: [],
  });

  await p.route('**/*', async (route) => {
    const u = new URL(route.request().url());
    const path = u.pathname;
    if (u.host === 'avatars.test') {              // 远端头像源站：模拟网络往返
      if (avatarDelay) await sleep(avatarDelay);
      return route.fulfill({ body: AVATAR_PNG, contentType: 'image/png' });
    }
    if (path.startsWith('/api/')) {
      if (path === '/api/neblink/status') {
        if (opts.statusDelayMs) await sleep(opts.statusDelayMs);
        return route.fulfill({ json: status() });
      }
      if (path === '/api/neblink/avatar') {       // 网关同源代理
        // proxy:'fail' = 缓存建不起来的真实退化条件（avatarCache.js 注释第 4 条
        // 「渐进增强：拉取失败 → 静默放弃本次缓存构建，行为退化到 <img> 直拉现状」）
        if (opts.proxy === 'fail') return route.fulfill({ status: 502, json: { error: 'upstream HTTP 502' } });
        if (avatarDelay) await sleep(avatarDelay);
        return route.fulfill({ body: AVATAR_PNG, contentType: 'image/png' });
      }
      return route.fulfill({ json: {} });
    }
    if (path === '/' || path === '/index.html') {
      return route.fulfill({ body: readFileSync(join(WEB, 'index.html'), 'utf8'), contentType: 'text/html' });
    }
    try {
      const file = join(WEB, decodeURIComponent(path));
      return route.fulfill({ body: readFileSync(file), contentType: MIME[extname(file)] || 'application/octet-stream' });
    } catch { return route.fulfill({ status: 404, body: '' }); }
  });

  await p.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({
      type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done',
      models: [], defaults: {},
    }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await p.addInitScript(([cacheKey, cacheJson, loc]) => {
    try {
      localStorage.setItem('nebflow_token', 't');
      localStorage.setItem('neblink_token', 't');
      localStorage.setItem('nebflow_locale', loc);
      localStorage.setItem('neblink_locale', loc);
      if (cacheJson) localStorage.setItem(cacheKey, cacheJson);
      else localStorage.removeItem(cacheKey);
    } catch { /* ignore */ }
    // 逐帧 DOM 中间态采样（判据：槽位里 logo 已隐藏、照片却还不可绘制）
    // 两个槽位同采：设置对话框头像区 + 活动栏头像（同一 renderAvatar 代码形状）。
    window.__frames = [];
    window.__bootFrames = [];
    const readSlot = (root) => {
      const photo = root && root.querySelector('img[class$="-photo"]');
      const logo = root && root.querySelector('img[class$="-logo"]');
      return {
        mounted: !!root,
        photoHidden: photo ? photo.hidden : null,
        photoReady: photo ? !!(photo.complete && photo.naturalWidth > 0) : null,
        srcKind: photo ? (photo.getAttribute('src') || '').slice(0, 12) : null,
        logoHidden: logo ? logo.hidden : null,
      };
    };
    const tick = () => {
      window.__frames.push(Object.assign({ t: Math.round(performance.now()) }, readSlot(document.getElementById('settings-avatar-entry'))));
      window.__bootFrames.push(Object.assign({ t: Math.round(performance.now()) }, readSlot(document.getElementById('activity-avatar'))));
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }, [LS_CACHE_KEY, opts.cache === 'warm'
    ? JSON.stringify({ url: AVATAR_URL, dataUrl: AVATAR_DATAURL, fetchedAt: Date.now(), loggedIn: true })
    : null, opts.locale || 'zh-CN']);

  await p.goto('http://mock.local/', { waitUntil: 'domcontentloaded' });
  await p.waitForSelector('#settings-btn', { state: 'attached', timeout: 20000 });
  await sleep(700);
  if (!opts.skipOpen) await openPanel(p);               // 打开设置对话框 = 头像区挂载
  await sleep(opts.skipOpen ? 0 : 900);
  return { p, pageErrors };
}

/** 稳定态存档帧：截图头像区（紧贴裁剪，供逐帧像素分类 + 改前改后一致性比对）。 */
async function avatarClip(p) {
  const box = await p.$eval('#settings-avatar-entry', (el) => {
    const r = el.getBoundingClientRect();
    return { x: r.x, y: r.y, width: r.width, height: r.height };
  });
  // 紧贴圆环**内部**裁剪（避开圆环描边与面板底图），使像素分类只问
  //「这片区域里是 logo 画 / 照片 / 都没有」。
  const inset = Math.max(6, Math.round(box.width * 0.18));
  return { x: Math.round(box.x + inset), y: Math.round(box.y + inset), width: Math.round(box.width - inset * 2), height: Math.round(box.height - inset * 2) };
}

const isOpen = (p) => p.evaluate(() => !!document.getElementById('settings-overlay')?.classList.contains('on'));
// 开关面板走 sidebar.js 的导出入口（= activityBar.js 设置按钮点击处理器调用的同两个
// 函数：activityBar.js:310 openSettingsPanel / :304 closeSettingsPanel）。用模块入口而
// 非鼠标点击，是因为面板打开时 #settings-overlay 会拦截 pointer events（关不掉）。
const openPanel = (p) => p.evaluate(() => import('/js/sidebar.js').then((m) => m.openSettingsPanel()));
const closePanel = (p) => p.evaluate(() => import('/js/sidebar.js').then((m) => m.closeSettingsPanel()));

/** 头像区在视口里的裁剪框（需先打开一次设置；随后关掉，供本轮实测复用）。 */
async function calibrationClip(browser, opts = {}) {
  const S = await newPage(browser, { ...opts, cache: 'warm', avatarDelayMs: 0 });
  const clip = await avatarClip(S.p);
  const box = await S.p.evaluate(() => {
    const r = document.getElementById('settings-avatar-entry').getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height };
  });
  await closePanel(S.p);                          // 关掉，保持「从未打开过」的冷状态
  await S.p.close();
  return { clip, box };
}

/**
 * 测「打开对话框」这一遍的逐帧读数 + 逐帧像素快照。
 * 计时段 = 点击前起跑，覆盖整个挂载窗口（照片真加载 / 缓存回落都在这段里）。
 */
async function measureOpen(p, clip, { windowMs = 1200, tag = '' } = {}) {
  const shots = [];
  const deadline = Date.now() + windowMs;
  const loop = (async () => {
    while (Date.now() < deadline) {
      try { shots.push(await p.screenshot({ clip })); } catch { /* page busy */ }
    }
  })();
  await p.evaluate(() => { window.__frames.length = 0; });
  await openPanel(p);                             // 打开设置对话框 = 头像区挂载
  await loop;
  const frames = await p.evaluate(() => window.__frames.slice());
  if (SHOTS) shots.forEach((b, i) => writeFileSync(join(SHOTS, `${tag}shot-${String(i).padStart(3, '0')}.png`), b));
  return { frames, shots };
}

/**
 * 测**登录态首帧**：页面冷启动 → 状态回来 → 活动栏头像槽位由 logo 翻成照片。
 * 这条路径**必然冷**（页面里没有任何更早的渲染替它预热字节），与设置对话框
 * 共用同一 renderAvatar 代码形状（activityBar.js:729-738）。
 */
async function measureBoot(browser, opts = {}) {
  const S = await newPage(browser, { ...opts, skipOpen: true, statusDelayMs: opts.statusDelayMs ?? 1600 });
  const clip = await S.p.evaluate(() => {
    const r = document.getElementById('activity-avatar').getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) };
  });
  const shots = [];
  const deadline = Date.now() + 2600;
  const loop = (async () => {
    while (Date.now() < deadline) {
      try { shots.push(await S.p.screenshot({ clip })); } catch { /* busy */ }
    }
  })();
  await S.p.evaluate(() => { window.__bootFrames.length = 0; });
  await loop;
  const frames = await S.p.evaluate(() => window.__bootFrames.slice());
  await S.p.close();
  const photoframe = frames.find((f) => f.srcKind && f.srcKind.startsWith('http'));
  return { frames, shots, mountedSrcKind: photoframe ? photoframe.srcKind : null, clip };
}

/** 从逐帧读数里抽出「空环帧」：slot 里 logo 已隐藏 且 照片不可绘制 = 用户什么都看不见。 */
const blankFrames = (frames) => frames.filter((f) => f.mounted && f.logoHidden === true && f.photoReady === false);
/** 照片真正就位的帧（未隐藏 + 已解码）。 */
const readyFrames = (frames) => frames.filter((f) => f.mounted && f.photoHidden === false && f.photoReady === true);
/** 逐帧像素分类：L = 纯 logo 参考帧 / P = 稳定照片参考帧 / ? = 第三态（中间态）。 */
const classify = (shots, refLogo, refPhoto) => shots.map((s) => (s.equals(refLogo) ? 'L' : s.equals(refPhoto) ? 'P' : '?')).join('');
/**
 * 计时段 = [第一帧「纯 logo」, 第一帧「稳定照片」]。段内的 '?' = **真中间态**
 * （槽位里既不是 logo 也不是照片 = 空环）；段外的 '?' 是设置面板淡入/未挂载时的
 * 底图，不算数。热缓存时窗口为空（P 先出现）⇒ 恒 0。
 */
function blankWindow(shots, refLogo, refPhoto) {
  const iL = shots.findIndex((s) => s.equals(refLogo));
  const iP = shots.findIndex((s) => s.equals(refPhoto));
  if (iP < 0) return { from: iL, to: iP, blanks: -1, seq: classify(shots, refLogo, refPhoto) };
  if (iL < 0 || iL > iP) return { from: iL, to: iP, blanks: 0, seq: classify(shots, refLogo, refPhoto) };
  let blanks = 0;
  for (let i = iL + 1; i < iP; i++) if (!shots[i].equals(refLogo) && !shots[i].equals(refPhoto)) blanks++;
  return { from: iL, to: iP, blanks, seq: classify(shots, refLogo, refPhoto) };
}

// ── 主流程 ───────────────────────────────────────────────
(async () => {
  const results = [];
  const detail = {};
  const ok = (name, cond, extra) => {
    results.push([name, !!cond]);
    console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}${extra !== undefined ? '  — ' + extra : ''}`);
  };
  const browser = await chromium.launch();
  try {
    console.log(`# e2e-avatar-flicker — web tree: ${LABEL}`);
    console.log(`# avatar fixture: ${AVATAR_PNG.length} bytes PNG(256x256 noise), dataURI=${AVATAR_DATAURL.length} chars`);

    // 参考帧：① 纯 logo（登录但无头像）② 稳定照片（热缓存、照片已就位）
    let refLogo, refPhoto, CLIP;
    {
      const S = await newPage(browser, { noAvatar: true, cache: 'cold' });
      CLIP = await avatarClip(S.p);
      refLogo = await S.p.screenshot({ clip: CLIP });
      console.log(`# clip = ${JSON.stringify(CLIP)}`);
      await S.p.close();
    }
    {
      const S = await newPage(browser, { cache: 'warm', avatarDelayMs: 0 });
      refPhoto = await S.p.screenshot({ clip: CLIP });
      await S.p.close();
    }
    const sigLogo = signature(refLogo), sigPhoto = signature(refPhoto);
    ok('REF 参考帧可判读：纯 logo 帧 ≠ 稳定照片帧（颜色签名可分离）',
      Math.abs(sigLogo.magenta - sigPhoto.magenta) > 0.05 && sigPhoto.magenta > sigLogo.magenta,
      `sigLogo=${JSON.stringify(sigLogo)} sigPhoto=${JSON.stringify(sigPhoto)}`);

    // ── A 控制组：热缓存 data URI，首次打开（钉不该恒红）──
    {
      const S = await newPage(browser, { cache: 'warm', avatarDelayMs: 0, skipOpen: true });
      const { frames, shots } = await measureOpen(S.p, CLIP, { tag: 'A-' });
      const blank = blankFrames(frames);
      const ready = readyFrames(frames);
      detail.A = {
        frames: frames.length, blank: blank.length, ready: ready.length, shots: shots.length,
        window: blankWindowBySig(shots, sigLogo, sigPhoto),
        firstPhotoReadyMs: ready.length ? ready[0].t : null,
      };
      ok('A1 热缓存首开 → 零空环帧（DOM 逐帧）', blank.length === 0, JSON.stringify(detail.A));
      ok('A2 热缓存首开 → 照片就位（无功能回归）', ready.length > 0, `readyFrames=${ready.length}`);
      classifyShots(shots, sigLogo, sigPhoto, 'A');
      ok('A3 热缓存首开 → 像素序列 L→P 窗口内无中间态', detail.A.window.blanks === 0,
        `blanks=${detail.A.window.blanks} seq=${detail.A.window.seq}`);
      await S.p.close();
    }

    // ── B 冷字节路径：缓存建不起来 ⇒ 每遍回落远端 URL 直拉 ──
    // 触发条件 = avatarCache.js 注释第 4 条的退化分支（拉取失败 → 静默放弃缓存
    // 构建，行为退化到 <img> 直拉）；harness 用 /api/neblink/avatar 502 复现。
    {
      // 条件：冷字节仍在飞（活动栏首次请求未落地）时打开对话框 —— 作者口径
      //「打开对话框等操作」的可控复现窗口。
      const S = await newPage(browser, { cache: 'cold', proxy: 'fail', avatarDelayMs: 1500, skipOpen: true });
      const entryState = await S.p.evaluate(() => {
        const raw = localStorage.getItem('nebflow_avatar_cache');
        if (!raw) return null;
        const e = JSON.parse(raw);
        return { url: (e.url || '').slice(0, 24), dataUrlLen: (e.dataUrl || '').length, failedAt: !!e.failedAt };
      });
      const { frames, shots } = await measureOpen(S.p, CLIP, { tag: 'B-' });
      const blank = blankFrames(frames);
      const ready = readyFrames(frames);
      const first = frames.find((f) => f.mounted && f.srcKind);
      detail.B = {
        cacheEntry: entryState,
        mountedSrcKind: first ? first.srcKind : null,
        frames: frames.length, blank: blank.length, ready: ready.length,
        shots: shots.length, window: blankWindowBySig(shots, sigLogo, sigPhoto),
        blankSpanMs: blank.length ? blank[blank.length - 1].t - blank[0].t : 0,
        firstBlank: blank.length ? blank[0] : null,
      };
      ok('B0 退化条件成立：缓存条目存在但 dataUrl 为空（= 永久直拉态）',
        !!entryState && entryState.dataUrlLen === 0 && entryState.failedAt, JSON.stringify(entryState));
      ok('B1 缓存建不起时首开 → 零空环帧', blank.length === 0,
        `blank=${blank.length} span=${detail.B.blankSpanMs}ms src=${detail.B.mountedSrcKind} first=${JSON.stringify(detail.B.firstBlank)}`);
      ok('B2 缓存建不起时首开 → 像素序列 L→P 窗口内无中间态', detail.B.window.blanks === 0,
        `blanks=${detail.B.window.blanks} seq=${detail.B.window.seq}`);
      ok('B3 缓存建不起时首开 → 照片最终就位（无功能回归）', ready.length > 0, `readyFrames=${ready.length}`);

      // ── B4 同页重复开关（此时字节已落地 ⇒ 负对照：窗口关上后不该再红）──
      const seq2 = [];
      for (let i = 0; i < 2; i++) {
        if (await isOpen(S.p)) await closePanel(S.p);
        await sleep(250);
        const r = await measureOpen(S.p, CLIP, { windowMs: 800, tag: `B${i + 2}-` });
        const b = blankFrames(r.frames);
        const w = blankWindowBySig(r.shots, sigLogo, sigPhoto);
        seq2.push({
          open: i + 2, frames: r.frames.length, blank: b.length,
          ready: readyFrames(r.frames).length, windowBlanks: w.blanks, seq: w.seq,
        });
      }
      detail.B4 = seq2;
      console.log('   B4 repeat-open: ' + JSON.stringify(seq2));
      ok('B4 重复开关 → 每一遍都零空环帧', seq2.every((r) => r.blank === 0), JSON.stringify(seq2));
      await S.p.close();
    }

    // ── C 未命中 → 命中的 src 换帧（远端 URL → data URI）──
    {
      const S = await newPage(browser, { cache: 'cold', avatarDelayMs: 250, skipOpen: true });
      // 先开一遍让后台缓存建起来（首见 URL → 网关同源代理 → localStorage），
      // 但错开 3s 轮询窗口，使「src 换帧」落进下面这一遍的计时段。
      await openPanel(S.p);
      await sleep(1400);
      await closePanel(S.p);
      await sleep(150);
      const { frames, shots } = await measureOpen(S.p, CLIP, { tag: 'C-' });
      const built = await S.p.evaluate(() => {
        const raw = localStorage.getItem('nebflow_avatar_cache');
        return raw ? JSON.parse(raw).dataUrl.slice(0, 12) : null;
      });
      const blank = blankFrames(frames);
      const flips = frames.filter((f, i) => i > 0 && f.srcKind !== frames[i - 1].srcKind).map((f) => f.srcKind);
      detail.C = {
        cacheBuilt: built, frames: frames.length, blank: blank.length,
        srcFlips: flips, shots: shots.length, window: blankWindowBySig(shots, sigLogo, sigPhoto),
      };
      ok('C1 缓存已建（未命中→命中路径真实存在）', !!built, `dataUrl=${built}`);
      ok('C2 换帧窗口 → 零空环帧', blank.length === 0,
        `blank=${blank.length} srcFlips=${JSON.stringify(flips)}`);
      ok('C3 换帧窗口 → 像素序列 L→P 窗口内无中间态', detail.C.window.blanks === 0,
        `blanks=${detail.C.window.blanks} seq=${detail.C.window.seq}`);
      await S.p.close();
    }

    // ── F 登录态首帧（活动栏头像槽位；同一代码形状，字节必冷）──
    {
      // 参考帧：活动栏「纯 logo」/「稳定照片」
      let refLogoBoot, refPhotoBoot;
      {
        const S = await newPage(browser, { noAvatar: true, cache: 'cold', statusDelayMs: 0 });
        const c = await S.p.evaluate(() => { const r = document.getElementById('activity-avatar').getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) }; });
        refLogoBoot = await S.p.screenshot({ clip: c });
        await S.p.close();
        const T = await newPage(browser, { cache: 'warm', avatarDelayMs: 0, statusDelayMs: 0 });
        refPhotoBoot = await T.p.screenshot({ clip: c });
        await T.p.close();
      }
      const sigLogoBoot = signature(refLogoBoot), sigPhotoBoot = signature(refPhotoBoot);
      console.log(`   [sig boot] L=${JSON.stringify(sigLogoBoot)} P=${JSON.stringify(sigPhotoBoot)}`);
      for (const [name, o] of [['F0 控制组（热缓存 data URI）', { cache: 'warm', avatarDelayMs: 0 }],
                               ['F1 冷字节（缓存建不起）', { cache: 'cold', proxy: 'fail', avatarDelayMs: 250 }],
                               ['F2 冷字节（缓存空、首见）', { cache: 'cold', avatarDelayMs: 250 }]]) {
        const r = await measureBoot(browser, o);
        const blank = r.frames.filter((f) => f.mounted && f.logoHidden === true && f.photoReady === false);
        const ready = r.frames.filter((f) => f.mounted && f.photoHidden === false && f.photoReady === true);
        const w = blankWindowBySig(r.shots, sigLogoBoot, sigPhotoBoot);
        const rn = runs(r.shots, refLogoBoot, refPhotoBoot);
        // 自包含判据：登录态首帧之后，槽位**任何一段都不许是空的**。
        // 段态：P = 照片色（品红占比高）/ L = logo 画（品牌绿）/ ? = 空环。
        // 首段允许为 ?（登录态尚未到达前的占位底图——修前修后一致，非本缺陷）。
        console.log(`   ${name.slice(0, 2)} pixel runs: ${rn.map((r) => `${r.len}${r.state}`).join(' ')}`);
        detail[name.slice(0, 2)] = {
          mountedSrcKind: r.mountedSrcKind, frames: r.frames.length, blank: blank.length,
          blankSpanMs: blank.length ? blank[blank.length - 1].t - blank[0].t : 0,
          ready: ready.length, shots: r.shots.length, windowBlanks: w.blanks, seq: w.seq,
          runs: rn.map((r) => `${r.len}${r.state}`),
        };
        ok(`${name} 首帧 → 槽位零空环帧（DOM 逐帧）`, blank.length === 0,
          JSON.stringify({ blank: blank.length, spanMs: detail[name.slice(0, 2)].blankSpanMs, src: r.mountedSrcKind }));
        const emptyRuns = rn.filter((r, i) => i > 0 && r.state === '?');
        ok(`${name} 首帧 → 像素游程无「空环」段（首段占位除外）`, emptyRuns.length === 0,
          `runs=${rn.map((r) => `${r.len}${r.state}`).join(' ')}`);
        ok(`${name} 首帧 → 照片最终就位（无功能回归）`, ready.length > 0, `readyFrames=${ready.length}`);
      }
    }

    // ── G 件② 设备描述提示文案：渲染位读数（dropbox.js:147 的 placeholder）──
    {
      const expect = {
        'zh-CN': '此描述对 Agent 可见，Agent 会根据你的描述自动选择合适的设备执行任务',
        en: 'Visible to agents — they use this description to automatically pick the right device for a task',
      };
      for (const loc of ['zh-CN', 'en']) {
        const S = await newPage(browser, { locale: loc, cache: 'cold', statusDelayMs: 0 });
        const seen = await S.p.evaluate(async () => {
          const m = await import('/js/dropbox.js');
          m.openDropbox({ deviceId: 'd1', deviceName: 'dev', platform: 'macos', userDescription: '', isLocal: true });
          const ta = document.getElementById('dropbox-desc-input');
          return { placeholder: ta ? ta.getAttribute('placeholder') : null, key: 'neblink.deviceDescHint' };
        });
        detail['G-' + loc] = seen;
        ok(`G 件② ${loc} → placeholder 渲染为作者给定语义`, seen.placeholder === expect[loc],
          `${JSON.stringify(seen.placeholder)}`);
        await S.p.close();
      }
    }

    // ── D 零视觉变化：稳定态与「纯 logo」参考帧逐字节一致（无头像时）──
    {
      const S = await newPage(browser, { noAvatar: true, cache: 'cold' });
      await sleep(500);
      const shot = await S.p.screenshot({ clip: CLIP });
      ok('D1 无头像稳态 = 纯 logo（改前改后像素一致）', shot.equals(refLogo), `same=${shot.equals(refLogo)}`);
      await S.p.close();
    }

    // ── S 零视觉变化留痕：稳定态逐面落盘（N6_STABLE=<dir>），供改前/改后逐字节比对 ──
    if (process.env.N6_STABLE) {
      mkdirSync(process.env.N6_STABLE, { recursive: true });
      for (const theme of ['light', 'dark']) {
        for (const vp of [{ width: 1440, height: 900 }, { width: 1024, height: 768 }, { width: 800, height: 600 }]) {
          const tagv = `${theme}-${vp.width}x${vp.height}`;
          for (const [tag, opts] of [['withavatar', { cache: 'warm', avatarDelayMs: 0 }], ['noavatar', { noAvatar: true, cache: 'cold' }]]) {
            const S = await newPage(browser, { ...opts, theme, viewport: vp });
            await sleep(600);
            const clip = await avatarClip(S.p);
            await S.p.screenshot({ path: join(process.env.N6_STABLE, `settings-${tag}-${tagv}.png`), clip });
            console.log(`   stable clip ${tag}-${tagv}: ${JSON.stringify(clip)}`);
            await S.p.screenshot({ path: join(process.env.N6_STABLE, `panel-${tag}-${tagv}.png`) });
            await S.p.close();
          }
          const A = await newPage(browser, { cache: 'warm', avatarDelayMs: 0, theme, viewport: vp, skipOpen: true });
          await sleep(900);
          await A.p.screenshot({ path: join(process.env.N6_STABLE, `activity-${tagv}.png`) });
          await A.p.close();
        }
      }
      console.log('   stable dumps → ' + process.env.N6_STABLE);
    }

    // ── E 亮/暗 × 三视口矩阵（冷缓存首开）──
    if (process.env.N6_MATRIX !== '0') {
      const matrix = [];
      for (const theme of ['light', 'dark']) {
        for (const vp of [{ width: 1440, height: 900 }, { width: 1024, height: 768 }, { width: 800, height: 600 }]) {
          const cal = await calibrationClip(browser, { theme, viewport: vp });
          const S = await newPage(browser, { theme, viewport: vp, cache: 'cold', proxy: 'fail', avatarDelayMs: 1500, skipOpen: true });
          const { frames, shots } = await measureOpen(S.p, cal.clip, { tag: 'E-', windowMs: 1000 });
          const blank = blankFrames(frames);
          const ready = readyFrames(frames);
          const w = blankWindowBySig(shots, sigLogo, sigPhoto);
          const row = { theme, vp: `${vp.width}x${vp.height}`, frames: frames.length, blank: blank.length, ready: ready.length, windowBlanks: w.blanks };
          matrix.push(row);
          console.log(`   matrix ${row.theme}/${row.vp}: frames=${row.frames} blank=${row.blank} ready=${row.ready}`);
          await S.p.close();
        }
      }
      detail.matrix = matrix;
      ok('E1 亮/暗 × 三视口：全格零空环帧', matrix.every((r) => r.blank === 0), JSON.stringify(matrix));
      ok('E2 亮/暗 × 三视口：全格照片就位', matrix.every((r) => r.ready > 0), '');
    }
  } finally {
    await browser.close();
  }

  const failed = results.filter(([, c]) => !c);
  console.log(`\n== ${LABEL} ==  ${results.length - failed.length}/${results.length} PASS`);
  if (failed.length) console.log('RED: ' + failed.map(([n]) => n).join(' | '));
  if (OUT) {
    mkdirSync(OUT, { recursive: true });
    const f = join(OUT, `flicker-${LABEL.replace(/[^a-z0-9]+/gi, '_').slice(-40)}.json`);
    writeFileSync(f, JSON.stringify({ label: LABEL, web: WEB, pass: results.length - failed.length, total: results.length, results, detail }, null, 2));
    console.log('wrote ' + f);
  }
  process.exit(failed.length ? 1 : 0);
})();
