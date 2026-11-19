#!/usr/bin/env node
// mock-llm.mjs — OpenAI 兼容流式 mock，供「真实例 · 真 canvas」证据取证使用。
//
// 目的：让**真引擎**（真路由 / 真数据 / 真工具执行）在无外部 LLM 的前提下跑出一个
// 真实的 ask 编辑回合，从而在 canvas 里自然长出真实的 `askreview` 审阅页签。
//
// 状态机（按请求 messages 的最后一条判定）：
//   last role === 'tool'  且其后无 assistant        → 终答 "MOCK_DONE(<tool result 摘要>)"
//   last user 含 'do ask edit now'                  → 工具调用 ask_edit（把 TARGET 文件里
//                                                     OLD 串替换为 NEW 串 —— 真引擎执行，
//                                                     真落盘 + 真 askEditApplied 帧）
//   其他                                             → 纯文本 "ok"
//
// 端口取 argv[2]（缺省 18990）。仅监听 127.0.0.1。stdin 'quit' ⇒ 优雅关闭。
import http from 'node:http';

const PORT = Number(process.argv[2] || 18990);
// 取证批需要**多个可审块**（才能在同一回合里演「逐块接受 / 逐块拒绝 / 全部接受」）。
// 一个 ask_edit 的两处改动若隔 ≥4 行未变（组件的 collapseUnchanged.minSize=4），
// 组件就把它算作**两个 hunk** ⇒ 一次 ask 回合即得两个可审块。
// 两相（phase）按用户文本区分：'phase2' → 第二相，否则第一相。
const PHASES = (() => {
  try {
    const raw = process.env.MOCK_ASK_PHASES;
    if (raw) { const j = JSON.parse(raw); if (j && Array.isArray(j.p1) && Array.isArray(j.p2)) return j; }
  } catch { /* fall through to default */ }
  return {
    // 行距：beta(2) … iota(9) 之间隔 6 行未变 ⇒ 两个可审块。
    p1: [{ old: 'beta\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\niota', new: 'BETA-REVIEWED\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\nIOTA-REVIEWED' }],
    // 行距：kappa(10) … rho(17) 之间隔 6 行未变 ⇒ 两个可审块（与第一相行段不相交）。
    p2: [{ old: 'kappa\nlambda\nmu\nnu\nxi\nomicron\npi\nrho', new: 'KAPPA-REVIEWED\nlambda\nmu\nnu\nxi\nomicron\npi\nRHO-REVIEWED' }],
  };
})();

function msgText(m) {
  const c = m?.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('\n');
  return '';
}

function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === 'user') return msgText(messages[i]);
  }
  return '';
}

// Bound-file absolute path — the ask system prompt renders it verbatim
// ("当前文件绝对路径：<abs>"); the real engine requires ask_edit to carry it.
function boundPath(messages) {
  for (const m of messages) {
    const t = msgText(m);
    const hit = t.match(/当前文件绝对路径[：:]\s*(\S+)/);
    if (hit) return hit[1];
  }
  return process.env.MOCK_ASK_PATH || '';
}

function decide(messages) {
  let lastToolIdx = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === 'tool') { lastToolIdx = i; break; }
  }
  if (lastToolIdx >= 0) {
    const answered = messages.slice(lastToolIdx + 1).some((m) => m?.role === 'assistant');
    if (!answered) return { kind: 'final', text: 'MOCK_DONE(' + msgText(messages[lastToolIdx]).slice(0, 60) + ')' };
  }
  const u = lastUserText(messages);
  if (u.includes('delegate now')) return { kind: 'delegate' };
  if (u.includes('do ask edit now')) {
    return { kind: 'ask-edit', path: boundPath(messages), edits: u.includes('phase2') ? PHASES.p2 : PHASES.p1 };
  }
  return { kind: 'ok' };
}

function chunk(delta, finish) {
  return 'data: ' + JSON.stringify({
    id: 'chatcmpl-mock', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
    model: 'mock-1', choices: [{ index: 0, delta, finish_reason: finish ?? null }],
  }) + '\n\n';
}

function streamFor(decision) {
  if (decision.kind === 'delegate') {
    // 真派发：引擎收到这个工具调用后真的起一次 delegate 批次（真会话/真工具/
    // 真终态 → 真 `fileChanges` 载荷）。
    const args = JSON.stringify({
      task: 'explicitly report the workspace state of real-canvas-demo, then finish (evidence batch).',
      project: process.env.MOCK_DELEGATE_PROJECT || 'real-canvas-demo',
      name: '真委托批',
    });
    return [
      chunk({ role: 'assistant' }),
      chunk({ tool_calls: [{ index: 0, id: 'call_mock_delegate_1', type: 'function', function: { name: 'Delegate', arguments: args } }] }),
      chunk({}, 'tool_calls'),
      'data: [DONE]\n\n',
    ].join('');
  }
  if (decision.kind === 'ask-edit') {
    const calls = decision.edits.map((e, i) => ({
      index: i, id: 'call_mock_edit_' + i, type: 'function',
      function: { name: 'ask_edit', arguments: JSON.stringify({ path: decision.path || '', old_str: e.old, new_str: e.new, replace_all: false }) },
    }));
    return [
      chunk({ role: 'assistant' }),
      chunk({ tool_calls: calls }),
      chunk({}, 'tool_calls'),
      'data: [DONE]\n\n',
    ].join('');
  }
  const text = decision.kind === 'final' ? decision.text : 'ok';
  return [chunk({ role: 'assistant', content: '' }), chunk({ content: text }), chunk({}, 'stop'), 'data: [DONE]\n\n'].join('');
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      let messages = [];
      try { messages = JSON.parse(body).messages || []; } catch { /* tolerate */ }
      const d = decide(messages);
      console.log(`[mock] n=${messages.length} lastRole=${messages[messages.length - 1]?.role} lastUser=${JSON.stringify(lastUserText(messages)).slice(0, 60)} decision=${d.kind}${d.path ? ' path=' + d.path : ''}`);
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
      res.end(streamFor(d));
    });
    return;
  }
  res.writeHead(404).end();
});

server.listen(PORT, '127.0.0.1', () => console.log(`mock-llm on 127.0.0.1:${PORT}`));
process.stdin.setEncoding('utf8');
process.stdin.on('data', (data) => { if (data.trim() === 'quit') server.close(() => process.exit(0)); });
