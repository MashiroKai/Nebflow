// canvas-error-capsule-clearance.spec.mjs — 台账 #912 件1 spec 钉（源面静态契约）
//
// 作者令（2026-10-26 12:02）：「提示文字不得被 CanvasHeader 胶囊遮挡」——错误态
// 卡片（.canvas-error，含 PDF 打开失败面「Cannot display PDF」）首行文本此前
// 顶对齐落在卡头胶囊带（.canvas-header top 10 + 36px 高，z-30 毛玻璃叠层），
// 实拍在案首行被吞。
//
// 契约：
//   C1 让位带在位 —— split.css 有 `.canvas-tab-pane .canvas-error` 的
//      padding-top 覆盖（56px > 胶囊底缘 46px），且作用域限定 .canvas-tab-pane
//      （chat 侧同名类不受累）；
//   C2 胶囊带几何未变 —— .canvas-header 仍 top:10px / 高 36px（--capsule-h），
//      本批只动错误面不动胶囊；
//   C3 失败面挂载点 —— viewers/pdf.js 等五 viewer 的失败面仍落 .canvas-error
//      （类名契约不破，让位随类名生效）。
//
// 运行：node tests/canvas-error-capsule-clearance.spec.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const read = (rel) => readFileSync(join(WEB, rel), 'utf8');

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (cond) pass++; else fail++;
};

// C1 让位带在位 + 作用域限定
const css = read('css/split.css');
const cssNoComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
const scoped = cssNoComments.match(/\.canvas-tab-pane\s+\.canvas-error\s*\{[^}]*padding-top:\s*(\d+)px/);
ok('C1a .canvas-tab-pane .canvas-error 让位带在位', !!scoped, scoped ? `padding-top=${scoped[1]}px` : 'missing');
ok('C1b 让位量清出胶囊带（>46px）', !!scoped && Number(scoped[1]) > 46, scoped ? `${scoped[1]}px vs 底缘46px` : '');
// 基类规则（选择器恰为 .canvas-error）不得带 padding-top —— chat 侧同名类零受累。
const baseRule = cssNoComments.match(/(?:^|\n)\.canvas-error\s*\{([^}]*)\}/);
ok('C1c 基类 .canvas-error 无 padding-top（chat 弹层不受累）',
  !!baseRule && !/padding-top/.test(baseRule[1]), baseRule ? baseRule[1].trim().replace(/\s+/g, ' ') : 'missing');

// C2 胶囊带几何未变
ok('C2a .canvas-header top:10px 钉在位', /(^|\n)\.canvas-header\s*\{[^}]*top:\s*10px/.test(css));
ok('C2b 胶囊高 36px token 钉在位', /--capsule-h,\s*36px/.test(css));

// C3 失败面挂载点契约
const pdfJs = read('js/viewers/pdf.js');
ok('C3a pdf.js 失败面落 .canvas-error', pdfJs.includes("className = 'canvas-error'") || pdfJs.includes("class=\"canvas-error\""));
const imgJs = read('js/viewers/image.js');
ok('C3b image.js 失败面落 .canvas-error', imgJs.includes('canvas-error'));
const fvJs = read('js/fileViewers.js');
ok('C3c fileViewers 渲染失败面落 .canvas-error', fvJs.includes("'canvas-error'"));

console.log(`\ncanvas-error-capsule-clearance: ${pass} PASS / ${fail} FAIL`);
process.exit(fail === 0 ? 0 : 1);
