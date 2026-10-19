// attachmentPreview.js — 好友/设备对话框附件卡 → Canvas 面板预览（作者令 2026-09-15：
// 「然后对好友/设备对话框里的附件，点击附件要能直接在 canvas 里预览」）。
//
// 定位：本模块是**附件预览的唯一入口**（两入口共用同一判据、同一渲染腿），
// 只做两件事：
//   ① 判定「这件能不能在 Canvas 里渲染」——判据逐条见 `resolveItemType`；
//   ② 把「已判定的字节/路径」交给 Canvas 既有 tab + viewer 注册表渲染。
// 🔴 本模块**不拼任何 URL、不 fetch、不碰文件系统**：
//   · 好友面的字节由调用方经 `friendsApi.downloadAttachment`（应用内鉴权路由）取得；
//   · 设备面的本机落盘件由调用方经 `nfTicket.ticketUrl` ⇒ `/api/nf-file?path=…&ticket=…`
//     取字节（**主路**，与好友面同走 `previewBlob`）；票据路由拒绝时回落
//     `previewLocalPath` ⇒ `workspace-open-item` ⇒ Canvas 既有 `pop.readFile` 链路。
//   三条腿都是应用既有取数面，禁在此另写第四条（禁本地直链 / 禁代理 / 禁假绿）。
//   📌 判据变更出处 = 作者 2026-09-17 12:31 令（设备面附件行为对齐好友/群）：
//   设备面**主路**由 `workspace-open-item` 改为 `previewBlob`（devattach 批）。

import { openTab, hasTab, setActiveTab } from './canvas.js';
import { itemTypeForFileName } from './fileViewers.js';
import { t } from './i18n.js';
import { nfCachedBlob, nfCachePut } from './nfFileCache.js';

/** 要 `content`（文本）的 itemType —— code/json/csv/yaml 共用同一 monaco 实现，
 *  markdown 走 md viewer（同一渲染管线 = 聊天窗 md 那条）。 */
const TEXT_ITEM_TYPES = new Set(['code', 'json', 'csv', 'yaml', 'markdown']);

/** 能由 **blob** 渲染的二进制 itemType —— 判据 = 该 viewer 有 `objectUrl` 腿
 *  （`objectUrl || await ticketUrl(absPath)` 同构模式，见 `viewers/pdf.js` / `viewers/image.js`）。
 *
 *  docx/xlsx/pptx/epub（附件/文件浏览器语义对齐批 2026-09-15）已补上这条腿：
 *  四个 viewer 各自 `fetch(url)` 后自己解包（mammoth / SheetJS / ZIP 解析 / 下载链），
 *  与 pdf/image 完全同构 ⇒ 附件手上只有远端 blob 时不再需要本机路径票据。
 *  其余二进制类型（zip 等）无 viewer 认领 ⇒ 仍走可见降级。 */
const BLOB_ITEM_TYPES = new Set(['image', 'pdf', 'docx', 'xlsx', 'pptx', 'epub']);

/** 🔴 显式排除面（本批划定的安全边界）：`html` 不入附件预览。
 *  html viewer 的 iframe 是 `allow-scripts allow-same-origin`（应用**同源**脚本权限）——
 *  对「用户自己打开的本机文件」是本仓既有口径，而附件是**对端推来的不受信内容**；
 *  预览它等于给远端内容一条拿到应用同源脚本权限的路径 ⇒ 本批不开这个新面，
 *  改用可见降级（toast）。
 *
 *  2026-09-15 取证判词（`attbrowsersem-forensic`，`viewers/html.js:397,:501` 为锚）逐字口径 =
 *  「**不要复制**……**维持 `EXCLUDED_ITEM_TYPES = {html}`**」，与本批任务书
 *  「zero 新增注入/执行面（禁引入应用内 html 渲染新面）」**同判**。任务书另一条
 *  「html 按文件浏览器同款打开」与之**互斥**（文件浏览器的 html 形态本身就是应用内
 *  srcdoc iframe 渲染 = 同源脚本执行面）⇒ 本批**不上呈自选路线**，html 维持排除并上呈裁定
 *  （见 `.nebflow/reports/20260915_attbrowsersem-impl.md` 开放项 1）。 */
const EXCLUDED_ITEM_TYPES = new Set(['html']);

/** 打开路径的文本预览上限：与 gateway `pop.readFile` 的**打开闸**同值同源
 *  （禁第二把尺）。2026-09-20 作者令「canvas 的文件大小限制太小了……提高到
 *  100MB」⇒ 两端同批 10MB → 100MB（服务端常量 = `WebSocketRoutes.MaxPopReadFileBytes`）。 */
const MAX_TEXT_BYTES = 100 * 1024 * 1024;

/** 对话框内**直显**图片附件的字节上限（uifix 批 2026-09-17）。
 *
 *  ⚠ 2026-09-20（打开闸 10MB→100MB 批）：本值**刻意从 `MAX_TEXT_BYTES` 解耦**并
 *  保持原值 10MB。两把尺管的是两件事——
 *    · `MAX_TEXT_BYTES` = 「一件附件能不能**进页面打开**」（打开闸，随作者令上移）；
 *    · 本值 = 「对话框内**直显**一张图片附件的字节成本」（内联/直显预算族，
 *      作者本批明令零触碰：图片内联 5MB 闸 + `data:` URI 40k 闸同族）。
 *  解耦前两者是同一个字面量，闸上移会顺带把直显预算放宽到 100MB —— 那是越面改动；
 *  解耦后本值行为**逐字不变**：超限仍走既有降级（附件卡原样：名称/体积/下载键），
 *  **不**做部分渲染、**不**报错。
 *  消费方不变（`messages.js::attachInlineImage` / 本机缩略帧预算，见 messages.js:1945-1947）。 */
export const MAX_INLINE_IMAGE_BYTES = 10 * 1024 * 1024;

/** 「这件附件是不是图片」——**判据单源**（uifix 批 2026-09-17）。
 *
 *  消费方 = `messages.js::attachInlineImage`（图片附件对话框内直显）。走本函数
 *  而不是在渲染面重写一张扩展名表：ext→itemType 的真源始终是
 *  `fileViewers.js::itemTypeForFileName`（各 viewer 自报 `extensions`），本函数
 *  只是「它 === 'image'」这一个谓词的**唯一**具名出口 ⇒ 渲染面零第二张表。
 *  同时把 `fileViewers.js`（含 monaco 等重模块）的静态依赖留在本模块内，
 *  `messages.js` 只依赖本模块（附件判据的属主）。
 *  @param {string} fileName @returns {boolean} */
export function isImageAttachmentName(fileName) {
  return itemTypeForFileName(fileName) === 'image';
}

/** 内容嗅探窗口（首 4KB）。 */
const SNIFF_BYTES = 4096;

/** tabId → objectUrl（图片/PDF/docx/xlsx/pptx/epub 的 blob URL）。tab 关闭即撤销，禁泄漏。 */
const objectUrls = new Map();
let revokeBound = false;

function bindObjectUrlRevoke() {
  if (revokeBound) return;
  revokeBound = true;
  document.addEventListener('canvas-tab-closed', (/** @type {CustomEvent} */ e) => {
    const id = e && e.detail && e.detail.id;
    if (!id) return;
    const url = objectUrls.get(id);
    if (!url) return;
    objectUrls.delete(id);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
}

/** 首 4KB 无 NUL 字节 ⇒ 当文本处理。
 *
 *  只在**扩展名无 viewer 认领**时使用（`.txt` / `.py` / `.log` 等）。判据是内容而非
 *  第二张扩展名表：仓内 ext→itemType 的真源 = 服务端 `FileTypeRegistry` + 各 viewer
 *  的 `extensions`，这里禁再手抄一份表（两张表迟早漂移）。
 *  @param {Blob} blob
 *  @returns {Promise<boolean>} */
async function looksTextual(blob) {
  try {
    const head = new Uint8Array(await blob.slice(0, SNIFF_BYTES).arrayBuffer());
    if (head.length === 0) return false;
    for (const b of head) if (b === 0) return false;
    return true;
  } catch { return false; }
}

/** 委托给既有 Canvas tab + viewer 渲染（**唯一**渲染腿，两入口共用）。 */
async function renderIntoPane(paneEl, itemType, ctx) {
  const { renderFile } = await import('./fileViewers.js');
  await renderFile(paneEl, { itemType, absPath: null, ...ctx });
}

/** @returns {Promise<'ok'|'failed'>} */
async function renderIntoTab(id, title, itemType, ctx) {
  const entry = openTab(id, title || id, { type: itemType });
  if (!entry) return 'failed';
  entry.paneEl.dataset.attachmentPreview = '1';
  await renderIntoPane(entry.paneEl, itemType, ctx);
  return 'ok';
}

/** 同一件的重复点击 = 面板里已在看这一件 ⇒ 只激活，不取第二份字节、不重挂 viewer。 */
function alreadyOpen(id) {
  if (!hasTab(id)) return false;
  setActiveTab(id);
  return true;
}

/** 该附件的预览 tab 是否已在面板里（**取字节之前**调用 —— 「重复点击不取第二份字节」
 *  是调用方（先在取字节前问一句，再决定要不要发那次鉴权请求）的责任）。 */
export function isPreviewOpen(id) {
  return alreadyOpen(id);
}

/**
 * 预览一件**已到手的远端附件字节**（好友面：`friendsApi.downloadAttachment` 的 blob）。
 *
 * @param {object} p
 * @param {string} p.id       — Canvas tab id（按附件 id 取，重复点击即复用）
 * @param {string} p.title    — tab 标题
 * @param {string} p.fileName — 附件名（判据主输入）
 * @param {Blob}   p.blob     — 已经由鉴权路由取回的整件字节
 * @returns {Promise<'ok'|'unsupported'|'failed'>} `unsupported` = 无 viewer 可渲染该类型
 *   （调用方须给**可见**降级，禁静默）；`failed` = tab 建不起来。
 */
export async function previewBlob({ id, title, blob, fileName }) {
  if (!blob) return 'failed';
  const name = fileName || title || '';
  if (alreadyOpen(id)) return 'ok';

  let itemType = itemTypeForFileName(name);
  if (!itemType) {
    // 无 viewer 认领扩展名：只在**确证是文本**时才落到 code viewer（内容嗅探）
    itemType = (blob.size > 0 && blob.size <= MAX_TEXT_BYTES && await looksTextual(blob)) ? 'code' : null;
  }
  if (!itemType || EXCLUDED_ITEM_TYPES.has(itemType)) return 'unsupported';

  if (TEXT_ITEM_TYPES.has(itemType)) {
    if (blob.size > MAX_TEXT_BYTES) return 'unsupported';
    const content = await blob.text();
    return renderIntoTab(id, title, itemType, { content, fileName: name, size: blob.size });
  }

  if (BLOB_ITEM_TYPES.has(itemType)) {
    bindObjectUrlRevoke();
    const objectUrl = URL.createObjectURL(blob);
    objectUrls.set(id, objectUrl);
    return renderIntoTab(id, title, itemType, { objectUrl, fileName: name, size: blob.size });
  }

  // 无 blob 腿的类型（zip 等无 viewer 认领者）：可见降级（**最后兜底**，非主路径）
  return 'unsupported';
}

/**
 * 预览一件**本机落盘件**（设备面完成态：`deviceSavedPath`，服务端 `DropboxService`
 * 记录的本机绝对路径）。
 *
 *  📌 定位（devattach 批 · 作者令 2026-09-17 12:31）：本腿在设备面已**不是主路** ——
 *  主路 = 票据路由取字节后走 `previewBlob`（与好友/群面同一条渲染腿），本腿是**回落**
 *  （票据路由不服务文本腿，判据见 `canFetchLocalBytes` 的说明）。回落保留 = 改前行为**逐字**仍在，非死代码。
 *
 * 取数 = 应用既有本机文件链：空内容 + `absPath` ⇒ Canvas `openWorkspaceItem` 发
 * `pop.readFile`（WS，带会话）⇒ 服务端 `FileTypeRegistry` 定 itemType；二进制再由
 * viewer 走 `/api/nf-file` 票据。本函数**不拼 URL、不 fetch**（同 `reference.js`/`chat.js`
 * 的既有开面 idiom，禁第二套开法）。
 *
 * 判据只用扩展名（本腿**手上没有字节**，不做额外取数往返）：无 viewer 认领 ⇒
 * `unsupported`（fail-closed，落可见降级）；这与好友面「字节在手 ⇒ 可加内容嗅探」
 * 的差异是**有意的**，不是两套逻辑。
 *
 * @param {object} p
 * @param {string} p.path  — 本机绝对路径
 * @param {string} [p.title]
 * @returns {'ok'|'unsupported'}
 */
export function previewLocalPath({ path, title }) {
  if (!path) return 'unsupported';
  const itemType = itemTypeForFileName(path);
  if (!itemType || EXCLUDED_ITEM_TYPES.has(itemType)) return 'unsupported';
  window.dispatchEvent(new CustomEvent('workspace-open-item', {
    detail: {
      id: `file:${path}`,
      title: title || String(path).split('/').pop(),
      itemType: '',
      content: '',
      absPath: path,
      path,
      pinned: false,
    },
  }));
  return 'ok';
}

/** 本机落盘件是否可预览（**只对设备面**：好友面字节在手、判据在 `previewBlob` 里，
 *  不看扩展名就能判）。
 *
 *  用途 = 调用方决定「设备卡是否挂可点面」：`false` ⇒ **不挂**（无本地件就没有可渲染的
 *  内容，禁造假按钮，§B.7 ③）而非「点了报错」。 */
export function canPreviewLocalPath(path) {
  const itemType = itemTypeForFileName(path);
  return !!itemType && !EXCLUDED_ITEM_TYPES.has(itemType);
}

/** **按文件名**判「这件能不能在 Canvas 里渲染」——与 [[canPreviewLocalPath]] **同判据、
 *  同真源**（`itemTypeForFileName` + `EXCLUDED_ITEM_TYPES`），唯一差别是入参形态：
 *  本出口收**文件名**而不收路径。
 *
 *  用途（selfattach 批 · 片 2，A 腿）：设备面**发送侧**的字节源是本机 `File` 句柄
 *  （blob 腿）而不是路径 —— 那里没有 `absPath` 可判，但**名称**是真实名
 *  （`att.name` ← 台账 `fileName`，显示名口径：显示名 = 真实名）。
 *  调用方用它决定「值不值得挂可点面」：`false` ⇒ **不挂**（如 `.zip`：`previewBlob`
 *  无 viewer 认领 ⇒ 必然 `unsupported`），避免造「可点但点了必报不支持」的假可点面（§B.7 ③）。
 *
 *  ⚠ 与 `previewBlob` 的**内容嗅探腿**的关系：`previewBlob` 对无扩展名认领的件在
 *  「确证是文本」时仍能渲染，本出口**不**预判这一条（手上还没字节）⇒ 本出口是**保守**
 *  判据（宁可少挂一个可点面，也不造假按钮）。
 *  @param {string} name @returns {boolean} */
export function canPreviewName(name) {
  const itemType = itemTypeForFileName(name);
  return !!itemType && !EXCLUDED_ITEM_TYPES.has(itemType);
}

/** 本机落盘件的字节能否由**票据路由**（`/api/nf-file?path=…&ticket=…`）整件取回
 *  （设备面 legacy 卡的两处判据源：① 挂下载键；② 预览腿选路）。
 *
 *  判据 = 该类型落在 **blob 腿**（`BLOB_ITEM_TYPES`）——即「这件本机落盘件的字节可由
 *  `/api/nf-file` 整件取回」的那些类型（image/pdf/docx/xlsx/pptx/epub；服务端白名单
 *  `NfFileAllowedExt` 覆盖其各族扩展名，见 `WebSocketRoutes.scala:5203-5241`）。
 *
 *  🔴 文本腿（`TEXT_ITEM_TYPES`：code/json/csv/yaml/markdown）**为 false**：它们的字节在
 *  设备面走 `pop.readFile` 的 WS 通道（前端无字节 API），票据路由按扩展名白名单拒绝
 *  ⇒ 这两种消费点都要据此改道：预览腿走 `previewLocalPath`（改前那条路，逐字一致）、
 *  下载腿**不挂键**（挂了就是「可点但点了必然报错」的假按钮，§B.7 ③ 明令禁止）。
 *  ⚠ 登记项已闭环（selfattach 批复核）：`.doc` / `.ppt` / `.xls` 残差已由 **nfext 批
 *  `0f64d8bc5`** 补进服务端白名单（`WebSocketRoutes.scala:5247-5290` 现含 doc/docx/xls/
 *  xlsx/xlsm/ppt/pptx）⇒ 本判据（blob 腿 = viewer 认领面）与服务端白名单在**这三种**上
 *  已同向；本函数仍按**超集**口径工作（服务端白名单才是终判）。
 *  与 `canPreviewLocalPath` **同族**（同一「本机落盘件 + 既有路」判据面），判据源仍是
 *  `itemTypeForFileName`（禁第二张扩展名表）。
 *  @param {string} path @returns {boolean} */
export function canFetchLocalBytes(path) {
  return BLOB_ITEM_TYPES.has(itemTypeForFileName(path));
}

// ── 流式开图（imgdoor #542 · 三档加速）────────────────────────────────────
// 作者令 2026-10-20 逐字：「慢的应该是非本机文件，点击有一个下载过程，但是也应该
// 立即在canvas显示标签页，然后标签页显示一个圈作为下载进度显示。」
//
// `previewWithProgress` = 档3 的单点实现，同时承接档1/档2 的快路：
//   · **tab-first（档3）**：`openTab` 同步出标签（零延迟），之后的一切取数都在
//     标签已可见之后发生——今天的链（`savedPathBlob` 全量缓冲完才 `previewBlob`
//     开标签）从根上倒置；
//   · **进度环（档3）**：`load` 侧边收边报（`onProgress(loaded, total)`），
//     Content-Length 可得 → 百分比环（`data-nf-progress="determinate"`），
//     不可得 → 转圈（`"indeterminate"`）；
//   · **可见失败（档3 + messages.js:2588 族静默降级可见化）**：取数/渲染失败
//     ⇒ 标签内就地错误态（`data-nf-error="1"`）+ **重试键**，永不静默；
//   · **缓存快路（档2）**：`cacheKey` 在场先查 `nfFileCache`，命中零取数直渲染
//     （速度 = 档1）；未命中取数成功后落缓存；
//   · **字节路由零新增（红线）**：本函数**不拼 URL、不 fetch**——字节获取由
//     调用方经 `load` 注入（既有票据路 / friendsApi 鉴权路的流式读出器），本模块
//     的「禁第三条取字节路」纪律逐字保持。
//
// 渲染出口仍走**既有** viewer 注册表（`renderFile`），与 `previewBlob` 同一条腿。

const SVG_NS = 'http://www.w3.org/2000/svg';
const PROGRESS_RING_R = 18;
const PROGRESS_RING_C = 2 * Math.PI * PROGRESS_RING_R;

/** 档3 进度面板：百分比环（Content-Length 可得）或转圈（不可得）。
 *  数据读数面：`data-nf-progress` = determinate|indeterminate、`data-nf-pct` = 0..100。 */
function renderProgressPane(paneEl, label) {
  paneEl.classList.add('scrollable');
  paneEl.innerHTML = '';
  const wrap = document.createElement('div');
  wrap.className = 'nf-progress-pane';
  wrap.setAttribute('data-nf-progress', 'indeterminate');
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'nf-progress-ring');
  svg.setAttribute('viewBox', '0 0 44 44');
  const mkCircle = (cls) => {
    const c = document.createElementNS(SVG_NS, 'circle');
    c.setAttribute('class', cls);
    c.setAttribute('cx', '22');
    c.setAttribute('cy', '22');
    c.setAttribute('r', String(PROGRESS_RING_R));
    return c;
  };
  const bg = mkCircle('nf-progress-ring-bg');
  const fg = mkCircle('nf-progress-ring-fg');
  fg.style.strokeDasharray = String(PROGRESS_RING_C);
  fg.style.strokeDashoffset = String(PROGRESS_RING_C);
  svg.append(bg, fg);
  const spinner = document.createElement('div');
  spinner.className = 'canvas-loading-spinner nf-progress-spinner';
  const pct = document.createElement('div');
  pct.className = 'nf-progress-pct';
  const lab = document.createElement('div');
  lab.className = 'nf-progress-label';
  lab.textContent = label;
  wrap.append(svg, spinner, pct, lab);
  paneEl.appendChild(wrap);
  return {
    /** @param {number} loaded @param {number} total */
    setProgress(loaded, total) {
      if (!wrap.isConnected) return;
      if (total > 0) {
        wrap.setAttribute('data-nf-progress', 'determinate');
        const p = Math.max(0, Math.min(100, Math.round((loaded / total) * 100)));
        fg.style.strokeDashoffset = String(PROGRESS_RING_C * (1 - p / 100));
        pct.textContent = `${p}%`;
        wrap.setAttribute('data-nf-pct', String(p));
      } else {
        wrap.setAttribute('data-nf-progress', 'indeterminate');
      }
    },
  };
}

/** 档3 可见失败态：就地错误面板 + 重试键（**禁静默**——messages.js:2588 族旧腿
 *  「blob 拿不到 ⇒ 悄悄回落文本腿」对图片零反馈，本面板即其可见化替身）。 */
function renderProgressFailure(paneEl, err, subject, onRetry) {
  if (!paneEl.isConnected) return;
  paneEl.classList.add('scrollable');
  paneEl.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'canvas-error';
  box.setAttribute('data-nf-error', '1');
  const statusSuffix = err && /** @type {Error & {status?: number}} */ (err).status
    ? ` (HTTP ${/** @type {Error & {status?: number}} */ (err).status})` : '';
  const msg = document.createElement('div');
  msg.textContent = `${t('canvas.loadFailed')}${statusSuffix}`;
  const sub = document.createElement('div');
  sub.className = 'nf-error-subject';
  sub.textContent = subject || '';
  box.append(msg, sub);
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'viewer-retry-btn';
  retry.textContent = t('canvas.retry');
  retry.setAttribute('data-nf-retry', '1');
  retry.addEventListener('click', () => { if (paneEl.isConnected) onRetry(); });
  box.appendChild(retry);
  paneEl.appendChild(box);
}

/** 终态不可重试面（unsupported / 超上限）：同一面板家族，无重试键。 */
function renderProgressNote(paneEl, message) {
  if (!paneEl.isConnected) return;
  paneEl.classList.add('scrollable');
  paneEl.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'canvas-error';
  box.setAttribute('data-nf-note', '1');
  box.textContent = message;
  paneEl.appendChild(box);
}

/**
 * 一次「进度 → 取数 → 渲染」尝试（重试键复入同一函数 ⇒ 绕开 tab 已存在的
 * 幂等早退）。
 * @returns {Promise<'ok'|'unsupported'|'failed'>}
 */
async function runProgressAttempt(paneEl, args) {
  const { fileName, load, cacheKey } = args;
  const name = fileName || '';
  const prog = renderProgressPane(paneEl, t('canvas.loadingFile'));
  try {
    let blob = cacheKey ? await nfCachedBlob(cacheKey) : null; // 档2：命中零取数
    if (blob) {
      prog.setProgress(1, 1);
    } else {
      const res = await load({ onProgress: prog.setProgress });
      blob = res && res.blob;
      if (blob && cacheKey) void nfCachePut(cacheKey, blob, res.version);
    }
    if (!blob || typeof blob.size !== 'number' || blob.size <= 0) throw new Error('empty body');
    // 判据与 previewBlob 同源同序：调用方显式 itemType > 文件名（ext→itemType 真源
    // `itemTypeForFileName`）> 内容嗅探（无 viewer 认领扩展名时，确证文本才落 code）。
    let effType = args.itemType
      || itemTypeForFileName(name)
      || ((blob.size <= MAX_TEXT_BYTES && await looksTextual(blob)) ? 'code' : '');
    if (!effType || EXCLUDED_ITEM_TYPES.has(effType)) {
      renderProgressNote(paneEl, t('messages.attachPreviewUnsupported', { name }));
      return 'unsupported';
    }
    if (TEXT_ITEM_TYPES.has(effType)) {
      if (blob.size > MAX_TEXT_BYTES) {
        renderProgressNote(paneEl, t('messages.attachPreviewUnsupported', { name }));
        return 'unsupported';
      }
      const content = await blob.text();
      await renderIntoPane(paneEl, effType, { content, fileName: name, size: blob.size });
      return 'ok';
    }
    if (BLOB_ITEM_TYPES.has(effType)) {
      // 重试/重挂腿：同 tab id 换新 objectURL 前先撤旧的（禁泄漏，同 closeTab 家族）。
      const prevUrl = objectUrls.get(args.id);
      if (prevUrl) {
        objectUrls.delete(args.id);
        setTimeout(() => URL.revokeObjectURL(prevUrl), 1000);
      }
      bindObjectUrlRevoke();
      const objectUrl = URL.createObjectURL(blob);
      objectUrls.set(args.id, objectUrl);
      await renderIntoPane(paneEl, effType, { objectUrl, fileName: name, size: blob.size });
      return 'ok';
    }
    renderProgressNote(paneEl, t('messages.attachPreviewUnsupported', { name }));
    return 'unsupported';
  } catch (err) {
    renderProgressFailure(paneEl, /** @type {Error} */ (err), name, () => {
      runProgressAttempt(paneEl, args).catch(() => {});
    });
    return 'failed';
  }
}

/**
 * 流式预览（档1/2/3 单点）：**同步开标签** → 进度 → 取数（缓存快路）→ 渲染，
 * 失败就地可见 + 重试。返回 `'failed'` 时失败态**已在标签内可见**（调用方无需再叠
 * 通用 toast——「可见」由本函数保证，「fail-silent」从此不存在）。
 *
 * @typedef {{ id: string, title?: string, fileName?: string, itemType?: string,
 *   load: (h: {onProgress: (loaded: number, total: number) => void}) => Promise<{blob: Blob, version?: string}>,
 *   cacheKey?: string }} PreviewWithProgressArgs
 *   id = Canvas tab id（重复点击即复用）；itemType 缺席时按文件名 + 内容嗅探判；
 *   load = 字节获取（调用方注入**既有**路由的流式读出器；本模块零 fetch）；
 *   cacheKey = 档2 缓存键（缺席 = 不缓存）。
 * @param {PreviewWithProgressArgs} p
 * @returns {Promise<'ok'|'unsupported'|'failed'>}
 */
export async function previewWithProgress({ id, title, fileName, itemType, load, cacheKey }) {
  if (!id || typeof load !== 'function') return 'failed';
  if (alreadyOpen(id)) return 'ok';
  // 判据先行（同步、零取数）：与 previewBlob 同一真源。openTab 的 type 用它，
  // 使 persistTabs/restore 的形态与改前 previewBlob 腿一致（restore 后重渲染同型）。
  const typeGuess = itemType || itemTypeForFileName(fileName || '');
  // 🔴 tab-first：openTab 同步执行（面板 + 标签当帧可见），取数在其后。
  const entry = openTab(id, title || id, { type: typeGuess || 'generic' });
  if (!entry) return 'failed';
  entry.paneEl.dataset.attachmentPreview = '1';
  return runProgressAttempt(entry.paneEl, { id, title, fileName, itemType: typeGuess, load, cacheKey });
}
