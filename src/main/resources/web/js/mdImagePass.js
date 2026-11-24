// mdImagePass.js — markdown 正文图片的「候选路径构造＋铸票＋失败处理」单源
// （ask-image-ticket 批 2026-11-24 · 作者报「Ask 回答正文相对路径图片看不了」）。
//
// 修前形态：这套管线只活在 viewers/markdown.js 的**字符串级** pass 里（文档查看器
// 专用）；Ask 对话内核（askDialog.setMarkdownHtml）走通用 markdown 渲染，相对路径
// 图片按页面 URL 解析 ⇒ `/attachments/<目录>/x.png` 404 text/plain（作者 2026-11-24
// 实测样本）。本批把「候选表 / 铸票判决 / 失败占位 / onerror 级联自愈」抽成共享
// 模块，两个消费面零第二套实现：
//   · viewers/markdown.js —— **字符串级** pass（marked 输出在进 DOM 前改写 src；
//     行为逐字保留，只换模块边界）；
//   · askDialog.js —— **DOM 级** pass（resolveRelativeImages：setMarkdownHtml
//     落地后对气泡体里的 <img> 就地铸票换 src；基准目录 = cfg.docPath() 所指
//     文档所在目录——「所问文档所在目录」正是回答正文相对路径的语义）。
//
// 纪律（两消费面同守，与文档查看器批 2026-11-05 同源）：
//   · 字节路由零新增：终局 URL 始终 `/api/nf-file?path&ticket`，票据经
//     POST /api/nf-ticket 批量铸；直连文件系统一律不经前端。
//   · 失败不许静默：路径解析不到 / 404 / 票据被拒 ⇒ 一块明确的失败占位
//     （nf-md-img-fail 族，class 与文档查看器共用一份），绝不留白。
//   · 无文档上下文（选区档 Ask 无 cfg.docPath）且引用是相对路径 ⇒ 同样落
//     明确占位（canvas.imgFailNoDoc），**零网络**——不发明注定 404 的页面相对请求。
//
// 本模块不认识任何消费面（不查画布 DOM、不开面级事件）；作用域一律由调用方传入。

import { mintTickets, ticketRejects, reMint, stripCredentialParams, decodePathParam } from './nfTicket.js';
// 账本单点（二次加载清剿批 2026-10-24 语义原样）：nfViewerUrl 命中零抓取 / 未命中
// 取数即入账；blob 解码失败自愈逐出（nfCacheDrop，见下方 mdImgFail ①）。
import { nfViewerUrl, nfCacheDrop } from './nfFileCache.js';
import { t } from './i18n.js';

/** 图片扩展名（决定 `![[…]]` 是「图片嵌入」还是「文档引用」）。 */
export const MD_IMG_EXT_RE = /\.(png|jpe?g|jfif|gif|webp|avif|bmp|svg|ico|tiff?)$/i;

/** 「浏览器本分」的 src（远端 / data: / blob: / 已代理 / 协议头排除）—— 这些不
 *  归 pass 管也**不许摘**：摘了远端图就不出。三个判定点共用一枚（候选表、
 *  DOM 级 Pass 1、落 DOM 前的中和腿）——判据分裂 = 一处摘一处留。 */
const MD_IMG_BROWSER_RE = /^(https?:|data:|blob:|\/api\/|javascript:|mailto:)/i;

/** vault 根 = 文档库的注册根。wiki-docs 纪律的布局口径（SKILL.md「Vault layout」）：
 *  vault 根 = `<data_root>/projects/<project>/.stand/docs/`，附件按惯例放
 *  `<vault>/attachments/` 或 `<vault>/assets/`（`docs/attachments/…`）。
 *  `/…/.stand/docs/design/x.md` ⇒ `/…/.stand/docs`；
 *  不在 `docs/` 下但仍在 `.stand/` 里（少见）⇒ `/…/.stand`；
 *  两者都不在（repo reports/、/tmp 夹具）⇒ ''，候选表自然少几层，不猜。 */
export function vaultRootOf(absPath) {
  if (!absPath) return '';
  const norm = String(absPath).replace(/\\/g, '/');
  const docsIdx = norm.lastIndexOf('/.stand/docs/');
  if (docsIdx >= 0) return String(absPath).slice(0, docsIdx + '/.stand/docs'.length);
  const standIdx = norm.lastIndexOf('/.stand/');
  if (standIdx >= 0) return String(absPath).slice(0, standIdx + '/.stand'.length);
  return '';
}

/** 文档自身所在目录（相对路径图片的第一优先级基准）。`a/b/x.md` ⇒ `a/b`；
 *  无路径分隔符 ⇒ ''（无目录上下文）。 */
export function docDirOf(absPath) {
  if (!absPath) return '';
  return String(absPath).substring(0, String(absPath).lastIndexOf(String(absPath).includes('\\') ? '\\' : '/'));
}

/** 属性值转义（候选路径/原路径要进 data-*，引号与尖括号必须先折叠）。 */
export function escapeAttr(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 原始 src → 已解码引用（marked 会 percent-encode：`assets/截屏 1.png` 与
 *  `<assets/x 2.png>` 都到这里变成 `assets/%E6%88%AA…%201.png`。先解码，否则下面
 *  再转义一次、端点去找一个字面 `%E6…` 的文件。非法转义（名字里的真 `%`）原样用）。 */
export function decodeImgRef(raw) {
  try { return decodeURIComponent(raw); } catch (e) { return raw; }
}

/** 一张图 src 的**候选绝对路径**表（按作者令的优先级排，去重后交给级联）。
 *
 *  ①`文档自身所在目录 + ref`
 *  ②`vault 根 + ref`（vault 根 = `…/.stand/docs`）
 *  ③`<vault>/attachments/<ref>`、`<vault>/assets/<ref>` —— **vault 的附件惯例目录**。
 *    作者第二份复现样本就是这一条：正文写的是裸文件名 `![[x.png]]`，图却在
 *    `docs/attachments/x.png`（既不在文档同目录、也不在 vault 根），只按 ①② 解析
 *    必然 404 —— 这正是「截图在里面但不渲染」的那条离线路径。
 *  ④绝对路径 / `~`（`~` 由 /api/nf-file 端点展开成 user.home）= 单候选
 *  ⑤此表之外唯一的字节路由始终是 `/api/nf-file?path&ticket`（消费面铸票），
 *    不新增端点、直连文件系统一律不经前端。
 *  @param {string} ref 文档里写的原始引用
 *  @param {string} docDir 文档自身目录（'' = 无路径上下文）
 *  @param {string} vaultRoot vault 根（'' = 不在 vault 里）
 *  @returns {string[]} 空数组 = 远端 / data: / 已代理 URL（原样留给浏览器） */
export function imageCandidates(ref, docDir, vaultRoot) {
  const src = String(ref == null ? '' : ref).trim();
  if (!src) return [];
  if (MD_IMG_BROWSER_RE.test(src)) return [];
  // ④ 绝对路径 / `~` 家目录：单候选
  if (src.startsWith('/') || src.startsWith('~') || /^[A-Za-z]:[\\/]/.test(src)) return [src];
  const rel = src.replace(/^\.\//, '');
  /** @type {string[]} */
  const out = [];
  const push = (p) => { if (p && !out.includes(p)) out.push(p); };
  // ① 文档自身所在目录（`![](assets/x.png)` 的标准 markdown 语义）
  if (docDir) push(docDir + '/' + rel);
  // ② vault 根
  if (vaultRoot) push(vaultRoot + '/' + rel);
  // ③ vault 的附件惯例目录（裸文件名的 Obsidian 嵌入语义 + 本库布局）
  if (vaultRoot) {
    push(vaultRoot + '/attachments/' + rel);
    push(vaultRoot + '/assets/' + rel);
  }
  // 文档在 vault 子目录里时，同级的 attachments/assets 也试一档（`docs/design/x.md`
  // 引用 `docs/attachments/a.png` 的写法）
  if (docDir && vaultRoot && docDir !== vaultRoot) {
    push(docDir + '/attachments/' + rel);
    push(docDir + '/assets/' + rel);
  }
  if (!out.length) push(rel);
  return out;
}

/** 读 `data-nf-md-alt` 里剩下的候选 URL 表（encodeURIComponent(JSON) 载体）。 */
export function readAltUrls(ds) {
  try {
    const parsed = JSON.parse(decodeURIComponent(ds.nfMdAlt || ''));
    return Array.isArray(parsed) ? parsed.filter((u) => typeof u === 'string' && u) : [];
  } catch (e) { return []; }
}

/** 服务端 reason → 状态码（失败占位据此说人话）。 */
export function reasonStatus(reason) {
  const r = String(reason || '');
  if (r === 'not-found' || r === 'missing') return 404;
  if (r.startsWith('credential') || r === 'denied') return 403;
  if (r === 'extension-not-allowed') return 415;
  return 0;
}

/** 失败占位的**原因文案**：HTTP 状态码 → 作者能据以行动的一句话。
 *  @param {number} status @param {string} [detail] */
export function mdImageFailReason(status, detail) {
  const s = Number(status) || 0;
  if (s === 404 || s === 410) return t('canvas.imgFailMissing', { status: String(s) });
  // 401 / 403 **分叉**（作者裁 2026-11-19「区分」）：401 = 请求根本没带票（真因 =
  // 没有凭据：铸票面没给票 / 退避窗内无票 / URL 形状问题），403 = 带了票但票被判据
  // 拒绝（`X-Nf-Reason`：invalid-ticket / expired / path-mismatch / credential-*）。
  // 共用一句会把「没有票」误报成「票被拒」—— 服务端语义未动，只拆文案与分支。
  if (s === 403) return t('canvas.imgFailDenied', { status: String(s) });
  if (s === 401) return t('canvas.imgFailNoCredential', { status: String(s) });
  if (s === 429 || s === 503) return t('canvas.imgFailBusy', { status: String(s) });
  if (s === 400) return t('canvas.imgFailBadPath', { status: String(s) });
  if (s === 415) return t('canvas.imgFailUnsupported', { status: String(s) });
  if (s === 0) return t('canvas.imgFailUnreachable');
  return t('canvas.imgFailUnknown', { status: String(s), detail: detail || '' });
}

/** 尽力探一次终局 URL 的 HTTP 状态（只为把「为什么失败」写准；失败面已经在）。 */
export async function probeMdImageUrl(url) {
  if (!url) return 0;
  try {
    const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
    return res.status;
  } catch (e) { return 0; }
}

/** 失败占位的 **HTML 串**形态（**铸票面当场判决**用：全部候选都被端点拒绝
 *  ⇒ 连 <img> 都不发，直接落一块明确占位，原因来自服务端 `rejected` 判决）。
 *  与 showMdImageFailure 产物同构（同一组 class + 同一个 data 标记），样式与
 *  测试选择器两边共用一份。
 *  @param {string} ref 文档里写的原路径原文
 *  @param {number} status 由服务端 reason 映射来的状态码（404/403/415/0）
 *  @param {string} [detail] 服务端 reason 原文（诊断留痕）
 *  @returns {string} */
export function mdImageFailHtml(ref, status, detail) {
  return '<div class="nf-md-img-fail" data-nf-md-img-fail="1"'
    + ` data-nf-md-img-status="${escapeAttr(String(status || 0))}"`
    + (detail ? ` data-nf-md-img-reason="${escapeAttr(detail)}"` : '')
    + '>'
    + `<div class="nf-md-img-fail-title">${escapeAttr(t('canvas.imgFailTitle'))}</div>`
    + `<div class="nf-md-img-fail-reason">${escapeAttr(mdImageFailReason(status, detail))}</div>`
    + `<code class="nf-md-img-fail-path">${escapeAttr(ref)}</code>`
    + '</div>';
}

/** 终局失败面：把坏掉的 <img> 换成一块**明确**的占位（原因 + 原路径），
 *  绝不留白。视频/文档里作者能立刻读到「哪张图、哪条路径、为什么」。
 *  @param {HTMLImageElement} img */
export function showMdImageFailure(img) {
  if (!img || !img.isConnected) return;
  const ds = img.dataset || {};
  const authored = ds.nfMdEmbed ? `![[${ds.nfMdEmbed}]]` : (ds.nfMdSrc || img.getAttribute('src') || '');
  const box = document.createElement('div');
  box.className = 'nf-md-img-fail';
  box.setAttribute('data-nf-md-img-fail', '1');
  if (ds.nfMdEmbed) box.setAttribute('data-nf-md-embed', ds.nfMdEmbed);
  const head = document.createElement('div');
  head.className = 'nf-md-img-fail-title';
  head.textContent = t('canvas.imgFailTitle');
  const reason = document.createElement('div');
  reason.className = 'nf-md-img-fail-reason';
  const known = Number(ds.nfMdErr || 0);
  reason.textContent = mdImageFailReason(known, ds.nfMdErrText);
  const pathEl = document.createElement('code');
  pathEl.className = 'nf-md-img-fail-path';
  pathEl.textContent = authored;
  box.append(head, reason, pathEl);
  img.replaceWith(box);
  // 原因精修：渲染期没拿到状态码（直取票据 URL 失败）⇒ 探一次状态码写回。
  if (!known) {
    const probeUrl = ds.nfMdFb || img.getAttribute('src') || '';
    probeMdImageUrl(probeUrl).then((status) => {
      if (!box.isConnected) return;
      box.setAttribute('data-nf-md-img-status', String(status));
      reason.textContent = mdImageFailReason(status, '');
    }).catch(() => { /* 占位已经在，探测器失败不改结论 */ });
  }
}

/** 失败占位的 **DOM 节点**形态（DOM 级 pass 用——askDialog 那条腿改的是已落地的
 *  节点，不是 HTML 字符串）。与 mdImageFailHtml 同构（同一解析产物）。
 *  @param {string} ref @param {number} status @param {string} [detail]
 *  @returns {HTMLElement} */
export function mdImageFailNode(ref, status, detail) {
  const parsed = new DOMParser().parseFromString(mdImageFailHtml(ref, status, detail), 'text/html');
  const node = parsed.body.firstElementChild;
  return /** @type {HTMLElement} */ (node || document.createElement('div'));
}

/** 无文档上下文的占位（Ask 选区档：没有「所问文档」，相对引用无从解析）。
 *  形态与失败占位同族（同 class / 同 data 标记 + 专属 data-nf-md-img-nodoc），
 *  原因一句话说清「这份回答没有文档上下文」，原路径原文照给人看。零网络。
 *  @param {string} ref 回答正文里写的原路径原文
 *  @returns {HTMLElement} */
export function mdImageNoDocNode(ref) {
  const box = document.createElement('div');
  box.className = 'nf-md-img-fail';
  box.setAttribute('data-nf-md-img-fail', '1');
  box.setAttribute('data-nf-md-img-nodoc', '1');
  box.setAttribute('data-nf-md-img-status', '0');
  const head = document.createElement('div');
  head.className = 'nf-md-img-fail-title';
  head.textContent = t('canvas.imgFailTitle');
  const reason = document.createElement('div');
  reason.className = 'nf-md-img-fail-reason';
  reason.textContent = t('canvas.imgFailNoDoc');
  const pathEl = document.createElement('code');
  pathEl.className = 'nf-md-img-fail-path';
  pathEl.textContent = ref;
  box.append(head, reason, pathEl);
  return box;
}

// ── onerror 级联（与文档查看器逐字同源）─────────────────────────────────────
// 顺序：①账本 blob 解码失败的自愈腿（逐出账本 + 回落票据 URL 恰一次）→ ①′ 票据
// 自愈（401/403 重铸 ≤3 次）→ ②候选级联（文档目录腿失败 ⇒ vault 根腿，一次一张、
// 不成环）→ ③终局失败占位（原因 + 原路径，绝不留白）。
// （仍发布在 window.__nfMdImgFail：字符串级 pass 的 onerror 是内联属性、求值于全局
//   作用域——模块 import 不可见。DOM 级 pass 直接持模块引用。）

/**
 *  @param {HTMLImageElement} img
 *  @returns {void}
 */
function mdImgFail(img) {
  if (!img || !img.dataset) return;
  const ds = img.dataset;
  const fallback = ds.nfMdFb || '';
  // ① 账本 blob 解码失败：坏字节逐出账本，回落票据 URL 恰一次。
  if (img.src && img.src.startsWith('blob:') && fallback && !ds.nfMdRetried) {
    ds.nfMdRetried = '1';
    const q = /[?&]path=([^&]*)/.exec(fallback);
    const p = q ? decodePathParam(q[1]) : null;
    if (p) void nfCacheDrop('nf:' + p).catch(() => {});
    img.src = fallback;
    return;
  }
  // ①′ 票据自愈（B1，票据自愈批 2026-11-19 · 作者令「文档里的图片，重启/票过期后
  // 必须自愈恢复，不许长期停在错误态」）：<img>/<video> 元素腿此前只有「①账本坏字节
  // 逐出 + 回落那枚**可能已过期**的候选 URL」与「②候选级联」两条腿 —— 网关重启
  // 清空票仓、或票 TTL 过期后，回落的那枚旧票 URL 同样 401/403，于是（② 的候选全都
  // 骑同一批失效票）直落 ③ 终局占位（作者实拍「无权读取，票据被拒（HTTP 401）」停住）。
  // 这里补一条腿：**重铸新票**并重试。覆盖面 = 401 与 403 的**全部** reason
  // （invalid-ticket / expired / path-mismatch / credential-* —— 只按**状态类**判，
  // 不筛服务端 reason，因为元素腿拿不到 reason 时要照旧自愈）。
  //   · 闸① **只对票的失败动手**：onerror 不带状态，所以用 probeMdImageUrl
  //     探一次；探到 401/403 才重铸。404/400/415 等非票因 ⇒ 逐字交回既有 ② 候选
  //     级联（否则会把「文件不存在」误当票据问题去重铸，并对不存在路径打转）。
  //   · 闸② **每次失败至多重铸 3 次**（nfMdRerolls 计数）：任何情形都不成环。
  //   · 探到的状态回填 nfMdErr ⇒ ③ 不必再白探一次。
  // 服务端票仓语义与校验强度**一个字不动**：这只是把客户端已有的「重铸 + 重试」
  // 自愈腿补齐到元素腿。
  const rerolls = Number(ds.nfMdRerolls || 0);
  const carryingBlob = (img.getAttribute('src') || '').startsWith('blob:');
  if (rerolls < 3 && !carryingBlob) {
    const p = ds.nfMdPath || '';
    const known = Number(ds.nfMdErr || 0);
    if (p && (known === 0 || known === 401 || known === 403)) {
      ds.nfMdRerolls = String(rerolls + 1);
      const failing = img.getAttribute('src') || '';
      const decide = known ? Promise.resolve(known) : probeMdImageUrl(failing).catch(() => 0);
      decide.then((s) => {
        if (!img.isConnected) return;
        const st = Number(s) || 0;
        if (st === 401 || st === 403) {
          return reMint(p)
            .then((url) => {
              if (!img.isConnected) return;
              const next = String(url || '');
              if (next && next !== img.getAttribute('src')) img.src = next; // 新票 ⇒ 再试
              else if (/** @type {any} */ (window).__nfMdImgFail) /** @type {any} */ (window).__nfMdImgFail(img); // 拿不到新票 ⇒ 走 ②/③
            })
            .catch(() => { if (/** @type {any} */ (window).__nfMdImgFail) /** @type {any} */ (window).__nfMdImgFail(img); });
        }
        if (st) ds.nfMdErr = String(st);            // 回填（③ 不再白探）
        if (/** @type {any} */ (window).__nfMdImgFail) /** @type {any} */ (window).__nfMdImgFail(img); // 非票因 ⇒ 交回 ②/③
      });
      return;
    }
  }
  // ② 候选级联：vault 根腿（或反过来的文档目录腿）再试恰一次/候选。
  const alts = readAltUrls(ds);
  if (alts.length) {
    ds.nfMdAlt = encodeURIComponent(JSON.stringify(alts.slice(1)));
    // 这个候选就是**真实生效**的路径了：看原图（lightbox）要拿它。
    const q2 = /[?&]path=([^&]*)/.exec(alts[0]);
    const np = q2 ? decodePathParam(q2[1]) : null;
    if (np) ds.nfMdPath = np;
    img.src = alts[0];
    return;
  }
  // ③ 终局：明确失败占位。
  img.title = t('canvas.imgFailed') + ': ' + stripCredentialParams(ds.nfMdSrc || img.src || '');
  showMdImageFailure(img);
}

/** 把级联发布到 window.__nfMdImgFail（幂等）。字符串级 pass 注入的内联 onerror
 *  求值于全局作用域，靠它找到本实现；DOM 级 pass 直接持模块引用，无需全局，
 *  但级联自身的再入（①′/② 交回）走全局间接，两消费面都装上它最稳。 */
export function installMdImgFailGlobal() {
  /** @type {any} */ (window).__nfMdImgFail = mdImgFail;
}

// ── DOM 级 pass（Ask 回答正文）──────────────────────────────────────────────

/**
 * 落 DOM 前的**摘 src**腿（askDialog.setMarkdownHtml 在 DOMParser 之后、
 * importNode/replaceChildren 之前调）：把「会被页面相对解析」的 `<img>` 的
 * src 属性同步摘掉、原引用原文搬进 `data-nf-md-src`。
 *
 * 为什么必须在 importNode 之前（本批红实证，双核形态不同）：解析产物一旦经
 * `document.importNode` 落进**活文档**（节点文档 = fully active 的主文档——与
 * 是否已插列无关），src 就会触发图片取数：WebKit 在 clone/落地拍即发请求；
 * Chromium 排队到任务拍才判——但无文档上下文分支走 `replaceWith`（不摘 src），
 * 排队任务照样把请求发出去。两形态都会对相对路径发一轮**注定 404** 的页面
 * 相对请求（作者报障的原始形态）。摘掉后，终局 src 一律由 DOM 级 pass 就地
 * 换上（票 URL / 账本 blob / 失败占位）。
 *
 * 「浏览器本分」的 src（远端 / data: / blob: / 已代理）不摘——摘了远端图
 * 就不出；判定与候选表/DOM 级 pass 同一枚 MD_IMG_BROWSER_RE。
 *
 * data-nf-md-src 存**原始未解码**的引用原文（与字符串级 pass 的 data-nf-md-src
 * 同义）：解码统一在 Pass 1（decodeImgRef 单点，不二次解码）。
 *
 * @param {Element} root 解析产物容器（DOMParser 的 body）
 * @returns {void}
 */
export function neutralizePendingImages(root) {
  if (!root || !root.querySelectorAll) return;
  for (const el of Array.from(root.querySelectorAll('img'))) {
    const img = /** @type {HTMLImageElement} */ (el);
    const raw = img.getAttribute('src');
    if (raw == null || !raw) continue;
    if (MD_IMG_BROWSER_RE.test(String(decodeImgRef(raw)).trim())) continue; // 浏览器本分
    img.removeAttribute('src');
    img.dataset.nfMdSrc = raw;
  }
}

/**
 * 对**已落地**的 markdown 正文 DOM 就地解析相对路径图片：候选 → 批量铸票 →
 * 账本单点解析终局 src → onerror 级联（复用上方的共享实现，零第二套）。
 *
 * 与字符串级 pass 的两点形态差异（语义同源）：
 *   · **同步摘 src**：收集候选的同拍把 `src` 属性摘掉——否则浏览器会立刻按页面
 *     URL 对相对路径发一轮**注定 404** 的请求（作者报障的原始形态）。
 *   · **竞争闸 alive()**：Ask 的正文会多次重渲（流式 done / askState 回放 / 切文档
 *     都重建气泡）。每一渲染轮持一个 epoch；迟到的铸票/取数结果若属旧轮
 *     （节点已被新轮替换、或文档已切换），一律**弃写**——旧请求结果不得回填
 *     新 DOM，也不得对已弃图再发字节请求。
 *
 * 远端 / data: / blob: / 已代理 URL 原样留给浏览器；无文档上下文且引用为相对
 * 路径 ⇒ mdImageNoDocNode 占位（零网络）。
 *
 * @param {Element} scope 正文容器（气泡体）
 * @param {{ docPath?: string, pane?: Element, alive?: () => boolean }} [opts]
 *   docPath — 基准文档的绝对路径（'' = 无文档上下文）
 *   pane    — 账本 blob URL 的归属面（缺省 = scope）
 *   alive   — 竞争闸：本渲染轮是否仍是当前轮（缺省 = 恒真）
 * @returns {Promise<void>}
 */
export async function resolveRelativeImages(scope, opts = {}) {
  if (!scope || !scope.querySelectorAll) return;
  const docPath = opts.docPath || '';
  const pane = opts.pane || scope;
  const alive = opts.alive || (() => true);
  installMdImgFailGlobal();
  const docDir = docDirOf(docPath);
  const vaultRoot = vaultRootOf(docPath);

  // Pass 1（同步）— 收集 + 摘 src + 无上下文判决。引用读取顺序：先 data-nf-md-src
  // （落 DOM 前中和腿搬进来的原文），缺再看 src 属性（未经中和的直落形态——如
  // 基点行为兼容腿）。两处都存**未解码原文**，解码在此单点。
  /** @type {{ img: HTMLImageElement, ref: string, cands: string[] }[]} */
  const targets = [];
  for (const el of Array.from(scope.querySelectorAll('img'))) {
    const img = /** @type {HTMLImageElement} */ (el);
    const raw = img.dataset.nfMdSrc != null ? String(img.dataset.nfMdSrc) : img.getAttribute('src');
    if (raw == null || !raw) continue;
    const ref = decodeImgRef(raw);
    if (!ref) continue;
    if (MD_IMG_BROWSER_RE.test(String(ref).trim())) continue; // 浏览器的事
    const absolute = ref.startsWith('/') || ref.startsWith('~') || /^[A-Za-z]:[\\/]/.test(ref);
    // 无文档上下文 + 相对引用 ⇒ 明确占位，零网络（不发明页面相对 404）。
    if (!docDir && !vaultRoot && !absolute) {
      img.replaceWith(mdImageNoDocNode(ref));
      continue;
    }
    // 同步摘 src（见头注）；dataset 载荷与字符串级 pass 同名同义（nf-md-src 一律
    // 存未解码原文，与中和腿一致）。
    img.removeAttribute('src');
    img.dataset.nfMdSrc = raw;
    img.dataset.nfMdErr = '';
    targets.push({ img, ref, cands: imageCandidates(ref, docDir, vaultRoot) });
  }
  if (!targets.length) return;

  // Pass 2 — 一次批量铸票（候选一起去，级联时不再多发一次铸票）。
  const candidates = new Set();
  for (const t of targets) for (const p of t.cands) candidates.add(p);
  const tickets = await mintTickets([...candidates]);
  if (!alive()) return;
  // 铸票判决（`rejected` 表）：被端点拒绝的候选 = 确定读不到，直接跳过
  // （零请求、零「文件不存在被读成票据被拒」）。
  const rejects = ticketRejects();

  // Pass 3 — 逐图落位（终局 src 经账本单点；失败 ⇒ 级联数据 + 旧腿 URL）。
  await Promise.all(targets.map(async (t) => {
    if (!t.img.isConnected || !alive()) return; // 旧轮弃写
    const all = t.cands;
    const live = all.filter((p) => tickets.get(p));
    const unknown = all.filter((p) => !tickets.get(p) && !rejects.has(p));
    const usable = live.concat(unknown);
    if (!usable.length) {
      // 全部候选被端点拒绝 ⇒ 零请求，当场给明确失败占位（原因来自服务端判决）。
      const first = all.find((p) => rejects.has(p)) || all[0];
      t.img.replaceWith(mdImageFailNode(t.ref, reasonStatus(rejects.get(first)), rejects.get(first)));
      return;
    }
    /** @param {string} p */
    const urlOf = (p) => {
      const tk = tickets.get(p);
      return `/api/nf-file?path=${encodeURIComponent(p)}${tk ? `&ticket=${encodeURIComponent(tk)}` : ''}`;
    };
    const urls = usable.map(urlOf);
    t.img.dataset.nfMdPath = usable[0];
    t.img.dataset.nfMdAlt = encodeURIComponent(JSON.stringify(urls.slice(1)));
    // 级联入口（onerror）：DOM 级直接持模块引用（不依赖全局发布）。
    t.img.addEventListener('error', () => mdImgFail(t.img));
    try {
      const finalUrl = await nfViewerUrl({ absPath: usable[0], objectUrl: null, pane });
      if (!t.img.isConnected || !alive()) return; // 迟到 ⇒ 弃写
      // blob 回落腿（与字符串级 pass 同义）：账本 src 失败时回落的那枚票据 URL。
      if (finalUrl && finalUrl !== urls[0]) t.img.dataset.nfMdFb = urls[0];
      if (finalUrl) t.img.setAttribute('src', finalUrl);
      else t.img.setAttribute('src', urls[0]);
    } catch (err) {
      if (!t.img.isConnected || !alive()) return; // 迟到 ⇒ 弃写
      const anyErr = /** @type {any} */ (err);
      t.img.dataset.nfMdErr = String(Number(anyErr.status || 0) || 0);
      t.img.setAttribute('src', urls[0]);
    }
  }));
}
