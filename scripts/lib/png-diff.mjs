// png-diff.mjs — 零依赖 PNG 解码 / 编码 / 像素比对（视觉基线用）。
//
// 为什么要自写：依赖白名单锁死在 package.json 的 esbuild / typescript / ws 三件
// （+ 视觉基线批的硬约束「不安装任何新依赖」），pixelmatch / pngjs / odiff 一律
// 不可用。仓内已有先例：scripts/e2e-avatar-flicker.cjs:92-129 自写最小 PNG 解码
// （只够取像素做颜色签名）。本模块是同一路线的完整版：解码全 5 种行滤波、
// 编码（差图落盘用）、以及带容差的像素比对 + 差异框。
//
// 支持面（够用即止，超出直接抛错，绝不静默降级）：
//   · bitDepth = 8，interlace = 0（Chromium/Edge 截图恒为此形态）
//   · colorType 0/2/4/6（灰度/RGB/RGBA/灰度+alpha）；调色板(3)不支持 → 报错

import { deflateSync, inflateSync } from 'node:zlib';

const SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

// ── CRC32（PNG chunk 校验）──
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

/** 解码 PNG → { width, height, data: Uint8Array(RGBA) } */
export function decodePng(buf) {
  if (buf.length < 8 || !buf.subarray(0, 8).equals(SIG)) throw new Error('png-diff: not a PNG (bad signature)');
  let off = 8;
  let ihdr = null;
  const idat = [];
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const body = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      ihdr = {
        width: body.readUInt32BE(0),
        height: body.readUInt32BE(4),
        bitDepth: body[8],
        colorType: body[9],
        interlace: body[12],
      };
    } else if (type === 'IDAT') {
      idat.push(body);
    } else if (type === 'IEND') {
      break;
    }
    off += 12 + len;
  }
  if (!ihdr) throw new Error('png-diff: no IHDR');
  if (ihdr.bitDepth !== 8) throw new Error(`png-diff: unsupported bitDepth ${ihdr.bitDepth} (need 8)`);
  if (ihdr.interlace !== 0) throw new Error('png-diff: interlaced PNG unsupported');
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.colorType];
  if (!channels) throw new Error(`png-diff: unsupported colorType ${ihdr.colorType} (palette not supported)`);

  const raw = inflateSync(Buffer.concat(idat));
  const { width, height } = ihdr;
  const stride = width * channels;
  const out = Buffer.alloc(stride * height);

  // 逐行反滤波（PNG spec 9.2）
  let pos = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[pos++];
    const rowStart = y * stride;
    const prevStart = (y - 1) * stride;
    for (let x = 0; x < stride; x++) {
      const rawByte = raw[pos++];
      const a = x >= channels ? out[rowStart + x - channels] : 0;
      const b = y > 0 ? out[prevStart + x] : 0;
      const c = y > 0 && x >= channels ? out[prevStart + x - channels] : 0;
      let v;
      switch (filter) {
        case 0: v = rawByte; break;
        case 1: v = rawByte + a; break;
        case 2: v = rawByte + b; break;
        case 3: v = rawByte + ((a + b) >> 1); break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          v = rawByte + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
          break;
        }
        default: throw new Error(`png-diff: bad filter type ${filter} at row ${y}`);
      }
      out[rowStart + x] = v & 0xff;
    }
  }

  // 统一转 RGBA
  const rgba = new Uint8Array(width * height * 4);
  for (let i = 0, j = 0; i < out.length; i += channels, j += 4) {
    if (channels === 4) { rgba[j] = out[i]; rgba[j + 1] = out[i + 1]; rgba[j + 2] = out[i + 2]; rgba[j + 3] = out[i + 3]; }
    else if (channels === 3) { rgba[j] = out[i]; rgba[j + 1] = out[i + 1]; rgba[j + 2] = out[i + 2]; rgba[j + 3] = 255; }
    else if (channels === 1) { rgba[j] = rgba[j + 1] = rgba[j + 2] = out[i]; rgba[j + 3] = 255; }
    else { rgba[j] = rgba[j + 1] = rgba[j + 2] = out[i]; rgba[j + 3] = out[i + 1]; }
  }
  return { width, height, data: rgba };
}

/** 编码 RGBA → PNG（colorType 6，filter 0；差图落盘用） */
export function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter none
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const idat = deflateSync(raw, { level: 6 });
  const chunk = (type, body) => {
    const head = Buffer.alloc(8);
    head.writeUInt32BE(body.length, 0);
    head.write(type, 4, 'ascii');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), body])), 0);
    return Buffer.concat([head, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([SIG, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

/**
 * 像素比对。
 * @param {Buffer} baseBuf 基准 PNG
 * @param {Buffer} curBuf  当前 PNG
 * @param {{tolerance?: number}} opts tolerance = 单通道容差（0-255，默认 0 = 逐像素严格相等）
 * @returns {{width:number,height:number,same:boolean,diffPixels:number,totalPixels:number,
 *            ratio:number,bbox:null|{x0:number,y0:number,x1:number,y1:number},diffPng:Buffer|null,reason?:string}}
 */
export function comparePng(baseBuf, curBuf, opts = {}) {
  const tolerance = opts.tolerance ?? 0;
  const a = decodePng(baseBuf);
  const b = decodePng(curBuf);
  if (a.width !== b.width || a.height !== b.height) {
    return {
      width: a.width, height: a.height, same: false, diffPixels: -1,
      totalPixels: a.width * a.height, ratio: 1, bbox: null, diffPng: null,
      reason: `尺寸不一致 base=${a.width}x${a.height} cur=${b.width}x${b.height}`,
    };
  }
  const { width, height } = a;
  const total = width * height;
  const diff = new Uint8Array(width * height * 4);
  let diffPixels = 0;
  let x0 = width, y0 = height, x1 = -1, y1 = -1;
  for (let i = 0; i < total; i++) {
    const p = i * 4;
    const dr = Math.abs(a.data[p] - b.data[p]);
    const dg = Math.abs(a.data[p + 1] - b.data[p + 1]);
    const db = Math.abs(a.data[p + 2] - b.data[p + 2]);
    const da = Math.abs(a.data[p + 3] - b.data[p + 3]);
    const changed = dr > tolerance || dg > tolerance || db > tolerance || da > tolerance;
    if (changed) {
      diffPixels++;
      const x = i % width, y = (i / width) | 0;
      if (x < x0) x0 = x; if (x > x1) x1 = x;
      if (y < y0) y0 = y; if (y > y1) y1 = y;
      // 差异像素：纯红（人眼最好认）；一致像素：基准图灰度化后压到 25%（留形不留色）
      diff[p] = 255; diff[p + 1] = 0; diff[p + 2] = 0; diff[p + 3] = 255;
    } else {
      const g = (a.data[p] * 299 + a.data[p + 1] * 587 + a.data[p + 2] * 114) / 1000;
      const v = 190 + g * 0.1; // 浅底（≈190-215），差图的红色块对比度足够
      diff[p] = v; diff[p + 1] = v; diff[p + 2] = v; diff[p + 3] = 255;
    }
  }
  return {
    width, height,
    same: diffPixels === 0,
    diffPixels,
    totalPixels: total,
    ratio: diffPixels / total,
    bbox: diffPixels ? { x0, y0, x1, y1 } : null,
    diffPng: diffPixels ? encodePng(width, height, diff) : null,
  };
}
