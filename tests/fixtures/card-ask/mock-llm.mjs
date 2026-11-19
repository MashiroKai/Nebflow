#!/usr/bin/env node
// card-ask/mock-llm.mjs — OpenAI-compatible streaming mock for the
// card-tool-revive LIVE acceptance (isolated engine, zero production touch).
//
// State machine (decided from the last messages of each request):
//   last role === 'tool'                          → final text "MOCK_DONE(<tool result head>)"
//   last user text contains 'CARD_NOW'            → tool_call  Card {html, title}
//   anything else                                 → plain text "ok"
//
// Only 127.0.0.1. Port = argv[2] (default 19490).
//
// Unlike the askuser fixture mock, this one deliberately calls the **Card**
// tool: the point of the live leg is that a REAL ask session executes the REAL
// revived CardTool through the REAL tool-dispatch path, and the REAL
// `___CARD_HTML___` payload comes back over the socket.
import http from 'node:http';

const PORT = Number(process.argv[2] || 19490);

/** A deterministic, self-contained card: a small table + a bar-ish figure. */
const CARD_HTML = [
  '<style>',
  '  :root { color-scheme: light dark; }',
  '  body { font: 14px/1.5 -apple-system, "Helvetica Neue", sans-serif; margin: 0; padding: 10px 12px; }',
  '  h3 { margin: 0 0 8px; font-size: 15px; }',
  '  table { border-collapse: collapse; width: 100%; }',
  '  th, td { border-bottom: 1px solid #8884; padding: 4px 6px; text-align: left; }',
  '  .bar { height: 9px; border-radius: 5px; background: #07c160; margin-top: 6px; }',
  '  .cap { opacity: .65; font-size: 12px; margin-top: 8px; }',
  '</style>',
  '<h3>CARD-LIVE-OK · 能量谱抽样</h3>',
  '<table>',
  '  <tr><th>抽样点</th><th>能量 (a.u.)</th></tr>',
  '  <tr><td>A</td><td>0.82</td></tr>',
  '  <tr><td>B</td><td>0.61</td></tr>',
  '  <tr><td>C</td><td>0.44</td></tr>',
  '</table>',
  '<div class="bar" style="width:82%"></div>',
  '<div class="cap">rendered by the revived Card tool inside a Canvas Ask session</div>',
].join('\n');

const CARD_ARGS = JSON.stringify({ html: CARD_HTML, title: 'CARD-LIVE-OK' });

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
  let lastToolIdx = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'tool') { lastToolIdx = i; break; }
  }
  if (lastToolIdx >= 0) {
    const answered = messages.slice(lastToolIdx + 1).some((m) => m.role === 'assistant');
    if (!answered) {
      const content = msgText(messages[lastToolIdx]);
      return { kind: 'final', text: 'MOCK_DONE(' + content.slice(0, 80) + ')' };
    }
  }
  if (lastUserText(messages).includes('CARD_NOW')) return { kind: 'card' };
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
  if (decision.kind === 'card') {
    return [
      chunk({ role: 'assistant' }),
      chunk({
        tool_calls: [{
          index: 0,
          id: 'call_card_mock_1',
          type: 'function',
          function: { name: 'Card', arguments: CARD_ARGS },
        }],
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
      let tools = [];
      try { const p = JSON.parse(body); messages = p.messages || []; tools = (p.tools || []).map((t) => t?.function?.name).filter(Boolean); } catch { /* tolerate */ }
      const last = messages[messages.length - 1] || {};
      const d = decide(messages);
      console.log(`[mock] n=${messages.length} lastRole=${last.role} lastUser=${JSON.stringify(lastUserText(messages)).slice(0, 80)} decision=${d.kind} tools=[${tools.join(',')}]`);
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
      res.end(streamFor(d));
    });
    return;
  }
  res.writeHead(404).end();
});

server.listen(PORT, '127.0.0.1', () => console.log(`[mock] card-ask mock-llm on 127.0.0.1:${PORT}`));
process.stdin.setEncoding('utf8');
process.stdin.on('data', (data) => {
  if (data.trim() === 'quit') server.close(() => process.exit(0));
});
