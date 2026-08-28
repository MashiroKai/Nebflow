#!/usr/bin/env node
// smoke-websearch-p0.mjs — WebSearch P0 冒烟（2026-08-23）。
//
// 验收项（任务书 E1）：
//   1) 真实实例启动，/api/health ok（由外层启动脚本保证，此处复检）
//   2) zhipu OpenAI-兼容会话：WebSearch 路由 Tier 2 → 结果带
//      "provider:" 来源标注 + URL
//   3) qwen 会话：enable_search 冒烟 — 有证据=结构化来源；无证据=
//      降级 Tier 3 non-guaranteed（两条都是验收通过态，报告记录实态）
//   4) 无能力链头（deepseek/anthropic 协议）：直接 Tier 3 +
//      non-guaranteed 标注
//
// Run（隔离实例，默认 8097）:
//   NEBFLOW_URL=http://localhost:8097 NEBFLOW_HOME_DIR=<isolated home> \
//     node scripts/smoke-websearch-p0.mjs
//
// Env: SMOKE_AGENT（会话绑定的 agent 名，决定链头）；SMOKE_QUERY（搜索词）；
//      SMOKE_EXPECT（"provider:" | "non-guaranteed" | "any"）。

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const URL_BASE = process.env.NEBFLOW_URL || 'http://localhost:8097';
const HOME = process.env.NEBFLOW_HOME_DIR;
if (!HOME) {
  console.error('FAIL  NEBFLOW_HOME_DIR is required (isolated instance home)');
  process.exit(1);
}
const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
const AGENT = process.env.SMOKE_AGENT || 'WsProbe';
const QUERY = process.env.SMOKE_QUERY || '查询 Scala 3.5 语言的最新发布日期';
const EXPECT = process.env.SMOKE_EXPECT || 'any';

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

async function api(path, method = 'GET', body) {
  const res = await fetch(`${URL_BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      'content-type': 'application/json'
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* raw */ }
  return { status: res.status, text, json };
}

// ── health ─────────────────────────────────────────────────────────────
const health = await api('/api/health');
check('health ok', health.status === 200 && health.json?.status === 'ok',
  `status=${health.status}`);

// ── create session bound to the probe agent ────────────────────────────
const sess = await api('/api/sessions', 'POST', { name: `smoke-${AGENT}`, agentName: AGENT });
const sid = sess.json?.id;
check('session created', !!sid && sess.status === 200, `agent=${AGENT} sid=${sid}`);
if (!sid) {
  console.log(`# FAILED: ${failed} (no session)`);
  process.exit(1);
}

// ── run one real turn (blocking, 180s) ─────────────────────────────────
const t0 = Date.now();
const turn = await api(`/api/sessions/${sid}/turn`, 'POST', {
  content: `${QUERY}。请使用 WebSearch 工具。`,
  timeoutSec: 180
});
check('turn completed', turn.status === 200 && turn.json?.status === 'completed',
  `status=${turn.json?.status} ${turn.json?.durationMs ?? ''}ms (wall ${Date.now() - t0}ms)`);

// ── pull session history and find the WebSearch tool result ────────────
const hist = await api(`/api/sessions/${sid}/history`);
const histStr = JSON.stringify(hist.json ?? hist.text);
const sawWebSearch = histStr.includes('WebSearch');
check('WebSearch tool appeared in history', sawWebSearch);

const hasProviderProvenance = /provider:[a-z0-9-]+/.test(histStr);
const hasNonGuaranteed = histStr.includes('non-guaranteed');
const hasUrl = /https?:\/\/[^\\s"]+/.test(histStr);

if (EXPECT === 'provider:') {
  check('result carries provider provenance', hasProviderProvenance,
    hasProviderProvenance ? (histStr.match(/provider:[a-z0-9-]+/) || [''])[0] : 'missing');
  check('result carries source URL', hasUrl);
} else if (EXPECT === 'non-guaranteed') {
  check('result annotated non-guaranteed (Tier 3)', hasNonGuaranteed);
  check('NO provider provenance leaked into Tier 3 path', !hasProviderProvenance);
} else {
  // E1 nuance: BOTH outcomes are passing states — record which one happened.
  if (hasProviderProvenance) {
    console.log('EVIDENCE  provider-native search produced structured evidence:',
      (histStr.match(/provider:[a-z0-9-]+/) || [''])[0]);
  } else if (hasNonGuaranteed) {
    console.log('EVIDENCE  degraded to Tier 3 builtin aggregation (documented accepted outcome)');
  } else {
    check('either provider evidence OR non-guaranteed annotation present', false,
      'history carries neither marker');
  }
}

console.log(failed === 0 ? '# ALL PASS' : `# FAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);
