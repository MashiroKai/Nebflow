// test-output-block.mjs — report 块提取器 JS 侧单测（output 块纪律渠道接线批
// 2026-11-01 起；report 块可见性批 2026-11-20 迁移标签）。
//
// 被测对象 = 真实的 `src/main/resources/web/js/utils.js` 源（从模块源码里取出
// 函数体，不是抄一份副本——同 test-model-badge.mjs 形态），断言作者裁定过的
// 判定表：无块 / 单块 / 多块 / 围栏含标签 / 空块 + 不平衡家族。
//
// 围栏安全（作者令原文：块形态当初就是为了避开 md 语法冲突，提取器不能反被
// 围栏骗了）：``` 围栏内的字面 <report> 是内容不是结构——既不能被剥离、不能
// 参与配对，管线的不平衡逃逸腿也不能腐蚀围栏体。
//
// Run from the repo root: node scripts/test-output-block.mjs
import { readFileSync } from 'node:fs';

const src = readFileSync('src/main/resources/web/js/utils.js', 'utf8');
let allOk = true;
const fail = (msg) => { console.log(`FAIL ${msg}`); allOk = false; };

// ── grab the real functions from the module source ──────────────────────────
function grab(re, name) {
  const m = re.exec(src);
  if (!m) { fail(`${name}: not found in utils.js`); return null; }
  return m[0];
}
const rePairSrc = grab(/const RE_OUTPUT_PAIR = .*;/, 'RE_OUTPUT_PAIR');
const maskSrc = grab(/function _maskCodeFences\(text\) \{[\s\S]*?\n\}/, '_maskCodeFences');
const balOnSrc = grab(/function _outputTagsBalancedOn\(masked\) \{[\s\S]*?\n\}/, '_outputTagsBalancedOn');
const balSrc = grab(/function _outputTagsBalanced\(text\) \{[\s\S]*?\n\}/, '_outputTagsBalanced');
const extractSrc = grab(/export function extractOutputInner\(text\) \{[\s\S]*?\n\}/, 'extractOutputInner');
if (!rePairSrc || !maskSrc || !balOnSrc || !balSrc || !extractSrc) process.exit(1);

const factory = new Function(
  rePairSrc + '\n' + maskSrc + '\n' + balOnSrc + '\n' + balSrc + '\n' + extractSrc.replace('export ', '') +
    '\nreturn { extractOutputInner, maskFences: _maskCodeFences, balanced: _outputTagsBalanced };'
);
const { extractOutputInner, maskFences, balanced } = factory(undefined);

// ── 1. 无块：narration only ⇒ null (the console face falls back to full text; ──
//    the CHANNEL face reads the same null as "deliver nothing") ──────────────
if (extractOutputInner('纯叙述，无任何标签') !== null) fail('OB-1 no-tag text must extract to null');
if (extractOutputInner('') !== null) fail('OB-1 empty text must extract to null');
if (extractOutputInner('</report> 孤立闭标签') !== null) fail('OB-1 stray close must extract to null');
if (extractOutputInner('<report>未完的开标签') !== null) fail('OB-1 open-without-close must extract to null');

// ── 2. 单块：trimmed body ────────────────────────────────────────────────────
if (extractOutputInner('前叙述 <report> 你好 </report> 后叙述') !== '你好') fail('OB-2 single block body');
if (extractOutputInner('<report>line1\nline2</report>') !== 'line1\nline2') fail('OB-2 multi-line body');

// ── 3. 多块：blank-line join, in order ──────────────────────────────────────
if (extractOutputInner('<report>A</report>\n中间叙述\n<report> B </report>') !== 'A\n\nB')
  fail('OB-3 multi-block blank-line join');

// ── 4. 围栏含标签（the author's fence-safety order） ────────────────────────
if (extractOutputInner('看这段：\n```html\n<report>hi</report>\n```\n完毕') !== null)
  fail('OB-4a fenced literal tags must NOT be extracted');
if (extractOutputInner('<report>A</report>\n```html\n<report>hi</report>\n```\n<report>B</report>') !== 'A\n\nB')
  fail('OB-4b fenced literal tags must not join/consume outside blocks');
if (extractOutputInner('```scala\nval x = "<report>y</report>"') !== null)
  fail('OB-4c unterminated fence hides everything to the end');
// The fenced stray close must not poison a valid outside pair.
if (extractOutputInner('<report>ok</report>\n```\n</report>\n```') !== 'ok')
  fail('OB-4d fenced stray close must not break outside balance');
// And the fenced pair must not rescue an outside unbalanced open.
if (extractOutputInner('<report>ok\n```\n<report>x</report>\n```') !== null)
  fail('OB-4e fenced pair must not rescue outside unbalanced tag');

// ── 5. 空块：consumed, extracts to "" (NOT null) ────────────────────────────
if (extractOutputInner('<report></report>') !== '') fail('OB-5 empty block must extract to ""');
if (extractOutputInner('<report>   </report>') !== '') fail('OB-5 whitespace block must extract to ""');
if (extractOutputInner('<report></report><report>real</report>') !== '\n\nreal')
  fail('OB-5 empty block keeps its blank-line slot (verbatim console join)');

// ── the mask itself: same length (offsets 1:1 with the original) ────────────
const fenceSample = 'a\n```html\n<report>hi</report>\n```\nb';
const masked = maskFences(fenceSample);
if (masked.length !== fenceSample.length) fail('mask must be length-preserving (offset contract)');
if (!masked.includes('a\n') || !masked.includes('\nb')) fail('mask must keep outside text readable');
if (masked.includes('<report>')) fail('mask must hide in-fence literal tags from the scan');
if (maskFences('no fences here <report>t</report>') !== 'no fences here <report>t</report>')
  fail('mask fast path: fence-free text untouched');

// ── legacy tag: the retired `<output>` spelling is ordinary text ────────────
// (report 块可见性批: 无过渡别名——旧字样既不提取也不特殊处理)
if (extractOutputInner('前 <output>x</output> 后') !== null)
  fail('OB-6 the retired <output> tag must be ordinary text (no transitional alias)');

// ── the pipeline pass: fence-aware wiring pinned on the real source ─────────
// The balanced arm must find pairs on the MASKED text and splice the ORIGINAL
// (fenced literal tags never become placeholders); the unbalanced arm must
// escape only out-of-fence tags (escaping in-fence tags would corrupt the
// fence body).
if (!/const masked = _maskCodeFences\(text\);/.test(src))
  fail('pipeline pass must mask before the balance gate');
if (!/RE_OUTPUT_PAIR\.exec\(masked\)/.test(src))
  fail('pipeline balanced arm must find pairs on the masked text');
if (!/RE_OUTPUT_ANY_TAG\.exec\(masked\)/.test(src))
  fail('pipeline unbalanced arm must escape only masked-scan (out-of-fence) tags');
if (/text\.replace\(RE_OUTPUT_PAIR,/.test(src))
  fail('pipeline balanced arm must not blind-replace pairs on the raw text');
if (/text\.replace\(RE_OUTPUT_ANY_TAG,/.test(src))
  fail('pipeline unbalanced arm must not blind-escape tags on the raw text');
// The extractor goes through the same mask (single scan source).
if (!/const masked = _maskCodeFences\(text\);\n  if \(!_outputTagsBalancedOn\(masked\)\) return null;/.test(src))
  fail('extractOutputInner must gate on the masked text');
// The retired kill switch must not be READ by the extractor or the pipeline
// (prose mentions of its retirement are fine — a live read is not).
if (/parsedConfig\?*\.?outputBlock|outputBlockEnabled\s*\(/.test(src))
  fail('the retired `outputBlock.enabled` kill switch must not be read anywhere in utils.js');

console.log(allOk ? '\nALL PASS' : '\nSOME FAIL');
process.exit(allOk ? 0 : 1);
