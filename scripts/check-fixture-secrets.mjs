#!/usr/bin/env node
// check-fixture-secrets.mjs — 证据/夹具里的**高熵长令牌字面量**静态哨兵
// （2026-11-04 两小件批 · 件三）。
//
// Why a gate：`tests/fixtures/` 与 `reports-evidence/` 是「贴原始输出」的两片地，
// 真实凭据一旦被粘进夹具或证据日志，就同时进了 git 历史。而此前对凭据的判据只
// 有**键名**一条（见 `nebflow.shared.SecretKeys`：出站回显/日志在运行期掩码），
// 落盘侧没有任何机器判据 —— 抄一条 64 位 hex 进夹具，没有任何门禁会红。
//
// ── 判据（三条同时成立才红）──────────────────────────────────────────────
// ① 键名命中敏感词表（与运行期脱敏同一族：deviceToken / accessToken /
//    refreshToken / token / clientSecret / apiKey / password / authorization，
//    大小写不敏感，下划线 / 短横线 / 点号变体归一后同效）；
// ② 值长度 ≥ 32 字符（短于 32 的令牌形态在**现有夹具**里全是测试占位：
//    `tool-card-leak-on-reload/fixture*.json` 的 token=13、
//    `msg-token-chip` / `provider-test-modal` 的 apiKey/token=15–21 —— 阈值取
//    32 正是为了不误报这些）；
// ③ 高熵：字符集 ≥ 3 类（小写/大写/数字/符号）。
//
// 占位词豁免（example / placeholder / fake / dummy / sample / redacted / xxx /
// changeme / your- / todo / <…> / ${…} / 省略号 / 单一字符重复）——**登记豁免**
// 而不是「猜它是假的」：豁免只对**值里带占位字样**的那些生效，一段纯随机串
// 不受保护。
//
// 🔴 输出纪律：命中行只报 `file:line  key=…  len=…  classes=…`，**绝不回显值**
// （连前缀都不打）——门禁自己不能成为新的泄漏面。
//
// ── 用法 ────────────────────────────────────────────────────────────────
//   node scripts/check-fixture-secrets.mjs                # 门禁（0 绿 / 1 红）
//   node scripts/check-fixture-secrets.mjs --json         # 机器读
//   node scripts/check-fixture-secrets.mjs --roots a,b    # 换扫描面（验红自测用）
// 退出码：0 = 绿；1 = 红（有命中）；2 = 环境错误。

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const ARGS = process.argv.slice(2);
const JSON_OUT = ARGS.includes('--json');
const rootsArg = (() => {
  const i = ARGS.indexOf('--roots');
  if (i >= 0 && ARGS[i + 1]) return ARGS[i + 1].split(',').map((s) => s.trim()).filter(Boolean);
  const kv = ARGS.find((a) => a.startsWith('--roots='));
  return kv ? kv.slice('--roots='.length).split(',').map((s) => s.trim()).filter(Boolean) : null;
})();

const DEFAULT_ROOTS = ['tests/fixtures', 'reports-evidence'];

// 二进制 / 不可扫形态（按扩展名；内容判定之外的粗筛，宁可漏扫不误扫）。
const SKIP_EXT = new Set([
  '.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.ico', '.icns', '.pdf',
  '.zip', '.gz', '.tgz', '.jar', '.class', '.so', '.dylib', '.dll', '.exe',
  '.woff', '.woff2', '.ttf', '.otf', '.mp4', '.webm', '.mov', '.mp3', '.wav',
  '.db', '.sqlite', '.pickle', '.npy', '.wasm',
]);
const MAX_BYTES = 8 * 1024 * 1024; // 单文件上限（证据里的大日志逐行读会拖死门禁）

/**
 * 敏感词根（归一后 contains 判定）。**同一族**由运行期单一真源
 * `nebflow.shared.SecretKeys` 定义；这里只是它在落盘面的镜像（JS 侧无法 import
 * Scala，故列表等价而非共享代码 —— 两侧任一方加词，另一方必须同步，批报告点名）。
 */
const KEY_WORDS = [
  'devicetoken', 'accesstoken', 'refreshtoken', 'idtoken', 'sessiontoken',
  'authtoken', 'token', 'clientsecret', 'apikey', 'password', 'passwd',
  'credential', 'authorization', 'secret',
];

/** 配额类键（`maxTokens` / `budgetTokens`）不是凭据 —— 与运行期同一条豁免。 */
const NON_SECRET = new Set([
  'maxtokens', 'budgettokens', 'mintokens', 'tokencount', 'tokensused',
  'tokenestimate', 'contexttokens', 'prompttokens', 'completiontokens',
  'maxoutputtokens', 'maxinputtokens', 'tokenlimit', 'tokenbudget',
]);

const PLACEHOLDER_WORDS = [
  'example', 'placeholder', 'fake', 'dummy', 'sample', 'redacted', 'xxx',
  'changeme', 'your-', 'yourtoken', 'todo', 'lorem', 'masked', 'none',
];

const normalize = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

function isSensitiveKey(key) {
  const k = normalize(key);
  if (!k || NON_SECRET.has(k)) return false;
  return KEY_WORDS.some((w) => k.includes(w));
}

function charClasses(value) {
  const has = { lower: /[a-z]/, upper: /[A-Z]/, digit: /[0-9]/, symbol: /[^A-Za-z0-9]/ };
  return Object.values(has).reduce((n, re) => n + (re.test(value) ? 1 : 0), 0);
}

function isPlaceholder(value) {
  const v = value.toLowerCase();
  if (PLACEHOLDER_WORDS.some((w) => v.includes(w))) return true;
  if (/[<>${}…]|\.\.\.|[*]/.test(value)) return true; // <redacted> / ${TOKEN} / xxx...
  if (/^(.)\1{7,}$/.test(value)) return true; // aaaaaaaaa
  return false;
}

// 键值对形态 —— **四种赋值语境**，都要求键处在「赋值位」而不是句子中间：
//  A 引号键值（JSON / Python dict repr）：`"deviceToken": "…"` / `'deviceToken': '…'`
//  B 赋值式（env / .env / shell）：`deviceToken=…`（键前必须是分隔符，值不许带空白）
//  C 行首 YAML-ish：行首（可带 `- `）的 `key: value`
//  D CLI 旗标：`--token …` / `--api-key=…`
// 值字符集 = 令牌常见字符（base64 的 +/= 、JWT 的 . 、URL 安全的 -_ ）。
// 为什么要「语境」而不是裸 `key: value`：实测裸形态会把散文吃进来 ——
// `only Bearer file token: ApiFull/WsAuth/OpenAiCompat/BearerFileOnly accept`
// 会被当成 `token = ApiFull/...`（42 字符、3 类字符），是纯误报。
const PATTERNS = [
  // A: quoted key + quoted value
  /["']([A-Za-z0-9_.\-]{1,64})["']\s*[:=]\s*["']([^"'\s]{32,})["']/g,
  // B: assignment (key preceded by a non-identifier char, unquoted or quoted value)
  /(?:^|[^A-Za-z0-9_.\-])([A-Za-z0-9_.\-]{1,64})=["']?([A-Za-z0-9+/=_\-.:~]{32,})["']?/g,
  // C: YAML-ish at line start (optionally a list dash)
  /^[\s\-]*["']?([A-Za-z0-9_.\-]{1,64})["']?\s*:\s+["']?([A-Za-z0-9+/=_\-.:~]{32,})["']?/g,
  // D: CLI flag
  /--([A-Za-z0-9_.\-]{1,64})[= ]+["']?([A-Za-z0-9+/=_\-.:~]{32,})["']?/g,
];

function scanText(text) {
  const hits = [];
  const seen = new Set(); // 同一 (行, 键, 值长) 只报一次：A/C 两种语境会重叠命中同一行
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    for (const re of PATTERNS) {
      re.lastIndex = 0;
      let m;
      while ((m = re.exec(line)) !== null) {
        const key = m[1];
        const value = m[2];
        if (!isSensitiveKey(key)) continue;
        if (value.length < 32) continue;
        if (charClasses(value) < 3) continue;
        if (isPlaceholder(value)) continue;
        const id = `${i + 1}|${normalize(key)}|${value.length}`;
        if (seen.has(id)) continue;
        seen.add(id);
        hits.push({ line: i + 1, key, len: value.length, classes: charClasses(value) });
      }
    }
  }
  return hits;
}

function walk(dir, out) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === '.git') continue;
      walk(p, out);
    } else if (e.isFile()) {
      if (SKIP_EXT.has(extname(e.name).toLowerCase())) continue;
      try {
        if (statSync(p).size > MAX_BYTES) continue;
      } catch {
        continue;
      }
      out.push(p);
    }
  }
  return out;
}

const roots = rootsArg ?? DEFAULT_ROOTS;
const files = [];
for (const r of roots) walk(resolve(ROOT, r), files);

const findings = [];
let scanned = 0;
for (const f of files) {
  let text;
  try {
    text = readFileSync(f, 'utf8');
  } catch {
    continue;
  }
  if (text.includes('\u0000')) continue; // 分不清是文本 ⇒ 跳过（不做启发式）
  scanned++;
  for (const h of scanText(text)) findings.push({ file: relative(ROOT, f), ...h });
}

if (JSON_OUT) {
  console.log(JSON.stringify({ roots, scanned, findings }, null, 2));
} else if (findings.length === 0) {
  console.log(
    `[fixture-secrets] ${scanned} files scanned under ${roots.join(', ')} — ` +
      'no high-entropy long token literals  [OK]'
  );
} else {
  console.error(`[fixture-secrets] ${findings.length} high-entropy long token literal(s) found:`);
  for (const f of findings) {
    // 🔴 值一律不回显：只报键名 / 长度 / 字符类数。
    console.error(`  ${f.file}:${f.line}  key=${f.key}  len=${f.len}  classes=${f.classes}`);
  }
  console.error(
    '\n修复口径：夹具/证据里的凭据位换占位词（example/placeholder/redacted…）或短化到 < 32；' +
      '真值只许放在 secrets store（凭据仓库），不进 git。'
  );
}

process.exit(findings.length === 0 ? 0 : 1);
