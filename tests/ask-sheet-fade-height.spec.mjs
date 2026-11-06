// ask-sheet-fade-height.spec.mjs — 文档阅读面 Ask 面板「上缘渐隐 + 消息区加高」机械
// 断言集（ui-unification-20261103-doc-ask-fade 立项；2026-11-04 ask-fade-content-opacity
// 批按作者裁定改判据 —— 见下）。
//
// 作者令①（2026-11-03 实拍 IMG_1720 / IMG_1721）：「上边缘过渡：现在上方边缘有很明显
// 的截断。我想要的效果是像主对话窗口那样，做一个慢慢渐渐隐去的消失效果来做过渡」+
// 「把 Ask 对话窗口里消息区域的高度再调高一点」。
// 作者令②（2026-11-04 09:22 逐字）：「canvas Ask 的这个渐隐，应该是对消息什么的降低
// 透明度到 0 的过程，而不是放一个过渡的条在哪，整体要是透明无边框的」。
//
// 病根（①-批引入，②-批实证）：①-批把主对话窗的「渐隐带」形态搬进 Ask——一个
// 独立的绝对定位视觉层 .ask-veil（挂 .ds-edge-fade：veil 渐层背景 + backdrop-filter
// blur/saturate + 自身 mask）。它不是内容透明度，而是**一块带底色的遮罩条**：面板
// 背后是文档正文（面板本身透明），veil 的 0.92→0 亮色渐层把背后内容洗成一条可见的
// 乳白横带；气泡跨带时 = 一条「被切掉的卡片边」。作者②-令要的是**内容本体 alpha
// 渐到 0**：mask 作用在滚动容器（= 内容层）自身的绘制结果上，零背景/零边框/零浮影/
// 零 backdrop-filter ⇒ 面板整体透明无边框、没有任何可见过渡元素。
//
// 判据（同脚本同判据，改前必红 / 改后必绿；A 组 2026-11-06 毛玻璃批换代 —— 列 mask
// 退役（backdrop root 架空子泡玻璃 = 半透明洗色），载体 = askDock.js syncAskFade
// 逐泡 opacity）：
//   A 渐隐本体 = 内容本体透明度 —— A0 面板内**零**渐隐带元素（.ds-edge-fade / .ask-veil
//     计数 0；①-批形态必红）；A0d 面板内零带渐变背景的元素；A0b 列零 mask-image
//     （backdrop root 根因摘除）+ 载体在场（askDock.js 源含 syncAskFade）；A0c 外框
//     零 mask；A1 双探针两点亮度跨度 ≥60（带顶探针被洗近背景、带外探针全实 = 渐隐
//     真实存在）；A3 带顶探针区亮度 ≥190（深色探针洗到只剩背景）。〔A2/A2b/A5
//     连续剖面/单调/接缝判据随列 mask 载体退役：逐泡 opacity 是泡级 alpha，带内
//     无连续渐变剖面形态。〕
//   B 渐隐不影响交互 —— B1 带内 elementFromPoint 命中滚动容器/内容（mask 不裁 hit-test）；
//     B2 带内滚轮真的滚得动；B3 内容宿主 mask 存在但 pointer-events 正常（auto）。
//   T 整体透明无边框 —— T1 面板/滚动容器（dock / colwrap / col）背景透明（background-color
//     全透明 + background-image none）；T2 无边框/分隔线（border-style none 或宽 0）；
//     T3 无浮影/内阴影（box-shadow none）；T4 面板零 backdrop-filter（不磨玻璃）。
//   C 消息区加高 —— C1 列可视高 ≥320（①-批前 194 必红）；C2 输入条与列不重叠且输入条
//     热区 ≥44；C3 背后文档仍留可读区（面板顶距视口顶 ≥28%）；C4 内容真的溢出可滚。
//   D 爆炸半径 —— D1 主对话窗 #g2-edge-fade 仍在且仍挂 .ds-edge-fade（本批只改 Ask，
//     主窗不动）；D2 ask.css 源零 veil 材质（无 .ask-veil / --ds-fade-veil / 列与柱元素上
//     零 backdrop-filter；轮钮与气泡的玻璃档是 11-04 12:52 令显式授权的材质面，不算残留）；D3 ask.css 源（剥注释）零 mask-image + askDock.js 源含 syncAskFade（渐隐唯一实现，防列 mask 复活）；
//     D4 glass.css #g2-edge-fade 块内零材质（主窗单源纪律不变）；D5 design-system.css
//     .ds-edge-fade 配方完整（主窗材质单源不破）；D6 askDock.js 源不再渲染渐隐带元素。
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

/** 矩形平均亮度（毛玻璃批 2026-11-06 两点采样用；连续剖面 bandProfile 随列 mask
 * 载体退役删除 —— 逐泡 opacity 无带内连续渐变形态可采样）。 */
function meanLum(png, rc) {
  const { W, H, ch, out } = decodePng(png);
  const xa = Math.max(0, Math.round(rc.left + 6)), xb = Math.min(W - 1, Math.round(rc.right - 6));
  const ya = Math.max(0, Math.round(rc.top + 6)), yb = Math.min(H - 1, Math.round(rc.bottom - 6));
  let s = 0, n = 0;
  for (let y = ya; y <= yb; y++) for (let x = xa; x <= xb; x++) {
    const i = (y * W + x) * ch;
    s += 0.299 * out[i] + 0.587 * out[i + 1] + 0.114 * out[i + 2]; n++;
  }
  return n ? +(s / n).toFixed(1) : 0;
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

    /* ── 几何 + 计算样式读数（A/T/C/E 组共用）────────────────────────────
       veil 已退役（②-批）⇒ 不再读 .ask-veil 几何；改读「渐隐带元素计数 + 三个容器
       的计算样式（背景/边框/浮影/backdrop-filter/mask）」。 */
    const geo = await page.evaluate(() => {
      const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: +b.top.toFixed(1), bottom: +b.bottom.toFixed(1), left: +b.left.toFixed(1), right: +b.right.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
      const colwrap = document.querySelector('.ask-colwrap');
      const col = document.querySelector('.ask-col');
      const dock = document.querySelector('.ask-dock');
      const input = document.querySelector('.ask-input');
      const send = document.querySelector('.ask-input .ask-send');
      const mdock = document.getElementById('mobile-dock');
      const cs = (el) => (el ? getComputedStyle(el) : null);
      const fadeH = col ? parseFloat(cs(col).getPropertyValue('--ask-fade-h')) || 0 : 0;
      // 透明/无边框/无浮影读数（T 组）：背景色透明等价形有多解（transparent /
      // rgba(0, 0, 0, 0)），统一判 alpha=0；背景图必须 none（否则 = 一块渐变色块）。
      const paint = (el) => {
        if (!el) return null;
        const g = cs(el);
        const bc = String(g.backgroundColor).replace(/\s+/g, '');
        const alphaOf = (s) => {
          const m = s.match(/^rgba?\(([^)]+)\)$/);
          if (!m) return s === 'transparent' ? 0 : 1;
          const parts = m[1].split(',').map((v) => parseFloat(v));
          return parts.length >= 4 ? parts[3] : 1;
        };
        return {
          bgColor: bc, bgAlpha: alphaOf(bc), bgImage: String(g.backgroundImage),
          borderStyle: g.borderTopStyle, borderWidth: parseFloat(g.borderTopWidth) || 0,
          borderRightWidth: parseFloat(g.borderRightWidth) || 0,
          boxShadow: String(g.boxShadow),
          backdrop: String(g.backdropFilter || g.webkitBackdropFilter || 'none'),
          mask: String(g.maskImage || g.webkitMaskImage || 'none'),
          pointerEvents: g.pointerEvents,
        };
      };
      return {
        colwrap: r(colwrap), col: r(col), dock: r(dock), input: r(input), send: r(send), mobileDock: r(mdock),
        fadeH,
        colClientH: col ? col.clientHeight : 0,
        colPadTop: col ? parseFloat(cs(col).paddingTop) || 0 : 0,
        // 带净空在滚动容器内（padding box 才是裁剪/渐隐区）⇒ 静态可见内容高 = client − padding-top
        colContentH: col ? Math.round(col.clientHeight - (parseFloat(cs(col).paddingTop) || 0)) : 0,
        colScrollH: col ? col.scrollHeight : 0,
        // A0：面板内渐隐带元素计数（①-批形态 = 1，②-批 = 0）
        fadeLayerCount: document.querySelectorAll('.ask-dock .ds-edge-fade, .ask-dock .ask-veil').length,
        veilEls: document.querySelectorAll('.ask-veil').length,
        // A0d：面板内「带渐变背景的元素」（= 可见过渡条/色块的 DOM 形态；作者令
        // 「而不是放一个过渡的条在哪」⇒ 面板内不许有任何真渐变背景的层）。
        // 排除两类非目标：① .ask-bub 消息内容（气泡自带材质域）；② 退化平涂渐变
        // （全 stop 同色 = 平涂色块的 CSS 写法，如用户气泡 sapphire tint 复合层——
        // 不是「过渡条」形态）。veil 遮罩条 = 0.92→0.45→0 三档真渐变 ⇒ 必红。
        gradientLayers: dock
          ? Array.from(dock.querySelectorAll('*'))
            .filter((e) => !e.hasAttribute('data-test-fixture') && !e.classList.contains('ask-bub'))
            .map((e) => ({ cls: String(e.className || e.tagName), img: getComputedStyle(e).backgroundImage }))
            .filter((x) => {
              if (!x.img || x.img === 'none') return false;
              const stops = (x.img.match(/rgba?\([^)]*\)|#[0-9a-fA-F]{3,8}/g) || []);
              if (stops.length === 0) return true;
              return !stops.every((s) => s === stops[0]);
            })
          : [],
        paintDock: paint(dock), paintColwrap: paint(colwrap), paintCol: paint(col),
        colwrapMask: cs(colwrap) ? String(cs(colwrap).maskImage) : null,
        colMask: cs(col) ? String(cs(col).maskImage) : null,
        viewportH: window.innerHeight,
        tableFont: (() => { const td = document.querySelector('.ask-bub.ai table td'); return td ? cs(td).fontSize : null; })(),
      };
    });
    readings.geo = geo;

    /* ── A0/A0b 渐隐本体 = 内容级 mask（②-批核心判据：不是一条遮罩条）──────── */
    // A0「过渡条彻底移除」：面板内不许存在任何渐隐带/遮罩层元素（.ds-edge-fade 成员
    // 在 Ask 面板 = ①-批的 .ask-veil 遮罩条；它带 veil 渐层底色 ⇒ 可见色块）。
    check('A0 面板内零渐隐带遮罩元素（.ds-edge-fade / .ask-veil 计数 = 0；①-批形态必红）',
      geo.fadeLayerCount === 0 && geo.veilEls === 0,
      JSON.stringify({ inDock: geo.fadeLayerCount, veilAnywhere: geo.veilEls }));
    // A0d「不许放一个过渡的条在哪」：面板内任何元素都不许带渐变背景（遮罩条/色块
    // 的通用 DOM 形态；夹具探针 data-test-fixture 除外）。veil 遮罩条 = background
    // linear-gradient ⇒ ①-批形态必红。
    check('A0d 面板内零带渐变背景的元素（无任何过渡条/色块层；①-批 veil 必红）',
      (geo.gradientLayers || []).length === 0,
      JSON.stringify(geo.gradientLayers));
    // A0b 再改判（毛玻璃批 2026-11-06，作者令「聊天气泡使用我们的毛玻璃效果 显然
    // 它使用错了 现在是半透明的效果」）：列 mask **整体退役** —— mask 使 .ask-col
    // 成为 backdrop root，子气泡的 backdrop-filter 只能采样本子树（=透明），模糊
    // 被架空 =「半透明洗色」假玻璃（改前像素实证 diff=0.22，改后 11.9）。判据换成
    // 双面钉：运行时 .ask-col 计算样式 mask-image = none；实现载体在场 = askDock.js
    // 源含 syncAskFade（逐泡 opacity：泡顶到列顶距离在 --ask-fade-h 内线性落 alpha）。
    const askDockSrc = readFileSync(join(WEB, 'js', 'askDock.js'), 'utf8');
    check('A0b 列零 mask（backdrop root 根因摘除，.ask-col 计算样式 mask-image = none）+ 渐隐载体在场（askDock.js syncAskFade 逐泡 opacity）',
      String(geo.colMask || 'none') === 'none' && /syncAskFade/.test(askDockSrc),
      JSON.stringify({ colMask: String(geo.colMask || 'none'), syncAskFadeInSrc: /syncAskFade/.test(askDockSrc) }));
    check('A0c 外框零 mask（渐隐不在容器上另垫一层）', String(geo.colwrapMask || 'none') === 'none', String(geo.colwrapMask));

    /* ── T 整体透明无边框（②-批：面板本体零底色/零边框/零分隔线/零内阴影）──── */
    const paintChecks = [
      ['T1 面板/滚动容器背景透明（dock/colwrap/col 背景 alpha = 0 且无背景图）',
        (p) => !!p && p.bgAlpha === 0 && /^none$/.test(p.bgImage),
        (p) => ({ bg: p && p.bgColor, img: p && p.bgImage })],
      ['T2 无边框/无分隔线（dock/colwrap/col 边框宽 = 0 或 style none）',
        (p) => !!p && (p.borderWidth === 0 || /^none$/.test(p.borderStyle)) && p.borderRightWidth === 0,
        (p) => ({ w: p && p.borderWidth, style: p && p.borderStyle, rw: p && p.borderRightWidth })],
      ['T3 无浮影/无内阴影（dock/colwrap/col box-shadow = none）',
        (p) => !!p && /^none$/.test(p.boxShadow), (p) => p && p.boxShadow],
      ['T4 面板零 backdrop-filter（不磨玻璃 = 不会成为一块乳白罩）',
        (p) => !!p && /^none$/.test(p.backdrop), (p) => p && p.backdrop],
    ];
    for (const [name, pred, detail] of paintChecks) {
      const pass = pred(geo.paintDock) && pred(geo.paintColwrap) && pred(geo.paintCol);
      check(name, !!pass, JSON.stringify({ dock: detail(geo.paintDock), colwrap: detail(geo.paintColwrap), col: detail(geo.paintCol) }));
    }

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
    // F1 短会话不雾化：内容级 mask 的衰减区 = 滚动容器顶 --ask-fade-h，列 padding-top
    // ≥ 该高 ⇒ 静态（会话短）时首条落在衰减区之下、完整清晰；滚动时消息穿过衰减区
    // 自然淡到 0（「渐隐只作用于视觉」两件事互不打架）。
    check('F1 静态内容让开衰减区（colPaddingTop ≥ --ask-fade-h；会话短时首条不被洗掉）',
      !!geo.col && geo.fadeH > 0 && geo.colPadTop >= geo.fadeH - 0.5,
      JSON.stringify({ padTop: geo.colPadTop, fadeH: geo.fadeH, contentTop: geo.col && +(geo.col.top + geo.colPadTop).toFixed(1) }));
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

    /* ── A/B 渐隐带（毛玻璃批 2026-11-06 判据换代：双探针两点跨度）────────
       渐隐载体已从列 mask 换成**逐泡 opacity**（askDock.js syncAskFade）——
       mask 的「带内连续 alpha 剖面」概念随之消失：单条气泡在带内是**整条统一
       alpha**（泡顶深度决定），不存在泡内渐变。等义判据 =
       「深度浅的探针被洗到近背景（alpha→0）、深度深的探针全实（alpha=1）」
       两点亮度跨度 ≥60（渐隐真实存在且非 0/1 两态）+ 带顶探针 ≥190（洗到只剩
       背景）。探针必须挂 `.ask-bub` 类（syncAskFade 只 fade 气泡族）。 */
    await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const mk = (id) => {
        const d = document.createElement('div');
        d.id = id;
        d.className = 'ask-bub';            // 载体换代：探针 = 泡形（syncAskFade 的作用域）
        d.setAttribute('data-test-fixture', 'fade-band-ink');
        d.style.cssText = 'flex:none;width:100%;height:30px;background:#1f242b;border-radius:8px;';
        return d;
      };
      const pb = mk('__fade_probe_b');
      const pa = mk('__fade_probe_a');
      // 探针铺**顶**（列 mask 时代的尾插法够不着顶带：尾插探针在滚动尽头也只能停在
      // 列底净空之上，永远进不了顶部衰减档）。DOM 序 A 在前 B 在后。
      col.insertBefore(pb, col.firstChild);
      col.insertBefore(pa, pb);
      col.scrollTop = 0;                    // 先归零（开场 render 滚在最大值）⇒ scroll 事件先算一拍
    });
    await page.waitForTimeout(160);         // 渐隐 rAF 拍（scrollTop=0 → syncAskFade）
    // 对表滚动：探针 a 顶 = 列顶 + 2px（带内浅层 ⇒ alpha = 2/12 ≈ 0.17）；探针 b 顶 =
    // 列顶 + 38px（30px 泡高 + 6px 列缝，带外 ⇒ alpha = 1）。
    await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const pa = document.getElementById('__fade_probe_a');
      const ct = col.getBoundingClientRect().top;
      const pt = pa.getBoundingClientRect().top;
      col.scrollTop += (pt - (ct + 2));
    });
    await page.waitForTimeout(600);   // 渐隐 rAF 拍落定 + 版面稳定

    const geo2 = await page.evaluate(() => {
      const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { top: +b.top.toFixed(1), bottom: +b.bottom.toFixed(1), left: +b.left.toFixed(1), right: +b.right.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
      const col = document.querySelector('.ask-col');
      const fadeH = parseFloat(getComputedStyle(col).getPropertyValue('--ask-fade-h')) || 0;
      const oA = document.getElementById('__fade_probe_a');
      const oB = document.getElementById('__fade_probe_b');
      return {
        col: r(col), fadeH,
        probeA: r(oA), probeB: r(oB),
        opacityA: oA ? getComputedStyle(oA).opacity : null,
        opacityB: oB ? getComputedStyle(oB).opacity : null,
        scrollTop: col.scrollTop, scrollH: col.scrollHeight, clientH: col.clientHeight,
      };
    });
    readings.geo2 = geo2;

    // A3-结构：浅层探针确实铺住衰减带顶（probeA.top ≤ 列顶 + fadeH ⇒ 量的是
    // 「泡形内容被逐泡 opacity 洗掉」的过程，不是空白）
    const bandTop = geo2.col.top;
    const bandH = geo2.fadeH;
    check('A3-结构 浅层探针铺住衰减带顶（probeA.top ∈ [col.top, col.top + fadeH]）',
      !!geo2.probeA && geo2.probeA.top >= bandTop - 1 && geo2.probeA.top <= bandTop + bandH + 1,
      JSON.stringify({ probeATop: geo2.probeA && geo2.probeA.top, bandTop, bandH }));

    // 渐隐衰减区 = 列顶 fadeH（内容级 mask 起点透明 → fadeH 处全实）。
    // 面板整体透明（T1）⇒ 衰减区背后透出文档正文文字 —— 为让亮度剖面只反映
    // 「探针内容 alpha」而非背后文字噪声，采样前沿列矩形垫一块纯底色板（夹具操作：
    // 只改采样环境、不触产品代码；实况截图已在上面取好，是真实透底状态）。
    await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const cb = col.getBoundingClientRect();
      const slab = document.createElement('div');
      slab.id = '__fade_backdrop';
      slab.setAttribute('data-test-fixture', 'uniform-backdrop');
      slab.style.cssText = 'position:fixed;pointer-events:none;z-index:255;background:#f5f6f8;'
        + `left:${cb.left - 4}px;top:${cb.top - 4}px;width:${cb.width + 8}px;height:${cb.height + 8}px;`;
      document.body.appendChild(slab);
    });
    await page.waitForTimeout(120);

    // 两点采样（判据换代见上）：底色板垫底 ⇒ 亮度只反映探针本体 alpha。整屏实拍
    // 一次，对两个探针矩形（meanLum 收 6px 避开圆角抗锯齿）各取平均亮度：探针
    // #1f242b（暗 ≈35）在板 #f5f6f8（亮 ≈246）上 —— alpha→0 ⇒ ≈246；alpha=1 ⇒ ≈35。
    const shot = await page.screenshot();
    writeFileSync(join(OUT, 'ask-sheet-fade-probes.png'), shot);
    const L_A = meanLum(shot, geo2.probeA);
    const L_B = meanLum(shot, geo2.probeB);
    // 跨度方向：亮板（#f5f6f8）上「被洗掉的探针」= 亮（≈板色 246）、全实探针 = 暗
    // （≈泡色 35）⇒ 跨度 = L_A − L_B（带顶探针 − 带外探针）。
    const span = +(L_A - L_B).toFixed(1);
    // A2/A2b/A5（带内连续剖面/单调性/接缝斜率）随列 mask 载体退役：逐泡 opacity 是
    // 泡级 alpha —— 单条泡在带内整条统一透明度（泡顶深度决定），不存在带内连续
    // 渐变剖面，「直线边界/接缝」在新载体里没有对应物理形态（毛玻璃批 2026-11-06）。
    readings.fade = { L_A, L_B, span, opacityA: geo2.opacityA, opacityB: geo2.opacityB };
    check('A1 渐隐两点亮度跨度 ≥60（带顶探针近背景亮 L_A − 带外探针全实暗 L_B；渐隐真实存在必绿）', span >= 60,
      `L_A=${L_A} L_B=${L_B} span=${span} opacityA=${geo2.opacityA} opacityB=${geo2.opacityB}`);
    check('A3 带顶探针被洗到只剩背景（探针区亮度 ≥190 = 深色泡在带顶 alpha→0）', L_A >= 190, `L_A=${L_A}`);

    /* ── B 渐隐不影响交互 ─────────────────────────────────────────────── */
    const hits = await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const fadeH = parseFloat(getComputedStyle(col).getPropertyValue('--ask-fade-h')) || 0;
      const cb = col.getBoundingClientRect();
      const bandTop2 = cb.top;
      const xs = [cb.left + cb.width / 2, cb.left + 40, cb.right - 40];
      const ys = [bandTop2 + fadeH * 0.25, bandTop2 + fadeH * 0.5, bandTop2 + fadeH * 0.8];
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
    // B1 判据细化（2026-11-05）：带内命中目标 = 滚动容器/内容本体。**允许**命中
    // `.ds-resize-handle`——那是浮在面板左上角（带角）上的拖拽控件本体（z3 / 44×44
    // 热区 / pointer-events 自动），它挡住的是「拖拽」这条交互、不是 mask 产物；旧
    // 写法在带高 56px 时也会命中它（改前树同红，属长期误判）。真正要排除的是
    // 遮罩层（.ask-veil / .ds-edge-fade）吃事件。
    const isHandle = (h) => /ds-resize-handle/.test(String(h.tag || ''));
    check('B1 衰减区内命中滚动容器/内容（内容级 mask 不裁 hit-test；除左上角拖拽控件本体外不得有遮罩层拦手势）',
      hits.every((h) => (h.inCol && !h.isVeil) || (isHandle(h) && !h.isVeil)),
      JSON.stringify(hits.map((h) => `${h.y}:${h.tag}`)));
    check('B3 内容宿主可交互（.ask-col pointer-events ≠ none）', geo.paintCol && geo.paintCol.pointerEvents !== 'none',
      geo.paintCol && geo.paintCol.pointerEvents);

    // 先落到可滚中段（滚轮要有余量才滚得动）
    const beforeScroll = await page.evaluate(() => {
      const c = document.querySelector('.ask-col');
      const mid = Math.max(0, Math.round((c.scrollHeight - c.clientHeight) / 2));
      c.scrollTop = mid;
      return c.scrollTop;
    });
    const wheelAt = await page.evaluate(() => {
      const col = document.querySelector('.ask-col');
      const fadeH = parseFloat(getComputedStyle(col).getPropertyValue('--ask-fade-h')) || 0;
      const cb = col.getBoundingClientRect();
      return { x: Math.round(cb.left + cb.width / 2), y: Math.round(cb.top + fadeH * 0.4) };
    });
    // 先滚回带区（滚轮会移动内容；用 scrollTop 复位保证采样可复现）
    await page.evaluate(({ y }) => { const c = document.querySelector('.ask-col'); c.scrollTop = y; }, { y: beforeScroll });
    await page.waitForTimeout(120);
    await page.mouse.move(wheelAt.x, wheelAt.y);
    await page.mouse.wheel(0, 120);
    await page.waitForTimeout(260);
    const afterScroll = await page.evaluate(() => document.querySelector('.ask-col').scrollTop);
    readings.wheel = { beforeScroll, afterScroll };
    check('B2 衰减区内滚轮真的滚得动（内容级 mask 不裁滚动手势）', afterScroll - beforeScroll >= 10,
      JSON.stringify({ beforeScroll, afterScroll }));

    /* ── 实况截图：面板滚到中段（气泡接近上缘自然淡出、无条无色块）────────
       先拆夹具（探针 + 底色板），回到真实透底状态。 */
    await page.evaluate(() => {
      document.getElementById('__fade_probe_a')?.remove();
      document.getElementById('__fade_probe_b')?.remove();
      document.getElementById('__fade_backdrop')?.remove();
      const c = document.querySelector('.ask-col');
      c.scrollTop = Math.max(0, Math.round((c.scrollHeight - c.clientHeight) / 2));
    });
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(OUT, 'ask-sheet-midscroll.png') });
    const mTop = Math.max(0, Math.round(geo.col.top - 24));
    await page.screenshot({
      path: join(OUT, 'ask-sheet-midscroll-band.png'),
      clip: { x: 0, y: mTop, width: 390, height: Math.min(260, 844 - mTop) },
    });

    /* ── D 爆炸半径（本批只改 Ask：主对话窗 #g2-edge-fade 原样不动）──────── */
    const d = await page.evaluate(() => {
      const mainFade = document.getElementById('g2-edge-fade');
      const askDock = document.querySelector('.ask-dock');
      const pick = (el) => {
        if (!el) return null;
        const cs = getComputedStyle(el);
        return {
          cls: String(el.className),
          bg: cs.backgroundImage,
          bf: cs.backdropFilter || cs.webkitBackdropFilter,
          mask: cs.maskImage || cs.webkitMaskImage,
          pe: cs.pointerEvents,
        };
      };
      return {
        mainFade: pick(mainFade),
        askFadeLayers: askDock ? askDock.querySelectorAll('.ds-edge-fade, .ask-veil').length : -1,
      };
    });
    readings.shared = d;
    check('D1 主对话窗 #g2-edge-fade 仍在且仍挂 .ds-edge-fade（本批不动主窗）',
      !!d.mainFade && /ds-edge-fade/.test(d.mainFade.cls) && /linear-gradient/.test(d.mainFade.mask),
      JSON.stringify(d.mainFade && { cls: d.mainFade.cls, mask: String(d.mainFade.mask).slice(0, 40) }));
    check('D1b Ask 面板内零 .ds-edge-fade/ask-veil 成员（与主窗解耦，不再共用遮罩条手法）', d.askFadeLayers === 0, `count=${d.askFadeLayers}`);

    // 静态面：ask.css 源零 veil 痕迹 + .ask-col 块承载内容级 mask；主窗单源纪律不变
    const blockOf = (file, selector) => {
      const src = readFileSync(join(WEB, 'css', file), 'utf8');
      const i = src.indexOf(selector);
      if (i < 0) return '';
      const open = src.indexOf('{', i);
      const close = src.indexOf('}', open);
      return open < 0 || close < 0 ? '' : src.slice(open, close);
    };
    const askCssSrc = readFileSync(join(WEB, 'css', 'ask.css'), 'utf8');
    const askColBlock = blockOf('ask.css', '.ask-col {');
    const g2Block = blockOf('glass.css', '#g2-edge-fade {');
    const dsBlock = blockOf('design-system.css', '.ds-edge-fade {');
    const askDockJs = readFileSync(join(WEB, 'js', 'askDock.js'), 'utf8');
    // 注释剥离后再判「源零 veil 痕迹」（注释里叙述退役史属正常，不算实现残留）
    const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');
    const askCssCode = stripComments(askCssSrc);
    const askDockJsCode = stripComments(askDockJs);
    readings.blocks = { askColBlock: askColBlock.slice(0, 500), g2Block: g2Block.slice(0, 400), dsBlock: dsBlock.slice(0, 700) };
    // D2 判据收窄（2026-11-04 12:52 批之后）：ask.css 里出现的 backdrop-filter 只允许
    // 属于**轮钮/气泡玻璃档**（作者令「底部按钮毛玻璃照抄顶部两个按钮」+「气泡吃顶部
    // 两钮那套毛玻璃」——那是显式授权的材质面）。veil 的病根 = 把材质挂到**列/柱/遮罩
    // 元素**上（遮罩条才需要自带底与 blur）。所以判据改成：零 .ask-veil 选择器、零
    // --ds-fade-veil、且列/柱元素块内零 backdrop-filter。
    const bfOnColumn = /\.ask-col(wrap)?[^{}]*\{[^}]*backdrop-filter/.test(askCssCode);
    check('D2 ask.css 实现面零 veil 材质（无 .ask-veil / 无 --ds-fade-veil / 列与柱元素上零 backdrop-filter）',
      !/\.ask-veil/.test(askCssCode) && !/--ds-fade-veil/.test(askCssCode) && !bfOnColumn,
      JSON.stringify({ askVeilSelector: /\.ask-veil/.test(askCssCode), veilTok: /--ds-fade-veil/.test(askCssCode), bfOnColumn, bfAnywhere: /backdrop-filter/.test(askCssCode) }));
    // D3 判据换代（毛玻璃批 2026-11-06）：渐隐唯一实现 = askDock.js syncAskFade
    // 逐泡 opacity；ask.css 源（剥注释）零 mask-image —— 防「列 mask」复活（复活即
    // 重新架空气泡玻璃 = 缺陷①回归）。
    check('D3 ask.css 源零 mask-image（列 mask 退役不复活）+ askDock.js syncAskFade 在场（渐隐唯一实现）',
      !/mask-image/.test(askCssCode) && /syncAskFade/.test(askDockJsCode),
      JSON.stringify({ askCssMaskImage: /mask-image/.test(askCssCode), syncAskFade: /syncAskFade/.test(askDockJsCode) }));
    check('D4 glass.css #g2-edge-fade 块内零材质声明（主窗材质只在 .ds-edge-fade 一处）',
      !!g2Block && !/backdrop-filter/.test(g2Block) && !/gradient/.test(g2Block),
      g2Block.replace(/\s+/g, ' ').slice(0, 120));
    check('D5 design-system.css 的 .ds-edge-fade 配方完整（主窗单源不破：veil 渐层 + blur/saturate + mask + pointer-events:none）',
      !!dsBlock && /var\(--ds-fade-veil\)/.test(dsBlock) && /blur\(var\(--ds-fade-blur\)\)/.test(dsBlock)
      && /saturate\(var\(--ds-fade-sat\)\)/.test(dsBlock) && /mask-image/.test(dsBlock) && /pointer-events:\s*none/.test(dsBlock),
      dsBlock.replace(/\s+/g, ' ').slice(0, 160));
    check('D6 askDock.js 实现面不再渲染渐隐带元素（无 ds-edge-fade / ask-veil 挂载）',
      !/ds-edge-fade/.test(askDockJsCode) && !/ask-veil/.test(askDockJsCode),
      JSON.stringify({ edgeFade: /ds-edge-fade/.test(askDockJsCode), veil: /ask-veil/.test(askDockJsCode) }));

    check('Z 页面零 pageerror', !(readings.pageErrors && readings.pageErrors.length), JSON.stringify(readings.pageErrors || []));

    writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
    if (failures.length) throw new Error(`doc ask fade/height assertions failed (${failures.length}): ${failures.join(' | ')}`);
  });
});
