#!/usr/bin/env node
/**
 * PTC 最小闭环（真模型、真 MCP、真代码执行）。
 *
 *   模型生成代码 → 代码调用 ≥2 个不同的宿主工具 → 结果回给模型 → 模型基于结果继续下一步
 *
 * 宿主半边 = 本脚本：按插件 mcp.json 起 MCP server（与引擎 core/mcp/McpClient.scala 同路径），
 * 把工具以引擎的命名 `mcp__plugin_<plugin>_<server>__<tool>` 暴露给模型，转调 MCP。
 *
 *   node scripts/ptc/closed-loop.mjs --lang python  --out reports/20261102_ptc-evidence
 *   node scripts/ptc/closed-loop.mjs --lang typescript --out reports/20261102_ptc-evidence
 *
 * 模型/供应商从 --provider/--model 指定，密钥读实例配置（$PTC_LLM_CONFIG 或 ~/.stand/nebflow.json），
 * 只用于发请求，永不打印。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { McpStdioClient, serverSpec } from './lib/mcp-client.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const argv = process.argv.slice(2);
const arg = (name, fallback) => (argv.includes(`--${name}`) ? argv[argv.indexOf(`--${name}`) + 1] : fallback);

const LANG = arg('lang', 'python');
const PROVIDER = arg('provider', process.env.PTC_PROVIDER || 'USTC');
const MODEL = arg('model', process.env.PTC_MODEL || 'deepseek-v4-flash-ascend');
const MAX_TURNS = Number(arg('max-turns', '6'));
const OUT = path.resolve(arg('out', path.join(ROOT, 'reports', '20261102_ptc-evidence')));

const PLUGIN = {
  python: { dir: path.join(ROOT, 'plugins/ptc-python'), name: 'ptc-python', command: process.env.PTC_PYTHON || null },
  typescript: { dir: path.join(ROOT, 'plugins/ptc-typescript'), name: 'ptc-typescript', command: process.env.PTC_NODE || null },
}[LANG];
if (!PLUGIN) throw new Error(`unknown --lang '${LANG}' (python|typescript)`);

// ── 供应商配置（只读密钥，不落盘不打印）────────────────────────────────────
function loadProvider() {
  const candidates = [
    process.env.PTC_LLM_CONFIG,
    path.join(os.homedir(), '.stand', 'nebflow.json'),
    path.join(os.homedir(), '.nebflow', 'nebflow.json'),
  ].filter(Boolean);
  for (const file of candidates) {
    if (!fs.existsSync(file)) continue;
    const cfg = JSON.parse(fs.readFileSync(file, 'utf8'));
    const p = cfg?.llm?.providers?.[PROVIDER];
    if (p?.baseUrl && p?.apiKey) return { baseUrl: p.baseUrl.replace(/\/$/, ''), apiKey: p.apiKey, source: file };
  }
  throw new Error(`provider '${PROVIDER}' not found (looked in: ${candidates.join(', ')})`);
}

async function chat(provider, body, attempt = 1) {
  const timeout = Number(process.env.PTC_LLM_TIMEOUT_MS || 300000);
  try {
    const res = await fetch(`${provider.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${provider.apiKey}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`LLM ${res.status}: ${text.slice(0, 300)}`);
    return JSON.parse(text);
  } catch (e) {
    if (attempt >= 3) throw e;
    process.stderr.write(`[closed-loop] LLM attempt ${attempt} failed (${e?.name}: ${e?.message?.slice(0, 120)}), retrying…\n`);
    return chat(provider, body, attempt + 1);
  }
}

// ── workspace ─────────────────────────────────────────────────────────────
const RUN_DIR = path.join(OUT, LANG);
fs.mkdirSync(RUN_DIR, { recursive: true });
const PLUGIN_DATA = path.join(RUN_DIR, 'plugin-data');
const WORKSPACE = path.join(PLUGIN_DATA, 'workspace');
const FIXTURES = {
  'notes/alpha.md': '# alpha\nalpha TODO one: 接通 PTC 反向通道\nbeta: 与 PTC 无关\n',
  'notes/beta.md': '# beta\ngamma TODO two: 把工具调用台账写进响应\ndelta: 与 PTC 无关\n',
  'notes/gamma.md': '# gamma\nepsilon TODO three: 给 py/ts 两插件同一份契约\n',
  'logs/ignore.log': 'no markdown here, but mentions TODO once\n',
};
function resetWorkspace() {
  fs.rmSync(WORKSPACE, { recursive: true, force: true });
  fs.mkdirSync(WORKSPACE, { recursive: true });
  for (const [rel, content] of Object.entries(FIXTURES)) {
    const full = path.join(WORKSPACE, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
  }
}

// ── 工具面（与引擎同一命名）───────────────────────────────────────────────
function toolDefs(pluginName, tools) {
  return tools.map((t) => ({
    type: 'function',
    function: {
      name: `mcp__plugin_${pluginName}_ptc__${t.name}`,
      description: t.description,
      parameters: t.inputSchema,
    },
  }));
}

function toolName(pluginName, t) { return `mcp__plugin_${pluginName}_ptc__${t}`; }

// ── 主流程 ─────────────────────────────────────────────────────────────────
const provider = loadProvider();
resetWorkspace();
const spec = serverSpec(PLUGIN.dir, 'ptc', { pluginData: PLUGIN_DATA, ...(PLUGIN.command ? { command: PLUGIN.command } : {}) });
const client = new McpStdioClient(spec, { label: PLUGIN.name });
const serverInfo = await client.initialize();
const tools = await client.listTools();
const promptDoc = fs.readFileSync(path.join(PLUGIN.dir, 'Plugin.md'), 'utf8');

const messages = [
  { role: 'system', content: `${promptDoc}\n\n---\n\n本会话暴露的工具名带 MCP 前缀：${toolName(PLUGIN.name, 'ptc_run')}（只读）、${toolName(PLUGIN.name, 'ptc_run_rw')}（读写）。工作目录 = 你的 workspace 根（当前目录）。` },
  {
    role: 'user',
    content: [
      'workspace 里有 notes/*.md 与 logs/ignore.log。请完成三件事：',
      '1) 统计所有 .md 文件里含 TODO 的行数、这些行分布在哪些文件；',
      '2) 在 workspace 根写 summary.json，内容为 {"todo_lines": <int>, "files": [<相对路径>...], "chars": <所有 .md 文件字符数合计>}；',
      '3) 用一句话回报结果。',
      '约束：用一次代码执行把「列文件 + 检索 + 读取 + 写汇总」串起来（不要逐个文件单独调工具）；只读档不允许写文件。',
    ].join('\n'),
  },
];

const turns = [];
let artifact = null;

for (let turn = 1; turn <= MAX_TURNS; turn += 1) {
  const startedAt = Date.now();
  process.stderr.write(`[closed-loop] ${LANG} turn ${turn}: calling ${MODEL}…\n`);
  const completion = await chat(provider, { model: MODEL, messages, tools: toolDefs(PLUGIN.name, tools), tool_choice: 'auto', max_tokens: 4000 });
  const choice = completion.choices?.[0];
  const msg = choice?.message ?? {};
  const durationMs = Date.now() - startedAt;
  const record = {
    turn, duration_ms: durationMs, finish_reason: choice?.finish_reason,
    usage: completion.usage ?? null,
    assistant: { content: msg.content ?? null, reasoning: msg.reasoning_content ?? null, tool_calls: [] },
    tool_executions: [],
  };

  if (msg.tool_calls?.length) {
    messages.push({ role: 'assistant', content: msg.content ?? '', tool_calls: msg.tool_calls });
    for (const call of msg.tool_calls) {
      const shortName = String(call.function?.name ?? '').replace(`mcp__plugin_${PLUGIN.name}_ptc__`, '');
      let args = {};
      let parseError = null;
      try { args = JSON.parse(call.function?.arguments ?? '{}'); } catch (e) { parseError = String(e.message); }
      const exec = { id: call.id, tool: shortName, name: call.function?.name, args, parse_error: parseError };
      record.assistant.tool_calls.push({ id: call.id, name: call.function?.name, arguments: args, parse_error: parseError });
      if (parseError) {
        exec.result_text = `Error: tool arguments were not valid JSON (${parseError})`;
        exec.result_json = null;
      } else {
        const t0 = Date.now();
        const r = await client.callTool(shortName, args);
        exec.duration_ms = Date.now() - t0;
        exec.result_text = r.text;
        exec.result_json = r.json;
        exec.is_error = r.isError;
        if (r.json && r.json.error) exec.error = r.json.error;
      }
      record.tool_executions.push(exec);
      messages.push({ role: 'tool', tool_call_id: call.id, content: exec.result_text });
    }
    turns.push(record);
    continue;
  }

  record.assistant.content = msg.content ?? '';
  turns.push(record);
  messages.push({ role: 'assistant', content: msg.content ?? '' });
  break;
}

await client.close();

const artifactPath = path.join(WORKSPACE, 'summary.json');
if (fs.existsSync(artifactPath)) {
  artifact = { path: artifactPath, content: JSON.parse(fs.readFileSync(artifactPath, 'utf8')) };
}

const toolLedgers = turns.flatMap((t) => t.tool_executions).flatMap((e) => e.result_json?.tool_calls ?? []);
const distinctTools = Array.from(new Set(toolLedgers.map((c) => c.tool)));
const final = turns[turns.length - 1]?.assistant?.content ?? null;
const verdict = {
  closed_loop: turns.length >= 2 && distinctTools.length >= 2,
  turns: turns.length,
  ptc_executions: turns.flatMap((t) => t.tool_executions).filter((e) => e.tool.startsWith('ptc_run')).length,
  distinct_tools_used_in_code: distinctTools,
  tool_calls_in_code: toolLedgers.length,
  artifact_written: Boolean(artifact),
  final_answer: final,
};

const record = {
  generated_at: new Date().toISOString(),
  language: LANG,
  plugin: PLUGIN.name,
  protocol: serverInfo?.protocolVersion,
  model: { provider: PROVIDER, name: MODEL, config_source: provider.source },
  tool_surface: tools.map((t) => toolName(PLUGIN.name, t.name)),
  wall_ms: turns.reduce((a, t) => a + t.duration_ms + t.tool_executions.reduce((b, e) => b + (e.duration_ms ?? 0), 0), 0),
  verdict,
  turns,
  artifact,
  conversation: messages,
};

fs.writeFileSync(path.join(RUN_DIR, 'conversation.json'), `${JSON.stringify(record, null, 2)}\n`);
if (artifact) fs.writeFileSync(path.join(RUN_DIR, 'summary.artifact.json'), `${JSON.stringify(artifact.content, null, 2)}\n`);

console.log(`[${LANG}] plugin=${PLUGIN.name} model=${PROVIDER}/${MODEL} protocol=${serverInfo?.protocolVersion}`);
console.log(`turns=${verdict.turns} ptc_executions=${verdict.ptc_executions} tools_in_code=${distinctTools.join(',')} calls=${verdict.tool_calls_in_code}`);
console.log(`artifact=${artifact ? JSON.stringify(artifact.content) : 'MISSING'}`);
console.log(`final=${String(final).slice(0, 200)}`);
console.log(`closed_loop=${verdict.closed_loop}`);
process.exit(verdict.closed_loop && artifact ? 0 : 1);
