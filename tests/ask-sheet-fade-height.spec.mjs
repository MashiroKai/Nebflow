// ask-sheet-fade-height.spec.mjs — 文档阅读面 Ask 面板「上缘渐隐 + 消息区加高」机械
// 断言集（ui-unification-20261103-doc-ask-fade）。
//
// 作者令（2026-11-03 实拍 IMG_1720 / IMG_1721）：「文档里 Ask 对话窗口有两个调整：
// 1. 上边缘过渡：现在上方边缘有很明显的截断。我想要的效果是像主对话窗口那样，做一个
// 慢慢渐渐隐去的消失效果来做过渡。2. 窗口高度：把 Ask 对话窗口里消息区域的高度再调高
// 一点，让它能有更多空间显示内容」。
//
// 病根（本批实证，见 evidence/doc-ask-fade/README.md）：现盘 .ask-colwrap 同时挂
// ① 顶部留白 padding-top = --ask-fade-h（内容在带底之下起排）② 列 mask 渐隐。
// 两者叠加后「渐隐带只盖住那段空白留白」：内容在带底沿以 mask≈1.0 的满不透明度进入
// ⇒ 视觉上就是一条硬切线（截断）。主对话窗 #g2-edge-fade 的形态相反——内容在带顶
// 起排、滚入带内被 veil 渐层+毛玻璃「先糊后没」，没有第二条 mask 参与。
//
// 四套断言（同脚本同判据，改前必红 / 改后必绿）：
//   A 上缘渐隐（像素级） —— A0 内容起排点 = 带顶（带盖内容，不是盖留白）；A1 带内
//     亮度剖面从顶到底单调变深且跨度 ≥60（有真渐隐）；A2 带内相邻采样行跳变 ≤45
//     （无可见直线边界）；A3 带顶 8px 内无截断残留（切缘处的深色内容被判据「吃掉」）；
//     A5 带底沿两侧亮度差 ≤30（带与下方内容连续，无硬切缝）。
//   B 渐隐带仍可交互 —— B1 带内 elementFromPoint 命中滚动容器/内容（不被 mask 裁掉、
//     不被视觉层拦住）；B2 带内滚轮真的滚得动；B3 视觉层 pointer-events:none；
//     B4 内容宿主零 mask（坑②：mask 参与 hit-test 裁剪）。
//   C 消息区加高 —— C1 列可视高 ≥320（改前 194 必红）；C2 输入条与列不重叠且输入条
//     热区 ≥44；C3 背后文档仍留可读区（面板顶距视口顶 ≥28%）；C4 内容真的溢出可滚。
//   D 与主对话窗同源 —— D1 #g2-edge-fade 与 .ask-veil 挂同一个 .ds-* 渐隐带类；
//     D2 两者材质计算样式逐项相等（veil 渐层 / blur+saturate / 两段式 mask）；
//     D3 ask.css 的 .ask-veil 块内零材质声明（不许第二套实现）；D4 glass.css 的
//     #g2-edge-fade 块内同样零材质声明（材质只在 .ds-edge-fade 一处）。
//   E 同族竖排 bug（实拍表格首列「还挂旧数／不需动／待办」逐字竖排）—— E1 窄列短标签
//     不逐字折行（首列文本行块数 = 1）；E2 表格字号 ≥13px（iOS HIG 可读字档）。
//
// 数据面 = 自包含 harness（route 静态资源 + mock WS 夹具），零后端、零 sbt；视口 390×844。
// 跑法：PW_SELFCONTAINED_ONLY=1 npx playwright test tests/ask-sheet-fade-height.spec.mjs
// 取证：ASK_EVIDENCE_DIR=<目录> 把读数 JSON + 截图落指定目录。
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';
import { createHash } from 'node:crypto';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.ASK_WEB || join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.env.ASK_EVIDENCE_DIR || join(HERE, '..', '.nebflow', 'evidence', 'doc-ask-fade');
mkdirSync(OUT, { recursive: true });

const SID = 'askfade-session';
const DOC_TITLE = 'askfade-doc';
const DOC_ABS = `/tmp/docs/${DOC_TITLE}.md`;
/* 客户端/服务端同式的 ask 会话 id 派生（sha256(absPath) 前 8 hex，前缀 ask-）——
   askDock.consumeFrame 按 sid 反查文件，mock 回放帧必须携同一 sid，否则整帧被忽略。 */
const ASK_SID = 'ask-' + createHash('sha256').update(DOC_ABS, 'utf8').digest('hex').slice(0, 8);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

/* 文档正文：长段（与作者实拍同构：阅读面里是一份长文档）。 */
const DOC_CONTENT = `# ${DOC_TITLE}\n\n` + Array.from({ length: 24 }, (_, i) =>
  `第${i + 1}段：手机端文档阅读面的正文段落，用来验证 Ask 面板上缘渐隐与消息区高度。` +
  `本段刻意写满四十个字以上，让它在手机视口里折成两行以上，从而覆盖面板顶缘的采样区。`).join('\n\n') + '\n';

/* Ask 转录（mock 回放）：表 + 长段 + 代码块，覆盖实拍里的三种内容形态。 */
const ASK_TABLE = [
  '| 还挂旧数 | 说明 |',
  '| --- | --- |',
  '| 还挂旧数 | 53.5/64.4%）、任务谱占比图图题、input_source 产物包（GCR 总注量高 ~49×） |',
  '| 不需动 | 全部 dose 表锚结论、结构/器件类图件、已重跑图（F8/F9） |',
  '| 待办 | 重跑类 6 项共用一个根因——「积分口径 + 映射修正」统一重跑批 |',
].join('\n');
const ASK_TRANSCRIPT = [
  { role: 'user', text: '这份盘点文档里还有哪些旧数没清？' },
  { role: 'ai', text: ASK_TABLE },
  { role: 'ai', text: '上面的表是逐条清单。' + '每条都带出处，判定与理由写在右列。'.repeat(6) },
  { role: 'user', text: '那这一轮要重跑哪些？' },
  { role: 'ai', text: '重跑清单如下：\n\n```\nF8 结构图重跑\nF9 器件图重跑\nF13 任务谱图重跑\nC4 微分通量标签重跑\nC17 medianE 旧值引用重跑\n```\n\n以上六项共用同一个根因。' },
];

/* ── PNG 解码（与 tests/mobile-canvas-topbar-semantic.spec.mjs 同一实现）──── */
function decodePng(buf) {
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

/** 带内亮度剖面：每行取 [x0..x1] 均值，再用 win 窗口平滑（跨过行距周期）。 */
function bandProfile(png, { x0, x1, yTop, yBottom, win = 8 }) {
  const { W, H, ch, out } = decodePng(png);
  const lum = (x, y) => { const i = (y * W + x) * ch; return 0.299 * out[i] + 0.587 * out[i + 1] + 0.114 * out[i + 2]; };
  const xa = Math.max(0, Math.round(x0)), xb = Math.min(W - 1, Math.round(x1));
  const rowMean = (y) => {
    let s = 0, n = 0;
    const yy = Math.min(H - 1, Math.max(0, Math.round(y)));
    for (let x = xa; x <= xb; x++) { s += lum(x, yy); n++; }
    return n ? s / n : 0;
  };
  const winMean = (y) => {
    let s = 0, n = 0;
    for (let dy = 0; dy < win; dy++) { const yy = Math.round(y) + dy; if (yy >= 0 && yy < H) { s += rowMean(yy); n++; } }
    return n ? s / n : 0;
  };
  const series = [];
  for (let y = yTop; y <= yBottom; y += 2) series.push([y, +winMean(y).toFixed(1)]);
  return { series, winMean };
}

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'light' });

test.describe('doc ask sheet · top fade + message area height', () => {
  test('A fade · B hit-test · C height · D same-source · E table regression', async ({ page }) => {
    const failures = [];
    const readings = {};
    page.on('pageerror', (e) => {
      (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 200));
    });
    const check = (name, cond, extra = '') => {
      // eslint-disable-next-line no-console
      console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
      if (!cond) failures.push(name);
      return cond;
    };

    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'e2e-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      localStorage.setItem('stand_sw', '0');
      Object.defineProperty(navigator.serviceWorker, 'register', {
        value: () => new Promise(() => {}), configurable: true,
      });
    });
    await page.route('**/*', (route) => {
      const p0 = decodeURIComponent(new URL(route.request().url()).pathname);
      const p = p0 === '/' ? '/index.html' : p0;
      if (p.startsWith('/api/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
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
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
      sendConfig(); sendSessions();
      ws.onMessage((raw) => {
        let m; try { m = JSON.parse(raw); } catch { return; }
        if (m.type === 'getConfig') sendConfig();
        else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
        else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
        else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
        else if (m.type === 'askOpen') {
          // askOpen 不携 sid（服务端按 file.absPath 派生）；mock 直接回放夹具转录。
          ws.send(JSON.stringify({
            type: 'askState',
            sessionId: m.sessionId || ASK_SID,
            file: (m.file && m.file.absPath) || '',
            fileName: (m.file && m.file.name) || 'doc',
            messages: ASK_TRANSCRIPT,
          }));
        }
      });
    });
    await page.goto('http://localhost:8899/');
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
    }, SID, { timeout: 20000 });
    await page.waitForSelector('body.mobile-layer', { timeout: 15000 });

    // 长文档开场（生产同一条开法；watchCanvasEvents 有 3s 启动抑制窗 → 等它过期）
    await page.waitForTimeout(3400);
    await page.evaluate(({ title, content, docAbs }) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:docs/' + title + '.md', itemType: 'markdown', title, content, absPath: docAbs, size: content.length },
      }));
    }, { title: DOC_TITLE, content: DOC_CONTENT, docAbs: DOC_ABS });
    await page.waitForSelector('.mobile-page[data-page="canvas"].live', { timeout: 12000 });
    await page.waitForTimeout(500);

    // 开 Ask 面板（mock 回放转录到位）
    await page.click('.ask-fab');
    await page.waitForTimeout(800);
    await page.waitForSelector('.ask-dock:not(.closed) .ask-col .ask-bub', { timeout: 8000 });

    /* ── 几何读数（C 组 + E 组）──────────────────────────────────────── */
    const geo = await page.evaluate(() => {
      const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: +b.top.toFixed(1), bottom: +b.bottom.toFixed(1), left: +b.left.toFixed(1), right: +b.right.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
      const colwrap = document.querySelector('.ask-colwrap');
      const col = document.querySelector('.ask-col');
      const veil = document.querySelector('.ask-colwrap .ask-veil');
      const dock = document.querySelector('.ask-dock');
      const input = document.querySelector('.ask-input');
      const send = document.querySelector('.ask-input .ask-send');
      const mdock = document.getElementById('mobile-dock');
      const cs = (el) => (el ? getComputedStyle(el) : null);
      return {
        colwrap: r(colwrap), col: r(col), veil: r(veil), dock: r(dock), input: r(input), send: r(send), mobileDock: r(mdock),
        colClientH: col ? col.clientHeight : 0,
        colPadTop: col ? parseFloat(cs(col).paddingTop) || 0 : 0,
        // 带净空在滚动容器内（padding box 才是裁剪/渐隐区）⇒ 静态可见内容高 = client − padding-top
        colContentH: col ? Math.round(col.clientHeight - (parseFloat(cs(col).paddingTop) || 0)) : 0,
        colScrollH: col ? col.scrollHeight : 0,
        veilPointerEvents: cs(veil) ? cs(veil).pointerEvents : null,
        colwrapMask: cs(colwrap) ? String(cs(colwrap).maskImage) : null,
        colMask: cs(col) ? String(cs(col).maskImage) : null,
        viewportH: window.innerHeight,
        tableFont: (() => { const td = document.querySelector('.ask-bub.ai table td'); return td ? cs(td).fontSize : null; })(),
      };
    });
    readings.geo = geo;

    /* ── E 同族竖排 bug（表格首列短标签）──────────────────────────────── */
    const tableProbe = await page.evaluate(() => {
      const td = document.querySelector('.ask-bub.ai table tbody tr td');
      if (!td) return null;
      const node = td.firstChild && td.firstChild.nodeType === 3 ? td.firstChild : td;
      const range = document.createRange();
      range.selectNodeContents(node);
      const rects = Array.from(range.getClientRects()).filter((r) => r.width > 0 && r.height > 0);
      const b = td.getBoundingClientRect();
      return { lines: rects.length, tdW: +b.width.toFixed(1), tdH: +b.height.toFixed(1), text: (td.textContent || '').slice(0, 12) };
    });
    readings.tableProbe = tableProbe;
    check('E1 窄列短标签不逐字折行（首列文本行块数 = 1）', !!tableProbe && tableProbe.lines === 1,
      JSON.stringify(tableProbe));
    check('E2 表格字号 ≥13px（iOS HIG 可读字档；旧 12px 必红）', !!geo.tableFont && parseFloat(geo.tableFont) >= 13, geo.tableFont);

    /* ── C 消息区高度 ─────────────────────────────────────────────────── */
    check('C1 列可视高 ≥320px（作者令「消息区域再调高一点」；改前 194 必红）', geo.colClientH >= 320, `colClientH=${geo.colClientH}`);
    // F1 短会话不雾化：带净空 = 带高且落在滚动容器内 ⇒ 内容盒顶 ≥ 带底沿
    // （会话只有一两条消息时，首条完整清晰；旧形态「保留区在容器外」对短会话本就成立，
    //  但滚动态永不入带 = 硬切 —— 本条钉住「静态清晰 + 动态穿带」两件事的前提）。
    check('F1 带净空 = 带高且 ≥ 带底沿（会话短时首条不被带雾化）',
      !!geo.col && !!geo.veil && geo.colPadTop >= geo.veil.h - 0.5 && geo.col.top + geo.colPadTop >= geo.veil.top + geo.veil.h - 1,
      JSON.stringify({ padTop: geo.colPadTop, veilH: geo.veil && geo.veil.h, contentTop: geo.col && +(geo.col.top + geo.colPadTop).toFixed(1) }));
    check('C1b 静态可见内容高 ≥300（= 列高 − 带净空；旧形态 194 必红）', geo.colContentH >= 300, `colContentH=${geo.colContentH}`);
    check('C2 输入条不与消息列重叠（input.top ≥ col.bottom − 0.5）', !!geo.input && !!geo.col && geo.input.top >= geo.col.bottom - 0.5,
      JSON.stringify({ inputTop: geo.input && geo.input.top, colBottom: geo.col && geo.col.bottom }));
    check('C2b 输入条热区 ≥44（输入条行高 ≥44 且发送钮 ≥32）', !!geo.input && geo.input.h >= 44 && !!geo.send && geo.send.h >= 32,
      JSON.stringify({ inputH: geo.input && geo.input.h, sendH: geo.send && geo.send.h }));
    check('C3 背后文档保留可读区（面板顶距视口顶 ≥28%）', !!geo.dock && geo.dock.top >= geo.viewportH * 0.28,
      JSON.stringify({ dockTop: geo.dock && geo.dock.top, need: +(geo.viewportH * 0.28).toFixed(1) }));
    check('C4 内容真的溢出可滚（scrollHeight > clientHeight + 40）', geo.colScrollH > geo.colClientH + 40,
      JSON.stringify({ scrollH: geo.colScrollH, clientH: geo.colClientH }));

    // 写实态截图（人眼复核面）
    await page.screenshot({ path: join(OUT, 'ask-sheet-realistic.png') });
    const shotTop = Math.max(0, Math.round((geo.dock ? geo.dock.top : 200) - 28));
    await page.screenshot({ path: join(OUT, 'ask-sheet-realistic-band.png'), clip: { x: 0, y: shotTop, width: 390, height: Math.min(240, 844 - shotTop) } });

    /* ── A/B 渐隐带（注入深色探针块 + 滚到带区，量带内亮度剖面）──────── */
    await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const d = document.createElement('div');
      d.id = '__fade_probe';
      d.setAttribute('data-test-fixture', 'fade-band-ink');
      // 块高 > 列可视高 ⇒ 滚到底时块顶必然落在可视口之上，整个渐隐带都压在深色块
      // 上（量带自身的 alpha 剖面；切缘被 overflow 裁在带顶之上 = 截断是否被吃掉的
      // 真实形态）。
      d.style.cssText = 'flex:none;width:100%;height:' + (col.clientHeight + 180) + 'px;background:#1f242b;border-radius:8px;';
      col.appendChild(d);
      col.scrollTop = 1e6;   // 落到底（= 最大值），深色块吃满整列
    });
    await page.waitForTimeout(250);

    const geo2 = await page.evaluate(() => {
      const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: +b.top.toFixed(1), bottom: +b.bottom.toFixed(1), left: +b.left.toFixed(1), right: +b.right.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
      const col = document.querySelector('.ask-col');
      const veil = document.querySelector('.ask-colwrap .ask-veil');
      return {
        col: r(col), veil: r(veil), probeEl: r(document.getElementById('__fade_probe')),
        scrollTop: col.scrollTop, scrollH: col.scrollHeight, clientH: col.clientHeight,
      };
    });
    readings.geo2 = geo2;

    // A0 结构：内容起排点 = 带顶（带盖内容而非盖留白）
    check('A0 渐隐带盖在内容上（col.top ≤ veil.top + 1；旧形态 = 内容在带底之下起排）',
      !!geo2.col && !!geo2.veil && geo2.col.top <= geo2.veil.top + 1,
      JSON.stringify({ colTop: geo2.col && geo2.col.top, veilTop: geo2.veil && geo2.veil.top }));

    const band = geo2.veil;
    const clipY = Math.max(0, band.top - 12);
    const clipH = Math.min(844 - clipY, Math.ceil(band.h) + 60);
    const buf = await page.screenshot({ clip: { x: 0, y: clipY, width: 390, height: clipH } });
    writeFileSync(join(OUT, 'ask-sheet-fade-band.png'), buf);
    const x0 = Math.max(0, (geo2.col ? geo2.col.left : 12) + 16);
    const x1 = Math.min(389, (geo2.col ? geo2.col.right : 378) - 16);
    const { series } = bandProfile(buf, {
      x0, x1,
      yTop: Math.max(0, band.top - clipY),
      yBottom: Math.min(clipH - 1, band.bottom - clipY),
    });
    const L_top = series[0][1];
    const L_bot = series[series.length - 1][1];
    const span = +(L_top - L_bot).toFixed(1);
    let maxJump = 0, maxAt = 0, inversions = 0;
    for (let i = 1; i < series.length; i++) {
      const d = series[i][1] - series[i - 1][1];   // 向下变深 ⇒ 负值
      if (Math.abs(d) > maxJump) { maxJump = +Math.abs(d).toFixed(1); maxAt = series[i][0]; }
      if (d > 8) inversions++;   // 向下反而变亮 = 逆序
    }
    // 带底沿两侧（带内末行 vs 带下 8..16px）连续性
    const seamY = Math.max(0, band.bottom - 8);
    const seamBuf = await page.screenshot({ clip: { x: 0, y: seamY, width: 390, height: 24 } });
    const seamProf = bandProfile(seamBuf, { x0, x1, yTop: 0, yBottom: 16, win: 4 });
    const seamJump = +Math.abs((seamProf.series[0] ? seamProf.series[0][1] : 0) - (seamProf.series[seamProf.series.length - 1][1] || 0)).toFixed(1);
    readings.fade = { L_top, L_bot, span, maxJump, maxAt, inversions, seamJump, series, band, clipY };

    check('A1 带内亮度剖面有真渐隐（顶亮底深，跨度 ≥60；硬切/空带必红）', span >= 60, `span=${span} top=${L_top} bottom=${L_bot}`);
    check('A2 带内无可见直线边界（相邻采样行跳变 ≤45）', maxJump <= 45, `maxJump=${maxJump}@y${maxAt}`);
    check('A2b 剖面单调（向下变深的逆序步数 ≤2）', inversions <= 2, `inversions=${inversions}`);
    check('A3 带顶切缘无截断残留（带顶 8px 亮度 ≥190 = 深色内容被 veil 吃掉）', L_top >= 190, `L_top=${L_top}`);
    check('A5 带底沿两侧连续（跳变 ≤30；旧形态在带底硬切必红）', seamJump <= 30, `seamJump=${seamJump}`);

    /* ── B 渐隐带仍可交互 ─────────────────────────────────────────────── */
    const hits = await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const veil = document.querySelector('.ask-colwrap .ask-veil');
      const vb = veil.getBoundingClientRect();
      const cb = col.getBoundingClientRect();
      const xs = [cb.left + cb.width / 2, cb.left + 40, cb.right - 40];
      const ys = [vb.top + vb.height * 0.25, vb.top + vb.height * 0.5, vb.top + vb.height * 0.8];
      const out = [];
      for (const x of xs) for (const y of ys) {
        const el = document.elementFromPoint(x, y);
        out.push({
          x: +x.toFixed(0), y: +y.toFixed(0),
          tag: el ? (el.id || el.className || el.tagName) : null,
          inCol: !!(el && (el === col || el.closest('.ask-col'))),
          isVeil: !!(el && el.classList && el.classList.contains('ask-veil')),
        });
      }
      return out;
    });
    readings.hits = hits;
    check('B1 带内命中滚动容器/内容（不被 mask 裁掉、不被视觉层拦住）', hits.every((h) => h.inCol && !h.isVeil),
      JSON.stringify(hits.map((h) => `${h.y}:${h.tag}`)));
    check('B3 视觉层 pointer-events:none（不是栏）', geo.veilPointerEvents === 'none', geo.veilPointerEvents);
    check('B4 内容宿主零 mask（坑②：mask 参与 hit-test 裁剪）', geo.colwrapMask === 'none' && geo.colMask === 'none',
      JSON.stringify({ colwrap: geo.colwrapMask, col: geo.colMask }));

    // 先落到可滚中段（滚轮要有余量才滚得动）
    const beforeScroll = await page.evaluate(() => {
      const c = document.querySelector('.ask-col');
      const mid = Math.max(0, Math.round((c.scrollHeight - c.clientHeight) / 2));
      c.scrollTop = mid;
      return c.scrollTop;
    });
    const wheelAt = await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const veil = document.querySelector('.ask-colwrap .ask-veil');
      const vb = veil.getBoundingClientRect();
      const cb = col.getBoundingClientRect();
      return { x: Math.round(cb.left + cb.width / 2), y: Math.round(vb.top + vb.height * 0.4) };
    });
    // 先滚回带区（滚轮会移动内容；用 scrollTop 复位保证采样可复现）
    await page.evaluate(({ y }) => { const c = document.querySelector('.ask-col'); c.scrollTop = y; }, { y: beforeScroll });
    await page.waitForTimeout(120);
    await page.mouse.move(wheelAt.x, wheelAt.y);
    await page.mouse.wheel(0, 120);
    await page.waitForTimeout(260);
    const afterScroll = await page.evaluate(() => document.querySelector('.ask-col').scrollTop);
    readings.wheel = { beforeScroll, afterScroll };
    check('B2 带内滚轮真的滚得动（渐隐带未被 hit-test 裁掉）', afterScroll - beforeScroll >= 10,
      JSON.stringify({ beforeScroll, afterScroll }));

    /* ── D 与主对话窗同源 ─────────────────────────────────────────────── */
    const d = await page.evaluate(() => {
      const askVeil = document.querySelector('.ask-colwrap .ask-veil');
      const mainFade = document.getElementById('g2-edge-fade');
      const pick = (el) => {
        if (!el) return null;
        const cs = getComputedStyle(el);
        return {
          cls: el.className,
          bg: cs.backgroundImage,
          bf: cs.backdropFilter || cs.webkitBackdropFilter,
          mask: cs.maskImage || cs.webkitMaskImage,
          pe: cs.pointerEvents,
        };
      };
      return { askVeil: pick(askVeil), mainFade: pick(mainFade) };
    });
    readings.shared = d;
    const fadeClasses = (s) => String(s || '').split(/\s+/).filter((c) => /^ds-.*fade/.test(c));
    const askFadeCls = fadeClasses(d.askVeil && d.askVeil.cls);
    const mainFadeCls = fadeClasses(d.mainFade && d.mainFade.cls);
    check('D1 Ask 视觉层与 #g2-edge-fade 挂同一个 .ds-* 渐隐带类', askFadeCls.length > 0 && askFadeCls.some((c) => mainFadeCls.includes(c)),
      JSON.stringify({ ask: d.askVeil && d.askVeil.cls, main: d.mainFade && d.mainFade.cls }));
    const norm = (v) => String(v || '').replace(/\s+/g, ' ').trim();
    check('D2 两者材质计算样式同源（veil 渐层 / blur+saturate / 两段式 mask 逐项相等）',
      !!d.askVeil && !!d.mainFade
      && norm(d.askVeil.bg) === norm(d.mainFade.bg)
      && norm(d.askVeil.bf) === norm(d.mainFade.bf)
      && norm(d.askVeil.mask) === norm(d.mainFade.mask)
      && d.askVeil.pe === 'none' && d.mainFade.pe === 'none',
      JSON.stringify({ ask: [norm(d.askVeil.bg).slice(0, 24), norm(d.askVeil.bf), norm(d.askVeil.mask).slice(0, 24)], main: [norm(d.mainFade.bg).slice(0, 24), norm(d.mainFade.bf), norm(d.mainFade.mask).slice(0, 24)] }));

    // 静态面：材质只在 .ds-edge-fade 一处（消费面不许第二套实现）
    const blockOf = (file, selector) => {
      const src = readFileSync(join(WEB, 'css', file), 'utf8');
      const i = src.indexOf(selector);
      if (i < 0) return '';
      const open = src.indexOf('{', i);
      const close = src.indexOf('}', open);
      return open < 0 || close < 0 ? '' : src.slice(open, close);
    };
    const askVeilBlock = blockOf('ask.css', '.ask-colwrap .ask-veil');
    const g2Block = blockOf('glass.css', '#g2-edge-fade {');
    const dsBlock = blockOf('design-system.css', '.ds-edge-fade {');
    readings.blocks = { askVeilBlock: askVeilBlock.slice(0, 400), g2Block: g2Block.slice(0, 400), dsBlock: dsBlock.slice(0, 700) };
    check('D3 ask.css .ask-veil 块内零材质声明（无 backdrop-filter / 渐层 —— 不许第二套形态）',
      !!askVeilBlock && !/backdrop-filter/.test(askVeilBlock) && !/gradient/.test(askVeilBlock),
      askVeilBlock.replace(/\s+/g, ' ').slice(0, 120));
    check('D4 glass.css #g2-edge-fade 块内零材质声明（材质只在 .ds-edge-fade 一处）',
      !!g2Block && !/backdrop-filter/.test(g2Block) && !/gradient/.test(g2Block),
      g2Block.replace(/\s+/g, ' ').slice(0, 120));
    check('D5 design-system.css 的 .ds-edge-fade 承载完整配方（veil 渐层 + blur/saturate + 两段式 mask + pointer-events:none）',
      !!dsBlock && /var\(--ds-fade-veil\)/.test(dsBlock) && /blur\(var\(--ds-fade-blur\)\)/.test(dsBlock)
      && /saturate\(var\(--ds-fade-sat\)\)/.test(dsBlock) && /mask-image/.test(dsBlock) && /pointer-events:\s*none/.test(dsBlock),
      dsBlock.replace(/\s+/g, ' ').slice(0, 160));

    check('Z 页面零 pageerror', !(readings.pageErrors && readings.pageErrors.length), JSON.stringify(readings.pageErrors || []));

    writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
    if (failures.length) throw new Error(`doc ask fade/height assertions failed (${failures.length}): ${failures.join(' | ')}`);
  });
});
