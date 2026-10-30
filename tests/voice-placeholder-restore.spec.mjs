// voice-placeholder-restore.spec.mjs — 语音模式退出后输入框占位文本不恢复修复
// （作者报障 2026-10-30 12:55）：「进入语音模式之后会改变输入框的占位文本，退出
// 语音模式的时候占位文本不会变回之前的形式，一直卡在语音输入情况下的文本」。
//
// 根因：input.js startVoice() 把 #input.placeholder 直写为 t('input.ph.listening')
// （聆听中…），而所有退出路径（点击切换 / Esc / 拖动全双工的 ensureDictationIdle）
// 汇入的 stopVoice() 只清了 voiceActive/interim/类名，没有任何占位文本恢复调用
// —— 之后若再无 input/附件条事件，placeholder 永远卡在聆听态。修复 = stopVoice()
// 尾部归一调用 updateInputPlaceholder()（voiceActive 已 false，回落附件/纯引用
// 帧闸/通用文案单点）。
//
// 断言面：
//   T1 点击进语音 ⇒ placeholder 变为「聆听中…」；再点退出 ⇒ 恢复原占位文本；
//   T2 Esc 退出 ⇒ 占位文本同样恢复（退出路径覆盖）；
//   T3 语音中切走再切回（social 配置弹窗开/点外关；单会话架构无会话轨、好友面
//      默认封存）⇒ 切回时仍在聆听态（不误恢复），退出后零残留。
//
// Harness 同 voice-dedupe.spec.mjs：真实 index.html 静态伺服 + MockSR(Web Speech)
// + FakeWS（configData/sessionList 帧），零后端。
// Run: npx playwright test tests/voice-placeholder-restore.spec.mjs

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

/** Injected before any app script: mock SpeechRecognition + WS (config/session frames). */
async function injectMocks(page) {
  await page.addInitScript(() => {
    class FakeWS {
      constructor(url) {
        this.url = url; this.readyState = 1;
        FakeWS.instance = this; FakeWS.sent = FakeWS.sent || [];
        queueMicrotask(() => {
          this.onopen && this.onopen({});
          this.onmessage && this.onmessage({ data: JSON.stringify({
            type: 'configData',
            config: JSON.stringify({ features: { voice: true } }),
            configured: true, onboarding: 'done', models: [], defaults: {},
          }) });
          this.onmessage && this.onmessage({ data: JSON.stringify({
            type: 'sessionList',
            sessions: [
              { id: 's1', agentName: 'Nebula', name: 'Session One', title: 'T', updatedAt: Date.now() },
              { id: 's2', agentName: 'Nebula', name: 'Session Two', title: 'T', updatedAt: Date.now() },
            ],
            activeId: 's1', folders: [],
          }) });
        });
      }
      send(d) { FakeWS.sent.push(d); }
      close() { this.readyState = 3; }
    }
    FakeWS.OPEN = 1;
    window.WebSocket = FakeWS;

    // Mock SpeechRecognition: property-style handlers, Chrome event shapes
    // （voice-dedupe 同款最小 mock —— 本 spec 只驱动启动/停止，不喂识别结果）。
    class MockSR {
      constructor() {
        this.lang = ''; this.continuous = false; this.interimResults = false;
        MockSR.instances.push(this);
      }
      start() {
        this.onstart && this.onstart({});
        this.onaudiostart && this.onaudiostart({});
      }
      abort() { queueMicrotask(() => this.onend && this.onend({})); }
      stop() { queueMicrotask(() => this.onend && this.onend({})); }
    }
    MockSR.instances = [];
    window.SpeechRecognition = MockSR;
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

const placeholderOf = (page) =>
  page.evaluate(() => document.getElementById('input')?.getAttribute('placeholder') ?? null);

/** Exact i18n strings, read from the page (locale-independent assertions). */
const i18nPlaceholders = (page) =>
  page.evaluate(async () => {
    const m = await import('/js/i18n.js');
    return { fallback: m.t('input.placeholder'), listening: m.t('input.ph.listening') };
  });

const clickMic = (page) => page.locator('#voice-btn').click();
const waitDictating = (page) =>
  page.waitForFunction(() => document.getElementById('input')?.classList.contains('voice-dictating'), null, { timeout: 5000 });

test.describe('voice placeholder — enter/exit restore (author report 2026-10-30)', () => {
  test('T1 click toggle: enter changes placeholder to listening; exit restores the fallback text', async ({ page }) => {
    await boot(page);
    const ph = await i18nPlaceholders(page);

    await expect.poll(() => placeholderOf(page)).toBe(ph.fallback);

    await clickMic(page);
    await waitDictating(page);
    await expect.poll(() => placeholderOf(page)).toBe(ph.listening);

    await clickMic(page); // exit
    await page.waitForFunction(() => !document.getElementById('input')?.classList.contains('voice-dictating'), null, { timeout: 5000 });
    // The fix: stopVoice() restores the composer placeholder through
    // updateInputPlaceholder() — never left stuck on the listening text.
    await expect.poll(() => placeholderOf(page)).toBe(ph.fallback);
  });

  test('T2 Escape exit: placeholder restored through the same stopVoice() path', async ({ page }) => {
    await boot(page);
    const ph = await i18nPlaceholders(page);

    await clickMic(page);
    await waitDictating(page);
    await expect.poll(() => placeholderOf(page)).toBe(ph.listening);

    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.getElementById('input')?.classList.contains('voice-dictating'), null, { timeout: 5000 });
    await expect.poll(() => placeholderOf(page)).toBe(ph.fallback);
  });

  test('T3 mid-dictation panel switch away and back: no premature restore, zero residue after exit', async ({ page }) => {
    await boot(page);
    const ph = await i18nPlaceholders(page);

    await clickMic(page);
    await waitDictating(page);
    await expect.poll(() => placeholderOf(page)).toBe(ph.listening);

    // 切走再切回：单会话架构下侧栏无会话轨（sidebar.js:2043 注释 session
    // list DOM removed），好友面默认封存（activityBar.js detachFriendEntries）
    // —— 产品上可达的「切页面」= 打开 social 配置弹窗再点外关窗（Esc 与听写
    // 停止键同键冲突，故用 overlay 点外关闭路径）。dictation 仍在飞 ⇒ placeholder
    // 必须仍是聆听态（不误恢复）；退出后归零残留 —— 作者报障的「切走再切回也
    // 残留」面。
    await page.locator('#social-btn').click();
    await page.waitForFunction(() => document.getElementById('social-overlay')?.classList.contains('on'), null, { timeout: 5000 });
    // 点外关窗（e.target === overlay 的关闭路径；不碰 Esc 以免同键停止听写）
    await page.evaluate(() => document.getElementById('social-overlay')?.click());
    await page.waitForFunction(() => !document.getElementById('social-overlay')?.classList.contains('on'), null, { timeout: 5000 });
    // 切回后听写仍然 live（voice-dictating 在位 = 引擎未被切页面误关）
    await expect.poll(() => placeholderOf(page)).toBe(ph.listening);

    await clickMic(page); // exit
    await page.waitForFunction(() => !document.getElementById('input')?.classList.contains('voice-dictating'), null, { timeout: 5000 });
    await expect.poll(() => placeholderOf(page)).toBe(ph.fallback);
  });
});
