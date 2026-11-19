#!/usr/bin/env node
// card-ask/provision.mjs — isolated home + fixture for the card-tool-revive LIVE leg.
//
// Writes ONLY under $CARD_HOME (default /tmp/card-ask-live-home) and
// $CARD_FIXTURE_DIR (default /tmp/card-ask-live-fixture). Zero production
// touch: no signal, no read of the live data root; the engine that boots
// against it is a separate process on its own port.
//
//   node tests/fixtures/card-ask/provision.mjs
import { mkdirSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const HOME = process.env.CARD_HOME || '/tmp/card-ask-live-home';
const FIXTURE_DIR = process.env.CARD_FIXTURE_DIR || '/tmp/card-ask-live-fixture';
const MOCK_PORT = Number(process.env.CARD_MOCK_PORT || 19490);
const FILE = join(FIXTURE_DIR, '能量谱抽样说明.md');

for (const dir of [HOME, FIXTURE_DIR]) {
  if (existsSync(dir)) rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
}

// Single mock provider (tests/fixtures/card-ask/mock-llm.mjs). safety.defaultMode=auto-all
// keeps the permission gate out of the way; workSchedule off keeps the freeze face quiet.
writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
  workSchedule: { enabled: false },
  safety: { defaultMode: 'auto-all' },
  llm: {
    providers: {
      mock: {
        baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`,
        apiKey: 'sk-card-live-stub',
        protocol: 'openai',
        models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }],
      },
    },
  },
}, null, 2) + '\n');

// Onboarding pre-skipped (the async overlay intercepts pointer events).
writeFileSync(join(HOME, 'onboarding.json'), JSON.stringify({ state: 'skipped' }) + '\n');

// The ask-bound document (ask accepts md/html/ebook/pdf).
writeFileSync(FILE, [
  '# 能量谱抽样说明',
  '',
  '本文档记录三个抽样点 A / B / C 的能量读数，用于卡片渲染验收。',
  '',
  '| 抽样点 | 能量 (a.u.) |',
  '| --- | --- |',
  '| A | 0.82 |',
  '| B | 0.61 |',
  '| C | 0.44 |',
  '',
  '结论：能量随抽样点位置单调下降。',
  '',
].join('\n'));

console.log(`[provision] HOME=${HOME} FIXTURE=${FILE} MOCK_PORT=${MOCK_PORT}`);
