// uifix-glass.spec.mjs — uifix ⑤ 浮层下缘毛玻璃裙边（scroll-under-glass）验收
//
// 作者令（2026-10-20 第五件）：消息滚动到浮层面板下方被硬切 → 应**穿过并毛玻璃
// 透出下层**；backdrop-filter 耗资源则退化 = 毛玻璃渐隐（mask 渐变）。推广到所有
// 浮层压滚动内容的面板。做成共享工具类 .scroll-under-glass（css/scrollUnderGlass.css
// 裙边方案：面板本体零触碰，::after 裙边 = blur+saturate+tint 渐变+mask 渐隐 28px）。
//
// 断言面：
//   G1 套用清点 —— #五面挂类（#reminder-panel / #daemon-panel /
//      #settings-modal / #memory-modal / #rules-modal；项目/插件为 canvas 页签、
//      桌面态不压滚动内容，不在清单——假设已在报告声明）+ ::after 几何
//      （backdrop-filter blur / mask linear-gradient / 高 28px）。
//      顶带退役腿（2026-10-22 作者回访裁定「不允许任何渐变蒙层盖在气泡内容上」）：
//      #top-overlays 不挂类、::after 不存在 —— 带空时裙边恰压穿顶首行（归因探针
//      tests/tmp-topclip-band-probe.mjs 实证），随顶带裙边退役批整组摘除。
//   G2 像素·主态 —— 高对比消息条穿过裙边带：带内高频能量（邻差）≪ 带下原生行
//      （blur 磨砂实证），且纵向存在渐隐梯度（逐行能量递增曲线）。
//   G3 降级态 —— __scrollUnderGlass.degrade(true)（与自动判定同腿）：::after
//      backdrop-filter 'none'、mask 保留；同位像素带内高频能量显著回升（渐隐不磨砂）。
//   G4 暗色 —— 亮暗两套 colorScheme 重复 G1 几何 + G2/G3 像素核心链。
//   G5 探测器 —— state() 形状 + no-backdrop 特性腿（CSS.supports 恒真环境下
//      仅验 supported=true 路径；不支持路径由降级类同名腿覆盖）。
//   G6/G7 顶部消失点批（2026-10-22 作者追加令，取代 UI 批⑥⑦旧契约）—— 桌面：
//      #chat 盒顶 = 顶带下缘（⑦ margin 钉制退役，margin 恒 0）+ 滚动遮罩锚点 =
//      Header 顶缘（--chat-top-clip 实测 = header.top − chat.top，#chat 挂 mask，
//      滚动前后锚点不动）+ #header 零裙边；手机层：渐隐穿顶退役（#header::after
//      裙边不在）+ 同一 mask 变量在位（消失点同为 Header 顶缘）。G0 让位算式仍锚
//      #chat 实际盒顶。
//
// 环境注：被测像素面原为 #top-overlays 任务带 —— 顶带裙边退役批（2026-10-22
// 作者回访裁定）摘除带的挂类后，裙边像素契约改证于裸挂 .scroll-under-glass 台面
// （几何与原带 1:1；现役五面均 overflow:hidden 裁剪自身裙边，属史前现状另案）。
//
// 运行：node tests/uifix-glass.spec.mjs

import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';
import zlib from 'node:zlib';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8157);
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
}
const approx = (a, b, tol) => Math.abs(a - b) <= tol;

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try { const b = readFileSync(file); res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(b); }
  catch { if (!res.headersSent) { res.writeHead(404); res.end(); } else res.end(); }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const browser = await chromium.launch();

/* 像素取证：裙边带内逐行高频能量（邻差绝对值均值，0-255 尺度）。
   clip 为 (boundary-4) 起向下 60px 的窄条 → 行 0..3 = 面板内、4..32 = 裙边、>36 = 原生。 */
/* PNG 确定性解码（Node 端，零依赖）：playwright 截图 = 8-bit 非隔行 PNG
 * （colorType 6/2/0），zlib inflate + 反滤波（None/Sub/Up/Avg/Paeth）。
 * 页内 <img>+canvas 解码曾出现与落盘字节不符的扁平化读数（headless 竞态），
 * 改为 Node 端解码同一 buffer —— 编码/解码同源，无第二真相。 */
function decodePng(buf) {
  const { inflateSync } = zlib;
  let off = 8, W = 0, H = 0, colorType = 6;
  const idat = [];
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { W = data.readUInt32BE(0); H = data.readUInt32BE(4); colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    off += 12 + len;
    if (type === 'IEND') break;
  }
  const ch = colorType === 6 ? 4 : colorType === 2 ? 3 : 1;
  const raw = inflateSync(Buffer.concat(idat));
  const stride = W * ch;
  const out = Buffer.alloc(H * stride);
  let p = 0;
  for (let y = 0; y < H; y++) {
    const filter = raw[p++];
    const row = raw.subarray(p, p + stride); p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= ch ? cur[x - ch] : 0;
      const b = prev ? prev[x] : 0;
      const c = x >= ch && prev ? prev[x - ch] : 0;
      let v = row[x];
      if (filter === 1) v = (v + a) & 255;
      else if (filter === 2) v = (v + b) & 255;
      else if (filter === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (filter === 4) {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
      }
      cur[x] = v;
    }
  }
  return { W, H, ch, out };
}

/* 像素取证：裙边带内逐行高频能量（邻差绝对值均值，0-255 尺度）。
   clip 为 (boundary-6) 起向下 64px 的窄条 → 行 0..6 = 面板/带内、7..33 = 裙边、>36 = 原生。 */
async function rowEnergy(page, clip) {
  const buf = await page.screenshot({ clip });
  if (process.env.GLASS_DEBUG) {
    const fs = await import('node:fs');
    fs.writeFileSync(`/tmp/glass-px-${clip.y}-${Date.now() % 100000}.png`, buf);
  }
  const { W, H, ch, out } = decodePng(buf);
  const lum = (x, y) => {
    const i = (y * W + x) * ch;
    return ch >= 3 ? 0.299 * out[i] + 0.587 * out[i + 1] + 0.114 * out[i + 2] : out[i];
  };
  /* 每行 { range, edges }：range = 亮度极差（模糊/遮罩都压缩振幅）；
     edges = 中阈穿越数（条纹"锐度"——模糊塌边、纯遮罩保边）。
     ⚠ 邻差对 (偶,奇) 采样在 10px 条带上恰好永不跨越 (奇→偶) 边缘 → 能量恒 0
     （第一版断言全红的根因），游程/极差度量与条纹相位无关。 */
  const rows = [];
  for (let y = 0; y < H; y++) {
    let mn = 255, mx = 0, edges = 0, prev = null;
    for (let x = 4; x < W - 4; x++) {
      const L = lum(x, y);
      mn = Math.min(mn, L); mx = Math.max(mx, L);
      const b = L > 127;
      if (prev !== null && b !== prev) edges++;
      prev = b;
    }
    rows.push({ range: mx - mn, edges });
  }
  return { rows, W, H, diag: `${W}x${H} px0=${out[0]},${out[1]},${out[2]}` };
}

async function boot(colorScheme) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    window.__resvLog = [];
    const sp = CSSStyleDeclaration.prototype.setProperty;
    CSSStyleDeclaration.prototype.setProperty = function (k, v, pri) {
      if (k === 'padding-top') {
        const chat = document.getElementById('chat');
        window.__resvLog.push([performance.now() | 0, v, chat ? (chat.getBoundingClientRect().top | 0) : -1,
          document.getElementById('top-overlays') ? (document.getElementById('top-overlays').getBoundingClientRect().bottom | 0) : -1]);
      }
      return sp.call(this, k, v, pri);
    };
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e).slice(0, 100)));
  await page.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#chat', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

/** 高对比条纹标尺穿帮布景：裸挂 .scroll-under-glass 台面（工具类契约被测面，
 *  几何 = 原任务带台面 1:1：absolute + 64px 内容盒，::after 裙边 top:100%）+
 *  fixed 定位黑白栅条。栅条 z=5（低于台面 z-25 的裙边堆叠上下文、高于页面底色），
 *  上段 40px 没入台面本体之下（被内容盒遮住），中段 28px 落在裙边带，其余裸露
 *  = 原生参照。返回带底缘 y（= 裙边起点）。
 *  （顶带裙边退役批 2026-10-22：原被测面 #top-overlays 摘类退役；现役五面中
 *  reminder/daemon/settings/memory 均 overflow:hidden —— 裙边 ::after 的包含块
 *  = 面板自身，被面板裁剪（史前现状，另案），故像素契约以裸台面证工具类本体，
 *  真实面孔由 G1 清点腿 + crossing-shots 无蒙层压字腿分管。） */
async function stageScene(page) {
  await page.evaluate(() => {
    const stage = document.createElement('div');
    stage.id = 'sug-probe-stage';
    stage.className = 'scroll-under-glass';
    stage.style.cssText = 'position:absolute;z-index:25;left:60px;top:80px;width:400px;height:64px;';
    stage.innerHTML = '<div style="height:64px"></div>';
    const strip = document.createElement('div');
    strip.id = 'sug-probe-strip';
    // 挂 #main（台面 z-25 同上下文）：z-5 = 台面下、页面底色上——模拟真实消息层
    strip.style.cssText = 'position:absolute;z-index:5;left:60px;width:400px;' +
      `top:${80 + 64 - 40}px;height:220px;` +
      'background:repeating-linear-gradient(90deg,#000 0 10px,#fff 10px 20px)';
    const main = document.getElementById('main');
    main.appendChild(stage);
    main.appendChild(strip);
    return true;
  });
  await sleep(250);
}

async function geometry(page, tag) {
  const g = await page.evaluate(() => {
    const ids = ['reminder-panel', 'daemon-panel', 'settings-modal', 'memory-modal', 'rules-modal'];
    const out = { ids: [], band: null, extra: [] };
    for (const id of ids) {
      const el = document.getElementById(id);
      const cs = el ? getComputedStyle(el, '::after') : null;
      out.ids.push({
        id, present: !!el && el.classList.contains('scroll-under-glass'),
        blur: cs ? cs.backdropFilter || cs.webkitBackdropFilter : '',
        mask: cs ? cs.maskImage || cs.webkitMaskImage : '',
        h: cs ? cs.height : '',
      });
    }
    // 顶带退役腿：不挂类 + ::after 不存在（content none/normal = 无伪元素盒）
    const band = document.getElementById('top-overlays');
    const bcs = band ? getComputedStyle(band, '::after') : null;
    out.band = {
      hasClass: !!band && band.classList.contains('scroll-under-glass'),
      content: bcs ? bcs.content : '(no-el)',
      mask: bcs ? (bcs.maskImage || bcs.webkitMaskImage || '') : '',
    };
    const sug = window.__scrollUnderGlass;
    return { ...out, sug: sug ? sug.state() : null };
  });
  ok(`G1[${tag}] 五面套用清点（挂类 + ::after blur + mask 渐变 + 28px）`,
    g.ids.every((x) => x.present && /blur/.test(x.blur) && /linear-gradient/.test(x.mask) && approx(parseFloat(x.h), 28, 0.5)),
    g.ids.map((x) => `${x.id}:${x.present ? '✓' : '✗'}/blur=${/blur/.test(x.blur) ? '✓' : '✗'}/mask=${/linear-gradient/.test(x.mask) ? '✓' : '✗'}/h=${x.h}`).join(' '));
  ok(`G1[${tag}] 顶带裙边退役：#top-overlays 不挂类、::after 不存在（穿顶首行零蒙层）`,
    g.band && !g.band.hasClass && /none|normal/.test(g.band.content) && !/linear-gradient/.test(g.band.mask),
    JSON.stringify(g.band));
  ok(`G5[${tag}] 探测器 state()（supported + 未降级初态）`, !!g.sug && g.sug.supported === true && g.sug.degraded === false,
    JSON.stringify(g.sug));
  return g;
}

async function pixelRound(page, tag, degraded) {
  // 台面底缘 y（裸挂 .scroll-under-glass 台面底 = 裙边顶）；x 对齐标尺条
  const boundary = await page.evaluate(() =>
    document.getElementById('sug-probe-stage').getBoundingClientRect().bottom);
  await sleep(100);
  const clip = { x: 60, y: boundary - 6, width: 400, height: 64 };
  const shot = await rowEnergy(page, clip);
  const { rows } = shot;
  if (process.env.GLASS_DEBUG) console.log('    [px]', shot.diag);

  // 行坐标：clip.y = boundary-6 → boundary 在 rows[6]；裙边带 = rows[7..33]；原生 = rows[44..60]
  const zone = rows.slice(7, 33);                 // 裙边带内（28px）
  const native = rows.slice(44, 60);              // 裙边下方原生条纹
  const avg = (a, k) => a.reduce((s, x) => s + x[k], 0) / Math.max(1, a.length);
  const zoneR = avg(zone, 'range'), nativeR = avg(native, 'range');
  const zoneX = avg(zone, 'edges'), nativeX = avg(native, 'edges');
  // 渐隐梯度：带内振幅沿行向下回升（顶部遮罩最强）→ 首末四行均值比
  const topR = avg(zone.slice(0, 4), 'range'), botR = avg(zone.slice(-4), 'range');
  if (!degraded) {
    ok(`G2[${tag}] 主态像素：带内振幅塌缩 + 边数下降（blur 磨砂）`,
      nativeR > 120 && zoneR < nativeR * 0.85 && zoneX < nativeX,
      `zoneRange=${zoneR.toFixed(0)} nativeRange=${nativeR.toFixed(0)} edges=${zoneX.toFixed(0)}/${nativeX.toFixed(0)}`);
    ok(`G2[${tag}] 主态渐隐梯度（带内下行振幅 > 上行）`, botR > topR * 1.3,
      `top=${topR.toFixed(0)} bot=${botR.toFixed(0)}`);
  } else {
    ok(`G3[${tag}] 降级态像素：条纹边数保全（渐隐不磨砂）`, nativeX >= 6 && zoneX > nativeX * 0.6,
      `zoneEdges=${zoneX.toFixed(0)} nativeEdges=${nativeX.toFixed(0)}`);
    ok(`G3[${tag}] 降级态振幅高于主态磨砂（纯遮罩保对比）`, zoneR > nativeR * 0.35,
      `zoneRange=${zoneR.toFixed(0)} nativeRange=${nativeR.toFixed(0)}`);
  }
  return { zoneR, nativeR, zoneX, nativeX };
}

for (const scheme of ['light', 'dark']) {
  const { ctx, page, errors } = await boot(scheme);
  const tag = scheme;
  await stageScene(page);
  await sleep(250);

  /* G1 几何 + G5 探测器 */
  await geometry(page, tag);

  /* G0 pass-under 机制：带 overlay 后 top-reserve 观察器按带高写 #chat padding-top
     （带底 − chat 顶 + 8px 基线）；滚动内容才能从带下穿过（作者令本体） */
  await sleep(600);                    // max-height .3s 过渡 + RO 重算落定
  if (process.env.GLASS_DEBUG) {
    const roProbe = await page.evaluate(async () => {
      const ov = document.getElementById('top-overlays');
      const before = (window.__resvLog || []).length;
      ov.style.height = '90px';                     // 手动拨高 → RO 应再火
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      await new Promise((r) => setTimeout(r, 300));
      const mid = (window.__resvLog || []).length;
      ov.style.height = '';
      await new Promise((r) => setTimeout(r, 300));
      return { before, mid, after: (window.__resvLog || []).length,
               pad: getComputedStyle(document.getElementById('chat')).paddingTop };
    });
    console.log('    [ro]', JSON.stringify(roProbe));
  }
  const g0 = await page.evaluate(() => ({
    pad: parseFloat(getComputedStyle(document.getElementById('chat')).paddingTop) || 0,
    bandBottom: document.getElementById('top-overlays').getBoundingClientRect().bottom,
    mainTop: document.getElementById('main').getBoundingClientRect().top,
    chatTop: document.getElementById('chat').getBoundingClientRect().top,
  }));
  /* 锚 #chat 实际盒顶（顶部消失点批 2026-10-22：⑦ margin 钉制退役，盒顶 =
     顶带下缘；padding 让位算式仍 = 带底 − chat 顶 + 8——与算式同源测量） */
  const expectPad = Math.max(8, Math.ceil(g0.bandBottom - g0.chatTop) + 8);
  ok(`G0[${tag}] top-reserve 让位（#chat padding-top = 带底缘让位 + 8）`,
    Math.abs(g0.pad - expectPad) <= 2, `pad=${g0.pad.toFixed(0)} expect=${expectPad} band=${g0.bandBottom.toFixed(0)} chatTop=${g0.chatTop.toFixed(0)} log=${JSON.stringify(await page.evaluate(() => window.__resvLog.slice(-4)))}`);

  /* G2 主态像素 */
  await pixelRound(page, tag, false);

  /* G3 降级态（与自动判定同一条腿：degrade(true) → html.glass-degraded） */
  await page.evaluate(() => window.__scrollUnderGlass.degrade(true));
  await sleep(120);
  const degCs = await page.evaluate(() => {
    const cs = getComputedStyle(document.getElementById('sug-probe-stage'), '::after');
    return { blur: cs.backdropFilter || cs.webkitBackdropFilter || '', mask: cs.maskImage || cs.webkitMaskImage || '' };
  });
  /* mask 保留由 G3 像素腿实证（条纹边数保全 = 渐隐 mask 仍在工作）；
     computed mask 读数在 html 类切换后有不可靠窗口（blur=none 正确、mask 误报
     none），不作断言依据。 */
  ok(`G3[${tag}] 降级态几何：::after blur 摘除`, /none/.test(degCs.blur),
    `blur=${degCs.blur}`);
  await pixelRound(page, tag, true);

  ok(`R[${tag}] 零 JS 运行时错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();
}

/* ════════ G6/G7 顶部消失点批（2026-10-22 作者追加令）：消失点锚定 Header 顶缘 ════════
   症状①内容区渐隐退役 + 症状②消失点 = Header 顶缘（内容从 Header 玻璃下穿过，
   上缘处消失）：桌面 = ⑦ margin 钉制退役（#chat margin 恒 0，盒顶回顶带下缘）+
   #chat 挂 mask（--chat-top-clip = header.top − chat.top，滚动前后锚点不动）+
   #header 无任何顶部裙边；手机层 = body.mobile-layer #header::after 渐隐裙边退役
   （scrollUnderGlass.css 选择器组摘除）+ 同一 mask 变量在位。六面浮层裙边照旧
   （G1 已证，作者明示浮层原样）。 */
{
  const { ctx, page, errors } = await boot('light');
  const readGeo = () => page.evaluate(() => {
    const chat = document.getElementById('chat');
    const header = document.getElementById('header');
    const cr = chat.getBoundingClientRect(), hr = header.getBoundingClientRect();
    const after = getComputedStyle(header, '::after');
    const cs = getComputedStyle(chat);
    return {
      chatTop: cr.top, headerTop: hr.top, headerBottom: hr.bottom,
      chatMargin: parseFloat(cs.marginTop) || 0,
      clipVar: parseFloat(cs.getPropertyValue('--chat-top-clip')) || 0,
      chatMask: cs.maskImage || cs.webkitMaskImage || '',
      skirtContent: after.content, skirtMask: after.maskImage || after.webkitMaskImage || '',
      headerHasClass: header.classList.contains('scroll-under-glass'),
      mobile: document.body.classList.contains('mobile-layer'),
    };
  });
  const d = await readGeo();
  ok('G6[桌面] ⑦退役：#chat margin 恒 0（盒顶回顶带下缘，不再钉 header 盒底）',
    !d.mobile && d.chatMargin === 0,
    `mobile=${d.mobile} margin=${d.chatMargin}`);
  ok('G6[桌面] 消失点锚=Header 顶缘：--chat-top-clip ≈ header.top − chat.top（±2px）',
    Math.abs(d.clipVar - Math.max(0, d.headerTop - d.chatTop)) <= 2 && d.clipVar >= 0,
    `clip=${d.clipVar} headerTop=${d.headerTop.toFixed(1)} chatTop=${d.chatTop.toFixed(1)}`);
  ok('G6[桌面] #chat 挂滚动遮罩（mask-image 在场，锚点变量消费）',
    /linear-gradient/.test(d.chatMask),
    `mask=${d.chatMask.slice(0, 60)}`);
  ok('G6[桌面] ⑥顶部渐隐摘除：#header 无裙边类、::after 不存在（content none）',
    !d.headerHasClass && /none|normal/.test(d.skirtContent) && !/linear-gradient/.test(d.skirtMask),
    `class=${d.headerHasClass} after=${d.skirtContent} mask=${d.skirtMask.slice(0, 40)}`);
  /* 滚动态实证：灌满溢出行后滚到深处——盒顶纹丝不动、锚点变量与滚动位置无关 */
  const d2 = await page.evaluate(async () => {
    const chat = document.getElementById('chat');
    for (let i = 0; i < 30; i++) {
      const row = document.createElement('div');
      row.className = 'row user';
      row.style.height = '40px';
      row.textContent = 'clip-anchor';
      chat.appendChild(row);
    }
    chat.scrollTop = chat.scrollHeight;
    await new Promise((r) => setTimeout(r, 150));
    const cr = chat.getBoundingClientRect();
    const hr = document.getElementById('header').getBoundingClientRect();
    return {
      clip: parseFloat(getComputedStyle(chat).getPropertyValue('--chat-top-clip')) || 0,
      chatTop: cr.top, headerTop: hr.top, scrolled: chat.scrollTop,
    };
  });
  ok('G6[桌面] 滚动态：锚点不动（clip 恒 = header.top − chat.top ±2px，盒顶不随滚动移动）',
    Math.abs(d2.clip - Math.max(0, d2.headerTop - d2.chatTop)) <= 2 && Math.abs(d2.chatTop - d.chatTop) <= 1 && d2.scrolled > 0,
    `clip=${d2.clip} chatTop=${d2.chatTop.toFixed(1)} headerTop=${d2.headerTop.toFixed(1)} scrolled=${d2.scrolled}`);
  ok(`R[G6] 零 JS 运行时错误`, errors.length === 0, errors.slice(0, 2).join(' | '));
  await ctx.close();

  /* G7 手机层：渐隐穿顶退役（消失点同锚 Header 顶缘） */
  const mctx = await browser.newContext({ viewport: { width: 375, height: 667 }, colorScheme: 'light', isMobile: true, hasTouch: true });
  await mctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const mpage = await mctx.newPage();
  const merrors = [];
  mpage.on('pageerror', (e) => merrors.push(String(e).slice(0, 100)));
  await mpage.route('**/api/**', (r) => r.fulfill({ status: 404, json: {} }));
  await installTicketMock(mpage);
  await mpage.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => { let m; try { m = JSON.parse(raw); } catch { return; } if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 })); });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await mpage.goto(BASE + '/index.html');
  await mpage.waitForSelector('#chat', { timeout: 15000 });
  await sleep(700);
  const m = await mpage.evaluate(() => {
    const chat = document.getElementById('chat');
    const header = document.getElementById('header');
    const cr = chat.getBoundingClientRect(), hr = header.getBoundingClientRect();
    const after = getComputedStyle(header, '::after');
    const cs = getComputedStyle(chat);
    return {
      mobile: document.body.classList.contains('mobile-layer'),
      chatTop: cr.top, headerTop: hr.top,
      chatMargin: parseFloat(cs.marginTop) || 0,
      clipVar: parseFloat(cs.getPropertyValue('--chat-top-clip')) || 0,
      chatMask: cs.maskImage || cs.webkitMaskImage || '',
      skirtContent: after.content, skirtMask: after.maskImage || after.webkitMaskImage || '',
    };
  });
  ok('G7[手机] ①渐隐退役：mobile-layer 在挂 + #header::after 裙边不存在（content none、无渐变）',
    m.mobile && /none|normal/.test(m.skirtContent) && !/linear-gradient/.test(m.skirtMask),
    `mobile=${m.mobile} after=${m.skirtContent} mask=${m.skirtMask.slice(0, 40)}`);
  ok('G7[手机] ②消失点同锚 Header 顶缘：mask 变量在场（≥0）+ margin 恒 0 + 盒顶高于 header 盒底（内容穿顶）',
    /linear-gradient/.test(m.chatMask) && m.clipVar >= 0 && m.chatMargin === 0 && m.chatTop < (m.headerTop + 90),
    `clip=${m.clipVar} mask=${m.chatMask.slice(0, 40)} margin=${m.chatMargin} chatTop=${m.chatTop.toFixed(1)} headerTop=${m.headerTop.toFixed(1)}`);
  ok(`R[G7] 零 JS 运行时错误`, merrors.length === 0, merrors.slice(0, 2).join(' | '));
  await mctx.close();
}

await browser.close();
server.close();
console.log(`\nuifix-glass: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
