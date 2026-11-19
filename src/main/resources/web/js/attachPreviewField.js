// attachPreviewField.js — 好友/群图片预览传输批 · **契约字段读写单点**（字段方言双兼容批 2026-09-22）。
//
// 定位：预览字段的**构造 / 解析 / 缺省回落 / `data:` 合成**单点 —— 把「方言登记 / 取值域 /
// 上限 / 编码」收在一处，避免 `messages.js`（读）与 `attachUpload.js`（写）各写一份而漂移。
//
// 🔴 本件**不接触 wire**：零 import、零 fetch、零 `friendsApi` / 零网关模块引用。
//   纯函数面（入参是「已经到手的 JS 对象 / Blob」，出参是「归一化字段对象 / 线面对象」），
//   可被浏览器与 node 同时 import（顶层不访问 DOM）。自测 = `selfTest()`。
//
// ── 为什么是「方言登记表」而不是「一套键集」（作者 2026-09-22 令）──
//   「因为 server 也不可能一直不动，不能让以后 server 换个字段名就又不行」
//   「我需要 server 和这边的兼容，你改这边，让两边都兼容」
//   上一批把**六键** `{fmt,w,h,bytes,sha256,b64}` 静态钉成唯一形态，对端换成**五键**
//   `{mime,w,h,size,b64}` 后全链失效。本件因此把**每个已知方言**写成一条**数据**
//   （`PREVIEW_DIALECTS`）：
//     · **读面**：按登记表逐条试、**按键集识别**（零版本协商、不需要谁知道谁说哪版）；
//     · **写面**：写哪一套键名由**方言 id 参数**决定（默认 = `DEFAULT_SEND_DIALECT_ID`）；
//     · 本件内**不存在**独立于方言的「键集常量」当收发唯一判据 —— 键名/键序一律由
//       `dialectKeyOrder(dialect)` 算出（`PREVIEW_DIALECTS` 是唯一事实源）。
//
// ── 归一化内部表示（与方言无关的唯一形状）──
//   `readPreviewField` / `declarePreviewOf` 都产出/接受同一个归一化对象：
//     `{ mime, w, h, size, sha256|null, b64, dialectId }`
//   · `mime` = **标准内部拼写**（full MIME `image/webp` / `image/jpeg`）—— 与线面方言无关；
//   · `size` = **解码后**字节数；`sha256` = `null` 或 64 位小写 hex（有的方言不带摘要步）；
//   · `dialectId` = 这个对象说哪一版（读面 = 被识别到的那版；写面 = 申报的那版）。
//   ⇒ 「收到哪套拼写就还哪套」（收-发往返恒等）；跨方言的差异**只在两张映射表里**，
//     判定/上限/长度自洽全部共用同一份实现。
//
// 三条兼容纪律（照旧，逐条不变）：
//   ① **旧端载荷逐字节不变**：无该键 ⇒ `readPreviewField` 恒回 `null`，调用方走改前那条路；
//   ② **未知/多余字段忽略不炸**：解析只读本方言声明的子键，多余键既不复制进归一化结果、也不报错；
//   ③ **预览与原件字段互不污染**：`writePreviewField` 只动 `preview` 一个键。

/** 预览字段的**键名**（真源 = `AttachmentPreviewContract.PreviewKey`）。
 *  🔴 两版方言**共用同一个外层键名**（换的是内层子键名，不是这一层）。 */
export const PREVIEW_FIELD_KEY = 'preview';

/**
 * **方言登记表**（收面的唯一判据面；也是写面键名/键序的唯一事实源）。
 *
 * @typedef {{id:string, note:string, mimeKey:string, bytesKey:string, digestKey:(string|null),
 *            formats:readonly string[], toMime:Record<string,string>, fromMime:Record<string,string>}} PreviewDialect
 * @type {readonly PreviewDialect[]}
 */
export const PREVIEW_DIALECTS = Object.freeze([
  // 方言 A = 五键：neblink-server 现读 main（41e12af / 对齐批 6b2af54）；full MIME、无摘要步。
  Object.freeze({
    id: 'mime5',
    note: 'five keys {mime,w,h,size,b64}; full MIME spellings; no digest step (neblink-server main 41e12af)',
    mimeKey: 'mime',
    bytesKey: 'size',
    digestKey: null,
    formats: Object.freeze(['image/webp', 'image/jpeg']),
    toMime: Object.freeze({ 'image/webp': 'image/webp', 'image/jpeg': 'image/jpeg' }),
    fromMime: Object.freeze({ 'image/webp': 'image/webp', 'image/jpeg': 'image/jpeg' }),
  }),
  // 方言 B = 六键：已退役的 neblink-server 4399d7a4c / 本仓 xferpreview-impl2 支；裸格式名、带摘要步。
  //   🔴 作废的只是「把它当**唯一**形态」，不是「不认它」—— 对端可能说这一版，收面必须认。
  Object.freeze({
    id: 'fmt6',
    note: 'six keys {fmt,w,h,bytes,sha256,b64}; bare format names; digest step (neblink-server 4399d7a4c / xferpreview-impl2)',
    mimeKey: 'fmt',
    bytesKey: 'bytes',
    digestKey: 'sha256',
    formats: Object.freeze(['webp', 'jpeg']),
    toMime: Object.freeze({ webp: 'image/webp', jpeg: 'image/jpeg' }),
    fromMime: Object.freeze({ 'image/webp': 'webp', 'image/jpeg': 'jpeg' }),
  }),
]);

/**
 * **默认写面方言 id** —— 一个**可切换的政策旋钮**，**不是**判据：读面不读它（读面只按登记表
 * 逐条试）；写面每次由调用方给的方言 id 决定，本值只在「调用方没给」时生效。
 * 取值理由：等于现读对端 main 说的那一版 ⇒ 默认配置下新载荷可直接被现读服务端接受；
 * 对端再换代 ⇒ 改这一行（或在调用点逐次指定），读写代码零改动。
 */
export const DEFAULT_SEND_DIALECT_ID = 'mime5';

/** 按 id 取方言（**写面的取用点**；未登记 ⇒ `null`）。 @returns {PreviewDialect|null} */
export function previewDialectById(id) {
  return PREVIEW_DIALECTS.find((d) => d.id === id) || null;
}

/** 默认写面方言结构（由 id 取回，单一命名面）。 @returns {PreviewDialect} */
export function defaultPreviewDialect() {
  return previewDialectById(DEFAULT_SEND_DIALECT_ID) || PREVIEW_DIALECTS[0];
}

/** 全部已登记方言 id（读数/错误文案用）。 @returns {string[]} */
export function previewDialectIds() {
  return PREVIEW_DIALECTS.map((d) => d.id);
}

/** 某方言的 wire 键序（= **算出**的，不是抄来的一份常量）。 @returns {string[]} */
export function dialectKeyOrder(dialect) {
  return [dialect.mimeKey, 'w', 'h', dialect.bytesKey, ...(dialect.digestKey ? [dialect.digestKey] : []), 'b64'];
}

/** 归一化对象的键集（与方言无关的内部形状）。 @returns {string[]} */
export function normalizedFieldKeys() {
  return ['mime', 'w', 'h', 'size', 'sha256', 'b64', 'dialectId'];
}

/** 允许的**标准内部** mime（两版方言的并集取值域；一个事实一处拼写）。 */
export const PREVIEW_FIELD_MIMES = Object.freeze(['image/webp', 'image/jpeg']);

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

/** 标准内部 mime 是否在取值域内。 @returns {boolean} */
export function mimeAllowed(mime) {
  return PREVIEW_FIELD_MIMES.includes(mime);
}

/** 某方言是否会说这个**标准内部** mime。 @returns {boolean} */
export function dialectSpeaks(dialect, mime) {
  return !!dialect && Object.prototype.hasOwnProperty.call(dialect.fromMime, mime);
}

/**
 * 按**一条指定方言**读预览字段 —— 内部解析原语（与方言无关的判定全在这里，只读本方言声明的键）。
 *
 * 判据（任一不成立 ⇒ `null`）：
 *   ① 入参是普通对象；
 *   ② 本方言的 mime 子键在场、取值在**本方言的取值域**内；
 *   ③ `w` / `h` 为正整数且长边 ≤ [[PREVIEW_FIELD_MAX_DIM]]；
 *   ④ 本方言的字节数子键为不超过 [[PREVIEW_FIELD_MAX_BYTES]] 的正整数；
 *   ⑤ `b64` 非空、长度 == `4 × ceil(size / 3)` 且 ≤ [[PREVIEW_FIELD_MAX_B64_LEN]]；
 *   ⑥ 本方言带摘要键 ⇒ 该键在场且 [[isSha256Hex]]（缺摘要 = 不是本方言的形状）。
 * 多余键**不读不报**；任何异常路径都不抛。
 * @param {any} raw @param {PreviewDialect} dialect
 * @returns {{mime:string,w:number,h:number,size:number,sha256:string|null,b64:string,dialectId:string}|null}
 */
export function readInDialect(raw, dialect) {
  if (!isPlainObject(raw) || !dialect) return null;
  const spelling = typeof raw[dialect.mimeKey] === 'string' ? raw[dialect.mimeKey] : '';
  const mime = Object.prototype.hasOwnProperty.call(dialect.toMime, spelling) ? dialect.toMime[spelling] : '';
  if (!mime || !mimeAllowed(mime)) return null;
  if (!isPositiveInt(raw.w) || !isPositiveInt(raw.h)) return null;
  if (Math.max(raw.w, raw.h) > PREVIEW_FIELD_MAX_DIM) return null;
  const size = raw[dialect.bytesKey];
  if (!isPositiveInt(size) || size > PREVIEW_FIELD_MAX_BYTES) return null;
  const b64 = typeof raw.b64 === 'string' ? raw.b64 : '';
  if (b64.length === 0 || b64.length > PREVIEW_FIELD_MAX_B64_LEN) return null;
  if (b64.length !== base64LengthOf(size)) return null;
  let sha256 = null;
  if (dialect.digestKey) {
    const d = raw[dialect.digestKey];
    if (!isSha256Hex(d)) return null;
    sha256 = d;
  }
  return { mime, w: raw.w, h: raw.h, size, sha256, b64, dialectId: dialect.id };
}

/**
 * 读预览字段 —— **唯一解析入口**（收面）：按 [[PREVIEW_DIALECTS]] 登记表**逐条试**，
 * 命中即回（结果**自带方言 id** ⇒ 回写按原方言）。
 * 全部方言都不认（含「第三种未知方言」）⇒ `null`（调用方走「无预览」= 改前那条路），**零异常**。
 *
 * 平局规则（有意、写明）：两版方言的**区分键互斥**（A 靠 `mime`+`size`、B 靠 `fmt`+`bytes`+`sha256`），
 * 唯一可能同时像两版的是**人为构造的混合体** ⇒ 那种载荷按**本表声明序**取先者 —— 判据确定、可复算。
 *
 * @param {any} attachment
 * @returns {{mime:string,w:number,h:number,size:number,sha256:string|null,b64:string,dialectId:string}|null}
 */
export function readPreviewField(attachment) {
  if (!isPlainObject(attachment)) return null;
  const raw = attachment[PREVIEW_FIELD_KEY];
  if (!isPlainObject(raw)) return null;
  for (const d of PREVIEW_DIALECTS) {
    const hit = readInDialect(raw, d);
    if (hit) return hit;
  }
  return null;
}

/** 这个**线面对象**（申报体本身）能被哪一版方言识别（读数/对账用；不认识 ⇒ `null`）。 */
export function identifyDialectOf(rawPreviewObject) {
  if (!isPlainObject(rawPreviewObject)) return null;
  for (const d of PREVIEW_DIALECTS) {
    if (readInDialect(rawPreviewObject, d)) return d;
  }
  return null;
}

/** 这件附件**有没有可用的预览**（= [[readPreviewField]] 非空）。 @returns {boolean} */
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

/** 归一化对象是否整体自洽（写面前自检；不自洽 ⇒ 宁可不发也不发半截）。 @returns {boolean} */
export function normalizedFieldOk(field) {
  return !!field && mimeAllowed(field.mime) && isPositiveInt(field.w) && isPositiveInt(field.h)
    && Math.max(field.w, field.h) <= PREVIEW_FIELD_MAX_DIM
    && isPositiveInt(field.size) && field.size <= PREVIEW_FIELD_MAX_BYTES
    && typeof field.b64 === 'string' && field.b64.length === base64LengthOf(field.size)
    && (field.sha256 === null || field.sha256 === undefined || isSha256Hex(field.sha256));
}

/**
 * 把**生成器出口**（`makePreviewFrame` 的结果或等形对象）转成**归一化字段对象**。
 *
 * 取数纪律：`size` **不重算**（= 生成器给的真值 `blob.size`，两者不等即判形状错、不申报）；
 * `sha256` / `b64` 由生成器同批算出（同一份 blob 字节 ⇒ 三者机械自洽）。
 *
 * @param {{blob?:any,mime?:string,width?:number,height?:number,bytes?:number,sha256?:string,b64?:string}} frame
 * @param {{dialectId?:string}} [opts] 写面方言 id（未给/未登记 ⇒ 默认方言；不影响本函数产出的
 *        归一化形状，只记在 `dialectId` 上 —— **线面拼写在 [[wireFieldOf]] 才发生**）
 * @returns {{mime:string,w:number,h:number,size:number,sha256:string|null,b64:string,dialectId:string}|null}
 *   `null` ⇒ **不申报预览**（生成器 `ok:false`、形状不全、mime 越域、体积越界、编码缺失）
 *   —— 契约字段可选 ⇒ 调用方原样走改前路径。
 */
export function declarePreviewOf(frame, opts) {
  if (!isPlainObject(frame) || !frame.blob) return null;
  const blob = frame.blob;
  const mime = typeof frame.mime === 'string' ? frame.mime : (blob.type || '');
  if (!mimeAllowed(mime)) return null;
  const w = frame.width;
  const h = frame.height;
  if (!isPositiveInt(w) || !isPositiveInt(h) || Math.max(w, h) > PREVIEW_FIELD_MAX_DIM) return null;
  const size = typeof blob.size === 'number' ? blob.size : NaN;
  if (!isPositiveInt(size) || size > PREVIEW_FIELD_MAX_BYTES) return null;
  if (typeof frame.bytes === 'number' && frame.bytes !== size) return null; // 生成器读数与 blob 不一致 ⇒ 不申报
  const sha256 = isSha256Hex(frame.sha256) ? frame.sha256 : null;
  const b64 = typeof frame.b64 === 'string' ? frame.b64 : '';
  if (b64.length === 0 || b64.length > PREVIEW_FIELD_MAX_B64_LEN) return null;
  if (b64.length !== base64LengthOf(size)) return null;
  const dialectId = (opts && previewDialectById(opts.dialectId)) ? opts.dialectId : DEFAULT_SEND_DIALECT_ID;
  const field = { mime, w, h, size, sha256, b64, dialectId };
  return normalizedFieldOk(field) ? field : null;
}

/** 旧名（同族先例沿革）：`declarePreviewOf` 的**别名**，同一实现、零第二份。 @type {typeof declarePreviewOf} */
export const buildPreviewField = declarePreviewOf;

/**
 * 归一化字段 → **某方言的线面对象**（键序 = [[dialectKeyOrder]]）—— 🔴 写面的**唯一**拼写点。
 *
 * 拒绝面（回 `null`，**绝不发半截字段**）：
 *   · 方言 id 未登记（**不回落**：静默换方言正是「换字段名就又不行」的根因）；
 *   · 该方言不会说这个 mime（`{fmt:'image/webp'}` 这类混拼永远不会产出）；
 *   · 该方言带摘要键而本字段没有合法摘要（`fmt6` 要 `sha256`，缺 ⇒ 拒绝）；
 *   · 字段本身不自洽（[[normalizedFieldOk]]）。
 * @param {any} field @param {string} dialectId @returns {any|null}
 */
export function wireFieldOf(field, dialectId) {
  const dialect = previewDialectById(dialectId);
  if (!dialect || !normalizedFieldOk(field)) return null;
  if (!dialectSpeaks(dialect, field.mime)) return null;
  const out = {};
  out[dialect.mimeKey] = dialect.fromMime[field.mime];
  out.w = field.w;
  out.h = field.h;
  out[dialect.bytesKey] = field.size;
  if (dialect.digestKey) {
    if (!isSha256Hex(field.sha256)) return null;
    out[dialect.digestKey] = field.sha256;
  }
  out.b64 = field.b64;
  return out;
}

/** 按**该字段自己说的方言**拼线面对象（「收到哪套拼写就还哪套」的单点出口）。 @returns {any|null} */
export function wireFieldOfOwnDialect(field) {
  return wireFieldOf(field, (field && field.dialectId) || DEFAULT_SEND_DIALECT_ID);
}

/**
 * 预览字段 → `data:` URL —— 🔴 **全仓唯一**的 `data:` 合成点。
 * MIME 子类型取自**归一化** mime（与方言无关：两版都说同一个格式，只是拼法不同）。
 * @param {any} attachment @returns {string} 无预览 ⇒ `''`
 */
export function previewDataUrlOf(attachment) {
  const field = readPreviewField(attachment);
  if (!field) return '';
  return `data:${field.mime};base64,${field.b64}`;
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
 * 三类必测（作者裁定）：**缺字段 / 未知字段 / 空值**；外加**方言双兼容**（两版都读得进、
 * 各按自己的拼写回写、第三种方言回落）、密钥集、互不污染、构造-解析往返。
 * @returns {Promise<{passed:number,failed:number,total:number,green:boolean,cases:Array<{name:string,ok:boolean,detail:string}>}>}
 */
export async function selfTest() {
  const cases = [];
  const check = (name, ok, detail) => { cases.push({ name, ok: !!ok, detail: String(detail) }); };

  // ── 1. 缺字段：旧消息 ⇒ 现行为不变 ──
  // 夹具键序 = wire 键序（id,name,size,mime,sha256,state = `ATTACHMENT_LEGACY_KEYS` 声明序）
  const legacy = { id: 'a1', name: 'p.jpg', size: 900000, mime: 'image/jpeg', sha256: 'ab', state: 'ready' };
  check('missing-key/read-null', readPreviewField(legacy) === null, 'readPreviewField(legacy) should be null');
  check('missing-key/has-false', hasPreviewField(legacy) === false, 'hasPreviewField(legacy) should be false');
  check('missing-key/blob-null', previewBlobOf(legacy) === null, 'previewBlobOf(legacy) should be null');
  check('missing-key/data-url-empty', previewDataUrlOf(legacy) === '', 'previewDataUrlOf(legacy) should be empty');
  check(
    'missing-key/legacy-shape-byte-equal',
    JSON.stringify(legacyShapeOf(legacy)) === JSON.stringify(legacy),
    'legacyShapeOf must reproduce the legacy key order/values byte-for-byte'
  );

  // ── 2. 方言登记表：两版都登记、键序算出、无独立键集常量 ──
  const mime5 = previewDialectById('mime5');
  const fmt6 = previewDialectById('fmt6');
  check('dialects/both-registered', !!mime5 && !!fmt6, `ids=${JSON.stringify(previewDialectIds())}`);
  check(
    'dialects/mime5-key-order',
    JSON.stringify(dialectKeyOrder(mime5)) === JSON.stringify(['mime', 'w', 'h', 'size', 'b64']),
    `mime5 order=${JSON.stringify(dialectKeyOrder(mime5))}`
  );
  check(
    'dialects/fmt6-key-order',
    JSON.stringify(dialectKeyOrder(fmt6)) === JSON.stringify(['fmt', 'w', 'h', 'bytes', 'sha256', 'b64']),
    `fmt6 order=${JSON.stringify(dialectKeyOrder(fmt6))}`
  );
  check('dialects/unknown-id-null', previewDialectById('never-heard-of-it') === null, 'an unregistered dialect id must read null');

  // ── 3. 两版方言的**载荷**都能被读进来（收面双形态），且各带自己的 dialectId ──
  const bytes = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  const b64 = bytesToBase64(bytes);
  const sha = await sha256HexOfBytes(bytes);
  check('sha256/hex-shape', isSha256Hex(sha), `sha256 must be 64 lowercase hex, got ${sha}`);
  const wireA = { mime: 'image/webp', w: 4, h: 3, size: bytes.length, b64 };
  const wireB = { fmt: 'webp', w: 4, h: 3, bytes: bytes.length, sha256: sha, b64 };
  const readA = readInDialect(wireA, mime5);
  const readB = readInDialect(wireB, fmt6);
  check('dialect-A/read', !!readA && readA.mime === 'image/webp' && readA.size === bytes.length && readA.sha256 === null && readA.dialectId === 'mime5',
    `readA=${JSON.stringify(readA && { ...readA, b64: readA.b64.slice(0, 8) })}`);
  check('dialect-B/read', !!readB && readB.mime === 'image/webp' && readB.size === bytes.length && readB.sha256 === sha && readB.dialectId === 'fmt6',
    `readB=${JSON.stringify(readB && { ...readB, b64: readB.b64.slice(0, 8) })}`);
  // 交错：A 的主体喂 B 的读法 / B 的主体喂 A 的读法 ⇒ 必须不认识（证明判据**按键集**分派）
  check('dialect-A/not-readable-as-B', readInDialect(wireA, fmt6) === null, 'the five-key object must not satisfy the six-key dialect');
  check('dialect-B/not-readable-as-A', readInDialect(wireB, mime5) === null, 'the six-key object must not satisfy the five-key dialect');
  // 外层入口（真收面路径）：同一个附件对象，两版载荷都读得进
  check('attachment/reads-A', !!readPreviewField({ ...legacy, preview: wireA }), 'four+attachment with five-key preview must parse');
  check('attachment/reads-B', !!readPreviewField({ ...legacy, preview: wireB }), 'four+attachment with six-key preview must parse');
  check(
    'identify/A-and-B',
    identifyDialectOf(wireA)?.id === 'mime5' && identifyDialectOf(wireB)?.id === 'fmt6',
    `A=${identifyDialectOf(wireA)?.id} B=${identifyDialectOf(wireB)?.id}`
  );

  // ── 4. 未知 / 第三种方言 ⇒ 回落无预览（禁崩、禁静默改成别的方言）──
  const thirdParty = { format: 'image/webp', width: 4, height: 3, length: bytes.length, data: b64 };
  check('unknown-dialect/read-null', readPreviewField({ ...legacy, preview: thirdParty }) === null, 'an unregistered third dialect must fall back to no-preview');
  check('unknown-dialect/identify-null', identifyDialectOf(thirdParty) === null, 'an unregistered third dialect must not be identified');
  check('unknown-dialect/no-throw', readPreviewField({ preview: { fmt: 5, w: 'x', h: null, bytes: {}, b64: [] } }) === null, 'garbage shapes must read null, not throw');
  check('unknown-dialect/wire-refused', wireFieldOf({ mime: 'image/webp', w: 4, h: 3, size: 10, sha256: sha, b64, dialectId: 'mime5' }, 'never-heard-of-it') === null, 'an unregistered dialect id must be refused on the write side (never silently substituted)');

  // ── 5. 写面：方言由参数决定，键名随参数变（无写死的单一键集）──
  const fieldA = declarePreviewOf({ blob: new Blob([bytes], { type: 'image/webp' }), mime: 'image/webp', width: 4, height: 3, bytes: bytes.length, sha256: sha, b64 }, { dialectId: 'mime5' });
  const fieldB = declarePreviewOf({ blob: new Blob([bytes], { type: 'image/webp' }), mime: 'image/webp', width: 4, height: 3, bytes: bytes.length, sha256: sha, b64 }, { dialectId: 'fmt6' });
  const outA = wireFieldOf(fieldA, 'mime5');
  const outB = wireFieldOf(fieldB, 'fmt6');
  check('write/A-keys', !!outA && JSON.stringify(Object.keys(outA)) === JSON.stringify(['mime', 'w', 'h', 'size', 'b64']), `A keys=${outA && JSON.stringify(Object.keys(outA))}`);
  check('write/B-keys', !!outB && JSON.stringify(Object.keys(outB)) === JSON.stringify(['fmt', 'w', 'h', 'bytes', 'sha256', 'b64']), `B keys=${outB && JSON.stringify(Object.keys(outB))}`);
  check('write/A-no-digest-key', !!outA && !('sha256' in outA) && !('bytes' in outA) && !('fmt' in outA), `A=${outA && JSON.stringify(Object.keys(outA))}`);
  check('write/B-carries-digest', !!outB && outB.sha256 === sha, `B.sha256=${outB && outB.sha256}`);
  // 归一化字段与方言**无关**：两个声明只差 `dialectId`（那是「我说哪一版」的记号，不是值）
  const stripDialect = (f) => { const o = { ...f }; delete o.dialectId; return JSON.stringify(o); };
  check('write/same-field-two-spellings', stripDialect(fieldA) === stripDialect(fieldB), `A=${stripDialect(fieldA)} B=${stripDialect(fieldB)}`);
  // 交叉：把 mime5 的字段按 fmt6 拼 ⇒ 数字不变、键名换（证明「换字段名」只换拼写）
  check('write/spelling-swap-same-values', outA && outB && outA.size === outB.bytes && outA.mimeKey === undefined && outB.fmt === 'webp', `A.size=${outA && outA.size} B.bytes=${outB && outB.bytes}`);
  // 6 键方言缺摘要 ⇒ 拒（不发半截）
  check('write/B-missing-digest-refused', wireFieldOf({ ...fieldB, sha256: null }, 'fmt6') === null, 'a digest-bearing dialect must refuse a field without a digest');
  // 方言不会说的 mime ⇒ 拒
  check('write/unknown-mime-refused', wireFieldOf({ ...fieldA, mime: 'image/gif' }, 'mime5') === null, 'an out-of-domain mime must be refused');

  // ── 6. 往返：写哪一版就读回哪一版（收-发恒等，两版各一次）──
  //    `declarePreviewOf` 记的 `dialectId` 与「读回来时识别到的」必然一致 —— 但**读面**认的是
  //    线上键集（A 的主体里没有摘要键 ⇒ 读回来 `sha256` 为 `null`），故比较时只把
  //    「线面能承载的值」对齐：mime / 几何 / 字节数 / b64 必须逐字段相等，且识别到的方言
  //    等于声明时指定的那一版。
  const wireRoundTrip = (field, out, id) => {
    const back = readPreviewField({ preview: out });
    return !!back && back.mime === field.mime && back.w === field.w && back.h === field.h
      && back.size === field.size && back.b64 === field.b64 && back.dialectId === id;
  };
  check('roundtrip/A', wireRoundTrip(fieldA, outA, 'mime5'), `A out=${JSON.stringify(Object.keys(outA || {}))} back=${JSON.stringify(readPreviewField({ preview: outA }))}`);
  check('roundtrip/B', wireRoundTrip(fieldB, outB, 'fmt6') && readPreviewField({ preview: outB }).sha256 === sha, `B out=${JSON.stringify(Object.keys(outB || {}))} back=${JSON.stringify(readPreviewField({ preview: outB }))}`);
  check('roundtrip/own-dialect-writer', JSON.stringify(wireFieldOfOwnDialect(fieldB)) === JSON.stringify(outB), 'wireFieldOfOwnDialect must reproduce the six-key spelling for a six-key field');

  // ── 7. 多余键 / 互不污染 / `data:` 单点 ──
  const withUnknown = { ...legacy, futureKey: { keep: true }, preview: { ...wireA, extra: 'ignore-me', future: 42 } };
  const readUnknown = readPreviewField(withUnknown);
  check('unknown-keys/parsed', readUnknown !== null, 'a preview with unknown sub-keys must still parse');
  check(
    'unknown-keys/not-copied',
    JSON.stringify(Object.keys(readUnknown || {}).sort()) === JSON.stringify([...normalizedFieldKeys()].sort()),
    'the normalized field must carry exactly the internal keys'
  );
  const written = writePreviewField(withUnknown, readUnknown);
  check(
    'unknown-keys/attachment-untouched',
    JSON.stringify(written.futureKey) === JSON.stringify(withUnknown.futureKey),
    'unknown attachment keys must survive writePreviewField untouched'
  );
  const dataUrl = previewDataUrlOf({ preview: outB });
  check('data-url/prefix-shape', dataUrl === `data:image/webp;base64,${b64}`, `unexpected data url: ${dataUrl.slice(0, 40)}`);
  check('data-url/same-for-both-dialects', previewDataUrlOf({ preview: outA }) === previewDataUrlOf({ preview: outB }), 'both dialects describe one format, so the synthesized data URL must be identical');
  check('data-url/no-raw-b64-in-wire-field', String(outB.b64).indexOf('data:') < 0, 'the wire field must carry a pure base64 string');

  // ── 8. 空值 / 越界：两版方言**逐条**同判 ──
  const bad = (d, over) => {
    const base = d.digestKey ? { ...wireB } : { ...wireA };
    for (const [k, v] of Object.entries(over)) base[k] = v;
    return readPreviewField({ ...legacy, preview: base });
  };
  const emptyCases = [
    ['preview-null', { ...legacy, preview: null }],
    ['preview-string', { ...legacy, preview: 'webp' }],
    ['preview-array', { ...legacy, preview: [] }],
    ['preview-empty-object', { ...legacy, preview: {} }],
  ];
  for (const [label, att] of emptyCases) {
    check(`empty-value/${label}`, readPreviewField(att) === null, `${label} must fall back to the no-preview branch`);
  }
  const crossed = [
    ['A/mime-out-of-set', bad(mime5, { mime: 'image/png' })],
    ['A/mime-bare-word', bad(mime5, { mime: 'webp' })],
    ['A/dim-over-max', bad(mime5, { w: PREVIEW_FIELD_MAX_DIM + 1 })],
    ['A/size-zero', bad(mime5, { size: 0, b64: '' })],
    ['A/size-over-hard-cap', bad(mime5, { size: PREVIEW_FIELD_MAX_BYTES + 1 })],
    ['A/b64-length-mismatch', bad(mime5, { b64: b64.slice(0, 8) })],
    ['A/b64-over-max-len', bad(mime5, { b64: 'A'.repeat(PREVIEW_FIELD_MAX_B64_LEN + 1) })],
    ['B/mime-out-of-set', bad(fmt6, { fmt: 'jpg' })],
    ['B/mime-full-spelling', bad(fmt6, { fmt: 'image/webp' })],
    ['B/sha256-short', bad(fmt6, { sha256: sha.slice(0, 63) })],
    ['B/sha256-uppercase', bad(fmt6, { sha256: sha.toUpperCase() })],
    ['B/sha256-missing', (() => { const o = { ...wireB }; delete o.sha256; return readPreviewField({ ...legacy, preview: o }); })()],
    ['B/size-zero', bad(fmt6, { bytes: 0, b64: '' })],
    ['B/size-over-hard-cap', bad(fmt6, { bytes: PREVIEW_FIELD_MAX_BYTES + 1 })],
    ['B/dim-over-max', bad(fmt6, { w: PREVIEW_FIELD_MAX_DIM + 1 })],
    ['B/b64-length-mismatch', bad(fmt6, { b64: b64.slice(0, 8) })],
  ];
  for (const [label, res] of crossed) {
    check(`out-of-domain/${label}`, res === null, `${label} must fall back to the no-preview branch`);
  }

  // ── 9. 构造 → 解析往返 + 互不污染 + blob 重建 ──
  const attached = writePreviewField(legacy, fieldA);
  check('no-pollution/legacy-keys', JSON.stringify(legacyShapeOf(attached)) === JSON.stringify(legacy), 'adding preview must not alter any legacy key');
  check('no-pollution/strip-restores', JSON.stringify(stripPreviewField(attached)) === JSON.stringify(legacy), 'stripPreviewField must restore the legacy shape byte-for-byte');
  check('no-pollution/has-false-after-strip', hasPreviewField(stripPreviewField(attached)) === false, 'stripped attachment must report no preview');
  check('construct/bytes-mismatch-refused', declarePreviewOf({ blob: new Blob([bytes], { type: 'image/webp' }), mime: 'image/webp', width: 4, height: 3, bytes: bytes.length + 1, sha256: sha, b64 }) === null, 'a frame whose bytes reading disagrees with blob.size must be refused');
  check('construct/mime-out-of-set', declarePreviewOf({ blob: new Blob([bytes], { type: 'image/gif' }), mime: 'image/gif', width: 4, height: 3 }) === null, 'out-of-set mime must be refused');
  check('construct/null-frame', declarePreviewOf(null) === null, 'declarePreviewOf(null) must be null');
  check('construct/alias-same-impl', buildPreviewField === declarePreviewOf, 'buildPreviewField must be the same single implementation (no second copy)');
  const decoded = previewBlobOf(attached);
  check('blob-of/decoded', !!decoded && decoded.size === bytes.length && decoded.type === 'image/webp', 'previewBlobOf must rebuild the exact byte count');
  check('blob-of/truncated-refused', previewBlobOf({ preview: { ...wireA, b64: b64.slice(0, 4) } }) === null, 'a truncated payload must be refused (length re-check)');

  // ── 10. 跨块边界往返（>1 块 ⇒ 逼出「分块 btoa 的块长必须是 3 的倍数」这条纪律）──
  const big = new Uint8Array(70000);
  for (let i = 0; i < big.length; i++) big[i] = (i * 31) % 256;
  const bigB64 = bytesToBase64(big);
  check('base64/multi-chunk-length', bigB64.length === base64LengthOf(big.length), `${bigB64.length} vs expected ${base64LengthOf(big.length)}`);
  check('base64/multi-chunk-no-interior-padding', !bigB64.slice(0, bigB64.length - 2).includes('='), 'interior "=" means chunks are not 3-byte aligned');
  const backBytes = base64ToBytes(bigB64);
  let same = backBytes.length === big.length;
  if (same) for (let i = 0; i < big.length; i++) if (backBytes[i] !== big[i]) { same = false; break; }
  check('base64/multi-chunk-roundtrip', same, `decoded ${backBytes.length} bytes, expected ${big.length}`);
  const bigField = declarePreviewOf({ blob: new Blob([big], { type: 'image/webp' }), mime: 'image/webp', width: 640, height: 480, bytes: big.length, sha256: await sha256HexOfBytes(big), b64: bigB64 }, { dialectId: 'fmt6' });
  check('base64/multi-chunk-field-shape', !!bigField && bigField.size === 70000 && bigField.dialectId === 'fmt6', 'a >32 KiB payload must survive declarePreviewOf');
  const bigBack = previewBlobOf({ preview: wireFieldOf(bigField, 'fmt6') });
  check('base64/multi-chunk-blob-roundtrip', !!bigBack && bigBack.size === 70000, 'previewBlobOf must return the full byte count (no truncation)');
  const bigBackA = previewBlobOf({ preview: wireFieldOf(bigField, 'mime5') });
  check('base64/multi-chunk-blob-roundtrip-A', !!bigBackA && bigBackA.size === 70000, 'the five-key spelling must round-trip the same bytes');

  const passed = cases.filter((c) => c.ok).length;
  return { passed, failed: cases.length - passed, total: cases.length, green: passed === cases.length, cases };
}
