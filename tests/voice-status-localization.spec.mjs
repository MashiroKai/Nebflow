// Focused regression for localized voice-mode status labels.
// Run: node tests/voice-status-localization.spec.mjs
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const browser = await chromium.launch();
const page = await browser.newPage();
await page.addInitScript(() => localStorage.setItem('nebflow_locale', 'en'));
await page.route('**/*', route => {
  const url = new URL(route.request().url());
  let path = decodeURIComponent(url.pathname);
  if (path === '/') path = '/index.html';
  const file = normalize(join(WEB, path));
  if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403 });
  try {
    return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
  } catch {
    return route.fulfill({ status: 404 });
  }
});
await page.goto('http://localhost:1/index.html');
const result = await page.evaluate(async () => {
  const i18n = await import('/js/i18n.js');
  const voice = await import('/js/voiceMode.js');
  const states = ['thinking', 'speaking', 'interrupted', 'error'];
  const en = Object.fromEntries(states.map(s => {
    const element = document.createElement('div');
    voice.renderVoiceStatus(element, s);
    return [s, element.textContent];
  }));
  i18n.setLocale('zh-CN');
  const zh = Object.fromEntries(states.map(s => {
    const element = document.createElement('div');
    voice.renderVoiceStatus(element, s);
    return [s, element.textContent];
  }));
  const errorElement = document.createElement('div');
  voice.renderVoiceStatus(errorElement, 'error', 'Permission denied raw detail');
  return { en, zh, rawError: errorElement.textContent };
});
await browser.close();

const expected = {
  en: { thinking: 'Thinking…', speaking: 'Speaking', interrupted: 'Interrupted', error: 'Voice unavailable. Check microphone permission or connection, then exit and try again.' },
  zh: { thinking: '正在思考…', speaking: '正在朗读', interrupted: '已打断', error: '语音暂不可用。请检查麦克风权限或网络连接，然后退出并重试。' },
};
for (const locale of ['en', 'zh']) {
  for (const state of Object.keys(expected[locale])) {
    if (result[locale][state] !== expected[locale][state]) {
      throw new Error(`${locale}/${state}: expected ${expected[locale][state]}, got ${result[locale][state]}`);
    }
  }
}
if (Object.values(result.zh).some(label => /Voice unavailable|Thinking|Speaking|Interrupted/.test(label))) {
  throw new Error('Chinese voice status exposed an untranslated English label');
}
if (result.rawError !== expected.zh.error) {
  throw new Error(`raw error detail leaked into localized status: ${result.rawError}`);
}
console.log('PASS localized voice status rendering (en + zh-CN, 4 states each; raw error suppressed)');
