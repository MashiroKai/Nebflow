#!/usr/bin/env node
/**
 * mock-llm —— 缺陷①（2026-11-02）复现/复验用的确定性假 LLM（OpenAI 兼容）。
 *
 * 缺陷①要做的事：造一个**慢响应**，证明插件自动分配腿不再 60 秒被掐、也不再静默
 * 降级成 plugins=[]。真模型（ustc/deepseek-v4-flash-ascend）不可控，所以这里把上游
 * 换成本地假模型：
 *
 *   - 请求体里出现分配器提示词标记（"Choose the capability packages"）⇒
 *     **延迟 ALLOC_DELAY_MS（缺省 70000ms）**后回 `{"plugins": ["ptc-python","ptc-javascript"]}`
 *     （旧 jar：sttp 60s 缺省 readTimeout 在这条非流式腿上掐断 ⇒ fail-open ⇒ plugins=[]）
 *   - 根会话的首个 turn ⇒ 回一个 `Delegate` 工具调用（把整条 delegate spawn 链路拉起来）
 *   - delegate 会话的首个 turn ⇒ 回一批工具调用（ptc_run_rw ×3 + Grep），覆盖
 *     ③④⑤⑥ 的现场证据；随后一个 turn 收尾
 *   - 其它 ⇒ 纯文本收尾
 *
 * 每个请求体与响应都逐条落 `requests.jsonl`（证据面：system prompt 里有没有
 * `<injected-plugins>` 块、工具结果原文、分配调用到达/返回时刻）。
 *
 *   node scripts/ptc/mock-llm.mjs --port 18124 --out D:/ptc-e2e/evidence/mock-llm
 */
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : dflt;
};
const PORT = Number(opt('port', 18124));
const OUT = opt('out', 'D:/ptc-e2e/evidence/mock-llm');
const ALLOC_DELAY_MS = Number(opt('alloc-delay', 70000));
const WORKSPACE = opt('workspace', ''); // ③ 目标工作区（delegate 自己的 worktree）
fs.mkdirSync(OUT, { recursive: true });
const LOG = path.join(OUT, 'requests.jsonl');

const seen = { delegateToolCall: false, delegateToolBatch: false };
const t0 = Date.now();
const stamp = () => Date.now() - t0;
const log = (obj) => fs.appendFileSync(LOG, JSON.stringify({ at: stamp(), ...obj }) + '\n');

const ALLOC_MARK = 'Choose the capability packages';
const textOf = (m) => {
  if (typeof m.content === 'string') return m.content;
  if (Array.isArray(m.content)) return m.content.map((b) => (typeof b?.text === 'string' ? b.text : '')).join('\n');
  return '';
};
const bodyOf = (req) => req.messages.map((m) => `${m.role}:${textOf(m)}`).join('\n');

function openAiMessage(content, toolCalls) {
  const msg = { role: 'assistant', content: content ?? null };
  if (toolCalls?.length) msg.tool_calls = toolCalls;
  return msg;
}

const toolCall = (id, name, inputArgs) => ({
  id,
  type: 'function',
  function: { name, arguments: JSON.stringify(inputArgs) },
});

/** ptc_run_rw 的 python/js 同一份编排（⑤ 双侧同用例的直接现场）。 */
const PTC_CODE = (fileOut) => `
const hits = tools.glob({ pattern: 'notes/**/*.md' });
console.log('glob:', JSON.stringify(hits.paths));
const rows = [];
for (const p of hits.paths) {
  const doc = tools.read({ path: p });
  const g = tools.grep({ pattern: 'TODO', path: p });
  rows.push({ path: p, chars: doc.text.length, todo: g.count });
}
tools.write({ path: '${fileOut}', content: JSON.stringify(rows, null, 2) });
({ files: rows.length, rows })
`;

const PTC_CODE_PY = (fileOut) => `
import json
hits = tools.glob({"pattern": "notes/**/*.md"})
rows = []
for p in hits["paths"]:
    doc = tools.read({"path": p})
    g = tools.grep({"pattern": "TODO", "path": p})
    rows.append({"path": p, "chars": len(doc["text"]), "todo": g["count"]})
tools.write({"path": "${fileOut}", "content": json.dumps(rows, indent=2)})
{"files": len(rows), "rows": rows}
`;

function toolNameFor(tools, suffix) {
  const t = (tools || []).find((x) => typeof x?.function?.name === 'string' && x.function.name.endsWith(suffix));
  return t?.function?.name ?? null;
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ status: 'mock-llm', delayMs: ALLOC_DELAY_MS, seen }));
    return;
  }
  let raw = '';
  req.on('data', (d) => { raw += d; });
  req.on('end', () => {
    let body = {};
    try { body = JSON.parse(raw); } catch { /* keep {} */ }
    const text = bodyOf(body);
    const isAlloc = text.includes(ALLOC_MARK);
    const tools = body.tools || [];
    // 会话身份判定不看自然语言（Nebula 的 system prompt 里到处是 "Delegate"）：
    // 根会话**有** Delegate 工具；delegate/subagent 面没有（kernel 面被摘除）。
    const hasDelegateTool = (tools || []).some((t) => t?.function?.name === 'Delegate');
    const isDelegate = !hasDelegateTool && tools.length > 0;
    const model = body.model ?? 'mock';
    const entry = { model, isAlloc, isDelegate, messages: body.messages?.length ?? 0, tools: tools.length };
    log({ ...entry, phase: 'request', textHead: text.slice(0, 2000) });

    const reply = (message, finish = 'stop') => {
      log({ phase: 'response', isAlloc, finish, message: message.content ?? '(tool_calls)' });
      // agent turn 走**流式**面（sendMessageStream），非流式 JSON 会被判 EmptyStream；
      // 分配腿走非流式。同一个假模型两面都要会，按 body.stream 分流。
      if (body.stream === true) {
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-cache',
          connection: 'keep-alive',
        });
        const chunk = (delta, finishReason = null) => `data: ${JSON.stringify({
          id: `mock-${stamp()}`,
          object: 'chat.completion.chunk',
          created: Math.floor(Date.now() / 1000),
          model,
          choices: [{ index: 0, delta, finish_reason: finishReason }],
        })}\n\n`;
        if (message.content) res.write(chunk({ role: 'assistant', content: message.content }));
        else res.write(chunk({ role: 'assistant', content: '' }));
        (message.tool_calls || []).forEach((tc, i) => {
          res.write(chunk({
            tool_calls: [{
              index: i,
              id: tc.id,
              type: 'function',
              function: { name: tc.function.name, arguments: tc.function.arguments },
            }],
          }));
        });
        res.write(chunk({}, finish));
        res.write('data: [DONE]\n\n');
        res.end();
        return;
      }
      const payload = {
        id: `mock-${stamp()}`,
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model,
        choices: [{ index: 0, message, finish_reason: finish }],
        usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
      };
      log({ phase: 'response', isAlloc, finish, message: message.content ?? '(tool_calls)' });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(payload));
    };

    if (isAlloc) {
      // 慢响应：缺陷① 复现点。旧实现 sttp 60s 缺省在这条非流式腿上掐断。
      log({ phase: 'alloc-delay-start', delayMs: ALLOC_DELAY_MS });
      setTimeout(() => {
        log({ phase: 'alloc-delay-end' });
        reply(openAiMessage('{"plugins":["ptc-python","ptc-javascript"]}'), 'stop');
      }, ALLOC_DELAY_MS);
      return;
    }

    if (!isDelegate && !seen.delegateToolCall && tools.length > 0) {
      const delegate = toolNameFor(tools, 'Delegate') || 'Delegate';
      seen.delegateToolCall = true;
      log({ phase: 'emit', tool: delegate });
      reply(
        openAiMessage('派一个 delegate 去跑 PTC 证据用例。', [
          toolCall('call-delegate-1', delegate, {
            task: '用 ptc-python 与 ptc-javascript 两个插件的 ptc_run_rw 在 notes/ 下各跑一遍同一份编排，互相校验；跑完原样报告工具结果。',
          }),
        ]),
        'tool_calls',
      );
      return;
    }

    if (isDelegate && !seen.delegateToolBatch) {
      const py = toolNameFor(tools, 'ptc_run_rw');
      const grep = toolNameFor(tools, 'Grep') || 'Grep';
      if (!py) {
        log({ phase: 'no-ptc-tool', note: 'plugin not allocated — plugins=[] (silent degradation)' });
        reply(openAiMessage('No PTC tool available; task finished with builtin tools.'));
        return;
      }
      seen.delegateToolBatch = true;
      const pyName = toolNameFor(tools, '_ptc_run_rw');
      const jsName = (tools || [])
        .map((t) => t?.function?.name)
        .filter((n) => typeof n === 'string' && n.endsWith('ptc_run_rw') && n.includes('javascript'))[0];
      const pyPlugin = (tools || [])
        .map((t) => t?.function?.name)
        .filter((n) => typeof n === 'string' && n.endsWith('ptc_run_rw') && n.includes('python'))[0];
      const rootArg = WORKSPACE ? { fs_roots: [WORKSPACE] } : {};
      const calls = [];
      if (pyPlugin) {
        calls.push(toolCall('call-py', pyPlugin, {
          code: PTC_CODE_PY('notes/ptc-python.json'),
          language: 'python',
          profile: 'readwrite',
          ...rootArg,
        }));
      }
      if (jsName) {
        calls.push(toolCall('call-js', jsName, {
          code: PTC_CODE('notes/ptc-js.json'),
          language: 'javascript',
          profile: 'readwrite',
          ...rootArg,
        }));
        // ④：类型注解必须被**明确拒绝**（本插件不转译），而不是含糊通过。
        calls.push(toolCall('call-ts-annot', jsName, {
          code: "const x: string = 'a';\n({ x })",
          language: 'javascript',
          profile: 'readonly',
          ...rootArg,
        }));
      }
      calls.push(toolCall('call-grep', grep, { pattern: 'TODO', path: WORKSPACE || '.' }));
      log({ phase: 'emit-batch', count: calls.length, py: pyPlugin, js: jsName, grep });
      reply(openAiMessage('跑 PTC 用例。', calls), 'tool_calls');
      return;
    }

    reply(openAiMessage('证据已收集，收尾。'));
  });
});

server.listen(PORT, '127.0.0.1', () => {
  log({ phase: 'listen', port: PORT, allocDelayMs: ALLOC_DELAY_MS, workspace: WORKSPACE });
  console.log(`[mock-llm] listening on http://127.0.0.1:${PORT} (alloc delay ${ALLOC_DELAY_MS}ms, out ${OUT})`);
});
