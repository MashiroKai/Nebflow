#!/usr/bin/env node
// fixture-home.mjs — `tests/askuser-refresh-survive.spec.mjs` 的 **NEBFLOW_HOME 隔离夹具** provisioner。
//
// 动因（#548 e2e 夹具小批）：件 2 `tests/askuser-refresh-survive.spec.mjs` 不自起 gateway，需外部已
// provisioned 的 `NEBFLOW_HOME`（LLM 端点指向同目录的 `mock-llm.mjs`）+ `BASE_URL`/`TOKEN` 注入；
// 本仓此前只有 `tests/fixtures/askuser-refresh/mock-llm.mjs`，**没有** home provisioner ⇒ 该 spec 结构性跑不起来。
// 形态出处 = `.nebflow/evidence/20260912_r2-304-impl/fixture-home-r2e2e.mjs`（同形先例）
//          + `scripts/e2e-askuser-source-label.mjs` 的 fixture 构建段（onboarding 预跳过 = QA 既有教训）。
//
// 🔴 隔离与写根纪律：
//   * **只写 `$NEBFLOW_HOME_DIR` 之下**（本脚本的**唯一**写入面；装配前过 `writeguard` 写根断言，
//     越界即 fail-fast 非零退出；装配中的幂等重置过 `delguard` 删除守卫）。
//   * **零 repo 写、零宿主数据根（`~/.nebflow`）写**：agent 定义取**本仓** `src/main/resources/seed/agents/**`
//     （repo-tracked ⇒ 装配面可重复、不随宿主配置漂移），`Nebula` 面由本脚本**合成**最小定义。
//   * 输出目录 = 调用方给的既有目录（本件无任何硬编码绝对输出路径）。
//
// 用法：
//   NEBFLOW_HOME_DIR=<隔离 home 绝对路径> [MOCK_PORT=19000] node tests/fixtures/askuser-refresh/fixture-home.mjs [--provision|--manifest|--teardown]
//     默认 --provision：幂等重置 + 装配 + 打印 manifest sha（连跑两次 sha 必一致）
//     --manifest     ：只重算已有 home 的 manifest（不写、不删）
//     --teardown     ：删除 home（过 delguard 断言）+ 打印零残留读数
//   环境：FIXTURE_PROJECT（默认 qa-e2eask-refresh）、FIXTURE_EPOCH（默认 1757000000000 —— 固定时间戳 ⇒ 装配面确定性）
//   🔴 负控（无需起实例，只跑断言）：
//     NEBFLOW_HOME_DIR=~/.nebflow/docs/x NB_DRY_RUN=1 node tests/fixtures/askuser-refresh/fixture-home.mjs   # 期望 rc=3（写根拒绝）

import { mkdirSync, writeFileSync, readFileSync, cpSync, existsSync, readdirSync, statSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, relative, dirname } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { guardWrites } from '../../../scripts/lib/writeguard.mjs';
import { assertCleanable } from '../../../scripts/lib/delguard.mjs';

const REPO = dirname(dirname(dirname(dirname(fileURLToPath(import.meta.url))))); // <repo>/tests/fixtures/askuser-refresh/x.mjs → <repo>
const HOME = process.env.NEBFLOW_HOME_DIR;
const MOCK_PORT = Number(process.env.MOCK_PORT || 19000);
const PROJECT = process.env.FIXTURE_PROJECT || 'qa-e2eask-refresh';
const EPOCH = Number(process.env.FIXTURE_EPOCH || 1757000000000);
const SEED_AGENTS = join(REPO, 'src/main/resources/seed/agents');
// 夹具内 agent 面：seed 有的取 seed（repo-tracked ⇒ 确定性）；`Nebula` 只有宿主面 ⇒ 合成最小定义。
// 🔴 2026-09-21（chain-askuserdup 批）：原清单含 `kernel`，而该 seed agent 已于
// `e50b885e7`（promptopt W1，seed 4 → 3）退役 ⇒ 本 provisioner 自那之后**结构性跑不起来**
// （`seed agent missing` 非零退出）。按现读 seed 面收敛（general / project-dispatcher），
// 与退役后的 seed 树逐条对齐。
const AGENTS_FROM_SEED = ['general', 'project-dispatcher'];
const MODE = process.argv[2] && process.argv[2].startsWith('--') ? process.argv[2] : '--provision';

if (!HOME) { console.error('[fixture] NEBFLOW_HOME_DIR required'); process.exit(1); }
// 🔴 前置硬断言：进程 `$HOME` 不得指向夹具内 —— 否则
//   ① delguard 会把夹具 home 当成「宿主 home 根」而拒绝任何删除（拆装不可重复）；
//   ② 夹具内的子进程（gateway / playwright）会去夹具里找 `~/Library/Caches/ms-playwright`（浏览器缓存找不到）。
if (process.env.HOME && (process.env.HOME === HOME || process.env.HOME.startsWith(HOME.endsWith('/') ? HOME : HOME + '/'))) {
  console.error(`[fixture] ABORT: 进程 $HOME=${process.env.HOME} 指向夹具 home（${HOME}）—— 请以真实宿主 HOME 运行本件`);
  process.exit(6);
}

// ── 写根断言（先于任何写入；越界 → stderr + exit 3）────────────────────────┬──
guardWrites([HOME], { repoRoot: REPO, label: 'fixture-home/askuser-refresh' });
// ────────────────────────────────────────────────────────────────────────┘

const WS = join(HOME, `ws-${PROJECT}`);
const PROJ_DIR = join(HOME, 'projects', PROJECT);

function sha256(p) {
  return createHash('sha256').update(readFileSync(p)).digest('hex');
}

/** 装配面清单：排序后的 `<relpath> <bytes> <sha256>` 行 + 全清单 sha（确定性判据）。 */
function manifest() {
  const rows = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir).sort()) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else rows.push(`${relative(HOME, p)} ${st.size} ${sha256(p)}`);
    }
  };
  walk(HOME);
  rows.sort();
  const text = rows.join('\n') + '\n';
  return { rows, sha: createHash('sha256').update(text).digest('hex'), text };
}

function report(m, label) {
  console.log(`[fixture] ${label} MANIFEST_SHA=${m.sha} files=${m.rows.length}`);
  for (const r of m.rows) console.log(`[fixture]   ${r}`);
}

function provision() {
  // 幂等重置：装配面 =「重建」而非「数据清理」（同 `e2e-askuser-source-label.mjs` buildFixture 口径），
  // 故不受 delguard 的 CLEAN 开关约束，但**必须**过删除守卫（路径解析 + 允许根/黑名单断言）。
  assertCleanable(HOME, { label: 'fixture-home/askuser-refresh/reprovision' });
  rmSync(HOME, { recursive: true, force: true });
  mkdirSync(HOME, { recursive: true });

  // 1) LLM provider → 同目录 mock（tests/fixtures/askuser-refresh/mock-llm.mjs，状态机见其头注）
  //    workSchedule.enabled=false：免冻结调度面干扰；safety.defaultMode=auto-all：免权限闸挡住 REST turn
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    workSchedule: { enabled: false },
    safety: { defaultMode: 'auto-all' },
    llm: {
      providers: {
        mock: {
          baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`,
          apiKey: 'sk-fixture-stub',
          protocol: 'openai',
          models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }],
        },
      },
    },
  }, null, 2) + '\n');

  // 2) onboarding 预跳过（异步遮罩会拦截指针事件 —— QA 既有教训）
  writeFileSync(join(HOME, 'onboarding.json'), JSON.stringify({ state: 'skipped' }) + '\n');

  // 3) agent 定义
  for (const a of AGENTS_FROM_SEED) {
    const src = join(SEED_AGENTS, a);
    if (!existsSync(src)) { console.error(`[fixture] seed agent missing: ${src}`); process.exit(4); }
    mkdirSync(join(HOME, 'agents', a), { recursive: true });
    for (const f of ['agent.json', 'system.md']) {
      if (existsSync(join(src, f))) cpSync(join(src, f), join(HOME, 'agents', a, f));
    }
  }
  // `Nebula`：spec 经 `POST /api/callbacks/inject {agent:'Nebula'}` 注入 ExternalEvent（宿主面才有该定义）
  // ⇒ 夹具内合成最小定义（无宿主读 ⇒ 装配面可重复）
  mkdirSync(join(HOME, 'agents', 'Nebula'), { recursive: true });
  writeFileSync(join(HOME, 'agents', 'Nebula', 'agent.json'), JSON.stringify({
    name: 'Nebula', displayName: 'Nebula', preset: 'general', skills: [],
    description: 'fixture-local minimal Nebula def (askuser-refresh e2e)',
  }, null, 2) + '\n');
  writeFileSync(join(HOME, 'agents', 'Nebula', 'system.md'), '# Nebula (fixture)\n\nE2E fixture agent definition.\n');

  // 4) 一个挂载项目 + 工作区（spec 经 REST 建会话；项目面供 agent/工具面可解析）
  mkdirSync(join(WS, '.nebflow'), { recursive: true });
  writeFileSync(join(WS, 'AGENTS.md'), `# ${PROJECT}\n\naskuser-refresh e2e fixture workspace.\n`);
  mkdirSync(PROJ_DIR, { recursive: true });
  writeFileSync(join(PROJ_DIR, 'project.json'), JSON.stringify({
    name: PROJECT, workspace: WS, agentFile: join(WS, 'AGENTS.md'), createdAt: EPOCH,
  }, null, 2) + '\n');
}

function teardown() {
  assertCleanable(HOME, { label: 'fixture-home/askuser-refresh/teardown' });
  rmSync(HOME, { recursive: true, force: true });
  console.log(`[fixture] torn down HOME=${HOME} exists=${existsSync(HOME)}`);
}

if (MODE === '--provision') {
  provision();
  report(manifest(), 'provision');
} else if (MODE === '--manifest') {
  if (!existsSync(HOME)) { console.error(`[fixture] HOME not provisioned: ${HOME}`); process.exit(5); }
  report(manifest(), 'manifest');
} else if (MODE === '--teardown') {
  teardown();
} else {
  console.error(`[fixture] unknown mode ${MODE}（--provision|--manifest|--teardown）`);
  process.exit(64);
}
