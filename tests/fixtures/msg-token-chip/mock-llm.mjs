#!/usr/bin/env node
// mock-llm.mjs — msgchip 批真实环验收（作者令 2026-11-01「消息身份牌系统升级」验收①）
// 的 OpenAI 兼容流式 mock。
//
// 与 askuser-refresh/mock-llm.mjs 同形（OpenAI /v1/chat/completions SSE），状态机单一：
// 每个用户轮 → 读 `<home>/sessions/*/msg-tokens.jsonl` 的**最新铸牌行**，回复正文引用
// 该牌的**带短名形态** `{{msg:<core>|<短名>}}`（短名按铸牌序轮换：输入统一令/迁移三裁定/
// 验收口径），首条另附一枚旧裸形态对照。铸牌先于 pipeLlmCall（AgentIdle 同缝）⇒ LLM 请求
// 到达时牌必已在册；jsonl 缺席/无行 = 回复纯文本（链路仍通）。
//
// 用法：node mock-llm.mjs <port> <fixtureHome>
import http from 'node:http';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const PORT = Number(process.argv[2] || 18990);
const HOME = process.argv[3] || '';

const NAMES = ['输入统一令', '迁移三裁定', '验收口径'];

/** 最新铸牌行（engine 铸牌先于 LLM 调用 ⇒ 到达本 mock 时已在册）。 */
function latestToken() {
  try {
    const dir = join(HOME, 'sessions');
    if (!HOME || !existsSync(dir)) return null;
    let best = null;
    for (const sid of readdirSync(dir)) {
      const f = join(dir, sid, 'msg-tokens.jsonl');
      if (!existsSync(f)) continue;
      const lines = readFileSync(f, 'utf8').split('\n').filter(l => l.trim());
      for (const line of lines) {
        try {
          const m = JSON.parse(line);
          if (m && typeof m.token === 'string' && (!best || (m.seq || 0) > (best.seq || 0))) best = m;
        } catch { /* 坏行容忍与引擎同读数 */ }
      }
    }
    return best;
  } catch {
    return null;
  }
}

function replyText(mintCount) {
  const t = latestToken();
  const core = t ? t.token.replace(/^\{\{msg:/, '').replace(/\}\}$/, '') : '';
  const name = NAMES[Math.min(Math.max(mintCount - 1, 0), NAMES.length - 1)];
  if (!core) return 'ok（未见铸牌行）';
  if (mintCount <= 1) {
    return `收到，这条已记下并起了短名：{{msg:${core}|${name}}} —— 后续按这条执行；引用时点小牌可看原文。旧形态对照：{{msg:${core}}}`;
  }
  return `第 ${mintCount} 条也收到。逐条引牌：{{msg:${core}|${name}}} —— 要点已入档，不整段复述原文。`;
}

// 每会话铸牌计数（内存态：按 messages 里 user 轮数量近似，避免与引擎序号耦合）。
function userTurns(messages) {
  return messages.filter(m => m && m.role === 'user').length;
}

function sse(res, text) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
  const chunk = (obj) => `data: ${JSON.stringify(obj)}\n\n`;
  res.write(chunk({ id: 'c1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant' } }] }));
  // 按字符分片（小片流式，模拟真流）
  const pieces = text.match(/[\s\S]{1,24}/g) || [];
  for (const p of pieces) {
    res.write(chunk({ id: 'c1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content: p } }] }));
  }
  res.write(chunk({ id: 'c1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }));
  res.write('data: [DONE]\n\n');
  res.end();
}

http.createServer((req, res) => {
  if (!req.url || !req.url.includes('/chat/completions')) {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found' }));
    return;
  }
  let body = '';
  req.on('data', (d) => { body += d; });
  req.on('end', () => {
    try {
      const j = JSON.parse(body || '{}');
      const messages = Array.isArray(j.messages) ? j.messages : [];
      sse(res, replyText(userTurns(messages)));
    } catch {
      sse(res, 'ok');
    }
  });
}).listen(PORT, '127.0.0.1', () => console.log(`[mock-llm] listening on 127.0.0.1:${PORT} (home=${HOME || 'unset'})`));
