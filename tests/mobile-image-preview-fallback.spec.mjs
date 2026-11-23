#!/usr/bin/env node
// mobile-image-preview-fallback.spec.mjs — 手机图片「点开大图 → 预览失败」闸（作者报障 2026-11-23
// 21:52 · 实图 IMG_2039：「Pop 的消息图片缩略图显示正常，点开大图弹 toast『预览失败，请重试』」）。
//
// 根因（messages.js previewAttachmentOf）：图片名先命中 media 分支，原图腿
// （api.downloadAttachment → /api/friends/attachments/{id}）一败即整件 toast，卡面上已水合
// 的同一件字节被丢弃；手机层「复用卡面字节」分支对图片名不可达（media 正则覆盖全部图片扩展名）。
//
// 判据：
//   P1 原图腿 410（已过期）+ 卡面缩略在场 ⇒ 无 toast；灯箱打开；灯箱图 naturalWidth>0（卡面字节兜底）。
//   P2 原图腿 200 ⇒ 无 toast；灯箱打开；灯箱图 naturalWidth>0；原图腿恰被请求 1 次（原图真取到）。
//   N1 负对照：原图腿 410 + 无卡面缩略 ⇒ 必须 toast（证明 toast 路径活着、闸非空转）。
//
// 用法：node tests/mobile-image-preview-fallback.spec.mjs    （WEB_ROOT=<dir> 可指向旧树做红验）
// Exit：0 = 全绿；1 = 违例；2 = 环境缺失。

import { join } from 'node:path';
import { deflateSync } from 'node:zlib';
import { ROOT, loadPlaywright, launchBrowser, createSession, setWebRoot } from '../scripts/lib/visual-harness.mjs';

setWebRoot(process.env.WEB_ROOT || join(ROOT, 'src/main/resources/web'));

const CLOCK = 1793940000000;
const VIEW = { width: 390, height: 844 };
const ATT_ID = 'att-img2039';
const ATT_URL = `**/api/friends/attachments/${ATT_ID}`;

// 最小合法 PNG（64×64 红块），零外部文件依赖。
function pngBytes(w = 64, h = 64) {
  const crc = (buf) => {
    let c, crcv = 0xffffffff;
    for (let n = 0; n < buf.length; n++) {
      c = (crcv ^ buf[n]) & 0xff;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcv = (crcv >>> 8) ^ c;
    }
    return (crcv ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const row = Buffer.concat([Buffer.from([0]), Buffer.from([220, 60, 60].concat(Array(w - 1).fill(0).flatMap(() => [220, 60, 60])))]);
  const raw = Buffer.concat(Array.from({ length: h }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

async function runCase(browser, { attStatus, withThumb }) {
  const s = await createSession(browser, { width: VIEW.width, height: VIEW.height, colorScheme: 'light', clock: CLOCK });
  s.page.on('pageerror', (e) => { throw new Error('pageerror ' + e.message); });
  const hits = { n: 0 };
  const png = pngBytes();
  await s.page.route(ATT_URL, (route) => {
    hits.n += 1;
    if (attStatus === 200) return route.fulfill({ status: 200, contentType: 'image/png', body: png });
    return route.fulfill({ status: attStatus, contentType: 'application/json', body: '{"error":"expired"}' });
  });
  await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: 'preview-root', messages: [] });
  const out = await s.page.evaluate(async ({ withThumb, pngB64, attId }) => {
    window.__toasts = [];
    window.__showToast = (msg) => { window.__toasts.push(String(msg)); };
    const img = document.createElement('img');
    img.setAttribute('data-test-thumb', '1');
    if (withThumb) {
      img.src = 'data:image/png;base64,' + pngB64;
      await img.decode();
    }
    document.body.appendChild(img);
    const mod = await import('/js/messages.js');
    if (typeof mod.previewAttachmentOf !== 'function') return { fatal: 'previewAttachmentOf not exported' };
    mod.previewAttachmentOf({ id: attId, name: 'IMG_2039.png' }, withThumb ? img : null, 'friend', {});
    const deadline = Date.now() + 4000;
    let lb = null;
    while (Date.now() < deadline) {
      lb = document.querySelector('.nf-lightbox');
      if (window.__toasts.length || (lb && lb.classList.contains('on') && document.querySelector('.nf-lightbox img.loaded, .nf-lightbox img'))) break;
      await new Promise((r) => setTimeout(r, 60));
    }
    await new Promise((r) => setTimeout(r, 300));
    const gImg = document.querySelector('.nf-lightbox img');
    return {
      toasts: window.__toasts.slice(),
      lbOn: !!(lb && lb.classList.contains('on')),
      imgNatural: gImg ? gImg.naturalWidth : 0,
    };
  }, { withThumb, pngB64: png.toString('base64'), attId: ATT_ID });
  await s.close();
  return { ...out, attHits: hits.n };
}

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[mobile-image-preview-fallback] browser=${label} view=${VIEW.width}x${VIEW.height}`);
  let failed = 0;
  const check = (name, ok, detail) => {
    if (ok) console.log(`PASS ${name} ${JSON.stringify(detail)}`);
    else { failed++; console.error(`FAIL ${name} ${JSON.stringify(detail)}`); }
  };

  const p1 = await runCase(browser, { attStatus: 410, withThumb: true });
  if (p1.fatal) { console.error(p1.fatal); await browser.close(); process.exit(2); }
  check('P1 original-410 + thumb ⇒ no toast, lightbox on, image decoded',
    p1.toasts.length === 0 && p1.lbOn && p1.imgNatural > 0, p1);

  const p2 = await runCase(browser, { attStatus: 200, withThumb: true });
  check('P2 original-200 ⇒ no toast, lightbox on, original fetched once',
    p2.toasts.length === 0 && p2.lbOn && p2.imgNatural > 0 && p2.attHits === 1, p2);

  const n1 = await runCase(browser, { attStatus: 410, withThumb: false });
  check('N1 negative: original-410 + no thumb ⇒ toast must fire (path live)',
    n1.toasts.length === 1 && !n1.lbOn, n1);

  await browser.close();
  if (failed) { console.error(`mobile-image-preview-fallback: ${failed} failure(s)`); process.exit(1); }
  console.log('mobile-image-preview-fallback: green');
}

main().catch((e) => {
  console.error(`mobile-image-preview-fallback: ${e && e.message ? e.message.split('\n')[0] : e}`);
  process.exit(e && /playwright|browser|Executable/i.test(String(e.message)) ? 2 : 1);
});
