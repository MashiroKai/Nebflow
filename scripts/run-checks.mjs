#!/usr/bin/env node
// Shared checkout-only checks. Runtime, browser, JVM and installed-plugin gates stay separate.
import { spawn } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isAbsolute, resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const nodeCheck = (name, script, profiles = ['static', 'frontend']) => ({
  name, argv: [process.execPath, `scripts/${script}.mjs`], profiles, timeoutMs: name === 'js-types' ? 150_000 : 120_000,
});

const scriptTests = readdirSync(new URL('./tests/', import.meta.url))
  .filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/tests/${name}`);
if (!scriptTests.length) throw new Error('No script unit tests found; refusing broad test discovery');

// Membership is declared once here, not copied into Make or CI command lists.
export const CHECKS = [
  nodeCheck('css-structure', 'check-css-structure'),
  nodeCheck('floating-clearance', 'check-floating-clearance'),
  nodeCheck('floating-discovery', 'check-floating-discovery'),
  nodeCheck('mobile-floating-inventory', 'check-mobile-floating-inventory'),
  nodeCheck('design-tokens', 'check-design-tokens'),
  nodeCheck('message-style', 'check-msgstyle-single-source'),
  nodeCheck('chat-ui', 'check-chat-ui'),
  { name: 'chat-ui-computed', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/chat-ui-computed.mjs'] },
  { name: 'mobile-canvas-hit-clearance', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-canvas-hit-clearance.spec.mjs'] },
  // canvas 底部控件行「可见且可点」闸（作者报障 2026-11-18：左下两钮 + 中间三点被
  // 手机 dock 整片盖住）。既有 mobile-canvas-hit-clearance 主动跳过不可点控件、
  // 既有 mobile-canvas-toolbar-layout 长期红着没人当闸 ⇒ 这类缺陷在闸下不可见。
  { name: 'canvas-controls-dock-clearance', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/canvas-controls-dock-clearance.spec.mjs'] },
  { name: 'mobile-decision-preview-occlusion', profiles: ['frontend'], timeoutMs: 120_000,
    argv: [process.execPath, 'tests/mobile-decision-preview-occlusion.spec.mjs'] },
  { name: 'media-gallery', profiles: ['frontend'], timeoutMs: 120_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/mobile-image-gallery.spec.mjs', 'tests/media-click-unify.spec.mjs', '--workers=1'] },
  // 账户栏整件退役（作者令 2026-11-18）**渲染级**闸：真实形态（手机全屏设置页 +
  // 桌面设置页 + 个人主页）断言该栏零命中；源码级同判据 = check-panel-uniform。
  { name: 'settings-account-removed', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/settings-account-removed.mjs'] },
  nodeCheck('bubble-side', 'check-bubble-side'),
  nodeCheck('self-explaining', 'check-self-explaining'),
  nodeCheck('badge-words', 'check-badge-words'),
  nodeCheck('circular', 'check-circular'),
  nodeCheck('fixture-secrets', 'check-fixture-secrets'),
  nodeCheck('api-routes', 'check-api-routes'),
  nodeCheck('provider-panel', 'check-provider-panel'),
  nodeCheck('freeze-state', 'check-freeze-state'),
  nodeCheck('flat-buttons', 'check-flat-buttons'),
  nodeCheck('glass', 'check-glass-unify'),
  nodeCheck('freeze-silence', 'check-freeze-silence'),
  nodeCheck('freeze-replay', 'check-freeze-replay-silence'),
  nodeCheck('canvas-hint', 'check-canvas-hint-tokens'),
  nodeCheck('canvas-buttons', 'check-canvas-buttons'),
  // canvas 保存失败通知批（2026-11-18 P0「老是出现 canvas 保存失败的通知」）：
  // 静态腿钉「跨刷新去重 / 正文逐字 / 可执行下一步 / 可关闭」四条声明不变量。
  nodeCheck('canvas-save-notice', 'check-canvas-save-notice'),
  { name: 'canvas-save-failure-dedup', profiles: ['frontend'], timeoutMs: 180_000,
    argv: ['npm', 'exec', '--no', '--', 'playwright', 'test', 'tests/canvas-save-failure-dedup.spec.mjs', '--workers=1'] },
  { name: 'canvas-button-geom', profiles: ['frontend'], timeoutMs: 180_000,
    argv: [process.execPath, 'tests/canvas-button-unify-geom.spec.mjs'] },
  { name: 'canvas-button-diff', profiles: ['frontend'], timeoutMs: 240_000,
    argv: [process.execPath, 'tests/canvas-button-unify-diff.spec.mjs'] },
  nodeCheck('canvas-families', 'check-canvas-families', ['static']),
  nodeCheck('toggle', 'check-toggle-unify'),
  nodeCheck('scroll-pill', 'check-scroll-pill-center'),
  nodeCheck('input-surface', 'check-input-surface'),
  nodeCheck('dropdown-single-control', 'check-dropdown-single-control'),
  nodeCheck('mobile-canvas-strip', 'check-mobile-canvas-strip'),
  nodeCheck('panel-unify', 'check-panel-unify'),
  nodeCheck('settings-ios-rows', 'check-settings-ios-rows'),
  // 陈旧面板行批（2026-11-18）：后台智能体面板「活跃行 ⊆ 引擎活跃快照」不变量——
  // durable store 的重启打断行（pending-recovery）曾被折成「进行中」+ 690h 时钟。
  nodeCheck('bg-panel-active-liveness', 'check-bg-panel-active-liveness'),
  // 附件持久化批（2026-11-18 P0「我发送出去的文件过一会还是会出现这种失效的问题」）：
  // 用户附件落盘身份 / 读取面授权键 / attJson 延迟求值 / footer 下载键四条链的源码闸
  // （端到端回归见 tests/attachment-persistence.spec.mjs，需真实例）。
  nodeCheck('attachment-persistence', 'check-attachment-persistence'),
  // 设置页结构治理批（2026-11-18）：栏目名去重（section 头 vs 卡内标题逐对核）+
  // 按钮形态统一（退出 / 后台智能体恢复·停 登记进设置页动作族；按钮规则禁字面量几何）。
  nodeCheck('settings-section-dedupe', 'check-settings-section-dedupe', ['static']),
  nodeCheck('settings-button-uniform', 'check-settings-button-uniform', ['static']),
  ...[
    ['js-types', 'check-js-types'], ['ime', 'check-ime-guard'],
    ['component-contract', 'check-component-contract'], ['takeover-identify', 'check-takeover-identify'],
    ['design-spec', 'check-design-spec-sync'], ['prompt-source', 'check-prompt-single-source'],
    ['prompt-imports', 'check-prompt-catalog-imports'], ['provider-models', 'check-provider-modellist'],
    ['local-coupling', 'check-local-coupling'], ['tag-format', 'check-tag-format'],
    ['evidence-manifests', 'check-evidence-manifests'], ['scala-layers', 'check-scala-layers'],
    ['dispatcher-seal', 'check-dispatcher-seal'],
    ['node-stuck-notify', 'check-node-stuck-notify'],
    ['delegate-mode-slot', 'check-delegate-mode-slot'],
    ['perm-presentation', 'check-perm-presentation'],
    ['permission-gate', 'check-permission-gate'],
  ].map(([name, script]) => nodeCheck(name, script, ['static'])),
  { name: 'tooling-lint', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, 'node_modules/eslint/bin/eslint.js', 'eslint.config.mjs',
      'scripts/check-js-types.mjs', 'scripts/run-checks.mjs', 'scripts/check-sdd.mjs', 'scripts/check-openspec.mjs', 'scripts/tests/'] },
  { name: 'script-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: [process.execPath, '--test', ...scriptTests] },
  { name: 'worktree-manifest', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/check-delivery.py', '--manifest-only'] },
  { name: 'cli-logo', profiles: ['static'], timeoutMs: 150_000,
    argv: ['python3', '-B', 'scripts/check-logo-matrix.py'] },
  { name: 'cli-logo-tests', profiles: ['static'], timeoutMs: 120_000,
    argv: ['python3', '-B', 'scripts/tests/test-cli-banner-payload.py'] },
  nodeCheck('sdd', 'check-sdd', ['static']),
  nodeCheck('sdd-changed', 'check-sdd', ['sdd-changed']),
];

export function selectChecks(profile, base, manifestTmp) {
  if (!['static', 'frontend', 'sdd-changed'].includes(profile)) throw new Error(`Unknown profile: ${profile}`);
  if (profile === 'sdd-changed' && !base) throw new Error('sdd-changed requires an explicit --base');
  if (profile !== 'sdd-changed' && base) throw new Error('--base is only valid with sdd-changed');
  if (manifestTmp && (profile !== 'static' || !isAbsolute(manifestTmp))) throw new Error('--manifest-tmp requires an absolute path and the static profile');
  return CHECKS.filter(check => check.profiles.includes(profile)).map(check => ({
    ...check, argv: [...check.argv, ...(base ? ['--base', base] : []),
      ...(manifestTmp && check.name === 'worktree-manifest' ? ['--tmp', manifestTmp] : [])],
  }));
}

// Each POSIX child owns its process group; never signal the caller's group.
async function terminateTree(child) {
  if (!Number.isInteger(child?.pid) || child.pid <= 1 || child.pid === process.pid) return;
  if (process.platform !== 'win32') {
    try { process.kill(-child.pid, 'SIGKILL'); }
    catch (error) { if (error.code !== 'ESRCH') throw error; }
    return;
  }
  await new Promise((resolveCleanup, rejectCleanup) => {
    const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', shell: false });
    const timer = setTimeout(() => {
      killer.kill();
      rejectCleanup(new Error('Process-tree cleanup timed out'));
    }, 5000);
    killer.once('error', error => { clearTimeout(timer); rejectCleanup(error); });
    killer.once('close', code => {
      clearTimeout(timer);
      if (code === 0) resolveCleanup();
      else rejectCleanup(new Error(`Process-tree cleanup failed: taskkill exit ${code}`));
    });
  });
}

export async function runCheck(check, { cwd = ROOT, log = console.log } = {}) {
  if (!Array.isArray(check.argv) || !check.argv.length || !check.argv.every(arg => typeof arg === 'string') ||
      !Number.isFinite(check.timeoutMs) || check.timeoutMs <= 0) throw new Error('Invalid check declaration');
  const start = performance.now();
  log(`START ${check.name}`);
  return new Promise(resolveResult => {
    let timedOut = false;
    let interrupted;
    let failure;
    let child;
    let timer;
    let heartbeat;
    let finished = false;
    let stopping = false;
    let cleanup;
    const finish = async (code, signal) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      clearInterval(heartbeat);
      await cleanup;
      process.removeListener('SIGINT', onInterrupt);
      process.removeListener('SIGTERM', onTerminate);
      const elapsedMs = Math.round(performance.now() - start);
      const ok = !failure && !timedOut && !interrupted && !signal && code === 0;
      const reason = interrupted ?? (timedOut ? 'timeout' : failure ? failure.message : signal ? `signal ${signal}` : `exit ${code}`);
      log(`${ok ? 'PASS' : 'FAIL'} ${check.name} (${elapsedMs}ms; ${reason})`);
      resolveResult({ name: check.name, ok, code, signal, interrupted, timedOut, error: failure?.message, elapsedMs });
    };
    const stop = () => {
      if (stopping || finished) return;
      stopping = true;
      cleanup = terminateTree(child).catch(error => { failure = error; });
      // Do not wait forever for close, even if inherited descriptors stay open.
      void cleanup.then(() => finish(null, interrupted ?? 'SIGKILL'));
    };
    const onInterrupt = () => { interrupted = 'SIGINT'; stop(); };
    const onTerminate = () => { interrupted = 'SIGTERM'; stop(); };
    process.on('SIGINT', onInterrupt);
    process.on('SIGTERM', onTerminate);
    try {
      child = spawn(check.argv[0], check.argv.slice(1), {
        cwd, stdio: 'inherit', shell: false, detached: process.platform !== 'win32',
      });
    } catch (error) {
      failure = error;
      void finish(null, null);
      return;
    }
    child.once('error', error => { failure = error; });
    child.once('close', (code, signal) => { void finish(code, signal); });
    timer = setTimeout(() => { timedOut = true; stop(); }, check.timeoutMs);
    heartbeat = setInterval(() => log(`RUNNING ${check.name} (${Math.round(performance.now() - start)}ms)`), 15_000);
  });
}

export async function main(args = process.argv.slice(2)) {
  let profile = 'static';
  let base;
  let manifestTmp;
  let list = false;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--list' || arg === '--dry-run') list = true;
    else if (arg === '--profile' || arg === '--base' || arg === '--manifest-tmp') {
      const value = args[++index];
      if (!value || value.startsWith('--')) throw new Error(`Missing value for ${arg}`);
      if (arg === '--profile') profile = value;
      else if (arg === '--base') base = value;
      else manifestTmp = value;
    } else throw new Error(`Unknown argument: ${arg}`);
  }
  const checks = selectChecks(profile, base, manifestTmp);
  if (list) {
    for (const check of checks) console.log(`${check.name}: ${JSON.stringify(check.argv)} (timeout ${check.timeoutMs}ms)`);
    return 0;
  }
  let failures = 0;
  for (const check of checks) {
    const result = await runCheck(check);
    if (result.interrupted) return result.interrupted === 'SIGINT' ? 130 : 143;
    if (!result.ok) failures++;
  }
  console.log(`${profile}: ${checks.length - failures}/${checks.length} checks passed`);
  return failures ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(code => { process.exitCode = code; }).catch(error => {
    console.error(`Checks ERROR: ${error.message}`);
    process.exitCode = 2;
  });
}
