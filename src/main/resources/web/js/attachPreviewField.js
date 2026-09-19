// attachPreviewField.js — 好友/群图片预览传输批 · **解耦包「契约字段映射（本仓侧）」**。
//
// 定位：预览字段的**构造 / 解析 / 缺省回落**单点 —— 把「字段名 / 键集 / 取值域 / 缺省」
// 收在一处，避免将来 `messages.js`（读）与 `attachUpload.js`（写）各写一份而漂移。
//
// 🔴 本件**不接触 wire**：零 import、零 fetch、零 `friendsApi` / 零网关模块引用。
//   纯函数面（入参是「已经到手的 JS 对象 / Blob」，出参是「字段对象 / 归一化对象」），
//   可被浏览器与 node 同时 import（顶层不访问 DOM）。自测 = `selfTest()`。
//
// ── 镜像口径（与 `src/main/scala/nebflow/neblink/AttachmentPreviewContract.scala` 同源）──
//   键名 = `preview`，挂在**附件对象**（`AttachmentSummary` 镜像）上，**可选**：
//     · 键缺席  ⇒ 旧消息 / 非图片附件 ⇒ **现行为不变**（回落「无预览」分支）；
//     · 键在场  ⇒ `{ mime, w, h, size, b64 }`，五键**全为必填**（子键不设可选，缺席即该字段整体不可用）。
//   取值域：`mime ∈ {image/webp, image/jpeg}`（= 发送侧格式梯的**两档出口**）。
//
// 三条兼容纪律（作者 2026-09-19 20:4x 裁定 · 「双向兼容钉死」的客户端侧）：
//   ① **旧端载荷逐字节不变** ⇒ 本件只为**新**形态服务；旧消息没有该键时，
//      `readPreviewField` 一律回 `null`，调用方走改前那条路（本件不产生任何替代渲染）；
//   ② **未知/多余字段忽略不炸** —— 解析只读白名单五键，多余键既不复制进归一化结果、
//      也不报错；对**附件对象**的其它键（含将来新增的）本件**零增删**；
//   ③ **预览与原件字段互不污染** —— `writePreviewField` 只动 `preview` 一个键，
//      `stripPreviewField` 的产物与原对象逐键等价（`legacyShapeOf` 是那条判据的单点出口）。

/** 预览字段的**键名**（镜像真源 = `AttachmentPreviewContract.PreviewKey`）。 */
export const PREVIEW_FIELD_KEY = 'preview';

/** 预览字段的**键集**（顺序即编码顺序；镜像真源 = `AttachmentPreviewContract.PreviewKeys`）。 */
export const PREVIEW_FIELD_KEYS = Object.freeze(['mime', 'w', 'h', 'size', 'b64']);

/** 允许的 mime（= 发送侧格式梯两档出口；不在表内 ⇒ 该字段判**不可用**，回落无预览）。 */
export const PREVIEW_FIELD_MIMES = Object.freeze(['image/webp', 'image/jpeg']);

/**
 * **解析侧硬上界**（256 KiB）—— 与发送侧**软上限**（180 KiB，`attachImagePreview.js`
 * `PREVIEW_SOFT_CAP_BYTES`）**不是同一把尺**，刻意分开且不互相推导：
 *   · 软上限 = 发送侧的**降级梯触发线**（工程取值，可随实测调整）；
 *   · 本值 = 解析侧的**收口线**：只拦「明显越界 / 恶意构造」的载荷，不参与常规判定。
 * 取 256 KiB 的理由：给软上限留 ~1.4× 余量，避免两端数值耦合后一起漂移。
 */
export const PREVIEW_FIELD_MAX_BYTES = 256 * 1024;

/** 附件对象上与预览**无关**的既有键（`legacyShapeOf` 的判据面；只读参照，见 NeblinkModel 镜像）。 */
export const ATTACHMENT_LEGACY_KEYS = Object.freeze(['id', 'name', 'size', 'sha256', 'state', 'mime']);

/** 是否普通对象（非 null / 非数组）。 @returns {boolean} */
function isPlainObject(v) {
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

/** 正整数（`Number.isInteger` 且 > 0）—— 尺寸/字节数一律用它，禁接受字符串数字。 @returns {boolean} */
function isPositiveInt(v) {
  return typeof v === 'number' && Number.isInteger(v) && v > 0;
}

/** n 字节的 base64 长度（无换行标准编码）—— 纯算式，**不解码**就能做的完整性判据。 */
function base64LengthOf(byteCount) {
  return 4 * Math.ceil(byteCount / 3);
}

/**
 * 读预览字段 —— **唯一解析入口**。
 *
 * 判据（任一不成立 ⇒ 返回 `null` ⇒ 调用方走「无预览」= 改前那条路）：
 *   ① 附件对象是普通对象，且 `preview` 是普通对象（`null` / 数组 / 字符串一律不算）；
 *   ② `mime` ∈ [[PREVIEW_FIELD_MIMES]]；
 *   ③ `w` / `h` 为正整数；`size` 为不超过 [[PREVIEW_FIELD_MAX_BYTES]] 的正整数；
 *   ④ `b64` 是非空字符串，且**长度 == 4 × ceil(size / 3)**（长度即完整性判据，零解码成本）。
 * 多余键**不读不报**；任何异常路径都不抛。
 *
 * @param {any} attachment @returns {{mime:string,w:number,h:number,size:number,b64:string}|null}
 */
export function readPreviewField(attachment) {
  if (!isPlainObject(attachment)) return null;
  const raw = attachment[PREVIEW_FIELD_KEY];
  if (!isPlainObject(raw)) return null;
  const mime = typeof raw.mime === 'string' ? raw.mime : '';
  if (!PREVIEW_FIELD_MIMES.includes(mime)) return null;
  if (!isPositiveInt(raw.w) || !isPositiveInt(raw.h)) return null;
  if (!isPositiveInt(raw.size) || raw.size > PREVIEW_FIELD_MAX_BYTES) return null;
  const b64 = typeof raw.b64 === 'string' ? raw.b64 : '';
  if (b64.length === 0 || b64.length !== base64LengthOf(raw.size)) return null;
  return { mime, w: raw.w, h: raw.h, size: raw.size, b64 };
}

/** 这件附件**有没有可用的预览**（= `readPreviewField` 非空）。 @returns {boolean} */
export function hasPreviewField(attachment) {
  return readPreviewField(attachment) !== null;
}

/** Uint8Array → 标准 base64（分块 `btoa`，避免大数组展开爆栈）。
 *
 *  🔴 **块长必须是 3 的整数倍**（此处 0x7E00 = 32,256 = 3 × 10,752）：
 *  base64 每 3 字节出 4 字符，只有**末块**允许出现 `=` 填充；块长若不被 3 整除，
 *  中间块会产出 `=`，拼接后整个串**不再是合法 base64** ⇒ 解码端（`atob` / `Buffer`）
 *  会在该处停下，**静默截断**成前若干块（实测：块长 0x8000 时 >32 KiB 的载荷全部
 *  截断到 32,768 B）。自测的跨块往返用例（见 `selfTest`）就是这条纪律的守门人。
 *  @returns {string} */
export function bytesToBase64(bytes) {
  const CHUNK = 0x7e00;
  let out = '';
  for (let i = 0; i < bytes.length; i += CHUNK) {
    const end = Math.min(i + CHUNK, bytes.length);
    let chunk = '';
    for (let j = i; j < end; j++) chunk += String.fromCharCode(bytes[j]);
    out += btoa(chunk);
  }
  return out;
}

/** 标准 base64 → Uint8Array（分块 `atob`）。 **不吞异常**：非法串由调用方处置。 @returns {Uint8Array} */
export function base64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * 把**生成器出口**（`makePreviewFrame` 的结果或等形对象）转成**字段对象**。
 *
 * @param {{blob?:any,mime?:string,width?:number,height?:number,bytes?:number}} frame
 * @returns {Promise<{mime:string,w:number,h:number,size:number,b64:string}|null>}
 *   `null` ⇒ **不申报预览**（生成器 `ok:false`、形状不全、mime 越域、体积越界、
 *   或 base64 环节异常）—— 契约字段可选 ⇒ 调用方原样走改前路径。
 */
export async function buildPreviewField(frame) {
  try {
    if (!isPlainObject(frame) || !frame.blob) return null;
    const blob = frame.blob;
    const mime = typeof frame.mime === 'string' ? frame.mime : (blob.type || '');
    if (!PREVIEW_FIELD_MIMES.includes(mime)) return null;
    const w = frame.width;
    const h = frame.height;
    if (!isPositiveInt(w) || !isPositiveInt(h)) return null;
    const size = typeof blob.size === 'number' ? blob.size : Number(frame.bytes);
    if (!isPositiveInt(size) || size > PREVIEW_FIELD_MAX_BYTES) return null;
    const buf = await blob.arrayBuffer();
    const bytes = new Uint8Array(buf);
    if (bytes.length !== size) return null;
    const b64 = bytesToBase64(bytes);
    if (b64.length !== base64LengthOf(size)) return null;
    return { mime, w, h, size, b64 };
  } catch (err) {
    return null;
  }
}

/**
 * 把字段对象**写进**附件对象（返回**新**对象，调用方原对象零改动）。
 *
 * 纪律：只增删 `preview` **一个**键；其余键（含未知键）逐键原样搬运 ⇒ 预览与原件字段
 * **互不污染**。`field` 为 `null`/`undefined` ⇒ 等价于 [[stripPreviewField]]（清除）。
 * @returns {any} */
export function writePreviewField(attachment, field) {
  const src = isPlainObject(attachment) ? attachment : {};
  if (field === null || field === undefined) return stripPreviewField(src);
  const out = {};
  for (const k of Object.keys(src)) {
    if (k === PREVIEW_FIELD_KEY) continue;
    out[k] = src[k];
  }
  out[PREVIEW_FIELD_KEY] = field;
  return out;
}

/** 去掉 `preview` 键的**新**对象（其余键逐键原样）—— 「旧端形态」的构造单点。 @returns {any} */
export function stripPreviewField(attachment) {
  const src = isPlainObject(attachment) ? attachment : {};
  const out = {};
  for (const k of Object.keys(src)) {
    if (k === PREVIEW_FIELD_KEY) continue;
    out[k] = src[k];
  }
  return out;
}

/**
 * 「旧端载荷逐字节不变」的**本仓侧判据出口**：只取**既有键**并按**既有顺序**回出，
 * 与 `preview` 在场与否无关。
 *
 * 用途：构造/对比旧形态字符串（`JSON.stringify(legacyShapeOf(att))`）—— 任何把
 * `preview` 写歪（写成 `null`、写成多余键、改动了既有键）的实现都会在此现形。
 * @returns {any} */
export function legacyShapeOf(attachment) {
  const src = isPlainObject(attachment) ? attachment : {};
  const out = {};
  for (const k of ATTACHMENT_LEGACY_KEYS) {
    if (Object.prototype.hasOwnProperty.call(src, k)) out[k] = src[k];
  }
  return out;
}

/**
 * 预览字节 → `Blob`（渲染/直显用）。**解码后复核长度**：与字段申报的 `size` 不等
 * ⇒ 返回 `null`（fail-closed，不把残缺字节交给渲染面）。
 * @param {any} attachment @returns {Blob|null} */
export function previewBlobOf(attachment) {
  const field = readPreviewField(attachment);
  if (!field) return null;
  try {
    const bytes = base64ToBytes(field.b64);
    if (bytes.length !== field.size) return null;
    return new Blob([bytes], { type: field.mime });
  } catch (err) {
    return null;
  }
}

/**
 * 自测（纯函数面，**零 wire / 零 DOM**；浏览器与 node 同样可跑）。
 *
 * 三类必测（作者裁定）：**缺字段 / 未知字段 / 空值**，外加密钥集、互不污染、
 * 旧形态等价、构造-解析往返。
 * @returns {Promise<{passed:number,failed:number,total:number,green:boolean,cases:Array<{name:string,ok:boolean,detail:string}>}>}
 */
export async function selfTest() {
  const cases = [];
  const check = (name, ok, detail) => { cases.push({ name, ok: !!ok, detail: String(detail) }); };

  // ── 1. 缺字段：旧消息 ⇒ 现行为不变 ──
  const legacy = { id: 'a1', name: 'p.jpg', size: 900000, sha256: 'ab', state: 'ready', mime: 'image/jpeg' };
  check('missing-key/read-null', readPreviewField(legacy) === null, 'readPreviewField(legacy) should be null');
  check('missing-key/has-false', hasPreviewField(legacy) === false, 'hasPreviewField(legacy) should be false');
  check('missing-key/blob-null', previewBlobOf(legacy) === null, 'previewBlobOf(legacy) should be null');
  check(
    'missing-key/legacy-shape-byte-equal',
    JSON.stringify(legacyShapeOf(legacy)) === JSON.stringify(legacy),
    'legacyShapeOf must reproduce the legacy key order/values byte-for-byte'
  );

  // ── 2. 未知字段：多余键忽略不炸，既有/未知键零增删 ──
  const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  const b64 = bytesToBase64(bytes);
  const withUnknown = {
    ...legacy,
    futureKey: { keep: true },
    preview: { mime: 'image/webp', w: 4, h: 3, size: bytes.length, b64, extra: 'ignore-me', future: 42 },
  };
  const readUnknown = readPreviewField(withUnknown);
  check('unknown-keys/parsed', readUnknown !== null, 'preview with unknown sub-keys must still parse');
  check(
    'unknown-keys/not-copied',
    JSON.stringify(Object.keys(readUnknown || {}).sort()) === JSON.stringify([...PREVIEW_FIELD_KEYS].sort()),
    'normalized field must carry exactly the whitelisted keys'
  );
  const written = writePreviewField(withUnknown, readUnknown);
  check(
    'unknown-keys/attachment-untouched',
    JSON.stringify(written.futureKey) === JSON.stringify(withUnknown.futureKey),
    'unknown attachment keys must survive writePreviewField untouched'
  );
  check('unknown-keys/no-throw', readPreviewField({ preview: { mime: 5, w: 'x', h: null, size: {}, b64: [] } }) === null, 'garbage shapes must read null, not throw');

  // ── 3. 空值：null / 空串 / 空对象 / 长度不符 ⇒ 一律回落无预览 ──
  const emptyCases = [
    ['preview-null', { ...legacy, preview: null }],
    ['preview-string', { ...legacy, preview: 'image/webp' }],
    ['preview-array', { ...legacy, preview: [] }],
    ['preview-empty-object', { ...legacy, preview: {} }],
    ['mime-empty', { ...legacy, preview: { mime: '', w: 4, h: 3, size: 10, b64 } }],
    ['mime-out-of-set', { ...legacy, preview: { mime: 'image/png', w: 4, h: 3, size: 10, b64 } }],
    ['b64-empty', { ...legacy, preview: { mime: 'image/webp', w: 4, h: 3, size: 10, b64: '' } }],
    ['size-zero', { ...legacy, preview: { mime: 'image/webp', w: 4, h: 3, size: 0, b64: '' } }],
    ['size-mismatch-b64len', { ...legacy, preview: { mime: 'image/webp', w: 4, h: 3, size: 10, b64: b64.slice(0, 8) } }],
    ['size-over-hard-cap', { ...legacy, preview: { mime: 'image/webp', w: 4, h: 3, size: PREVIEW_FIELD_MAX_BYTES + 1, b64 } }],
  ];
  for (const [label, att] of emptyCases) {
    check(`empty-value/${label}`, readPreviewField(att) === null, `${label} must fall back to the no-preview branch`);
  }

  // ── 4. 构造 → 解析往返 + 互不污染 ──
  const blob = new Blob([bytes], { type: 'image/webp' });
  const field = await buildPreviewField({ blob, mime: 'image/webp', width: 4, height: 3, bytes: bytes.length });
  check('construct/field-shape', !!field && field.size === bytes.length && field.mime === 'image/webp', 'buildPreviewField must yield the declared shape');
  check(
    'construct/key-order',
    !!field && JSON.stringify(Object.keys(field)) === JSON.stringify([...PREVIEW_FIELD_KEYS]),
    'constructed field key order must equal PREVIEW_FIELD_KEYS'
  );
  const attached = writePreviewField(legacy, field);
  check('roundtrip/read-back', JSON.stringify(readPreviewField(attached)) === JSON.stringify(field), 'written field must parse back identically');
  check(
    'no-pollution/legacy-keys',
    JSON.stringify(legacyShapeOf(attached)) === JSON.stringify(legacy),
    'adding preview must not alter any legacy key'
  );
  check(
    'no-pollution/strip-restores',
    JSON.stringify(stripPreviewField(attached)) === JSON.stringify(legacy),
    'stripPreviewField must restore the legacy shape byte-for-byte'
  );
  const stripped = stripPreviewField(attached);
  check('no-pollution/has-false-after-strip', hasPreviewField(stripped) === false, 'stripped attachment must report no preview');
  check('construct/null-frame', (await buildPreviewField(null)) === null, 'buildPreviewField(null) must be null');
  check('construct/bad-mime-frame', (await buildPreviewField({ blob, mime: 'image/gif', width: 4, height: 3 })) === null, 'out-of-set mime must be refused');
  const decoded = previewBlobOf(attached);
  check('blob-of/decoded', !!decoded && decoded.size === bytes.length && decoded.type === 'image/webp', 'previewBlobOf must rebuild the exact byte count');

  // ── 5. 跨块边界往返（>1 块 ⇒ 逼出「分块 btoa 的块长必须是 3 的倍数」这条纪律）──
  //     实测教训：块长取 2 的幂（0x8000）时，中间块产出 `=` 填充 ⇒ 解码端在 32,768 B
  //     处**静默截断**，预览载荷越大丢得越多。本用例是那条失败模式的守门人。
  const big = new Uint8Array(70000);
  for (let i = 0; i < big.length; i++) big[i] = (i * 31) % 256;
  const bigB64 = bytesToBase64(big);
  check('base64/multi-chunk-length', bigB64.length === base64LengthOf(big.length), `${bigB64.length} vs expected ${base64LengthOf(big.length)}`);
  check('base64/multi-chunk-no-interior-padding', !bigB64.slice(0, bigB64.length - 2).includes('='), 'interior "=" means chunks are not 3-byte aligned');
  const backBytes = base64ToBytes(bigB64);
  let same = backBytes.length === big.length;
  if (same) for (let i = 0; i < big.length; i++) if (backBytes[i] !== big[i]) { same = false; break; }
  check('base64/multi-chunk-roundtrip', same, `decoded ${backBytes.length} bytes, expected ${big.length}`);
  const bigBlob = new Blob([big], { type: 'image/webp' });
  const bigField = await buildPreviewField({ blob: bigBlob, mime: 'image/webp', width: 640, height: 480 });
  check('base64/multi-chunk-field-shape', !!bigField && bigField.size === 70000, 'a >32 KiB payload must survive buildPreviewField');
  const bigBlobBack = previewBlobOf({ preview: bigField });
  check('base64/multi-chunk-blob-roundtrip', !!bigBlobBack && bigBlobBack.size === 70000, 'previewBlobOf must return the full byte count (no truncation)');

  const passed = cases.filter((c) => c.ok).length;
  return { passed, failed: cases.length - passed, total: cases.length, green: passed === cases.length, cases };
}
