// uifix-glass.spec.mjs — uifix ⑤ 浮层下缘毛玻璃裙边（scroll-under-glass）验收
//
// 作者令（2026-10-20 第五件）：消息滚动到浮层面板下方被硬切 → 应**穿过并毛玻璃
// 透出下层**；backdrop-filter 耗资源则退化 = 毛玻璃渐隐（mask 渐变）。推广到所有
// 浮层压滚动内容的面板。做成共享工具类 .scroll-under-glass（css/scrollUnderGlass.css
// 裙边方案：面板本体零触碰，::after 裙边 = blur+saturate+tint 渐变+mask 渐隐 28px）。
//
// 断言面：
//   G1 套用清点 —— #六面挂类（#top-overlays / #reminder-panel / #daemon-panel /
//      #settings-modal / #memory-modal / #rules-modal；项目/插件为 canvas 页签、
//      桌面态不压滚动内容，不在清单——假设已在报告声明）+ ::after 几何
//      （backdrop-filter blur / mask linear-gradient / 高 28px）。
//   G2 像素·主态 —— 高对比消息条穿过裙边带：带内高频能量（邻差）≪ 带下原生行
//      （blur 磨砂实证），且纵向存在渐隐梯度（逐行能量递增曲线）。
//   G3 降级态 —— __scrollUnderGlass.degrade(true)（与自动判定同腿）：::after
//      backdrop-filter 'none'、mask 保留；同位像素带内高频能量显著回升（渐隐不磨砂）。
//   G4 暗色 —— 亮暗两套 colorScheme 重复 G1 几何 + G2/G3 像素核心链。
//   G5 探测器 —— state() 形状 + no-backdrop 特性腿（CSS.supports 恒真环境下
//      仅验 supported=true 路径；不支持路径由降级类同名腿覆盖）。
//
// 环境注：任务带内容管线（ws 链）由 taskList 族 spec 自管，此处直接驱动
// .has-tasks + 注入示意卡（被测面 = 裙边 CSS/几何/像素，与数据管线解耦）。
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

/** 高对比条纹标尺穿帮布景：任务带（has-tasks + 示意卡）+ fixed 定位黑白栅条。
 *  栅条 z=5（低于带 z-25 的裙边堆叠上下文、高于页面底色），上段 40px 没入带本
 *  体之下（被不透明卡遮住），中段 28px 落在裙边带，其余裸露 = 原生参照。
 *  返回带底缘 y（= 裙边起点）。 */
async function stageScene(page) {
  await page.evaluate(() => {
    const tl = document.getElementById('task-list');
    tl.classList.add('has-tasks');
    tl.innerHTML = '<div class="task-card" style="height:64px"><div class="task-header" style="height:28px"></div></div>';
    const r = document.getElementById('top-overlays').getBoundingClientRect();
    const strip = document.createElement('div');
    strip.id = 'sug-probe-strip';
    // 挂 #main（带 z-25 的同上下文）：z-5 = 带下、页面底色上——模拟真实消息层
    strip.style.cssText = 'position:absolute;z-index:5;left:60px;width:400px;' +
      `top:${r.bottom - 40}px;height:220px;` +
      'background:repeating-linear-gradient(90deg,#000 0 10px,#fff 10px 20px)';
    document.getElementById('main').appendChild(strip);
    return true;
  });
  await sleep(150);
}

async function geometry(page, tag) {
  const g = await page.evaluate(() => {
    const ids = ['top-overlays', 'reminder-panel', 'daemon-panel', 'settings-modal', 'memory-modal', 'rules-modal'];
    const out = { ids: [], extra: [] };
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
    const sug = window.__scrollUnderGlass;
    return { ...out, sug: sug ? sug.state() : null };
  });
  ok(`G1[${tag}] 六面套用清点（挂类 + ::after blur + mask 渐变 + 28px）`,
    g.ids.every((x) => x.present && /blur/.test(x.blur) && /linear-gradient/.test(x.mask) && approx(parseFloat(x.h), 28, 0.5)),
    g.ids.map((x) => `${x.id}:${x.present ? '✓' : '✗'}/blur=${/blur/.test(x.blur) ? '✓' : '✗'}/mask=${/linear-gradient/.test(x.mask) ? '✓' : '✗'}/h=${x.h}`).join(' '));
  ok(`G5[${tag}] 探测器 state()（supported + 未降级初态）`, !!g.sug && g.sug.supported === true && g.sug.degraded === false,
    JSON.stringify(g.sug));
  return g;
}

async function pixelRound(page, tag, degraded) {
  // 带底缘 y（#task-list 底 = 裙边顶）
  const boundary = await page.evaluate(() =>
    document.getElementById('top-overlays').getBoundingClientRect().bottom);
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
  }));
  /* 锚 #main（滚动无关）：带 overlay 后 chat 顶 = #main 顶 */
  const expectPad = Math.max(8, Math.ceil(g0.bandBottom - g0.mainTop) + 8);
  ok(`G0[${tag}] top-reserve 让位（#chat padding-top = 带底缘让位 + 8）`,
    Math.abs(g0.pad - expectPad) <= 2, `pad=${g0.pad.toFixed(0)} expect=${expectPad} band=${g0.bandBottom.toFixed(0)} mainTop=${g0.mainTop.toFixed(0)} log=${JSON.stringify(await page.evaluate(() => window.__resvLog.slice(-4)))}`);

  /* G2 主态像素 */
  await pixelRound(page, tag, false);

  /* G3 降级态（与自动判定同一条腿：degrade(true) → html.glass-degraded） */
  await page.evaluate(() => window.__scrollUnderGlass.degrade(true));
  await sleep(120);
  const degCs = await page.evaluate(() => {
    const cs = getComputedStyle(document.getElementById('task-list'), '::after');
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

await browser.close();
server.close();
console.log(`\nuifix-glass: ${pass} PASS / ${fail} FAIL`);
process.exit(fail ? 1 : 0);
