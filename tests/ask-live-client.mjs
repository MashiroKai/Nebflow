// ask-live-client.mjs — ask mode 三修① LIVE 验证客户端（真实发问→真实回复）。
// 对沙箱实例（--home /tmp/ask-live-home --port 8299）走真 WS 协议：
//   askOpen(file) → askState(回放) → askQuestion(真实问题) → textDelta* → done
// 全帧留档 /tmp/ask-live-frames.jsonl；回复全文 + 分段时延打 stdout。
import WebSocket from 'ws';
import { readFileSync, appendFileSync, rmSync } from 'node:fs';

const HOME = '/tmp/ask-live-home';
const FILE = '/tmp/ask-live-fixture/能量谱抽样说明.md';
const NAME = '能量谱抽样说明.md';
const QUESTION = '这份文档的核心结论是什么？请用一句话回答。';
const FRAMES = '/tmp/ask-live-frames.jsonl';
rmSync(FRAMES, { force: true });

const token = JSON.parse(readFileSync(`${HOME}/auth.json`, 'utf8'));
const ws = new WebSocket('ws://127.0.0.1:8299/ws', { headers: { Authorization: `Bearer ${token}` } });

const t0 = Date.now();
let sid = '';
let text = '';
let deltas = 0;
let doneAt = 0;
let stateAt = 0;
let finished = false;

const timer = setTimeout(() => {
  console.error('LIVE-TIMEOUT: 90s 内未走完 ask 链路');
  dumpAndExit(1);
}, 90000);

function dumpAndExit(code) {
  clearTimeout(timer);
  try { ws.close(); } catch { /* already */ }
  setTimeout(() => process.exit(code), 150);
}

function log(obj) { appendFileSync(FRAMES, JSON.stringify({ t: Date.now() - t0, ...obj }) + '\n'); }

ws.on('open', () => {
  console.log(`LIVE-OPEN +${Date.now() - t0}ms`);
  ws.send(JSON.stringify({ type: 'askOpen', file: { absPath: FILE, name: NAME }, mainSessionId: '' }));
  log({ dir: 'out', type: 'askOpen', file: FILE });
});

ws.on('message', (raw) => {
  let m; try { m = JSON.parse(raw.toString()); } catch { return; }
  log({ dir: 'in', type: m.type, sessionId: m.sessionId || '' });
  if (m.type === 'askState') {
    stateAt = Date.now();
    sid = m.sessionId;
    console.log(`LIVE-ASKSTATE +${stateAt - t0}ms sid=${sid} replayed=${(m.messages || []).length} msgs`);
    ws.send(JSON.stringify({ type: 'askQuestion', sessionId: sid, content: QUESTION, mainSessionId: '' }));
    log({ dir: 'out', type: 'askQuestion', sessionId: sid, content: QUESTION });
  } else if (m.type === 'textDelta') {
    deltas += 1;
    text += m.delta || '';
  } else if (m.type === 'done') {
    doneAt = Date.now();
    const finalText = m.finalText || text;
    console.log(`LIVE-DONE +${doneAt - t0}ms deltas=${deltas}`);
    console.log('LIVE-REPLY-BEGIN');
    console.log(finalText.trim().slice(0, 1200));
    console.log('LIVE-REPLY-END');
    console.log(`LIVE-TIMING askState=${stateAt - t0}ms done=${doneAt - t0}ms textDeltas=${deltas} replyChars=${finalText.length}`);
    finished = true;
    dumpAndExit(0);
  } else if (m.type === 'askError' || m.type === 'error') {
    console.error(`LIVE-ERROR: ${m.type}: ${m.message || '(no message)'}`);
    dumpAndExit(1);
  }
});

ws.on('error', (e) => { console.error('LIVE-WS-ERROR:', e.message); dumpAndExit(1); });
ws.on('close', () => { if (!finished) { console.error('LIVE-WS-CLOSED-early'); dumpAndExit(1); } });
