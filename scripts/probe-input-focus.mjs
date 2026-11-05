#!/usr/bin/env node
// probe-input-focus.mjs — 输入类控件焦点态证据采集器（input-focus-ring 批 2026-11-05）
//
// 装置 = scripts/lib/visual-harness.mjs（零端口 / MockWS / 真 Chromium）。
// `--web-root` 指向「改前」或「改后」的静态根（改前 = `git archive HEAD` 快照），
// 同一支脚本、同一套场景 ⇒ 唯一变量是本批的 CSS/JS 改动。
//
// 每个主题采三类读数：
//   ① 计算值 —— outline / outline-offset / border / box-shadow（未聚焦 · 鼠标聚焦 · 键盘 Tab 聚焦）
//   ② 像素   —— 左边缘扫描出「实际画出来的那一列」，与框内背景 / 框外背景算 WCAG 对比度
//              （PNG 交页面 canvas 解码，零新增依赖）
//   ③ 覆盖   —— 设置面内全部 input/textarea/select 逐个聚焦后读同一组计算值（含被折叠/滚出视口的）
//              外加 spellcheck 属性表与「红波浪线」对照实验
//
// 用法：node scripts/probe-input-focus.mjs --web-root <dir> --tag before|after --out <dir> [--themes light,dark]

import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// 装置 = 同目录 scripts/lib/visual-harness.mjs（零端口 / MockWS / 真 Chromium），路径相对本文件
const { loadPlaywright, launchBrowser, createSession, settle, shoot, setWebRoot } = await import(new URL('./lib/visual-harness.mjs', import.meta.url));

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root');
const TAG = valOf('--tag', 'after');
const OUT = valOf('--out', '/tmp/ifr');
const THEMES = (valOf('--themes', 'light,dark') || '').split(',').filter(Boolean);
if (WEB_ROOT) setWebRoot(WEB_ROOT);
mkdirSync(OUT, { recursive: true });

const CONFIG = {
  llm: {
    providers: {
      anthropic: {
        baseUrl: 'https://api.anthropic.com/',
        apiKey: 'sk-ant-api03-EXAMPLEKEYVALUE0000000000',
        protocol: 'anthropic',
        models: [{ id: 'claude-sonnet-4', contextWindow: 200000 }],
      },
    },
    model: 'claude-sonnet-4',
  },
  imagegen: { provider: 'openai', model: 'gpt-image-1', baseUrl: 'https://api.openai.com/v1/', keyConfigured: true },
  jev: { active: 'openai', providers: { openai: { type: 'openai', endpoint: 'https://api.openai.com/v1', model: 'gpt-4o' } } },
  features: { friends: false },
};

const INPAGE = () => {
  window.__np = {
    async pixels(dataUrl) {
      const img = new Image();
      await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = dataUrl; });
      const c = document.createElement('canvas');
      c.width = img.naturalWidth; c.height = img.naturalHeight;
      const g = c.getContext('2d', { willReadFrequently: true });
      g.drawImage(img, 0, 0);
      const d = g.getImageData(0, 0, c.width, c.height).data;
      return {
        w: c.width, h: c.height,
        at(x, y) { const i = ((y | 0) * c.width + (x | 0)) * 4; return [d[i], d[i + 1], d[i + 2]]; },
      };
    },
    median(px, x0, y0, x1, y1) {
      const acc = [[], [], []];
      for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
        const c = px.at(x, y);
        acc[0].push(c[0]); acc[1].push(c[1]); acc[2].push(c[2]);
      }
      return acc.map((a) => a.sort((p, q) => p - q)[Math.floor(a.length / 2)]);
    },
  };
};

const lin = (c) => { const s = c / 255; return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
const ratio = (a, b) => { const la = lum(a), lb = lum(b); const hi = Math.max(la, lb), lo = Math.min(la, lb); return +((hi + 0.05) / (lo + 0.05)).toFixed(2); };
const parseColor = (s) => {
  const m = String(s).match(/rgba?\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(/[ ,/]+/).filter((x) => x !== '').map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }
  // color-mix 的计算值形态：`color(srgb 0.20 0.42 0.79)` / 带 alpha
  const c = String(s).match(/color\(\s*srgb\s+([^)]+)\)/);
  if (c) {
    const p = c[1].trim().split(/\s+/).map(Number);
    return { r: Math.round(p[0] * 255), g: Math.round(p[1] * 255), b: Math.round(p[2] * 255), a: p.length > 3 ? p[3] : 1 };
  }
  return null;
};
const over = (fg, bg) => (fg.a >= 1 ? [fg.r, fg.g, fg.b] : [
  Math.round(fg.r * fg.a + bg[0] * (1 - fg.a)),
  Math.round(fg.g * fg.a + bg[1] * (1 - fg.a)),
  Math.round(fg.b * fg.a + bg[2] * (1 - fg.a)),
]);

const pw = await loadPlaywright();
const { browser } = await launchBrowser(pw.mod);
const report = { tag: TAG, webRoot: WEB_ROOT, generatedAt: new Date().toISOString(), themes: {} };

for (const theme of THEMES) {
  const s = await createSession(browser, { width: 1440, height: 1000, colorScheme: theme });
  await s.page.addInitScript(INPAGE);
  await s.boot({ config: CONFIG });
  await s.page.click('#settings-btn');
  await settle(s.page, 450);
  const result = { theme };

  // ① 覆盖扫描：设置面（含后续打开的弹窗）内全部输入类控件，逐个 programmatic 聚焦读计算值
  result.inventory = await s.page.evaluate(() => {
    const SEL = 'input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="file"]):not([type="button"]):not([type="submit"]):not([type="reset"]):not([type="color"]):not([type="hidden"]), textarea, select';
    const root = document.querySelector('#settings-overlay') || document.body;
    const out = [];
    for (const el of root.querySelectorAll(SEL)) {
      const prev = document.activeElement;
      el.focus();
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      out.push({
        tag: el.tagName.toLowerCase(),
        id: el.id || null,
        cls: typeof el.className === 'string' ? el.className : '',
        type: el.getAttribute('type'),
        spellcheck: el.getAttribute('spellcheck'),
        visible: !!(r.width && r.height),
        outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor} / off ${cs.outlineOffset}`,
        borderFocus: `${cs.borderTopWidth} ${cs.borderTopColor}`,
        boxShadow: cs.boxShadow,
        matchedSingleSource: el.matches(SEL),
      });
      el.blur();
      if (prev && prev !== document.body && typeof prev.focus === 'function') prev.focus();
    }
    return out;
  });

  // ② 设置页主体（键盘 Tab 能走通的那张脸）：展开「STT 高级设置」让文本/密钥输入件上屏
  await s.page.evaluate(() => {
    for (const id of ['#stt-advance-toggle', '#ttl-advance-toggle', '#jev-advance-toggle']) {
      const b = document.querySelector(id);
      if (b) b.click();
    }
  });
  await settle(s.page, 400);
  result.subjects = {};
  // 例外面读数：主会话输入框 #input（作者令 2026-11-03 ④：键盘态不加高亮）与
  // 命令面板输入框（Monaco QuickInput，保留自己的环）
  result.exceptions = await s.page.evaluate(() => {
    const read = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return { missing: sel };
      el.focus();
      const cs = getComputedStyle(el);
      const out = {
        focused: document.activeElement === el,
        outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor} / off ${cs.outlineOffset}`,
        border: `${cs.borderTopWidth} ${cs.borderTopColor}`,
        boxShadow: cs.boxShadow,
      };
      el.blur();
      return out;
    };
    return { composer: read('#input'), paletteInput: read('#nf-quickinput-host .quick-input-box input') };
  });
  // Monaco 面板在打桩环境里不可实例化 ⇒ 用**同结构合成子树**验级联：
  // 键盘环规则（keyboard.css，含 ID）与本文例外谁赢，一条读数就能判。
  result.exceptions.paletteSynthetic = await s.page.evaluate(() => {
    const host = document.createElement('div');
    host.id = 'nf-quickinput-host';
    host.style.cssText = 'position:fixed;left:0;top:0;width:420px;height:60px;z-index:9;';
    host.innerHTML = '<div class="quick-input-box"><input type="text" value="probe"></div>';
    document.body.appendChild(host);
    const el = host.querySelector('input');
    el.focus();
    const cs = getComputedStyle(el);
    const out = {
      focused: document.activeElement === el,
      outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor} / off ${cs.outlineOffset}`,
      border: `${cs.borderTopWidth} ${cs.borderTopColor}`,
      boxShadow: cs.boxShadow,
      note: '同结构合成子树（Monaco 本体在打桩环境不可实例化）',
    };
    el.blur();
    host.remove();
    return out;
  });
  writeFileSync(join(OUT, `${TAG}-${theme}-settings-full.png`), await shoot(s.page));

  // ②-b 种类矩阵：设置面里折叠/滚出视口的控件能证明「同一套」，但**架子**证明的是
  // 元素种类 —— 这里用真 CSS 造一组同结构夹具（不动产品 DOM、测完即摘），把
  // 6 种 input type + textarea + select 逐个真点聚焦读同一组读数。
  result.kindMatrix = {};
  const kinds = await s.page.evaluate(() => {
    const host = document.createElement('div');
    host.id = 'nf-kind-matrix';
    host.style.cssText = 'position:fixed;left:16px;bottom:16px;z-index:2147483646;display:flex;flex-direction:column;gap:6px;padding:8px;background:var(--color-bg,#fff);border-radius:8px';
    const specs = [['text', 'cfg-input'], ['password', 'cfg-input'], ['number', 'cfg-input'], ['email', 'cfg-input'], ['search', 'cfg-input'], ['url', 'cfg-input']];
    host.innerHTML = specs.map(([t, c], i) => `<input type="${t}" class="${c}" data-probe="kind${i}" value="sample">`).join('')
      + '<textarea class="cfg-input cfg-textarea" data-probe="kind6" rows="1">sample</textarea>'
      + '<select class="cfg-input" data-probe="kind7"><option>sample</option></select>';
    document.body.appendChild(host);
    return [...host.querySelectorAll('input,textarea,select')].map((el, i) => ({ probe: `kind${i}`, kind: el.tagName.toLowerCase() + (el.getAttribute('type') ? ':' + el.getAttribute('type') : '') }));
  });
  for (const k of kinds) {
    const m = await measureSubject(s, k.probe, theme, { keyboard: 'none' });
    result.kindMatrix[k.kind] = { probe: k.probe, hitTest: m.hitTest, rest: m.rest, mouse: m.mouse };
  }
  await s.page.evaluate(() => { const h = document.getElementById('nf-kind-matrix'); if (h) h.remove(); });
  const pageMarks = await s.page.evaluate(() => {
    const out = {};
    const pick = (k, sel) => {
      const el = document.querySelector(sel);
      if (!el) return;
      el.setAttribute('data-probe', k);
      out[k] = { sel, tag: el.tagName.toLowerCase(), cls: el.className, type: el.getAttribute('type') };
    };
    pick('settingsSelect', '#cfg-thinking-effort');
    pick('settingsText', '#stt-endpoint');
    pick('settingsSecret', '#stt-apikey');
    return out;
  });
  for (const [k, meta] of Object.entries(pageMarks)) {
    result.subjects[k] = await measureSubject(s, k, theme, { keyboard: 'settings' });
    result.subjects[k].meta = meta;
  }

  // ③ 弹窗面（.cfg-modal）：鼠标聚焦 + 截图（键盘腿被该脸的 focus-trap 拦回设置层，
  //    见批文档 §3 备注；键盘可达性由 ② 的设置页同一套规则证明）
  const opened = await s.page.evaluate(() => {
    const b = [...document.querySelectorAll('#settings-overlay button')].find((x) => /添加服务商|Add provider/i.test(x.textContent || ''));
    if (b) { b.click(); return true; }
    return false;
  });
  await settle(s.page, 500);
  if (opened) {
    const marks = await s.page.evaluate(() => {
      const out = {};
      const pick = (k, sel) => { const el = document.querySelector(sel); if (el) { el.setAttribute('data-probe', k); out[k] = { sel, cls: el.className, type: el.getAttribute('type') || el.tagName.toLowerCase(), spellcheck: el.getAttribute('spellcheck') }; } };
      pick('dialogApiKey', '.cfg-modal-body input[data-field="apiKey"]');
      pick('dialogBaseUrl', '.cfg-modal-body input[data-field="baseUrl"]');
      pick('dialogProtocol', '.cfg-modal-body select[data-field="protocol"]');
      return out;
    });
    for (const [k, meta] of Object.entries(marks)) {
      result.subjects[k] = await measureSubject(s, k, theme, { keyboard: 'none' });
      result.subjects[k].meta = meta;
    }
    result.spellcheck = await spellcheckProbe(s, [
      { name: 'dialog.apiKey', sel: '[data-probe="dialogApiKey"]' },
      { name: 'dialog.baseUrl', sel: '[data-probe="dialogBaseUrl"]' },
    ]);
    result.spellcheckControl = await headlessDictionaryControl(s.page);
  } else {
    result.error = 'provider modal entry not found';
  }

  report.themes[theme] = result;
  await s.close();
}

async function measureSubject(s, key, theme, opts = {}) {
  const page = s.page;
  const loc = page.locator(`[data-probe="${key}"]`).first();
  await loc.scrollIntoViewIfNeeded().catch(() => {});
  await settle(page, 150);
  const out = {};

  await page.evaluate(() => { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); });
  await settle(page, 80);
  out.rest = await readFocusState(page, key);

  const box0 = await loc.boundingBox();
  if (!box0) return { error: 'no bounding box' };
  // 命中测试：点左侧 1/3 处必须真是该控件（避免被浮层/滚动裁剪挡住时拍出假读数）
  const hit = await page.evaluate((k) => {
    const el = document.querySelector(`[data-probe="${k}"]`);
    const r = el.getBoundingClientRect();
    const x = r.left + r.width * 0.3, y = r.top + r.height / 2;
    const e = document.elementFromPoint(x, y);
    return { ok: !!e && (e === el || el.contains(e) || e.contains(el)), got: e ? `${e.tagName.toLowerCase()}.${(typeof e.className === 'string' ? e.className : '').split(' ')[0]}` : null };
  }, key);
  out.hitTest = hit;

  // 鼠标点击聚焦（真实指针事件；点左侧 1/3 处避开眼睛按钮）
  await page.mouse.click(box0.x + box0.width * 0.3, box0.y + box0.height / 2);
  await settle(page, 220);
  const box = (await loc.boundingBox()) || box0;
  out.mouse = await readFocusState(page, key);
  out.mouse.file = `${TAG}-${theme}-${key}-mouse.png`;
  const shotM = await clipShot(page, box, 8);
  writeFileSync(join(OUT, out.mouse.file), shotM);
  out.mouse.pixel = await analyse(page, shotM, key);
  out.mouse.zoom = `${TAG}-${theme}-${key}-mouse-x3.png`;
  writeFileSync(join(OUT, out.mouse.zoom), await zoomPng(page, shotM, 3));

  if (opts.keyboard === 'settings') {
    // 键盘 Tab：起点 = 设置层的关闭键（该层内第一个可聚焦件），再真实按 Tab 逐格走到目标
    out.keyboard = { tab: await tabInto(page, key) };
    Object.assign(out.keyboard, await readFocusState(page, key));
    out.keyboard.file = `${TAG}-${theme}-${key}-keyboard.png`;
    const shotK = await clipShot(page, box, 8);
    writeFileSync(join(OUT, out.keyboard.file), shotK);
    out.keyboard.pixel = await analyse(page, shotK, key);
    out.keyboard.zoom = `${TAG}-${theme}-${key}-keyboard-x3.png`;
    writeFileSync(join(OUT, out.keyboard.zoom), await zoomPng(page, shotK, 3));
  } else {
    out.keyboard = { skipped: '该脸的 focus-trap 把 Tab 拦回下层（见批文档 §3 备注）；键盘可达性由设置页同一套规则证明' };
  }

  // 整脸截图（含 label，供报告内嵌）
  out.faceFile = `${TAG}-${theme}-${key}-face.png`;
  writeFileSync(join(OUT, out.faceFile), await page.screenshot({ animations: 'disabled', caret: 'hide', clip: { x: Math.max(0, box.x - 80), y: Math.max(0, box.y - 44), width: Math.min(640, box.width + 160), height: box.height + 80 } }));
  return out;
}

async function tabInto(page, key) {
  await page.evaluate(() => {
    const start = document.querySelector('#settings-modal-close') || document.querySelector('#settings-overlay button');
    if (start) start.focus();
  });
  for (let i = 0; i < 120; i++) {
    await page.keyboard.press('Tab');
    const ok = await page.evaluate((k) => document.activeElement === document.querySelector(`[data-probe="${k}"]`), key);
    if (ok) { await settle(page, 260); return { ok: true, tabs: i + 1 }; }
  }
  await settle(page, 260);
  return { ok: false, tabs: 0 };
}

async function readFocusState(page, key) {
  return page.evaluate((k) => {
    const el = document.querySelector(`[data-probe="${k}"]`);
    const cs = getComputedStyle(el);
    return {
      focused: document.activeElement === el,
      focusVisible: el.matches(':focus-visible'),
      outlineStyle: cs.outlineStyle, outlineWidth: cs.outlineWidth, outlineColor: cs.outlineColor, outlineOffset: cs.outlineOffset,
      borderTop: `${cs.borderTopWidth} ${cs.borderTopStyle} ${cs.borderTopColor}`,
      borderTopColor: cs.borderTopColor,
      boxShadow: cs.boxShadow,
      backgroundColor: cs.backgroundColor,
    };
  }, key);
}

async function zoomPng(page, buf, factor = 3) {
  const dataUrl = 'data:image/png;base64,' + buf.toString('base64');
  const b64 = await page.evaluate(async (args) => {
    const img = new Image();
    await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = args.url; });
    const c = document.createElement('canvas');
    c.width = img.naturalWidth * args.factor;
    c.height = img.naturalHeight * args.factor;
    const g = c.getContext('2d');
    g.imageSmoothingEnabled = false;           // 最近邻放大：像素边界保持锐利，便于看描边
    g.drawImage(img, 0, 0, c.width, c.height);
    return c.toDataURL('image/png').split(',')[1];
  }, { url: dataUrl, factor });
  return Buffer.from(b64, 'base64');
}

async function clipShot(page, box, pad = 8) {
  return page.screenshot({
    animations: 'disabled', caret: 'hide', scale: 'css',
    clip: { x: Math.max(0, box.x - pad), y: Math.max(0, box.y - pad), width: box.width + pad * 2, height: box.height + pad * 2 },
  });
}

async function analyse(page, buf, key) {
  const dataUrl = 'data:image/png;base64,' + buf.toString('base64');
  const geo = await page.evaluate((k) => {
    const el = document.querySelector(`[data-probe="${k}"]`);
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return { w: r.width, h: r.height, borderTopColor: cs.borderTopColor };
  }, key);
  const raw = await page.evaluate(async (args) => {
    const px = await window.__np.pixels(args.dataUrl);
    const PAD = args.pad;
    const eh = Math.round(args.geo.h); const ew = Math.round(args.geo.w);
    const inner = window.__np.median(px, PAD + 6, PAD + eh - 6, Math.min(px.w - 2, PAD + ew - 6), PAD + eh - 3);
    const outer = window.__np.median(px, Math.max(0, PAD - 6), 1, Math.max(1, PAD - 3), 4);
    const cy = PAD + Math.round(eh / 2);
    let best = null;
    for (let x = Math.max(0, PAD - 6); x <= Math.min(px.w - 1, PAD + 6); x++) {
      const c = px.at(x, cy);
      const d = Math.abs(c[0] - inner[0]) + Math.abs(c[1] - inner[1]) + Math.abs(c[2] - inner[2]);
      if (!best || d > best.d) best = { x, c, d };
    }
    return { inner, outer, edge: best.c, edgeX: best.x, edgeDelta: best.d, imgW: px.w, imgH: px.h };
  }, { dataUrl, pad: 8, geo });
  const bc = parseColor(geo.borderTopColor);
  const stroke = bc ? over(bc, raw.inner) : null;
  return {
    innerBg: raw.inner, outerBg: raw.outer, edgePixel: raw.edge, edgeX: raw.edgeX, edgeDelta: raw.edgeDelta,
    borderComputed: geo.borderTopColor, borderEffectiveOverInner: stroke,
    contrastEdge_vs_inner: ratio(raw.edge, raw.inner),
    contrastEdge_vs_outer: ratio(raw.edge, raw.outer),
    contrastBorderComputed_vs_inner: stroke ? ratio(stroke, raw.inner) : null,
    clip: `${raw.imgW}x${raw.imgH}`,
  };
}

// 拼写检查：往字段打一串密钥样值（无可读词）+ 一段明确拼错的英文，数红波浪线像素
async function spellcheckProbe(s, list) {
  const page = s.page;
  const KEY = 'sk-xqvz-jkwpq';
  const out = [];
  for (const c of list) {
    const loc = page.locator(c.sel).first();
    if (!(await loc.count().catch(() => 0))) { out.push({ ...c, skipped: 'not-in-dom' }); continue; }
    await loc.scrollIntoViewIfNeeded().catch(() => {});
    const box = await loc.boundingBox();
    if (!box) { out.push({ ...c, skipped: 'not-visible' }); continue; }
    await loc.fill('').catch(() => {});
    await loc.type(KEY + ' recieve teh', { delay: 3 }).catch(() => {});
    await page.evaluate((sel) => { const el = document.querySelector(sel); if (el) el.blur(); }, c.sel);
    await settle(page, 320);
    const shot = await clipShot(page, box, 4);
    writeFileSync(join(OUT, `${TAG}-${c.name.replace(/[^a-z0-9]+/gi, '_')}-spell.png`), shot);
    const red = await countRed(page, shot);
    const attrs = await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      return el ? { spellcheck: el.getAttribute('spellcheck'), type: el.getAttribute('type'), autocorrect: el.getAttribute('autocorrect'), autocapitalize: el.getAttribute('autocapitalize'), autocomplete: el.getAttribute('autocomplete'), security: getComputedStyle(el).webkitTextSecurity || null } : null;
    }, c.sel);
    out.push({ ...c, ...attrs, redPixels: red.red, wave: red.red > 0 });
  }
  return out;
}

// 对照实验：往页面临时插一个**明确允许拼写检查**的输入框（spellcheck=true + 拼错的英文），
// 若它也不出红波浪线 ⇒ headless Chromium 没有拼写词典，采不到波浪线（采集器的已知界限）。
async function headlessDictionaryControl(page) {
  await page.evaluate(() => {
    const d = document.createElement('div');
    d.id = 'nf-spell-control';
    d.style.cssText = 'position:fixed;left:20px;top:20px;z-index:2147483647;background:#fff;padding:4px';
    d.innerHTML = '<input id="nf-spell-input" type="text" spellcheck="true" style="width:260px;font-size:15px;background:#fff;color:#000">';
    document.body.appendChild(d);
    const i = document.getElementById('nf-spell-input');
    i.value = 'recieve teh seperate occured';
  });
  await settle(page, 500);
  const box = await page.locator('#nf-spell-input').boundingBox();
  const shot = await clipShot(page, box, 2);
  writeFileSync(join(OUT, `${TAG}-spell-control.png`), shot);
  const red = await countRed(page, shot);
  await page.evaluate(() => { const d = document.getElementById('nf-spell-control'); if (d) d.remove(); });
  return { note: 'spellcheck=true + 拼错英文的对照字段', redPixels: red.red, wave: red.red > 0 };
}

async function countRed(page, buf) {
  const dataUrl = 'data:image/png;base64,' + buf.toString('base64');
  return page.evaluate(async (url) => {
    const px = await window.__np.pixels(url);
    let red = 0, total = 0;
    for (let y = 0; y < px.h; y++) for (let x = 0; x < px.w; x++) {
      const [r, g, b] = px.at(x, y);
      total++;
      if (r > 120 && g < 90 && b < 90 && r - Math.max(g, b) > 60) red++;
    }
    return { red, total };
  }, dataUrl);
}

writeFileSync(join(OUT, `${TAG}-report.json`), JSON.stringify(report, null, 2));
console.log('written', join(OUT, `${TAG}-report.json`));
await browser.close();
