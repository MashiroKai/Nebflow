// askuser-attach-unify.spec.mjs — AskUser 卡附件「同源渲染 / 可打开 / 内嵌叠卡 /
// 卡片减负」机械断言集（ui-unification-20261103-askuser-attach，2026-11-04 批）。
//
// 作者原话（本批唯一判据来源）：「第一是这个 Ask User 卡片里面的这些附件：像图片和
// 视频都会以最小的缩略图形式显示，文件则以图标形式显示，跟我们输入框的这些显示应该
// 要是同源的同时如果是在手机端的话，你们的这些文件我无法在 Canvas 打开，它没有自动
// 打开。或者说如果不用自动打开的话，最好也能让我点开 这个设计的样式可以内嵌到
// Ask User 卡片里面：1. 图片和视频：也是以这种叠卡的形式存在 2. 文件：以我们目前的
// 形式存在，只是在左上角打一个标签，说明这些文件来源于哪一个 Ask User。这样的话，
// 原本的 Ask User 卡片里面就可以不显示，或者只显示缩略图了」
//
// 断言（同脚本、改前必红 / 改后必绿；编号 = 任务书 a–f + ⑤a + iOS HIG）：
//   a) 同源：`renderAttachmentFace` 全 web/js **恰一份定义**，两处附件面
//      （renderAttachmentPreviewInner = 输入框条 / renderAskAttachments = 卡区）
//      同一函数；卡区媒体走卡语言标准组件（buildMediaFlat/buildMediaStack +
//      openMediaLightbox），卡区零第二份 <img>/<video> 自造；图标面单词源。
//   b) 图/视频真实缩略帧宽 ≥ max(卡片内容宽 × 0.2, 96px)；单件 = 平面卡
//      （.pop-media-flat）、多件 = 叠卡（.ds-media-stack + .ds-media-stack__pill）。
//   c) 点击图 / 叠卡 / 文件：预览层 DOM 真出现且可见（.nf-lightbox--media
//      display:flex），文件点击 → /api/nf-file 取回次数递增（不得点了没反应）。
//   d) 文件卡左上角来源标签在位（.ds-att-origin），文本含 AskUser 标识，几何在
//      左上（tag.top < chip.top + 2 且 tag.left ≤ chip.left + 14）。
//   e) 占位符与任何其它文本节点 boundingRect 不相交；`#nf-mode-chip` / 输入条内
//      「N.NAV」文本零命中（作者实拍 IMG_1725 的叠字面）。
//   f) 卡身不随件数线性膨胀：4 枚与 9 枚文件卡片高度差 ≤6px，且默认可见 chip
//      = 3（部分折叠，非整列隐藏），折叠键文本含总件数，展开后 9 枚全可见。
//   ⑤a) 不可用态**给原因 + 可执行动作**：原因档文本 ≠「不可用」裸词，重试下载键在位。
//   h) 手机 HIG：文件 chip 高 ≥44、名字字阶 ≥13、长名**模糊淡出**（mask 渐变 +
//      text-overflow:clip，铁律禁省略号）。
//   l) i18n 双档词条齐备（zh-CN / en 同键集）。
//   z) 零页面错误。
//
// 数据面 = 自包含（route 静态 + routeWebSocket 夹具 + /api/nf-ticket & /api/nf-file
// 票据腿）：零后端、零 sbt、零真实实例。
// Run：PW_SELFCONTAINED_ONLY=1 npx playwright test tests/askuser-attach-unify.spec.mjs
// 取证：ASKATT_EVIDENCE_DIR=<目录>（默认 …/evidence/ui-unification-20261103/askuser-attach）
//       + ASKATT_PHASE=before|after（子目录名）。
// 改前树：ASKATT_WEB=<git archive 出来的改前 web 根>（同一脚本跑改前树 ⇒ 红）。
import { test } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = process.env.ASKATT_WEB || join(REPO, 'src', 'main', 'resources', 'web');
const EVIDENCE_ROOT = process.env.ASKATT_EVIDENCE_DIR
  || '/Users/kaiyu/.stand/evidence/ui-unification-20261103/askuser-attach';
const OUT = join(EVIDENCE_ROOT, process.env.ASKATT_PHASE || 'after');
mkdirSync(OUT, { recursive: true });

const SID = 'askatt-unify-session';
const ORIGIN = 'http://localhost:8891';
const FOLD_AT = 3; // 卡区折叠阈值（chat.js ASK_ATTACHMENT_FOLD_AT）

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
/** W×H 的实心块样张（蓝底 + 橙带）：真解码比，缩略图宽由 fit 算术决定。 */
function solidPng(W, H, rgb) {
  const stride = 1 + W * 3;
  const raw = Buffer.alloc(H * stride);
  for (let y = 0; y < H; y++) {
    raw[y * stride] = 0;
    for (let x = 0; x < W; x++) {
      const o = y * stride + 1 + x * 3;
      const band = (y > H * 0.42 && y < H * 0.58);
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
const PNG_WIDE = solidPng(1024, 430, [46, 116, 181]);   // 宽幅（2.38:1）
const PNG_SQUARE = solidPng(640, 640, [90, 140, 96]);   // 方图（1:1）
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64');
const PREVIEW_1PX = 'data:image/png;base64,' + PNG_1PX.toString('base64');

/* ── 夹具路径（真盘不落盘：字节由 /api/nf-file 路由提供）───────────────── */
const DIR = '/tmp/askatt-unify-fixture';
const IMG_A = `${DIR}/fig3b_cabin_zoom.png`;
const IMG_B = `${DIR}/planer_map.png`;
const VID = `${DIR}/demo_clip.mp4`;
// 长中文名（h 腿「模糊淡出」的样本；也是作者实拍「2026…」档）
const MD_LONG = `${DIR}/20261031_中子能谱盘点报告_超长文件名验证模糊淡出而非省略号.md`;
const MD = (i) => `${DIR}/附件说明-${String(i).padStart(2, '0')}.md`;
// 缺件档必须是**文件 chip**（.pdf，不走媒体面）——⑤a 腿量的是文件 chip 的降级态。
const MISSING = `${DIR}/gone_附件说明_已迁走.pdf`;
const MD_SET = Array.from({ length: 9 }, (_, i) => (i === 0 ? MD_LONG : MD(i + 1)));

/** 可读路径集合（/api/nf-file 200；其余 404 = missing 档）。 */
const READABLE = new Set([IMG_A, IMG_B, VID, ...MD_SET]);
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf', '.map': 'application/json',
};

/** 源码单源读数（a 腿）：定义/消费点/第二份实现的存在性。 */
function sourceLegs() {
  const jsRoot = join(WEB, 'js');
  const files = [];
  (function walk(d) {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else if (n.endsWith('.js')) files.push(p);
    }
  })(jsRoot);
  const srcOf = (p) => readFileSync(p, 'utf8');
  let defs = 0;
  let calls = 0;
  let thumbImpl = 0;
  let iconImpl = 0;
  for (const f of files) {
    const s = srcOf(f);
    defs += (s.match(/function renderAttachmentFace\s*\(/g) || []).length;
    calls += (s.match(/renderAttachmentFace\(/g) || []).length;
    thumbImpl += (s.match(/className = 'att-thumb'/g) || []).length;
    iconImpl += (s.match(/class="att-file-icon/g) || []).length;
  }
  const chatSrc = srcOf(join(jsRoot, 'chat.js'));
  /** 按花括号配对截取函数体（够用的近似：目标函数内无花括号字面量）。 */
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
  const zh = readFileSync(join(jsRoot, 'locales', 'zh-CN.js'), 'utf8');
  const en = readFileSync(join(jsRoot, 'locales', 'en.js'), 'utf8');
  const i18nKeys = ['attUnavailable_missing', 'attUnavailable_denied', 'attUnavailable_network',
    'attUnavailable_unsupported', 'attCanDownload', 'attRetryDownload', 'attDownloadFailed',
    'attFoldTotal'];
  return {
    web: WEB,
    jsFileCount: files.length,
    faceDefs: defs,
    faceCalls: calls,
    thumbImpl,
    iconImpl,
    cardUsesFace: cardBody.includes('renderAttachmentFace('),
    stripUsesFace: stripBody.includes('renderAttachmentFace('),
    cardUsesMediaFlat: cardBody.includes('buildMediaFlat('),
    cardUsesMediaStack: cardBody.includes('buildMediaStack('),
    cardUsesLightbox: cardBody.includes('openMediaLightbox('),
    cardOwnImg: /createElement\('img'\)/.test(cardBody),
    cardOwnVideo: /createElement\('video'\)/.test(cardBody),
    i18n: i18nKeys.map((k) => ({
      key: k, zh: zh.includes(`'askUser.${k}'`), en: en.includes(`'askUser.${k}'`),
    })),
  };
}

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, colorScheme: 'light' });

test.describe('AskUser 卡附件 · 同源 / 可打开 / 叠卡 / 减负', () => {
  test('a,b,c,d,e,f,⑤a,h,i18n,z · 改前必红改后必绿', async ({ page }) => {
    const failures = [];
    const readings = { phase: process.env.ASKATT_PHASE || 'after', web: WEB, cases: {}, checks: [] };
    page.on('pageerror', (e) => {
      (readings.pageErrors = readings.pageErrors || []).push(String(e.message).slice(0, 240));
    });
    const check = (name, cond, extra = '') => {
      // eslint-disable-next-line no-console
      console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
      readings.checks.push({ name, ok: !!cond, extra });
      if (!cond) failures.push(name);
      return cond;
    };

    /* ══ a 腿：源码单源 ═══════════════════════════════════════════════ */
    const src = sourceLegs();
    readings.source = src;
    check('a1 renderAttachmentFace 全 web/js 恰一份定义', src.faceDefs === 1, `defs=${src.faceDefs} of ${src.jsFileCount} js files`);
    check('a2 输入框附件条消费同一函数（renderAttachmentPreviewInner → renderAttachmentFace）', src.stripUsesFace);
    check('a3 AskUser 卡区消费同一函数（renderAskAttachments → renderAttachmentFace）', src.cardUsesFace);
    check('a4 卡区不再装配 220px 卡语言媒体卡（attachment-thumb-unify 2026-11-05 改判）',
      !src.cardUsesMediaFlat && !src.cardUsesMediaStack, JSON.stringify({ flat: src.cardUsesMediaFlat, stack: src.cardUsesMediaStack }));
    check('a5 卡区预览走灯箱单点（openMediaLightbox）', src.cardUsesLightbox);
    check('a6 卡区零第二套媒体实现（无 createElement(<img>/<video>)）',
      !src.cardOwnImg && !src.cardOwnVideo, JSON.stringify({ img: src.cardOwnImg, video: src.cardOwnVideo }));
    check('a7 缩略图面单词源（className = \'att-thumb\' 恰 1 处）', src.thumbImpl === 1, `impl=${src.thumbImpl}`);
    check('a8 文件图标面单词源（class="att-file-icon 恰 1 处）', src.iconImpl === 1, `impl=${src.iconImpl}`);
    for (const k of src.i18n) {
      check(`l1 i18n 双档词条 ${k.key}`, k.zh && k.en, JSON.stringify(k));
    }

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

    const nfFileHits = [];   // {path, ticket}
    let ticketSeq = 0;
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
          nfFileHits.push({ path, ticket: url.searchParams.get('ticket') || '' });
          if (!READABLE.has(path)) return route.fulfill({ status: 404, body: 'not found' });
          const ext = extname(path).toLowerCase();
          if (ext === '.png') {
            return route.fulfill({ status: 200, contentType: 'image/png', body: path === IMG_A ? PNG_WIDE : PNG_SQUARE });
          }
          // .md / .mp4 档：字节面只需「可读」，容器解码失败不影响结构断言。
          return route.fulfill({
            status: 200,
            contentType: ext === '.mp4' ? 'video/mp4' : 'text/markdown; charset=utf-8',
            body: Buffer.from('# fixture\n'),
          });
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
    await page.waitForFunction(() => window.__nfMobileLayer && window.__nfMobileLayer.active === true, null, { timeout: 20000 });
    await page.waitForTimeout(900);

    /* ══ e 腿：占位符 vs 其它文本节点（空附件态先量）═══════════════════ */
    const eLeg = await page.evaluate(() => {
      const bar = document.getElementById('input-bar');
      const inp = document.getElementById('input');
      const ir = inp.getBoundingClientRect();
      const offenders = [];
      const navHits = [];
      const walk = (el) => {
        for (const child of el.children) {
          const cs = getComputedStyle(child);
          const r = child.getBoundingClientRect();
          const ownText = [...child.childNodes]
            .filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join('');
          if (ownText && /\bNAV\b/i.test(ownText)) {
            navHits.push({ cls: String(child.className || ''), id: child.id, text: ownText.slice(0, 40) });
          }
          const visible = cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0;
          if (visible && ownText && r.width > 1 && r.height > 1
            && child !== inp && !inp.contains(child) && !child.contains(inp)) {
            const overlap = !(r.right <= ir.left || r.left >= ir.right || r.bottom <= ir.top || r.top >= ir.bottom);
            if (overlap) {
              offenders.push({
                id: child.id, cls: String(child.className || ''), text: ownText.slice(0, 40),
                rect: [+r.x.toFixed(1), +r.y.toFixed(1), +r.width.toFixed(1), +r.height.toFixed(1)],
              });
            }
          }
          walk(child);
        }
      };
      if (bar) walk(bar);
      return {
        barFound: !!bar,
        inputRect: [+ir.x.toFixed(1), +ir.y.toFixed(1), +ir.width.toFixed(1), +ir.height.toFixed(1)],
        placeholder: inp.placeholder,
        modeChipAny: document.querySelectorAll('#nf-mode-chip, .nf-mode-chip').length,
        navHits,
        offenders,
      };
    });
    readings.e = eLeg;
    check('e1 输入条在场（#input-bar / #input）', eLeg.barFound && eLeg.inputRect[2] > 40, JSON.stringify(eLeg.inputRect));
    check('e2 已退役的 .nf-mode-chip 零命中（作者实拍「1.NAV」叠字的机制面）', eLeg.modeChipAny === 0, `hits=${eLeg.modeChipAny}`);
    check('e3 输入条内「N.NAV」文本零命中', eLeg.navHits.length === 0, JSON.stringify(eLeg.navHits));
    check('e4 占位符与其他文本节点 boundingRect 零相交', eLeg.offenders.length === 0, JSON.stringify(eLeg.offenders));

    /* ══ 输入框附件条（同源对照面）：图 = .att-thumb，文件 = .att-file ══ */
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
      const thumbs = [...strip.querySelectorAll('.att-thumb')];
      const files = [...strip.querySelectorAll('.att-file')];
      const r = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
      };
      return {
        thumbs: thumbs.length,
        thumbBox: r(thumbs[0]),
        files: files.length,
        fileCls: files[0] ? files[0].className : '',
        fileHasAskCls: files.some((f) => f.classList.contains('ask-att-file')),
        originInStrip: strip.querySelectorAll('.ds-att-origin').length,
      };
    }, PREVIEW_1PX);
    readings.strip = stripLeg;
    check('a9 输入框条图片档 = .att-thumb 真缩略帧', stripLeg.thumbs === 1 && !!stripLeg.thumbBox && stripLeg.thumbBox.w > 0,
      JSON.stringify(stripLeg.thumbBox));
    check('a10 输入框条文件档 = .att-file chip（且不挂卡区来源标签/卡区类）',
      stripLeg.files === 1 && !stripLeg.fileHasAskCls && stripLeg.originInStrip === 0,
      JSON.stringify({ cls: stripLeg.fileCls, origin: stripLeg.originInStrip }));
    // 清空条带，避免干扰卡区截图与后续读数
    await page.evaluate(async () => {
      const { activeView } = await import('/js/chatView.js');
      const chatMod = await import('/js/chat.js');
      activeView.pendingAttachments.length = 0;
      chatMod.renderAttachmentPreview();
    });
    await page.waitForTimeout(180);

    /* ══ 挂 5 张 AskUser 卡（c1..c5）══════════════════════════════════ */
    const CASES = [
      { id: 'c1', attachments: [IMG_A, MD_SET[1], MD_SET[2]], question: '图片缩放确认：哪一档合适？' },
      { id: 'c2', attachments: [MD_SET[0], MD_SET[1], MD_SET[2], MD_SET[3]], question: '四件文件一起看' },
      { id: 'c3', attachments: MD_SET.slice(0, 9), question: '九件文件一起看' },
      { id: 'c4', attachments: [MISSING], question: '缺件档：文件已迁走' },
      { id: 'c5', attachments: [IMG_A, IMG_B, VID], question: '三件媒体叠卡' },
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
          activeView.sessionId, 'Nebula', 'askatt-' + c.id, null, c.attachments,
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
    await page.waitForTimeout(1500); // 探针（票据轨首字节）+ 字节腿收敛窗

    /* ══ 卡区读数 ═══════════════════════════════════════════════════════ */
    const readCards = () => page.evaluate(() => {
      const rect = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
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
        const chips = list ? [...list.querySelectorAll('.att-file.ask-att-file')] : [];
        const names = chips.map((c) => c.querySelector('.att-file-name'));
        const tags = wrap ? [...wrap.querySelectorAll('.ds-att-origin')] : [];
        const head = wrap ? wrap.querySelector('.ask-attachments-head') : null;
        const foldBtn = wrap ? wrap.querySelector('.ask-att-fold-btn') : null;
        const flat = wrap ? wrap.querySelector('.pop-media-flat') : null;
        const stack = wrap ? wrap.querySelector('.ds-media-stack') : null;
        const stackView = stack ? stack.querySelector('.ds-media-stack__view') : null;
        const stackImg = stackView ? stackView.querySelector('img') : null;
        const flatImg = flat ? flat.querySelector('img') : null;
        const thumbs = list ? [...list.querySelectorAll('.att-thumb')] : [];
        out[id] = {
          boxH: rect(box) && rect(box).h,
          listW: list ? rect(list).w : null,
          listX: list ? rect(list).x : null,
          listOverflowX: list ? list.scrollWidth - list.clientWidth : null,
          thumbCount: thumbs.length,
          thumbBox: thumbs[0] ? rect(thumbs[0]) : null,
          chips: chips.length,
          visibleChips: chips.filter(visible).length,
          foldedChips: wrap ? wrap.querySelectorAll('.ask-att-folded').length : 0,
          chipBoxes: chips.filter(visible).map((c) => rect(c)),
          nameFontPx: names[0] ? parseFloat(getComputedStyle(names[0]).fontSize) : null,
          nameTextOverflow: names[0] ? getComputedStyle(names[0]).textOverflow : null,
          nameMask: names[0] ? (getComputedStyle(names[0]).maskImage || getComputedStyle(names[0]).webkitMaskImage || 'none') : null,
          originTags: tags.length,
          originTexts: tags.map((t) => t.textContent),
          originInHead: tags.length === 1 && !!head && head.contains(tags[0]),
          foldBtn: !!foldBtn,
          foldText: foldBtn ? foldBtn.textContent : null,
          foldAria: foldBtn ? foldBtn.getAttribute('aria-expanded') : null,
          unavailable: wrap ? wrap.querySelectorAll('.ask-att-unavailable').length : 0,
          reasonTexts: wrap ? [...wrap.querySelectorAll('.ask-att-reason')].map((n) => n.textContent) : [],
          retryBtns: wrap ? wrap.querySelectorAll('.ask-att-retry').length : 0,
          flat: !!flat, flatImg: rect(flatImg),
          stack: !!stack,
          pillCount: stack ? stack.querySelectorAll('.ds-media-stack__pill').length : 0,
          stackViewBox: rect(stackView),
          stackImg: rect(stackImg),
        };
      }
      return out;
    });

    const r1 = await readCards();
    readings.cases = r1;
    const c1 = r1.c1 || {};
    const c2 = r1.c2 || {};
    const c3 = r1.c3 || {};
    const c4 = r1.c4 || {};
    const c5 = r1.c5 || {};

    /* ══ b 腿：卡区图片档 = 输入框同款小缩略图（attachment-thumb-unify 改判）══
       作者令 2026-11-05 12:57「附件这里添加的图片文件等应该用我们输入框同款的小
       图标/缩略图」⇒ 旧判据「缩略帧宽 ≥ max(内容宽×0.2, 96px)」作废：卡区图片档
       不再是 220px 卡语言媒体卡，而是 `.att-thumb` 28px 档（与输入条逐值同盒）。 */
    check('b1 c1 图片档 = .att-thumb 小缩略图（无 .pop-media-flat）',
      c1.thumbCount === 1 && c1.flat === false, JSON.stringify({ thumbs: c1.thumbCount, flat: c1.flat }));
    check('b2 c1 缩略帧 = 28px 档且不越出附件条',
      !!c1.thumbBox && c1.thumbBox.w <= 28.5 && c1.thumbBox.h <= 28.5 && c1.thumbBox.x + c1.thumbBox.w <= (c1.listW || 0) + (c1.listX || 0) + 1,
      JSON.stringify({ thumb: c1.thumbBox, listW: c1.listW, listX: c1.listX }));
    check('b3 c5 三件媒体同条带（零叠卡 .ds-media-stack）',
      c5.stack === false && c5.thumbCount >= 1, JSON.stringify({ stack: c5.stack, thumbs: c5.thumbCount }));
    check('b4 c5 附件条零横向溢出', c5.listOverflowX <= 0, `over=${c5.listOverflowX}`);

    /* ══ d 腿：来源标签（attachment-thumb-unify 改判：收拢到标题行一处）══════ */
    check('d1 c1 来源标签一处（标题行，不再每 chip notch）', c1.originTags === 1, `tags=${c1.originTags}`);
    check('d2 来源标签文本含 AskUser 标识',
      !!c1.originTexts && c1.originTexts.length === 1 && /AskUser/.test(c1.originTexts[0]),
      JSON.stringify(c1.originTexts));
    check('d3 来源标签在附件区标题行内（不压附件 chip/缩略帧）',
      c1.originInHead === true, JSON.stringify({ inHead: c1.originInHead }));

    /* ══ f 腿：卡片减负（不随件数线性膨胀）═════════════════════════════ */
    check('f1 默认只显前 3 枚（4 枚卡：可见 3 / 折叠 1）',
      c2.visibleChips === FOLD_AT && c2.foldedChips === 1, JSON.stringify({ vis: c2.visibleChips, folded: c2.foldedChips }));
    check('f2 9 枚卡同样只显前 3 枚', c3.visibleChips === FOLD_AT && c3.foldedChips === 6,
      JSON.stringify({ vis: c3.visibleChips, folded: c3.foldedChips }));
    check('f3 折叠键文本含总件数（共 9 个）', !!c3.foldText && /9/.test(c3.foldText), JSON.stringify(c3.foldText));
    // 部分折叠（可见 3）是前提：旧形态「>3 整列 display:none」也「高度不涨」，那不算减负。
    check('f4 件数 4 → 9 卡身高度不涨（Δ≤6px，非线性膨胀）且两卡都只显前 3',
      !!c2.boxH && !!c3.boxH && Math.abs(c3.boxH - c2.boxH) <= 6
      && c2.visibleChips === FOLD_AT && c3.visibleChips === FOLD_AT,
      `h(4)=${c2.boxH} h(9)=${c3.boxH} Δ=${Math.abs((c3.boxH || 0) - (c2.boxH || 0)).toFixed(1)}`);
    // 展开：9 枚全可见（真控件，不是死按钮）
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c3"]');
      const btn = box && box.querySelector('.ask-att-fold-btn');
      if (btn) btn.click();
    });
    await page.waitForTimeout(260);
    const c3x = (await readCards()).c3 || {};
    readings.c3expanded = c3x;
    check('f5 展开后 9 枚全可见且 aria-expanded=true',
      c3x.visibleChips === 9 && c3x.foldAria === 'true',
      JSON.stringify({ vis: c3x.visibleChips, aria: c3x.foldAria }));

    /* ══ ⑤a 腿：不可用态给原因 + 可执行动作 ══════════════════════════ */
    check('⑤a1 缺件卡落不可用态', c4.unavailable >= 1, JSON.stringify({ unavailable: c4.unavailable }));
    check('⑤a2 原因文本非「不可用」裸词（给成因档）',
      !!c4.reasonTexts && c4.reasonTexts.length >= 1
      && c4.reasonTexts.every((t) => t && t.trim() !== '不可用' && /不存在|权限|网络|不支持/.test(t)),
      JSON.stringify(c4.reasonTexts));
    check('⑤a3 可执行动作在位（重试下载键）', c4.retryBtns >= 1, `retry=${c4.retryBtns}`);

    /* ══ h 腿：手机 HIG ═════════════════════════════════════════════════ */
    check('h1 文件 chip 热区高 ≥44px', !!c1.chipBoxes && c1.chipBoxes.length > 0
      && c1.chipBoxes.every((b) => b.h >= 44 - 0.5), JSON.stringify(c1.chipBoxes));
    check('h2 文件名有效字号 ≥13px', !!c1.nameFontPx && c1.nameFontPx >= 13 - 0.05, `font=${c1.nameFontPx}`);
    check('h3 长名截断走可见省略号（attachment-thumb-unify：渐隐撤除，作者令 2026-11-05 09:28 同判）',
      !!c1.nameTextOverflow && c1.nameTextOverflow === 'ellipsis',
      JSON.stringify({ overflow: c1.nameTextOverflow, mask: c1.nameMask }));
    check('h4 来源标签可读字 ≥13px',
      await page.evaluate(() => {
        const tag = document.querySelector('.option-box[data-ask-case="c1"] .ds-att-origin');
        return tag ? parseFloat(getComputedStyle(tag).fontSize) >= 13 - 0.05 : false;
      }));
    const tagStyle = await page.evaluate(() => {
      const tag = document.querySelector('.option-box[data-ask-case="c1"] .ds-att-origin');
      if (!tag) return null;
      const cs = getComputedStyle(tag);
      const b = tag.getBoundingClientRect();
      const head = tag.closest('.ask-attachments-head');
      return {
        mask: cs.maskImage || cs.webkitMaskImage || 'none',
        overflow: cs.textOverflow,
        pointerEvents: cs.pointerEvents,
        tagW: +b.width.toFixed(1),
        inHead: !!head && head.contains(tag),
        headBottom: head ? +head.getBoundingClientRect().bottom.toFixed(1) : null,
        listTop: head && head.parentElement && head.parentElement.querySelector('.ask-attachments-list')
          ? +head.parentElement.querySelector('.ask-attachments-list').getBoundingClientRect().top.toFixed(1) : null,
      };
    });
    readings.originTagStyle = tagStyle;
    check('h5 来源标签在标题行（不压附件）+ 超长省略号 + 不吃点击',
      !!tagStyle && tagStyle.inHead === true && tagStyle.overflow === 'ellipsis'
      && tagStyle.pointerEvents === 'none' && tagStyle.headBottom <= (tagStyle.listTop ?? 1e9) + 1,
      JSON.stringify(tagStyle));

    await page.screenshot({ path: join(OUT, 'cards-after.png'), fullPage: false });
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c1"]');
      if (box) box.scrollIntoView({ block: 'center' });
    });
    await page.waitForTimeout(200);
    await page.screenshot({ path: join(OUT, 'card-c1-closeup.png') });
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c5"]');
      if (box) box.scrollIntoView({ block: 'center' });
    });
    await page.waitForTimeout(200);
    await page.screenshot({ path: join(OUT, 'card-c5-stack.png') });

    /* ══ c 腿：点击 → 预览层真出现 / 文件真取回 ═══════════════════════ */
    const lightboxState = () => page.evaluate(() => {
      const el = document.querySelector('.nf-lightbox');
      if (!el) return { present: false };
      const cs = getComputedStyle(el);
      return {
        present: true, cls: el.className, display: cs.display,
        visible: cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0,
        mediaChild: !!el.querySelector('.nf-lightbox-media'),
        name: el.querySelector('.nf-lightbox-name') ? el.querySelector('.nf-lightbox-name').textContent : null,
      };
    });
    const closeLightbox = async () => {
      await page.evaluate(() => { const el = document.querySelector('.nf-lightbox'); if (el) el.remove(); });
      await page.waitForTimeout(120);
    };

    // c1 缩略帧点击（attachment-thumb-unify：图片档 = .att-thumb 小缩略图）
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c1"]');
      const thumb = box && box.querySelector('.ask-att-thumb');
      if (thumb) thumb.click();
    });
    await page.waitForTimeout(420);
    const lb1 = await lightboxState();
    readings.lightboxFlat = lb1;
    check('c1 点击小缩略图 → 预览层 DOM 出现且可见（display:flex）',
      lb1.present === true && lb1.visible === true && /nf-lightbox--media/.test(lb1.cls || ''),
      JSON.stringify(lb1));
    await page.screenshot({ path: join(OUT, 'preview-image.png') });
    await closeLightbox();

    // c5 多件媒体档点击（第 1 枚缩略帧）
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c5"]');
      const thumb = box && box.querySelector('.ask-att-thumb');
      if (thumb) thumb.click();
    });
    await page.waitForTimeout(420);
    const lb2 = await lightboxState();
    readings.lightboxStack = lb2;
    check('c2 点击多件档缩略帧 → 预览层 DOM 出现且可见', lb2.present === true && lb2.visible === true, JSON.stringify(lb2));
    await page.screenshot({ path: join(OUT, 'preview-stack.png') });
    await closeLightbox();

    // c1 文件 chip 点击（下载腿）
    const hitsBefore = nfFileHits.length;
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c1"]');
      const chip = box && box.querySelector('.att-file.ask-att-file.is-clickable');
      if (chip) chip.click();
    });
    await page.waitForTimeout(900);
    const hitsAfter = nfFileHits.length;
    readings.fileClick = { before: hitsBefore, after: hitsAfter, last: nfFileHits.slice(-2) };
    check('c3 点击文件 chip → /api/nf-file 取回次数递增（不得无响应）',
      hitsAfter > hitsBefore, JSON.stringify({ before: hitsBefore, after: hitsAfter }));

    // 降级卡的重试动作也真发请求（动作不是死键）
    const hitsBeforeRetry = nfFileHits.length;
    await page.evaluate(() => {
      const box = document.querySelector('.option-box[data-ask-case="c4"]');
      const btn = box && box.querySelector('.ask-att-retry');
      if (btn) btn.click();
    });
    await page.waitForTimeout(800);
    readings.retryClick = { before: hitsBeforeRetry, after: nfFileHits.length };
    check('⑤a4 重试下载键真发取回请求（非死键）', nfFileHits.length > hitsBeforeRetry,
      JSON.stringify(readings.retryClick));

    /* ══ z 腿 ═══════════════════════════════════════════════════════════ */
    const errs = (readings.pageErrors || []).filter((t) => !/favicon/i.test(t));
    check('z1 零页面错误', errs.length === 0, errs.slice(0, 3).join(' | '));

    writeFileSync(join(OUT, 'readings.json'), JSON.stringify(readings, null, 2));
    console.log(`\n[askuser-attach-unify] phase=${readings.phase} web=${WEB}`);
    console.log(`[askuser-attach-unify] ${readings.checks.length - failures.length}/${readings.checks.length} PASS → ${OUT}`);
    if (failures.length) throw new Error(`askuser-attach-unify assertions failed (${failures.length}): ${failures.join(' | ')}`);
  });
});
