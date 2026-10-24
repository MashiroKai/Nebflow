// viewers/pptx.js — PPTX viewer：转换优先（服务端 soffice→PDF / qlmanage 首页 PNG，
// canvas-pptx-convert 批 2026-10-24 作者令「服务端转换优先」）＋ 纯前端静态渲染兜底
// （路线 A MVP，作者 2026-10-06 拍板令，结构原样保留）。
//
// 渲染腿阶梯（viewPptx 顶层的顺序即阶梯）：
//   1. 服务端转换腿 GET /api/nf-office（absPath 在场才有）：
//      soffice→整件 PDF → **复用既有 pdf 面板**（viewers/pdf.js，作者令「PDF 预览
//      复用既有 pdf 面板」）；qlmanage→首页 PNG → 首页预览卡＋下载链。
//      票据同族：同一张 `POST /api/nf-ticket` 铸的 per-path 票（零新增凭据面），
//      服务端缓存命中（X-Nf-Office-Cache: hit）时二次点击零重转。
//   2. 纯前端渲染腿（下文 ①-⑦，路线 A）：转换器缺席（501）/转换全败（502）/
//      无 absPath（附件 blob 腿）时的兜底——13MB 级重 deck 实测会触发 staging 30s
//      渲染超时落降级卡（这正是转换腿成为主腿的原因）。
//   3. 终态降级卡：「此环境无预览能力」＋下载按钮（不许裸失败——路线 A 的
//      renderDownloadFallback 承接，文案批内升级）。
//
// 路线 A 架构（任务书七问打包值逐条落）：
//   1. 渲染库 = @aiden0z/pptx-renderer 1.3.0（Apache-2.0，vendor/pptx-renderer/ 台账，
//      POC 四闸过：保真抽样 6/6、zipLimits 预算闸实测触发、维护度 2026-09 活跃）。
//      库源码只 fetch 成字符串（单飞 Promise），在 staging 帧内 blob-module import——
//      宿主页从头到尾不 eval 库代码，esbuild 图谱零接触（无动态 import 边界告警面）。
//   2. 容器 = 双层沙箱 iframe：
//      ① staging 帧（sandbox="allow-scripts"，无 allow-same-origin ⇒ opaque origin，
//         读不到宿主 DOM/cookie/storage）：库在帧内离屏渲染；帧内 CSP
//         `default-src 'none'` 把全部远端加载在网络层硬断——POC 实测裸库在宿主页
//         staging 会把外链 <img src=http://…> 真发出去（sink 实收在案），CSP 是
//         「DOM/网络层无远程请求」读数的唯一硬保证；违例计数经桥回传（diag.extBlocked）。
//      ② 产物帧（sandbox=""：禁脚本/禁表单/禁弹窗/禁顶层导航）：只装中和后的静态
//         产物，零脚本零信道。
//      渲染产物永不进宿主同源 DOM。两帧之间 = 一对 postMessage（render 请求 / product
//      回包）——即任务书所说「窄消息桥」；翻页/缩放由父页直控产物帧的尺寸与 srcdoc
//      重灌完成，产物帧内无可被消息驱动的东西。
//   3. zip 预算闸 = shared.js ZIP_BUDGET 预算族（entry 32MB / total 128MB，
//      打开闸 100MB 派生——见 shared.js 常量注释），经 zipLimits 喂给库。
//   4. 大件行为：源件 >50MB（MAX_SOURCE_BYTES）拒绝渲染 → 可见降级下载链。
//   5. 引用姿势 = 白名单不透传：内联媒体 blob: → data: 重写；其余（http/https 外链
//      图片/音视频/超链接/样式 url()）一律落占位或剥除（docx.js:48-62 纪律，不留口子）。
//      中和跑在 staging 帧内（产物侧），CSP 跑在 staging 帧头（网络侧）——双侧独立生效。
//   6. .ppt 处置 = 魔数嗅探：zip `PK` 继续渲染；CFB `D0 CF 11 E0`（含 PowerPoint
//      加密 pptx——加密 OOXML 即 CFB 容器）与未知魔数 → 下载链腿（行为保留，
//      注册面/白名单零改动）。
//   7. MVP 范围 = .pptx 静态保真预览 + 上下滚动翻页 + 缩放（enableViewerZoom 复用）。
//      缩略图侧栏/锚点跳页/.ppt 缓存/系统交接 = 增强池另批。

import { escapeHtml, formatSize, ZIP_BUDGET } from './shared.js';
import { mintTickets, ticketUrl } from '../nfTicket.js';
import { nfViewerUrl } from '../nfFileCache.js';
import { enableViewerZoom } from './zoom.js';
import pdfViewer from './pdf.js';

/** 渲染闸（与打开闸分工：100MB 闸管进页面，本闸管渲不渲）。派生：单 tab 内存峰值
 *  包络——解压族 128MB + data: 重写 ~1.37× + srcdoc 字符串驻留，源件 50MB 以上
 *  的 deck 预览收益不抵 tab 内存代价。 */
const MAX_SOURCE_BYTES = 50 * 1024 * 1024;

/** 库资产 URL（懒加载；不进开屏包——pdf.js:7-8 先例）。版本 pin 与 vendor 台账同步。 */
const LIB_URL = '/vendor/pptx-renderer/aiden0z-pptx-renderer.browser.es.js';

/** @type {Promise<string>|null} 单飞：库源码字符串（每 tab 渲染复用，不再重复 fetch）。 */
let _libSrcReady = null;
function loadPptxRendererSource() {
  if (_libSrcReady) return _libSrcReady;
  _libSrcReady = (async () => {
    const resp = await fetch(LIB_URL);
    if (!resp.ok) throw new Error('renderer asset fetch HTTP ' + resp.status);
    return await resp.text();
  })();
  return _libSrcReady;
}

/** 外链占位（docx.js:48-62 SVG 占位纪律的 pptx 变体；不落任何远端字节）。 */
const EXTERNAL_PLACEHOLDER =
  'data:image/svg+xml;utf8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="64">' +
    '<rect width="100%" height="100%" fill="#f0f0f0" rx="8"/>' +
    '<text x="120" y="37" text-anchor="middle" font-size="12" fill="#999">' +
    'external reference — not loaded in preview</text></svg>');

/** staging 帧头 CSP——网络层硬闸：除 data:/blob:（自包含内联）外一律禁载。
 *  script-src 'unsafe-inline'：主函数以内联 <script> 内嵌（srcdoc 是我们自己铸的）；
 *  blob: 供库模块 import。worker-src 'none'：pdfjs:false 已关 EMF-PDF 后备腿，
 *  库在 MVP 路径不需要 worker。 */
const STAGE_CSP =
  "default-src 'none'; script-src 'unsafe-inline' blob:; style-src 'unsafe-inline' data:; " +
  'img-src data: blob:; media-src data: blob:; font-src data: blob:; ' +
  "connect-src blob:; worker-src 'none'; base-uri 'none'; form-action 'none'";

/** staging 帧内主函数（经 .toString() 内嵌进 srcdoc，必须完全自包含、不闭包任何外部量）。
 *  职责：收 render 请求 → 帧内 import 库 → open 渲染 → 三遍中和序列化 → 回传产物。 */
function nfPptxStageMain() {
  // 注：Chromium 对 sandbox srcdoc 帧不向 document 派发 securitypolicyviolation
  // 事件（实测），故不做违例计数——网络层保证由 CSP 强制执行本身承担（驱动侧以
  // requestfailed reason=csp + sink 零命中取证），读数走中和器计数（extNeutralized）。
  window.addEventListener('message', (ev) => {
    const msg = ev.data || {};
    if (msg.type !== 'nf-pptx-render') return;
    /** @param {any} m */
    const done = (m) => { try { window.parent.postMessage(m, '*'); } catch (_) { /* 帧已亡 */ } };
    (async () => {
      try {
        const modUrl = URL.createObjectURL(new Blob([msg.libSrc], { type: 'text/javascript' }));
        const lib = await import(modUrl);
        const stage = document.getElementById('stage');
        // open() 在全部 slide 挂载并 rendercomplete 之后才 resolve（POC 实测：回调先于
        // open resolve 触发——不能拿回调当完成信号，会序列化到半程 DOM）。
        const viewer = await Promise.race([
          lib.PptxViewer.open(msg.buf, stage, msg.openOptions),
          new Promise((_, rej) => setTimeout(() => rej(new Error('render timeout 30s')), 30000)),
        ]);
        // ── 三遍中和序列化（问题 5：白名单不透传，不留口子）──
        let blobRewrites = 0, extNeutralized = 0;
        const isExternal = (v) => /^https?:\/\//i.test(v) || /^\/\//.test(v);
        // a) canvas 像素快照（echarts 图表腿：canvas 像素不经 innerHTML 序列化）
        for (const c of Array.from(stage.querySelectorAll('canvas'))) {
          try {
            const img = document.createElement('img');
            img.src = c.toDataURL('image/png');
            if (c.className) img.className = c.className;
            const st = c.getAttribute('style');
            if (st) img.setAttribute('style', st);
            c.replaceWith(img);
          } catch (_) { /* 单 canvas 失败不拖垮整页 */ }
        }
        // b) blob: → data:（产物帧为无 allow-same-origin 沙箱，宿主 blob: 不可达；
        //    同时这也是「媒体进产物＝自包含」的必经步）
        const blobEls = Array.from(stage.querySelectorAll('*'))
          .filter((el) => Array.from(el.attributes).some((a) => a.value.startsWith('blob:')));
        for (const el of blobEls) {
          for (const a of Array.from(el.attributes)) {
            if (!a.value.startsWith('blob:')) continue;
            try {
              const resp = await fetch(a.value); // 帧内自读自身 blob（connect-src blob:）
              const blob = await resp.blob();
              el.setAttribute(a.name, await new Promise((res, rej) => {
                const fr = new FileReader();
                fr.onload = () => res(String(fr.result));
                fr.onerror = () => rej(fr.error);
                fr.readAsDataURL(blob);
              }));
              blobRewrites++;
            } catch (_) { /* blob 已失效 → 留原样，产物帧内不可达即惰性 */ }
          }
        }
        // c) 外链中和：img → SVG 占位；a[href] 剥导航；其余剥引用；style url() 剥除
        for (const el of Array.from(stage.querySelectorAll('[src], [href], [poster]'))) {
          for (const name of ['src', 'href', 'poster']) {
            const v = el.getAttribute(name);
            if (!v || !isExternal(v)) continue;
            if (name === 'href' && el.tagName === 'A') {
              el.removeAttribute('href');
              el.removeAttribute('target');
              el.setAttribute('title', 'external link — not loaded in preview');
            } else if (el.tagName === 'IMG') {
              el.setAttribute('src', msg.placeholder);
            } else {
              el.removeAttribute(name);
              el.setAttribute('data-nf-ext-stripped', '1');
            }
            extNeutralized++;
          }
        }
        for (const el of Array.from(stage.querySelectorAll('[style*="url("]'))) {
          const st = el.getAttribute('style') || '';
          const next = st.replace(/url\((['"]?)(https?:\/\/|\/\/)[^)]*\)/gi, 'none');
          if (next !== st) { el.setAttribute('style', next); extNeutralized++; }
        }
        // 切页几何（缩放/滚动几何都在父页算，产物帧内零脚本零信道）
        const units = Array.from(stage.children);
        const slideDims = units.map((u) => {
          const f = /** @type {HTMLElement} */ (u.firstElementChild);
          const box = (f && f.style && f.style.width) ? f : /** @type {HTMLElement} */ (u);
          return { w: box.offsetWidth || 960, h: box.offsetHeight || 720 };
        });
        const unitsHtml = units.map((u) => u.innerHTML);
        try { viewer.destroy(); } catch (_) { /* 析构幂等 */ }
        done({
          type: 'nf-pptx-product',
          payload: { unitsHtml, slideDims, blobRewrites, extNeutralized },
        });
      } catch (e) {
        done({ type: 'nf-pptx-error', message: String(e && e.message || e) });
      }
    })();
  });
}

/** staging 帧壳页（宿主壳最小化：CSP 一行 + 一条样式 + 自包含主函数）。 */
function stageShellHtml() {
  return '<!doctype html><html><head><meta charset="utf-8">' +
    '<meta http-equiv="Content-Security-Policy" content="' + STAGE_CSP + '">' +
    '<style>html,body{margin:0;padding:0;background:#fff;}#stage{width:960px;}</style>' +
    '</head><body><div id="stage"></div><script>(' + nfPptxStageMain.toString() +
    ')();<' + '/script></body></html>';
}

/** 可见降级腿 = 旧占位行为原样保留（信息卡 + 点击铸票下载链），加一行降级原因。
 *  纪律不变（2026-09-11 C batch）：href 不落任何凭据，票据在用户点击时才铸，
 *  blob 腿（objectUrl）天然免票据。
 *  canvas-pptx-convert 批：首行升级为作者令原话「此环境无预览能力」——终态降级
 *  必须显式说人话＋给下载，不许裸失败。 */
function renderDownloadFallback(pane, { absPath, fileName, size, objectUrl }, reason) {
  pane.innerHTML = `
    <div class="canvas-md-viewer" style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:var(--color-text-muted);">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" opacity="0.4">
        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
      </svg>
      <div style="text-align:center;">
        <div style="font-weight:600;color:var(--color-text);margin-bottom:4px">${escapeHtml(fileName || 'PowerPoint')}</div>
        <div style="font-size:12px;opacity:0.6">${formatSize(size || 0)}</div>
        <div style="font-size:13px;opacity:0.85;margin-top:6px">此环境无预览能力</div>
        <div style="font-size:12px;opacity:0.75;max-width:420px;margin-top:6px">${escapeHtml(reason)}</div>
      </div>
      <a class="nf-pptx-download" href="#" download="${escapeHtml(fileName || 'presentation.pptx')}" style="font-size:13px;color:var(--color-primary);text-decoration:none;padding:6px 16px;border:1px solid var(--color-primary);border-radius:8px;">Download to view</a>
    </div>`;

  const link = /** @type {HTMLAnchorElement|null} */ (pane.querySelector('a.nf-pptx-download'));
  if (!link || (!absPath && !objectUrl)) return;
  link.addEventListener('click', async (e) => {
    e.preventDefault();
    const url = objectUrl || await ticketUrl(absPath);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName || 'presentation.pptx';
    document.body.appendChild(a);
    a.click();
    a.remove();
  });
}

// ── 服务端转换腿（canvas-pptx-convert 批 · 主腿）─────────────────────────────
// GET /api/nf-office?path=&ticket= —— soffice→整件 PDF / qlmanage→首页 PNG。
// 票据 = 既有 per-path 铸票（mintTickets），无票 ⇒ 静默回路线 A（鉴权降级面已有
// 既有口径，本腿绝不登出绝不横幅）。200 → 按 Content-Type 分流（pdf 面板复用 /
// 首页预览卡）；501/502/415/网络错 ⇒ 回路线 A（原因记 diag，不裸失败）。

/** 转换腿取数超时：服务端单腿 150s ＋ 轮询/启动余量。 */
const OFFICE_CONVERT_TIMEOUT_MS = 180000;

/** viewer 自铸 blob URL 的 pane 生命周期账（nfFileCache.trackViewerUrl 同形，
 *  本模块自有小账：转换产物 PDF 不进 nf 账本——键族语义是源件字节）。回收点 =
 *  同 pane 重渲起点（viewPptx 首行）与转换腿接管前，防多次开文件累积。 */
const paneBlobUrls = new WeakMap();
function trackPaneBlobUrl(pane, url) {
  const urls = paneBlobUrls.get(pane) || [];
  urls.push(url);
  paneBlobUrls.set(pane, urls);
}
function revokePaneBlobUrls(pane) {
  const urls = paneBlobUrls.get(pane);
  if (!urls) return;
  for (const u of urls) { try { URL.revokeObjectURL(u); } catch (_) { /* 已失效 */ } }
  paneBlobUrls.delete(pane);
}

/** 服务端转换腿。返回 true = pane 已被转换产物接管（PDF 面板/首页卡）；
 *  false = 未转换（调用方落路线 A），失败原因已记 diag.convertSkipped。 */
async function tryOfficeConvert(pane, ctx, diag) {
  const { absPath, fileName } = ctx;
  if (!absPath) { diag.convertSkipped = 'no-absPath'; return false; }
  diag.convertPhase = 'minting-ticket';
  let ticket = '';
  try {
    const map = await mintTickets([absPath]);
    ticket = map.get(absPath) || '';
  } catch (_) { /* mintTickets 契约：绝不 reject——防御性兜底 */ }
  if (!ticket) { diag.convertSkipped = 'no-ticket'; return false; }
  diag.convertPhase = 'converting';
  setLoadingText(pane, 'Converting to PDF for preview (cached after first time)...');
  let resp;
  try {
    resp = await fetch('/api/nf-office?path=' + encodeURIComponent(absPath) +
      '&ticket=' + encodeURIComponent(ticket), { signal: AbortSignal.timeout(OFFICE_CONVERT_TIMEOUT_MS) });
  } catch (e) {
    diag.convertSkipped = 'fetch-failed: ' + String(e && e.message || e);
    return false;
  }
  if (!pane.isConnected) return true; // tab 已关：不再动 pane，也不再落路线 A
  if (!resp.ok) {
    diag.convertSkipped = 'HTTP ' + resp.status + ' (' +
      (resp.headers.get('x-nf-reason') || 'no-reason') + ')';
    return false; // 501 converter-absent / 502 conversion-failed / 415 … ⇒ 路线 A
  }
  const ctype = (resp.headers.get('content-type') || '').toLowerCase();
  diag.converter = resp.headers.get('x-nf-office-converter') || '';
  diag.convertCache = resp.headers.get('x-nf-office-cache') || '';
  const blob = await resp.blob().catch(() => null);
  if (!blob || !pane.isConnected) return true;
  if (ctype.includes('pdf') || blob.type === 'application/pdf') {
    // 整件 PDF → 复用既有 pdf 面板（作者令「PDF 预览复用既有 pdf 面板」）。
    diag.gateDecision = 'convert-pdf';
    const url = URL.createObjectURL(blob);
    trackPaneBlobUrl(pane, url);
    pane.classList.remove('scrollable');
    await pdfViewer.render(pane, {
      absPath,
      fileName: String(fileName || 'presentation').replace(/\.(pptx?|docx?|xlsx?)$/i, '') + '.pdf',
      anchor: undefined, // 转换腿无文档引用跳页语义
      objectUrl: url,
    });
    return true;
  }
  if (ctype.includes('image') || blob.type.startsWith('image/')) {
    // qlmanage 首页 PNG → 首页预览卡（如实注明只有首页）＋下载链。
    diag.gateDecision = 'convert-firstpage';
    renderFirstPagePreview(pane, ctx, blob, diag);
    return true;
  }
  diag.convertSkipped = 'unexpected content-type: ' + ctype;
  return false;
}

/** 首页 PNG 预览卡（qlmanage 兜底腿）：如实「仅首页」＋下载链。 */
function renderFirstPagePreview(pane, { absPath, fileName, size, objectUrl }, blob, diag) {
  const url = URL.createObjectURL(blob);
  trackPaneBlobUrl(pane, url);
  pane.classList.add('scrollable');
  pane.innerHTML = `
    <div class="canvas-md-viewer" style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:24px 0;">
      <img alt="${escapeHtml(fileName || 'preview')}" src="${url}"
        style="max-width:min(920px,94%);border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,.15);background:#fff;">
      <div style="font-size:12px;color:var(--color-text-muted);opacity:0.75;text-align:center;">
        Quick Look preview — first page only (${escapeHtml(diag.converter || 'qlmanage')})<br>
        ${escapeHtml(fileName || '')} · ${formatSize(size || 0)}
      </div>
      <a class="nf-pptx-download" href="#" download="${escapeHtml(fileName || 'presentation.pptx')}"
        style="font-size:13px;color:var(--color-primary);text-decoration:none;padding:6px 16px;border:1px solid var(--color-primary);border-radius:8px;">Download full file</a>
    </div>`;
  const link = /** @type {HTMLAnchorElement|null} */ (pane.querySelector('a.nf-pptx-download'));
  if (!link || (!absPath && !objectUrl)) return;
  link.addEventListener('click', async (e) => {
    e.preventDefault();
    const dlUrl = objectUrl || await ticketUrl(absPath);
    const a = document.createElement('a');
    a.href = dlUrl;
    a.download = fileName || 'presentation.pptx';
    document.body.appendChild(a);
    a.click();
    a.remove();
  });
}

/** 转换期加载文案（tryOfficeConvert 各阶段刷新；路线 A 起点会整体重写 pane）。 */
function setLoadingText(pane, text) {
  if (!pane.isConnected) return;
  const box = pane.firstElementChild;
  if (box && box.textContent !== undefined) box.textContent = text;
}

/** 壳页（产物帧：宿主壳最小化：一行样式 + 静态产物；无脚本、无外引）。 */
function buildShell(unitsHtml, slideDims, zoom) {
  const z = zoom;
  const boxes = slideDims.map((d, i) => {
    const inner = unitsHtml[i] || '';
    return `<div class="slidebox" style="width:${Math.round(d.w * z)}px;height:${Math.round(d.h * z)}px;">` +
      `<div style="width:${d.w}px;height:${d.h}px;transform:scale(${z});transform-origin:0 0;">${inner}</div></div>`;
  }).join('\n');
  return '<!doctype html><html><head><meta charset="utf-8"><style>' +
    'html,body{margin:0;padding:0;background:#f2f3f5;}' +
    '.deck{display:flex;flex-direction:column;align-items:center;padding:24px 0;gap:16px;}' +
    '.slidebox{background:#fff;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.15);}' +
    '</style></head><body><div class="deck">' + boxes + '</div></body></html>';
}

/** PPTX viewer — 腿阶梯：服务端转换（soffice→PDF 复用 pdf 面板 / qlmanage→首页卡）
 *  → 纯前端沙箱渲染（路线 A：fetch bytes → 魔数嗅探 → staging 帧渲染 → 产物帧）。
 *  blob 双腿纪律同 pdf/docx（`objectUrl || await ticketUrl(absPath)`，点击铸票见降级腿）。
 *  @param {HTMLElement} pane
 *  @param {{absPath?:string, fileName?:string, size?:number, objectUrl?:string}} ctx */
async function viewPptx(pane, { absPath, fileName, size, objectUrl }) {
  revokePaneBlobUrls(pane); // 同 pane 重渲：清上一轮 viewer 自铸 blob（含 staging 帧/桥清理）
  /** @type {any} */ (pane)._pptxTeardown?.();
  pane.classList.remove('scrollable');
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading presentation...</div>`;
  /** @type {any} */ (pane)._pptxDiag = { gateDecision: 'pending' };
  const diag = /** @type {any} */ (pane)._pptxDiag;
  const ctx = { absPath, fileName, size, objectUrl };

  if (!absPath && !objectUrl) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for PPTX viewer.</div>`;
    return;
  }

  // 主腿：服务端转换（absPath 在场才有；50MB 前端闸**不**拦它——转换在服务端走，
  // 产物是紧凑 PDF，200MB 服务端闸由端点自持）。
  if (absPath) {
    const converted = await tryOfficeConvert(pane, ctx, diag);
    if (converted || !pane.isConnected) return;
    // 未转换（转换器缺席/全败/无票）⇒ 落路线 A；convertSkipped 已记因。
    if (!pane.isConnected) return;
    pane.classList.remove('scrollable');
    pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading presentation...</div>`;
  }

  await renderFrontendPptx(pane, ctx, diag);
}

/** 路线 A 纯前端渲染腿（结构 = 2026-10-06 MVP 原样；本批仅外加 paneBlobUrls 回收
 *  与 diag 因果记录）。 */
async function renderFrontendPptx(pane, { absPath, fileName, size, objectUrl }, diag) {
  const ctx = { absPath, fileName, size, objectUrl };

  // 闸 4：源件 >50MB 拒绝渲染（声明尺寸先看，objectUrl 腿的实际尺寸在取字节后复核）
  if (size && size > MAX_SOURCE_BYTES) {
    diag.gateDecision = 'refuse-render:source>50MB';
    renderDownloadFallback(pane, ctx, `Presentation exceeds the ${Math.round(MAX_SOURCE_BYTES / 1048576)}MB in-app render cap — download to view.`);
    return;
  }

  let buf;
  try {
    // M2 账本接缝（imgdoor #542 推广批）：票据腿经 nfViewerUrl——账本命中 = blob URL
    // 直开（第二次打开零取数），未命中走既有票据链并入账。下载锚腿（renderDownloadFallback）
    // 保持票据 URL 原样（下载语义不经账本）。
    const url = await nfViewerUrl({ absPath, objectUrl, pane });
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    buf = await resp.arrayBuffer();
  } catch (e) {
    if (!pane.isConnected) return;
    diag.gateDecision = 'fetch-failed';
    diag.reason = String(e && e.message || e);
    renderDownloadFallback(pane, ctx, `Could not load presentation bytes (${String(e && e.message || e)}).`);
    return;
  }
  if (!pane.isConnected) return;

  if (buf.byteLength > MAX_SOURCE_BYTES) {
    diag.gateDecision = 'refuse-render:actual>50MB';
    renderDownloadFallback(pane, ctx, `Presentation exceeds the ${Math.round(MAX_SOURCE_BYTES / 1048576)}MB in-app render cap — download to view.`);
    return;
  }

  // 闸 6：魔数嗅探（zip `PK` vs CFB `D0 CF 11 E0`）
  const head = new Uint8Array(buf, 0, Math.min(8, buf.byteLength));
  const isZip = head[0] === 0x50 && head[1] === 0x4B;            // 'PK'
  const isCfb = head[0] === 0xD0 && head[1] === 0xCF && head[2] === 0x11 && head[3] === 0xE0;
  if (!isZip) {
    diag.gateDecision = isCfb ? 'download-leg:cfb' : 'download-leg:unknown-container';
    renderDownloadFallback(pane, ctx, isCfb
      ? 'Legacy .ppt or encrypted presentation (binary container) — cannot render in-app; download to view.'
      : 'Unrecognized presentation container — download to view.');
    return;
  }

  // 渲染腿：staging 帧（allow-scripts 沙箱 + CSP 断网）→ 窄桥 → 产物帧（全沙箱）
  let libSrc;
  try {
    libSrc = await loadPptxRendererSource();
  } catch (e) {
    if (!pane.isConnected) return;
    diag.gateDecision = 'renderer-load-failed';
    diag.reason = String(e && e.message || e);
    renderDownloadFallback(pane, ctx, `Could not load the in-app renderer (${String(e && e.message || e)}).`);
    return;
  }
  if (!pane.isConnected) return;

  /** @type {HTMLIFrameElement|null} */ let stageFrame = null;
  /** @type {((ev: MessageEvent) => void)|null} */ let onMessage = null;
  const cleanupStage = () => {
    if (onMessage) { window.removeEventListener('message', onMessage); onMessage = null; }
    if (stageFrame) { stageFrame.remove(); stageFrame = null; }
  };
  try {
    const product = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('render pipeline timeout 60s')), 60000);
      onMessage = (ev) => {
        if (!stageFrame || ev.source !== /** @type {any} */ (stageFrame).contentWindow) return;
        const m = ev.data || {};
        if (m.type === 'nf-pptx-product') { clearTimeout(timer); resolve(m.payload); }
        else if (m.type === 'nf-pptx-error') { clearTimeout(timer); reject(new Error(m.message || 'staging render failed')); }
      };
      window.addEventListener('message', onMessage);
      stageFrame = document.createElement('iframe');
      stageFrame.setAttribute('sandbox', 'allow-scripts'); // opaque origin：读不到宿主任何面
      stageFrame.title = '';
      stageFrame.setAttribute('aria-hidden', 'true');
      stageFrame.style.cssText = 'position:fixed;left:-100000px;top:0;width:1000px;height:800px;border:0;visibility:hidden;';
      stageFrame.srcdoc = stageShellHtml();
      document.body.appendChild(stageFrame);
      stageFrame.addEventListener('load', () => {
        // buf 一并 transfer（零拷贝；父侧此后不再需要字节）
        /** @type {any} */ (stageFrame).contentWindow
          .postMessage({ type: 'nf-pptx-render', libSrc, buf, placeholder: EXTERNAL_PLACEHOLDER, openOptions: {
            renderMode: 'list',
            zipLimits: {
              maxEntries: 4000, // 库推荐值（entry 数上限）；预算族两值来自 shared.js
              maxEntryUncompressedBytes: ZIP_BUDGET.maxEntryBytes,
              maxTotalUncompressedBytes: ZIP_BUDGET.maxTotalBytes,
              maxMediaBytes: ZIP_BUDGET.maxTotalBytes,
            },
            fitMode: 'none',
            width: 960,
            listOptions: { windowed: false },
            pdfjs: false, // EMF-PDF 后备腿关闭：worker-src 'none' 下也不可用，EMF 如实降级
          } }, '*', [buf]);
      }, { once: true });
    });
    cleanupStage();
    if (!pane.isConnected) return;
    if (!product.slideDims.length) throw new Error('renderer produced no slides');

    diag.gateDecision = 'render';
    diag.slideCount = product.slideDims.length;
    diag.blobRewrites = product.blobRewrites;
    diag.extNeutralized = product.extNeutralized;

    // 组装产物帧：sandbox=""（禁脚本/表单/弹窗/顶层导航，opaque origin）
    pane.innerHTML = '';
    pane.classList.add('scrollable');
    const wrap = document.createElement('div');
    wrap.style.cssText = 'display:flex;flex-direction:column;align-items:center;padding:16px 0;';
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', '');
    frame.title = fileName || 'presentation.pptx';
    frame.style.cssText = 'border:0;display:block;';
    wrap.appendChild(frame);
    pane.appendChild(wrap);

    const slideDims = product.slideDims;
    const unitsHtml = product.unitsHtml;
    const deckPad = 24 * 2, deckGap = 16;
    const intrinsicW = Math.max(...slideDims.map((d) => d.w));
    const contentH = (z) => slideDims.reduce((s, d) => s + Math.round(d.h * z) + deckGap, deckPad);
    const contentW = (z) => Math.round(intrinsicW * z) + 32;

    const layout = (z) => {
      frame.srcdoc = buildShell(unitsHtml, slideDims, z);
      frame.style.width = contentW(z) + 'px';
      frame.style.height = contentH(z) + 'px';
    };
    const fit = () => Math.min(Math.max(((wrap.clientWidth || pane.clientWidth || 800) - 48) / intrinsicW, 0.25), 3);
    let zoom = fit();
    layout(zoom);

    // 缩放锚点：保住 pivot 所指页（重灌 srcdoc 会重置 frame，父滚动容器由我们自持）。
    // wheel 连续手势每 delta 重灌一次 srcdoc 会刷屏重载 —— 照 pdf.js 的 120ms debounce
    // 收敛为一次重灌；状态值同步更新（工具栏百分比读数不延迟）。
    const slideTops = (z) => {
      let y = 24; const tops = [];
      for (const d of slideDims) { tops.push(y); y += Math.round(d.h * z) + deckGap; }
      return tops;
    };
    let relayoutTimer = null;
    const applyScale = (next, pivot) => {
      const oldZoom = zoom;
      const pivotY = pivot ? pivot.y + wrap.scrollTop : wrap.scrollTop + wrap.clientHeight / 2;
      const tops = slideTops(oldZoom);
      let anchor = 0;
      for (let i = 0; i < tops.length; i++) if (tops[i] <= pivotY) anchor = i;
      const dy = pivotY - tops[anchor]; // 锚点页内偏移（旧缩放像素）
      const pivotOffset = pivot ? pivot.y : wrap.clientHeight / 2;
      zoom = next;
      clearTimeout(relayoutTimer);
      relayoutTimer = setTimeout(() => {
        layout(zoom);
        const newTops = slideTops(zoom);
        wrap.scrollTop = newTops[anchor] + dy * (zoom / oldZoom) - pivotOffset;
      }, 120);
      diag.zoom = next;
    };
    enableViewerZoom(pane, {
      min: 0.25, max: 5,
      getScale: () => zoom,
      applyScale,
      reset: () => { clearTimeout(relayoutTimer); zoom = fit(); layout(zoom); diag.zoom = zoom; },
      attachTo: wrap,
    });
    diag.zoom = zoom;
  } catch (err) {
    cleanupStage();
    if (!pane.isConnected) return;
    diag.gateDecision = 'render-failed';
    diag.error = String(err && err.message || err);
    renderDownloadFallback(pane, ctx, `Failed to render PPTX: ${String(err && err.message || err)}`);
    return;
  }
}

export default {
  name: 'pptx',
  label: 'PPTX',
  extensions: ['.ppt', '.pptx'],
  binary: true,
  priority: 0,
  render: viewPptx,
};
