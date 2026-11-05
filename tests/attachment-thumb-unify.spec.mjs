// attachment-thumb-unify.spec.mjs — AskUser 卡附件区「输入框同款小图标/缩略图」
// 机械断言集 + 前后对照取证（attachment-thumb-unify 批，作者令 2026-11-05 12:57）。
//
// 作者报障原话（本批唯一判据来源）：「附件这里添加的图片文件等应该用我们输入框
// 同款的小图标/缩略图」+ 两张实拍：
//   ① 「附件 · 1」区：整张手机截图被渲染成 220×476 的巨大卡片（右侧大留白）；
//   ② 一张缩略图溢出容器、右侧被截断，红 × 删除钮压图错位。
//
// 断言（改前必红 / 改后必绿；编号 = 任务书「要做的事」1-4 + 硬约束）：
//   1) 同源：卡区图片档 = `.att-thumb`（与输入框附件条**同一个类、同一组计算值**
//      ——尺寸/圆角/描边逐值相等），且卡区零 `.pop-media-flat` / `.ds-media-stack`
//      （220px 卡语言媒体卡不再进卡）；`renderAttachmentFace` 全 web/js 恰一份定义。
//   2) 版式：附件条零横向溢出（list scrollWidth ≤ clientWidth；任何子元素
//      boundingRect 不越出 list 盒；卡盒自身不横向溢出）；图片缩略帧恒 28×28
//      （宽幅 3000×120 / 超长 120×4000 同判据，原图尺寸不参与布局）。
//   3) 可点开看大图：点缩略图 → 灯箱层 `.nf-lightbox--media` 可见且媒体元素在位。
//   4) 非图片档：`.att-file` 类型图标 + 文件名（可见省略号 text-overflow:ellipsis，
//      长名真截断：scrollWidth > clientWidth），不靠渐隐。
//   5) 删除角标归位（输入框附件条口径）：`.att-remove` 完整落在附件条可视盒内
//      （不被裁），圆心落在缩略帧右上角角标位（半叠角，不许漂到图外）。
//   6) 折叠：>3 枚默认显 3 + 「共 N 个」，展开后全显。
//   7) 两档视口各跑一遍（375 手机层 / 1440 桌面）+ 零页面错误。
//
// 数据面 = 自包含（route 静态 + routeWebSocket 夹具 + /api/nf-ticket & /api/nf-file
// 票据腿）：零后端、零 sbt、零真实实例、零 6138 端口。
// Run：PW_SELFCONTAINED_ONLY=1 npx playwright test tests/attachment-thumb-unify.spec.mjs
// 取证：ATTUNI_EVIDENCE_DIR=<目录>（默认 ~/.stand/evidence/attachment-thumb）
//       + ATTUNI_PHASE=before|after（子目录名）
// 改前树：ATTUNI_WEB=<git archive 出来的改前 web 根>（同一脚本跑改前树 ⇒ 红）。
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = process.env.ATTUNI_WEB || join(REPO, 'src', 'main', 'resources', 'web');
const OUT_ROOT = process.env.ATTUNI_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/attachment-thumb';
const PHASE = process.env.ATTUNI_PHASE || 'after';
mkdirSync(join(OUT_ROOT, PHASE), { recursive: true });
const OUT = join(OUT_ROOT, PHASE);

const SID = 'attuni-session';
const ORIGIN = 'http://localhost:8893';
const FOLD_AT = 3; // chat.js ASK_ATTACHMENT_FOLD_AT

/* ── 自编码 PNG（免外部夹具；CRC32 手表 —— node:zlib 无 crc32）─────────── */
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}
/** W×H 实心块样张（底 + 中带）：真解码比，任何尺寸都不许影响布局。 */
function solidPng(W, H, rgb) {
  const stride = 1 + W * 3;
  const raw = Buffer.alloc(H * stride);
  for (let y = 0; y < H; y++) {
    raw[y * stride] = 0;
    const band = (y > H * 0.42 && y < H * 0.58);
    for (let x = 0; x < W; x++) {
      const o = y * stride + 1 + x * 3;
      raw[o] = band ? 232 : rgb[0];
      raw[o + 1] = band ? 168 : rgb[1];
      raw[o + 2] = band ? 72 : rgb[2];
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw, { level: 6 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const PNG_PORTRAIT = solidPng(585, 1266, [34, 40, 52]);   // 手机截图比（0.46）
const PNG_WIDE = solidPng(3000, 120, [46, 116, 181]);     // 超宽 25:1
const PNG_TALL = solidPng(120, 4000, [90, 140, 96]);      // 超长 1:33
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');
const PREVIEW_1PX = 'data:image/png;base64,' + PNG_1PX.toString('base64');

/* ── 夹具路径（真盘不落盘：字节由 /api/nf-file 路由提供）───────────────── */
const DIR = '/tmp/attuni-fixture';
const IMG_PHONE = `${DIR}/screenshot_phone.png`;
const IMG_WIDE = `${DIR}/fig_ultra_wide_3000.png`;
const IMG_TALL = `${DIR}/fig_ultra_tall_4000.png`;
const PDF = `${DIR}/reading_notes.pdf`;
const ZIP = `${DIR}/release_bundle.zip`;
const MD_LONG = `${DIR}/20261105_附件缩略图统一批_超长文件名用于验证省略号截断的样本.md`;
const MD = (i) => `${DIR}/附件说明-${String(i).padStart(2, '0')}.md`;
const MISSING = `${DIR}/gone_已迁走_附件.pdf`;
const MD_SET = Array.from({ length: 9 }, (_, i) => (i === 0 ? MD_LONG : MD(i + 1)));

const BYTES = new Map([
  [IMG_PHONE, { mime: 'image/png', body: PNG_PORTRAIT }],
  [IMG_WIDE, { mime: 'image/png', body: PNG_WIDE }],
  [IMG_TALL, { mime: 'image/png', body: PNG_TALL }],
]);
for (const p of [PDF, ZIP, ...MD_SET]) {
  if (!BYTES.has(p)) BYTES.set(p, { mime: 'application/octet-stream', body: Buffer.from('fixture-bytes') });
}
BYTES.get(PDF).mime = 'application/pdf';
BYTES.get(ZIP).mime = 'application/zip';
// MISSING 故意**不登记**：/api/nf-file 404 = 不可读档（降级态腿的数据源）。
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.map': 'application/json',
};

/** 源码单源读数：定义/消费点（改前必红面 —— 卡区媒体卡 + 第二份实现）。 */
function sourceLegs() {
  const chatSrc = readFileSync(join(WEB, 'js', 'chat.js'), 'utf8');
  const defs = (chatSrc.match(/function renderAttachmentFace\s*\(/g) || []).length;
  const thumbImpl = (chatSrc.match(/className = 'att-thumb'/g) || []).length;
  function bodyOf(name) {
    const i = chatSrc.indexOf('function ' + name + '(');
    if (i < 0) return '';
    const j = chatSrc.indexOf('{', i);
    let depth = 0;
    for (let k = j; k < chatSrc.length; k++) {
      if (chatSrc[k] === '{') depth++;
      else if (chatSrc[k] === '}') { depth--; if (depth === 0) return chatSrc.slice(j, k + 1); }
    }
    return '';
  }
  const cardBody = bodyOf('renderAskAttachments');
  const stripBody = bodyOf('renderAttachmentPreviewInner');
  return {
    web: WEB,
    faceDefs: defs,
    thumbImpl,
    cardUsesFace: cardBody.includes('renderAttachmentFace('),
    stripUsesFace: stripBody.includes('renderAttachmentFace('),
    cardUsesMediaCard: /buildMediaFlat\(|buildMediaStack\(/.test(cardBody),
    cardUsesLightbox: cardBody.includes('openMediaLightbox('),
  };
}

/** 一档视口的完整取证 + 断言（375 手机层 / 1440 桌面同一脚本）。 */
async function runViewport(page, vpTag) {
  const failures = [];
  const readings = { phase: PHASE, web: WEB, viewport: vpTag, checks: [], cases: {} };
  page.on('pageerror', (e) => {
    (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 240));
  });
  const check = (name, cond, extra = '') => {
    // eslint-disable-next-line no-console
    console.log(`${cond ? 'PASS' : 'FAIL'}  [${vpTag}] ${name}${extra ? '  — ' + extra : ''}`);
    readings.checks.push({ name, ok: !!cond, extra });
    if (!cond) failures.push(name);
    return cond;
  };

  /* ══ 1 腿：源码单源 ═══════════════════════════════════════════════════ */
  const src = sourceLegs();
  readings.source = src;
  check('1a renderAttachmentFace 恰一份定义', src.faceDefs === 1, `defs=${src.faceDefs}`);
  check('1b 缩略帧面单点（className = att-thumb 恰 1 处）', src.thumbImpl === 1, `impl=${src.thumbImpl}`);
  check('1c 卡区消费同一附件面函数', src.cardUsesFace);
  check('1d 输入框条消费同一附件面函数', src.stripUsesFace);
  check('1e 卡区零卡语言媒体卡（无 buildMediaFlat/buildMediaStack）', !src.cardUsesMediaCard);
  check('1f 卡区预览走灯箱单点（openMediaLightbox）', src.cardUsesLightbox);

  /* ══ 浏览器夹具：真实 web 树 + WS 桩 + 票据腿 ═══════════════════════ */
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    localStorage.removeItem('nebflow_askuser_drafts');
    Object.defineProperty(navigator.serviceWorker, 'register', {
      value: () => new Promise(() => {}), configurable: true,
    });
  });

  let ticketSeq = 0;
  const nfFileHits = [];
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const p0 = decodeURIComponent(url.pathname);
    const p = p0 === '/' ? '/index.html' : p0;
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      if (p === '/api/nf-ticket') {
        let paths = [];
        try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
        const tickets = {};
        for (const q of (Array.isArray(paths) ? paths : [])) {
          tickets[q] = { t: 'tk-' + (++ticketSeq), exp: Date.now() + 600000, uses: -1 };
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ tickets, rejected: [] }) });
      }
      if (p === '/api/nf-file') {
        const path = url.searchParams.get('path') || '';
        nfFileHits.push({ path, ticket: url.searchParams.get('ticket') || '', range: route.request().headers()['range'] || '' });
        const fx = BYTES.get(path);
        if (!fx) return route.fulfill({ status: 404, body: 'not found' });
        return route.fulfill({ status: 200, contentType: fx.mime, body: fx.body });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200, contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      } else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });

  await page.goto(ORIGIN + '/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 25000 });
  await page.waitForTimeout(900);

  /* ══ 输入框附件条（同源对照面）：图 = .att-thumb，✕ = .att-remove ═══ */
  const stripLeg = await page.evaluate(async (preview) => {
    const { activeView } = await import('/js/chatView.js');
    const chatMod = await import('/js/chat.js');
    activeView.pendingAttachments.length = 0;
    activeView.pendingAttachments.push(
      { type: 'image', mimeType: 'image/png', data: '', name: 'strip-shot.png', preview },
      { type: 'text', mimeType: 'text/markdown', data: '', name: 'strip-notes.md' },
    );
    chatMod.renderAttachmentPreview();
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const strip = document.getElementById('attachment-preview');
    const thumb = strip.querySelector('.att-thumb');
    const remove = strip.querySelector('.att-remove');
    const rect = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), right: +b.right.toFixed(1) };
    };
    const cs = thumb ? getComputedStyle(thumb) : null;
      const nameEl = strip.querySelector('.att-file-name');
      return {
        thumbBox: rect(thumb),
        thumbCls: thumb ? thumb.className : '',
        thumbRadius: cs ? cs.borderTopLeftRadius : null,
        thumbBorder: cs ? cs.borderTopWidth : null,
        removeBox: rect(remove),
        stripBox: rect(strip),
        barBox: rect(document.getElementById('input-bar')),
        stripOverflowX: strip.scrollWidth - strip.clientWidth,
        // 命中测试：角标圆心的最上层元素必须是自身上（证明既没被裁也没被邻居盖住）
        removeHitsSelf: (() => {
          if (!remove) return false;
          const b = remove.getBoundingClientRect();
          const top = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
          return !!top && (top === remove || remove.contains(top));
        })(),
        // 裁剪面审计：任一 overflow≠visible 的祖先盒是否切到角标（「不被裁」的真判据）
        removeClippers: (() => {
          if (!remove) return [];
          const b = remove.getBoundingClientRect();
          const out = [];
          let el = remove.parentElement;
          while (el) {
            const cs = getComputedStyle(el);
            if (cs.overflow !== 'visible' || cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
              const r = el.getBoundingClientRect();
              const cut = b.left < r.left - 0.5 || b.right > r.right + 0.5
                || b.top < r.top - 0.5 || b.bottom > r.bottom + 0.5;
              out.push({ host: String(el.id || el.className || el.tagName).slice(0, 40), cut });
            }
            el = el.parentElement;
          }
          return out;
        })(),
        nameOverflow: nameEl ? getComputedStyle(nameEl).textOverflow : null,
      };
  }, PREVIEW_1PX);
  readings.strip = stripLeg;
  check('2a 输入框条图片档 = .att-thumb', !!stripLeg.thumbBox && stripLeg.thumbCls === 'att-thumb', JSON.stringify(stripLeg.thumbBox));
  // ✕ 角标归位（输入框附件条口径）：圆心压在小盒右上角 + 未被裁/未被盖 + 落在输入条内
  const rm = stripLeg.removeBox, th = stripLeg.thumbBox, sb = stripLeg.stripBox, bb = stripLeg.barBox;
  const rmCenter = rm ? { x: rm.x + rm.w / 2, y: rm.y + rm.h / 2 } : null;
  readings.removeBadge = { rm, th, rmCenter, hitSelf: stripLeg.removeHitsSelf, clippers: stripLeg.removeClippers };
  check('2b 删除角标圆心命中自身（既未被裁也未被邻居盖住）', stripLeg.removeHitsSelf === true);
  check('2b2 删除角标不被任一裁剪面切掉（overflow≠visible 祖先盒全含）',
    (stripLeg.removeClippers || []).every((c) => !c.cut),
    JSON.stringify(stripLeg.removeClippers));
  check('2c 删除角标圆心恒压缩略帧右上角（|dx|,|dy| ≤ 1px —— 半叠角角标位）',
    !!rmCenter && !!th
    && Math.abs(rmCenter.x - th.right) <= 1 && Math.abs(rmCenter.y - th.y) <= 1,
    JSON.stringify({ dx: rmCenter && +(rmCenter.x - th.right).toFixed(1), dy: rmCenter && +(rmCenter.y - th.y).toFixed(1) }));
  check('2d 输入框条零横向溢出', stripLeg.stripOverflowX <= 0, `over=${stripLeg.stripOverflowX}`);
  await page.screenshot({ path: join(OUT, `${vpTag}-strip.png`), clip: await clipOf(page, '#input-bar') });

  // 清空条带，避免干扰卡区读数
  await page.evaluate(async () => {
    const { activeView } = await import('/js/chatView.js');
    const chatMod = await import('/js/chat.js');
    activeView.pendingAttachments.length = 0;
    chatMod.renderAttachmentPreview();
  });
  await page.waitForTimeout(150);

  /* ══ 挂四张 AskUser 卡 ═══════════════════════════════════════════════ */
  const CASES = [
    { id: 'p1', attachments: [IMG_PHONE], question: '手机截图附件：是否可读？' },
    { id: 'p2', attachments: [IMG_WIDE, IMG_TALL, PDF], question: '极端尺寸 + 非图片文件' },
    { id: 'p5', attachments: [MD_LONG, ZIP], question: '长名与压缩包' },
    { id: 'p3', attachments: MD_SET.slice(0, 9), question: '九件文件一起看' },
    { id: 'p4', attachments: [MISSING], question: '缺件档：文件已迁走' },
  ];
  const mounted = await page.evaluate(async (cases) => {
    const chatMod = await import('/js/chat.js');
    const { activeView } = await import('/js/chatView.js');
    const chat = activeView.dom.chat;
    const out = [];
    for (const c of cases) {
      const before = chat.querySelectorAll('.option-box').length;
      chatMod.renderAskUser(
        [{ question: c.question, options: [{ label: 'A' }, { label: 'B' }] }],
        activeView.sessionId, 'Nebula', 'attuni-' + c.id, null, c.attachments,
      );
      let box = null;
      for (let i = 0; i < 60; i++) {
        const boxes = chat.querySelectorAll('.option-box');
        if (boxes.length > before) { box = boxes[boxes.length - 1]; break; }
        await new Promise((r) => requestAnimationFrame(r));
      }
      if (box) box.dataset.askCase = c.id;
      out.push({ id: c.id, mounted: !!box });
    }
    return out;
  }, CASES);
  readings.mounted = mounted;
  await page.waitForTimeout(1600); // 探针（票据轨首字节）+ 缩略帧字节腿收敛窗
  check('3a 四张卡全部挂载', mounted.every((m) => m.mounted), JSON.stringify(mounted));

  /* ══ 卡区读数 ═══════════════════════════════════════════════════════ */
  const readCards = () => page.evaluate(() => {
    const rect = (el) => {
      if (!el) return null;
      const b = el.getBoundingClientRect();
      return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1), right: +b.right.toFixed(1) };
    };
    const visible = (el) => {
      const cs = getComputedStyle(el);
      const b = el.getBoundingClientRect();
      return cs.display !== 'none' && cs.visibility !== 'hidden' && b.height > 0 && b.width > 0;
    };
    const out = {};
    for (const box of document.querySelectorAll('.option-box')) {
      const id = box.dataset.askCase;
      if (!id) continue;
      const wrap = box.querySelector('.ask-attachments-wrap');
      const list = wrap && wrap.querySelector('.ask-attachments-list');
      const thumbs = list ? [...list.querySelectorAll('.att-thumb')] : [];
      const thumbWraps = list ? [...list.querySelectorAll('.ask-att-thumb')] : [];
      const chips = list ? [...list.querySelectorAll('.att-file.ask-att-file')] : [];
      const names = chips.map((c) => c.querySelector('.att-file-name'));
      const glyphs = chips.map((c) => c.querySelector('.att-file-glyph'));
      // 溢出：任何后代越出 list 盒（左右上下各一道）
      const offenders = [];
      if (list) {
        const lb = list.getBoundingClientRect();
        for (const el of list.querySelectorAll('*')) {
          if (!visible(el)) continue;
          const r = el.getBoundingClientRect();
          if (r.right > lb.right + 1 || r.left < lb.left - 1) {
            offenders.push({
              cls: String(el.className || el.tagName).slice(0, 40),
              rect: [+r.left.toFixed(1), +r.right.toFixed(1)], list: [+lb.left.toFixed(1), +lb.right.toFixed(1)],
            });
          }
        }
      }
      const lb = list ? list.getBoundingClientRect() : null;
      const thumb = thumbs[0];
      out[id] = {
        boxH: rect(box) && rect(box).h,
        mediaCards: wrap ? wrap.querySelectorAll('.pop-media-flat, .ds-media-stack').length : 0,
        thumbs: thumbs.length,
        thumbBox: thumb ? rect(thumb) : null,
        thumbCls: thumb ? thumb.className : null,
        thumbRadius: thumb ? getComputedStyle(thumb).borderTopLeftRadius : null,
        thumbBorder: thumb ? getComputedStyle(thumb).borderTopWidth : null,
        thumbFit: thumb ? getComputedStyle(thumb).objectFit : null,
        thumbWraps: thumbWraps.length,
        thumbWrapBox: thumbWraps[0] ? rect(thumbWraps[0]) : null,
        chips: chips.length,
        visibleChips: chips.filter(visible).length,
        chipBoxes: chips.filter(visible).map((c) => rect(c)),
        nameFontPx: names[0] ? parseFloat(getComputedStyle(names[0]).fontSize) : null,
        nameOverflow: names[0] ? getComputedStyle(names[0]).textOverflow : null,
        nameTruncated: names.map((n) => {
          if (!n) return false;
          const boxW = n.getBoundingClientRect().width;
          const rg = document.createRange();
          rg.selectNodeContents(n);
          const textW = rg.getBoundingClientRect().width;
          return n.scrollWidth > n.clientWidth + 1 || textW > boxW + 1;
        }),
        thumbWrapCS: thumbWraps[0] ? (() => {
          const cs = getComputedStyle(thumbWraps[0]);
          return {
            display: cs.display, alignItems: cs.alignItems,
            h: +thumbWraps[0].getBoundingClientRect().height.toFixed(1),
            imgMargin: thumb ? getComputedStyle(thumb).margin : null,
          };
        })() : null,
        iconShown: glyphs.map((i) => !!i && getComputedStyle(i).display !== 'none'),
        nameShown: names.map((n) => !!n && getComputedStyle(n).display !== 'none'),
        folded: wrap ? wrap.querySelectorAll('.ask-att-folded').length : 0,
        foldBtn: !!(wrap && wrap.querySelector('.ask-att-fold-btn')),
        foldText: wrap && wrap.querySelector('.ask-att-fold-btn') ? wrap.querySelector('.ask-att-fold-btn').textContent : null,
        foldAria: wrap && wrap.querySelector('.ask-att-fold-btn') ? wrap.querySelector('.ask-att-fold-btn').getAttribute('aria-expanded') : null,
        originTags: wrap ? wrap.querySelectorAll('.ds-att-origin').length : 0,
        originText: wrap && wrap.querySelector('.ds-att-origin') ? wrap.querySelector('.ds-att-origin').textContent : null,
        unavailable: wrap ? wrap.querySelectorAll('.ask-att-unavailable').length : 0,
        reasonTexts: wrap ? [...wrap.querySelectorAll('.ask-att-reason')].map((n) => n.textContent) : [],
        retryBtns: wrap ? wrap.querySelectorAll('.ask-att-retry').length : 0,
        listW: lb ? +lb.width.toFixed(1) : null,
        listOverflowX: list ? list.scrollWidth - list.clientWidth : null,
        boxOverflowX: box.scrollWidth - box.clientWidth,
        offenders,
      };
    }
    return out;
  });

  const r = await readCards();
  readings.cases = r;
  const p1 = r.p1 || {}, p2 = r.p2 || {}, p3 = r.p3 || {}, p4 = r.p4 || {}, p5 = r.p5 || {};

  /* ══ 1 腿（运行时）：卡区图片档 = 输入框同款小缩略图 ═══════════════ */
  check('1g p1 图片档渲染为 .att-thumb（且仅一枚）', p1.thumbs === 1 && p1.thumbCls === 'att-thumb',
    JSON.stringify({ thumbs: p1.thumbs, cls: p1.thumbCls }));
  check('1h 卡区零 220px 卡语言媒体卡（.pop-media-flat/.ds-media-stack = 0）',
    p1.mediaCards === 0 && p2.mediaCards === 0, JSON.stringify({ p1: p1.mediaCards, p2: p2.mediaCards }));
  check('1i 缩略帧与输入框条逐值同盒（尺寸/圆角/描边/取景）',
    !!p1.thumbBox && !!stripLeg.thumbBox
    && p1.thumbBox.w === stripLeg.thumbBox.w && p1.thumbBox.h === stripLeg.thumbBox.h
    && p1.thumbRadius === stripLeg.thumbRadius && p1.thumbBorder === stripLeg.thumbBorder
    && p1.thumbFit === 'cover',
    JSON.stringify({ card: p1.thumbBox, strip: stripLeg.thumbBox, radius: [p1.thumbRadius, stripLeg.thumbRadius], border: [p1.thumbBorder, stripLeg.thumbBorder], fit: p1.thumbFit }));
  check('1j 缩略帧 ≤ 28px 档（不是 220px 大卡）', !!p1.thumbBox && p1.thumbBox.w <= 28.5 && p1.thumbBox.h <= 28.5,
    JSON.stringify(p1.thumbBox));

  /* ══ 2 腿：版式（任何尺寸原图都不撑破容器）═══════════════════════════ */
  for (const [id, c] of Object.entries(r)) {
    check(`2e ${id} 附件条零横向溢出（scrollWidth ≤ clientWidth）`, c.listOverflowX <= 0, `over=${c.listOverflowX}`);
    check(`2f ${id} 卡盒零横向溢出`, c.boxOverflowX <= 0, `over=${c.boxOverflowX}`);
    check(`2g ${id} 无子元素越出附件条盒（含超宽/超长原图）`, c.offenders.length === 0, JSON.stringify(c.offenders));
  }
  check('2h p2 超宽(3000×120)/超长(120×4000) 原图仍为 28×28 缩略帧',
    !!p2.thumbBox && p2.thumbBox.w <= 28.5 && p2.thumbBox.h <= 28.5, JSON.stringify(p2.thumbBox));
  check('2i p2 三件（2 图 + pdf）同条带换行、无溢出',
    p2.chips === 1 && p2.thumbs === 2 && p2.offenders.length === 0,
    JSON.stringify({ chips: p2.chips, thumbs: p2.thumbs }));

  /* ══ 4 腿：非图片档 = 类型图标 + 文件名（省略号截断）═══════════════ */
  check('4a 非图片档全部为 .att-file chip（pdf/zip/md）', p2.chips + p5.chips === 3,
    JSON.stringify({ p2: p2.chips, p5: p5.chips }));
  check('4b chip 内类型图标在位（.att-file-glyph 文件图标可见）',
    [...(p2.iconShown || []), ...(p5.iconShown || [])].every(Boolean) && (p2.iconShown || []).length + (p5.iconShown || []).length === 3,
    JSON.stringify({ p2: p2.iconShown, p5: p5.iconShown }));
  check('4b2 chip 内文件名可见（图标 + 名字两件齐）',
    [...(p2.nameShown || []), ...(p5.nameShown || [])].every(Boolean),
    JSON.stringify({ p2: p2.nameShown, p5: p5.nameShown }));
  check('4c 文件名截断走省略号（text-overflow: ellipsis）', p5.nameOverflow === 'ellipsis', String(p5.nameOverflow));
  check('4d 长名真截断（省略号生效：名字盒宽 < 文本固有宽）', (p5.nameTruncated || []).some(Boolean),
    JSON.stringify(p5.nameTruncated));

  /* ══ 6 腿：折叠减负 ═════════════════════════════════════════════════ */
  check('6a p3 九件默认只显前 3 枚', p3.visibleChips === FOLD_AT && p3.folded === 6,
    JSON.stringify({ vis: p3.visibleChips, folded: p3.folded }));
  check('6b 折叠键文本含总件数（共 9 个）', !!p3.foldText && /9/.test(p3.foldText), JSON.stringify(p3.foldText));

  /* ══ 5 腿：不可读档降级（原因 + 可执行动作）═══════════════════════ */
  check('5a p4 缺件档落降级态', p4.unavailable >= 1, JSON.stringify({ unavailable: p4.unavailable }));
  check('5b 原因档非「不可用」裸词 + 重试下载键在位',
    p4.reasonTexts.length >= 1 && p4.reasonTexts.every((x) => x && x.trim() !== '不可用') && p4.retryBtns >= 1,
    JSON.stringify({ reasons: p4.reasonTexts, retry: p4.retryBtns }));

  /* ══ 来源标签：一处（标题行），不压附件 ════════════════════════════ */
  check('1k 来源标签一处挂在标题行（每 chip notch 退役）',
    p1.originTags === 1 && /AskUser/.test(p1.originText || ''),
    JSON.stringify({ n: p1.originTags, text: p1.originText }));

  /* ── 取证截图（每档视口一组）────────────────────────────────────── */
  await page.evaluate(() => {
    const box = document.querySelector('.option-box[data-ask-case="p1"]');
    if (box) box.scrollIntoView({ block: 'center' });
  });
  await page.waitForTimeout(200);
  await page.screenshot({ path: join(OUT, `${vpTag}-card-p1.png`) });
  await page.evaluate(() => {
    const box = document.querySelector('.option-box[data-ask-case="p2"]');
    if (box) box.scrollIntoView({ block: 'center' });
  });
  await page.waitForTimeout(200);
  await page.screenshot({ path: join(OUT, `${vpTag}-card-p2.png`) });
  await page.evaluate(() => {
    const box = document.querySelector('.option-box[data-ask-case="p3"]');
    if (box) box.scrollIntoView({ block: 'center' });
  });
  await page.waitForTimeout(200);
  await page.screenshot({ path: join(OUT, `${vpTag}-card-p3.png`) });

  /* ══ 3 腿：点缩略图看原图 ══════════════════════════════════════════ */
  const hitsBefore = nfFileHits.length;
  await page.evaluate(() => {
    const box = document.querySelector('.option-box[data-ask-case="p1"]');
    const wrap = box && box.querySelector('.ask-att-thumb');
    if (wrap) wrap.click();
  });
  await page.waitForTimeout(600);
  const lb = await page.evaluate(() => {
    const el = document.querySelector('.nf-lightbox');
    if (!el) return { present: false };
    const cs = getComputedStyle(el);
    const media = el.querySelector('.nf-lightbox-media');
    return {
      present: true,
      cls: el.className,
      visible: cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0,
      mediaTag: media ? media.tagName : null,
      mediaNatural: media && media.tagName === 'IMG' ? media.naturalWidth : null,
      name: el.querySelector('.nf-lightbox-name') ? el.querySelector('.nf-lightbox-name').textContent : null,
      canvasEntry: !!el.querySelector('.nf-lightbox-canvas'),
      mediaBox: media ? (() => { const b = media.getBoundingClientRect(); return [+b.width.toFixed(1), +b.height.toFixed(1)]; })() : null,
    };
  });
  readings.lightbox = lb;
  check('3b 点缩略图 → 灯箱层可见（.nf-lightbox--media）',
    lb.present === true && lb.visible === true && /nf-lightbox--media/.test(lb.cls || ''), JSON.stringify(lb));
  check('3c 灯箱里是原图（naturalWidth = 585，真解码）+ 文件名在位',
    lb.mediaTag === 'IMG' && lb.mediaNatural === 585 && lb.name === 'screenshot_phone.png',
    JSON.stringify({ tag: lb.mediaTag, nat: lb.mediaNatural, name: lb.name }));
  check('3d 灯箱保留 Canvas 次级入口（path 在 ⇒ 可看大图/外跳）', lb.canvasEntry === true);
  check('3e 灯箱复用账本字节（点开后零重复抓取增量 ≤1）', nfFileHits.length - hitsBefore <= 1,
    JSON.stringify({ before: hitsBefore, after: nfFileHits.length }));
  await page.screenshot({ path: join(OUT, `${vpTag}-lightbox.png`) });
  await page.evaluate(() => { const el = document.querySelector('.nf-lightbox'); if (el) el.remove(); });
  await page.waitForTimeout(150);

  /* ══ 7 腿：零页面错误 ══════════════════════════════════════════════ */
  const errs = (readings.pageErrors || []).filter((t) => !/favicon/i.test(t));
  check('7a 零页面错误', errs.length === 0, errs.slice(0, 3).join(' | '));

  writeFileSync(join(OUT, `${vpTag}-readings.json`), JSON.stringify(readings, null, 2));
  console.log(`\n[attachment-thumb-unify] phase=${PHASE} viewport=${vpTag} web=${WEB}`);
  console.log(`[attachment-thumb-unify] ${readings.checks.length - failures.length}/${readings.checks.length} PASS → ${OUT}`);
  if (failures.length) throw new Error(`attachment-thumb-unify failed [${vpTag}] (${failures.length}): ${failures.join(' | ')}`);
}

/** 元素裁剪盒（截图用；元素缺失回全屏）。 */
async function clipOf(page, sel) {
  const box = await page.evaluate((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { x: Math.max(0, b.x - 8), y: Math.max(0, b.y - 8), width: Math.min(b.width + 16, window.innerWidth), height: Math.min(b.height + 16, window.innerHeight) };
  }, sel);
  return box || undefined;
}

const VIEWPORTS = [
  { tag: '375', width: 375, height: 812, hasTouch: true },
  { tag: '1440', width: 1440, height: 900, hasTouch: false },
];

for (const vp of VIEWPORTS) {
  test.describe(`AskUser 卡附件 · 输入框同款小图标/缩略图 @${vp.tag}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.hasTouch, colorScheme: 'light' });
    test(`同源 / 版式 / 可点开 / 极端用例 @${vp.tag}`, async ({ page }) => {
      await runViewport(page, vp.tag);
    });
  });
}
