#!/usr/bin/env node
/**
 * check-llm-failure-dismiss.mjs — LLM 失败卡「点按消失」静态闸（2026-11-22）。
 * 断言：正文点按 / 关闭钮 / 换模型成功淡出 三条消失途径接线齐全；宿主 dismiss store 接入挂载与收卡。
 * 用法：node scripts/check-llm-failure-dismiss.mjs [--root <dir>]；退出码 0 = 绿，1 = 有缺失。
 */
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const rootIdx = args.indexOf('--root');
const ROOT = path.resolve(rootIdx >= 0 ? args[rootIdx + 1] : process.cwd());
const read = (rel) => {
  const p = path.join(ROOT, rel);
  return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '';
};

const CARD = read('src/main/resources/web/js/llmFailureCard.js');
const MAIN = read('src/main/resources/web/js/main.js');
const CSS = read('src/main/resources/web/css/llmFailureCard.css');

const checks = [
  // 2026-11-23：事件回调用仓内惯例 @type {Element} 标注（js-types），正则放宽为
  // isLlmFailureControlTarget( …e.target… )。
  ['正文点按消失（控件区除外）', /box\.addEventListener\('click'[\s\S]*isLlmFailureControlTarget\([^)]*e\.target\)[\s\S]*dismiss\(\)/.test(CARD)],
  ['控件区判定覆盖动作行/关闭钮/按钮/下拉', /\.llm-failure-actions, \.llm-failure-close, button, select, option/.test(CARD)],
  ['标题行关闭钮（显式兜底）', /className = 'llm-failure-close'[\s\S]*bClose\.onclick/.test(CARD)],
  ['换模型成功后自动淡出', /is-switched[\s\S]*setTimeout\(fadeOut, SWITCH_FADE_MS\)/.test(CARD)],
  ['消失经 onDismiss 通知宿主', /deps\.onDismiss\(m\.key\)/.test(CARD)],
  ['宿主 dismiss store 拦截同组复出', /if \(llmDismissed\.has\(key\)\) return;/.test(MAIN)],
  ['recovered 清除 dismiss 记录', /llmDismissed\.clear\(key\)/.test(MAIN)],
  ['淡出样式 + 控件光标', /\.llm-failure-card\.is-fading \{ opacity: 0; \}/.test(CSS) && /\.llm-failure-card \.llm-failure-actions/.test(CSS)],
];

let bad = 0;
for (const [name, ok] of checks) {
  console.log(`${ok ? 'OK  ' : 'MISS'} ${name}`);
  if (!ok) bad++;
}
if (bad) { console.log(`FAIL ${bad} 项缺失`); process.exit(1); }
console.log('PASS check-llm-failure-dismiss');
