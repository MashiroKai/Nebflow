#!/usr/bin/env node
/**
 * check-provider-notice-unify.mjs — 服务商受限通知「卡片化收编」静态闸
 * （provider-notice-card-unify 批 2026-11-23；作者 08:08 问「这些通知不都应该用 llm 卡吗」）。
 *
 * 断言（任一缺失即红）：
 *   C1 后台失败组呈现（providerFailureStatus.js）走卡族面（.option-box .llm-failure-card），
 *      不再产出裸 details/summary 横幅。
 *   C2 窗口受限型卡态：LlmFailureCard 有 llm-schedule-window 分类线值；渲染分叉出
 *      「去设置」动作（无等待/换模型按钮）；帧字段 windowDesc 进视图模型。
 *   C3 时段窗失败判据：LlmFailureSignal 认 ModelScheduleRestrictedError 为 LLM 类（卡家族收编）。
 *   C4 provider id 兜底：ProviderFailureGroups 无逐行明细时从正文提取 provider/model 形状。
 *   C5 「去设置」接线：main.js 给卡 deps.openSettings（openSettingsPanel 同一入口）。
 *
 * 用法：node scripts/check-provider-notice-unify.mjs [--root <dir>]；退出码 0 = 绿，1 = 有缺失。
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
const STATUS = read('src/main/resources/web/js/providerFailureStatus.js');
const SIGNAL = read('src/main/scala/nebflow/llm/LlmFailureSignal.scala');
const GROUPS = read('src/main/scala/nebflow/shared/ProviderFailureGroups.scala');

const checks = [
  ['C1 失败组卡面（.option-box .llm-failure-card，不再 details/summary）',
    /class: 'option-box llm-failure-card'/.test(STATUS) && !/h\('details'/.test(STATUS)],
  ['C1 失败组仍按 presentation 分标题（族区分呈现保留）',
    /titleKey\(group\.presentation\)/.test(STATUS) && /providerFailureGroupShape/.test(STATUS)],
  ['C2 窗口受限分类线值（前后端同键）',
    /llm-schedule-window/.test(CARD) && /llm-schedule-window/.test(read('src/main/scala/nebflow/llm/LlmFailureCard.scala'))],
  ['C2 窗口受限卡「去设置」动作（无等待/换模型分叉）',
    /if \(m\.isWindow\) \{[\s\S]*去设置/.test(CARD) && /openSettings/.test(CARD)],
  ['C2 windowDesc 进视图模型', /windowDesc/.test(CARD)],
  ['C3 时段窗错误收进 LLM 类判据',
    /case _: ModelScheduleRestrictedError => true/.test(SIGNAL)],
  ['C4 provider 兜底解析（ProviderFailureGroups）',
    /providerFallback/.test(GROUPS) && /受限：/.test(GROUPS)],
  ['C5 去设置接线（openSettingsPanel 单点入口）',
    /openSettings: openSettingsPanel/.test(MAIN)],
];

let bad = 0;
for (const [name, ok] of checks) {
  console.log(`${ok ? 'OK  ' : 'MISS'} ${name}`);
  if (!ok) bad++;
}
if (bad) { console.log(`FAIL ${bad} 项缺失`); process.exit(1); }
console.log('PASS check-provider-notice-unify');
