// attachPreviewField.js — 好友/群图片预览传输批 · **契约字段读写单点**（2026-09-21 传输集成批）。
//
// 定位：预览字段的**构造 / 解析 / 缺省回落 / `data:` 合成**单点 —— 把「字段名 / 键集 / 取值域
// / 上限 / 编码」收在一处，避免 `messages.js`（读）与 `attachUpload.js`（写）各写一份而漂移。
//
// 🔴 本件**不接触 wire**：零 import、零 fetch、零 `friendsApi` / 零网关模块引用。
//   纯函数面（入参是「已经到手的 JS 对象 / Blob」，出参是「字段对象 / 归一化对象」），
//   可被浏览器与 node 同时 import（顶层不访问 DOM）。自测 = `selfTest()`。
//
// ── 线面口径（真源 = `AttachmentPreviewContract.scala` §B.1，逐键同值同源）──
//   键名 = `preview`，挂在**附件对象**上，**可选**：
//     · 键缺席 / `null` / 形状不全 ⇒ **无预览**（调用方走改前那条「拉原件」路）；
//     · 键在场 ⇒ `{ fmt, w, h, bytes, sha256, b64 }`，**六键全有或全无**（无「只有几何没有字节」形态）。
//   取值域：`fmt ∈ {webp, jpeg}`（**裸格式名**；不是 `jpg`、不是 `image/webp`）。
//   `sha256` = 对 `b64` **解码后**字节取的 64 位小写 hex；`b64` = RFC 4648 §4 标准 base64（带 `=` 填充）。
//   🔴 `b64` **不带 `data:` 前缀**：`fmt` 已经载着格式了，一个事实只拼一次。`data:` 的**合成
//      只落在本件**（[[previewDataUrlOf]]）—— wire 面零 `data:` 字面（判据 §3.3 的 grep 面）。
//
// ── 形变登记（上一批 → 本批；不是「顺手改」）──
//   上一批（解耦包）本件是**五键** `{mime,w,h,size,b64}`。跨仓腿 ① 落地后服务端契约是**六键**
//   ⇒ 本批逐键对齐：`mime`→`fmt`（值域 = 裸格式名）、`size`→`bytes`、**新增 `sha256`**、
//   新增 b64 串长上限常量。旧五键形态**从未上线**（解耦包零接线）⇒ 无兼容面。
//
// 三条兼容纪律（照旧，逐条不变）：
//   ① **旧端载荷逐字节不变**：无该键 ⇒ [[previewOf]] 恒回 `null`，调用方走改前那条路；
//   ② **未知/多余字段忽略不炸**：解析只读白名单六键，多余键既不复制进归一化结果、也不报错；
//   ③ **预览与原件字段互不污染**：[[writePreviewField]] 只动 `preview` 一个键。

/** 预览字段的**键名**（真源 = `AttachmentPreviewContract.PreviewKey`）。 */
export const PREVIEW_FIELD_KEY = 'preview';

/** 预览字段的**键集**（顺序即编码顺序 = 结构体声明序；真源 = `AttachmentPreviewContract.PreviewKeys`）。 */
export const PREVIEW_FIELD_KEYS = Object.freeze(['fmt', 'w', 'h', 'bytes', 'sha256', 'b64']);

/** 允许的 `fmt`（= 发送侧格式梯两档出口；不在表内 ⇒ 该字段判**不可用**，回落无预览）。 */
export const PREVIEW_FIELD_FORMATS = Object.freeze(['webp', 'jpeg']);

/** 生成器（`attachImagePreview.js`）的 mime → 线面 `fmt`（**唯一**映射表；与 Scala 侧 [[Mimes]] 同源）。 */
export const PREVIEW_FIELD_FORMAT_BY_MIME = Object.freeze({ 'image/webp': 'webp', 'image/jpeg': 'jpeg' });

/** 尺寸上限（px，长边）—— 与生成侧钳制同值（**一个数一处**）。 */
export const PREVIEW_FIELD_MAX_DIM = 1280;

/**
 * **解析侧硬上界**（256 KiB = 服务端闸的同值）—— 与发送侧**软上限**（180 KiB，
 * `attachImagePreview.js` `PREVIEW_SOFT_CAP_BYTES`）**不是同一把尺**，刻意分开且不互相推导：
 *   · 软上限 = 发送侧的**降级梯触发线**（工程取值，可随实测调整）；
 *   · 本值 = 解析侧的**收口线**（也是服务端 `MAX_ATTACHMENT_PREVIEW_BYTES` 的同值）。
 */
export const PREVIEW_FIELD_MAX_BYTES = 262144;

/** `b64` **串长**上限 `4 × ⌈262144/3⌉` = 349,528（先验长、再解码 ⇒ 免解码拦巨型串）。 */
export const PREVIEW_FIELD_MAX_B64_LEN = 4 * Math.ceil(PREVIEW_FIELD_MAX_BYTES / 3);

/** 附件对象上与预览**无关**的既有键（`legacyShapeOf` 的判据面；只读参照，见 NeblinkModel 镜像）。 */
export const ATTACHMENT_LEGACY_KEYS = Object.freeze(['id', 'name', 'size', 'mime', 'sha256', 'state']);

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

/** 64 位**小写** hex（与服务端 `is_valid_sha256_hex` 同判据）。 @returns {boolean} */
export function isSha256Hex(v) {
  return typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
}

/** 线面 `fmt` 是否在取值域内。 @returns {boolean} */
export function formatAllowed(fmt) {
  return PREVIEW_FIELD_FORMATS.includes(fmt);
}

/**
 * 读预览字段 —— **唯一解析入口**（判据 §3.3「读写单点」的读面）。
 *
 * 判据（任一不成立 ⇒ 返回 `null` ⇒ 调用方走「无预览」= 改前那条路）：
 *   ① 附件对象是普通对象，且 `preview` 是普通对象（`null` / 数组 / 字符串一律不算）；
 *   ② `fmt ∈ PREVIEW_FIELD_FORMATS`；
 *   ③ `w` / `h` 为正整数且长边 ≤ [[PREVIEW_FIELD_MAX_DIM]]；
 *   ④ `bytes` 为不超过 [[PREVIEW_FIELD_MAX_BYTES]] 的正整数；
 *   ⑤ `sha256` 是 64 位小写 hex；
 *   ⑥ `b64` 是非空字符串，**长度 == 4 × ceil(bytes / 3)** 且 ≤ [[PREVIEW_FIELD_MAX_B64_LEN]]
 *      （长度即完整性判据，零解码成本）。
 * 多余键**不读不报**；任何异常路径都不抛。
 *
 * @param {any} attachment
 * @returns {{fmt:string,w:number,h:number,bytes:number,sha256:string,b64:string}|null}
 */
export function previewOf(attachment) {
  if (!isPlainObject(attachment)) return null;
  const raw = attachment[PREVIEW_FIELD_KEY];
  if (!isPlainObject(raw)) return null;
  const fmt = typeof raw.fmt === 'string' ? raw.fmt : '';
  if (!formatAllowed(fmt)) return null;
  if (!isPositiveInt(raw.w) || !isPositiveInt(raw.h)) return null;
  if (Math.max(raw.w, raw.h) > PREVIEW_FIELD_MAX_DIM) return null;
  if (!isPositiveInt(raw.bytes) || raw.bytes > PREVIEW_FIELD_MAX_BYTES) return null;
  if (!isSha256Hex(raw.sha256)) return null;
  const b64 = typeof raw.b64 === 'string' ? raw.b64 : '';
  if (b64.length === 0 || b64.length > PREVIEW_FIELD_MAX_B64_LEN) return null;
  if (b64.length !== base64LengthOf(raw.bytes)) return null;
  return { fmt, w: raw.w, h: raw.h, bytes: raw.bytes, sha256: raw.sha256, b64 };
}

/** 这件附件**有没有可用的预览**（= [[previewOf]] 非空）。 @returns {boolean} */
export function hasPreviewOf(attachment) {
  return previewOf(attachment) !== null;
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

/** 字节 → SHA-256 的 **64 位小写 hex**（`crypto.subtle` —— 浏览器与 node ≥16 同一 API）。
 *  能力缺失 / 计算失败 ⇒ **reject**（由调用方 fail-closed 收口，禁静默回空串）。
 *  @param {Uint8Array} bytes @returns {Promise<string>} */
export async function sha256HexOfBytes(bytes) {
  const subtle = (typeof crypto !== 'undefined' && crypto && crypto.subtle) ? crypto.subtle : null;
  if (!subtle || typeof subtle.digest !== 'function') throw new Error('no-subtle-crypto');
  const buf = await subtle.digest('SHA-256', bytes);
  const view = new Uint8Array(buf);
  let out = '';
  for (let i = 0; i < view.length; i++) out += view[i].toString(16).padStart(2, '0');
  return out;
}

/**
 * 把**生成器出口**（`makePreviewFrame` 的结果）转成**线面字段对象**（判据 §3.2 的写面）。
 *
 * 取数纪律：`bytes` **不重算**（= 生成器给的真值 `blob.size`，两者不等即判形状错、不申报）；
 * `sha256` / `b64` 由生成器同批算出（同一份 blob 字节 ⇒ 三者机械自洽）。
 *
 * @param {{blob?:any,mime?:string,width?:number,height?:number,bytes?:number,sha256?:string,b64?:string}} frame
 * @returns {{fmt:string,w:number,h:number,bytes:number,sha256:string,b64:string}|null}
 *   `null` ⇒ **不申报预览**（生成器 `ok:false`、形状不全、mime 越域、体积越界、摘要/编码缺失）
 *   —— 契约字段可选 ⇒ 调用方原样走改前路径。
 */
export function declarePreviewOf(frame) {
  if (!isPlainObject(frame) || !frame.blob) return null;
  const blob = frame.blob;
  const mime = typeof frame.mime === 'string' ? frame.mime : (blob.type || '');
  const fmt = PREVIEW_FIELD_FORMAT_BY_MIME[mime] || '';
  if (!formatAllowed(fmt)) return null;
  const w = frame.width;
  const h = frame.height;
  if (!isPositiveInt(w) || !isPositiveInt(h) || Math.max(w, h) > PREVIEW_FIELD_MAX_DIM) return null;
  const bytes = typeof blob.size === 'number' ? blob.size : NaN;
  if (!isPositiveInt(bytes) || bytes > PREVIEW_FIELD_MAX_BYTES) return null;
  if (typeof frame.bytes === 'number' && frame.bytes !== bytes) return null; // 生成器读数与 blob 不一致 ⇒ 不申报
  const sha256 = typeof frame.sha256 === 'string' ? frame.sha256 : '';
  if (!isSha256Hex(sha256)) return null;
  const b64 = typeof frame.b64 === 'string' ? frame.b64 : '';
  if (b64.length === 0 || b64.length > PREVIEW_FIELD_MAX_B64_LEN) return null;
  if (b64.length !== base64LengthOf(bytes)) return null;
  return { fmt, w, h, bytes, sha256, b64 };
}

/** 旧名（同族先例沿革）：`declarePreviewOf` 的**别名**，同一实现、零第二份。 @type {typeof declarePreviewOf} */
export const buildPreviewField = declarePreviewOf;

/**
 * 预览字段 → `data:` URL —— 🔴 **全仓唯一**的 `data:` 合成点（判据 §3.3 的 grep 面）。
 * MIME 子类型与 `fmt` **恒等**（`webp` / `jpeg` 两档白名单内 `data:image/${fmt}` 就是对的）。
 * @param {any} attachment @returns {string} 无预览 ⇒ `''`
 */
export function previewDataUrlOf(attachment) {
  const field = previewOf(attachment);
  if (!field) return '';
  return `data:image/${field.fmt};base64,${field.b64}`;
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
 * 预览字节 → `Blob`（渲染/直显用）。**解码后复核长度**：与字段申报的 `bytes` 不等
 * ⇒ 返回 `null`（fail-closed，不把残缺字节交给渲染面）。
 * @param {any} attachment @returns {Blob|null} */
export function previewBlobOf(attachment) {
  const field = previewOf(attachment);
  if (!field) return null;
  try {
    const bytes = base64ToBytes(field.b64);
    if (bytes.length !== field.bytes) return null;
    return new Blob([bytes], { type: `image/${field.fmt}` });
  } catch (err) {
    return null;
  }
}

/**
 * 自测（纯函数面，**零 wire / 零 DOM**；浏览器与 node 同样可跑）。
 *
 * 三类必测（作者裁定）：**缺字段 / 未知字段 / 空值**，外加密钥集、互不污染、旧形态等价、
 * 构造-解析往返、摘要与 base64 的机械自洽。
 * @returns {Promise<{passed:number,failed:number,total:number,green:boolean,cases:Array<{name:string,ok:boolean,detail:string}>}>}
 */
export async function selfTest() {
  const cases = [];
  const check = (name, ok, detail) => { cases.push({ name, ok: !!ok, detail: String(detail) }); };

  // ── 1. 缺字段：旧消息 ⇒ 现行为不变 ──
  // 夹具键序 = wire 键序（id,name,size,mime,sha256,state = `ATTACHMENT_LEGACY_KEYS` 声明序）
  const legacy = { id: 'a1', name: 'p.jpg', size: 900000, mime: 'image/jpeg', sha256: 'ab', state: 'ready' };
  check('missing-key/read-null', previewOf(legacy) === null, 'previewOf(legacy) should be null');
  check('missing-key/has-false', hasPreviewOf(legacy) === false, 'hasPreviewOf(legacy) should be false');
  check('missing-key/blob-null', previewBlobOf(legacy) === null, 'previewBlobOf(legacy) should be null');
  check('missing-key/data-url-empty', previewDataUrlOf(legacy) === '', 'previewDataUrlOf(legacy) should be empty');
  check(
    'missing-key/legacy-shape-byte-equal',
    JSON.stringify(legacyShapeOf(legacy)) === JSON.stringify(legacy),
    'legacyShapeOf must reproduce the legacy key order/values byte-for-byte'
  );

  // ── 2. 未知字段：多余键忽略不炸，既有/未知键零增删 ──
  const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  const b64 = bytesToBase64(bytes);
  const sha = await sha256HexOfBytes(bytes);
  check('sha256/hex-shape', isSha256Hex(sha), `sha256 must be 64 lowercase hex, got ${sha}`);
  const field0 = { fmt: 'webp', w: 4, h: 3, bytes: bytes.length, sha256: sha, b64 };
  const withUnknown = {
    ...legacy,
    futureKey: { keep: true },
    preview: { ...field0, extra: 'ignore-me', future: 42 },
  };
  const readUnknown = previewOf(withUnknown);
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
  check('unknown-keys/no-throw', previewOf({ preview: { fmt: 5, w: 'x', h: null, bytes: {}, b64: [] } }) === null, 'garbage shapes must read null, not throw');

  // ── 3. 空值：null / 空串 / 空对象 / 长度不符 / 越域 ⇒ 一律回落无预览 ──
  const bad = (over) => ({ ...legacy, preview: { ...field0, ...over } });
  const emptyCases = [
    ['preview-null', { ...legacy, preview: null }],
    ['preview-string', { ...legacy, preview: 'webp' }],
    ['preview-array', { ...legacy, preview: [] }],
    ['preview-empty-object', { ...legacy, preview: {} }],
    ['fmt-empty', bad({ fmt: '' })],
    ['fmt-out-of-set-jpg', bad({ fmt: 'jpg' })],
    ['fmt-out-of-set-mime', bad({ fmt: 'image/webp' })],
    ['fmt-uppercase', bad({ fmt: 'WEBP' })],
    ['sha256-short', bad({ sha256: sha.slice(0, 63) })],
    ['sha256-uppercase', bad({ sha256: sha.toUpperCase() })],
    ['sha256-missing', { ...legacy, preview: { fmt: 'webp', w: 4, h: 3, bytes: bytes.length, b64 } }],
    ['b64-empty', bad({ b64: '' })],
    ['bytes-zero', bad({ bytes: 0, b64: '' })],
    ['bytes-over-hard-cap', bad({ bytes: PREVIEW_FIELD_MAX_BYTES + 1, b64: b64 })],
    ['dim-over-max', bad({ w: PREVIEW_FIELD_MAX_DIM + 1, h: 3 })],
    ['b64-length-mismatch', bad({ b64: b64.slice(0, 8) })],
    ['b64-over-max-len', bad({ b64: 'A'.repeat(PREVIEW_FIELD_MAX_B64_LEN + 1) })],
  ];
  for (const [label, att] of emptyCases) {
    check(`empty-value/${label}`, previewOf(att) === null, `${label} must fall back to the no-preview branch`);
  }

  // ── 4. 构造 → 解析往返 + 互不污染 + `data:` 单点 ──
  const blob = new Blob([bytes], { type: 'image/webp' });
  const frame = { blob, mime: 'image/webp', width: 4, height: 3, bytes: bytes.length, sha256: sha, b64 };
  const field = declarePreviewOf(frame);
  check('construct/field-shape', !!field && field.bytes === bytes.length && field.fmt === 'webp', 'declarePreviewOf must yield the declared shape');
  check(
    'construct/key-order',
    !!field && JSON.stringify(Object.keys(field)) === JSON.stringify([...PREVIEW_FIELD_KEYS]),
    'constructed field key order must equal PREVIEW_FIELD_KEYS'
  );
  check('construct/bytes-mismatch-refused', declarePreviewOf({ ...frame, bytes: bytes.length + 1 }) === null, 'a frame whose bytes reading disagrees with blob.size must be refused');
  check('construct/mime-out-of-set', declarePreviewOf({ ...frame, mime: 'image/gif' }) === null, 'out-of-set mime must be refused');
  check('construct/no-sha256', declarePreviewOf({ ...frame, sha256: '' }) === null, 'a frame without sha256 must be refused');
  check('construct/b64-len-mismatch', declarePreviewOf({ ...frame, b64: b64.slice(0, 8) }) === null, 'a frame whose b64 length disagrees with bytes must be refused');
  check('construct/null-frame', declarePreviewOf(null) === null, 'declarePreviewOf(null) must be null');
  check('construct/alias-same-impl', buildPreviewField === declarePreviewOf, 'buildPreviewField must be the same single implementation (no second copy)');
  const attached = writePreviewField(legacy, field);
  check('roundtrip/read-back', JSON.stringify(previewOf(attached)) === JSON.stringify(field), 'written field must parse back identically');
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
  check('no-pollution/has-false-after-strip', hasPreviewOf(stripPreviewField(attached)) === false, 'stripped attachment must report no preview');
  // `data:` 合成的**形状**判据（wire 面零前缀 → 只在读面合成）
  const dataUrl = previewDataUrlOf(attached);
  check('data-url/prefix-shape', dataUrl === `data:image/webp;base64,${b64}`, `unexpected data url: ${dataUrl.slice(0, 40)}`);
  check('data-url/no-raw-b64-in-wire-field', String(field.b64).indexOf('data:') < 0, 'the wire field must carry a pure base64 string');
  const decoded = previewBlobOf(attached);
  check('blob-of/decoded', !!decoded && decoded.size === bytes.length && decoded.type === 'image/webp', 'previewBlobOf must rebuild the exact byte count');
  check('blob-of/truncated-refused', previewBlobOf({ preview: { ...field, b64: b64.slice(0, 4) } }) === null, 'a truncated payload must be refused (length re-check)');

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
  const bigField = declarePreviewOf({ blob: bigBlob, mime: 'image/webp', width: 640, height: 480, bytes: big.length, sha256: await sha256HexOfBytes(big), b64: bigB64 });
  check('base64/multi-chunk-field-shape', !!bigField && bigField.bytes === 70000, 'a >32 KiB payload must survive declarePreviewOf');
  const bigBlobBack = previewBlobOf({ preview: bigField });
  check('base64/multi-chunk-blob-roundtrip', !!bigBlobBack && bigBlobBack.size === 70000, 'previewBlobOf must return the full byte count (no truncation)');

  const passed = cases.filter((c) => c.ok).length;
  return { passed, failed: cases.length - passed, total: cases.length, green: passed === cases.length, cases };
}
