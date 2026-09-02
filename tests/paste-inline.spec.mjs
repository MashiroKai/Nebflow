// paste-inline.spec.mjs — pure-paste inline send (纯粘贴内联, 2026-08-31).
//
// Three ruled behaviors under test:
//   R1  empty/whitespace input + paste ≤ 64KB  → text stays the message BODY
//       (no attachment; agent reads it in turn 1, zero tool calls).
//   R2  paste + substantial typed text → legacy conversion (typed text is the
//       intent, paste is the material) — unchanged, regression-guarded here.
//   R3  paste > 64KB → legacy conversion — unchanged; boundary (=/> 65536
//       bytes) pinned on both sides.
//
// Self-contained: ephemeral-port static server serving src/main/resources/web,
// real index.html boot (no backend, never touches the 8080 host or sbt).
// state.ws is stubbed so outgoing message frames are captured for assertion;
// pastes go through the REAL clipboard (grantPermissions + Cmd/Ctrl+V) so the
// browser default-insertion path is exercised exactly as a user does it.
//
// Run: npx playwright test tests/paste-inline.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};

/** Static server for the web dir (same shape as orbit-anim.spec.mjs). */
function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        const file = join(WEB, p === '/' ? 'index.html' : p);
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

let server, port, base;

test.beforeAll(async () => {
  ({ server, port } = await startServer());
  base = `http://127.0.0.1:${port}`;
});
test.afterAll(async () => { server.close(); });

test.beforeEach(async ({ context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => {});
});

/** Boot the real app, stub state.ws, install a capture-phase paste probe. */
async function boot(page) {
  await page.goto(base + '/');
  await page.waitForSelector('#input', { timeout: 20000 });
  await page.evaluate(async () => {
    document.addEventListener('paste', (e) => { window.__lastPaste = e; }, true);
    const [stateMod, inputMod, viewMod] = await Promise.all([
      import('/js/state.js'), import('/js/input.js'), import('/js/chatView.js'),
    ]);
    window.__nf = { state: stateMod.default, input: inputMod, views: viewMod };
    window.__captured = [];
    stateMod.default.ws = {
      readyState: 1, // WebSocket.OPEN
      send: (d) => window.__captured.push(JSON.parse(d)),
    };
  });
  // First-run onboarding overlay (fresh localStorage) — dismiss if it showed.
  const skip = page.locator('#ob-skip');
  if (await skip.isVisible({ timeout: 1500 }).catch(() => false)) {
    await skip.click();
    await page.locator('.onboarding-overlay').waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
  }
}

/** Real user paste: clipboard write + focus #input + Cmd/Ctrl+V. */
async function realPaste(page, text) {
  await page.evaluate((t) => navigator.clipboard.writeText(t), text);
  await page.locator('#input').focus();
  await page.keyboard.press('ControlOrMeta+v');
}

const defaultPrevented = (page) => page.evaluate(() => !!window.__lastPaste?.defaultPrevented);
const attCount = (page) =>
  page.evaluate(async () => (await import('/js/chatView.js')).chatViews.primary.pendingAttachments.length);

/** Last captured WS frame that carries a `content` field (the message frame). */
async function lastMessageFrame(page) {
  return page.evaluate(() => {
    const frames = window.__captured.filter((f) => typeof f.content === 'string');
    return frames[frames.length - 1] ?? null;
  });
}

async function sendAndCapture(page) {
  await page.evaluate(() => window.__nf.input.send());
  return lastMessageFrame(page);
}

// ============================================================
// R1 — pure-paste inline send (empty input, ≤ threshold)
// ============================================================

test.describe('R1 pure-paste inline', () => {
  test('mid-band paste (2000 chars) into empty input → message body, no attachment, zero tool calls', async ({ page }) => {
    await boot(page);
    const text = 'R1 '.repeat(667) + 'END'; // 2004 chars ASCII, in (1000, 65536] band
    await realPaste(page, text);

    // Browser default insertion happened (handler did NOT preventDefault)…
    expect(await defaultPrevented(page)).toBe(false);
    await expect(page.locator('#input')).toHaveValue(text);
    // …and nothing became an attachment.
    expect(await attCount(page)).toBe(0);

    // Send → the paste content IS the message content; zero attachments.
    const frame = await sendAndCapture(page);
    expect(frame.content).toBe(text);
    expect(frame.attachments).toEqual([]);
    expect(JSON.stringify(frame)).not.toContain('pasted-text-');
  });

  test('boundary =: exactly 65536 bytes → inline (≤ threshold takes the inline branch)', async ({ page }) => {
    await boot(page);
    const text = 'x'.repeat(65536); // exactly INLINE_PASTE_MAX_BYTES bytes
    await realPaste(page, text);

    expect(await defaultPrevented(page)).toBe(false);
    await expect(page.locator('#input')).toHaveValue(text);
    expect(await attCount(page)).toBe(0);

    const frame = await sendAndCapture(page);
    expect(frame.content).toBe(text);
    expect(frame.attachments).toEqual([]);
  });

  test('whitespace-only input counts as empty (spaces/newline/tabs) → inline; send trims to the paste body', async ({ page }) => {
    await boot(page);
    await page.locator('#input').fill('   \n\t  ');
    const text = 'WS '.repeat(700) + 'END'; // 2103 chars
    await realPaste(page, text);

    expect(await defaultPrevented(page)).toBe(false);
    expect(await attCount(page)).toBe(0);

    const frame = await sendAndCapture(page);
    expect(frame.content).toBe(text); // send() trims the whitespace-only prefix
    expect(frame.attachments).toEqual([]);
  });

  test('format preserved verbatim: paragraphs, blank lines, code, tabs — byte-identical into input and onto the wire', async ({ page }) => {
    await boot(page);
    // > 1000 chars so it exercises the paste-decision branch; structure-heavy.
    const para = 'first paragraph with 中文 mixed in\n';
    const code = 'function demo(a, b) {\n\t// tab-indented comment\n\tconst s = a + b;\n\tif (s > 0) return `tpl ${s}`;\n}\n';
    let text = '';
    while (text.length < 1800) text += para + '\n' + code + '\n\n';
    text = text.slice(0, text.lastIndexOf('\n', 1800));

    await realPaste(page, text);

    // Verbatim into the textarea — no wrapping, no fence, nothing added.
    await expect(page.locator('#input')).toHaveValue(text);
    expect(await attCount(page)).toBe(0);

    const frame = await sendAndCapture(page);
    expect(frame.content).toBe(text); // starts/ends non-space → trim is identity
    expect(frame.attachments).toEqual([]);
  });
});

// ============================================================
// R3 — above inline threshold: legacy conversion (unchanged)
// ============================================================

test.describe('R3 over-threshold conversion', () => {
  test('boundary >: 65537 bytes into empty input → file attachment, input stays empty, content intact', async ({ page }) => {
    await boot(page);
    const text = 'y'.repeat(65537); // exactly one byte over INLINE_PASTE_MAX_BYTES
    await realPaste(page, text);

    // Conversion branch: paste insertion prevented, input NOT filled…
    expect(await defaultPrevented(page)).toBe(true);
    await expect(page.locator('#input')).toHaveValue('');
    // …one pasted-text attachment materializes (addFileAttachment is async).
    await expect.poll(() => attCount(page), { timeout: 5000 }).toBe(1);
    // Feedback banner for the conversion, as before.
    await expect(page.locator('.attachment-banner')).toContainText('已转为文件附件');

    // Attachment carries the full content (base64 round-trip), named pasted-text-*.
    const att = await page.evaluate(async () =>
      (await import('/js/chatView.js')).chatViews.primary.pendingAttachments[0]);
    expect(att.name).toMatch(/^pasted-text-\d+\.txt$/);
    expect(att.size).toBe(65537);
    expect(Buffer.from(att.data, 'base64').toString('utf8')).toBe(text);
  });
});

// ============================================================
// R2 — paste + substantial typed text: legacy conversion (unchanged)
// ============================================================

test.describe('R2 paste with typed intent', () => {
  test('typed text + 2000-char paste → paste becomes attachment, typed text untouched', async ({ page }) => {
    await boot(page);
    await page.locator('#input').fill('解释这段代码：');
    const text = 'CODE '.repeat(400) + 'END'; // 2004 chars
    await realPaste(page, text);

    expect(await defaultPrevented(page)).toBe(true);
    await expect(page.locator('#input')).toHaveValue('解释这段代码：'); // intent untouched
    await expect.poll(() => attCount(page), { timeout: 5000 }).toBe(1);
    await expect(page.locator('.attachment-banner')).toContainText('已转为文件附件');
  });

  test('regression: typed text + small paste (≤1000 chars) still inserts inline — never converted', async ({ page }) => {
    await boot(page);
    await page.locator('#input').fill('看下这个：');
    const text = 'small '.repeat(80); // 480 chars, below LARGE_TEXT_THRESHOLD
    await realPaste(page, text);

    expect(await defaultPrevented(page)).toBe(false);
    await expect(page.locator('#input')).toHaveValue('看下这个：' + text);
    expect(await attCount(page)).toBe(0);
  });
});
