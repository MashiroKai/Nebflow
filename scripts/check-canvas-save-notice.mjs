#!/usr/bin/env node
// check-canvas-save-notice.mjs — Canvas 保存失败「静默保留」的**静态门禁**。
//
// 历史：2026-11-18 P0 加过「保存失败」toast（带去重账 + hint + 关闭钮）。
// 2026-11-22 作者令「删掉这个弹窗」：保存失败改为**静默保留**——不弹任何通知，
// 本地兜底（localUnsynced + 失权端草稿）照旧。本门禁随之翻转为「不得再出现」。
//
// 判据（每条都有命中数读数）：
//   S1 失败路径静默且兜底保留：canvasWriter 的 failed 处理器置 localUnsynced 并
//      调 writerChanged('reader')，且处理器体内不含任何通知调用。
//   S2 写者段无通知：从 `let localUnsynced` 到 canvasWriter 构造之间不出现
//      __showToast / showToast / writerNotice。
//   S3 全前端无「保存失败」弹窗文案：web/js 下不出现 'Canvas 保存失败' 字面量。
//   S4 去重/hint 残骸清零：不出现 writerNotice / SAVE_FAIL_HINT / NOTICE_SEEN_KEY /
//      canvas_notice_seen（死代码不留）。
//
// 用法：
//   node scripts/check-canvas-save-notice.mjs                    # 当前树（门禁用）
//   node scripts/check-canvas-save-notice.mjs --web-root <dir>   # 另一棵树
//   node scripts/check-canvas-save-notice.mjs --json             # 机器读
// 退出码：0 = 全绿；1 = 有 FAIL。
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, isAbsolute, normalize, extname } from 'node:path';
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
const FAIL_TEXT = 'Canvas 保存失败';

const results = [];
const check = (id, ok, note) => results.push({ id, ok, note });

const walk = (dir) => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? walk(p) : (['.js', '.mjs', '.html'].includes(extname(p)) ? [p] : []);
});

let canvasJs = '';
try { canvasJs = readFileSync(CANVAS, 'utf8'); } catch { /* handled below */ }

if (!canvasJs) {
  check('S0', false, `missing source canvas.js under ${WEB}`);
} else {
  const start = canvasJs.indexOf('let localUnsynced = false;');
  const end = canvasJs.indexOf('const canvasWriter = createCanvasWriterClient(');
  const block = start >= 0 && end > start ? canvasJs.slice(start, end) : '';
  const handler = /failed:\s*\(\)\s*=>\s*\{([^}]*)\}/.exec(canvasJs);
  const handlerBody = handler ? handler[1] : '';

  const silentFallback = /localUnsynced\s*=\s*true/.test(handlerBody) && /writerChanged\(\s*'reader'\s*\)/.test(handlerBody);
  const handlerSilent = !/__showToast|showToast|writerNotice/.test(handlerBody);
  check('S1', !!handler && silentFallback && handlerSilent,
    `failed handler: fallback-kept=${silentFallback} silent=${handlerSilent}`);

  const noNoticeInBlock = block.length > 0 && !/__showToast|showToast|writerNotice/.test(block);
  check('S2', noNoticeInBlock, `writer block carries no toast/notice call=${noNoticeInBlock} (block ${block.length} chars)`);

  const files = walk(join(WEB, 'js'));
  const literalHits = files.filter((f) => readFileSync(f, 'utf8').includes(FAIL_TEXT));
  check('S3', literalHits.length === 0,
    `'${FAIL_TEXT}' literal files=${literalHits.length}${literalHits.length ? ' ' + literalHits.map((f) => f.slice(WEB.length + 1)).join(',') : ''}`);

  const residue = ['writerNotice', 'SAVE_FAIL_HINT', 'NOTICE_SEEN_KEY', 'canvas_notice_seen']
    .filter((t) => canvasJs.includes(t));
  check('S4', residue.length === 0, `dead dedup/hint residue=${residue.length ? residue.join(',') : 'none'}`);
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
