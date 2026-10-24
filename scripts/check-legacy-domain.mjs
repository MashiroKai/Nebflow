#!/usr/bin/env node
// check-legacy-domain.mjs — 旧域字面量门禁（#831 旧域清扫批，2026-10-25）：
// git 跟踪文件中不得再出现**裸域** `nebflow.space` 字面量（白名单制）。
//
// ── 背景 ──
// 2026-10 品牌迁移后浏览器入口/安装桥已统一切到 mystand.space（brand.conf
// `domain`/`profileUrl`/`loginUrl`/`portalUrl` 均已切），旧域 nebflow.space 退役，
// 仅三类合法残留（见下方 WHITELIST）：
//   a) 历史封存件：reports/、evidence/、tests/fixtures/、official-fixtures——
//      冻结的取证/报告/夹具，改写即毁证；
//   b) 守卫/工具语义：EnrollGuard ProdDomains（旧中继安装仍须被拦）、
//      DeviceIdentitySpec（钉住旧域命中闸）、rebrand.sh（换域工具的源模式）、
//      coldstart.sh（生产域拒注清单注释）、branding-migration.spec.mjs
//      （rename-day 旧品牌启动夹具）、plugins-panel ⑦ 门禁自身（模式字面）；
//   c) 显式 legacy 标注：deploy-website SKILL.md（「legacy … retired」教学件）。
// 新代码再落旧域字面量 = 回归（用户可见入口指向已退役站点），本门禁拦截。
//
// ── 判据 ──
// 只拦**裸域**：`nebflow.space` 前后不得紧贴 `[A-Za-z0-9_.-]` ——
// 服务子域（neblink./auth./api.…nebflow.space，REBRAND.md 裁定：服务地址不是
// 浏览器入口，不随品牌迁移）与 `nebflow.spaceXyz` 类 token 天然不命中。
//
// ── 用法 ──
//   node scripts/check-legacy-domain.mjs   # exit 0 = clean；exit 1 = 命中
// CI 与本地同逻辑：.github/workflows/ci.yml 的 legacy-domain-gate job 直接调用。

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

// 裸域：前不接 [word . -]，后不接 [word -]（与 tests/plugins-panel-pure-plugins
// ⑦ 门禁的 `bare` 正则同源，双门禁判据一致）。
const BARE = /(?<![\w.-])nebflow\.space(?![\w-])/;

// ── 白名单（白名单制：路径前缀匹配；每条带豁免理由，删条目前先清残留）──
const WHITELIST = [
  'scripts/check-legacy-domain.mjs', // b) 门禁自身：判据正则字面（自排除，同 check-user-knowledge.sh 惯例）
  'reports/',                     // a) 历史批次报告（冻结封存）
  'evidence/',                    // a) 取证/实测原始件（冻结封存）
  'tests/fixtures/',              // a) 冻结测试夹具（canvas-html 快照等）
  'src/test/resources/official-fixtures/', // a) 官方第三方插件夹具（parser 回归）
  'scripts/rebrand.sh',           // b) 换域工具自身：旧域是其替换源模式
  'scripts/coldstart.sh',         // b) 生产域拒注清单注释（mystand/nebflow 双域）
  'src/main/scala/nebflow/neblink/EnrollGuard.scala',       // b) ProdDomains：旧中继安装仍须被拦
  'src/test/scala/nebflow/neblink/DeviceIdentitySpec.scala', // b) 钉住旧域命中闸的语义锚
  'tests/branding-migration.spec.mjs',  // b) rename-day 旧品牌启动夹具（迁移测试）
  'tests/plugins-panel-pure-plugins.spec.mjs', // b) ⑦ 品牌清扫门禁自身（模式字面）
  'src/main/resources/seed/plugins/website/skills/deploy-website/SKILL.md', // c) 显式 legacy 标注
];
const whitelisted = (p) => WHITELIST.some((w) => p === w || p.startsWith(w));

const files = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT, maxBuffer: 1e8 })
  .toString()
  .split('\0')
  .filter(Boolean);

const offenders = [];
for (const f of files) {
  let buf;
  try {
    buf = readFileSync(join(ROOT, f));
  } catch {
    continue; // ls-files 与工作区竞态（子模块/已删文件）——跳过
  }
  if (buf.includes(0)) continue; // 二进制（.bundle/.png 等）不扫
  buf
    .toString('utf8')
    .split('\n')
    .forEach((line, i) => {
      if (BARE.test(line)) offenders.push(`${f}:${i + 1}: ${line.trim().slice(0, 160)}`);
    });
}

const hits = offenders.filter((o) => !whitelisted(o.split(':')[0]));
if (hits.length > 0) {
  console.error(`✗ legacy-domain gate FAILED — bare "nebflow.space" literal(s) outside the whitelist:`);
  console.error(hits.join('\n'));
  console.error('');
  console.error('Browser-entry URLs must point at mystand.space (brand.conf single source);');
  console.error('service subdomains (*.nebflow.space) are NOT affected by this gate.');
  console.error('Legacy-frozen file? Add a JUSTIFIED entry to WHITELIST in scripts/check-legacy-domain.mjs');
  console.error('— do not widen it silently.');
  process.exit(1);
}
console.log(`✓ legacy-domain gate clean: bare nebflow.space = 0 outside whitelist (${offenders.length} whitelisted hit(s) in frozen/tool paths)`);
