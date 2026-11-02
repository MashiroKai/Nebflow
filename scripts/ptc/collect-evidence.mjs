#!/usr/bin/env node
/**
 * 把 closed-loop 产出的 conversation.json 汇成一份可直接贴进设计文档的证据摘要：
 * SUMMARY.md（人读）+ request-response.sample.json（rust 线做对照测试用的请求/响应原样样本）。
 *
 *   node scripts/ptc/collect-evidence.mjs --dir reports/20261102_ptc-evidence
 */
import fs from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const dir = path.resolve(argv.includes('--dir') ? argv[argv.indexOf('--dir') + 1] : 'reports/20261102_ptc-evidence');
const LANGS = ['python', 'typescript'];

const runs = [];
for (const lang of LANGS) {
  const file = path.join(dir, lang, 'conversation.json');
  if (fs.existsSync(file)) runs.push({ lang, data: JSON.parse(fs.readFileSync(file, 'utf8')) });
}
if (runs.length === 0) {
  console.error(`no conversation.json under ${dir} — run scripts/ptc/closed-loop.mjs first`);
  process.exit(1);
}

const lines = [];
lines.push('# PTC 最小闭环 —— 实证摘要', '');
lines.push(`生成时间：${new Date().toISOString()}　脚本：\`scripts/ptc/closed-loop.mjs\``, '');

lines.push('## 总览', '');
lines.push('| 语言 | 插件 | 轮次 | PTC 执行次数 | 代码内用到的工具 | 代码内工具调用数 | 产物 | 闭环 |');
lines.push('|---|---|---|---|---|---|---|---|');
for (const { lang, data } of runs) {
  const v = data.verdict;
  lines.push(`| ${lang} | ${data.plugin} | ${v.turns} | ${v.ptc_executions} | ${v.distinct_tools_used_in_code.join(', ')} | ${v.tool_calls_in_code} | ${v.artifact_written ? 'summary.json' : '缺失'} | ${v.closed_loop ? '✅' : '❌'} |`);
}
lines.push('');

for (const { lang, data } of runs) {
  const codeExec = data.turns.flatMap((t) => t.tool_executions).find((e) => e.tool.startsWith('ptc_run'));
  lines.push(`## ${lang}（插件 \`${data.plugin}\`）`, '');
  lines.push(`- 模型：\`${data.model.provider}/${data.model.name}\`　MCP 协议：\`${data.protocol}\``);
  lines.push(`- 暴露给模型的工具：\`${data.tool_surface.join('`, `')}\``);
  lines.push(`- 总耗时：${data.wall_ms} ms`);
  lines.push('');
  lines.push('### 逐轮', '');
  lines.push('| 轮 | finish_reason | 模型耗时(ms) | tokens | 代码执行 | 代码内工具台账 |');
  lines.push('|---|---|---|---|---|---|');
  for (const t of data.turns) {
    const exec = t.tool_executions.map((e) => `${e.tool} (${e.duration_ms ?? 0}ms)`).join('<br>') || '—';
    const ledger = t.tool_executions.flatMap((e) => e.result_json?.tool_calls ?? []).map((c) => c.tool).join(', ') || '—';
    lines.push(`| ${t.turn} | ${t.finish_reason} | ${t.duration_ms} | ${t.usage?.total_tokens ?? '-'} | ${exec} | ${ledger} |`);
  }
  lines.push('');
  if (codeExec) {
    lines.push('### 模型生成的代码（原文）', '');
    lines.push('```' + (lang === 'python' ? 'python' : 'js'));
    lines.push(String(codeExec.args.code ?? ''));
    lines.push('```', '');
    lines.push('### 这一次 ptc_run 的响应（契约 responseschema 原样）', '');
    lines.push('```json');
    lines.push(JSON.stringify(codeExec.result_json, null, 2));
    lines.push('```', '');
  }
  lines.push('### 产物', '');
  lines.push('```json');
  lines.push(JSON.stringify(data.artifact?.content ?? null, null, 2));
  lines.push('```', '');
  lines.push('### 模型最终回复', '');
  lines.push('```');
  lines.push(String(data.verdict.final_answer ?? ''));
  lines.push('```', '');
}

fs.writeFileSync(path.join(dir, 'SUMMARY.md'), `${lines.join('\n')}\n`);

// rust 线对照测试用：一条请求 + 一条响应，原样落盘
const sample = runs.map(({ lang, data }) => {
  const exec = data.turns.flatMap((t) => t.tool_executions).find((e) => e.tool.startsWith('ptc_run'));
  return { language: lang, plugin: data.plugin, tool: exec?.name, request: exec?.args, response: exec?.result_json };
});
fs.writeFileSync(path.join(dir, 'request-response.sample.json'), `${JSON.stringify({ contract: 'ptc/v1', samples: sample }, null, 2)}\n`);
console.log(`wrote ${path.join(dir, 'SUMMARY.md')}  (${runs.length} run(s))`);
