// pinyin.js — 中文拼音匹配（拼音匹配批, 2026-11-05 作者令「中文拼音优化」）.
//
// 用途单一：给候选补全（`@` 项目 / `#` 待办）提供**拼音命中**这一档排序信号，
// 让「输入 dian ⇒ 命中 电离能损」「输入 ddns/dlns/dl n ⇒ 命中电离能损」成立。
//
// ── 数据面 ────────────────────────────────────────────────────────────────
// 表在 pinyinTable.js（6763 个 GB2312 汉字，400 音节，约 8.9 KB 原文；内联随包
// 加载，**零网络、零第三方运行时依赖**）。本文件只做逻辑：建反查表 + 匹配打分。
//
// ── 匹配三档（严格单向，低档不得压过高档 —— 见 PI_RANK 不变式）────────────
//   full     ：整名全拼包含查询      「dianlinengsun」/「dian」/「lineng」
//   initial  ：整名拼音首字母包含查询 「dlns」/「dl n」（空格、-、_ 先剥掉）
//   非汉字字符（英文/数字/符号）原样进流：混排名「SiPM康普顿成像」的全拼
//   = 「sipmkangpudunchengxiang」、首字母 = 「skpdcx」，两条都照常命中。
//
// ── 口径（如实申报，不许写成"完美支持"）──────────────────────────────────
//   · 多音字：pypinyin 0.55.0 的**默认单字读音**（最高频读音）——长→chang、
//     重→zhong、行→xing、了→le、都→dou。取次常用读音的名字（如「长安」按
//     chang'an 读）在这些字上**没有拼音命中**；它绝不会产生错命中，汉字字面
//     匹配也完全不受影响。已知错例见批报告。
//   · ü 写作 v（绿→lv、女→nv）：查询里输入 ü/Ü 会先折成 v。
//   · 表外字符（生僻字、正体扩展区、emoji）不参与拼音流 —— 该位置只是"跳过"，
//     不会造成错命中。

import { PINYIN_TABLE_RAW } from './pinyinTable.js';

/** @typedef {{ char: string, syl: string | null }} PinyinUnit */

/** 字符 → 音节（懒建；表外字符查不到 = null）。 @type {Map<string, string> | null} */
let charToSyl = null;

/** 首次使用时按需解析整表（一次，~9 KB 文本 split + 遍历，毫秒级）。 */
function ensureTable() {
  if (charToSyl) return charToSyl;
  /** @type {Map<string, string>} */
  const map = new Map();
  for (const line of PINYIN_TABLE_RAW.split('\n')) {
    const i = line.indexOf(':');
    if (i <= 0) continue;
    const syl = line.slice(0, i);
    const chars = line.slice(i + 1);
    for (const ch of chars) {
      // 首见为准：同一字在表内只出现一次（生成侧按音节分桶，无重复）。
      if (!map.has(ch)) map.set(ch, syl);
    }
  }
  charToSyl = map;
  return map;
}

/** 查询串归一：小写、ü→v，剥掉空格 / '-' / '_'（"dl n" 与 "dln" 等价）。 */
function normalizeQuery(q) {
  return String(q == null ? '' : q)
    .toLowerCase()
    .replace(/ü/g, 'v')
    .replace(/[\s\-_]+/g, '');
}

/** Latin 字母/数字（归一为小写后的 ASCII 判定）。 */
const ASCII_RUN_RE = /[a-z0-9]/;
/** ASCII 词边界分隔符（空格 / - / _ / 斜杠 / 点）。 */
const WORD_SEP_RE = /[\s\-_/.]/;

/**
 * 把名字拆成有序单元流：汉字 → 音节单元；连续 ASCII 字母/数字 → **逐字母**单元
 * （音节 null，字母本身保留在 char 上）；表外其余字符 → 跳过（不产生单元）。
 *
 * 逐字母是刻意的：全拼流里拉丁字母必须逐位铺开，`slideblocks` 才能被
 * `lideblo` 这类中段查询命中。
 *
 * @param {string} name
 * @returns {PinyinUnit[]}
 */
export function toUnits(name) {
  const map = ensureTable();
  const src = String(name == null ? '' : name).toLowerCase().replace(/ü/g, 'v');
  /** @type {PinyinUnit[]} */
  const units = [];
  for (const ch of src) {
    if (WORD_SEP_RE.test(ch)) continue;
    if (ASCII_RUN_RE.test(ch)) { units.push({ char: ch, syl: null }); continue; }
    const syl = map.get(ch);
    if (syl) units.push({ char: ch, syl });
  }
  return units;
}

/**
 * 整名全拼串（汉字取音节，拉丁字母逐位原样）。
 * @param {string} name
 * @returns {string}
 */
export function fullPinyin(name) {
  let out = '';
  for (const u of toUnits(name)) out += u.syl || u.char;
  return out;
}

/**
 * 整名拼音首字母串：汉字取音节首字母；拉丁/数字按**词**取首字母（词边界 =
 * 分隔符，或驼峰小写→大写处）——「SlideBlocks Studio」⇒ `sbs`、
 * 「SiPM康普顿成像」⇒ `spkpdcx`，与人对"首字母"的直觉一致。
 *
 * @param {string} name
 * @returns {string}
 */
export function initialsPinyin(name) {
  const map = ensureTable();
  const raw = String(name == null ? '' : name);
  let out = '';
  /** 上一个字符是否仍是当前 ASCII 词的内部（小写字母/数字）。 */
  let inAsciiWord = false;
  /** 上一个字符是小写字母/数字（驼峰断词判据：小写→大写处断开）。 */
  let prevLower = false;
  for (const ch of raw) {
    const lower = ch.toLowerCase().replace(/ü/g, 'v');
    if (WORD_SEP_RE.test(ch)) { inAsciiWord = false; prevLower = false; continue; }
    if (ASCII_RUN_RE.test(lower)) {
      // 新词判定：当前不在词内，或大写紧跟小写/数字（驼峰）。全大写缩写
      // （DOSTEL / CZT）整段算**一个词**——它们的全拼流已经覆盖全部子串查询，
      // 首字母不需要再逐字母膨胀。
      const isUpper = ch !== lower;
      const camelBreak = inAsciiWord && prevLower && isUpper;
      if (!inAsciiWord || camelBreak) out += lower[0];
      inAsciiWord = true;
      prevLower = !isUpper;
      continue;
    }
    inAsciiWord = false;
    prevLower = false;
    const syl = map.get(lower);
    if (syl) out += syl[0];
  }
  return out;
}

/**
 * 拼音命中档（无命中 = 0）：2 = 全拼命中，1 = 首字母命中。
 * 两者都是「包含」语义：`dian`（前缀）与 `lineng`（中段）同样算命中 ——
 * 候选排序把前缀与中段分开是**字面档**的职责（见 tokenComplete 的 rank）。
 *
 * @param {string} name 候选名
 * @param {string} query 已归一的查询串（调用方传原始串亦可，本函数自行归一）
 * @returns {0 | 1 | 2}
 */
export function pinyinScore(name, query) {
  const q = normalizeQuery(query);
  if (!q) return 0;
  // 纯 ASCII 之外还有汉字才值得跑拼音（纯英文名的字面匹配已经覆盖）。
  if (fullPinyin(name).includes(q)) return 2;
  if (initialsPinyin(name).includes(q)) return 1;
  return 0;
}

/**
 * 该名字是否**含汉字**（决定要不要额外跑拼音档）。
 * @param {string} name
 * @returns {boolean}
 */
export function hasHan(name) {
  return /[\u3400-\u9fff]/.test(String(name == null ? '' : name));
}
