// scripts/tests/message-item-css-audit.test.mjs — M6–M11 CSS 判定的**盲区证明**
// （2026-11-18 复核：闸不只要会绿，还要能红）。
//
// 每条旧盲区都配一个「造出来能红」的样本；样本体**取自真实源码**（下面用
// extractRealRule() 从仓内真实 CSS 里切出真实规则块，再注入一份重复/近似写法），
// 而不是凭空手写，保证判据面对的是生产 CSS 的真实形状（多行声明、token 值、
// 逗号并列选择器、注释尾随）。
//
//   Red 组（判据必须报红）：
//     R1 同一声明写两遍（跨文件 / 跨规则）            → 旧 M9「只数枚举属性」漏检
//     R2 第二基座块不写枚举属性（只写 padding）        → 旧 M9 绿
//     R3 近似但不相同的上下文选择器（`#chat .pop-item-footer`）→ 旧 M10 只看 selExact，绿
//     R4 同一属性两次但大小写/空白不同（归一化后相等） → 旧扫描按原始 body 正则，绿
//     R5 变体在 `:is()` 里把成员当主语（越权属性）     → 旧 M8 只查 display/flex-direction，绿
//     R6 两侧面共享几何只写单侧面                       → 旧 M7/M11 的「命名两侧面」要求
//   Green 组（判据不许假红 —— 反证精确 token 与深度感知切分）：
//     G1 `#input-bar` 不得被当 `#input`
//     G2 `.pop-group-item` 不得被当 `.pop-group`
//     G3 `body.mobile-layer :is(.duration-badge .msg-copy, .duration-badge-time)` 的
//        祖先 `.duration-badge` 不是主语（真实源码整段，逐字可红可绿的分界样本）
//     G4 级联透传值 `inherit` 不是第二份声明
//     G5 降级块（`html.no-backdrop`）不参战
//     G6 真实 CSS 目录整体必须绿（闸在真源码上的回归钉）

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { auditMessageItemCss, subjectMembers, splitSelectorList } from '../lib/message-item-css-audit.mjs';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const CSS_ROOT = `${ROOT}src/main/resources/web/css/`;
const readCss = (f) => readFileSync(`${CSS_ROOT}${f}`, 'utf8');

/** 从真实 CSS 里切出「以 selector 开头的那条规则块」原文（含尾随注释内的同名不取）。 */
function extractRealRule(file, selector) {
  const text = readCss(file);
  const idx = text.indexOf(`\n${selector} {`);
  assert.ok(idx >= 0, `真实源码里找不到规则 ${selector} @ ${file}`);
  const end = text.indexOf('\n}', idx);
  assert.ok(end > idx, `真实规则 ${selector} @ ${file} 未闭合`);
  return text.slice(idx + 1, end + 2);
}

const audit = (entries) => auditMessageItemCss(entries
  .map(([file, text]) => ({ file, text })));
const hasErr = (errs, needle) => errs.some((e) => e.includes(needle));

test('R1 同一声明写两遍（真实单定义块 + 第二份）必须红', () => {
  const real = extractRealRule('chat.css', '.pop-group,\n.fm-att-list');
  const errs = audit([
    ['chat.css', real],
    ['friends.css', '.fm-att-list {\n  display: flex;\n}\n'],
  ]);
  assert.ok(hasErr(errs, '基座第二份') && hasErr(errs, '.fm-att-list 的 display'), errs.join('\n'));
});

test('R2 第二基座块不写「枚举属性」也必须红（旧判据的 B1）', () => {
  const real = extractRealRule('chat.css', '.duration-badge');
  const errs = audit([
    ['chat.css', real],
    ['sapphire.css', '.duration-badge {\n  padding: 4px;\n}\n'],
  ]);
  assert.ok(hasErr(errs, '基座第二份') && hasErr(errs, 'padding'), errs.join('\n'));
});

test('R3 近似但不相同的上下文选择器里的第二基座必须红（旧判据的 B3）', () => {
  const errs = audit([
    ['chat.css', extractRealRule('chat.css', '.pop-item-footer')],
    ['other.css', '#chat .pop-item-footer {\n  padding: 8px;\n}\n'],
  ]);
  assert.ok(hasErr(errs, '变体属性越权') && hasErr(errs, '#chat .pop-item-footer'), errs.join('\n'));
});

test('R4 归一化后相等的同一声明（PADDING / 值内空白）必须红（旧判据的 B5）', () => {
  const errs = audit([
    ['a.css', '.duration-badge {\n  padding: 4px   6px;\n}\n'],
    ['b.css', '.duration-badge {\n  PADDING :  4px 6px ;\n}\n'],
  ]);
  assert.ok(hasErr(errs, '基座第二份'), errs.join('\n'));
});

test('R5 `:is()` 里把成员当主语的越权变体必须红', () => {
  const errs = audit([
    ['mobileLayer.css', 'body.mobile-layer :is(.duration-badge, .duration-badge-time) {\n  min-height: 40px;\n}\n'],
  ]);
  assert.ok(hasErr(errs, '变体属性越权') && hasErr(errs, 'min-height'), errs.join('\n'));
});

test('R6 两侧面共享几何只写单侧面必须红（旧 M7/M11 的「命名两侧面」判据）', () => {
  const errs = audit([
    ['input.css', '.fm-input-bar {\n  position: relative;\n  padding: 6px 8px;\n}\n'],
  ]);
  assert.ok(hasErr(errs, '两侧面分叉') && hasErr(errs, '未命名 #input-bar'), errs.join('\n'));
});

test('G1 精确 token：`#input-bar` 不得被当 `#input`（反证旧 ID 正则子串误配）', () => {
  // 样本用 shell 的非共享几何属性（margin）：若按子串命中，`#input-bar` 会被当
  // field 成员 `#input` 的越权变体 ⇒ 假红；精确 token 命中下必须零错。
  const errs = audit([['input.css', '#input-bar {\n  margin: 0;\n}\n']]);
  assert.equal(errs.length, 0, errs.join('\n'));
  // 同名 token 出现在近似名里也不许命中：
  assert.deepEqual([...subjectMembers('#input-bar-extra .x')], []);
  assert.deepEqual([...subjectMembers('#input')], ['#input']);
});

test('G2 精确 token：`.pop-group-item` 不得被当 `.pop-group`', () => {
  const errs = audit([['chat.css', '.pop-group-item {\n  display: flex;\n}\n']]);
  assert.equal(errs.length, 0, errs.join('\n'));
  assert.deepEqual([...subjectMembers('.pop-group-item')], []);
});

test('G3 `:is()` 内的祖先成员不是主语（真实源码整段不许假红）', () => {
  const real = extractRealRule('mobileLayer.css', 'body.mobile-layer :is(.duration-badge .msg-copy, .duration-badge-time)');
  const errs = audit([['mobileLayer.css', real]]);
  assert.equal(errs.length, 0, errs.join('\n'));
  // 深度感知切分：`:is()` 内的逗号与空白都不切外层。
  assert.deepEqual(splitSelectorList('body.mobile-layer :is(.a .b, .c)'), ['body.mobile-layer :is(.a .b, .c)']);
  // 内嵌备选里 `.duration-badge` 是**祖先**（主语是 `.msg-copy`）；`.duration-badge-time`
  // 不是成员 token ⇒ 这条真实源码对 footer 族零命中（正是旧扫描假红的点）。
  assert.deepEqual([...subjectMembers('body.mobile-layer :is(.duration-badge .msg-copy, .duration-badge-time)')], []);
  // 对照：`：is()` 备选把成员当自己的主语时必须命中。
  assert.deepEqual([...subjectMembers('body.mobile-layer :is(.duration-badge, .x)')], ['.duration-badge']);
});

test('G4 级联透传值（inherit）不是第二份声明', () => {
  const errs = audit([
    ['glass.css', extractRealRule('glass.css', '#input-bar,\n#queue-bar.visible')],
    ['input.css', '#input-bar::before {\n  border-radius: inherit;\n}\n'],
  ]);
  assert.equal(errs.length, 0, errs.join('\n'));
});

test('G5 降级块不参战（html.no-backdrop 上下文）', () => {
  const errs = audit([
    ['input.css', extractRealRule('input.css', '#input-bar,\n.fm-input-bar')],
    ['glass.css', 'html.no-backdrop #input-bar {\n  padding: 1px;\n  display: block;\n}\n'],
  ]);
  assert.equal(errs.length, 0, errs.join('\n'));
});

test('G6 真实 CSS 目录整体必须绿（闸在真源码上的回归钉）', () => {
  const entries = readdirSync(CSS_ROOT).filter((f) => f.endsWith('.css'))
    .map((f) => ({ file: f, text: readCss(f) }));
  const errs = auditMessageItemCss(entries);
  assert.equal(errs.length, 0, errs.join('\n'));
});
