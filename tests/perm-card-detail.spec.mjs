#!/usr/bin/env node
// perm-card-detail.spec.mjs — 审批卡「三要素」硬闸（报障批 2026-11-19）。
//
// 被验缺陷（作者 2026-11-19 17:30 实拍，控制台 delegate 审批面）：
//   卡上只有 「ⓘ 危险操作：」 + 「↗ 来自: stand (delegate-neb)」 + 「允许 Bash？」 +
//   允许/拒绝 + 其他… + 取消/确认 —— **要执行什么命令、为什么算危险、在哪台机器哪个
//   目录**一个字都没有，用户只能盲批。两个实因都在代码里：
//     ① live 帧的 `input` 是 JSON **对象**，前端 `JSON.parse(inputJson)` 恒抛
//        ⇒ 命令正文从来没渲染过（横幅 {detail} 恒空）；
//     ② 来源 badge 用 `sourceSession.substring(0, 12)` 截断 ⇒ `delegate-neb…`。
//
// 本闸的判据（EXIT 0 = 全绿）：
//   A 命令正文：`.perm-cmd` 非空、全文在 DOM（多行逐字保留、不被静默截断）
//   B 折叠：默认 6 行（可见高度 ≤ 6 行 + 1px，且 scrollHeight 更大）
//   C 展开：点「展开全部（共 N 行）」后全部行可见；再点「收起」回 6 行
//   D 理由：`.perm-reason` 非空 + 规则 id + 闸名 + 风险档全部来自后端判定
//   E 作用域：设备 + 工作目录（文件类操作另加目标路径）
//   F 静态锚：chat.js 走 renderPermDetail 且不再截断来源 id；两条历史腿同款；
//     后端帧带 permDetail（AgentSessionExecution）且判定理由单点在
//     ToolReversibility.askReason / BashTool.dangerVerdict
//   G 零 pageerror
//
// 负控（`--negative-control`）：把本次修复**中和**（permDetail.js 的三要素渲染整块
// 关掉）后跑同一套判据 —— **必须红**。红 = 控制有效（exit 0，逐条打红读数）；
// 若中和后仍然全绿 ⇒ 本闸无效（exit 1）。
//
// 跑法：
//   node tests/perm-card-detail.spec.mjs                    # 当前树（应全绿）
//   node tests/perm-card-detail.spec.mjs --negative-control # 中和修复（应红）
//
// 真数据：判据载荷 = `tests/fixtures/perm-card-detail/frames.json` —— 由真机证据
// 脚本 `tests/evidence-perm-card-detail.mjs` 从**真实 askPermission 帧**里摘出
// （每条 = 真 delegate、真判定、真闸名/规则 id），不是手捏的形状。

import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(HERE);
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const FIXTURE = join(HERE, 'fixtures', 'perm-card-detail', 'frames.json');
const NEGATIVE = process.argv.includes('--negative-control');
const OUT_DIR = process.env.PERM_CARD_OUT || join(REPO, '.nebflow', 'evidence', '20261119_perm-card-detail');
const PORT = Number(process.env.PERM_CARD_PORT || 8317);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const failures = [];
const passes = [];
const log = (s) => console.log(s);
const ok = (id, name, extra = '') => { passes.push(`${id} ${name}${extra ? ' — ' + extra : ''}`); log(`PASS  ${id} ${name}${extra ? '  — ' + extra : ''}`); };
const bad = (id, name, detail = '') => { failures.push(`${id} ${name}${detail ? ' — ' + detail : ''}`); log(`FAIL  ${id} ${name}${detail ? '  — ' + detail : ''}`); };

if (!existsSync(FIXTURE)) {
  console.error(`perm-card-detail: fixture missing: ${FIXTURE}`);
  console.error('  （真机证据脚本 tests/evidence-perm-card-detail.mjs 从真实 askPermission 帧写出本件）');
  process.exit(1);
}
const fixture = JSON.parse(readFileSync(FIXTURE, 'utf8'));
const frames = Array.isArray(fixture.frames) ? fixture.frames : [];
if (!frames.length) {
  console.error('perm-card-detail: fixture has no frames');
  process.exit(1);
}

// ── 静态判据（源码锚，与浏览器无关，先跑） ────────────────────────────────
function staticAssertions() {
  const read = (p) => readFileSync(join(REPO, p), 'utf8');
  const chat = read('src/main/resources/web/js/chat.js');
  const persistence = read('src/main/resources/web/js/persistence.js');
  const exec = read('src/main/scala/nebflow/agent/AgentSessionExecution.scala');
  const perms = read('src/main/scala/nebflow/core/permissions.scala');
  const bash = read('src/main/scala/nebflow/core/tools/BashTool.scala');
  const detail = read('src/main/scala/nebflow/core/PermissionCardDetail.scala');

  const call = /renderPermDetail\(bubble, detailInfo/.test(chat)
    && /permDetailFrom\(\{\s*toolName,\s*input: inputJson,\s*permDetail/.test(chat);
  call ? ok('F1', 'live 卡渲染三要素块（renderPermDetail + 帧 permDetail）')
    : bad('F1', 'live 卡必须渲染三要素块', 'chat.js 缺 renderPermDetail(bubble, detailInfo…) 或 permDetail 未下传');

  const stale = /JSON\.parse\(inputJson \|\| '\{\}'\)/.test(chat);
  stale ? bad('F2', '命令正文来源不得是 JSON.parse(inputJson)', '对象形态的 input 会让正文恒空（本缺陷实因①）')
    : ok('F2', '命令正文来源已归一化（对象/字符串/双编码）');

  const truncated = /sourceSession\.substring\(0, 12\)/.test(chat);
  truncated ? bad('F3', '来源 badge 必须显示完整 session id', '仍有 sourceSession.substring(0, 12)（截成 delegate-neb…）')
    : ok('F3', '来源 badge 显示完整 session id');

  const histLegs = (persistence.match(/renderPermDetail\(bubble, histDetail, t\)/g) || []).length;
  histLegs >= 2 ? ok('F4', '两条历史重放腿都渲染三要素', `legs=${histLegs}`)
    : bad('F4', '历史重放腿必须同样渲染三要素', `legs=${histLegs} (expect ≥2)`);

  /"permDetail" ->/.test(exec) ? ok('F5', 'askPermission 帧携带 permDetail')
    : bad('F5', 'askPermission 帧必须携带 permDetail', 'AgentSessionExecution 未下发三要素载荷');

  const reasonSingleSource = /def askReason\(/.test(perms)
    && /def isReversible[\s\S]{0,220}askReason\(toolName, input, mode\)\.isEmpty/.test(perms);
  reasonSingleSource ? ok('F6', '出卡理由与判定同源（ToolReversibility.askReason）')
    : bad('F6', '出卡理由必须来自判定闸自身', 'permissions.scala 缺 askReason 或 isReversible 不再派生自它');

  /def dangerVerdict\(/.test(bash) ? ok('F7', 'Bash 档位判定携带规则 id/标签（dangerVerdict）')
    : bad('F7', 'BashTool 必须给出 dangerVerdict（规则 id + 标签）');

  const hasKeys = ['"command"', '"reason"', '"scope"'].every((k) => detail.includes(k));
  hasKeys ? ok('F8', 'PermissionCardDetail 载荷含 command/reason/scope 三键')
    : bad('F8', 'PermissionCardDetail 必须投影 command/reason/scope');
}

// ── 浏览器判据（真渲染器 + 真样式表） ──────────────────────────────────────
// 样式表清单**从产品 index.html 抄**（顺序一致）：令牌层（stand-tokens /
// design-system）+ 组件层（chat.css …）缺一层就会量错折叠高度 —— 闸不许自带
// 一份手写的样式子集。
function productStylesheets() {
  const html = readFileSync(join(WEB, 'index.html'), 'utf8');
  const hrefs = [...html.matchAll(/<link\s+[^>]*rel="stylesheet"[^>]*href="([^"]+)"/g)].map((m) => m[1]);
  return [...new Set(hrefs)].map((h) => `<link rel="stylesheet" href="/${h.replace(/^\.?\//, '')}">`).join('\n');
}

const HARNESS = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>perm-card-detail harness</title>
${productStylesheets()}
</head><body>
<div id="host"></div>
<script type="module">
import { renderPermDetail, permDetailFrom } from '/js/permDetail.js';
import { t } from '/js/i18n.js';
window.__err = [];
window.addEventListener('error', (e) => window.__err.push(String(e.message || e)));
const res = await fetch('/__fixture.json');
const data = await res.json();
window.__render = (i) => {
  const frame = data.frames[i];
  const host = document.getElementById('host');
  host.className = 'bubble ai perm-dangerous';
  host.style.width = 'min(680px, 92vw)';
  host.textContent = '';
  const detail = permDetailFrom({ toolName: frame.toolName, input: frame.input ?? null, permDetail: frame.permDetail });
  const node = renderPermDetail(host, detail, t);
  return { rendered: !!node, detail };
};
window.__READY = true;
</script>
</body></html>`;

function serve() {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      const url = decodeURIComponent((req.url || '/').split('?')[0]);
      if (url === '/__harness.html') {
        res.writeHead(200, { 'content-type': MIME['.html'] });
        res.end(HARNESS);
        return;
      }
      if (url === '/__fixture.json') {
        res.writeHead(200, { 'content-type': MIME['.json'] });
        res.end(readFileSync(FIXTURE));
        return;
      }
      const file = normalize(join(WEB, url));
      if (!file.startsWith(WEB)) { res.writeHead(403).end(); return; }
      if (!existsSync(file)) { res.writeHead(404).end(); return; }
      let body = readFileSync(file);
      // 负控：把本次修复的中和版喂给同一个渲染器（判据必须变红）
      if (NEGATIVE && file.endsWith(join('js', 'permDetail.js'))) {
        const src = body.toString('utf8');
        const neutralized = src.replace('if (hasCommand) {', 'if (false) {');
        if (neutralized === src) {
          console.error('perm-card-detail: negative control failed to neutralize permDetail.js');
          process.exit(1);
        }
        body = Buffer.from(neutralized, 'utf8');
      }
      res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(body);
    });
    server.listen(PORT, '127.0.0.1', () => resolve(server));
  });
}

/** 读一张卡的三要素状态。 */
async function readCard(page) {
  return page.evaluate(() => {
    const pre = document.querySelector('.perm-detail .perm-cmd');
    const toggle = document.querySelector('.perm-detail .perm-cmd-toggle');
    const reason = document.querySelector('.perm-detail .perm-reason');
    const rule = document.querySelector('.perm-detail .perm-reason-rule');
    const gate = document.querySelector('.perm-detail .perm-reason-gate');
    const badge = document.querySelector('.perm-detail .perm-risk-badge');
    const scopeRows = [...document.querySelectorAll('.perm-detail .perm-scope-row')].map((r) => ({
      k: r.querySelector('.perm-scope-key')?.textContent || '',
      v: r.querySelector('.perm-scope-val')?.textContent || '',
    }));
    const lh = pre ? parseFloat(getComputedStyle(pre).lineHeight) || 0 : 0;
    const cs = pre ? getComputedStyle(pre) : null;
    const padY = cs ? (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0) : 0;
    return {
      text: pre ? pre.textContent : '',
      clientH: pre ? pre.clientHeight : 0,
      contentH: pre ? pre.clientHeight - padY : 0,
      padY,
      scrollH: pre ? pre.scrollHeight : 0,
      lineHeight: lh,
      toggleHidden: toggle ? toggle.hidden : true,
      toggleText: toggle ? toggle.textContent : '',
      reason: reason ? reason.textContent : '',
      rule: rule ? rule.textContent : '',
      gate: gate ? gate.textContent : '',
      badge: badge ? badge.textContent : '',
      scopeRows,
      errors: window.__err || [],
    };
  });
}

async function browserAssertions() {
  let chromium;
  try {
    ({ chromium } = await import('playwright'));
  } catch {
    try {
      ({ chromium } = await import('@playwright/test'));
    } catch (e) {
      bad('G1', 'playwright 不可用', String(e && e.message));
      return;
    }
  }
  const server = await serve();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(String(e)));
    await page.goto(`http://127.0.0.1:${PORT}/__harness.html`, { waitUntil: 'load' });
    await page.waitForFunction(() => window.__READY === true, null, { timeout: 15000 });

    for (let i = 0; i < frames.length; i++) {
      const frame = frames[i];
      const d = frame.permDetail || {};
      const cmd = typeof d.command === 'string' ? d.command : '';
      const tag = frame.name || d.tool || `frame${i}`;
      const rendered = await page.evaluate((idx) => window.__render(idx), i);
      await page.waitForTimeout(60);
      const st = await readCard(page);

      if (rendered.rendered && st.text && st.text.trim() && st.text === cmd) {
        ok(`A1[${tag}]`, '命令正文节点非空且全文一致', `${st.text.length} 字 / ${cmd.split('\n').length} 行`);
      } else {
        bad(`A1[${tag}]`, '命令正文节点必须非空且未被静默截断',
          `node=${JSON.stringify((st.text || '').slice(0, 60))} fixture=${JSON.stringify(cmd.slice(0, 60))}`);
      }

      const lineCount = cmd.split('\n').length;
      if (lineCount > 6) {
        const sixLineH = st.lineHeight * 6;
        // 内容高（去掉 padding）≈ 6 行；容器仍可滚动 ⇒ 折叠真的发生在第 6 行
        if (st.scrollH > st.clientH + 1 && st.contentH <= sixLineH + 2) {
          ok(`B1[${tag}]`, '命令默认折叠为 6 行（滚动看全）',
            `内容高=${st.contentH} 6 行=${sixLineH.toFixed(1)} clientH=${st.clientH} scrollH=${st.scrollH}`);
        } else {
          bad(`B1[${tag}]`, '命令默认应折叠为 6 行',
            `内容高=${st.contentH} 6 行=${sixLineH.toFixed(1)} clientH=${st.clientH} scrollH=${st.scrollH}`);
        }
        if (!st.toggleHidden && /展开全部/.test(st.toggleText)) {
          ok(`B2[${tag}]`, '折叠开关可见且写明总行数', st.toggleText);
        } else {
          bad(`B2[${tag}]`, '折叠开关必须可见并写明「展开全部（共 N 行）」', `hidden=${st.toggleHidden} text=${JSON.stringify(st.toggleText)}`);
        }
        const hasToggle = (await page.locator('.perm-cmd-toggle').count()) > 0 && !st.toggleHidden;
        if (!hasToggle) {
          bad(`C1[${tag}]`, '展开/收起必须有效', '开关不存在 ⇒ 超长命令无法读全');
        } else {
          await page.click('.perm-cmd-toggle');
          const expanded = await page.evaluate(() => {
            const pre = document.querySelector('.perm-cmd');
            return { clientH: pre.clientHeight, scrollH: pre.scrollHeight, expanded: pre.classList.contains('expanded') };
          });
          await page.click('.perm-cmd-toggle');
          const collapsed = await page.evaluate(() => {
            const pre = document.querySelector('.perm-cmd');
            return { clientH: pre.clientHeight, scrollH: pre.scrollHeight, expanded: pre.classList.contains('expanded') };
          });
          const expandedOk = expanded.expanded && expanded.clientH >= expanded.scrollH - 2;
          const collapsedOk = !collapsed.expanded && collapsed.scrollH > collapsed.clientH + 1;
          expandedOk && collapsedOk
            ? ok(`C1[${tag}]`, '展开全部 / 收起 双向有效（超长内容不丢）', `展开H=${expanded.clientH} 全高=${expanded.scrollH} 收起H=${collapsed.clientH}`)
            : bad(`C1[${tag}]`, '展开/收起必须有效', `expanded=${JSON.stringify(expanded)} collapsed=${JSON.stringify(collapsed)}`);
        }
      }

      const r = d.reason || {};
      const reasonOk = !!st.reason.trim()
        && (!r.label || st.reason.includes(String(r.label)))
        && (!r.ruleId || st.rule.includes(String(r.ruleId)))
        && (!r.gate || st.gate.includes(String(r.gate)));
      reasonOk
        ? ok(`D1[${tag}]`, '为什么危险：标签 + 规则 id + 闸名（后端判定）', `${st.reason.trim()} | ${st.rule.trim()} | ${st.gate.trim()}`)
        : bad(`D1[${tag}]`, '理由块必须非空且带规则 id/闸名', `reason=${JSON.stringify(st.reason)} rule=${JSON.stringify(st.rule)} gate=${JSON.stringify(st.gate)}`);
      if (Number(r.level) > 0 && /L\d/.test(st.badge)) {
        ok(`D2[${tag}]`, '风险档可见', st.badge.trim());
      } else {
        bad(`D2[${tag}]`, '风险档必须可见', `badge=${JSON.stringify(st.badge)} level=${r.level}`);
      }

      const s = d.scope || {};
      const rows = st.scopeRows;
      const keysText = rows.map((x) => x.k).join('/');
      const nonEmpty = rows.filter((x) => x.v.trim()).length;
      const needsTarget = !!(s.target && String(s.target).trim());
      const scopeOk = rows.length >= (needsTarget ? 3 : 2)
        && nonEmpty === rows.length
        && keysText.includes('设备') && keysText.includes('工作目录')
        && (!needsTarget || keysText.includes('目标路径'));
      scopeOk
        ? ok(`E1[${tag}]`, '作用域齐全（设备/工作目录' + (needsTarget ? '/目标路径' : '') + '）',
          rows.map((x) => `${x.k}=${x.v}`).join(' · ').slice(0, 220))
        : bad(`E1[${tag}]`, '作用域必须有值', `rows=${JSON.stringify(rows)} needsTarget=${needsTarget}`);
    }

    const errs = pageErrors.concat((await readCard(page)).errors || []);
    errs.length === 0 ? ok('G1', '零 pageerror') : bad('G1', '页面不得报错', errs.slice(0, 3).join(' | '));
  } finally {
    await browser.close().catch(() => {});
    server.close();
  }
}

staticAssertions();
await browserAssertions();

const summary = [
  `perm-card-detail ${NEGATIVE ? '(negative control)' : ''} frames=${frames.length}`,
  `passes=${passes.length} failures=${failures.length}`,
  ...passes.map((p) => '  PASS ' + p),
  ...failures.map((f) => '  FAIL ' + f),
].join('\n');
log(summary);
try {
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(join(OUT_DIR, NEGATIVE ? 'gate-negative-control.txt' : 'gate-after.txt'), summary + '\n');
} catch { /* evidence dir optional */ }

if (NEGATIVE) {
  if (failures.length > 0) {
    log(`NEGATIVE-CONTROL: RED as expected (${failures.length} assertion(s) red with the fix neutralized) — 闸有效`);
    process.exit(0);
  }
  log('NEGATIVE-CONTROL: still green with the fix neutralized — 本闸无效（判据没有真的盯住三要素）');
  process.exit(1);
}

if (failures.length) {
  log(`perm-card-detail: FAILED (${failures.length})`);
  process.exit(1);
}
log('perm-card-detail: OK (命令正文 + 为什么危险 + 作用域 三要素齐全并过闸)');
process.exit(0);
