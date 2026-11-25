import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const path = 'src/main/resources/web/js/deviceIdentity.js';
const source = process.env.DEVICE_BASELINE
  ? execFileSync('git', ['show', `${process.env.DEVICE_BASELINE}:${path}`], { encoding: 'utf8' })
  : readFileSync(path, 'utf8');
const moduleSource = source
  .replace("import { key } from './branding.js';", "const key = (x) => x;")
  .replace("import { getDeviceRoster } from './deviceRoster.js';", 'const getDeviceRoster = async () => ({});');

test('device stamp includes reported zone and omits it when Intl fails', async () => {
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: 'Windows Chrome/1' } });
  globalThis.localStorage = { getItem: () => 'device-1', setItem: () => {} };
  const { deviceStamp } = await import(`data:text/javascript,${encodeURIComponent(moduleSource)}`);
  const original = Intl.DateTimeFormat;
  try {
    Intl.DateTimeFormat = () => ({ resolvedOptions: () => ({ timeZone: 'Asia/Tokyo' }) });
    const reported = JSON.parse(JSON.stringify(deviceStamp()));
    assert.equal(reported.timeZone, 'Asia/Tokyo');
    Intl.DateTimeFormat = () => { throw new Error('unavailable'); };
    const unavailable = JSON.parse(JSON.stringify(deviceStamp()));
    assert.equal(Object.hasOwn(unavailable, 'timeZone'), false);
    delete reported.timeZone;
    assert.deepEqual(unavailable, reported);
  } finally {
    Intl.DateTimeFormat = original;
  }
});
