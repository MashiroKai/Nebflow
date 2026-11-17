import { chromium } from 'playwright';
import { writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const [port, cookie, cookieName, sid, root] = process.argv.slice(2);
const base = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext();
  await context.addCookies([{ name: cookieName, value: cookie, url: base, httpOnly: true, sameSite: 'Lax' }]);
  const page = await context.newPage();
  await page.goto(`${base}/fixture`);
  await page.evaluate(async ({ sid, root }) => {
    window.watchFrames = [];
    const ws = new WebSocket(`ws://${location.host}/ws`);
    await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
    ws.onmessage = event => { const msg = JSON.parse(event.data); if (msg.type === 'fsChanged' || msg.type === 'fileOpError') window.watchFrames.push(msg); };
    ws.send(JSON.stringify({ type: 'watchSubscribe', sessionId: sid, rootPath: root, dirs: [''] }));
    window.watchWs = ws;
  }, { sid, root });
  // The protocol has no subscription acknowledgement: repeat actual writes
  // until a notification arrives or the bounded test deadline expires.
  let frame;
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline && !frame) {
    await writeFile(`${root}/probe.txt`, String(Date.now()));
    await page.waitForTimeout(700);
    const messages = await page.evaluate(() => window.watchFrames);
    const error = messages.find(m => m.type === 'fileOpError');
    assert(!error, JSON.stringify(error));
    frame = messages.find(m => m.type === 'fsChanged');
  }
  assert(frame, 'no fsChanged received after real writes');
  assert.equal(frame.rootPath, root);
  assert(Array.isArray(frame.dirs) && frame.dirs.every(x => typeof x === 'string') && frame.dirs.includes(''));
  assert.equal(typeof frame.overflow, 'boolean');
  assert.equal(frame.overflow, false);
  console.log(`WATCH_FRAME ${JSON.stringify(frame)}`);
  await page.evaluate(() => window.watchWs.close());
  await context.close();
} finally { await browser.close(); }
