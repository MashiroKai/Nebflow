#!/usr/bin/env node
// check-interaction-card-family.mjs — 主会话交互卡「统一风格」静态闸（作者裁定 B，2026-11-21）。
//
// 目标：主会话里的交互卡（askuser 卡 / 权限卡 / LLM 失败卡 ……）**卡面**只能由同一套令牌
// 承载。任何新造的卡面（在卡族选择器上另写渐变 / 圆角 / 描边 / 阴影的字面量）即红，逼它回到
// `var(--…)` 令牌——风格改一处全族生效，不会再出现「两张卡审美两个版本」。
//
// 口径（逐条）：
//   R1 卡面表面属性（background / background-image / border* / border-radius / box-shadow）
//      若出现在卡族选择器（FAMILY_CLASSES 中任一根类）的规则里，其值必须经 `var(--…)` 取得；
//      出现 px 数值 / #十六进制 / rgb(a)( / gradient 字面量即红。
//   R2 卡族登记 FAMILY_CLASSES：新增交互卡 = 在此登记其根类（单点，禁止散落）。
//   白名单 SURFACE_ALLOWLIST：具体「文件|选择器」+ 理由。默认空；新增须作者裁定并写理由。
//
// 与 check-component-contract 的边界：后者管 innerHTML 裸赋值、attachShadow、跨面 DOM 直查；
// 本闸只读 CSS 的卡面声明，不看 JS DOM 写法，两者判据不重叠、不双报。
//
// 用法：
//   node scripts/check-interaction-card-family.mjs                      # 扫仓库，红则非零退出
//   node scripts/check-interaction-card-family.mjs --negative-control   # 注入违例，必须被抓到
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src/main/resources/web/css');

/** 卡族根类登记（R2）。新增主会话交互卡 = 在此登记。 */
// `.nfc` = NebulaInteractionCard（接管卡）根类；登记于 2026-11-21 作者裁定（新卡面须过规范闸）。
export const FAMILY_CLASSES = ['option-box', 'llm-failure-card', 'nfc'];

/** 卡面表面属性（R1）。 */
const SURFACE_PROPS = new Set([
  'background', 'background-image', 'border', 'border-radius', 'box-shadow',
  'border-top', 'border-bottom', 'border-left', 'border-right',
]);

/**
 * 白名单（「文件|选择器」→ 理由）。默认空：卡族现有规则已全 token，无例外。
 */
export const SURFACE_ALLOWLIST = new Map();

const LITERAL = /\d+px|#[0-9a-fA-F]{3,8}\b|rgba?\(|gradient/;
const famRe = new RegExp(`\\.(?:${FAMILY_CLASSES.map(c => c.replace(/-/g, '\\-')).join('|')})(?![\\w-])`);

/** 去注释、抽取「选择器 { 声明 }」（无嵌套；@media 内层规则照样命中）。 */
export function extractRules(css) {
  const s = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const out = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(s))) out.push({ selector: m[1].trim().replace(/\s+/g, ' '), body: m[2] });
  return out;
}

/** 对单个 CSS 文本返回违例列表（纯函数，负控复用）。 */
export function scanCss(fileName, css, allowlist = SURFACE_ALLOWLIST) {
  const violations = [];
  for (const { selector, body } of extractRules(css)) {
    if (!famRe.test(selector)) continue;
    if (allowlist.has(`${fileName}|${selector}`)) continue;
    for (const decl of body.split(';')) {
      const i = decl.indexOf(':');
      if (i < 0) continue;
      const prop = decl.slice(0, i).trim().toLowerCase();
      const value = decl.slice(i + 1).trim();
      if (!SURFACE_PROPS.has(prop)) continue;
      if (value.includes('var(')) continue;
      if (!LITERAL.test(value)) continue;
      violations.push({ file: fileName, selector, prop, value });
    }
  }
  return violations;
}

function scanRepo() {
  const all = [];
  for (const f of readdirSync(CSS_DIR).filter(n => n.endsWith('.css')).sort()) {
    all.push(...scanCss(f, readFileSync(join(CSS_DIR, f), 'utf8')));
  }
  return all;
}

function main() {
  if (process.argv.includes('--negative-control')) {
    const fake = '.option-box.llm-failure-card { background: linear-gradient(#fff, #eee); border-radius: 14px; }';
    const hits = scanCss('negative-control.css', fake);
    if (hits.length < 2) {
      console.error(`NEGATIVE-CONTROL FAIL: 注入的字面量卡面只抓到 ${hits.length} 条（应 ≥2）`);
      process.exit(1);
    }
    console.log(`NEGATIVE-CONTROL OK: 注入违例被抓到 ${hits.length} 条（闸有效）`);
    process.exit(0);
  }
  const v = scanRepo();
  if (v.length) {
    console.error(`interaction-card-family FAIL：${v.length} 处卡面字面量（卡族须全 token）`);
    for (const x of v) console.error(`  ${x.file}  ${x.selector}  ${x.prop}: ${x.value}`);
    process.exit(1);
  }
  console.log(`interaction-card-family PASS：卡族 [${FAMILY_CLASSES.join(', ')}] 卡面表面属性全部经 var(--…) 令牌承载，白名单 ${SURFACE_ALLOWLIST.size} 条`);
}

main();
