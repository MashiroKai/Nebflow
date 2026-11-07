// harness.mjs — UI 巡检主脚本（只读驱动真实引擎，不注入假数据）
// 产物：reports-evidence/ui-audit-post-restart/<面>/<主题>-<尺寸>-<步骤>.png
//        reports-evidence/ui-audit-post-restart/_data/findings.json（自动检测明细）
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const ONLY = process.env.ONLY || '';
const THEMES = (process.env.THEMES || 'light,dark').split(',');
const SIZES = (process.env.SIZES || 'desktop,mobile').split(',');
const DATA = path.join(ROOT, '_data');
fs.mkdirSync(DATA, { recursive: true });

const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

// 一张 1x1 之上的可辨识小图（附件气泡取景用）
const FIXTURE_DIR = path.join(DATA, 'fixtures');
fs.mkdirSync(FIXTURE_DIR, { recursive: true });
const FIXTURE_PNG = path.join(FIXTURE_DIR, 'ui-audit-attach.png');
if (!fs.existsSync(FIXTURE_PNG)) {
  // 96x64 纯色 PNG（手工构造，避免外部依赖）
  const w = 96, h = 64;
  const raw = Buffer.alloc((w * 3 + 1) * h);
  let o = 0;
  for (let y = 0; y < h; y++) {
    raw[o++] = 0;
    for (let x = 0; x < w; x++) { raw[o++] = 40 + x; raw[o++] = 120; raw[o++] = 200 - y; }
  }
  const zlib = await import('node:zlib');
  const idat = zlib.deflateSync(raw);
  const crcTable = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const t = Buffer.from(type, 'ascii');
    const cr = Buffer.alloc(4); cr.writeUInt32BE(crc(Buffer.concat([t, data])));
    return Buffer.concat([len, t, data, cr]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(FIXTURE_PNG, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0)),
  ]));
}

const findings = [];
const push = (o) => findings.push(o);

// 单面失败不拖垮整轮：记录失败面名 + 修掉可能残留的浮层，后续面继续
const guardErrors = [];
async function guard(page, name, fn) {
  try {
    await dismissTakeover(page);
    await fn();
  } catch (e) {
    const msg = `${name}: ${String(e && e.message ? e.message : e).split('\n')[0].slice(0, 160)}`;
    console.log('  !! guarded failure', msg);
    guardErrors.push({ variant: currentTag, face: name, error: msg });
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(300);
  }
}
let currentTag = '';

// ───────────────────────── 页面内自动检测 ─────────────────────────
async function audit(page, face, v) {
  const res = await page.evaluate((meta) => {
    const out = { face: meta.face, variant: meta.v, items: [] };
    const vw = window.innerWidth, vh = window.innerHeight;
    const vis = (el) => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      if (r.width < 1 || r.height < 1) return false;
      if (s.visibility === 'hidden' || s.display === 'none' || +s.opacity === 0) return false;
      if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) return false;
      return true;
    };
    const desc = (el) => {
      const id = el.id ? '#' + el.id : '';
      const cls = (el.className || '').toString().trim().split(/\s+/).slice(0, 2).map((c) => '.' + c).join('');
      return el.tagName.toLowerCase() + id + cls;
    };
    const parseRGB = (c) => {
      const m = c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
      return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] } : null;
    };
    const lum = ({ r, g, b }) => {
      const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const blend = (fg, bg) => ({
      r: fg.r * fg.a + bg.r * (1 - fg.a),
      g: fg.g * fg.a + bg.g * (1 - fg.a),
      b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1,
    });
    const bgOf = (el) => {
      let cur = el, acc = null;
      while (cur) {
        const bg = parseRGB(getComputedStyle(cur).backgroundColor);
        if (bg && bg.a > 0) { acc = acc ? blend(acc, bg) : bg; if (bg.a >= 0.999 && acc.a >= 0.999) return acc; }
        cur = cur.parentElement;
      }
      const dark = matchMedia('(prefers-color-scheme: dark)').matches;
      return acc && acc.a > 0.5 ? acc : (dark ? { r: 18, g: 18, b: 20, a: 1 } : { r: 255, g: 255, b: 255, a: 1 });
    };

    // 1) 文本对比度
    const textEls = [...document.querySelectorAll('body *')].filter((el) => {
      if (!vis(el)) return false;
      const t = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 1);
      return t;
    });
    let n = 0;
    for (const el of textEls) {
      if (n++ > 900) break;
      const cs = getComputedStyle(el);
      const fg = parseRGB(cs.color);
      if (!fg) continue;
      const bg = bgOf(el);
      const f = blend(fg, bg);
      const L1 = lum(f), L2 = lum(bg);
      const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
      const size = parseFloat(cs.fontSize);
      const bold = +cs.fontWeight >= 700;
      const large = size >= 24 || (size >= 18.66 && bold);
      const need = large ? 3 : 4.5;
      if (ratio < need) {
        out.items.push({
          kind: 'contrast', sel: desc(el), ratio: +ratio.toFixed(2), need,
          size, color: cs.color, bg: `rgb(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)})`,
          text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 28),
        });
      }
    }

    // 2) 点击遮挡：元素中心点被别的元素吃掉
    const interactive = [...document.querySelectorAll('button, a[href], [role="button"], select, textarea, input:not([type=hidden])')].filter(vis);
    for (const el of interactive) {
      const r = el.getBoundingClientRect();
      const x = Math.round(r.x + r.width / 2), y = Math.round(r.y + r.height / 2);
      if (x < 0 || y < 0 || x >= vw || y >= vh) continue;
      const hit = document.elementFromPoint(x, y);
      if (!hit) continue;
      if (hit === el || el.contains(hit) || hit.contains(el)) continue;
      out.items.push({ kind: 'occluded', sel: desc(el), hit: desc(hit), text: (el.innerText || el.getAttribute('title') || '').trim().slice(0, 24), box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] });
    }

    // 3) 触摸目标 < 40px（手机面）
    if (meta.mobile) {
      for (const el of interactive) {
        const r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 40) {
          out.items.push({ kind: 'touch', sel: desc(el), box: [Math.round(r.width), Math.round(r.height)], text: (el.innerText || el.getAttribute('title') || el.getAttribute('aria-label') || '').trim().slice(0, 24) });
        }
      }
    }

    // 4) 文本被裁切（overflow 隐藏且内容溢出）
    for (const el of [...document.querySelectorAll('body *')].filter(vis)) {
      const cs = getComputedStyle(el);
      const clip = /hidden|clip/.test(cs.overflowY) || /hidden|clip/.test(cs.overflow);
      if (!clip) continue;
      if (el.children.length && ![...el.childNodes].some((x) => x.nodeType === 3 && x.textContent.trim())) continue;
      if (el.scrollHeight > el.clientHeight + 4 && el.clientHeight > 0) {
        out.items.push({ kind: 'clip', sel: desc(el), over: el.scrollHeight - el.clientHeight, text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 28) });
      }
    }

    // 5) 横向溢出
    if (document.documentElement.scrollWidth > vw + 1) {
      out.items.push({ kind: 'hoverflow', scrollWidth: document.documentElement.scrollWidth, vw });
    }

    // 6) 过小字号
    for (const el of textEls.slice(0, 900)) {
      const size = parseFloat(getComputedStyle(el).fontSize);
      if (size < 11) out.items.push({ kind: 'tinyfont', sel: desc(el), size, text: (el.textContent || '').trim().slice(0, 22) });
    }

    // 7) 关键面底色（主题一致性取样）
    const sample = (sel) => {
      const e = document.querySelector(sel);
      if (!e) return null;
      const cs = getComputedStyle(e);
      return { bg: cs.backgroundColor, color: cs.color, z: cs.zIndex };
    };
    out.skin = {
      body: getComputedStyle(document.body).backgroundColor,
      activityBar: sample('#activity-bar'),
      sidebar: sample('#sidebar'),
      chat: sample('#chat'),
      inputBar: sample('#input-bar'),
      header: sample('#header'),
    };
    return out;
  }, { face, v: `${v.theme}-${v.size}`, mobile: v.mobile });

  for (const it of res.items) push({ ...it, face, variant: `${v.theme}-${v.size}` });
  return res;
}

// ───────────────────────── 截图 / 驱动 ─────────────────────────
function mkShot(page, face, v) {
  const dir = path.join(ROOT, face);
  fs.mkdirSync(dir, { recursive: true });
  return async (label, full = false) => {
    const file = path.join(dir, `${v.theme}-${v.size}__${label}.png`);
    try {
      await page.screenshot({ path: file, fullPage: full, animations: 'disabled' });
      return file;
    } catch (e) { console.log('  shot failed', label, String(e).slice(0, 120)); return null; }
  };
}

async function tryClick(page, sels, ms = 900) {
  for (const s of sels) {
    try {
      const el = await page.$(s);
      if (!el) continue;
      const vis = await el.isVisible().catch(() => false);
      if (!vis) continue;
      try {
        await el.click({ timeout: 3000 });
      } catch (_) {
        // 被别的层吃掉时退化为强制点击（并记一笔，遮挡本身由 audit 的同名检查报出）
        await el.click({ timeout: 2000, force: true });
      }
      await page.waitForTimeout(ms);
      return s;
    } catch (_) { /* next */ }
  }
  return null;
}

// 接管弹窗（#takeover-box，多客户端同实例时的真实浮层）会遮住全站点击面 ——
// 每个面的开头按需收掉，避免巡检脚本自己被挡。
async function dismissTakeover(page) {
  try {
    const btn = await page.$('#takeover-retake');
    if (btn && (await btn.isVisible())) {
      await btn.click({ timeout: 2500 }).catch(() => {});
      await page.waitForTimeout(700);
    }
  } catch (_) { /* 无弹窗 */ }
}

async function login(page) {
  await page.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(8000);
  await dismissTakeover(page);
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
}

const variants = [];
for (const theme of THEMES) for (const size of SIZES) {
  variants.push({
    theme, size,
    mobile: size === 'mobile',
    viewport: size === 'mobile' ? { width: 375, height: 812 } : { width: 1440, height: 900 },
  });
}

const browser = await chromium.launch({ headless: true });
const report = {};

for (const v of variants) {
  const tag = `${v.theme}-${v.size}`;
  if (ONLY && !ONLY.split(',').includes(tag)) continue;
  currentTag = tag;
  console.log(`\n===== ${tag} =====`);
  const ctx = await browser.newContext({
    viewport: v.viewport,
    colorScheme: v.theme,
    isMobile: v.mobile,
    hasTouch: v.mobile,
    deviceScaleFactor: v.mobile ? 2 : 1,
    locale: 'zh-CN',
  });
  const page = await ctx.newPage();
  const netErrors = [];
  page.on('response', (r) => {
    const st = r.status();
    if (st >= 400) netErrors.push(`${st} ${r.url().replace(BASE, '/')}`);
  });
  page.on('console', (m) => { if (m.type() === 'error') netErrors.push(`console: ${m.text().slice(0, 120)}`); });
  page.on('pageerror', (e) => netErrors.push(`pageerror: ${String(e).slice(0, 120)}`));
  await login(page);
  report[tag] = { netErrors: [], faces: {} };

  // ── 面① 主会话 / 输入区 ──
  await guard(page, '面①', async () => {
    const shot = mkShot(page, '01-chat', v);
    const a = await audit(page, '01-chat-base', v);
    report[tag].faces['01-chat-base'] = a;
    await shot('1-base');
    // 输入态：长文本换行 + 发送按钮态
    const input = await page.$('#input');
    if (input) {
      await input.click({ timeout: 4000 }).catch((e) => console.log('  input click blocked:', String(e).slice(0, 90)));
      await page.evaluate(() => {
        const t = document.getElementById('input');
        t.value = '巡检用的长文本：这是一条用来观察输入框换行、行高、发送按钮位置与光标可见性的多行输入，长度刻意超过一行，看看是否会撑破输入条或者压住附件按钮与语音球。';
        t.dispatchEvent(new Event('input', { bubbles: true }));
      });
      await page.waitForTimeout(700);
      await shot('2-typing');
      report[tag].faces['01-typing'] = await audit(page, '01-chat-typing', v);
    }
    // 附件气泡
    const fileInput = await page.$('#input-area input[type=file]');
    if (fileInput) {
      await fileInput.setInputFiles(FIXTURE_PNG).catch((e) => console.log('  attach fail', String(e).slice(0, 90)));
      await page.waitForTimeout(1500);
      await shot('3-attach');
      report[tag].faces['01-attach'] = await audit(page, '01-chat-attach', v);
    }
    // 编辑态：悬停最后一条消息，找编辑入口
    await page.evaluate(() => {
      const rows = [...document.querySelectorAll('.row.user, .row.ai')];
      rows.forEach((r) => r.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })));
    });
    const lastUser = (await page.$$('.row.user')).pop();
    if (lastUser) {
      await lastUser.hover().catch(() => {});
      await page.waitForTimeout(500);
      const edited = await tryClick(page, ['.row.user:hover .msg-edit', '.row.user .msg-edit', '[data-act="edit"]', '.edit-btn', '.row.user .row-actions button'], 900);
      await shot('4-edit', false);
      report[tag].faces['01-edit'] = await audit(page, '01-chat-edit', v);
      report[tag].editClick = edited;
      if (edited) await page.keyboard.press('Escape').catch(() => {});
    }
    // 队列态（是否出现）
    report[tag].queueBar = await page.evaluate(() => {
      const q = document.getElementById('queue-bar');
      return q ? { kids: q.children.length, text: (q.innerText || '').slice(0, 80) } : null;
    });
    // 清空输入
    await page.evaluate(() => {
      const t = document.getElementById('input');
      if (t) { t.value = ''; t.dispatchEvent(new Event('input', { bubbles: true })); }
    });
    await page.waitForTimeout(300);
  });

  // ── 面② 左侧栏与折叠 ──
  await guard(page, '面②', async () => {
    const shot = mkShot(page, '02-sidebar', v);
    await tryClick(page, ['#sidebar-toggle'], 1100);
    await shot('1-collapsed');
    report[tag].faces['02-collapsed'] = await audit(page, '02-sidebar-collapsed', v);
    await tryClick(page, ['#sidebar-toggle'], 900);
    await shot('2-expanded');
    // 侧栏内部滚动到底
    if (!v.mobile) {
      await page.evaluate(() => {
        const t = document.getElementById('explorer-tree');
        if (t) t.scrollTop = t.scrollHeight;
      });
      await page.waitForTimeout(400);
      await shot('3-tree-scrolled');
    }
    report[tag].faces['02-expanded'] = await audit(page, '02-sidebar-expanded', v);
  });

  // ── 面③ 设置面板全部分区 ──
  await guard(page, '面③', async () => {
    const shot = mkShot(page, '03-settings', v);
    const opened = await tryClick(page, ['#settings-btn'], 1600);
    report[tag].settingsOpen = opened;
    if (opened) {
      const dims = await page.evaluate(() => {
        const m = document.getElementById('settings-modal');
        const c = document.getElementById('settings-content');
        return m && c ? { h: m.clientHeight, sh: c.scrollHeight, ch: c.clientHeight, cards: document.querySelectorAll('#settings-content .set-card, #settings-content [class*=set-]').length, textLen: (c.innerText || '').length } : null;
      });
      report[tag].settingsDims = dims;
      const steps = dims ? Math.min(8, Math.max(1, Math.ceil(dims.sh / Math.max(1, dims.ch)))) : 1;
      for (let i = 0; i < steps; i++) {
        await page.evaluate(([i, steps]) => {
          const c = document.getElementById('settings-content');
          const m = document.getElementById('settings-modal');
          const host = c && c.scrollHeight > c.clientHeight ? c : m;
          if (host) host.scrollTop = Math.round((host.scrollHeight - host.clientHeight) * (i / Math.max(1, steps - 1)));
          window.dispatchEvent(new Event('resize'));
        }, [i, steps]).catch(() => {});
        await page.waitForTimeout(450);
        await shot(`sec-${String(i + 1).padStart(2, '0')}`);
        const r = await audit(page, `03-settings-p${i + 1}`, v);
        report[tag].faces[`03-settings-p${i + 1}`] = r;
      }
      await tryClick(page, ['#settings-modal-close'], 700);
    }
  });

  // ── 面④ 后台智能体 / agentflow ──
  await guard(page, '面④', async () => {
    const shot = mkShot(page, '04-agents', v);
    const a1 = await tryClick(page, ['#bgagent-indicator', '#bg-indicator', '#bg-dropdown'], 1200);
    await shot('1-bg-dropdown');
    report[tag].faces['04-bg'] = await audit(page, '04-bgagent', v);
    report[tag].bgClick = a1;
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(400);
    const a2 = await tryClick(page, ['#agents-btn'], 1500);
    await shot('2-agents-panel');
    report[tag].faces['04-agents'] = await audit(page, '04-agents-panel', v);
    report[tag].agentsClick = a2;
    // 消息面板（委托链 / agentflow 行的所在）
    const a3 = await tryClick(page, ['#messages-btn'], 1500);
    await shot('3-messages');
    report[tag].faces['04-messages'] = await audit(page, '04-messages', v);
    report[tag].messagesClick = a3;
  });

  // ── 面⑤ 画布 / 卡窗 / 独立窗口 ──
  await guard(page, '面⑤', async () => {
    const shot = mkShot(page, '05-canvas', v);
    const opened = await tryClick(page, ['#canvas-toggle-btn'], 1300);
    await shot('1-open');
    report[tag].faces['05-canvas'] = await audit(page, '05-canvas', v);
    report[tag].canvasClick = opened;
    // 打开一个文件进画布（编辑器/预览面）
    if (!v.mobile) {
      const f = await page.$('#explorer-tree .tree-item, #explorer-tree [data-path], #explorer-tree .file');
      if (f) { await f.click().catch(() => {}); await page.waitForTimeout(1600); await shot('2-file-preview'); }
    }
    // 独立窗口
    try {
      const popBtn = await page.$('#canvas-pop-btn');
      if (popBtn && (await popBtn.isVisible())) {
        const [popup] = await Promise.all([
          ctx.waitForEvent('page', { timeout: 6000 }).catch(() => null),
          popBtn.click().catch(() => null),
        ]);
        if (popup) {
          await popup.waitForTimeout(3000);
          const pf = path.join(ROOT, '05-canvas', `${v.theme}-${v.size}__3-popout-window.png`);
          await popup.screenshot({ path: pf }).catch(() => {});
          report[tag].popout = { url: popup.url().slice(0, 120), shot: pf };
          await popup.close().catch(() => {});
        }
      }
    } catch (e) { report[tag].popoutErr = String(e).slice(0, 100); }
  });

  // ── 面⑥ 待办面板 / 手机待办入口 ──
  await guard(page, '面⑥', async () => {
    const shot = mkShot(page, '06-todo', v);
    const a = await tryClick(page, ['#todo-island', '#task-list', '#reminder-btn'], 1200);
    await shot('1-todo-open');
    report[tag].faces['06-todo'] = await audit(page, '06-todo', v);
    report[tag].todoClick = a;
    const b = await tryClick(page, ['#todo-create-btn'], 800);
    await shot('2-todo-create');
    report[tag].faces['06-todo-create'] = await audit(page, '06-todo-create', v);
    report[tag].todoCreateClick = b;
    await tryClick(page, ['#todo-close-btn'], 700);
    if (v.mobile) {
      // 手机待办入口：底栏 / 胶囊 / 抽屉
      const entry = await page.evaluate(() => [...document.querySelectorAll('body *')]
        .filter((e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e); return r.width > 8 && r.height > 8 && s.visibility !== 'hidden' && /待办/.test(e.textContent || '') && e.children.length < 3; })
        .map((e) => ({ cls: (e.className || '').toString().slice(0, 50), t: (e.textContent || '').trim().slice(0, 20) })).slice(0, 8));
      report[tag].mobileTodoEntry = entry;
      await shot('3-mobile-entry');
    }
  });

  // ── 面⑦ 通知横幅与浮层 ──
  await guard(page, '面⑦', async () => {
    const shot = mkShot(page, '07-notify', v);
    // 断网 3s 触发真实的重连横幅（不是伪造 DOM）
    await ctx.setOffline(true);
    await page.waitForTimeout(2600);
    await shot('1-offline-banner');
    report[tag].faces['07-offline'] = await audit(page, '07-offline-banner', v);
    report[tag].offlineBanner = await page.evaluate(() => {
      const b = document.getElementById('conn-banner');
      if (!b) return null;
      const r = b.getBoundingClientRect();
      return { cls: b.className, text: (b.innerText || '').trim().slice(0, 60), box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] };
    });
    await ctx.setOffline(false);
    await page.waitForTimeout(3500);
    await shot('2-after-reconnect');
    // 搜索浮层（真实浮层族之一）
    const s = await tryClick(page, ['#search-btn'], 1400);
    await shot('3-search-overlay');
    report[tag].faces['07-search'] = await audit(page, '07-search-overlay', v);
    report[tag].searchClick = s;
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(400);
  });

  // ── 面⑧ 文件浏览器与 diff ──
  await guard(page, '面⑧', async () => {
    const shot = mkShot(page, '08-explorer-diff', v);
    const a = await tryClick(page, ['#files-btn'], 1300);
    await shot('1-explorer');
    report[tag].faces['08-explorer'] = await audit(page, '08-explorer', v);
    report[tag].filesClick = a;
    // 变更文件面板
    const fc = await page.evaluate(() => {
      const p = document.getElementById('panel-filechanges');
      return p ? { cls: p.className, text: (p.innerText || '').trim().slice(0, 160) } : null;
    });
    report[tag].fileChanges = fc;
    // 点击树内一个文件 → 画布/编辑器面
    const f = await page.$('#explorer-tree .tree-item:not(.dir), #explorer-tree .file-item, #explorer-tree [data-is-dir="false"]');
    if (f) { await f.click().catch(() => {}); await page.waitForTimeout(1800); await shot('2-file-open'); report[tag].faces['08-file-open'] = await audit(page, '08-file-open', v); }
    await shot('3-filechanges-panel');
  });

  // ── 面⑨ 任务 / 合并队列 ──
  await guard(page, '面⑨', async () => {
    const shot = mkShot(page, '09-tasks', v);
    await tryClick(page, ['#projects-btn'], 1500);
    await shot('1-projects');
    report[tag].faces['09-projects'] = await audit(page, '09-tasks-projects', v);
    const info = await page.evaluate(() => {
      const t = document.getElementById('task-list');
      const texts = [...document.querySelectorAll('body *')].filter((e) => e.children.length === 0 && /排队|合并|队列|merging|queue/i.test(e.textContent || '') && (e.textContent || '').length < 40)
        .map((e) => ({ cls: (e.className || '').toString().slice(0, 40), t: (e.textContent || '').trim().slice(0, 30) })).slice(0, 12);
      return { taskList: t ? { cls: t.className, text: (t.innerText || '').trim().slice(0, 200) } : null, texts };
    });
    report[tag].tasks = info;
  });

  report[tag].netErrors = [...new Set(netErrors)].slice(0, 60);
  await ctx.close();
}

await browser.close();
fs.writeFileSync(path.join(DATA, 'findings.json'), JSON.stringify({ findings, report, guardErrors }, null, 1));
console.log('\nfindings:', findings.length, '→', path.join(DATA, 'findings.json'));
