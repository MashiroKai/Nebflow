import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { expect } from '@playwright/test';

const [port, cookie, cookieName, sid] = process.argv.slice(2);
const base = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext();
  await context.addCookies([{ name: cookieName, value: cookie, url: base, httpOnly: true, sameSite: 'Lax' }]);
  const page = await context.newPage();
  await page.goto(`${base}/fixture`);
  await page.evaluate(async sid => {
    const state = (await import('/js/state.js')).default;
    state.ws = new WebSocket(`ws://${location.host}/ws`);
    await new Promise((resolve, reject) => { state.ws.onopen = resolve; state.ws.onerror = reject; });
    window.sendQuote = async (body, complete, content = 'execute', split = false) => {
      const { chatMessageRefInput } = await import('/js/msgQuote.js');
      const { makeReference } = await import('/js/refModel.js');
      const { dispatchUserMessageFrames } = await import('/js/input.js');
      const quote = makeReference(chatMessageRefInput({ kind: 'text', text: body, senderName: 'author', messageId: 'row' }));
      quote.content.fullTextComplete = complete;
      const attachments = split ? [
        { name: 'large.bin', mimeType: 'application/octet-stream', data: 'a'.repeat(8 * 1024 * 1024 + 1) },
        { name: 'second.bin', mimeType: 'application/octet-stream', data: 'b' }
      ] : [];
      dispatchUserMessageFrames({ sessionId: sid, content, clientMessageId: crypto.randomUUID(), refs: [quote], wireAtts: attachments });
    };
  }, sid);
  const short = '逐字正文\nsecond\tline';
  const long = 'long text '.repeat(500) + 'LONG-END';
  await page.evaluate(async ([s, l]) => { await window.sendQuote(s, true); await window.sendQuote(l, true); await window.sendQuote('', false); await window.sendQuote('split exact quote', true, '', true); }, [short, long]);
  await expect.poll(async () => (await (await page.request.get(`${base}/probe`)).json()).some(x => x.includes('split exact quote')), { timeout: 20000 }).toBe(true);
  const messages = await (await page.request.get(`${base}/probe`)).json();
  assert(messages.some(x => x.includes(short)), 'short quote must reach agent verbatim');
  const folded = messages.find(x => x.includes('已折叠') && x.includes('/api/sessions/msg-token/') && !x.includes(long));
  assert(folded, 'long quote must carry handle');
  const handle = folded.match(/msg-token\/([0-9a-f]{8}-[0-9]{6})/)?.[1];
  assert(handle);
  const saved = await (await page.request.get(`${base}/probe-token/${handle}`)).json();
  assert.equal(saved.text, long, 'long handle must resolve exact snapshot');
  assert(messages.some(x => x.includes('引用未能解析')), 'missing quote must be explicit');
  assert(messages.some(x => x.includes('split exact quote')), `refs on first attachment-only split must reach agent: ${JSON.stringify(messages)}`);
  console.log('PASS browser -> real authenticated websocket -> registered agent: short/long/missing/split');
  await context.close();
} finally { await browser.close(); }
