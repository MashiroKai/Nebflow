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

/** 文本预览上限：与 `pop.readFile` 的 10MB 闸**同值同源**（禁第二把尺）。 */
const MAX_TEXT_BYTES = 10 * 1024 * 1024;

/** 对话框内**直显**图片附件的字节上限（uifix 批 2026-09-17）。
 *
 *  刻意**同值同源**于本文件的 `MAX_TEXT_BYTES`：两者都是「一件附件可以进页面」
 *  的同一把尺（同一份字节从同一条鉴权路由取回），分头写两个字面量迟早漂移。
 *  超限 ⇒ 不直显（回落既有附件卡：名称/体积/下载键），**不**做部分渲染、
 *  **不**报错——降级是可见的（卡片本身即说明面）。 */
export const MAX_INLINE_IMAGE_BYTES = MAX_TEXT_BYTES;

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

/** 委托给既有 Canvas tab + viewer 渲染（**唯一**渲染腿，两入口共用）。
 *  @returns {Promise<'ok'|'failed'>} */
async function renderIntoTab(id, title, itemType, ctx) {
  const entry = openTab(id, title || id, { type: itemType });
  if (!entry) return 'failed';
  entry.paneEl.dataset.attachmentPreview = '1';
  const { renderFile } = await import('./fileViewers.js');
  await renderFile(entry.paneEl, { itemType, absPath: null, ...ctx });
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
