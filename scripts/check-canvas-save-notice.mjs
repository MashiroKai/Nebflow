#!/usr/bin/env node
// check-canvas-save-notice.mjs — Canvas「保存失败」通知的**静态门禁**
// （canvas-save-failure 批，2026-11-18 P0：作者报障「现在怎么老是出现 canvas 保存
// 失败的通知 好烦」）。
//
// 运行面对应 tests/canvas-save-failure-dedup.spec.mjs（真 DOM/真网络计数）。本文件是
// **静态面**：把「这条通知的设计约束」钉成可机检 —— 真几何腿只能量它撞上的那几态，
// 静态腿量**全部声明**：改回「页内 Set 就完事」「去掉可关闭」「hint 空话」这类
// 回归，在没跑到该态时几何腿全绿，静态腿立刻红。
//
// 判据（每条都有命中数读数）：
//   N1 跨刷新去重：canvas.js 的保存失败通知去重账必须落到 sessionStorage
//      （页内 Set 只挡同一页；刷新清空 ⇒ 席位被持久占住时每次加载重弹 = 「老是」）
//   N2 同因单发射点：全树只允许一条 'save-failed' 原因字面量（不新增第二套通知）
//   N3 正文逐字保留：作者裁定原句 'Canvas 保存失败，本地变更已保留。' 必须原样在场
//   N4 可执行下一步：通知必须带 hint 次级行，且 hint 不是一句空话（≥12 字、
//      且不等于正文本身）
//   N5 可关闭：通知必须带 closable，且 modal.js 的 showToast 真的实现关闭钮
//   N6 hint 渲染：modal.js 真的把 opts.hint 渲染成 .nebflow-toast-hint
//   N7 富通知不改旧面：modal.js 只有在 closable/hint 在场时才切富结构
//      （普通 toast 的 DOM 与旧版一致 —— 本批不碰其他通知）
//
// 用法：
//   node scripts/check-canvas-save-notice.mjs                    # 当前树（门禁用）
//   node scripts/check-canvas-save-notice.mjs --web-root <dir>   # 另一棵树（改前取证）
//   node scripts/check-canvas-save-notice.mjs --json             # 机器读
// 退出码：0 = 全绿；1 = 有 FAIL。
import { readFileSync } from 'node:fs';
import { join, isAbsolute, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('..', import.meta.url));
const argv = process.argv.slice(2);
const argVal = (name, def = null) => {
  const i = argv.indexOf(`--${name}`);
  if (i >= 0 && argv[i + 1]) return argv[i + 1];
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(eq.indexOf('=') + 1) : def;
};
const JSON_OUT = argv.includes('--json');
const WEB_SPEC = argVal('web-root', '') || join(HERE, 'src/main/resources/web');
const WEB = normalize(isAbsolute(WEB_SPEC) ? WEB_SPEC : join(HERE, WEB_SPEC));

const CANVAS = join(WEB, 'js', 'canvas.js');
const MODAL = join(WEB, 'js', 'modal.js');

/** 作者裁定原句（与 canvas-readonly-toast / canvas-reader-nonblocking 的断言同一份）。 */
const CANONICAL = 'Canvas 保存失败，本地变更已保留。';

const results = [];
const check = (id, ok, note) => results.push({ id, ok, note });

let canvasJs = '';
let modalJs = '';
try { canvasJs = readFileSync(CANVAS, 'utf8'); } catch { /* handled below */ }
try { modalJs = readFileSync(MODAL, 'utf8'); } catch { /* handled below */ }

if (!canvasJs || !modalJs) {
  check('N0', false, `missing source (canvas.js=${!!canvasJs} modal.js=${!!modalJs}) under ${WEB}`);
} else {
  // 通知区块 = writerRejectionNotices 起到 canvasWriter 构造前。
  const blockStart = canvasJs.indexOf('const writerRejectionNotices');
  const blockEnd = canvasJs.indexOf('const canvasWriter = createCanvasWriterClient(');
  const block = blockStart >= 0 && blockEnd > blockStart ? canvasJs.slice(blockStart, blockEnd) : '';

  // N1 —— 去重账跨刷新存活（sessionStorage），且通知闸真的查它。
  const hasSeenStore = /sessionStorage\.getItem\(\s*NOTICE_SEEN_KEY\s*\)/.test(block)
    && /sessionStorage\.setItem\(\s*NOTICE_SEEN_KEY\s*[,)]?/.test(block);
  const noticeGuardsOnSeen = /function writerNotice\([\s\S]{0,400}noticeSeenBefore\(/.test(block);
  check('N1', hasSeenStore && noticeGuardsOnSeen,
    `sessionStorage seen-store=${hasSeenStore} writerNotice-guards-on-it=${noticeGuardsOnSeen}`);

  // N2 —— 同因只有一个发射点。
  const reasonLiterals = (canvasJs.match(/'save-failed'/g) || []).length;
  check('N2', reasonLiterals === 1, `'save-failed' literals=${reasonLiterals} (expect 1)`);

  // N3 —— 正文原句逐字在场（writerNotice 的调用点 args）。
  const noticeCall = /writerNotice\(\s*'([^']*)'/.exec(canvasJs);
  check('N3', !!noticeCall && noticeCall[1] === CANONICAL,
    noticeCall ? `message literal=${JSON.stringify(noticeCall[1])}` : 'writerNotice( literal not found');

  // N4 —— 可执行的下一步（hint 非空话）。
  const hintLit = /const SAVE_FAIL_HINT\s*=\s*'([^']*)'/.exec(canvasJs);
  const hintUsed = /\{\s*closable:\s*true\s*,\s*hint:\s*SAVE_FAIL_HINT\s*\}/.test(block);
  const hintText = hintLit ? hintLit[1] : '';
  check('N4', !!hintLit && hintUsed && hintText.length >= 12 && hintText !== CANONICAL,
    `hint literal=${JSON.stringify(hintText)} used=${hintUsed} (len>=12 & != message)`);

  // N5 —— 可关闭 + modal.js 真的实现关闭钮。
  const closablePassed = /\{\s*closable:\s*true\s*,\s*hint:\s*SAVE_FAIL_HINT\s*\}/.test(block);
  const modalCloseBtn = /nebflow-toast-close/.test(modalJs) && /opts\.closable/.test(modalJs);
  check('N5', closablePassed && modalCloseBtn,
    `notice passes closable=${closablePassed} modal-close-button=${modalCloseBtn}`);

  // N6 —— hint 渲染成次级行。
  const modalHint = /opts\.hint/.test(modalJs) && /nebflow-toast-hint/.test(modalJs);
  check('N6', modalHint, `modal renders opts.hint -> .nebflow-toast-hint=${modalHint}`);

  // N7 —— 富结构只在 closable/hint 在场时启用（普通 toast 面零改动）。
  const richGate = /const rich = !!\(opts\.closable \|\| opts\.hint\)/.test(modalJs);
  check('N7', richGate, `rich layout gated on closable||hint=${richGate}`);
}

const fails = results.filter((r) => !r.ok);
if (JSON_OUT) {
  console.log(JSON.stringify({ web: WEB, results, ok: fails.length === 0 }, null, 2));
} else {
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'} ${r.id} — ${r.note}`);
  console.log(fails.length === 0
    ? `canvas-save-notice: ${results.length}/${results.length} rules pass`
    : `canvas-save-notice: ${results.length - fails.length}/${results.length} rules pass — ${fails.map((f) => f.id).join(', ')} FAIL`);
}
process.exit(fails.length === 0 ? 0 : 1);
