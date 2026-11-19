// Real renderer / real CSS; mock transport only. No host gateway access.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { bootChat, historyFixture } from './_chat-face-harness.mjs';
const phase = process.env.PHASE || 'after';
const dir = new URL('../reports-evidence/notify-bubble-fix/', import.meta.url);
mkdirSync(dir, { recursive: true });
const browser = await chromium.launch();
const results = [];
try {
  for (const theme of ['light', 'dark']) for (const width of [1440, 375]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme: theme });
    const page = await context.newPage();
    await bootChat(page, { history: historyFixture(2) });
    const reading = await page.evaluate(async () => {
      const { buildInjectedRow } = await import('/js/chat/injectedRows.js');
      const { bubbleEl } = await import('/js/messages.js');
      const chat = document.getElementById('chat');
      chat.replaceChildren();
      for (const status of ['failed', 'completed']) chat.append(buildInjectedRow('Result details retained', 'delegate', Date.now(), status, 'nebflow'));
      const flow = document.createElement('div');
      flow.className = 'fm-flow';
      flow.style.cssText = 'flex: none; width: 100%; box-sizing: border-box; padding-top: 24px';
      const conv = { id: 'regression', kind: 'direct', friend: { userId: 'peer', name: 'Peer' } };
      for (const ours of [true, false]) flow.append(bubbleEl({ id: String(ours), body: ours ? 'Sent message' : 'Received message', ours, senderId: ours ? 'me' : 'peer', createdAt: Date.now() }, conv));
      chat.append(flow);
      const rect = el => { const r = el.getBoundingClientRect(); return { left: r.left, right: r.right }; };
      const bubbles = [...chat.querySelectorAll('.bubble.injected')];
      const out = flow.querySelector('.fm-msg.out'), incoming = flow.querySelector('.fm-msg.in');
      const token = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
      const expected = document.createElement('span');
      document.body.append(expected);
      const resolve = value => { expected.style.color = value; return getComputedStyle(expected).color; };
      const result = {
        notificationBg: bubbles.map(el => getComputedStyle(el).backgroundColor),
        expectedBg: resolve('rgb(var(--sapphire) / 0.12)'),
        verdictColors: bubbles.map(el => getComputedStyle(el.querySelector('.nf-rcpt-badge')).color),
        expectedVerdicts: ['--status-error-icon', '--status-success-icon'].map(name => resolve(token(name))),
        out: rect(out), incoming: rect(incoming),
        messageColors: [out, incoming].map(el => getComputedStyle(el.querySelector('.bubble')).backgroundColor),
      };
      expected.remove();
      return result;
    });
    const checks = {
      notificationBaseline: reading.notificationBg.every(value => value === reading.expectedBg),
      verdictSemantics: reading.verdictColors.every((value, i) => value === reading.expectedVerdicts[i]),
      sentRightReceivedLeft: reading.out.right > reading.incoming.right + 10 && reading.out.left > reading.incoming.left + 10,
      receivedNotGreen: reading.messageColors[0] !== reading.messageColors[1],
    };
    results.push({ theme, width, checks, reading });
    await page.locator('#chat').screenshot({ path: new URL(`${phase}-${theme}-${width}.png`, dir).pathname });
    await context.close();
  }
} finally { await browser.close(); }
writeFileSync(new URL(`${phase}.json`, dir), JSON.stringify(results, null, 2));
for (const row of results) console.log(row.theme, row.width, row.checks);
const failed = results.flatMap(row => Object.values(row.checks)).filter(value => !value).length;
console.log(`notify-bubble-regression: ${16 - failed}/16 passed`);
process.exitCode = failed ? 1 : 0;
