#!/usr/bin/env node
// paper-download.mjs — 论文下载工作流（paper-download skill）
//
// 输入 = DOI/URL 列表（--list file.json 或单个 --doi/--url）；逐篇串行：
//   打开文章页 → 反爬/登录态识别 → 定位 PDF 目标（观测即真相）→
//   获取（拟人点击捕获下载 → 带浏览器 cookie 的上下文请求 → 直接导航取响应体）→
//   校验（%PDF- 头 + 最小体积 + HTML 误装识别）→ 落盘到 --out-dir → 逐篇状态输出。
//
// 状态机（每篇恰好一个终态）：
//   SUCCESS | NEEDS_LOGIN | BLOCKED_ANTIBOT | NO_PDF | INVALID_PDF | DOWNLOAD_FAILED
//
// 纪律：串行 + 随机延时（遵守站点速率，绝不并发）；每篇处理完立即落盘报告再继续；
//       被反爬拦截或需要登录时如实报告，绝不假装成功。

import {parseArgs} from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import {
  ensureDirs, paths, result, statusLine, sleep, rand,
  launchSession, loadSession, sessionAlive, connectSession, ensureTab, detach,
  pageState, gotoHuman, humanScroll, humanClick, shootFixed,
  setDownloadStaging, resetDownloadBehavior, waitForNewFile, validatePdfBuf, slugify, exitSoon,
} from '../../browser-session/scripts/lib.mjs';

function die(msg, code = 1) {
  console.error('ERROR ' + msg);
  result({ok: false, error: msg});
  exitSoon(code);
}

function parse() {
  const argv = process.argv.slice(2);
  const {values} = parseArgs({
    args: argv,
    options: {
      list: {type: 'string'},
      doi: {type: 'string'},
      url: {type: 'string'},
      'out-dir': {type: 'string'},
      session: {type: 'string', default: 'default'},
      'min-delay-ms': {type: 'string', default: '5000'},
      'max-delay-ms': {type: 'string', default: '15000'},
      'min-bytes': {type: 'string', default: '10240'},
      'timeout-ms': {type: 'string', default: '60000'},
      report: {type: 'string'},
      'keep-open': {type: 'boolean', default: false},
    },
    allowPositionals: true,
    strict: false,
  });
  return values;
}

function readItems(values) {
  if (values.list) {
    const raw = JSON.parse(fs.readFileSync(values.list, 'utf8'));
    const arr = Array.isArray(raw) ? raw : raw.items;
    if (!Array.isArray(arr)) die('list 文件须为 JSON 数组或 {items:[...]}');
    return arr.map((it, i) => ({id: it.id || String(i + 1), doi: it.doi || null, url: it.url || null}));
  }
  if (values.doi) return [{id: values.doi, doi: values.doi, url: null}];
  if (values.url) return [{id: slugify(values.url), url: values.url, doi: null}];
  die('需要 --list <file.json> 或 --doi/--url 其一');
  return [];
}

function itemUrl(it) {
  if (it.url) return it.url;
  if (it.doi) return 'https://doi.org/' + String(it.doi).replace(/^https?:\/\/doi\.org\//, '');
  return null;
}

// 观测即真相：从页面实际 DOM 收集 PDF 候选事实（href + 可见文案），再按稳定度排序
async function findPdfTarget(page) {
  const facts = await page.evaluate(() => {
    let meta = null;
    const m = document.querySelector('meta[name="citation_pdf_url"]');
    if (m && m.content) meta = String(m.content);
    const out = [];
    for (const a of document.querySelectorAll('a[href]')) {
      const href = String(a.getAttribute('href') || '');
      if (!href || href.startsWith('javascript:')) continue;
      const text = String(a.innerText || a.textContent || '').trim().slice(0, 80);
      out.push({href, text});
    }
    return {meta, links: out};
  });
  const base = page.url();
  const abs = (h) => {
    try {
      return new URL(h, base).href;
    } catch {
      return null;
    }
  };
  if (facts.meta) {
    const a = abs(facts.meta);
    if (a) return {kind: 'meta-citation-pdf-url', href: a, text: null};
  }
  const scored = [];
  for (const l of facts.links) {
    const h = l.href.toLowerCase();
    let score = -1;
    if (h.endsWith('.pdf')) score = 100;
    else if (/\bpdf\b/i.test(l.text)) score = 80;
    else if (h.includes('pdfft')) score = 70; // ScienceDirect 直链形态
    else if (h.includes('.pdf')) score = 60;
    else if (h.includes('pdf')) score = 40;
    if (score > 0) scored.push({score, ...l, abs: abs(l.href)});
  }
  scored.sort((x, y) => y.score - x.score);
  if (!scored.length) return null;
  const pick = scored[0];
  if (!pick.abs) return null;
  return {kind: 'link', href: pick.abs, text: pick.text, hrefRaw: pick.href};
}

async function classifyBodyAsStatus(buf, pageStateFn) {
  // 获取到非 PDF 内容时，按内容特征分流终态（登录墙 / 反爬 / 纯无效）
  // 走到这里的必是"应 PDF 而非 PDF"的小型错误页/登录页/拦截页（真 PDF 在 %PDF- 头就通过了），
  // 因此可以放宽到前 256KB 找特征；真大文件不会进入本函数。
  const head = buf ? buf.subarray(0, 262144).toString('utf8') : '';
  const low = head.toLowerCase();
  if (
    /<input[^>]+type=["']?password/.test(low) ||
    /sign in to|please (sign in|log in)|log in to (view|continue|download)|请登录|登录后(查看|下载)/i.test(head.slice(0, 2000))
  ) {
    return 'NEEDS_LOGIN';
  }
  // 拦截页特征（2026-10-13 实测修订）：只认「人机检查页可见特征」，不再认健康页面也会携带的
  // CDN 探针串（如 IOP 正常文章页内嵌 px-captcha/captcha-delivery 探针脚本——曾致误判 BLOCKED_ANTIBOT）。
  if (
    /__cf_chl|challenge-form|challenge-running|just a moment|attention required|verify you are human|confirm you are a human|datadome\.co|geo-captcha|radware bot manager|we apologize for the inconvenience/i.test(
      head
    )
  ) {
    return 'BLOCKED_ANTIBOT';
  }
  void pageStateFn;
  return 'INVALID_PDF';
}

async function acquirePdf(page, browser, ctx, target, staging, timeoutMs) {
  // 策略一：拟人点击 + CDP 下载捕获（download behavior 已指到 staging）
  const before = new Set(fs.existsSync(staging) ? fs.readdirSync(staging) : []);
  try {
    let locator = null;
    if (target.kind === 'link') {
      const esc = target.hrefRaw.replace(/"/g, '\\"');
      const byHref = page.locator(`a[href="${esc}"]`).first();
      locator = byHref;
    }
    if (locator) {
      await humanScroll(page, {times: 1});
      await humanClick(page, locator, {timeout: 12000});
      const file = await waitForNewFile(staging, before, Math.min(30000, timeoutMs));
      if (file) return {via: 'click-download', file};
    }
  } catch (e) {
    statusLine(`click 路径未取得下载：${String((e && e.message) || e).split('\n')[0]}`);
  }
  // 策略二：带浏览器 cookie 的上下文请求（UA/cookie 与浏览器一致）
  try {
    const resp = await ctx.request.get(target.href, {
      headers: {referer: page.url()},
      timeout: timeoutMs,
      maxRedirects: 8,
    });
    const ct = String(resp.headers()['content-type'] || '');
    if (resp.status() === 200) {
      const buf = await resp.body();
      if (buf && buf.length > 512 && !ct.includes('text/html')) {
        return {via: 'context-request', buf};
      }
      if (buf && validatePdfBuf(buf, {minBytes: 1}).valid) {
        return {via: 'context-request', buf};
      }
      return {via: 'context-request', buf, weak: true};
    }
    statusLine(`context-request 状态码 ${resp.status()}（${ct}）`);
  } catch (e) {
    statusLine(`context-request 失败：${String((e && e.message) || e).split('\n')[0]}`);
  }
  // 策略三：直接导航到 PDF URL 取响应体
  try {
    const resp = await page.goto(target.href, {waitUntil: 'domcontentloaded', timeout: timeoutMs});
    if (resp) {
      const ct = String(resp.headers()['content-type'] || '');
      if (ct.includes('pdf') || (await page.url()).toLowerCase().includes('.pdf')) {
        const buf = await resp.body();
        if (buf) return {via: 'goto-body', buf};
      }
    }
  } catch (e) {
    statusLine(`goto-body 失败：${String((e && e.message) || e).split('\n')[0]}`);
  }
  return null;
}

async function main() {
  const values = parse();
  const items = readItems(values);
  const outDir = path.resolve(values['out-dir'] || 'papers');
  fs.mkdirSync(outDir, {recursive: true});
  const reportFile = path.resolve(
    values.report || path.join(outDir, `paper-download-report-${Date.now()}.json`)
  );
  const staging = path.join(paths().staging, `run-${Date.now()}`);
  const minBytes = Number(values['min-bytes']);
  const timeoutMs = Number(values['timeout-ms']);

  // 会话：不在则自动启动有头 Chrome
  let sess = loadSession(values.session);
  if (!sess || !(await sessionAlive(sess))) {
    statusLine(`会话 ${values.session} 不在，自动启动有头 Chrome…`);
    sess = (await launchSession({name: values.session})).session;
  }
  const {browser, ctx} = await connectSession(sess);
  await setDownloadStaging(browser, staging);
  const page = await ensureTab(browser, ctx, sess);

  statusLine(`共 ${items.length} 篇；out-dir=${outDir}；report=${reportFile}`);
  const records = [];

  // 终态归一：拿到字节后统一校验→落盘/按内容特征分流（点击捕获、上下文请求、直链共用）
  const finishBytes = async (rec, buf, via, shot) => {
    const v = validatePdfBuf(buf, {minBytes});
    if (v.valid) {
      const dest = path.join(outDir, `${slugify(rec.id, 80)}.pdf`);
      fs.writeFileSync(dest, buf);
      rec.status = 'SUCCESS';
      rec.file = dest;
      rec.size = v.size;
      rec.detail = `via=${via}`;
      void shot;
    } else if (v.reason === 'html-not-pdf' || v.reason === 'no-pdf-header') {
      rec.status = await classifyBodyAsStatus(buf);
      rec.detail = `取得内容但非 PDF（reason=${v.reason}, ${v.size}B）——按内容特征分流`;
      rec.shot = shot ?? null;
    } else {
      rec.status = 'INVALID_PDF';
      rec.detail = `校验未过：reason=${v.reason} size=${v.size} minBytes=${v.minBytes || minBytes}`;
    }
  };

  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    const url = itemUrl(it);
    const rec = {index: i + 1, id: it.id, request: url, status: null, detail: '', file: null, size: 0};
    console.log(`PAPER ${i + 1}/${items.length} id=${it.id} start url=${url}`);
    try {
      if (!url) throw new Error('该条目既无 url 也无 doi');
      // 直链 PDF 两条捕获路径：导航触发下载（attachment ⇒ goto 抛 "Download is starting"，
      // 字节已落 staging）/ 导航响应体本身是 PDF（Chrome 查看器页无 DOM 可定位）
      const beforeStaging = new Set(fs.existsSync(staging) ? fs.readdirSync(staging) : []);
      let resp = null;
      let navError = null;
      try {
        resp = await gotoHuman(page, url, {timeoutMs});
      } catch (e) {
        navError = e;
      }
      if (navError) {
        const dlFile = await waitForNewFile(staging, beforeStaging, 8000);
        if (!dlFile) throw navError;
        statusLine('导航以下载告终，从 staging 捕获下载件');
        await finishBytes(rec, fs.readFileSync(dlFile), 'direct-pdf-download', null);
      } else {
        await humanScroll(page, {times: 1});
        const st = await pageState(page);
        const shotDir = path.join(paths().shots, 'paper-download');
        const shot = await shootFixed(page, path.join(shotDir, `paper-${i + 1}-${it.id}.png`.replace(/[^\w.\-]+/g, '_'))).catch(() => null);

        if (st.guard.blocked) {
          rec.status = 'BLOCKED_ANTIBOT';
          rec.detail = `guard=${st.guard.kind} markers=${JSON.stringify(st.guard.markers)}`;
          rec.shot = shot;
        } else if (st.state === 'LOGIN') {
          rec.status = 'NEEDS_LOGIN';
          rec.detail = `页面呈登录态（密码框=${st.hasPassword} url 像登录页）。先跑 browser-session 登录流程再重跑本篇`;
          rec.shot = shot;
        } else {
          // 直链判定：入口 URL 本身即 PDF —— 优先导航下载捕获，再响应体
          let direct = null;
          const dlFile = await waitForNewFile(staging, beforeStaging, 2500);
          if (dlFile) {
            direct = {via: 'direct-pdf-download', buf: fs.readFileSync(dlFile)};
          } else {
            try {
              const ct = String((resp && resp.headers && resp.headers()['content-type']) || '');
              if (ct.includes('pdf')) {
                // 注意：页面级导航到 PDF 时 Chrome 用内置查看器渲染，resp.body() 给的是
                // 查看器 HTML（约 0.5KB）而非 PDF 字节 ⇒ 先走 ctx.request（带浏览器 cookie），
                // 查看器 body 只作兜底
                try {
                  const r2 = await ctx.request.get(page.url(), {timeout: timeoutMs, maxRedirects: 8});
                  if (r2.status() === 200) {
                    const b = await r2.body();
                    if (b && b.length) direct = {via: 'direct-pdf-url-request', buf: b};
                  }
                } catch {}
                if (!direct) {
                  const body = await resp.body();
                  if (body && body.length) direct = {via: 'direct-pdf-url', buf: body};
                }
              }
            } catch {}
          }
          if (direct) {
            await finishBytes(rec, direct.buf, direct.via + '（入口即 PDF 直链）', shot);
          } else {
            const target = await findPdfTarget(page);
            if (!target) {
              rec.status = 'NO_PDF';
              rec.detail = '页面未观测到任何 PDF 目标（无 citation_pdf_url、无 PDF 链接/按钮）';
              rec.shot = shot;
            } else {
              statusLine(`PDF 目标 kind=${target.kind} href=${target.href}${target.text ? ' text=' + JSON.stringify(target.text.slice(0, 40)) : ''}`);
              const got = await acquirePdf(page, browser, ctx, target, staging, timeoutMs);
              let buf = null;
              let srcFile = null;
              if (got) {
                if (got.file) srcFile = got.file;
                else if (got.buf) buf = got.buf;
              }
              if (!buf && srcFile) buf = fs.readFileSync(srcFile);
              if (!buf) {
                rec.status = 'DOWNLOAD_FAILED';
                rec.detail = '三条获取路径（点击下载/上下文请求/直接导航）均未取得内容';
              } else {
                const v = validatePdfBuf(buf, {minBytes});
                if (v.valid) {
                  const dest = path.join(outDir, `${slugify(it.id, 80)}.pdf`);
                  fs.writeFileSync(dest, buf);
                  rec.status = 'SUCCESS';
                  rec.file = dest;
                  rec.size = v.size;
                  rec.detail = `via=${got.via}${srcFile ? ' (下载捕获)' : ''}`;
                } else if (v.reason === 'html-not-pdf' || v.reason === 'no-pdf-header') {
                  rec.status = await classifyBodyAsStatus(buf);
                  rec.detail = `取得内容但非 PDF（reason=${v.reason}, ${v.size}B）——按内容特征分流`;
                } else {
                  rec.status = 'INVALID_PDF';
                  rec.detail = `校验未过：reason=${v.reason} size=${v.size} minBytes=${v.minBytes || minBytes}`;
                }
              }
            }
          }
        }
      }
    } catch (e) {
      rec.status = 'DOWNLOAD_FAILED';
      rec.detail = String((e && e.message) || e).split('\n')[0];
    }

    records.push(rec);
    console.log(`PAPER ${i + 1}/${items.length} id=${it.id} status=${rec.status} ${rec.detail}`);
    // 先落盘再回执：每篇处理完立即写报告文件
    const summary = records.reduce((acc, r) => {
      acc[r.status] = (acc[r.status] || 0) + 1;
      return acc;
    }, {});
    fs.writeFileSync(
      reportFile,
      JSON.stringify({generatedAt: new Date().toISOString(), outDir, summary, records}, null, 2),
      'utf8'
    );

    if (i < items.length - 1) {
      const wait = rand(Number(values['min-delay-ms']), Number(values['max-delay-ms']));
      statusLine(`篇间拟人延时 ${Math.round(wait)}ms（遵守站点速率，串行不并发）`);
      await sleep(wait);
    }
  }

  const summary = records.reduce((acc, r) => {
    acc[r.status] = (acc[r.status] || 0) + 1;
    return acc;
  }, {});
  await resetDownloadBehavior(browser);
  if (!values['keep-open']) {
    // 关会话：论文批跑完收尾（profile 留存，登录态不丢）
    const {closeSession} = await import('../../browser-session/scripts/lib.mjs');
    await detach(browser);
    await closeSession(values.session);
  } else {
    await detach(browser);
  }
  console.log('SUMMARY ' + JSON.stringify(summary));
  result({ok: true, cmd: 'paper-download', total: items.length, summary, outDir, report: reportFile});
}

main().then(() => exitSoon(0)).catch((e) => {
  console.error('ERROR ' + ((e && e.message) || e));
  result({ok: false, error: String((e && e.message) || e)});
  exitSoon(1);
});
