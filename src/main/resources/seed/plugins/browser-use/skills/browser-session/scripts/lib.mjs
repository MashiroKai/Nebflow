// lib.mjs — browser-use 包级共享载体模块（本件属 browser-session skill；paper-download 经相对路径复用）
//
// 职责：
//   1) 运行时解析：定位 playwright 包（脚本旁 node_modules → 本插件运行时目录 → npm 全局），
//      缺失时给出可执行的 bootstrap 指引（装到用户目录，绝不装进插件包/仓库）。
//   2) 会话注册表：sessions/<name>.json 记录 port/pid/profile；Chrome 以独立进程存活，
//      本模块的调用方是一次性 node 进程，经 CDP attach 控制浏览器后退出，浏览器与登录态留存。
//   3) 页面状态与反爬识别（识别即如实报告，绝不假装成功）。
//   4) 拟人化操作原语（节奏/鼠标轨迹/滚动）与截图落盘。
//   5) 登录判据配置（login-rules.json）与 PDF 校验。
//
// 安全纪律：本模块不引入、不存储任何凭据；profile/cookie 全部落在本机用户目录
// （%USERPROFILE% 下 NEBFLOW_BROWSER_USE_HOME 可覆盖），永不写入插件包或仓库。

import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import net from 'node:net';
import {spawn, spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const SKILL_DIR = path.resolve(HERE, '..');
export const PKG_ROOT = path.resolve(SKILL_DIR, '..', '..');

// ---------- 路径布局（全部在用户目录；测试用 NEBFLOW_BROWSER_USE_HOME 隔离） ----------

export function homeRoot() {
  return process.env.NEBFLOW_BROWSER_USE_HOME || path.join(os.homedir(), '.nebflow', 'browser-use');
}

export function paths() {
  const root = homeRoot();
  return {
    root,
    profiles: path.join(root, 'profiles'),
    sessions: path.join(root, 'sessions'),
    shots: path.join(root, 'screenshots'),
    staging: path.join(root, 'downloads'),
    exportsDir: path.join(root, 'exports'),
    config: path.join(root, 'config'),
    runtime: path.join(root, 'runtime'),
  };
}

export function ensureDirs() {
  const p = paths();
  for (const d of [p.root, p.profiles, p.sessions, p.shots, p.staging, p.exportsDir, p.config]) {
    fs.mkdirSync(d, {recursive: true});
  }
  return p;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const rand = (a, b) => a + Math.random() * (b - a);
export const randInt = (a, b) => Math.floor(rand(a, b + 1));

export function slugify(s, max = 60) {
  const base = String(s || '')
    .toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/[^a-z0-9\u4e00-\u9fff]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return (base || 'item').slice(0, max);
}

export function result(obj) {
  // 唯一机器可读出口：单行 RESULT JSON（人工可读行另行打印）。
  console.log('RESULT ' + JSON.stringify(obj));
}

let _exitScheduled = false;
export function exitSoon(code = 0) {
  // 断开纪律的落地点：playwright 的 CDP 连接持有打开的 WebSocket，会钉住事件循环，
  // 进程打印完终态后不会自然退出 ⇒ 定时强退（留 250ms 让 stdout 管道冲刷完）。
  // 只 drop socket = CDP 断开，浏览器进程/标签页/登录态不受影响。
  if (_exitScheduled) return;
  _exitScheduled = true;
  setTimeout(() => process.exit(code), 250);
}

export function statusLine(msg) {
  console.log('STATUS ' + msg);
}

// ---------- 输出安全：绝不把 cookie 值打进 stdout ----------

export function redactUrl(u) {
  try {
    const url = new URL(u);
    if (url.search) url.search = '?…';
    return url.href;
  } catch {
    return String(u || '');
  }
}

// ---------- Chrome 定位（仅用环境变量拼路径，避免机器绑定路径常量） ----------

export function findChrome() {
  const cand = [];
  if (process.env.NEBFLOW_BROWSER_USE_CHROME) cand.push(process.env.NEBFLOW_BROWSER_USE_CHROME);
  const pf = process.env.ProgramFiles;
  const pf86 = process.env['ProgramFiles(x86)'];
  const lad = process.env.LOCALAPPDATA;
  if (pf) {
    cand.push(path.join(pf, 'Google', 'Chrome', 'Application', 'chrome.exe'));
    cand.push(path.join(pf, 'Microsoft', 'Edge', 'Application', 'msedge.exe'));
  }
  if (pf86) {
    cand.push(path.join(pf86, 'Google', 'Chrome', 'Application', 'chrome.exe'));
  }
  if (lad) {
    cand.push(path.join(lad, 'Google', 'Chrome', 'Application', 'chrome.exe'));
  }
  for (const c of cand) {
    try {
      if (c && fs.existsSync(c)) return c;
    } catch {}
  }
  throw new Error(
    '未找到本机 Chrome/Edge。请用环境变量 NEBFLOW_BROWSER_USE_CHROME 指定浏览器可执行文件绝对路径后重试。'
  );
}

// ---------- 端口与就绪等待 ----------

export function freePort(prefer) {
  const tryPort = (p) =>
    new Promise((resolve) => {
      const srv = net.createServer();
      srv.once('error', () => resolve(p === 0 ? null : tryPort(0)));
      srv.listen(p, '127.0.0.1', () => {
        const got = srv.address().port;
        srv.close(() => resolve(got));
      });
    });
  return tryPort(prefer || 0);
}

async function fetchWithTimeout(url, opts = {}, ms = 3000) {
  return fetch(url, {...opts, signal: AbortSignal.timeout(ms)});
}

export async function cdpAlive(port) {
  try {
    const r = await fetchWithTimeout(`http://127.0.0.1:${port}/json/version`, {}, 2000);
    return r.ok;
  } catch {
    return false;
  }
}

export async function waitCdpReady(port, timeoutMs = 30000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    if (await cdpAlive(port)) return true;
    await sleep(300);
  }
  return false;
}

// ---------- 会话注册表 ----------

function sessionFile(name) {
  return path.join(paths().sessions, `${name}.json`);
}

export function loadSession(name) {
  try {
    const raw = fs.readFileSync(sessionFile(name), 'utf8');
    const data = JSON.parse(raw);
    if (!data || typeof data.port !== 'number') return null;
    return data;
  } catch {
    return null;
  }
}

export function saveSession(sess) {
  ensureDirs();
  fs.writeFileSync(sessionFile(sess.name), JSON.stringify(sess, null, 2), 'utf8');
}

export function removeSession(name) {
  try {
    fs.unlinkSync(sessionFile(name));
  } catch {}
}

export async function sessionAlive(sess) {
  if (!sess) return false;
  if (sess.pid) {
    // pid 探测：Windows tasklist / 其他平台 kill -0
    if (process.platform === 'win32') {
      const r = spawnSync('tasklist', ['/FI', `PID eq ${sess.pid}`, '/NH'], {timeout: 8000});
      const out = r.status === 0 ? String(r.stdout || '') : '';
      if (!out.includes(String(sess.pid))) return false;
    } else {
      try {
        process.kill(sess.pid, 0);
      } catch {
        return false;
      }
    }
  }
  return cdpAlive(sess.port);
}

export async function launchSession(opts = {}) {
  const name = opts.name || 'default';
  const p = ensureDirs();
  const existing = loadSession(name);
  if (existing && (await sessionAlive(existing))) {
    return {session: existing, reused: true};
  }
  if (existing) removeSession(name);

  const chromePath = findChrome();
  const profileName = opts.profile || name;
  const profileDir = path.isAbsolute(profileName)
    ? profileName
    : path.join(p.profiles, profileName);
  fs.mkdirSync(profileDir, {recursive: true});

  const port = await freePort(opts.port || 9222);
  const args = [
    `--user-data-dir=${profileDir}`,
    `--remote-debugging-port=${port}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--hide-crash-restore-bubble',
    '--disable-session-crashed-bubble',
    `--window-size=${opts.width || 1400},${opts.height || 900}`,
  ];
  if (opts.url) args.push(opts.url);

  const child = spawn(chromePath, args, {detached: true, stdio: 'ignore'});
  child.unref();

  const ready = await waitCdpReady(port, 30000);
  if (!ready) {
    throw new Error(`Chrome 已启动但 CDP 端口 ${port} 未就绪（30s 超时）。pid=${child.pid}`);
  }
  const session = {
    name,
    port,
    pid: child.pid,
    chromePath,
    userDataDir: profileDir,
    startedAt: new Date().toISOString(),
  };
  saveSession(session);
  return {session, reused: false};
}

export async function closeSession(name) {
  const sess = loadSession(name);
  if (!sess) return {closed: false, reason: 'no-session-file'};
  removeSession(name);
  if (await cdpAlive(sess.port)) {
    // 优雅路径：经 CDP Browser.close
    try {
      const pw = loadPlaywright();
      const browser = await pw.chromium.connectOverCDP(`http://127.0.0.1:${sess.port}`, {timeout: 8000});
      const cdp = browser.newBrowserCDPSession ? await browser.newBrowserCDPSession() : null;
      if (cdp) {
        await cdp.send('Browser.close');
      } else {
        await browser.close();
      }
      await sleep(1500);
    } catch {}
  }
  if (sess.pid && (await pidAlive(sess.pid))) {
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/PID', String(sess.pid), '/T', '/F'], {timeout: 10000});
    } else {
      try {
        process.kill(sess.pid, 'SIGTERM');
      } catch {}
    }
  }
  return {closed: true, reason: 'ok'};
}

async function pidAlive(pid) {
  if (process.platform === 'win32') {
    const r = spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/NH'], {timeout: 8000});
    return r.status === 0 && String(r.stdout || '').includes(String(pid));
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

// ---------- playwright 运行时解析（脚本旁 → 本插件运行时目录 → npm 全局） ----------

let _pw = null;

export function runtimeReady() {
  const rt = paths().runtime;
  try {
    return fs.existsSync(path.join(rt, 'node_modules', 'playwright'));
  } catch {
    return false;
  }
}

export function bootstrapRuntime() {
  const p = ensureDirs();
  const rt = p.runtime;
  fs.mkdirSync(rt, {recursive: true});
  const pkgJson = path.join(rt, 'package.json');
  if (!fs.existsSync(pkgJson)) {
    fs.writeFileSync(pkgJson, JSON.stringify({name: 'browser-use-runtime', private: true}, null, 2), 'utf8');
  }
  const npmCmd = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  statusLine(`bootstrap: npm install playwright --prefix ${rt} （一次性；此后离线可用）`);
  const r = spawnSync(npmCmd, ['install', 'playwright', '--prefix', rt, '--no-fund', '--no-audit', '--loglevel', 'error'], {
    stdio: 'inherit',
    shell: true,
    timeout: 900000,
  });
  if (r.status !== 0) {
    throw new Error(`bootstrap 失败（exit=${r.status}）。请检查网络/npm 后重试，或手工执行上述命令。`);
  }
  if (!runtimeReady()) throw new Error('bootstrap 后仍未找到 playwright，请检查 ' + rt);
  return rt;
}

export function loadPlaywright() {
  if (_pw) return _pw;
  const errs = [];
  // 1) 脚本所在路径向上（兼容把包内 node_modules 或仓库根 node_modules 作为运行时的场景）
  try {
    const req = createRequire(path.join(HERE, 'noop.js'));
    _pw = req('playwright');
    return _pw;
  } catch (e) {
    errs.push('script-adjacent: ' + e.message.split('\n')[0]);
  }
  // 2) 本插件运行时目录（bootstrap 落点，在用户目录，不入仓库）
  try {
    const rt = paths().runtime;
    const req = createRequire(path.join(rt, 'package.json'));
    _pw = req('playwright');
    return _pw;
  } catch (e) {
    errs.push('runtime-dir: ' + e.message.split('\n')[0]);
  }
  // 3) npm 全局
  try {
    const g = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['root', '-g'], {
      timeout: 15000,
      shell: true,
    });
    const root = String(g.stdout || '').trim().split(/\r?\n/)[0];
    if (root) {
      const req = createRequire(path.join(root, 'package.json'));
      _pw = req('playwright');
      return _pw;
    }
  } catch (e) {
    errs.push('npm-global: ' + e.message.split('\n')[0]);
  }
  throw new Error(
    'playwright 运行时缺失。执行一次：node "' +
      path.join(SKILL_DIR, 'chrome-session.mjs') +
      '" bootstrap\n  尝试过的位置：' +
      errs.join(' | ')
  );
}

// ---------- 连接与标签页 ----------

export async function connectSession(sess) {
  const pw = loadPlaywright();
  const browser = await pw.chromium.connectOverCDP(`http://127.0.0.1:${sess.port}`, {timeout: 20000});
  const ctx = browser.contexts()[0];
  if (!ctx) {
    try {
      await browser.close();
    } catch {}
    throw new Error('CDP 已连接但无默认浏览器上下文（异常状态，请 close 后重新 launch）');
  }
  return {browser, ctx};
}

// 断开纪律：绝不调用 browser.close() 来"收尾"——CDP attach 语义下它可能清理页面上下文。
// 直接让进程退出即可，socket 关闭即断开，浏览器进程与所有标签页原样存活。
export async function detach(_browser) {
  /* 故意空实现：见上注释 */
}

// 取一个"Chrome 自有"的标签页（优先复用现有 tab；必要时用 /json/new 由 Chrome 开新 tab）。
// 不用 ctx.newPage()：连接创建的页面在断开语义上不干净，Chrome 自有 tab 跨进程稳定。
export async function ensureTab(browser, ctx, sess, {preferUrl} = {}) {
  const pages = ctx.pages();
  if (preferUrl) {
    let want;
    try {
      want = new URL(preferUrl).href;
    } catch {}
    if (want) {
      const hit = pages.find((p) => {
        try {
          return new URL(p.url()).href === want;
        } catch {
          return false;
        }
      });
      if (hit) return hit;
    }
  }
  if (pages.length) return pages[pages.length - 1];
  try {
    const r = await fetchWithTimeout(
      `http://127.0.0.1:${sess.port}/json/new?about:blank`,
      {method: 'PUT'},
      5000
    );
    if (r.ok) {
      const before = new Set(pages.map((p) => p.url()));
      for (let i = 0; i < 40; i++) {
        for (const pg of ctx.pages()) {
          if (!before.has(pg.url()) || pg.url() === 'about:blank') return pg;
        }
        await sleep(200);
      }
    }
  } catch {}
  return ctx.newPage();
}

// ---------- 页面状态与反爬识别（识别即如实报告） ----------

export async function pageState(page) {
  const url = page.url();
  let title = '';
  let info = null;
  let evalError = null;
  try {
    info = await page.evaluate(() => {
      const vis = (el) => !!el && (el.offsetParent !== null || el.getClientRects().length > 0);
      const html = String(document.documentElement.innerHTML || '').slice(0, 60000);
      const bodyText = String((document.body && document.body.innerText) || '').slice(0, 6000);
      const q = (s) => document.querySelector(s);
      const hasPassword = [...document.querySelectorAll('input[type=password]')].some(vis);
      const markers = [];
      const push = (cond, m) => {
        if (cond) markers.push(m);
      };
      push(
        !!q('#challenge-form') ||
          !!q('#challenge-running') ||
          /cf-challenge|cdn-cgi\/challenge-platform|__cf_chl/i.test(html),
        'cloudflare'
      );
      push(
        !!q('#px-captcha') || /_pxAppId|px-captcha|captcha-delivery\.com|press.*and.*hold/i.test(html + ' ' + bodyText),
        'perimeterx'
      );
      push(/datadome\.co|ddv1|geo-captcha/i.test(html), 'datadome');
      push(
        /just a moment|attention required|请稍候|安全检查|确认您是真人|verify you are human|confirm you are a human/i.test(
          String(document.title || '') + ' ' + bodyText.slice(0, 600)
        ),
        'challenge-text'
      );
      push(
        /radware bot manager|we apologize for the inconvenience/i.test(
          String(document.title || '') + ' ' + bodyText
        ),
        'radware'
      );
      const hasCaptcha =
        !!q('iframe[src*="captcha"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[title*="captcha" i]') ||
        !!q('.g-recaptcha, .h-captcha, [class*="captcha" i]');
      const textLen = String((document.body && document.body.innerText) || '').trim().length;
      return {hasPassword, hasCaptcha, markers, docTitle: String(document.title || ''), textLen};
    });
    title = info.docTitle || (await page.title().catch(() => ''));
  } catch (e) {
    evalError = String((e && e.message) || e).split('\n')[0];
  }
  const urlLoginish = /log[-_]?in|sign[-_]?in|signin|\/auth|\/sso|\/cas|oauth|identify/i.test(url);
  const hasPassword = !!(info && info.hasPassword);
  const hasCaptcha = !!(info && info.hasCaptcha);
  const markers = (info && info.markers) || [];
  // guard 判定（2026-10-13 实测修订）：探针脚本 marker 只说明「站点部署了该防护」，
  // 不等于「本次被拦」——IOP 等站点的正常文章页也内嵌 PerimeterX 探针（bodyText 数千字）。
  // 拦截页证据 = markers 命中 且（页面近空白 <500 字 或 challenge 文案命中）。
  // 两个条件都不满足时 marker 仅随 JSON 上报（guard.markers），不改变 state。
  const challengeTextFired = markers.includes('challenge-text');
  const contentBare = typeof (info && info.textLen) === 'number' ? info.textLen < 500 : false;
  const guard = {
    blocked: markers.length > 0 && (contentBare || challengeTextFired),
    kind: markers[0] || null,
    markers,
    textLen: info ? info.textLen : null,
  };
  const loginish = hasPassword || (urlLoginish && !guard.blocked);
  let state = 'OK';
  if (guard.blocked) state = 'ANTIBOT';
  else if (loginish) state = 'LOGIN';
  else if (hasCaptcha) state = 'CAPTCHA';
  return {url: redactUrl(url), title, state, guard, hasPassword, hasCaptcha, evalError};
}

// ---------- 拟人化原语 ----------

export async function humanPause(page, a = 400, b = 1200) {
  await page.waitForTimeout(rand(a, b));
}

export async function humanScroll(page, {times = 2} = {}) {
  for (let i = 0; i < times; i++) {
    await page.mouse.wheel(0, randInt(240, 620));
    await page.waitForTimeout(rand(180, 520));
  }
}

export async function humanClick(page, locator, {timeout = 10000} = {}) {
  await locator.waitFor({state: 'visible', timeout});
  const box = await locator.boundingBox();
  if (!box) throw new Error('目标元素无几何信息（boundingBox=null），无法拟人点击');
  const x = box.x + box.width / 2 + rand(-box.width * 0.12, box.width * 0.12);
  const y = box.y + box.height / 2 + rand(-box.height * 0.12, box.height * 0.12);
  if (Math.random() < 0.25) {
    await page.mouse.move(x + rand(6, 18), y + rand(-8, 8), {steps: randInt(6, 12)});
    await page.waitForTimeout(rand(60, 160));
  }
  await page.mouse.move(x, y, {steps: randInt(10, 22)});
  await page.waitForTimeout(rand(80, 240));
  await page.mouse.down();
  await page.waitForTimeout(rand(40, 110));
  await page.mouse.up();
}

export async function humanType(page, locator, text, {timeout = 10000} = {}) {
  await locator.waitFor({state: 'visible', timeout});
  await humanClick(page, locator, {timeout});
  await page.waitForTimeout(rand(150, 400));
  for (const ch of String(text)) {
    await page.keyboard.type(ch, {delay: rand(40, 150)});
    if (Math.random() < 0.06) await page.waitForTimeout(rand(140, 380));
  }
}

export async function gotoHuman(page, url, {timeoutMs = 45000} = {}) {
  const resp = await page.goto(url, {waitUntil: 'domcontentloaded', timeout: timeoutMs});
  await page.waitForTimeout(rand(700, 1500));
  return resp;
}

// ---------- 截图 ----------

export async function shoot(page, label, {fullPage = false} = {}) {
  const p = ensureDirs();
  const dir = path.join(p.shots, label || 'misc');
  fs.mkdirSync(dir, {recursive: true});
  const file = path.join(dir, `${new Date().toISOString().replace(/[:.]/g, '-')}.png`);
  await page.screenshot({path: file, fullPage});
  return file;
}

export async function shootFixed(page, file, {fullPage = false} = {}) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  await page.screenshot({path: file, fullPage});
  return file;
}

// ---------- 登录判据配置 ----------

export function loadLoginRules() {
  try {
    const file = path.join(paths().config, 'login-rules.json');
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    return Array.isArray(data.rules) ? data.rules : [];
  } catch {
    return [];
  }
}

export function matchLoginRule(url) {
  let host = '';
  try {
    host = new URL(url).host;
  } catch {
    return null;
  }
  for (const rule of loadLoginRules()) {
    try {
      if (rule.hostPattern && new RegExp(rule.hostPattern).test(host)) return rule;
    } catch {}
  }
  return null;
}

export function criteriaFrom(opts = {}, url = '') {
  // 优先序：CLI 旗标 > login-rules.json 按主机匹配 > 内置启发式（弱，建议显式配置）
  if (opts.successUrl || opts.successSelector) {
    return {successUrl: opts.successUrl || null, successSelector: opts.successSelector || null, source: 'cli'};
  }
  const rule = matchLoginRule(url);
  if (rule) {
    return {
      successUrl: rule.successUrl || null,
      successSelector: rule.successSelector || null,
      source: 'login-rules.json:' + (rule.name || rule.hostPattern),
    };
  }
  return {successUrl: null, successSelector: null, source: 'heuristic'};
}

export async function criteriaMet(page, cri) {
  try {
    if (cri.successUrl && new RegExp(cri.successUrl).test(page.url())) return {ok: true, why: 'url-regex'};
  } catch (e) {
    throw new Error('success-url 不是合法正则：' + cri.successUrl + '（' + e.message + '）');
  }
  if (cri.successSelector) {
    try {
      await page.locator(cri.successSelector).first().waitFor({state: 'visible', timeout: 900});
      return {ok: true, why: 'selector-visible'};
    } catch {}
  }
  if (!cri.successUrl && !cri.successSelector) {
    const st = await pageState(page);
    if (st.state === 'OK') return {ok: true, why: 'heuristic'};
  }
  return {ok: false, why: null};
}

// ---------- 下载行为（CDP Browser.setDownloadBehavior：固定落 staging，程序可控） ----------

export async function setDownloadStaging(browser, stagingDir) {
  fs.mkdirSync(stagingDir, {recursive: true});
  const cdp = await browser.newBrowserCDPSession();
  await cdp.send('Browser.setDownloadBehavior', {
    behavior: 'allowAndName',
    downloadPath: stagingDir,
    eventsEnabled: true,
  });
  return cdp;
}

export async function resetDownloadBehavior(browser) {
  try {
    const cdp = await browser.newBrowserCDPSession();
    await cdp.send('Browser.setDownloadBehavior', {behavior: 'default'});
  } catch {}
}

export async function waitForNewFile(dir, beforeSet, timeoutMs = 30000) {
  const t0 = Date.now();
  let candidate = null;
  while (Date.now() - t0 < timeoutMs) {
    let entries = [];
    try {
      entries = fs.readdirSync(dir).filter((f) => !f.endsWith('.crdownload'));
    } catch {}
    const fresh = entries.find((f) => !beforeSet.has(f));
    if (fresh) {
      const full = path.join(dir, fresh);
      const s1 = fs.statSync(full).size;
      await sleep(800);
      const s2 = fs.statSync(full).size;
      if (s1 === s2 && s1 > 0) return full;
      candidate = full;
    }
    await sleep(300);
  }
  return candidate;
}

// ---------- PDF 校验（%PDF- 头 + 最小体积 + HTML 误装识别） ----------

export function validatePdfBuf(buf, {minBytes = 10240} = {}) {
  const size = buf ? buf.length : 0;
  if (!size) return {valid: false, reason: 'empty', size};
  if (size < minBytes) return {valid: false, reason: 'too-small', size, minBytes};
  const head = buf.subarray(0, 1024);
  const idx = head.indexOf('%PDF-');
  if (idx >= 0) return {valid: true, reason: 'pdf-header', size, headerOffset: idx};
  const low = buf.subarray(0, 512).toString('latin1').toLowerCase();
  if (low.includes('<!doctype html') || low.includes('<html')) {
    return {valid: false, reason: 'html-not-pdf', size};
  }
  return {valid: false, reason: 'no-pdf-header', size};
}
