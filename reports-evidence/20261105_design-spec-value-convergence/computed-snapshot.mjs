#!/usr/bin/env node
// computed-snapshot.mjs — 「computed 逐字未变」的机械对照装置（第一层验收判据）
//
// 任务书逐字：「改完必须给『computed 逐字未变』的机械对照——改动前后对同一组元素取
// getComputedStyle 全量快照并逐值 diff，差异必须为空。这不是"看着没变"，是逐值相等。」
//
// 做法：零端口零进程（复用 scripts/lib/visual-harness.mjs 的 page.route + MockWS 打桩，
// 结构上碰不到宿主实例 6138）。同一份场景配方跑两棵静态根（改前树 / 改后树），
// 对 **body 下每个元素** 取近 50 个计算属性 + 盒几何，按 DOM 路径逐值比对。
//
// 用法：
//   node computed-snapshot.mjs --web-root <dir> --out <file.json>
//   node computed-snapshot.mjs --diff <a.json> <b.json>     # 逐值 diff（空 = 合格）
//
// 为什么这比截图更硬：截图比的是「抗锯齿后的像素」，本装置比的是「浏览器解析出来的
// 每个声明的最终值」。令牌改名若把某个 var() 改成不存在的名字，计算值会回退
// （inherit/initial）⇒ 这里必然红，且能指到「哪个元素哪个属性」。
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadPlaywright, launchBrowser, createSession, settle, setWebRoot, webRoot } from '../../scripts/lib/visual-harness.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root');
const OUT = valOf('--out');

/** 打点属性：设计规格迭代面（字/距/圆角/色/几何）+ 盒模型。四角圆角用长写（无 border-radius 简写可读）。 */
const PROPS = [
  'font-size', 'font-family', 'font-weight', 'line-height', 'letter-spacing', 'text-transform',
  'color', 'background-color', 'background-image', 'box-shadow', 'opacity', 'z-index',
  'border-top-width', 'border-right-width', 'border-bottom-width', 'border-left-width',
  'border-top-style', 'border-top-color',
  'border-top-left-radius', 'border-top-right-radius', 'border-bottom-right-radius', 'border-bottom-left-radius',
  'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
  'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
  'row-gap', 'column-gap', 'display', 'position', 'top', 'right', 'bottom', 'left',
  'width', 'height', 'min-width', 'min-height', 'max-width', 'max-height',
  'flex-direction', 'align-items', 'justify-content', 'overflow-x', 'overflow-y', 'transform',
];

const CLOCK = 1757000000000;      // 冻结墙上时钟（相对时间文本的字符数会影响布局）
const CHAT = [
  { type: 'user', text: '把这份报告的重点整理成三条，并说明数据来源。', timestamp: 1757000000000 },
  { type: 'ai', text: '好的，三条重点如下：\n1. 先补视觉基线；\n2. 再上类型基建；\n3. 最后拆巨型函数。\n\n数据来源：fixture' },
  { type: 'tool', label: 'Read("a.md")', summary: '128 lines', isError: false, input: '{"path":"a.md"}', content: '1\t# 前端地基\n2\t\n' },
  { type: 'system', i18nKey: 'chat.compacted', params: { after: 12, before: 3, detail: '' }, content: 'Context compacted' },
  { type: 'ai', text: '已推进到第二步。' },
];
const STUBS = { '/api/plugins': { body: { plugins: [], rejected: [] } }, '/api/nf-authcheck': { status: 204 }, '/api/agents': { body: { agents: [] } } };

const SCENARIOS = [
  { name: 'shell', why: '主壳空态（顶栏/侧栏/输入条/画布空态）', run: async (s) => { await s.boot(); } },
  { name: 'chat', why: '对话流（气泡/工具卡/系统行）', run: async (s) => { await s.boot({ messages: CHAT }); } },
  {
    name: 'settings', why: '设置面板（严格面：设置行/标签/卡族）',
    run: async (s) => {
      await s.boot({ config: { llm: { providers: {} }, features: { friends: false } } });
      // 用 DOM 点击而不是真鼠标点击：375 视口下活动栏按钮可能被手机层隐藏，
      // 而本装置要的是**面板渲染出来后的计算值**，不是可点击性。
      await s.page.evaluate(() => { const b = document.getElementById('settings-btn'); if (b) b.click(); });
      await s.page.waitForTimeout(800);
      await settle(s.page, 250);
    },
  },
];
const VIEWPORTS = [{ w: 1440, h: 900, tag: '1440' }, { w: 375, h: 812, tag: '375' }];
const THEMES = ['dark', 'light'];

async function snapScenarios() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label, version } = await launchBrowser(pw);
  const rows = [];
  for (const vp of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const sc of SCENARIOS) {
        const s = await createSession(browser, {
          width: vp.w, height: vp.h, colorScheme: theme, clock: CLOCK,
          apiStubs: { ...STUBS },
        });
        try {
          await sc.run(s);
          await settle(s.page, 200);
          const data = await s.page.evaluate((props) => {
            const keyOf = (el) => {
              const parts = [];
              let e = el;
              while (e && e.parentElement) {
                const p = e.parentElement;
                const i = Array.prototype.indexOf.call(p.children, e);
                parts.unshift(`${e.tagName.toLowerCase()}${e.id ? '#' + e.id : ''}[${i}]`);
                e = p;
              }
              return parts.join('/');
            };
            const out = [];
            for (const el of document.body.querySelectorAll('*')) {
              const cs = getComputedStyle(el);
              const vals = props.map((p) => cs.getPropertyValue(p));
              const r = el.getBoundingClientRect();
              vals.push(`${r.x >= 0 ? '+' : ''}${Math.round(r.x)},${Math.round(r.y)},${Math.round(r.width)}x${Math.round(r.height)}`);
              out.push(`${keyOf(el)}\t${vals.join('|')}`);
            }
            return out;
          }, PROPS);
          for (const line of data) rows.push(`${vp.tag}/${theme}/${sc.name}\t${line}`);
        } finally {
          await s.close();
        }
      }
    }
  }
  await browser.close();
  return { rows, meta: { browser: `${label}-${version}`, props: PROPS, viewports: VIEWPORTS, themes: THEMES, scenarios: SCENARIOS.map((x) => x.name), webRoot: webRoot() } };
}

function doDiff(aPath, bPath) {
  const A = readFileSync(aPath, 'utf8').split('\n').filter((l) => l.split('\t').length >= 3);
  const B = readFileSync(bPath, 'utf8').split('\n').filter((l) => l.split('\t').length >= 3);
  const parse = (lines) => {
    const m = new Map();
    // 行格式：`<视口>/<主题>/<场景>\t<DOM路径>\t<值|值|…>` —— 三段。
    // （第一版漏了第二段，把「场景前缀」当成了元素键 ⇒ 只剩 12 个键、diff 假绿；
    //   证据装置的自身 bug 也必须被看见，故留此注。）
    for (const l of lines) {
      const [scope, key, vals] = l.split('\t');
      m.set(`${scope}\u0000${key}`, vals.split('|'));
    }
    return m;
  };
  const ma = parse(A), mb = parse(B);
  const diffs = [];
  const onlyA = [], onlyB = [];
  for (const [k, va] of ma) {
    const vb = mb.get(k);
    if (!vb) { onlyA.push(k); continue; }
    for (let i = 0; i < Math.max(va.length, vb.length); i += 1) {
      if (va[i] !== vb[i]) diffs.push({ key: k, prop: i < PROPS.length ? PROPS[i] : 'rect', from: va[i], to: vb[i] });
    }
  }
  for (const k of mb.keys()) if (!ma.has(k)) onlyB.push(k);
  console.log(`computed diff: A=${ma.size} 元素 / B=${mb.size} 元素`);
  console.log(`  逐值差异：${diffs.length}`);
  console.log(`  仅 A 有：${onlyA.length}   仅 B 有：${onlyB.length}`);
  for (const d of diffs.slice(0, 40)) console.log(`  · ${d.key}  ${d.prop}: ${d.from} → ${d.to}`);
  for (const k of onlyA.slice(0, 10)) console.log(`  · A-only ${k}`);
  for (const k of onlyB.slice(0, 10)) console.log(`  · B-only ${k}`);
  const ok = diffs.length === 0 && onlyA.length === 0 && onlyB.length === 0;
  console.log(ok ? '  ✅ COMPUTED IDENTICAL（逐值相等，元素集相同）' : '  ❌ COMPUTED DIFFERS');
  process.exit(ok ? 0 : 1);
}

if (valOf('--diff')) {
  const a = valOf('--diff'), b = argv[argv.indexOf('--diff') + 2];
  if (!a || !b) { console.error('diff 需要两个快照文件'); process.exit(2); }
  doDiff(a, b);
} else {
  if (WEB_ROOT) setWebRoot(WEB_ROOT);
  if (!OUT) { console.error('需要 --out <file.json>'); process.exit(2); }
  const { rows, meta } = await snapScenarios();
  writeFileSync(OUT, rows.join('\n') + '\n');
  writeFileSync(OUT.replace(/\.json$/, '.meta.json'), JSON.stringify(meta, null, 2) + '\n');
  console.log(`computed snapshot: ${rows.length} 行 → ${OUT}`);
  console.log(`  静态根：${meta.webRoot}`);
  console.log(`  浏览器：${meta.browser}  视口×主题×场景：${VIEWPORTS.length}×${THEMES.length}×${SCENARIOS.length}`);
}
