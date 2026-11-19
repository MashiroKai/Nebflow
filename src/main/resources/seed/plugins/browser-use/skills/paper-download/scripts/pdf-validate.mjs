#!/usr/bin/env node
// pdf-validate.mjs — PDF 文件校验（paper-download skill；亦可独立使用）
//
// 判据：前 1024 字节内存在 %PDF- 头（PDF 规范允许头部前有少量垃圾字节）；
//       体积 ≥ --min-bytes（默认 10240，滤掉拦截页/错误页碎片）；
//       头部像 HTML（<!doctype html / <html）⇒ 判 html-not-pdf（多半是被拦或登录墙页面）。
// 用法：node pdf-validate.mjs <file> [--min-bytes N]
//       作为模块：import {validatePdfBuf} from 本文件（或经 lib.mjs 导出）。

import {parseArgs} from 'node:util';
import fs from 'node:fs';
import {result, validatePdfBuf, exitSoon} from '../../browser-session/scripts/lib.mjs';

function main() {
  const argv = process.argv.slice(2);
  const {values} = parseArgs({
    args: argv,
    options: {'min-bytes': {type: 'string', default: '10240'}},
    allowPositionals: true,
    strict: false,
  });
  const file = argv.find((a) => !a.startsWith('--'));
  if (!file) {
    console.error('ERROR 需要 <file> 参数');
    result({ok: false, error: 'missing file'});
    exitSoon(1);
  }
  let buf;
  try {
    buf = fs.readFileSync(file);
  } catch (e) {
    result({ok: false, file, error: String((e && e.message) || e)});
    exitSoon(1);
  }
  const v = validatePdfBuf(buf, {minBytes: Number(values['min-bytes'])});
  console.log(`${v.valid ? 'VALID' : 'INVALID'} ${file} size=${v.size} reason=${v.reason}`);
  result({ok: v.valid, cmd: 'pdf-validate', file, ...v});
  exitSoon(v.valid ? 0 : 1);
}

main();
