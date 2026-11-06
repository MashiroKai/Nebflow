#!/usr/bin/env node
// reinject-llm.mjs — 隔离实例 nebflow.json 的 llm.providers 复注（幂等，msgchip 批
// 2026-11-01）。实拍批观察到：provision 写入的 llm 节在隔离实例首启后消失（boot 读到
// 空 providers ⇒ "No LLM provider configured"），此处按 fixture-home provision 同形
// 逐字段补写（MOCK_PORT 同源），供 runner 在 provision 之后、java 启动之前调用。
//
// 用法：node reinject-llm.mjs <nebflow.json 绝对路径> <mockPort>
import { readFileSync, writeFileSync } from 'node:fs';

const [path, portArg] = process.argv.slice(2);
const port = Number(portArg || 18990);
if (!path) { console.error('usage: node reinject-llm.mjs <nebflow.json> <mockPort>'); process.exit(2); }

const d = JSON.parse(readFileSync(path, 'utf8'));
d.workSchedule = { enabled: false };
d.safety = { defaultMode: 'auto-all' };
d.llm = {
  providers: {
    mock: {
      baseUrl: `http://127.0.0.1:${port}/v1/`,
      apiKey: 'sk-fixture-stub',
      protocol: 'openai',
      models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }],
    },
  },
};
writeFileSync(path, JSON.stringify(d, null, 2) + '\n');
console.log('[reinject] llm.providers:', Object.keys(d.llm.providers).join(','), '→', d.llm.providers.mock.baseUrl);
