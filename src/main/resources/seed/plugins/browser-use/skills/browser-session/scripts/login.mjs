#!/usr/bin/env node
// login.mjs — 人在环登录流程（browser-session skill）
//
// 流程：detect（打开登录入口，识别登录页/验证码/反爬，落截图与判据）→
//       【人环】用户在可见 Chrome 窗口里人工完成验证码/2FA（会话发出 AskUserQuestion 提示）→
//       wait（轮询登录成功判据：URL 正则 / 元素选择器 / 弱启发式）→ LOGIN_OK。
//
// 判据优先序：CLI 旗标 > ~/.nebflow/browser-use 下 config/login-rules.json（按 host 正则匹配）
//             > 内置弱启发式（无密码框 + URL 不像登录页 + 未被反爬拦截）——建议显式配置。
//
// 子命令：
//   detect <url> [--session N] [--success-url RE] [--success-selector SEL] [--timeout SEC]
//   wait              [--session N] [--success-url RE] [--success-selector SEL]
//                     [--timeout SEC] [--poll SEC] [--shot-every K]
//   auto   <url>      同 detect + wait 连跑（SSO 自动跳转/测试用）

import {parseArgs} from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import {
  SKILL_DIR, ensureDirs, paths, result, statusLine, sleep,
  launchSession, loadSession, sessionAlive, connectSession, ensureTab,
  pageState, gotoHuman, shootFixed, criteriaFrom, criteriaMet, exitSoon,
} from './lib.mjs';

function die(msg, code = 1) {
  console.error('ERROR ' + msg);
  result({ok: false, error: msg});
  exitSoon(code);
}

function pendingFile(session) {
  return path.join(paths().sessions, `${session}.login.json`);
}

function parse() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  const {values} = parseArgs({
    args: argv.slice(1),
    options: {
      session: {type: 'string', default: 'default'},
      'success-url': {type: 'string'},
      'success-selector': {type: 'string'},
      timeout: {type: 'string', default: '300'},
      poll: {type: 'string', default: '3'},
      'shot-every': {type: 'string', default: '4'},
    },
    allowPositionals: true,
    strict: false,
  });
  return {
    cmd,
    positional: argv.slice(1).filter((a) => !a.startsWith('--')),
    values,
  };
}

async function liveCtx(session, startUrl) {
  let sess = loadSession(session);
  if (!sess || !(await sessionAlive(sess))) {
    statusLine(`会话 ${session} 不在，自动启动有头 Chrome…`);
    const r = await launchSession({name: session, url: startUrl});
    sess = r.session;
  }
  const {browser, ctx} = await connectSession(sess);
  return {sess, browser, ctx};
}

async function detect() {
  const {positional, values} = parse();
  const url = positional[0];
  if (!url) die('detect 需要 <url>');
  const session = values.session;
  const {sess, browser, ctx} = await liveCtx(session, url);
  const page = await ensureTab(browser, ctx, sess, {preferUrl: url});
  if (page.url() !== url) await gotoHuman(page, url);
  const st = await pageState(page);
  const cri = criteriaFrom(
    {'success-url': values['success-url'], 'success-selector': values['success-selector']},
    url
  );
  const met = await criteriaMet(page, cri).catch((e) => {
    die(e.message);
  });

  const assistShot = path.join(paths().shots, session, 'login-assist.png');
  const shot = await shootFixed(page, assistShot);

  if (met.ok) {
    result({ok: true, cmd: 'detect', phase: 'LOGIN_ALREADY_OK', state: st.state, url: st.url, criteria: cri.source});
    return;
  }

  // 落判据与上下文，供 wait 接力（跨进程交接文件）
  ensureDirs();
  fs.writeFileSync(
    pendingFile(session),
    JSON.stringify({url, criteria: cri, detected: st.state, at: new Date().toISOString()}, null, 2),
    'utf8'
  );

  statusLine(`检测到 ${st.state}（guard=${st.guard.kind || '无'} 密码框=${st.hasPassword} 验证码=${st.hasCaptcha}）`);
  statusLine(`截图已落盘: ${shot}（用 Read 读图确认页面内容）`);
  console.log('ASSIST ' + JSON.stringify({
    need: 'human-login',
    session,
    state: st.state,
    guard: st.guard,
    url: st.url,
    screenshot: shot,
    criteriaSource: cri.source,
    successUrl: cri.successUrl,
    successSelector: cri.successSelector,
    how: '用户在可见 Chrome 窗口人工完成验证码/2FA/登录；会话侧随后运行 wait 轮询判据',
  }));
  result({ok: true, cmd: 'detect', phase: 'LOGIN_ASSISTANCE_NEEDED', state: st.state, url: st.url, shot, criteria: cri.source});
}

async function waitLoop() {
  const {values} = parse();
  const session = values.session;
  const sess = await (async () => {
    const s = loadSession(session);
    if (!s || !(await sessionAlive(s))) die(`会话 ${session} 不在。先 detect 或 launch。`);
    return s;
  })();
  let cri = criteriaFrom(
    {'success-url': values['success-url'], 'success-selector': values['success-selector']},
    ''
  );
  if (cri.source === 'heuristic') {
    try {
      const pending = JSON.parse(fs.readFileSync(pendingFile(session), 'utf8'));
      if (pending.criteria) cri = pending.criteria;
    } catch {}
  }
  const {browser, ctx} = await connectSession(sess);
  const timeoutMs = Number(values.timeout) * 1000;
  const pollMs = Math.max(1000, Number(values.poll) * 1000);
  const shotEvery = Math.max(1, Number(values['shot-every']));
  const watchShot = path.join(paths().shots, session, 'login-watch.png');
  statusLine(`wait 开始：判据来源=${cri.source} successUrl=${cri.successUrl || '-'} selector=${cri.successSelector || '-'} timeout=${values.timeout}s`);
  const t0 = Date.now();
  let i = 0;
  while (Date.now() - t0 < timeoutMs) {
    i++;
    const tabs = ctx.pages();
    let anyBlocked = false;
    for (const p of tabs) {
      let met;
      try {
        met = await criteriaMet(p, cri);
      } catch (e) {
        die(e.message);
      }
      if (met.ok) {
        const shot = await shootFixed(p, watchShot).catch(() => null);
        const st = await pageState(p).catch(() => null);
        result({ok: true, cmd: 'wait', phase: 'LOGIN_OK', why: met.why, url: p.url(), shot, polls: i, state: st ? st.state : null});
        return;
      }
      const st = await pageState(p).catch(() => null);
      if (st && st.guard.blocked) anyBlocked = true;
    }
    if (i % shotEvery === 0) {
      const p = tabs[tabs.length - 1];
      if (p) await shootFixed(p, watchShot).catch(() => {});
    }
    statusLine(`poll=${i} tabs=${tabs.length} ${anyBlocked ? '检测到反爬拦截页（等人工处理）' : '等待登录完成'} watch=${watchShot}`);
    await sleep(pollMs);
  }
  result({ok: false, cmd: 'wait', phase: 'LOGIN_TIMEOUT', timeout: Number(values.timeout), watch: watchShot});
  exitSoon(2);
}

async function main() {
  const {cmd, positional} = parse();
  if (cmd === 'detect') return detect();
  if (cmd === 'wait') return waitLoop();
  if (cmd === 'auto') {
    // detect + wait 连跑
    process.argv.splice(2, 1); // 去掉 auto，让 detect 读到 <url>
    await detect();
    await waitLoop();
    return;
  }
  die('未知子命令（detect / wait / auto）');
}

main().then(() => exitSoon(0)).catch((e) => {
  console.error('ERROR ' + ((e && e.message) || e));
  result({ok: false, error: String((e && e.message) || e)});
  exitSoon(1);
});
