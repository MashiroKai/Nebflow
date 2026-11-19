#!/usr/bin/env node
// chrome-session.mjs — 持久浏览器会话 CLI（browser-session skill）
//
// 会话模型：本机 Chrome（有头）以独立进程运行，持专用持久用户配置文件
// （登录一次，cookie/会话留在本机用户目录）；每次命令是新起的 node 进程，
// 经 CDP attach 控制浏览器后退出——浏览器、标签页、登录态跨调用存活。
//
// 子命令：
//   bootstrap                         安装 playwright 运行时（用户目录，一次性）
//   launch  [--session N] [--url U] [--profile P] [--port N]
//   status  [--session N]
//   close   [--session N]
//   goto <url>     [--session N]      导航并输出页面状态/反爬识别
//   state          [--session N]      只读状态快照（含反爬识别）
//   pages          [--session N]      列出全部标签页
//   screenshot     [--session N] [--label L] [--full]
//   click <selector> [--session N]    拟人点击（轨迹+节奏）
//   type <selector> <text> [--session N]   拟人输入
//   press <key>    [--session N]
//   scroll         [--session N] [--times K]
//   eval <js>      [--session N]      页面内求值（只读用途；禁改页面状态）
//   wait <urlRegex> [--session N] [--timeout SEC]
//   set-cookie <name> <value> --domain D [--path P]
//   cookies        [--session N] [--out FILE]   敏感：默认落文件不打印
//
// 输出纪律：过程行以 STATUS 前缀；终态恒为一行 RESULT JSON。

import {parseArgs} from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import {
  SKILL_DIR, ensureDirs, paths, result, statusLine, sleep, exitSoon,
  launchSession, loadSession, sessionAlive, closeSession, removeSession,
  connectSession, ensureTab, pageState, humanClick, humanType, humanScroll,
  gotoHuman, shoot, shootFixed, setDownloadStaging,
} from './lib.mjs';

function die(msg, code = 1) {
  console.error('ERROR ' + msg);
  result({ok: false, error: msg});
  exitSoon(code);
}

function parse() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  const {values} = parseArgs({
    args: argv.slice(1),
    options: {
      session: {type: 'string', default: 'default'},
      url: {type: 'string'},
      profile: {type: 'string'},
      port: {type: 'string'},
      label: {type: 'string'},
      full: {type: 'boolean', default: false},
      times: {type: 'string', default: '2'},
      timeout: {type: 'string', default: '60'},
      domain: {type: 'string'},
      path: {type: 'string', default: '/'},
      out: {type: 'string'},
      secure: {type: 'string', default: 'auto'},
    },
    allowPositionals: true,
    strict: false,
  });
  return {cmd, rest: argv.slice(1).filter((a) => !a.startsWith('--')).slice(0), values};
}

async function needSession(name) {
  const sess = loadSession(name);
  if (!sess || !(await sessionAlive(sess))) {
    throw new Error(`会话 ${name} 不在。先执行：node "${path.join(SKILL_DIR, 'chrome-session.mjs')}" launch --session ${name}`);
  }
  return sess;
}

async function main() {
  const {cmd, rest, values} = parse();
  const positional = rest.filter((a) => !a.startsWith('--'));
  const name = values.session;

  switch (cmd) {
    case 'bootstrap': {
      const {bootstrapRuntime} = await import('./lib.mjs');
      const rt = bootstrapRuntime();
      result({ok: true, cmd, runtime: rt});
      return;
    }

    case 'launch': {
      const {session, reused} = await launchSession({
        name,
        url: values.url,
        profile: values.profile,
        port: values.port ? Number(values.port) : undefined,
      });
      statusLine(`session=${name} port=${session.port} profile=${session.userDataDir}${reused ? ' (复用既有会话)' : ' (新启动)'}`);
      result({
        ok: true, cmd, reused, session: name, port: session.port,
        pid: session.pid, userDataDir: session.userDataDir, headful: true,
      });
      return;
    }

    case 'status': {
      const sess = loadSession(name);
      const alive = await sessionAlive(sess);
      result({ok: true, cmd, session: name, alive, sessionInfo: alive ? sess : null});
      return;
    }

    case 'close': {
      const r = await closeSession(name);
      result({ok: true, cmd, session: name, ...r});
      return;
    }

    case 'goto': {
      const url = positional[0];
      if (!url) die('goto 需要 <url>');
      const sess = await needSession(name);
      const {browser, ctx} = await connectSession(sess);
      const page = await ensureTab(browser, ctx, sess, {preferUrl: url});
      await gotoHuman(page, url);
      const st = await pageState(page);
      const shot = await shoot(page, name);
      statusLine(`state=${st.state} url=${st.url}${st.guard.blocked ? ' guard=' + st.guard.kind : ''}`);
      statusLine(`shot=${shot}`);
      result({ok: true, cmd, state: st.state, url: st.url, title: st.title, guard: st.guard, shot});
      return;
    }

    case 'state': {
      const sess = await needSession(name);
      const {browser, ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      if (!page) die('没有打开的标签页');
      const st = await pageState(page);
      const shot = await shoot(page, name);
      result({ok: true, cmd, ...st, shot});
      return;
    }

    case 'pages': {
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const list = [];
      for (const p of ctx.pages()) {
        list.push({url: p.url(), title: await p.title().catch(() => '')});
      }
      result({ok: true, cmd, session: name, count: list.length, pages: list});
      return;
    }

    case 'screenshot': {
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      if (!page) die('没有打开的标签页');
      const file = await shoot(page, values.label || name, {fullPage: values.full});
      result({ok: true, cmd, shot: file});
      return;
    }

    case 'click': {
      const sel = positional[0];
      if (!sel) die('click 需要 <selector>');
      const sess = await needSession(name);
      const {browser, ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      if (!page) die('没有打开的标签页');
      await humanScroll(page, {times: 1});
      await humanClick(page, page.locator(sel).first());
      await sleep(600);
      const st = await pageState(page);
      statusLine(`clicked=${sel} state=${st.state} url=${st.url}`);
      result({ok: true, cmd, clicked: sel, state: st.state, url: st.url, guard: st.guard});
      return;
    }

    case 'type': {
      const sel = positional[0];
      const text = positional[1];
      if (!sel || text === undefined) die('type 需要 <selector> <text>');
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      if (!page) die('没有打开的标签页');
      await humanType(page, page.locator(sel).first(), text);
      result({ok: true, cmd, typed: sel, length: text.length});
      return;
    }

    case 'press': {
      const key = positional[0];
      if (!key) die('press 需要 <key>');
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      await page.keyboard.press(key);
      result({ok: true, cmd, key});
      return;
    }

    case 'scroll': {
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      await humanScroll(page, {times: Number(values.times) || 2});
      result({ok: true, cmd, times: Number(values.times) || 2});
      return;
    }

    case 'eval': {
      const expr = positional.join(' ');
      if (!expr) die('eval 需要 <js 表达式>');
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const page = ctx.pages()[ctx.pages().length - 1];
      const value = await page.evaluate(expr);
      result({ok: true, cmd, value});
      return;
    }

    case 'wait': {
      const re = positional[0];
      if (!re) die('wait 需要 <urlRegex>');
      let rx;
      try {
        rx = new RegExp(re);
      } catch (e) {
        die('urlRegex 不合法：' + e.message);
      }
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const deadline = Date.now() + Number(values.timeout) * 1000;
      let i = 0;
      while (Date.now() < deadline) {
        for (const p of ctx.pages()) {
          if (rx.test(p.url())) {
            result({ok: true, cmd, matched: true, url: p.url(), polls: i});
            return;
          }
        }
        i++;
        statusLine(`poll=${i} tabs=${ctx.pages().length} 等待 URL 匹配 ${re}`);
        await sleep(2000);
      }
      result({ok: false, cmd, matched: false, timeout: Number(values.timeout)});
      exitSoon(2);
      return;
    }

    case 'set-cookie': {
      const cname = positional[0];
      const cvalue = positional[1];
      if (!cname || cvalue === undefined || !values.domain) {
        die('set-cookie 需要 <name> <value> --domain <domain>');
      }
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const secure =
        values.secure === 'auto'
          ? !/^(localhost|127\.)/.test(values.domain.replace(/^\./, ''))
          : values.secure === 'true';
      await ctx.addCookies([
        {name: cname, value: cvalue, domain: values.domain, path: values.path, secure, httpOnly: false},
      ]);
      result({ok: true, cmd, set: cname, domain: values.domain, secure});
      return;
    }

    case 'cookies': {
      // 敏感面：默认写入 exports 目录并只回路径；--stdout 才打印（内容含凭据，绝不入 git/日志）
      const sess = await needSession(name);
      const {ctx} = await connectSession(sess);
      const jar = await ctx.cookies();
      if (positional.includes('--stdout')) {
        result({ok: true, cmd, warning: 'cookie 明文（敏感，勿入 git/日志）', count: jar.length, cookies: jar});
        return;
      }
      const p = ensureDirs();
      const file = values.out || path.join(p.exportsDir, `cookies-${name}-${Date.now()}.json`);
      fs.mkdirSync(path.dirname(file), {recursive: true});
      fs.writeFileSync(file, JSON.stringify(jar, null, 2), 'utf8');
      statusLine(`cookie 已导出（敏感文件，勿提交）: ${file}`);
      result({ok: true, cmd, count: jar.length, file});
      return;
    }

    case 'help':
    default: {
      console.log(fs.readFileSync(path.join(SKILL_DIR, 'chrome-session.mjs'), 'utf8').split('\n').slice(1, 27).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
      result({ok: cmd === 'help', cmd: cmd || null, error: cmd ? '未知子命令' : undefined});
      exitSoon(cmd === 'help' ? 0 : 1);
    }
  }
}

main().then(() => exitSoon(0)).catch((e) => {
  console.error('ERROR ' + ((e && e.message) || e));
  result({ok: false, error: String((e && e.message) || e)});
  exitSoon(1);
});
