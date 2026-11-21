#!/usr/bin/env node
// check-theme-token-mirror.mjs — 双档令牌镜像静态闸（2026-11-21 暗色令牌裁定）。
//
// 背景：base.css 的色令牌分四块——亮档 :root（文件头部）、html[data-theme="light"]、
// html[data-theme="dark"]、@media (prefers-color-scheme: dark) 内的 :root。显式档
// （data-theme）与系统档（media）必须逐令牌一致，否则手动切换后的颜色与系统档分叉。
// 改令牌忘同步两处曾是注释约定；本闸把它升级为可执行判据。
//
// 口径（逐条）：
//   M1 html[data-theme="dark"] 的令牌键集 ≡ 媒体暗档 :root 的令牌键集（不多不少），
//      且逐键值字面相等。
//   M2 html[data-theme="light"] 的键集 ⊇ 媒体暗档键集（否则系统暗 + 手动亮时，被媒体块
//      覆盖的键会泄漏暗值）；且对这些键，值 ≡ 亮档 :root 的同名值。
//   M3 html[data-theme="light"] 中凡亮档 :root 也定义的键，值必须相等（禁止分叉）。
//   任一不一致 ⇒ 列出 键 / 期望 / 实际，exit 1。
//
// 解析只覆盖本文件用到的简单形态：`selector { --k: v; … }`；注释先剥离。不做 CSS 全语法
// 解析——形态不符时报错而非静默放过。

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const SPECS = [
  { file: 'src/main/resources/web/css/base.css', anchor: '--color-text', label: 'base.css' },
  { file: 'src/main/resources/web/css/chat.css', anchor: '--ask-card-top', label: 'chat.css（ask 卡面族）' },
];

const errors = [];

// openIdx 处为 '{'，返回其配对 '}' 之间的文本。
function blockBody(text, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < text.length; i++) {
    if (text[i] === '{') depth++;
    else if (text[i] === '}') { depth--; if (depth === 0) return text.slice(openIdx + 1, i); }
  }
  throw new Error('花括号不配对');
}

function parseDecls(body) {
  const map = new Map();
  for (const raw of body.split(';')) {
    const line = raw.trim();
    if (!line) continue;
    const m = line.match(/^(--[\w-]+)\s*:\s*([\s\S]+)$/);
    if (!m) continue;
    map.set(m[1], m[2].trim().replace(/\s+/g, ' '));
  }
  return map;
}

// 所有形如 `<selectorRe> {` 的顶层块体（按出现顺序）。
function blocks(text, selectorRe) {
  const out = [];
  const re = new RegExp(selectorRe, 'g');
  let m;
  while ((m = re.exec(text)) !== null) {
    const open = text.indexOf('{', m.index);
    out.push({ at: m.index, decls: parseDecls(blockBody(text, open)) });
  }
  return out;
}

// 媒体暗档：取第一个「内部 :root 含 anchor」的 prefers-color-scheme: dark 块。
function mediaDarkBlock(text, anchor) {
  const re = /@media\s*\(prefers-color-scheme:\s*dark\)\s*\{/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const body = blockBody(text, text.indexOf('{', m.index));
    const r = body.search(/:root\s*\{/);
    if (r < 0) continue;
    const decls = parseDecls(blockBody(body, body.indexOf('{', r)));
    if (decls.has(anchor)) return { at: m.index, decls };
  }
  return null;
}

const check = (tag, expected, actual, keys) => {
  for (const k of keys) {
    const e = expected.get(k);
    const a = actual.get(k);
    if (e !== a) errors.push(`${tag} ${k}：期望 ${e ?? '(缺失)'} · 实际 ${a ?? '(缺失)'}`);
  }
};

let summary = [];
for (const spec of SPECS) {
  const text = readFileSync(resolve(spec.file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const host = spec.label;
  const pick = (hits, name) => {
    const hit = hits.find((h) => h.decls.has(spec.anchor));
    if (!hit) errors.push(`${host}：找不到含 ${spec.anchor} 的 ${name} 块`);
    return hit?.decls ?? null;
  };
  const lightRoot = pick(blocks(text, String.raw`:root\s*\{`), '亮档 :root');
  const lightMirror = pick(blocks(text, String.raw`html\[data-theme="light"\]\s*\{`), 'html[data-theme="light"]');
  const darkMirror = pick(blocks(text, String.raw`html\[data-theme="dark"\]\s*\{`), 'html[data-theme="dark"]');
  const media = mediaDarkBlock(text, spec.anchor);
  const mediaDark = media?.decls ?? null;
  if (!mediaDark) errors.push(`${host}：找不到含 ${spec.anchor} 的媒体暗档 :root`);
  if (!(lightRoot && lightMirror && darkMirror && mediaDark)) continue;

  const mediaKeys = [...mediaDark.keys()];
  for (const k of mediaKeys) if (!darkMirror.has(k)) errors.push(`${host} M1 dark 缺键 ${k}（媒体暗档定义）`);
  for (const k of darkMirror.keys()) if (!mediaDark.has(k)) errors.push(`${host} M1 dark 多出键 ${k}（媒体暗档未定义）`);
  check(`${host} M1 dark≡media`, mediaDark, darkMirror, mediaKeys.filter((k) => darkMirror.has(k)));
  for (const k of mediaKeys) if (!lightMirror.has(k)) errors.push(`${host} M2 light 缺键 ${k}（系统暗会泄漏此键）`);
  check(`${host} M2 light≡lightRoot`, lightRoot, lightMirror, mediaKeys.filter((k) => lightMirror.has(k) && lightRoot.has(k)));
  check(`${host} M3 light≡lightRoot`, lightRoot, lightMirror, [...lightMirror.keys()].filter((k) => lightRoot.has(k)));
  summary.push(`${host} ${mediaKeys.length} 键`);
}

if (errors.length) {
  console.error(`theme-token-mirror FAIL（${errors.length} 条不一致）：`);
  for (const e of errors) console.error('  - ' + e);
  console.error('改令牌须同步：亮档 :root、html[data-theme="light"]、html[data-theme="dark"]、媒体暗档。');
  process.exit(1);
}
console.log(`theme-token-mirror PASS：${summary.join(' · ')}，逐键一致。`);
