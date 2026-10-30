// pop-stack-fan.spec.mjs — Pop 多文件叠卡视觉重设计「微信扇卡式」
// （作者令 2026-10-30 12:47:「参考一下微信的叠卡的设计 首先是会强制放缩所有尺寸
//   的卡片到一个大小 其次是叠卡的层次感设计它是像扑克一样轻微旋转展开的
//   而不是我们这样重叠的设计」）
//
//   AC-F1 统一卡尺寸 — 卡堆里所有条目（不论原图/视频比例）强制归一到同一
//        卡面大小：.pop-stack-view 固定 220×280 (11/14)，三张不同比例图的
//        layer box 尺寸一致；栈面 object-fit: cover（裁切保观感），展开行/
//        单文件仍 contain + 自身比例（展开态不受扇形影响）。
//   AC-F2 扇形层次 — 后层卡不再直重叠：mid/back 层 transform 带非零旋转
//        （±2~4° 交错）+ 水平错开；层级 ≤3 全露，4+ 收进「展开 N」。
//   AC-F3 交互回归 — 「展开 N」平铺 / 「收起」回叠可用；展开后每行回到自身
//        比例、transform:none；扇形纯 CSS transform，无新库。
//
// 桌面 (1200×900) + 移动 (390×844) 两视口同一三异比例图组
// （__popStackMixedRatios：1600×400 / 400×1600 / 800×600）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/pop-stack-fan.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/pop-artifacts/harness.html';

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
}

async function waitUntilUp(url, tries = 60) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  expect(fs.existsSync(path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, '')))).toBe(true);
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
});

async function newPage(browser, viewport) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  // Harness artifact: #popup-chat (an EMPTY stand-in container) is absolutely
  // positioned right:0 width:420px — in the real app it lives off-screen
  // (getHiddenRoot); in a 390px test viewport it blankets the whole column and
  // eats every click. Hide it: nothing renders there in these scenarios.
  await page.evaluate(() => { const p = document.getElementById('popup-chat'); if (p) p.style.display = 'none'; });
  return { context, page, pageErrors };
}

/** Render the 3-different-ratio stack and wait for every layer's bytes. */
async function renderFanStack(page) {
  await page.evaluate(() => window.__popStackMixedRatios());
  await page.waitForFunction(
    () => {
      const ls = document.querySelectorAll('#chat .pop-stack-view .media-layer');
      return ls.length === 3 && Array.from(ls).every(l => {
        const m = l.querySelector('.pop-img');
        return m && m.naturalWidth > 0;
      });
    },
    null, { timeout: 10000 },
  );
  await page.waitForTimeout(500); // transitions settle
}

/** Geometric + transform reading of the collapsed stack. */
function fanRead() {
  const view = document.querySelector('#chat .pop-stack-view');
  const vr = view.getBoundingClientRect();
  const layers = Array.from(view.querySelectorAll('.media-layer')).map(el => {
    const r = el.getBoundingClientRect();
    const m = el.querySelector('.pop-img, .pop-video');
    // 2D matrix(a, b, c, d, e, f): b = sin(θ) ⇒ the rotation; e = translateX.
    const tr = getComputedStyle(el).transform;
    const mtx = tr && tr !== 'none'
      ? tr.match(/^matrix\(([^)]+)\)$/)?.[1].split(',').map(Number)
      : null;
    return {
      cls: Array.from(el.classList).join('.'),
      boxW: Math.round(r.width * 10) / 10,
      boxH: Math.round(r.height * 10) / 10,
      centerX: r.left + r.width / 2,
      objectFit: m ? getComputedStyle(m).objectFit : '',
      natRatio: m && m.naturalWidth ? +(m.naturalWidth / m.naturalHeight).toFixed(3) : 0,
      transform: tr,
      rotDeg: mtx ? +(Math.asin(Math.min(1, Math.max(-1, mtx[1]))) * 180 / Math.PI).toFixed(2) : 0,
      translateX: mtx ? mtx[4] : 0,
      zIndex: el.style.zIndex,
    };
  });
  return {
    viewW: Math.round(vr.width * 10) / 10,
    viewH: Math.round(vr.height * 10) / 10,
    layers,
  };
}

const VIEWPORTS = [
  { name: 'desktop', w: 1200, h: 900 },
  { name: 'mobile', w: 390, h: 844 },
];

for (const vp of VIEWPORTS) {
  test.describe(`AC-F1/F2 — fan stack geometry (${vp.name} ${vp.w}×${vp.h})`, () => {
    test('unified card face: all layer boxes are ONE size; back layers fan (rotate + offset)', async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, { width: vp.w, height: vp.h });
      await renderFanStack(page);

      const read = await page.evaluate(fanRead);
      const front = read.layers.find(l => l.cls.includes('layer-front'));
      const mid = read.layers.find(l => l.cls.includes('layer-mid'));
      const back = read.layers.find(l => l.cls.includes('layer-back'));
      expect(front && mid && back, 'front + mid + back all painted (≤3 全露)').toBeTruthy();

      // 三张不同比例图（4.0 / 0.25 / 1.333）—— box 尺寸必须一致（getBoundingClientRect
      // 读变换后矩形：mid/back 的旋转会改变外接矩形，所以统一性断言只看 FRONT 与
      // view 盒，旋转/错开断言走矩阵分量）。
      expect(read.viewW, 'face width 220').toBeCloseTo(220, 0);
      expect(read.viewH, 'face height 280 (11/14)').toBeCloseTo(280, 0);
      expect(front.boxW, 'front layer == face width').toBeCloseTo(read.viewW, 0);
      expect(front.boxH, 'front layer == face height').toBeCloseTo(read.viewH, 0);
      expect(front.natRatio, 'fixture: front is the 4:1 wide image').toBeCloseTo(4, 1);
      expect(front.natRatio, 'the front media ratio differs from the face — the crop is real')
        .not.toBeCloseTo(read.viewW / read.viewH, 1);

      // 扇形层次：后层旋转非零（±2~4°）+ 水平错开存在；纯 CSS transform。
      expect(Math.abs(mid.rotDeg), `mid rotates (got ${mid.rotDeg}°)`).toBeGreaterThan(0.5);
      expect(Math.abs(mid.rotDeg), `mid rotation within the ±2~4° fan band (got ${mid.rotDeg}°)`).toBeLessThanOrEqual(4.5);
      expect(Math.abs(back.rotDeg), `back rotates (got ${back.rotDeg}°)`).toBeGreaterThan(0.5);
      expect(Math.abs(back.rotDeg), `back rotation within the ±2~4° fan band (got ${back.rotDeg}°)`).toBeLessThanOrEqual(4.5);
      expect(Math.abs(mid.translateX), `mid offset exists (got ${mid.translateX}px)`).toBeGreaterThan(4);
      expect(Math.abs(back.translateX), `back offset exists (got ${back.translateX}px)`).toBeGreaterThan(4);
      expect(Math.abs(back.translateX), 'back offsets MORE than mid (错落层次)').toBeGreaterThan(Math.abs(mid.translateX));
      expect(mid.zIndex, 'mid under front').toBe('2');
      expect(back.zIndex, 'back under mid').toBe('1');

      // 统一卡尺寸 + 裁切观感：栈面 cover；front 命中点仍是 img（叠卡区域没被埋）。
      for (const l of read.layers) {
        expect(l.objectFit, `${l.cls}: stack face crops onto the one card (cover)`).toBe('cover');
      }
      expect(pageErrors).toEqual([]);
      await context.close();
    });

    test('expand interaction regression: 展开 N flattens to own-ratio rows, 收起 re-stacks', async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, { width: vp.w, height: vp.h });
      await renderFanStack(page);

      // REAL click on the pill (the author's gesture path).
      const pill = page.locator('#chat .pop-stack-area .pill');
      const label = (await pill.textContent()).trim();
      expect(label, 'the pill offers 展开 3').toContain('3');
      await pill.scrollIntoViewIfNeeded();
      const pb = await pill.boundingBox();
      await page.mouse.click(pb.x + pb.width / 2, pb.y + pb.height / 2);
      await page.waitForFunction(() => document.querySelector('#chat .pop-expand-view').classList.contains('show'),
        null, { timeout: 5000 });
      await page.waitForTimeout(620); // the 0.42s height transition settles

      const expanded = await page.evaluate(() => {
        const rows = Array.from(document.querySelectorAll('#chat .pop-expand-view .media-layer'));
        return {
          rowCount: rows.length,
          // 展开态不受扇形影响：每行回到自身比例（三行高度两两不同）、transform 清零。
          heights: rows.map(r => Math.round(r.getBoundingClientRect().height * 10) / 10),
          transforms: rows.map(r => getComputedStyle(r).transform),
          objectFits: rows.map(r => {
            const m = r.querySelector('.pop-img');
            return m ? getComputedStyle(m).objectFit : '';
          }),
          stackHidden: getComputedStyle(document.querySelector('#chat .pop-stack-view')).display,
        };
      });
      expect(expanded.rowCount, 'one row per item').toBe(3);
      expect(new Set(expanded.heights).size, `each row keeps its OWN ratio (heights ${expanded.heights})`).toBe(3);
      for (const t of expanded.transforms) {
        expect(t, 'expanded rows carry no fan transform').toBe('none');
      }
      for (const f of expanded.objectFits) {
        expect(f, 'expanded rows stay un-cropped (contain)').toBe('contain');
      }
      expect(expanded.stackHidden, 'expanding hides the collapsed fan').toBe('none');

      // 收起回归：pill 变回「展开 3」，扇形叠卡复原（旋转/错开仍在）。
      const pill2 = page.locator('#chat .pop-stack-area .pill');
      await page.waitForTimeout(460); // the height transition's settle timer
      const pb2 = await pill2.boundingBox();
      await page.mouse.click(pb2.x + pb2.width / 2, pb2.y + pb2.height / 2);
      await page.waitForFunction(() => !document.querySelector('#chat .pop-expand-view').classList.contains('show'),
        null, { timeout: 5000 });
      await page.waitForTimeout(620);
      const label2 = (await pill2.textContent()).trim();
      expect(label2, 'the pill is back to 展开 3').toContain('3');
      const restored = await page.evaluate(fanRead);
      const rFront = restored.layers.find(l => l.cls.includes('layer-front'));
      const rMid = restored.layers.find(l => l.cls.includes('layer-mid'));
      expect(restored.viewW, 'the stack box is back (fan face 220)').toBeCloseTo(220, 0);
      expect(rFront.transform, 'front is unrotated').toBe('none');
      expect(Math.abs(rMid.rotDeg), 'the fan transform restored after collapse').toBeGreaterThan(0.5);
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  });
}

/* ═══════════ AC-F2b — >3 层收进「展开 N」（微信语义，扇形下不变） ═══════════ */

test.describe('AC-F2b — more than three layers stay collapsed into 展开 N', () => {
  test('five different-ratio images: only 3 painted, the rest is-buried', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, { width: 1200, height: 900 });
    await page.evaluate(() => window.__popFiveImages());
    await page.waitForFunction(
      () => document.querySelectorAll('#chat .pop-stack-view .media-layer').length === 5,
      null, { timeout: 10000 },
    );

    const read = await page.evaluate(fanRead);
    expect(read.layers.length, 'every item keeps its layer').toBe(5);
    const buried = read.layers.filter(l => l.cls.includes('is-buried'));
    expect(buried.length, 'the 4th and 5th are buried into the pill').toBe(2);
    // three painted layers: front unrotated + two fanning behind.
    const front = read.layers.find(l => l.cls.includes('layer-front'));
    const fan = read.layers.filter(l => !l.cls.includes('layer-front') && !l.cls.includes('is-buried'));
    expect(fan.length, 'two fan layers painted beside the front').toBe(2);
    expect(front.transform, 'the front card carries no fan transform').toBe('none');
    for (const l of fan) {
      expect(Math.abs(l.rotDeg), `${l.cls} fans (rotate ≠ 0)`).toBeGreaterThan(0.5);
    }
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

/* ═══════════ AC-F3 — 单文件不叠 + 点击面回归 ═══════════ */

test.describe('AC-F3 — a single media item never stacks; the collapsed fan still opens the front card', () => {
  test('single image: flat card, own ratio, no pill — unchanged by the fan batch', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, { width: 1200, height: 900 });
    await page.evaluate(() => window.__popSingleWide());
    await page.waitForSelector('#chat .pop-media-flat .pop-img');
    await page.waitForTimeout(300);

    const read = await page.evaluate(() => {
      const box = document.querySelector('#chat .pop-media-flat');
      const img = box.querySelector('.pop-img');
      const r = box.getBoundingClientRect();
      return {
        boxRatio: +(r.width / r.height).toFixed(3),
        natRatio: +(img.naturalWidth / img.naturalHeight).toFixed(3),
        objectFit: getComputedStyle(img).objectFit,
        pillCount: document.querySelectorAll('#chat .pop-stack-area').length,
      };
    });
    expect(read.boxRatio, 'the flat box keeps the media ratio (fan batch untouched it)')
      .toBeCloseTo(read.natRatio, 1);
    expect(read.objectFit, 'the flat face stays un-cropped (contain)').toBe('contain');
    expect(read.pillCount, 'no stack chrome for a lone item').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('a real click on the collapsed fan opens Canvas with the SHOWN (front) card', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser, { width: 1200, height: 900 });
    await renderFanStack(page);

    const front = page.locator('#chat .pop-stack-view .media-layer.layer-front .pop-img');
    await front.scrollIntoViewIfNeeded();
    await page.waitForTimeout(150);
    const fb = await front.boundingBox();
    const point = { x: fb.x + fb.width / 2, y: fb.y + fb.height / 2 };
    const hit = await page.evaluate(([x, y]) => {
      const el = document.elementFromPoint(x, y);
      return el ? el.tagName.toLowerCase() + '.' + Array.from(el.classList).join('.') : 'null';
    }, [point.x, point.y]);
    expect(hit, `the click point hit-tests to the image body (got ${hit})`).toContain('img.pop-img');

    await page.evaluate(() => {
      window.__opens = [];
      window.__onOpen = e => window.__opens.push(e.detail ? { absPath: e.detail.absPath } : null);
      window.addEventListener('workspace-open-item', window.__onOpen);
    });
    await page.mouse.click(point.x, point.y);
    await page.waitForTimeout(300);
    const opens = await page.evaluate(() => {
      window.removeEventListener('workspace-open-item', window.__onOpen);
      return window.__opens;
    });
    expect(opens.length, 'exactly one Canvas open fired').toBe(1);
    expect(opens[0].absPath, 'opened content = the front item (fan-0.png)').toBe('/tmp/pop/fan-0.png');
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});
