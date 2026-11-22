#!/usr/bin/env node
// perm-card-detail.spec.mjs — 审批卡「三要素」硬闸（报障批 2026-11-19 / 视觉返工批同日 19:38）。
//
// 被验缺陷①（作者 2026-11-19 17:30 实拍，控制台 delegate 审批面）：
//   卡上只有 「ⓘ 危险操作：」 + 「↗ 来自: stand (delegate-neb)」 + 「允许 Bash？」 +
//   允许/拒绝 + 其他… + 取消/确认 —— **要执行什么命令、为什么算危险、在哪台机器哪个
//   目录**一个字都没有，用户只能盲批。两个实因都在代码里：
//     ① live 帧的 `input` 是 JSON **对象**，前端 `JSON.parse(inputJson)` 恒抛
//        ⇒ 命令正文从来没渲染过（横幅 {detail} 恒空）；
//     ② 来源 badge 用 `sourceSession.substring(0, 12)` 截断 ⇒ `delegate-neb…`。
//
// 被验缺陷②（视觉，作者 2026-11-19 19:38 看 06-desktop-1440-write-card.png 逐字
// 「太丑了这也，要在原权限卡的基础上进行优化，然后外部的大方框去掉」）：
//   上一版把三要素装进**新增的外层容器**并在气泡外圈留了一圈彩边 ⇒ 框里套框。
//   本闸因此多两条结构判据：H1 = 气泡零边框（外面那圈大方框不存在，档位语义仍在
//   横幅底色上）；H2 = 三要素落在**原卡盒**内容流里、且在问句之前。
//
// 本闸的判据（EXIT 0 = 全绿）：
//   A 命令正文：`.perm-cmd` 非空、全文在 DOM（多行逐字保留、不被静默截断）
//   B 折叠：默认 6 行（可见高度 ≤ 6 行 + 1px，且 scrollHeight 更大）
//   C 展开：点「展开全部（共 N 行）」后全部行可见；再点「收起」回 6 行
//   D 理由：**一行**里的 人话标签 + 规则 id + 闸名 + 风险档，全部来自后端判定
//   E 作用域：**一行**里的 设备 + 工作目录（文件类操作另加目标路径）
//   F 静态锚：chat.js 把三要素插进卡盒（问句之前）且不再截断来源 id；两条历史腿同款；
//     后端帧带 permDetail；判定理由单点在 ToolReversibility.askReason /
//     BashTool.dangerVerdict；且不得残留 `.perm-detail` 外层容器 / 气泡彩边
//   G 零 pageerror
//   H1 气泡零边框（外层大方框去掉，档位底色仍在横幅上）
//   H2 三要素在卡盒内容流内、位于问句之前（内联，不是卡外新块）
//   H3 375 视口零横向溢出
//   I  去重（瘦身批 2026-11-21 · 作者令「信息不要重复——同一事实卡内只出现一次」）：
//      I1 危险类型（档位 + 人话标签）整卡只出现一次（卡首横幅承载）
//      I2 作用域目标路径整卡只出现一次（作用域行承载，正文块不再复述）
//      I3 Write/Edit 正文只一份（无 permissionDetails 第二完整区 / 无 <details> 复刻）
//      I4 静态：permissionDetails.js 已退役，调用点不存在
//
// 负控（`--negative-control`）：把本次渲染**整块中和**（renderPermDetail 直接返回
// null）后跑同一套判据 —— **必须红**。红 = 控制有效（exit 0，逐条打红读数）；
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
  const css = read('src/main/resources/web/css/chat.css');
  const exec = read('src/main/scala/nebflow/agent/AgentSessionExecution.scala');
  const perms = read('src/main/scala/nebflow/core/permissions.scala');
  const bash = read('src/main/scala/nebflow/core/tools/BashTool.scala');
  const detail = read('src/main/scala/nebflow/core/PermissionCardDetail.scala');

  // live 腿：三要素插进 showOptions 交出的卡盒（问句之前）
  const call = /const cardBox = showOptions\(/.test(chat)
    && /renderPermDetail\(cardBox, detailInfo, t, \{ before: cardBox\.firstElementChild,/.test(chat)
    && /permDetailFrom\(\{\s*toolName,\s*input: inputJson,\s*permDetail/.test(chat);
  call ? ok('F1', 'live 卡把三要素插进原卡盒（问句之前）')
    : bad('F1', 'live 卡必须在原卡盒里渲染三要素', 'chat.js 缺 cardBox/showOptions 返回值或 renderPermDetail(cardBox, detailInfo, …, before)');

  const stale = /JSON\.parse\(inputJson \|\| '\{\}'\)/.test(chat);
  stale ? bad('F2', '命令正文来源不得是 JSON.parse(inputJson)', '对象形态的 input 会让正文恒空（本缺陷实因①）')
    : ok('F2', '命令正文来源已归一化（对象/字符串/双编码）');

  const truncated = /sourceSession\.substring\(0, 12\)/.test(chat);
  truncated ? bad('F3', '来源 badge 必须显示完整 session id', '仍有 sourceSession.substring(0, 12)（截成 delegate-neb…）')
    : ok('F3', '来源 badge 显示完整 session id');

  const histLegs = (persistence.match(/renderPermDetail\(box, histDetail, t, \{ before: q,/g) || []).length;
  histLegs >= 2 ? ok('F4', '两条历史重放腿都渲染三要素（且同落卡盒问句之前）', `legs=${histLegs}`)
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

  // F9（视觉返工批）：外层容器 / 气泡彩边必须已删除 —— 否则又回到「框里套框」。
  const leftoverContainer = /\.perm-detail\s*\{|\.perm-detail-block\b|\.perm-detail-label\b/.test(css);
  const leftoverBubbleBorder = /\.bubble\.ai\.perm-(warning|dangerous|critical)\s*\{[^}]*border\s*:/s.test(css);
  (!leftoverContainer && !leftoverBubbleBorder)
    ? ok('F9', '无外层容器、无气泡彩边（外部大方框已去掉）')
    : bad('F9', '不得残留外层容器或气泡彩边', `container=${leftoverContainer} bubbleBorder=${leftoverBubbleBorder}`);

  // I4（瘦身批）：第二份「完整区」模块已退役 —— 文件不在、无任何 import / 调用点。
  const permissionDetailsFile = existsSync(join(WEB, 'js', 'permissionDetails.js'));
  const reuse = ['chat.js', 'persistence.js'].filter((f) => /from '\.\/permissionDetails\.js'|\bpermissionDetails\(/.test(read('src/main/resources/web/js/' + f)));
  (!permissionDetailsFile && reuse.length === 0)
    ? ok('I4', '第二完整区 permissionDetails.js 已退役（无文件、无调用点）')
    : bad('I4', '不得残留第二份完整区', `file=${permissionDetailsFile} refs=${reuse.join(',')}`);
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

// 宿主复刻 = 产品 live 卡的外形：`.bubble.ai.ob-ghost-seat` + 档位类，卡盒由
// showOptions 建（`.option-box` + `.option-q` + `.option-opts`）。这里只复刻**落点**，
// 三要素本身仍由产品真函数 renderPermDetail 生成。
const HARNESS = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>perm-card-detail harness</title>
${productStylesheets()}
</head><body>
<div id="chat"><div class="row ai"><div id="host"></div></div></div>
<script type="module">
import { renderPermDetail, permDetailFrom } from '/js/permDetail.js';
import { t } from '/js/i18n.js';
window.__err = [];
window.addEventListener('error', (e) => window.__err.push(String(e.message || e)));
const LEVEL_CLS = { 1: 'perm-warning', 2: 'perm-dangerous', 3: 'perm-critical' };
const data = await (await fetch('/__fixture.json')).json();
window.__render = (i) => {
  const frame = data.frames[i];
  const level = Number(frame.permDetail && frame.permDetail.reason && frame.permDetail.reason.level) || 0;
  const host = document.getElementById('host');
  host.className = 'bubble ai ob-ghost-seat' + (LEVEL_CLS[level] ? ' ' + LEVEL_CLS[level] : '');
  host.style.width = 'min(680px, 92vw)';
  host.textContent = '';
  // 档位横幅（chat.js 生产，同款文案键）
  const keys = { 1: 'chat.permLevel.warning', 2: 'chat.permLevel.dangerous', 3: 'chat.permLevel.critical' };
  if (keys[level]) {
    const banner = document.createElement('div');
    banner.className = 'perm-danger-banner';
    const riskLabel = (frame.permDetail.reason && (frame.permDetail.reason.label || frame.permDetail.reason.levelLabel)) || '';
    const span = document.createElement('span');
    span.textContent = t(keys[level], { detail: riskLabel });
    banner.appendChild(span);
    host.appendChild(banner);
  }
  // 卡盒（showOptions 的产品形状）
  const box = document.createElement('div');
  box.className = 'option-box';
  const q = document.createElement('div');
  q.className = 'option-q';
  q.textContent = t('chat.allowTool', { tool: frame.toolName });
  box.appendChild(q);
  const opts = document.createElement('div');
  opts.className = 'option-opts';
  for (const label of [t('chat.allow'), t('chat.deny')]) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'option-btn';
    b.textContent = label;
    opts.appendChild(b);
  }
  box.appendChild(opts);
  host.appendChild(box);
  const detail = permDetailFrom({ toolName: frame.toolName, input: frame.input ?? null, permDetail: frame.permDetail });
  // 与 chat.js 生产调用同形：横幅承载档位；>=2 档横幅文案带 {detail}=人话标签 ⇒ 事实块不复述。
  const riskLabel = (frame.permDetail.reason && (frame.permDetail.reason.label || frame.permDetail.reason.levelLabel)) || '';
  const node = renderPermDetail(box, detail, t, { level, before: box.firstElementChild, labelShown: level >= 2 && !!riskLabel });
  return { rendered: !!node, level, detail };
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
      // 负控：把本次修复**整块**中和（renderPermDetail 恒返回 null ⇒ 三要素一个都不挂），
      // 同一套判据喂给同一个渲染器，必须变红。
      if (NEGATIVE && file.endsWith(join('js', 'permDetail.js'))) {
        const src = body.toString('utf8');
        const neutralized = src.replace('  if (!hasCommand && !reason && !scope) return null;', '  return null;');
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
    const box = document.querySelector('.option-box');
    const pre = box ? box.querySelector('.perm-cmd') : null;
    const toggle = box ? box.querySelector('.perm-cmd-toggle') : null;
    const reason = box ? box.querySelector('.perm-reason') : null;
    const rule = box ? box.querySelector('.perm-reason-rule') : null;
    const gate = box ? box.querySelector('.perm-reason-gate') : null;
    const label = box ? box.querySelector('.perm-reason-label') : null;
        const scopeItems = box ? [...box.querySelectorAll('.perm-scope-item')].map((r) => ({
      k: r.querySelector('.perm-scope-key')?.textContent || '',
      v: r.querySelector('.perm-scope-val')?.textContent || '',
    })) : [];
    const lh = pre ? parseFloat(getComputedStyle(pre).lineHeight) || 0 : 0;
    const cs = pre ? getComputedStyle(pre) : null;
    const padY = cs ? (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0) : 0;
    const host = document.getElementById('host');
    return {
      hostText: host ? host.textContent : '',
      bannerText: (() => { const b = document.querySelector('.perm-danger-banner'); return b ? b.textContent : ''; })(),
      text: pre ? pre.textContent : '',
      clientH: pre ? pre.clientHeight : 0,
      contentH: pre ? pre.clientHeight - padY : 0,
      padY,
      scrollH: pre ? pre.scrollHeight : 0,
      lineHeight: lh,
      toggleHidden: toggle ? toggle.hidden : true,
      toggleText: toggle ? toggle.textContent : '',
      reason: reason ? reason.textContent : '',
      reasonLineCount: reason ? Math.round(reason.getBoundingClientRect().height / (parseFloat(getComputedStyle(reason).fontSize) * 1.5)) : 0,
      label: label ? label.textContent : '',
      rule: rule ? rule.textContent : '',
      gate: gate ? gate.textContent : '',
      scopeItems,
      // 内联落点：三要素必须在卡盒里、且在问句之前
      inBox: !!box && !!pre && box.contains(pre),
      beforeQuestion: !!box && !!pre && !!(box.querySelector('.option-q')) &&
        !!(pre.compareDocumentPosition(box.querySelector('.option-q')) & Node.DOCUMENT_POSITION_FOLLOWING),
      bubbleBorder: (() => {
        const b = document.getElementById('host');
        const s = getComputedStyle(b);
        return `${s.borderTopWidth} ${s.borderTopStyle} ${s.borderTopColor}`;
      })(),
      bannerBg: (() => {
        const ban = document.querySelector('.perm-danger-banner');
        return ban ? getComputedStyle(ban).backgroundColor : 'none';
      })(),
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
      // 正文源（瘦身批）：Write = 要写入的内容原文（路径不再复述）；其余 = 命令正文
      const body = frame.toolName === 'Write' && frame.input && typeof frame.input.content === 'string' ? frame.input.content : cmd;
      const tag = frame.name || d.tool || `frame${i}`;
      const rendered = await page.evaluate((idx) => window.__render(idx), i);
      await page.waitForTimeout(60);
      const st = await readCard(page);

      if (rendered.rendered && st.text && st.text.trim() && st.text === body) {
        ok(`A1[${tag}]`, '调用正文节点非空且全文一致', `${st.text.length} 字 / ${body.split('\n').length} 行`);
      } else {
        bad(`A1[${tag}]`, '命令正文节点必须非空且未被静默截断',
          `node=${JSON.stringify((st.text || '').slice(0, 60))} fixture=${JSON.stringify(cmd.slice(0, 60))}`);
      }

      const lineCount = body.split('\n').length;
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
      // 理由行 = 人话标签（降噪令：闸名 / 规则 id 不上卡）；标签按去重契约只在横幅（>=2 档）或此行（warning 档）一次
      // 标签或在理由行、或已在横幅（去重：横幅承载时理由行为空，属正常）
      const labelVisible = !!st.reason.trim() || (!!r.label && String(st.bannerText).includes(String(r.label)));
      const reasonOk = labelVisible && !st.rule.trim() && !st.gate.trim();
      reasonOk
        ? ok(`D1[${tag}]`, '为什么危险（一行）：人话标签；卡上不出现闸名/规则 id（降噪）', `${st.reason.trim()}`)
        : bad(`D1[${tag}]`, '理由行必须非空，且卡上不得出现闸名/规则 id', `reason=${JSON.stringify(st.reason)} rule=${JSON.stringify(st.rule)} gate=${JSON.stringify(st.gate)}`);
      // D2（瘦身批去重）：人话标签整卡只出现一次 —— 在卡首横幅（危险类型），理由行不复述。
      const labelCount = r.label ? st.hostText.split(String(r.label)).length - 1 : 0;
      if (!r.label || labelCount === 1) {
        ok(`D2[${tag}]`, '人话标签整卡只出现一次（横幅承载危险类型）', `count=${labelCount}`);
      } else {
        bad(`D2[${tag}]`, '人话标签必须整卡只出现一次', `count=${labelCount} label=${JSON.stringify(r.label)}`);
      }
      // D3：权限卡不显示风险分档（作者第 4 轮裁定）—— 理由行任何档位都不得出现 L 徽标
      if (!st.badge) {
        ok(`D3[${tag}]`, '权限卡不显示风险分档（理由行无 L 徽标）', `level=${r.level}`);
      } else {
        bad(`D3[${tag}]`, '权限卡不得显示风险分档', `badge=${JSON.stringify(st.badge)} level=${r.level}`);
      }

      const s = d.scope || {};
      const items = st.scopeItems;
      const keysText = items.map((x) => x.k).join('/');
      const nonEmpty = items.filter((x) => x.v.trim()).length;
      const needsTarget = !!(s.target && String(s.target).trim());
      const scopeOk = items.length >= (needsTarget ? 3 : 2)
        && nonEmpty === items.length
        && keysText.includes('设备') && keysText.includes('工作目录')
        && (!needsTarget || keysText.includes('目标路径'));
      scopeOk
        ? ok(`E1[${tag}]`, '作用域齐全（设备/工作目录' + (needsTarget ? '/目标路径' : '') + '）',
          items.map((x) => `${x.k}=${x.v}`).join(' · ').slice(0, 220))
        : bad(`E1[${tag}]`, '作用域必须有值', `items=${JSON.stringify(items)} needsTarget=${needsTarget}`);

      // H1 外层大方框已去掉：真 live 类组合（ob-ghost-seat + perm-*）下气泡零边框，
      //    档位语义仍留在横幅底色上（不是靠删语义色换来的干净）。
      const noBorder = /^(0px|0)\b/.test(st.bubbleBorder) || st.bubbleBorder.includes('none');
      const bannerTinted = st.bannerBg && st.bannerBg !== 'rgba(0, 0, 0, 0)' && st.bannerBg !== 'transparent';
      (noBorder && bannerTinted)
        ? ok(`H1[${tag}]`, '气泡零边框（无外层大方框）+ 档位底色仍在横幅', `border=${st.bubbleBorder} banner=${st.bannerBg}`)
        : bad(`H1[${tag}]`, '外层大方框必须去掉且档位底色保留', `border=${st.bubbleBorder} banner=${st.bannerBg}`);

      // H2 内联落点：三要素在**原卡盒**里、且在问句之前。
      (st.inBox && st.beforeQuestion)
        ? ok(`H2[${tag}]`, '三要素内联在原卡盒内容流（问句之前）')
        : bad(`H2[${tag}]`, '三要素必须在原卡盒内、问句之前', `inBox=${st.inBox} beforeQuestion=${st.beforeQuestion}`);
    }

    // H3 375 视口：三要素不撑破卡面（长命令靠横向滚动）
    const mobile = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
    const mpage = await mobile.newPage();
    mpage.on('pageerror', (e) => pageErrors.push('mobile: ' + String(e)));
    await mpage.goto(`http://127.0.0.1:${PORT}/__harness.html`, { waitUntil: 'load' });
    await mpage.waitForFunction(() => window.__READY === true, null, { timeout: 15000 });
    await mpage.evaluate(() => window.__render(0));
    await mpage.waitForTimeout(150);
    const m = await mpage.evaluate(() => ({
      docScrollW: document.documentElement.scrollWidth,
      boxW: document.querySelector('.option-box')?.getBoundingClientRect().width || 0,
      preW: document.querySelector('.perm-cmd')?.getBoundingClientRect().width || 0,
    }));
    m.docScrollW <= 375
      ? ok('H3', '375 视口零横向溢出', `scrollWidth=${m.docScrollW} boxW=${m.boxW.toFixed(1)} cmdW=${m.preW.toFixed(1)}`)
      : bad('H3', '375 视口不得横向溢出', `scrollWidth=${m.docScrollW} boxW=${m.boxW.toFixed(1)}`);
    await mobile.close();

    // I2 目标路径整卡只一次；I3 Write 正文只一份（内容原文出现次数 = 1）
    const wr = frames.findIndex((f) => f.toolName === 'Write');
    if (wr >= 0) {
      const wf = frames[wr];
      await page.evaluate((idx) => window.__render(idx), wr);
      const wst = await readCard(page);
      const target = (wf.permDetail && wf.permDetail.scope && wf.permDetail.scope.target) || '';
      const tgtCount = target ? wst.hostText.split(target).length - 1 : 0;
      tgtCount === 1
        ? ok('I2[Write]', '目标路径整卡只出现一次（作用域行承载）', `count=${tgtCount}`)
        : bad('I2[Write]', '目标路径必须整卡只出现一次', `count=${tgtCount}`);
      const content = (wf.input && wf.input.content) || '';
      const bodyCount = content ? wst.hostText.split(content).length - 1 : 0;
      bodyCount === 1
        ? ok('I3[Write]', 'Write 正文只一份（无第二完整区复刻）', `count=${bodyCount}`)
        : bad('I3[Write]', 'Write 正文必须只出现一份', `count=${bodyCount}`);
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
log('perm-card-detail: OK (三要素内联在原卡里：命令正文 + 一行理由 + 一行作用域，且无外层大方框)');
process.exit(0);
