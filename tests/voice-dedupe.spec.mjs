// voice-dedupe.spec.mjs — voice dictation duplicate-insert fix (2026-09-02).
//
// Author report: after editing (moving the caret / deleting text), the next
// voice dictation inserted duplicated content. Root causes fixed here:
//   WEB PATH  (a) the continuous Web Speech session stays live after the user
//   stops talking (Chrome auto-restarts on silence), so caret moves / deletes
//   between utterances shifted the value UNDER the [voiceAnchor,
//   voiceInterimLen) slot arithmetic — the next interim/final rewrote a STALE
//   range: the same fragment landed twice, or at the wrong place. Fix: input.js
//   re-anchors the slot on every user edit while dictating (input listener).
//   (b) recognition session state (final-commit index, pending interim) was
//   module-level and bled across instances: a stray event from an aborted
//   instance passed the shared dictating guard once the next session started
//   and re-committed the previous utterance's cumulative finals. Fix:
//   voiceEngine.js per-instance session state + retire-before-abort.
//   CLOUD PATH (c) starting a new cloud session while the previous session's
//   segment answer was still on the wire let the orphan answer be consumed by
//   the NEW session's pipeline (旧段+新段混插) and stall it. Fix: orphan-answer
//   skip (first consumed answer after a reset-with-orphan is the orphan by WS
//   FIFO).
//
// Harness: real index.html boot off an ephemeral-port static server (never
// touches the 8080 host or sbt). window.SpeechRecognition is a scripted mock
// driven through realistic Chrome event semantics (cumulative results,
// resultIndex re-reports, silence auto-restart); the cloud path runs the REAL
// voiceEngine pipeline against a fake AudioContext + fake WebSocket whose
// incoming frames the test dispatches (backend never involved).
//
// Run: npx playwright test tests/voice-dedupe.spec.mjs

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

/** Injected before any app script: mock SpeechRecognition + WS + audio stack. */
async function injectMocks(page) {
  await page.addInitScript(() => {
    // ── Fake WebSocket: captures outgoing frames, exposes incoming dispatch ──
    class FakeWS {
      constructor(url) {
        this.url = url; this.readyState = 1;
        FakeWS.instance = this; FakeWS.sent = FakeWS.sent || [];
        queueMicrotask(() => this.onopen && this.onopen({}));
      }
      send(d) { FakeWS.sent.push(d); }
      close() { this.readyState = 3; }
    }
    FakeWS.OPEN = 1;
    window.WebSocket = FakeWS;

    // ── Mock SpeechRecognition: property-style handlers, Chrome event shapes ──
    class MockSR {
      constructor() {
        this.lang = ''; this.continuous = false; this.interimResults = false;
        MockSR.instances.push(this);
      }
      start() {
        // onstart fires before any result (spec ordering contract); the mock
        // delivers it synchronously so tests can feed right after a click.
        this.onstart && this.onstart({});
        this.onaudiostart && this.onaudiostart({});
      }
      abort() { queueMicrotask(() => this.onend && this.onend({})); }
      stop() { queueMicrotask(() => this.onend && this.onend({})); }
    }
    MockSR.instances = [];
    window.SpeechRecognition = MockSR;

    // ── Fake AudioContext + getUserMedia (cloud path pipeline driver) ──
    class FakeAC {
      constructor() { this.sampleRate = 48000; this.destination = {}; }
      createMediaStreamSource() { return { connect() {} }; }
      createScriptProcessor() {
        const p = { onaudioprocess: null, connect() {}, disconnect() {} };
        FakeAC.processor = p;
        return p;
      }
      createGain() { return { gain: { value: 1 }, connect() {} }; }
      close() { return Promise.resolve(); }
    }
    window.AudioContext = FakeAC;
    if (!navigator.mediaDevices) {
      Object.defineProperty(navigator, 'mediaDevices', { value: {} });
    }
    navigator.mediaDevices.getUserMedia = async () => ({ getTracks: () => [{ stop() {} }] });

    // ── Test driver ──
    window.__voice = {
      // Feed a result event: parts = [{t: transcript, f: isFinal}], idx = resultIndex
      result(parts, idx) {
        const inst = MockSR.instances[MockSR.instances.length - 1];
        if (!inst || !inst.onresult) throw new Error('no live recognition instance');
        const results = parts.map((p) => ({ isFinal: !!p.f, length: 1, 0: { transcript: p.t } }));
        inst.onresult({ resultIndex: idx, results });
      },
      end() {
        const inst = MockSR.instances[MockSR.instances.length - 1];
        inst.onend && inst.onend({});
      },
      error(name) {
        const inst = MockSR.instances[MockSR.instances.length - 1];
        inst.onerror && inst.onerror({ error: name });
      },
      // Pump `seconds` of loud 48k chunks through the live ScriptProcessor —
      // real-time pacing so the 2s hard cut fires like production.
      async audio(seconds) {
        const proc = FakeAC.processor;
        if (!proc) throw new Error('no audio processor (dictation not started?)');
        const chunkMs = (4096 / 48000) * 1000;
        const n = Math.ceil((seconds * 1000) / chunkMs);
        for (let k = 0; k < n; k++) {
          if (!proc.onaudioprocess) return;
          proc.onaudioprocess({
            inputBuffer: { getChannelData: () => { const a = new Float32Array(4096); a.fill(0.3); return a; } },
          });
          await new Promise((r) => setTimeout(r, chunkMs + 10));
        }
      },
      sentFrames() { return (FakeWS.sent || []).map((s) => { try { return JSON.parse(s); } catch { return s; } }); },
      wsIncoming(msg) {
        const w = FakeWS.instance;
        if (!w || !w.onmessage) throw new Error('no ws onmessage wired');
        w.onmessage({ data: JSON.stringify(msg) });
      },
      instanceCount: () => MockSR.instances.length,
    };
  });
}

/** Boot the real app; dismiss first-run onboarding if it shows. */
async function boot(page) {
  await injectMocks(page);
  await page.goto(base + '/');
  await page.waitForSelector('#input', { timeout: 20000 });
  const skip = page.locator('#ob-skip');
  if (await skip.isVisible({ timeout: 1500 }).catch(() => false)) {
    await skip.click();
    await page.locator('.onboarding-overlay').waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
  }
}

const clickMic = (page) => page.locator('#voice-btn').click();
const boxValue = (page) => page.locator('#input').inputValue();
const feed = {
  result: (page, parts, idx) => page.evaluate(([p, i]) => window.__voice.result(p, i), [parts, idx]),
  end: (page) => page.evaluate(() => window.__voice.end()),
  audio: (page, sec) => page.evaluate((s) => window.__voice.audio(s), sec),
  ws: (page, msg) => page.evaluate((m) => window.__voice.wsIncoming(m), msg),
};
const countOf = (v, frag) => v.split(frag).length - 1;

/** Enable the cloud STT path at runtime (state.stt.sttConfigured). */
const enableCloud = (page) =>
  page.evaluate(async () => {
    const m = await import('/js/state.js');
    m.default.stt = { sttConfigured: true };
  });

const waitRecording = (page) =>
  page.waitForFunction(() => document.getElementById('voice-btn').classList.contains('recording'), null, { timeout: 5000 });

// ============================================================
// WEB PATH — between-session repro: dictate → stop → edit → dictate
// ============================================================

test.describe('web speech — edit then re-dictate (author repro)', () => {
  test('edit between sessions: zero duplication, insert lands at the caret', async ({ page }) => {
    await boot(page);

    // Session 1: dictate + final commit
    await clickMic(page);
    await feed.result(page, [{ t: '今天天气' }], 0);            // interim
    await expect(page.locator('#input')).toHaveValue('今天天气');
    await feed.result(page, [{ t: '今天天气不错', f: 1 }], 0);   // final commit
    await expect(page.locator('#input')).toHaveValue('今天天气不错 ');
    await clickMic(page); // stop

    // Edit: caret to index 4, delete one char (今天天气不错 → 今天天不错)
    await page.locator('#input').focus();
    await page.evaluate(() => document.getElementById('input').setSelectionRange(4, 4));
    await page.keyboard.press('Backspace');
    await expect(page.locator('#input')).toHaveValue('今天天不错 ');

    // Session 2: caret sits mid-text — dictation must insert THERE, once
    await clickMic(page);
    await feed.result(page, [{ t: '好的', f: 1 }], 0);
    await clickMic(page);

    const v = await boxValue(page);
    expect(v).toBe('今天天 好的 不错 '); // separator + insert at caret — not at the end
    expect(countOf(v, '好的')).toBe(1);

    // Insertion never auto-sends: no user-input frame (content field) on the wire
    const contentFrames = await page.evaluate(() =>
      window.__voice.sentFrames().filter((f) => typeof f.content === 'string'));
    expect(contentFrames).toEqual([]);
  });

  test('mid-caret start: draft goes at the caret, never appended to the end', async ({ page }) => {
    await boot(page);
    await page.locator('#input').fill('hello world');
    await page.locator('#input').focus();
    await page.evaluate(() => document.getElementById('input').setSelectionRange(5, 5));

    await clickMic(page); // separator space inserted at the caret
    await feed.result(page, [{ t: '测试', f: 1 }], 0);
    await clickMic(page);

    // caret sat at 5 → separator makes 'hello  world' (anchor 6) → the draft
    // lands BETWEEN the two spaces, exactly at the user's caret
    await expect(page.locator('#input')).toHaveValue('hello 测试  world');
  });
});

// ============================================================
// WEB PATH — mic still live while the user edits (the real-world trap)
// ============================================================

test.describe('web speech — edits while the mic is live', () => {
  test('typing/deleting during a live session re-anchors the draft; stop-flush inserts once', async ({ page }) => {
    await boot(page);

    await clickMic(page);
    await feed.result(page, [{ t: 'ABCDEF', f: 1 }], 0);
    await expect(page.locator('#input')).toHaveValue('ABCDEF ');
    // Mic NOT stopped — Chrome silence-restarts and stays live:
    await feed.end(page);

    // User edits while live: caret to 3, type XY, backspace one
    await page.locator('#input').focus();
    await page.evaluate(() => document.getElementById('input').setSelectionRange(3, 3));
    await page.keyboard.type('XY');         // ABCXYDEF
    await page.keyboard.press('Backspace'); // ABCXDEF, caret 4

    // Ambient-noise interim on the restarted session — must land at the caret
    await feed.result(page, [{ t: '嗯', f: 0 }], 0);
    await expect(page.locator('#input')).toHaveValue('ABCX嗯DEF ');

    // This click STOPS the live session (toggle) — pending interim flushes once
    await clickMic(page);
    await expect(page.locator('#input')).toHaveValue('ABCX嗯 DEF ');

    // Next click starts the real session 2
    await clickMic(page);
    await feed.result(page, [{ t: '测试', f: 1 }], 0);
    await clickMic(page);

    const v = await boxValue(page);
    expect(v).toBe('ABCX嗯 测试 DEF ');
    expect(countOf(v, '嗯')).toBe(1);
    expect(countOf(v, '测试')).toBe(1);
    expect(v).not.toContain('ABCDEF'); // the old utterance is never re-inserted
  });

  test('delete before the displayed draft + Chrome redraft → draft never appears twice', async ({ page }) => {
    await boot(page);

    await clickMic(page);
    await feed.result(page, [{ t: 'ABCDEF', f: 1 }], 0);
    await feed.end(page); // silence restart, mic stays live

    // Draft '嗯' displayed at the end (slot [7,8))
    await feed.result(page, [{ t: '嗯', f: 0 }], 0);
    await expect(page.locator('#input')).toHaveValue('ABCDEF 嗯');

    // User deletes a char BEFORE the draft — the true draft slides left by 1
    await page.locator('#input').focus();
    await page.evaluate(() => document.getElementById('input').setSelectionRange(3, 3));
    await page.keyboard.press('Backspace'); // deletes 'C' → 'ABDEF 嗯'

    // Chrome re-sends the same interim draft — must replace its OWN slot, not
    // stamp a second copy at the stale offset
    await feed.result(page, [{ t: '嗯', f: 0 }], 0);
    await expect(page.locator('#input')).toHaveValue('ABDEF 嗯');

    // Final commit for that utterance — still exactly one copy
    await feed.result(page, [{ t: '嗯', f: 1 }], 0);
    await clickMic(page);

    const v = await boxValue(page);
    expect(v).toBe('ABDEF 嗯 ');
    expect(countOf(v, '嗯')).toBe(1);
  });
});

// ============================================================
// WEB PATH — cumulative results semantics (#434 regression guard)
// ============================================================

test.describe('web speech — cumulative results lifecycle', () => {
  test('same-instance two rounds: final re-report commits once, restart resets cleanly', async ({ page }) => {
    await boot(page);

    await clickMic(page);
    await feed.result(page, [{ t: '你好' }], 0);              // interim
    await feed.result(page, [{ t: '你好世界' }], 0);          // interim grows (replaced)
    await expect(page.locator('#input')).toHaveValue('你好世界');
    await feed.result(page, [{ t: '你好世界', f: 1 }], 0);    // final commit
    await expect(page.locator('#input')).toHaveValue('你好世界 ');
    await feed.result(page, [{ t: '你好世界', f: 1 }], 0);    // Chrome re-report — no second commit
    await expect(page.locator('#input')).toHaveValue('你好世界 ');

    await feed.end(page); // silence → auto-restart: fresh result list, index 0 again
    await feed.result(page, [{ t: '很好', f: 1 }], 0);        // new round final at idx 0
    await clickMic(page);

    const v = await boxValue(page);
    expect(v).toBe('你好世界 很好 ');
    expect(countOf(v, '你好世界')).toBe(1);
    expect(countOf(v, '很好')).toBe(1);
  });

  test('final + new interim in one event, then stop: interim flushed exactly once', async ({ page }) => {
    await boot(page);

    await clickMic(page);
    await feed.result(page, [{ t: '第一句', f: 0 }], 0);
    await feed.result(page, [{ t: '第一句', f: 1 }, { t: '第二句', f: 0 }], 0);
    await expect(page.locator('#input')).toHaveValue('第一句 第二句');
    await clickMic(page); // strip displayed interim + engine flush → one copy

    const v = await boxValue(page);
    expect(v).toBe('第一句 第二句 ');
    expect(countOf(v, '第二句')).toBe(1);
  });

  test('stray result from a retired instance after re-dictation commits nothing', async ({ page }) => {
    await boot(page);

    // Session 1: commit an utterance, stop
    await clickMic(page);
    await feed.result(page, [{ t: '旧话', f: 1 }], 0);
    await clickMic(page);
    await expect(page.locator('#input')).toHaveValue('旧话 ');

    // Session 2 starts immediately — a queued result event from the RETIRED
    // instance lands after the new session is live (Chrome can deliver events
    // queued before abort()). It must be dropped wholesale.
    await clickMic(page);
    await page.evaluate(() => {
      const MockSR = window.SpeechRecognition;
      const retiredInst = MockSR.instances[MockSR.instances.length - 2];
      const results = [{ isFinal: true, length: 1, 0: { transcript: '旧话' } }];
      retiredInst.onresult({ resultIndex: 0, results });
    });
    await feed.result(page, [{ t: '新话', f: 1 }], 0);
    await clickMic(page);

    const v = await boxValue(page);
    expect(v).toBe('旧话 新话 ');
    expect(countOf(v, '旧话')).toBe(1);
  });
});

// ============================================================
// CLOUD PATH — real voiceEngine pipeline over fake audio + fake WS
// ============================================================

test.describe('cloud STT — streaming pipeline', () => {
  test('interim display → stop re-hang → final commit inserts exactly once', async ({ page }) => {
    await boot(page);
    await enableCloud(page);

    await clickMic(page);
    await waitRecording(page);
    await feed.audio(page, 2.6);            // ~2s → hard cut → segment 1 submitted
    await feed.ws(page, { type: 'transcription', text: '前段' });
    await expect(page.locator('#input')).toHaveValue('前段'); // live interim

    await clickMic(page); // stop: interim stripped, then re-hung by cloudStop
    await expect(page.locator('#input')).toHaveValue('前段'); // re-hung, still once
    await feed.ws(page, { type: 'transcription', text: '尾巴' }); // tail answer → finalize

    const v = await boxValue(page);
    expect(v).toBe('前段尾巴 ');
    expect(countOf(v, '前段')).toBe(1);
    expect(countOf(v, '尾巴')).toBe(1);
  });

  test('orphan answer from the previous session never lands in the new dictation', async ({ page }) => {
    await boot(page);
    await enableCloud(page);

    // Session 1: segment submitted; stop queues the tail (blocked by in-flight)
    await clickMic(page);
    await waitRecording(page);
    await feed.audio(page, 2.6);
    await clickMic(page); // stop session 1 — its segment answer still on the wire

    // Session 2 starts while session 1's answer is in flight → orphan armed
    await clickMic(page);
    await waitRecording(page);
    await feed.audio(page, 2.6);            // session 2 segment submitted
    await clickMic(page);                   // stop session 2 — tail queued

    // The ORPHAN (session 1) answer arrives first (WS FIFO) — must be discarded
    await feed.ws(page, { type: 'transcription', text: '旧尾巴' });
    // Session 2's real answers follow
    await feed.ws(page, { type: 'transcription', text: '新话' });
    await feed.ws(page, { type: 'transcription', text: '新尾' });

    const v = await boxValue(page);
    expect(v).toBe('新话新尾 ');
    expect(v).not.toContain('旧尾巴'); // 旧段+新段混插 is the bug being pinned
    expect(countOf(v, '新话')).toBe(1);
    expect(countOf(v, '新尾')).toBe(1);
  });
});
