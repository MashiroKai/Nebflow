#!/usr/bin/env node
// mock-llm.mjs — OpenAI 兼容流式 mock，供 askuser 刷新存活 E2E 使用。
// 状态机（按请求 messages 的最后一条判定）：
//   last role === 'tool'                                  → 终答 "MOCK_DONE(<tool result 摘要>)"
//   last user 消息含 'ask me now'                          → 工具调用 AskUserQuestion（两问：选项 + 开放）
//   其他（padding 消息 'pad-N' 等）                         → 纯文本 "ok"
// 端口取 argv[2]。仅监听 127.0.0.1。
import http from 'node:http';

const PORT = Number(process.argv[2] || 18990);

const ASK_ARGS = JSON.stringify({
  questions: [
    {
      question: 'E2E 刷新存活验证：请选择一个选项',
      options: [
        { label: 'alpha', description: '选项 A' },
        { label: 'beta', description: '选项 B' },
      ],
    },
    {
      question: 'E2E 第二问：确认执行？',
      options: [
        { label: 'yes', description: '执行' },
        { label: 'no', description: '不执行' },
      ],
    },
  ],
});

function msgText(m) {
  const c = m?.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('\n');
  return '';
}

function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'user') return msgText(messages[i]);
  }
  return '';
}

function decide(messages) {
  // 最后一个 tool result 之后没有 assistant 回应 → 该工具结果待处理 → 终答。
  // tools-complete 时 drain 的事件会与 tool result 同轮注入（末条是 inject
  // reminder），只看末条会漏判——答案在 context 里但永不出 MOCK_DONE。
  let lastToolIdx = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'tool') { lastToolIdx = i; break; }
  }
  if (lastToolIdx >= 0) {
    const answered = messages.slice(lastToolIdx + 1).some((m) => m.role === 'assistant');
    if (!answered) {
      const content = msgText(messages[lastToolIdx]);
      return { kind: 'final', text: 'MOCK_DONE(' + content.slice(0, 60) + ')' };
    }
  }
  if (lastUserText(messages).includes('ask me now')) return { kind: 'ask' };
  return { kind: 'ok' };
}

function chunk(delta, finish) {
  return (
    'data: ' +
    JSON.stringify({
      id: 'chatcmpl-mock',
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model: 'mock-1',
      choices: [{ index: 0, delta, finish_reason: finish ?? null }],
    }) +
    '\n\n'
  );
}

function streamFor(decision) {
  if (decision.kind === 'ask') {
    return [
      chunk({ role: 'assistant' }),
      chunk({
        tool_calls: [{ index: 0, id: 'call_mock_1', type: 'function', function: { name: 'AskUserQuestion', arguments: ASK_ARGS } }],
      }),
      chunk({}, 'tool_calls'),
      'data: [DONE]\n\n',
    ].join('');
  }
  const text = decision.kind === 'final' ? decision.text : 'ok';
  return [
    chunk({ role: 'assistant', content: '' }),
    chunk({ content: text }),
    chunk({}, 'stop'),
    'data: [DONE]\n\n',
  ].join('');
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      let messages = [];
      try { messages = JSON.parse(body).messages || []; } catch { /* tolerate */ }
      const last = messages[messages.length - 1] || {};
      console.log(`[mock] req: n=${messages.length} lastRole=${last.role} lastText=${JSON.stringify(msgText(last)).slice(0, 120)} lastUser=${JSON.stringify(lastUserText(messages)).slice(0, 120)} decision=${decide(messages).kind}`);
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      res.end(streamFor(decide(messages)));
    });
    return;
  }
  res.writeHead(404).end();
});

server.listen(PORT, '127.0.0.1', () => console.log(`mock-llm on 127.0.0.1:${PORT}`));
