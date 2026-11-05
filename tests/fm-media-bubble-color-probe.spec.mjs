// fm-media-bubble-color-probe.spec.mjs — 社交通道（`.fm-` 面）「媒体气泡 vs 文字气泡
// 同色」**红基线取证探针**（作者令 2026-11-04 16:39「社交窗口对话 气泡颜色不统一
// 应该和文字的气泡一个颜色」）。
//
// 角色：本文件是**取证件**，不是产品验收门。它只做一件事——把「媒体面容器底色」
// 与「同向文字气泡底色」的 computed 原文并排打出来，并给出逐项 PASS/FAIL，好让修
// 前/修后两次读数落在同一个刻度上（本节点只取证，不改任何产品 CSS）。
//
// 🔴 路径申明（与派工单口径的差异，必须点名）：派工单指定
//    tests/fm-media-bubble-color.spec.mjs。实测该路径在 2026-11-04 16:54 已被
//    **实现节点并发写入**（同 worktree，文件头自称「本文件由实现节点代建」——
//    其理由是当时未在任何分支上找到本 probe 的产物）。为避免覆盖他人正在跑的门
//    禁，本 probe 落在**不相交**的 -probe 路径；两份文件共存：
//      · tests/fm-media-bubble-color.spec.mjs        ← 实现节点的验收门（C1–C8）
//      · tests/fm-media-bubble-color-probe.spec.mjs  ← 本取证探针（红基线 + 截图）
//
// 独立 node 脚本（**刻意不 import @playwright/test**）：playwright.config.mjs 的
// 收集面按 import 形态判定，不匹配者自动排除，故本文件不会被 `playwright test`
// 误收；必须 `node tests/fm-media-bubble-color-probe.spec.mjs` 直跑。
//
// 判据（与派工单逐条对应）：
//   P1 底色同向相等（**唯一硬门**）：媒体面容器 computed `background-color` 字符串
//      **逐字** === 同向 `.fm-msg-bubble` 的 computed `background-color` 字符串。
//      被测容器 = ① 单图 `.pop-media-flat` ③ 叠卡 `.pop-stack-view > .media-layer`
//      （前/中/背三层）④ 未加载占位 `.pop-media-flat` + 失败态 `.pop-media-flat`。
//   P2 逐项比对（报告项，不设硬门）：`backdrop-filter` / `border-radius`（四角，
//      含方向尾角）/ `box-shadow` / `border` 与同向文字气泡逐项对照，打 PASS|DIFF。
//      —— 不设硬门的原因：这三项的差异是 a3342618c 同一条规则的**同一族漂移**
//      （媒体面 16px+4px vs 文字泡 14px+4px），修色若不动几何，把它们设成门会让
//      修后批次永久红；故本节只如实并列，由下游按设计意图取舍。
//   P3 文件卡差异项（**显式申报，不静默跳过**）：`.file-card` 按设计**不属于**气泡
//      族（2026-10-22 作者令「附件卡 = 统一 chip 族」，chat.css:4955-4972：底 =
//      `--color-surface-2` 缺省回落 `rgba(127,127,127,0.08)`、细边框
//      `--glass-etched-border`、10px 圆角）。故单列「差异项 + 理由」字段。
//   P4 下载角标读数：`.pop-media-canvas` 的 computed 底色（应为中性墨
//      `rgba(0, 0, 0, 0.45)`；截图里看着发绿只是 0.45 黑压在绿底上的**合成观感**）。
//
// 四态 = 亮色/暗色 × 视口 1440×900 / 375×812（`colorScheme` 走 context option，
// **不改任何主题文件**）。
//
// Run:
//   node tests/fm-media-bubble-color-probe.spec.mjs
//   node tests/fm-media-bubble-color-probe.spec.mjs --capture before
//   node tests/fm-media-bubble-color-probe.spec.mjs --capture after
// 截图落 /Users/kaiyu/.stand/evidence/bubble-color-unify/{before,after}/
//   <theme>-<width>-all-forms.png
// 退出码：有任何 P1 失败 ⇒ 1；全绿 ⇒ 0。

import { createServer } from 'node:http';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// FM_REPO_ROOT：静态根覆盖（取证用）。缺省 = 本 worktree。取值只影响「服务哪一份
// CSS」，probe 清单/断言零变化 —— 改前基线需要服务 git 历史里的 CSS 时走它
// （见 readings.md「为什么基线不是工作树」条）。探针本身永远跑本文件所在 worktree。
const REPO = process.env.FM_REPO_ROOT
  ? process.env.FM_REPO_ROOT
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS = '/tests/fixtures/fm-media-bubble/harness.html';
const EVIDENCE_ROOT = join(process.env.HOME || '/Users/kaiyu', '.stand', 'evidence', 'bubble-color-unify');

const THEMES = ['light', 'dark'];
const VIEWPORTS = [{ w: 1440, h: 900 }, { w: 375, h: 812 }];
const SIDES = ['out', 'in'];

// 气泡族被测容器（data-probe 值）。文件卡不在内（P3 单列）。
const FAMILY_FORMS = [
  { key: 'flat',        label: '①单图 .pop-media-flat',                 sel: '① 单图' },
  { key: 'stack-front', label: '③叠卡前卡 .media-layer.layer-front',     sel: '③ 叠卡前' },
  { key: 'stack-mid',   label: '③叠卡中层 .media-layer.layer-mid',       sel: '③ 叠卡中' },
  { key: 'stack-back',  label: '③叠卡背层 .media-layer.layer-back',      sel: '③ 叠卡背' },
  { key: 'pending',     label: '④未加载占位 .pop-media-flat',            sel: '④ 占位' },
  { key: 'failed',      label: '④加载失败 .pop-media-flat',              sel: '④ 失败' },
];

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

function startServer() {
  return new Promise((resolve, reject) => {
    const srv = createServer((req, res) => {
      const url = decodeURIComponent((req.url || '/').split('?')[0]);
      const file = join(REPO, url);
      try {
        const body = readFileSync(file);
        res.writeHead(200, { 'content-type': MIME[extname(join(REPO, url))] || 'application/octet-stream' });
        res.end(body);
      } catch {
        res.writeHead(404, { 'content-type': 'text/plain' });
        res.end('nf');
      }
    });
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

/** 页面侧读数：computed 原文一律不加工（逐字相等判据要的就是原文）。 */
function collectProbes() {
  const read = (el) => {
    if (!el) return null;
    const s = getComputedStyle(el);
    return {
      bg: s.backgroundColor,
      backdrop: s.backdropFilter,
      radius: [s.borderTopLeftRadius, s.borderTopRightRadius, s.borderBottomRightRadius, s.borderBottomLeftRadius],
      shadow: s.boxShadow,
      border: `${s.borderTopWidth} ${s.borderTopStyle} ${s.borderTopColor}`,
    };
  };
  const out = {};
  document.querySelectorAll('[data-probe]').forEach((el) => {
    out[el.getAttribute('data-probe')] = read(el);
  });
  return out;
}

const failures = [];   // P1 硬门失败
const lines = [];

function fmtRadius(r) {
  return r ? `tl=${r[0]} tr=${r[1]} br=${r[2]} bl=${r[3]}` : 'null';
}

function compare(label, ref, test) {
  const same = [];
  const diff = [];
  const cmp = (name, a, b) => (a === b ? same : diff).push(`${name}:${a}|${b}`);
  cmp('backdrop-filter', ref.backdrop, test.backdrop);
  cmp('border-radius', fmtRadius(ref.radius), fmtRadius(test.radius));
  cmp('box-shadow', ref.shadow, test.shadow);
  cmp('border', ref.border, test.border);
  return { same, diff };
}

async function main() {
  const argv = process.argv.slice(2);
  const capIdx = argv.indexOf('--capture');
  const phase = capIdx >= 0 ? argv[capIdx + 1] : (process.env.FM_CAPTURE || null);
  if (phase && !['before', 'after'].includes(phase)) {
    console.error(`--capture 只接受 before|after（收到 ${phase}）`);
    process.exit(2);
  }
  const outDir = phase ? join(EVIDENCE_ROOT, phase) : null;
  if (outDir) mkdirSync(outDir, { recursive: true });

  if (!existsSync(join(REPO, HARNESS))) {
    console.error(`夹具缺失：${HARNESS}`);
    process.exit(2);
  }

  const srv = await startServer();
  const port = srv.address().port;
  const browser = await chromium.launch({ headless: true });
  const shots = [];

  try {
    for (const theme of THEMES) {
      for (const vp of VIEWPORTS) {
        const ctx = await browser.newContext({ colorScheme: theme, viewport: { width: vp.w, height: vp.h } });
        const page = await ctx.newPage();
        await page.goto(`http://127.0.0.1:${port}${HARNESS}`, { waitUntil: 'load' });
        // 样式表就位闸：glass.css（最后载入）已生效 ⇒ --glass-bub-user-bg 可解析。
        await page.waitForFunction(() => {
          const el = document.querySelector('[data-probe="out-flat"]');
          return !!el && getComputedStyle(el).backgroundColor !== 'rgba(0, 0, 0, 0)';
        });
        const probes = await page.evaluate(collectProbes);

        // ── 读数（四态各打一份；401/1440 结论同构，读数仍逐态留档）──
        lines.push(`\n===== theme=${theme} viewport=${vp.w}x${vp.h} =====`);
        lines.push('selector | background-color | backdrop-filter | border-radius | box-shadow');
        for (const side of SIDES) {
          const ref = probes[`${side}-text`];
          lines.push(`${side} 基准 .fm-msg.${side} .fm-msg-bubble | ${ref.bg} | ${ref.backdrop} | ${fmtRadius(ref.radius)} | ${ref.shadow}`);
        }
        for (const side of SIDES) {
          const ref = probes[`${side}-text`];
          lines.push(`-- ${side} 向 --`);
          for (const f of FAMILY_FORMS) {
            const t = probes[`${side}-${f.key}`];
            lines.push(`${f.label} [${side}-${f.key}] | ${t.bg} | ${t.backdrop} | ${fmtRadius(t.radius)} | ${t.shadow}`);
            // P1 硬门
            if (t.bg !== ref.bg) {
              failures.push(
                `P1 底色不同源 ${side}/${f.key} @${theme}/${vp.w}\n` +
                `   期望（同向文字气泡 computed）: ${ref.bg}\n` +
                `   实测（媒体面容器 computed）: ${t.bg}`,
              );
            }
            // P2 逐项比对
            const { same, diff } = compare(`${side}/${f.key}`, ref, t);
            const mark = diff.length === 0 ? 'PASS' : 'DIFF';
            lines.push(`    P2 ${mark} same=[${same.join(', ')}] diff=[${diff.join(', ')}]`);
          }
          // P3 文件卡：chip 族，显式申报差异
          const fc = probes[`${side}-file`];
          lines.push(`${side} 文件卡 .file-card [${side}-file] | ${fc.bg} | ${fc.backdrop} | ${fmtRadius(fc.radius)} | ${fc.shadow}`);
          lines.push(
            `    P3 差异项（非缺陷）: 文件卡 ∉ 气泡族 —— 2026-10-22 作者令「附件卡=统一 chip 族」`
            + `（chat.css:4955-4972）：底=--color-surface-2 缺省回落 rgba(127,127,127,0.08)、`
            + `细框=--glass-etched-border、10px 圆角。故不参与「与文字气泡同色」判据。`
          );
          // P4 下载角标
          const chip = probes[`${side}-canvas`];
          if (chip) lines.push(`${side} 下载角标 .pop-media-canvas [${side}-canvas] | ${chip.bg} | - | ${fmtRadius(chip.radius)} | ${chip.shadow}`);
        }

        // ── 截图（一页含四形态 + 出/入双向）──
        if (outDir) {
          // 角标默认 opacity:0（hover 才显形）——先 hover 出向单图，让截图里能看见
          // 那颗「看着发绿」的下载钮，好复核「0.45 黑压在绿底上」的合成观感。
          await page.hover('[data-probe="out-flat"]').catch(() => {});
          const file = join(outDir, `${theme}-${vp.w}-all-forms.png`);
          await page.screenshot({ path: file, fullPage: true });
          shots.push(file);
        }
        await ctx.close();
      }
    }
  } finally {
    await browser.close();
    srv.close();
  }

  console.log(lines.join('\n'));

  if (shots.length) {
    console.log('\n=== 截图落盘 ===');
    shots.forEach((s) => console.log(`  ${s}`));
  }

  const total = THEMES.length * VIEWPORTS.length * SIDES.length * FAMILY_FORMS.length;
  console.log(`\n=== P1 硬门：${total} 项（theme×viewport×side×form）===`);
  if (failures.length) {
    console.log(`FAIL: ${failures.length} 项不合`);
    failures.forEach((f) => console.log(`  ✗ ${f}`));
    console.log('\n退出码 1');
    process.exitCode = 1;
  } else {
    console.log('PASS: 全绿');
    console.log('\n退出码 0');
    process.exitCode = 0;
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(3);
});
