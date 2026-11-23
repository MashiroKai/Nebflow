// 设置页「栏目名重复」静态闸（2026-11-18 设置页结构治理批 · 第一件）。
//
// 作者实拍报障：「每一栏都出现两次栏目名，比如『高级 高级』」。成因 = renderSettings 的
// 单卡分区同时给 section 头（<h2>）与卡内标题（setCard 的 title）写了**同一个 i18n key**
// ⇒ 同一句词面上屏两次。
//
// 本闸逐分区核对：`data-panel-group` 的 section 标题 key 与**该分区内每张 setCard 的
// 标题 key** 必须互不相同（多卡分区如「模型与服务」的卡名本来就不同名，一个不动；单卡
// 分区则卡名整件撤除，保留卡外 section 头）。
//
// 自证检测力（破坏 → 红 / 还原 → 绿）：给任一单卡分区把 `setCard({ body:` 改回
// `setCard({ title: t('settings.<同 key>'), body:` ⇒ 本闸 exit 1。
//
// 覆盖范围 = src/main/resources/web/js/sidebar.js 的 renderSettings 模板。
// 反假绿守卫：模板锚点必须命中（否则红），且解析到的分区数 ≥ 5、我的三个责任分区
// （runtime / advanced / about）必须在场 —— 正则失配或改动被整件回退都不会静默放行。
//
// 已知未闭合（不计入失败，见报告「冲突提示」段）：account 分区的卡内标题
// `settings.account` 定义在 js/neblink.js:433 的 accountCardHTML（非 sidebar.js 本文件），
// 与 section 头同名 ⇒ 仍是「账号 账号」。该面归**在飞的「设置账户栏移除」批**所有，
// 本批按冲突预警不动它；该批落地后本段失效，无需改闸。
import { readFileSync } from 'node:fs';

const SRC = process.env.SETTINGS_SIDEBAR_JS || 'src/main/resources/web/js/sidebar.js';
const src = readFileSync(SRC, 'utf8');

// 通用 + 运行时 已融合为单块「通用」（作者令 2026-11-22 18:51）⇒ 分区 = 4 个。
const REQUIRED = ['general', 'advanced', 'about'];
const EXPECTED_MIN = 4;

// ── 取 renderSettings 的模板串（content.innerHTML = ` ... `;）──────────────
const start = src.indexOf('content.innerHTML = `');
const rules = [];
if (start < 0) {
  rules.push(['template-literal-anchor found', false]);
} else {
  const end = src.indexOf('`;', start);
  const tpl = src.slice(start, end);

  // 逐分区切块：<section class="sp-panel-group" data-panel-group="X"><h2>${t('settings.K')}</h2> …
  const chunks = tpl.split('<section class="sp-panel-group"').slice(1);
  const HEAD = /^\s*data-panel-group="([\w-]+)"><h2>\$\{t\('([^']+)'\)\}<\/h2>/;
  // 卡内标题判定：只认「setCard({ 的第一个属性就是 title」，避免把 setRow / setNavRow
  // 的 title（行标题，本就该另起词）误当卡标题。
  const CARD_TITLE = /setCard\(\{\s*title:\s*t\('([^']+)'\)/g;

  const groups = chunks
    .map((ch) => {
      const h = ch.match(HEAD);
      if (!h) return null;
      const cardKeys = [...ch.matchAll(CARD_TITLE)].map((m) => m[1]);
      return { group: h[1], sectionKey: h[2], cardKeys };
    })
    .filter(Boolean);

  const seen = groups.map((g) => g.group);
  rules.push(['template-literal-anchor found', true]);
  rules.push([`panel groups parsed >= ${EXPECTED_MIN} (got ${groups.length})`, groups.length >= EXPECTED_MIN]);
  for (const r of REQUIRED) {
    rules.push([`required group present: ${r}`, seen.includes(r)]);
  }
  for (const g of groups) {
    const dupe = g.cardKeys.includes(g.sectionKey);
    rules.push([`no section/card title dupe: ${g.group} (${g.sectionKey})`, !dupe]);
    console.log(`  ${g.group}: section=${g.sectionKey} cards=[${g.cardKeys.join(', ') || '-'}]${dupe ? '  ⇐ DUPE' : ''}`);
  }
  console.log('  (open, out of scope) account: card title lives in js/neblink.js:433 accountCardHTML');
}

for (const [name, ok] of rules) console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
const failures = rules.filter(([, ok]) => !ok).length;
console.log(`settings-section-dedupe: ${rules.length} checks, ${failures} violations; scope: renderSettings section vs card titles`);
if (failures) process.exitCode = 1;
