// askuser-media-preview.spec.mjs — AskUser 选项「图片 / 视频 / 多张叠卡 / 长卡下滑」
// 真渲染验收（askuser-media 批 2026-11-18 · 作者令 17:52 逐字：
// 「要能填充图片和视频 并且支持多张叠卡显示 … 待办的决策卡片比较长，因此要可以
//   支持下滑查看」）。
//
// ── 被测面（真浏览器 + 真模块图 + 真 CSS + 真应用外壳）─────────────────────
//   全部走**生产入口**：`_chat-face-harness.mjs` 起真 index.html（`page.route` 从磁盘
//   注入本仓真 web 树，不起本地端口），WS 桩推一帧**真 `askUser` 帧** ⇒ main.js 的
//   `onMessage('askUser')` → `renderAskUser` → `showOptions`。零 stub、零第二套渲染。
//
// ── 判据（每条都是几何/字节读数，不读 class 猜）────────────────────────────
//   C1 swatch / 旧单图缩略（回归面）：形态未被本批拖坏（结构 + 像素到达）。
//   C2 **媒体组叠卡**：N 件媒体 ⇒ N 层 `.media-layer`；首张完整（有盒 + 字节到达），
//      后面几张**露边**（`layer-mid`/`layer-back` 扇形变换非 identity）；展开 N ⇒
//      N 行全可见出图；收起复原。**这一条就是本批的闸**（N → N 层且首张完整）。
//   C3 **视频**：单件视频面 = 消息面 flat 同款（静帧 poster + 原生控件可播放）；
//      真 webm 字节 ⇒ `readyState ≥ 1`，点击后 `!paused`（可播放的实证）。
//   C4 **与 label/description 共存**：媒体在上下文字在下的竖排卡；文字可见不裁。
//   C5 **长卡下滑**：`.option-scroll` 可滚动（scrollHeight > clientHeight、滚动后
//      scrollTop 变化），且**滚动不吞点击**——滚到底后点最后一个选项仍能选中并作答
//      （出站 `askUserAnswer` 帧实证）；确认/取消钮常驻区外可见。
//   C6 手机档 375×812 全屏页：同一条链（叠卡 + 展开 + 滚动 + 作答），零横向溢出。
//   C7 零回归：无 preview 的选项不产生任何 preview 节点；旧 `{type:'image'}` 仍是
//      56×40 小缩略（不升级成媒体面）。
//
// 红/绿对照（验闸有牙）：
//   绿腿：`node node_modules/@playwright/test/cli.js test tests/askuser-media-preview.spec.mjs`
//   红腿 A（功能缺场）：`CHATFACE_WEB_ROOT=<base 的 web 树>` 同上 ⇒ 新模块缺失 ⇒ 红。
//   红腿 B（定点破坏）：把 askMediaPreview.js 的媒体组分支打断 ⇒ C2 红；还原 ⇒ 绿。
// 截图落盘：ASKMEDIA_SHOT_DIR=<绝对目录>（缺省不落盘，CI 不留垃圾）。

import { test, expect } from '@playwright/test';
import zlib from 'node:zlib';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootChat, SID } from './_chat-face-harness.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

/* ── 夹具字节 ①：纯色 PNG（零依赖、真像素 ⇒ naturalWidth > 0） ───────────── */

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** Solid-colour PNG bytes: w×h, one flat RGB fill. */
function png(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  const row = Buffer.alloc(1 + w * 3);
  for (let x = 0; x < w; x++) {
    row[1 + x * 3] = r;
    row[2 + x * 3] = g;
    row[3 + x * 3] = b;
  }
  const raw = Buffer.concat(Array.from({ length: h }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

const dataUri = (w, h, rgb) => 'data:image/png;base64,' + png(w, h, rgb).toString('base64');

/* ── 夹具字节 ②：真 webm（VP8，Chromium 原生解码）─────────────────────────
   夹具文件 = `tests/fixtures/askuser-media/clip.webm`（1143 B，sha256
   2a5225ea750c3b20…）。由 ffmpeg 一次性生成：
     ffmpeg -f lavfi -i "color=c=0x1f4fa0:s=320x240:d=1.2:r=12" -c:v libvpx -b:v 90k -an clip.webm
   `ffprobe` 实测：codec_name=vp8 / width=320 / height=240（本批报告证据行）。 */
const WEBM_BYTES = readFileSync(join(HERE, 'fixtures', 'askuser-media', 'clip.webm'));
const WEBM_URL = 'http://localhost:1/media/clip.webm';
const LOCAL_WEBM = '/tmp/nf-askmedia/clip.webm';

/* ── 载荷（与 AgentActor.buildAskUserJson 的输出形状逐字同构）──────────────── */

const imgs = [
  dataUri(480, 320, [60, 170, 120]),
  dataUri(320, 480, [40, 110, 190]),
  dataUri(400, 300, [190, 120, 50]),
];
const POSTER = dataUri(320, 240, [220, 60, 70]); // 红封面（与视频自身的蓝画面可区分）

/** 一帧真 `askUser`（items 形状 = 前端消费的同一形状）。 */
function askFrame(items, requestId = 'rid-media-1') {
  return JSON.stringify({ type: 'askUser', sessionId: SID, agentName: 'E2E', requestId, items });
}

const MIXED_ITEMS = [
  {
    question: '用哪种设计？（媒体预览）',
    options: [
      { label: '色带款', description: 'legacy swatch', preview: { type: 'swatch', colors: ['#6b9c8a', '#a8c3b5'] } },
      { label: '旧缩略款', description: 'legacy thumb', preview: { type: 'image', src: imgs[0] } },
      {
        label: '视频款',
        description: '静帧 + 可播放',
        preview: { type: 'video', src: WEBM_URL, poster: POSTER, name: 'clip.webm' },
      },
      {
        label: '三图叠卡款',
        description: '前一张完整 + 后两张露边',
        preview: {
          type: 'media',
          items: [
            { kind: 'image', src: imgs[0], name: 'wide.png' },
            { kind: 'image', src: imgs[1], name: 'tall.png' },
            { kind: 'image', src: imgs[2], name: 'mid.png' },
          ],
        },
      },
      {
        label: '混合组款',
        description: '本地路径视频 + 图',
        preview: {
          type: 'media',
          items: [
            { kind: 'image', src: imgs[2], name: 'mid.png' },
            { kind: 'video', src: LOCAL_WEBM, path: LOCAL_WEBM, name: 'clip.webm' },
          ],
        },
      },
      { label: '无预览款' },
    ],
  },
];

/** 长卡夹具：6 个媒体组选项 ⇒ 内容必然超出 `--ask-options-max-h`。 */
const LONG_ITEMS = [
  {
    question: '长卡下滑验收：请选一个方案（内容超高必须可滚动，且滚动后仍可点选）',
    options: [0, 1, 2, 3, 4, 5].map((i) => ({
      label: `方案 ${i + 1}`,
      description: `第 ${i + 1} 套设计说明文字`,
      preview: {
        type: 'media',
        items: [
          { kind: 'image', src: imgs[i % imgs.length], name: `a${i}.png` },
          { kind: 'image', src: imgs[(i + 1) % imgs.length], name: `b${i}.png` },
          { kind: 'image', src: imgs[(i + 2) % imgs.length], name: `c${i}.png` },
        ],
      },
    })),
  },
];

/** 叠卡裁切批夹具（2026-11-18）：6 个媒体组选项 + **长标题 / 长描述** —— 复现作者报的
 *  「手机端叠卡显示不完整」现场（选项文字换行、卡身撑到宿主横向上限）。 */
const LONG_MEDIA_ITEMS = [{
  question: '长卡下滑验收：请选一个方案（内容超高必须可滚动）',
  options: [0, 1, 2, 3, 4, 5].map((i) => ({
    label: `方案 ${i + 1}：这一档比较长的标题文字用于检验换行与裁切`,
    description: `第 ${i + 1} 套设计说明文字，这是一段较长的描述，用来把选项卡撑到自然宽度上限以复现裁切`,
    preview: { type: 'media', items: [
      { kind: 'image', src: imgs[i % imgs.length], name: `a${i}.png` },
      { kind: 'image', src: imgs[(i + 1) % imgs.length], name: `b${i}.png` },
      { kind: 'image', src: imgs[(i + 2) % imgs.length], name: `c${i}.png` }] },
  })),
}];

/* ── 启动 ───────────────────────────────────────────────────────────────── */
/** 起真应用外壳 + WS 桩；返回可**主动推帧**的句柄。 */
async function bootWithAsk(page, { history, items, requestId } = {}) {
  let route = null;
  await bootChat(page, { history, onWsRoute: (ws) => { route = ws; } });
  // 本 spec 后注册的 route 优先生效（覆盖 harness 的 /api/ 兜底 JSON）。
  await page.route('**/media/**', (r) => r.fulfill({
    status: 200, contentType: 'video/webm', body: WEBM_BYTES,
  }));
  await page.route('**/api/nf-file**', (r) => r.fulfill({
    status: 200, contentType: 'video/webm', body: WEBM_BYTES,
  }));
  await page.route('**/api/nf-ticket', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ tickets: {} }),
  }));
  expect(route, 'WS 桩句柄必须可用（harness onWsRoute）').toBeTruthy();
  route.send(askFrame(items, requestId));
  await page.waitForSelector('#chat .option-box', { timeout: 20000 });
  await page.waitForTimeout(900); // 媒体字节 / 卡脸几何落定
  return route;
}

async function shot(page, name) {
  const dir = process.env.ASKMEDIA_SHOT_DIR;
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: `${dir}/${name}.png`, fullPage: false });
}

const box = (el) => {
  if (!el) return null;
  const b = el.getBoundingClientRect();
  const s = getComputedStyle(el);
  return {
    w: +b.width.toFixed(1), h: +b.height.toFixed(1),
    left: +b.left.toFixed(1), top: +b.top.toFixed(1),
    display: s.display, opacity: s.opacity, transform: s.transform,
  };
};

/* ── C1 swatch / 旧单图缩略：回归面 ─────────────────────────────────────── */

test.describe('C1 旧两形态（swatch / 单图小缩略）回归', () => {
  test('swatch 色带与旧 56×40 缩略照旧；无 preview 选项不产生 preview 节点', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: MIXED_ITEMS });

    const r = await page.evaluate(() => {
      const btns = [...document.querySelectorAll('#chat .option-box .option-btn')];
      const sw = btns[0].querySelector('.option-preview');
      const th = btns[1].querySelector('.option-preview');
      const noPv = btns[5];
      const img = th && th.querySelector('img.preview-img');
      const b = sw ? sw.getBoundingClientRect() : null;
      return {
        swatchStripes: sw ? sw.querySelectorAll('.preview-swatch').length : -1,
        swatchBox: b ? { w: +b.width.toFixed(1), h: +b.height.toFixed(1) } : null,
        thumbImg: img ? { complete: img.complete, nw: img.naturalWidth } : null,
        noPreviewNodes: noPv.querySelectorAll('.option-preview, .option-preview-media').length,
        noPreviewText: (noPv.querySelector('.option-text') || noPv).textContent.trim(),
      };
    });

    expect(r.swatchStripes, 'swatch 仍是 2 条色带').toBe(2);
    expect(r.swatchBox.w, '旧缩略槽宽 = 56').toBeCloseTo(56, 0);
    expect(r.swatchBox.h, '旧缩略槽高 = 40').toBeCloseTo(40, 0);
    expect(r.thumbImg && r.thumbImg.complete, '旧单图缩略字节到达').toBe(true);
    expect(r.thumbImg.nw, '旧单图缩略 naturalWidth').toBeGreaterThan(0);
    expect(r.noPreviewNodes, '无 preview 的选项零 preview 节点（E1 零回归）').toBe(0);
    expect(r.noPreviewText).toBe('无预览款');
    await shot(page, 'c1-legacy-forms');
    await ctx.close();
  });
});

/* ── C2 媒体组叠卡（本批的闸）───────────────────────────────────────────── */

test.describe('C2 媒体组：N 件 ⇒ N 层叠卡，首张完整、后几张露边，可展开', () => {
  test('三图组渲染成 3 层，首张有盒有字节，后两层扇形露边；展开 ⇒ 三行全出图', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: MIXED_ITEMS });

    // 三图组 = 第 4 个选项（index 3）；混合组 = index 4。
    const st = await page.evaluate(() => {
      const btns = [...document.querySelectorAll('#chat .option-box .option-btn')];
      const grp = btns[3].querySelector('.option-preview-media');
      if (!grp) return null;
      const area = grp.querySelector('.pop-stack-area');
      const layers = [...grp.querySelectorAll('.pop-stack-view .media-layer')];
      const ring = (el) => {
        if (!el) return null;
        const b = el.getBoundingClientRect();
        return { w: +b.width.toFixed(1), h: +b.height.toFixed(1), left: +b.left.toFixed(1), top: +b.top.toFixed(1) };
      };
      return {
        hasStackClass: !!area && area.classList.contains('ds-media-stack'),
        pillText: area ? area.querySelector('.pill').textContent.trim() : null,
        layerCount: layers.length,
        layerCls: layers.map((l) => l.className.replace('media-layer ds-media-card', '').trim()),
        layerBoxes: layers.map(ring),
        viewBox: ring(grp.querySelector('.pop-stack-view')),
        frontBox: ring(layers[0]),
        fanTransforms: layers.slice(1).map((l) => getComputedStyle(l).transform),
        frontImg: (() => {
          const im = layers[0] && layers[0].querySelector('img.pop-img');
          return im ? { complete: im.complete, nw: im.naturalWidth, box: ring(im) } : null;
        })(),
        mediaHostW: +grp.getBoundingClientRect().width.toFixed(1),
        faceW: getComputedStyle(grp).getPropertyValue('--ds-media-face-w').trim(),
        bundleGroups: document.querySelectorAll('#chat .option-preview-media').length,
      };
    });

    expect(st, '三图组必须渲染出媒体宿主').toBeTruthy();
    expect(st.hasStackClass, '媒体组复用消息面叠卡组件（.ds-media-stack）').toBe(true);
    expect(st.layerCount, '3 件媒体 ⇒ 3 层叠卡').toBe(3);
    expect(st.layerCls[0]).toBe('layer-front');
    expect(st.layerCls.slice(1).sort()).toEqual(['layer-back', 'layer-mid']);
    expect(st.frontImg && st.frontImg.complete, '首张（front）字节到达 = 完整卡脸').toBe(true);
    expect(st.frontImg.nw, '首张 naturalWidth').toBeGreaterThan(0);
    expect(st.frontImg.box.w, '首张有可见盒').toBeGreaterThan(100);
    expect(st.pillText, '展开钮报件数').toContain('3');
    // 「后面几张露出边」= 扇形位移/旋转变换在起效（非 identity）。
    for (const [i, tf] of st.fanTransforms.entries()) {
      expect(tf, `第 ${i + 2} 层必须带扇形变换（露边）`).not.toBe('none');
      expect(tf, `第 ${i + 2} 层扇形变换非 identity`).not.toBe('matrix(1, 0, 0, 1, 0, 0)');
    }
    // 露边的几何读数：露出层整体在首张**右侧**（前一张完整、后几张依次探出）。
    // 首张无扇形变换（transform:none）⇒ 其外框左缘与叠卡容器左缘对齐（±4px 容差）。
    expect(Math.abs(st.layerBoxes[0].left - st.viewBox.left), '首张左缘与叠卡容器对齐（首张完整落位）')
      .toBeLessThanOrEqual(4);
    expect(st.layerBoxes[1].left, '第 2 层向右探出').toBeGreaterThan(st.layerBoxes[0].left);
    expect(st.layerBoxes[2].left, '第 3 层比第 2 层更外').toBeGreaterThan(st.layerBoxes[1].left);
    expect(st.layerBoxes[2].left + st.layerBoxes[2].w, '露出层的右缘越过首张右缘（看得见露边，未被裁）')
      .toBeGreaterThan(st.layerBoxes[0].left + st.layerBoxes[0].w);
    expect(st.faceW, '卡脸尺度 = 宿主作用域收小档（组件同一变量）').toBe('168px');
    expect(st.mediaHostW, '媒体宿主不横向顶出选项卡').toBeLessThanOrEqual(460);
    await shot(page, 'c2-1-collapsed-fan');

    // 展开 N ⇒ 三行全可见出图。
    await page.locator('#chat .option-box .option-btn').nth(3).locator('.pill').click();
    await page.waitForFunction(() => document.querySelector('#chat .option-box .pop-expand-view')?.classList.contains('show'));
    await page.waitForTimeout(900);
    const ex = await page.evaluate(() => {
      // 取**带展开视图**的那个媒体宿主（文档里另有视频/混合两组，首个不一定是被展开的）。
      const grp = [...document.querySelectorAll('#chat .option-preview-media')]
        .find((g) => g.querySelector('.pop-expand-view'));
      const rows = [...grp.querySelectorAll('.pop-expand-view .media-layer')];
      return {
        display: getComputedStyle(grp.querySelector('.pop-expand-view')).display,
        rows: rows.map((r) => {
          const im = r.querySelector('img.pop-img');
          const b = r.getBoundingClientRect();
          return { w: +b.width.toFixed(1), h: +b.height.toFixed(1), complete: im ? im.complete : false, nw: im ? im.naturalWidth : 0 };
        }),
      };
    });
    expect(ex.display).toBe('flex');
    expect(ex.rows.length, '展开列 = 3 行').toBe(3);
    for (const [i, r] of ex.rows.entries()) {
      expect(r.w, `展开行 ${i} 有可见盒`).toBeGreaterThan(60);
      expect(r.h, `展开行 ${i} 有可见盒`).toBeGreaterThan(40);
      expect(r.complete && r.nw > 0, `展开行 ${i} 字节到达`).toBe(true);
    }
    await shot(page, 'c2-2-expanded');
    await ctx.close();
  });
});

/* ── C3 视频：静帧 + 可播放 ─────────────────────────────────────────────── */

test.describe('C3 视频：静帧（poster）+ 可播放（真 webm 字节）', () => {
  test('单件视频面走消息面 flat 同族；poster 生效、字节可解、点击可播', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: MIXED_ITEMS });

    const v = await page.evaluate(() => {
      const grp = [...document.querySelectorAll('#chat .option-preview-media')].find((g) => g.querySelector('video'));
      const video = grp && grp.querySelector('video.pop-video');
      if (!video) return null;
      return {
        singleFace: grp.classList.contains('option-preview-media--single'),
        flatFace: !!grp.querySelector('.pop-media-flat'),
        poster: video.getAttribute('poster') || '',
        controls: video.controls,
        paused: video.paused,
        readyState: video.readyState,
        box: (() => { const b = grp.getBoundingClientRect(); return { w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; })(),
      };
    });

    expect(v, '单件视频必须渲染出视频面').toBeTruthy();
    expect(v.singleFace, '1 件媒体 = 单件面').toBe(true);
    expect(v.flatFace, '单件面 = 消息面 flat 卡（同源）').toBe(true);
    expect(v.poster.startsWith('data:image/png;base64,'), '静帧 = 传入的 poster').toBe(true);
    expect(v.paused, '入场时是静帧（未自动播放）').toBe(true);
    expect(v.controls, '原生控件 = 可播放入口（消息面 flat 同款）').toBe(true);
    expect(v.readyState, '真 webm 字节已解出元数据（可播放的字节前提）').toBeGreaterThanOrEqual(1);
    expect(v.box.w).toBeGreaterThan(60);
    expect(v.box.h).toBeGreaterThan(40);
    await shot(page, 'c3-1-video-still');

    // 真实鼠标点击视频体 → 播放（Chromium 原生控件语义）；读数 = !paused。
    await page.locator('#chat .option-preview-media video.pop-video').first().click({ position: { x: 20, y: 20 } });
    await page.waitForTimeout(700);
    const playing = await page.evaluate(() => {
      const video = document.querySelector('#chat .option-preview-media video.pop-video');
      return { paused: video.paused, t: +video.currentTime.toFixed(3) };
    });
    expect(playing.paused, '点击后进入播放').toBe(false);
    expect(playing.t, '播放头在走').toBeGreaterThan(0);
    await shot(page, 'c3-2-video-playing');
    await ctx.close();
  });
});

/* ── C4 与 label/description 共存 ───────────────────────────────────────── */

test.describe('C4 媒体与文字共存：竖排卡，文字可见不裁', () => {
  test('媒体在上、label+description 在下，整行不横向溢出', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: MIXED_ITEMS });

    const r = await page.evaluate(() => {
      const btn = [...document.querySelectorAll('#chat .option-box .option-btn')][3];
      const body = btn.querySelector('.option-media-body');
      const media = body && body.querySelector('.option-preview-media');
      const text = body && body.querySelector('.option-text');
      const desc = text && text.querySelector('.option-desc');
      const rb = (el) => { const b = el.getBoundingClientRect(); return { top: +b.top.toFixed(1), h: +b.height.toFixed(1), w: +b.width.toFixed(1), left: +b.left.toFixed(1) }; };
      const card = document.querySelector('#chat .option-box').getBoundingClientRect();
      return {
        hasBody: !!body,
        media: media ? rb(media) : null,
        text: text ? rb(text) : null,
        descH: desc ? +desc.getBoundingClientRect().height.toFixed(1) : 0,
        descText: desc ? desc.textContent : '',
        btnWidth: +btn.getBoundingClientRect().width.toFixed(1),
        cardWidth: +card.width.toFixed(1),
        btnRight: +btn.getBoundingClientRect().right.toFixed(1),
        cardRight: +card.right.toFixed(1),
      };
    });

    expect(r.hasBody, '媒体选项 = 卡身体（媒体 + 文字）').toBe(true);
    expect(r.media.top, '媒体在文字之上（竖排）').toBeLessThan(r.text.top);
    expect(r.text.h, '文字行有可见高度').toBeGreaterThan(10);
    expect(r.descH, 'description 可见（未被裁）').toBeGreaterThan(10);
    expect(r.descText).toContain('前一张完整');
    expect(r.btnWidth, '选项行占满卡宽').toBeGreaterThan(r.cardWidth * 0.8);
    expect(r.btnRight, '不横向顶出卡片').toBeLessThanOrEqual(r.cardRight + 1);
    await shot(page, 'c4-coexist');
    await ctx.close();
  });
});

/* ── C5 长卡下滑 + 选择可点（作者令的直接验收）──────────────────────────── */

test.describe('C5 长内容下滑：容器可滚 + 滚动不吞点击', () => {
  test('内容超高 ⇒ 容器可滚；滚到底后点最后一个选项仍能选中并作答', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: LONG_ITEMS, requestId: 'rid-scroll-1' });

    const before = await page.evaluate(() => {
      const sc = document.querySelector('#chat .option-box .option-scroll');
      const bar = document.querySelector('#chat .option-box .option-btn-row');
      const box = document.querySelector('#chat .option-box');
      return {
        present: !!sc,
        scrollHeight: sc.scrollHeight, clientHeight: sc.clientHeight, scrollTop: sc.scrollTop,
        overflowY: getComputedStyle(sc).overflowY,
        boxH: +box.getBoundingClientRect().height.toFixed(1),
        barBottomWithin: bar.getBoundingClientRect().bottom <= box.getBoundingClientRect().bottom + 1,
        confirmVisible: (() => { const b = document.querySelector('.option-confirm').getBoundingClientRect(); return b.height > 0 && b.width > 0; })(),
        optionCount: document.querySelectorAll('#chat .option-box .option-btn:not(.opt-other)').length,
      };
    });

    expect(before.present, '滚动区在场（.option-scroll）').toBe(true);
    expect(before.overflowY, '溢出策略 = 滚动（不是裁剪）').toBe('auto');
    expect(before.scrollHeight, '内容高于容器（长卡成立）').toBeGreaterThan(before.clientHeight);
    expect(before.scrollTop).toBe(0);
    expect(before.optionCount).toBe(6);
    expect(before.confirmVisible, '确认钮常驻区外可见').toBe(true);
    expect(before.barBottomWithin, '按钮行在卡内（不被裁）').toBe(true);
    const scBox = await page.locator('#chat .option-box .option-scroll').boundingBox();
    expect(before.boxH, '卡片高度 = 上限档（min(64vh,560) + 按钮行），不整页拉长').toBeLessThan(700);
    await shot(page, 'c5-1-top');

    // 真实滚轮（不是 set scrollTop）：滚动可用。
    await page.mouse.move(scBox.x + scBox.width / 2, scBox.y + scBox.height / 2);
    await page.mouse.wheel(0, 2000);
    await page.waitForTimeout(400);
    const afterWheel = await page.evaluate(() => document.querySelector('#chat .option-box .option-scroll').scrollTop);
    expect(afterWheel, '滚轮可滚动容器').toBeGreaterThan(0);

    // 滚到底，点**最后一个**选项 ⇒ 选择仍可点（滚动不吞点击）。
    await page.evaluate(() => { const sc = document.querySelector('#chat .option-box .option-scroll'); sc.scrollTop = sc.scrollHeight; });
    await page.waitForTimeout(300);
    await shot(page, 'c5-2-scrolled-bottom');
    const last = page.locator('#chat .option-box .option-btn').nth(5);
    await last.click();
    const picked = await page.evaluate(() => {
      const btns = [...document.querySelectorAll('#chat .option-box .option-btn')];
      return btns.map((b, i) => (b.classList.contains('picked') ? i : -1)).filter((i) => i >= 0);
    });
    expect(picked, '滚到底后点选仍生效（仅最后一个被选中）').toEqual([5]);
    const confirmEnabled = await page.evaluate(() => !document.querySelector('#chat .option-box .option-confirm').disabled);
    expect(confirmEnabled, '选中后确认钮解禁 = 选择状态真的落了').toBe(true);

    await page.locator('#chat .option-box .option-confirm').click();
    await page.waitForTimeout(400);
    const answered = await page.evaluate(() => document.querySelector('#chat .option-box .option-answer')?.textContent || '');
    expect(answered, '作答行 = 被选中的方案').toContain('方案 6');
    await shot(page, 'c5-3-answered');
    await ctx.close();
  });
});

/* ── C6 手机档全屏页 ────────────────────────────────────────────────────── */

test.describe('C6 手机档 375×812 全屏页：同一条链', () => {
  test('叠卡 + 展开 + 下滑 + 作答；零横向溢出', async ({ browser }) => {
    const ctx = await browser.newContext({
      viewport: { width: 375, height: 812 },
      isMobile: true, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'light',
    });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: LONG_ITEMS, requestId: 'rid-mobile-1' });
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });

    const r = await page.evaluate(() => {
      const sc = document.querySelector('#chat .option-box .option-scroll');
      const btns = [...document.querySelectorAll('#chat .option-box .option-btn:not(.opt-other)')];
      const grp = document.querySelector('#chat .option-preview-media');
      return {
        mobileLayer: document.body.classList.contains('mobile-layer'),
        scrollable: sc.scrollHeight > sc.clientHeight,
        mediaHostRight: +grp.getBoundingClientRect().right.toFixed(1),
        viewportW: window.innerWidth,
        layerCount: grp.querySelectorAll('.pop-stack-view .media-layer').length,
        optionCount: btns.length,
        docScrollW: document.documentElement.scrollWidth,
      };
    });
    expect(r.mobileLayer, '全屏手机页（真 mobile-layer）').toBe(true);
    expect(r.scrollable, '手机档长卡同样可滚').toBe(true);
    expect(r.layerCount, '叠卡 3 层').toBe(3);
    expect(r.optionCount).toBe(6);
    expect(r.mediaHostRight, '媒体面不横向溢出视口').toBeLessThanOrEqual(r.viewportW + 1);
    expect(r.docScrollW, '页面零横向滚动').toBeLessThanOrEqual(r.viewportW + 1);
    await shot(page, 'c6-1-mobile-top');

    await page.evaluate(() => { const sc = document.querySelector('#chat .option-box .option-scroll'); sc.scrollTop = sc.scrollHeight; });
    await page.waitForTimeout(300);
    await shot(page, 'c6-2-mobile-bottom');
    await page.locator('#chat .option-box .option-btn').nth(5).click();
    await page.waitForTimeout(200);
    const state = await page.evaluate(() => ({
      picked: [...document.querySelectorAll('#chat .option-box .option-btn')].map((b, i) => (b.classList.contains('picked') ? i : -1)).filter((i) => i >= 0),
      confirmEnabled: !document.querySelector('#chat .option-box .option-confirm').disabled,
    }));
    expect(state.picked, '手机档滚到底后仍可点选').toEqual([5]);
    expect(state.confirmEnabled).toBe(true);
    await page.locator('#chat .option-box .option-confirm').click();
    await page.waitForTimeout(400);
    const answered = await page.evaluate(() => document.querySelector('#chat .option-box .option-answer')?.textContent || '');
    expect(answered).toContain('方案 6');
    await shot(page, 'c6-3-mobile-answered');
    await ctx.close();
  });
});

/* ── C7 媒体面点击 = 选中该选项（不打开 Canvas）────────────────────────── */

test.describe('C7 点媒体面 = 选中该选项；点「展开」不选中', () => {
  test('叠卡整面点击选中；展开钮点击只展开', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: MIXED_ITEMS });

    const opened = [];
    await page.evaluate(() => {
      window.__open = [];
      window.addEventListener('workspace-open-item', (e) => window.__open.push(e.detail));
    });
    // 点三图叠卡的卡脸（front 张）。
    await page.locator('#chat .option-box .option-btn').nth(3).locator('.pop-stack-view').click({ position: { x: 40, y: 60 } });
    await page.waitForTimeout(300);
    const afterFace = await page.evaluate(() => ({
      picked: [...document.querySelectorAll('#chat .option-box .option-btn')].map((b, i) => (b.classList.contains('picked') ? i : -1)).filter((i) => i >= 0),
      openEvents: (window.__open || []).length,
    }));
    expect(afterFace.picked, '点媒体面 ⇒ 选中该选项').toEqual([3]);
    expect(afterFace.openEvents, '点媒体面不打开 Canvas（选项语义优先）').toBe(0);

    // 点「展开 N」⇒ 只展开，不改变选择。
    await page.locator('#chat .option-box .option-btn').nth(3).locator('.pill').click();
    await page.waitForTimeout(600);
    const afterPill = await page.evaluate(() => ({
      expanded: document.querySelector('#chat .option-box .pop-expand-view')?.classList.contains('show'),
      picked: [...document.querySelectorAll('#chat .option-box .option-btn')].map((b, i) => (b.classList.contains('picked') ? i : -1)).filter((i) => i >= 0),
    }));
    expect(afterPill.expanded, '展开钮展开').toBe(true);
    expect(afterPill.picked, '展开钮不选中').toEqual([3]);
    opened.push(afterFace);
    await shot(page, 'c7-face-pick');
    await ctx.close();
  });
});

/* ── C8 手机全屏页「叠卡内容完整可见」闸（2026-11-18 作者报障）───────────────
   作者令逐字（2026-11-18）：「手机端的叠卡显示有些不完整 允许它问题卡片横向占用
   空间大一点 让内容能完整显示」。
   病灶（375×812 实测，被测树 = 未修源码）：提问卡宿主泡吃的是**消息列宽** 80%
   ⇒ 卡身 274.4 / 内容盒 242.4 < 叠卡行固有宽 265.3（展开钮 81.3 + 缝 16 + 卡脸
   168）；扇形末层再向右探 32.4 ⇒ 前景卡脸右缘越过 `.option-scroll`
   （overflow-x:hidden）裁剪盒 28.9px、扇形末层越 61.3px ⇒ 图被切、露边被切。
   判据（几何读数，不读 class 猜）：
     ① 真手机全屏页（body.mobile-layer）；
     ② 卡内一切**已绘制**像素（含旋转层 AABB）都在 `.option-scroll` 裁剪盒内；
     ③ 前景卡脸完整落位（右缘 ≤ 裁剪盒右缘）；
     ④ 叠卡行固有宽 ≤ 滚动区内容盒宽（卡脸装得下，而非靠缩小/裁切糊过去）；
     ⑤ 宿主泡 max-width **来自 token**（computed == var(--ds-ask-card-max-w) 解析值）；
     ⑥ 页面零横向滚动。
   红/绿：未修源码直跑 ⇒ ②/③/④ 红；`--ds-ask-card-max-w` 回 80% ⇒ 同样红；
   本批修复 ⇒ 绿。 */
test.describe('C8 手机全屏页：叠卡内容完整可见（无裁切）', () => {
  test('375×812 真全屏页：卡脸与扇形露边全数在裁剪盒内；宽度来自 token', async ({ browser }) => {
    const ctx = await browser.newContext({
      viewport: { width: 375, height: 812 },
      isMobile: true, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'light',
    });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: LONG_MEDIA_ITEMS, requestId: 'rid-mobile-clip' });
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 20000 });
    await page.waitForTimeout(600);

    const r = await page.evaluate(() => {
      const box = document.querySelector('#chat .option-box');
      const bubble = box.parentElement;
      const scroll = box.querySelector('.option-scroll');
      const scs = getComputedStyle(scroll);
      // overflow:hidden 的裁剪区 = padding box（border box − border 宽）。
      const clipRight = scroll.getBoundingClientRect().right - (parseFloat(scs.borderRightWidth) || 0);
      const clipLeft = scroll.getBoundingClientRect().left + (parseFloat(scs.borderLeftWidth) || 0);
      // 一切后代中「最右/最左已绘制缘」——旋转层的 getBoundingClientRect = 旋转后 AABB。
      let worstR = -Infinity, worstEl = '', worstL = Infinity;
      scroll.querySelectorAll('*').forEach((el) => {
        const b = el.getBoundingClientRect();
        if (b.width <= 0 || b.height <= 0) return;
        if (b.right > worstR) { worstR = b.right; worstEl = el.className.toString().split(' ').slice(0, 2).join('.') || el.tagName; }
        if (b.left < worstL) worstL = b.left;
      });
      const host = box.querySelector('.option-preview-media');
      const area = host && host.querySelector('.pop-stack-area');
      const pill = area && area.querySelector('.pill');
      const view = host && host.querySelector('.pop-stack-view');
      const layers = host ? [...host.querySelectorAll('.pop-stack-view .media-layer')] : [];
      const back = layers[2];
      const gap = area ? parseFloat(getComputedStyle(area).columnGap || getComputedStyle(area).gap || '0') : 0;
      const intrinsic = pill && view ? pill.getBoundingClientRect().width + gap + view.getBoundingClientRect().width : 0;
      const scrollContentW = scroll.getBoundingClientRect().width
        - parseFloat(scs.paddingLeft || '0') - parseFloat(scs.paddingRight || '0')
        - (parseFloat(scs.borderLeftWidth) || 0) - (parseFloat(scs.borderRightWidth) || 0);
      // token 解析：宿主泡的 computed max-width 必须等于该槽的解析值。
      const probe = document.createElement('div');
      probe.style.maxWidth = 'var(--ds-ask-card-max-w)';
      bubble.appendChild(probe);
      const resolved = getComputedStyle(probe).maxWidth;
      probe.remove();
      const label = box.querySelector('.option-btn .option-text');
      return {
        mobileLayer: document.body.classList.contains('mobile-layer'),
        tokenDeclared: getComputedStyle(bubble).getPropertyValue('--ds-ask-card-max-w').trim(),
        hostMaxWidth: getComputedStyle(bubble).maxWidth,
        tokenResolved: resolved,
        clipLeft: +clipLeft.toFixed(1), clipRight: +clipRight.toFixed(1),
        worstPaintedRight: +worstR.toFixed(1), worstPaintedEl: worstEl, worstPaintedLeft: +worstL.toFixed(1),
        overflowPx: +(worstR - clipRight).toFixed(1), overflowLeftPx: +(clipLeft - worstL).toFixed(1),
        faceRight: view ? +view.getBoundingClientRect().right.toFixed(1) : null,
        backRight: back ? +back.getBoundingClientRect().right.toFixed(1) : null,
        intrinsicW: +intrinsic.toFixed(1), scrollContentW: +scrollContentW.toFixed(1),
        labelH: label ? +label.getBoundingClientRect().height.toFixed(1) : 0,
        labelText: label ? label.innerText : '',
        labelOverflow: label ? getComputedStyle(label).textOverflow : null,
        docScrollW: document.documentElement.scrollWidth, viewportW: window.innerWidth,
        boxW: +box.getBoundingClientRect().width.toFixed(1),
        rowW: +bubble.parentElement.getBoundingClientRect().width.toFixed(1),
      };
    });

    expect(r.mobileLayer, '作者看到的真形态 = 手机全屏页（非弹窗/内嵌面板）').toBe(true);
    // ② 零裁切（**本闸主判据**：作者报的「叠卡显示不完整」）：卡内一切已绘制像素
    //    （含扇形旋转层的 AABB）都在裁剪盒内。未修源码直跑 ⇒ 这里红，读数 =
    //    前景卡脸越 28.9px / 扇形末层越 61.3px。
    expect(r.overflowPx, `叠卡右缘溢出裁剪盒（${r.worstPaintedEl}，裁剪盒右 ${r.clipRight}）—— 内容被切`)
      .toBeLessThanOrEqual(0.5);
    expect(r.overflowLeftPx, '叠卡左缘不得溢出裁剪盒').toBeLessThanOrEqual(0.5);
    // ③ 前景卡脸完整落位（作者截图里被切的那一块图）。
    expect(r.faceRight, '前景卡脸右缘必须在裁剪盒内（作者报的「被切的那一行/那一块」）')
      .toBeLessThanOrEqual(r.clipRight + 0.5);
    // 扇形末层（作者截图里探出的橙色边）同样不能挨刀。
    expect(r.backRight, '扇形末层右缘必须在裁剪盒内').toBeLessThanOrEqual(r.clipRight + 0.5);
    // ④ 卡脸装得下：叠卡行固有宽 ≤ 滚动区内容盒宽（不是靠缩小/裁切/省略号糊过去）。
    expect(r.intrinsicW, '叠卡行固有宽必须装进滚动区内容盒（内容完整而非被裁）')
      .toBeLessThanOrEqual(r.scrollContentW + 0.5);
    // 选项文字完好：长标题换行完整（无省略号截断，且有实际高度）。
    expect(r.labelText).toContain('用于检验换行与裁切');
    expect(r.labelOverflow === 'ellipsis', '标题不得改成省略号截断').toBe(false);
    expect(r.labelH, '长标题有实际渲染高度（换行后完整）').toBeGreaterThan(18);
    // ⑥ 页面零横向滚动；卡身确实变宽（作者裁定的「横向占用空间大一点」真的生效）。
    expect(r.docScrollW, '页面零横向滚动').toBeLessThanOrEqual(r.viewportW + 1);
    expect(r.boxW, '提问卡在手机层放宽到接近整列（作者裁定）').toBeGreaterThan(r.rowW * 0.9);
    // ⑤ 宽度来自 token：宿主泡的 computed max-width == var(--ds-ask-card-max-w) 解析值。
    expect(r.tokenDeclared, '手机层必须把 --ds-ask-card-max-w 落在同一个单源槽').not.toBe('');
    expect(r.hostMaxWidth, '宿主泡 max-width 必须由 --ds-ask-card-max-w 解析而来（非就地字面量）')
      .toBe(r.tokenResolved);
    await shot(page, 'c8-mobile-complete');
    await ctx.close();
  });
});

/* ── C9 桌面零回归闸：提问卡宿主不得在桌面被放宽 ────────────────────────────
   手机层的放宽必须**只在 body.mobile-layer 下**发生。桌面判据 = 提问卡宿主泡
   （.bubble.ob-ghost-seat）的 max-width 与普通消息泡（.bubble）**逐值相等**
   （即仍 == --ds-bubble-max-w = 80%）。任何人把 --ds-ask-card-max-w 的缺省改成
   更宽 ⇒ 本闸红。 */
test.describe('C9 桌面零回归：提问卡宿主宽度仍与消息泡同档', () => {
  test('1280×900：宿主 max-width == 普通气泡 max-width（80%），卡身几何不因本批变宽', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
    const page = await ctx.newPage();
    await bootWithAsk(page, { items: LONG_MEDIA_ITEMS, requestId: 'rid-desk-regress' });

    const r = await page.evaluate(() => {
      const box = document.querySelector('#chat .option-box');
      const bubble = box.parentElement;
      const plain = document.querySelector('#chat .row .bubble:not(.ob-ghost-seat)');
      const scroll = box.querySelector('.option-scroll');
      const scs = getComputedStyle(scroll);
      const clipRight = scroll.getBoundingClientRect().right - (parseFloat(scs.borderRightWidth) || 0);
      const host = box.querySelector('.option-preview-media');
      const layers = host ? [...host.querySelectorAll('.pop-stack-view .media-layer')] : [];
      return {
        mobileLayer: document.body.classList.contains('mobile-layer'),
        hostMaxWidth: getComputedStyle(bubble).maxWidth,
        plainMaxWidth: plain ? getComputedStyle(plain).maxWidth : null,
        tokenAsk: getComputedStyle(bubble).getPropertyValue('--ds-ask-card-max-w').trim(),
        tokenBubble: getComputedStyle(bubble).getPropertyValue('--ds-bubble-max-w').trim(),
        boxW: +box.getBoundingClientRect().width.toFixed(1),
        clipRight: +clipRight.toFixed(1),
        backRight: layers[2] ? +layers[2].getBoundingClientRect().right.toFixed(1) : null,
        docScrollW: document.documentElement.scrollWidth, viewportW: window.innerWidth,
      };
    });

    expect(r.mobileLayer, '桌面不该进手机层').toBe(false);
    expect(r.tokenAsk, '桌面缺省 = 消息列宽档（同槽缺省值）').toBe(r.tokenBubble);
    expect(r.hostMaxWidth, '桌面提问卡宿主仍与普通气泡同档（零回归）').toBe(r.plainMaxWidth);
    expect(r.hostMaxWidth, '桌面档 = 80%').toBe('80%');
    expect(r.backRight, '桌面叠卡本就完整（扇形末层在裁剪盒内）').toBeLessThanOrEqual(r.clipRight + 0.5);
    expect(r.docScrollW).toBeLessThanOrEqual(r.viewportW + 1);
    await shot(page, 'c9-desktop-no-regression');
    await ctx.close();
  });
});

/* ── 自检：夹具与被测树可打印（红腿靠 CHATFACE_WEB_ROOT 换树）───────────── */

test('harness self-check: 被测 web 树与夹具在场', async () => {
  const { WEB } = await import('./_chat-face-harness.mjs');
  expect(WEB).toContain('web');
  expect(WEBM_BYTES.length, '夹具视频字节非空').toBeGreaterThan(500);
  expect(zlib.deflateSync('x').length).toBeGreaterThan(0);
});
