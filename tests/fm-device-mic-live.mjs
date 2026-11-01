// fm-device-mic-live.mjs — 设备面板 mic **真实录音链路**实测（fm-threeface-align 批
// 2026-11-02 · 作者令：设备面补语音输入后必须证明「真的在录」，而不是桩）。
//
// 被测面：src/main/resources/web/js/messages/fmComposer.js 的麦克风腿（设备会话窗
// conv.kind === 'device'）：
//   click → startDictation → onInterim/onText/onState ⇒ 文本落 .fm-input。
// 语音引擎 = 既有单点 src/main/resources/web/js/voiceEngine.js（零改源码、零第二套机制）。
//
// 真链路（**不许 mock 掉录音**）：
//   ① Chromium 真采集：launch args 挂 --use-file-for-fake-audio-capture=<本脚本现场生成的
//      16kHz/mono/16bit WAV>（+ --use-fake-device-for-media-stream / --use-fake-ui-for-media-stream
//      / --autoplay-policy=no-user-gesture-required），WAV = 纯 node 手写 RIFF 头 +
//      440Hz 正弦 + 轻噪声，3.0s，振幅 0.32（显著高于静音）。
//   ② 云 STT 腿激活：WS 桩 serverConfig 帧带 {"stt":{"sttConfigured":true,"sttEnabled":true}}
//      （main.js:2700 `if (msg.stt)` 在 serverConfig 处理器里消费 ⇒ voiceEngine.useCloud()
//      为真 ⇒ 走 getUserMedia → PCM → WAV → WS，而非 Web Speech 腿）。
//   ③ WS 桩接 transcribe：收到 {type:'transcribe', audio:<base64>, language} ⇒ **解码并断言**
//      合法 RIFF/WAVE、16000Hz、单声道、16bit、样本非全零（报 RMS 实测值），WAV 原始字节
//      落盘可用播放器复核；然后回 {type:'transcription', text}。
//
// 断言（硬）：
//   M1 设备窗输入条存在 .fm-mic-btn（类名/aria-label/aria-pressed 初值 false）
//   M2 点击后 micBtn 带 recording 类且 aria-pressed=true
//   M3 真收到 ≥1 条 transcribe 帧，解码 WAV 采样 RMS > 0.01（物理证据：真的在录）
//   M4 再点一次（关闭）→ 出现 processing 类后收敛，input.fm-input 的 value 含桩回文本
//   M5 发送键 .fm-send-btn 随 value 非空放开（非 disabled）
//   M6 零 console error / 零 pageerror（语音错误必须用户可见，静默失败 = FAIL）
//   M7 三面对照：.fm-mic-btn 在好友 cF / 群 g-1 / 设备 dev:peer-1 三面同款在场
//
// REST/WS 桩照抄 tests/attcard-unify-shots.mjs 的三面板夹具（openConv =
// #messages-btn → #fm-conversations .fm-conv-row[data-conversation-id]），设备会话
// cid='dev:peer-1'，/api/neblink/status 里 device.id='self-1'、peers 含 peer-1。
//
// Run: NODE_PATH=/opt/homebrew/lib/node_modules node tests/fm-device-mic-live.mjs
//   MIC_EVID=<dir>     证据落盘目录（缺省 <repo>/evidence/20261102_fm-threeface-align/mic）
//   MIC_HEADED=1       有头兜底（headless 采集不到音频时用；本脚本默认 headless，
//                      真采集失败会自动降级重试一次有头再判 FAIL）

import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { mkdirSync, writeFileSync, readdirSync, unlinkSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const EVID = process.env.MIC_EVID || join(ROOT, 'evidence', '20261102_fm-threeface-align', 'mic');
mkdirSync(EVID, { recursive: true });
// 上一轮的产物段数/命名可能与本轮不同 ⇒ 先清本脚本自有产物，保证证据目录只含本轮读数
// （RUN.txt 是人工落盘的运行记录，由本轮命令重新生成，不在此清）。
for (const f of readdirSync(EVID)) {
  if (/^transcribe-seg\d+\.wav$/.test(f)
    || /^fake-input-.*\.wav$/.test(f)
    || f === 'readings.json') { try { unlinkSync(join(EVID, f)); } catch { /* ignore */ } }
}

let failures = 0;
const readings = { startedAt: new Date().toISOString(), segments: [], wsFrameTypes: {}, micTrace: [], notes: {} };
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
  return !!cond;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));
const iso = s => new Date(Date.now() + s * 1000).toISOString();

// ═══════════════════════════════════════════════════════════════════════════
// 1) 假音频 WAV 生成（纯 node：手写 RIFF 头 + 语音型信号）
// ═══════════════════════════════════════════════════════════════════════════
const FAKE_RATE = 16000;   // Chromium --use-file-for-fake-audio-capture 认 16k/mono/16bit
// 文件长度必须覆盖整个录音窗：Chromium 播完文件后只输出静音（残余噪声 ~0.02 RMS）。
const FAKE_SECONDS = 12.0;
const TONE_HZ = 440;
const TONE_AMP = 0.32;     // 远高于静音（voiceEngine SILENCE_RMS = 0.01）

/**
 * 语音型假音频（**关键**：getUserMedia 的默认音频处理——AGC + noiseSuppression +
 * echoCancellation——会把**稳态单音**判定为噪声/回声并压下去，实测稳态 440Hz 正弦
 * 首段后 RMS 从 0.27 掉到 0.025，落进噪声底，证据就成了「勉强过线」）。
 * 因此这里生成**非稳态**信号：音节包络（~4Hz 振幅调制）+ 音高滑动 + 谐波 +
 * 词间静音间隙 —— 与真人说话一样是时变的，短时能量分析不会被当成稳态噪声。
 * @returns {Buffer} 完整 RIFF/WAVE 字节（44 字节头 + PCM data）
 */
function makeFakeAudioWav({ sampleRate = FAKE_RATE, seconds = FAKE_SECONDS, amp = TONE_AMP, hz = TONE_HZ } = {}) {
  const n = Math.round(sampleRate * seconds);
  const dataSize = n * 2;
  const buf = Buffer.alloc(44 + dataSize);
  buf.write('RIFF', 0, 'latin1');
  buf.writeUInt32LE(36 + dataSize, 4);
  buf.write('WAVE', 8, 'latin1');
  buf.write('fmt ', 12, 'latin1');
  buf.writeUInt32LE(16, 16);        // fmt chunk size
  buf.writeUInt16LE(1, 20);         // PCM
  buf.writeUInt16LE(1, 22);         // mono
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 2, 28); // byte rate
  buf.writeUInt16LE(2, 32);         // block align
  buf.writeUInt16LE(16, 34);        // bits per sample
  buf.write('data', 36, 'latin1');
  buf.writeUInt32LE(dataSize, 40);
  const SYLL = 0.22;                // 音节周期 220ms（≈4.5 音节/秒，接近正常语速）
  const PHRASE = SYLL * 6;          // 每 6 个音节后一个词间停顿
  const GAP = 0.18;                 // 停顿占比（秒）
  let seed = 0x2f6e2b1;
  for (let i = 0; i < n; i++) {
    const t = i / sampleRate;
    // 音节包络：半波整流正弦（起音快、收音慢）
    const inPhrase = (t % PHRASE) < (PHRASE - GAP);
    const sylT = (t % SYLL) / SYLL;
    const env = inPhrase ? Math.pow(Math.sin(Math.PI * sylT), 0.7) : 0;
    // 音高滑动：基频在 180..260Hz 之间随音节缓慢游走（时变 ⇒ 不是稳态音）
    const f0 = 185 + 70 * Math.abs(Math.sin(2 * Math.PI * 0.7 * t)) + 12 * Math.sin(2 * Math.PI * 3 * t);
    const ph = 2 * Math.PI * f0 * t;
    // 谐波叠加（近似元音）
    const tone = Math.sin(ph) * 0.62 + Math.sin(2 * ph) * 0.24 + Math.sin(3 * ph) * 0.12;
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    const noise = ((seed / 0x7fffffff) * 2 - 1) * 0.01;   // ±1% 底噪：避开纯合成分
    const v = Math.max(-1, Math.min(1, amp * env * tone + noise));
    buf.writeInt16LE(v < 0 ? Math.round(v * 0x8000) : Math.round(v * 0x7fff), 44 + i * 2);
  }
  return buf;
}

// ═══════════════════════════════════════════════════════════════════════════
// 2) 上传 WAV 解码断言（真解 RIFF 头 + 真算 RMS）
// ═══════════════════════════════════════════════════════════════════════════
/** Goertzel 单位功率估计（单频幅值，不依赖 FFT 库）。 */
function goertzel(x, rate, freq) {
  const k = 2 * Math.PI * freq / rate;
  const w = 2 * Math.cos(k);
  let s1 = 0, s2 = 0;
  for (let i = 0; i < x.length; i++) { const s0 = x[i] + w * s1 - s2; s2 = s1; s1 = s0; }
  return Math.sqrt(s1 * s1 + s2 * s2 - w * s1 * s2) / x.length;
}

/** 主频扫描（80..2000Hz，5Hz 步进）——用于证明「录到的就是我喂进去的 440Hz 音源」
 *  （不是环境噪声、不是静音垫底）。 */
function dominantHz(samples, rate) {
  let best = { f: 0, m: 0 };
  for (let f = 80; f <= 2000; f += 5) {
    const m = goertzel(samples, rate, f);
    if (m > best.m) best = { f, m };
  }
  return { hz: best.f, mag: +best.m.toFixed(4) };
}

/** @returns {{riff:string,wave:string,audioFormat:number,channels:number,sampleRate:number,
 *             bits:number,dataSize:number,samples:number,rms:number,nonZero:number,peak:number,
 *             durationMs:number,chunks:string[],dominantHz:number,dominantMag:number}} */
function parseWav(buf) {
  const out = {
    bytes: buf.length,
    riff: buf.toString('latin1', 0, 4),
    wave: buf.toString('latin1', 8, 12),
    chunks: [],
    audioFormat: null, channels: null, sampleRate: null, bits: null,
    dataOffset: null, dataSize: 0, samples: 0, rms: 0, nonZero: 0, peak: 0, durationMs: 0,
  };
  let off = 12;
  while (off + 8 <= buf.length) {
    const id = buf.toString('latin1', off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    out.chunks.push(id);
    if (id === 'fmt ') {
      out.audioFormat = buf.readUInt16LE(off + 8);
      out.channels = buf.readUInt16LE(off + 10);
      out.sampleRate = buf.readUInt32LE(off + 12);
      out.byteRate = buf.readUInt32LE(off + 16);
      out.blockAlign = buf.readUInt16LE(off + 20);
      out.bits = buf.readUInt16LE(off + 22);
    } else if (id === 'data') {
      out.dataOffset = off + 8;
      out.dataSize = size;
    }
    off += 8 + size + (size & 1);
  }
  if (out.dataOffset === null) return out; // 非 WAVE/坏帧：rms 保持 0 ⇒ 断言必红
  const d = out.dataOffset;
  const sz = Math.min(out.dataSize, buf.length - d);
  let sumSq = 0, n = 0, nz = 0, peak = 0;
  for (let i = 0; i + 1 < sz; i += 2) {
    const v = buf.readInt16LE(d + i) / 32768;
    if (v !== 0) nz++;
    const a = Math.abs(v);
    if (a > peak) peak = a;
    sumSq += v * v;
    n++;
  }
  out.samples = n;
  out.rms = n ? Math.sqrt(sumSq / n) : 0;
  out.nonZero = nz;
  out.peak = peak;
  out.durationMs = out.sampleRate ? Math.round((n / out.sampleRate) * 1000) : 0;
  // 主频读数（只看前 0.5s，避开文件播完后的噪声尾巴）
  if (n > 0 && out.sampleRate) {
    const head = [];
    const take = Math.min(n, out.sampleRate / 2);
    for (let i = 0; i < take; i++) head.push(buf.readInt16LE(d + i * 2) / 32768);
    const dom = dominantHz(head, out.sampleRate);
    out.dominantHz = dom.hz;
    out.dominantMag = dom.mag;
    // 分段 RMS（100ms 窗）→ 时间调制比：稳恒音 / 静音垫底的比值≈1，真人语音型信号
    // 的音节包络会给出 ≥2 的高低差。
    const win = Math.round(out.sampleRate * 0.1);
    const wins = [];
    for (let o = 0; o + win <= n; o += win) {
      let s = 0;
      for (let i = 0; i < win; i++) { const v = buf.readInt16LE(d + (o + i) * 2) / 32768; s += v * v; }
      wins.push(Math.sqrt(s / win));
    }
    out.windowRms = wins.map(v => +v.toFixed(3));
    const lo = Math.min(...wins), hi = Math.max(...wins);
    out.modRatio = lo > 0 ? +(hi / lo).toFixed(2) : (hi > 0 ? Infinity : 0);
  } else {
    out.dominantHz = 0;
    out.dominantMag = 0;
    out.windowRms = [];
    out.modRatio = 0;
  }
  return out;
}

// ── 假音频文件落盘（路径不含空格：Chromium switch 值解析更稳）─────────────
const FAKE_DIR = join(tmpdir(), 'fm-device-mic-live');
mkdirSync(FAKE_DIR, { recursive: true });
const FAKE_WAV_PATH = join(FAKE_DIR, 'fake-mic-16k-mono.wav');
const fakeWav = makeFakeAudioWav();
writeFileSync(FAKE_WAV_PATH, fakeWav);
writeFileSync(join(EVID, 'fake-input-16k-mono-speechlike.wav'), fakeWav); // 复核：输入音源本身
const fakeSelf = parseWav(fakeWav);
console.log(`fake mic wav · ${FAKE_WAV_PATH} · ${fakeWav.length}B · ${fakeSelf.sampleRate}Hz/${fakeSelf.channels}ch/${fakeSelf.bits}bit · rms=${fakeSelf.rms.toFixed(4)} · dom=${fakeSelf.dominantHz}Hz · modRatio=${fakeSelf.modRatio}`);
readings.fakeInput = {
  path: FAKE_WAV_PATH, bytes: fakeWav.length, sampleRate: fakeSelf.sampleRate,
  channels: fakeSelf.channels, bits: fakeSelf.bits, samples: fakeSelf.samples,
  rms: +fakeSelf.rms.toFixed(5), dominantHz: fakeSelf.dominantHz,
  modRatio: fakeSelf.modRatio, durationMs: fakeSelf.durationMs,
};

// ═══════════════════════════════════════════════════════════════════════════
// 3) REST/WS 桩夹具（照抄 tests/attcard-unify-shots.mjs 的三面板夹具）
// ═══════════════════════════════════════════════════════════════════════════
const EPOCH = Date.now();
const FRIEND = { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', remark: null, since: iso(-86400) };
const row = (id, senderId, at, atts) => ({ id, senderId, kind: 'text', body: '', createdAt: iso(at), attachments: atts });
const deviceRow = (id, senderDeviceId, atMs, atts) => ({ id, senderDeviceId, kind: 'text', body: '', createdAtMs: EPOCH + atMs, attachments: atts });
const formsFor = (p) => [row(10 * p + 1, 'u-p', -3600, [])];
const devFormsFor = (p) => [deviceRow(10 * p + 1, 'peer-1', -3600000, [])];
const MSGS = { cF: formsFor(1), 'g-1': formsFor(2), 'dev:peer-1': devFormsFor(3) };
const CONVS = [
  { conversationId: 'cF', friend: FRIEND, lastMessage: MSGS.cF[0], unreadCount: 0 },
  { conversationId: 'dev:peer-1', kind: 'device', deviceId: 'peer-1', lastMessage: MSGS['dev:peer-1'][0], unreadCount: 0 },
  { conversationId: 'dev:self-1', kind: 'device', deviceId: 'self-1', lastMessage: null, unreadCount: 0 },
];
const GROUPS = [{ groupId: 'g-1', title: '项目群', unreadCount: 0, memberCount: 3, role: 'owner', memberAvatars: [], lastMessage: MSGS['g-1'][0] }];

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.mp4': 'video/mp4',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const STUB_TEXT = '设备面板语音实测';

/** 把当前已到的 transcribe 帧快照进 readings（含 WAV 落盘路径 + 全部解码读数）。
 *  在 M3 断言处与脚本收尾各调一次 —— 关闭后的尾段是 processing 期间才提交的，
 *  只快照一次会漏掉它。 */
function snapshotSegments() {
  readings.segments = transcribeFrames.map(f => ({
    index: f.index, bytes: f.bytes, wav: f.wavPath, language: f.language,
    riff: f.parsed.riff, wave: f.parsed.wave, audioFormat: f.parsed.audioFormat,
    sampleRate: f.parsed.sampleRate, channels: f.parsed.channels, bits: f.parsed.bits,
    samples: f.parsed.samples, durationMs: f.parsed.durationMs,
    rms: +f.parsed.rms.toFixed(5), peak: +f.parsed.peak.toFixed(4), nonZero: f.parsed.nonZero,
    dominantHz: f.parsed.dominantHz, dominantMag: f.parsed.dominantMag,
    modRatio: f.parsed.modRatio, windowRms: f.parsed.windowRms,
  }));
}
const wsFrames = [];          // 入站帧 type 序列
const transcribeFrames = [];  // {index, language, bytes, wavPath, parsed}

// ═══════════════════════════════════════════════════════════════════════════
// 4) 浏览器启动（真采集：file 档假音频优先）
// ═══════════════════════════════════════════════════════════════════════════
const LAUNCH_ARGS = [
  '--use-fake-ui-for-media-stream',
  '--use-fake-device-for-media-stream',
  '--autoplay-policy=no-user-gesture-required',
  `--use-file-for-fake-audio-capture=${FAKE_WAV_PATH}`,
];
const HEADED = process.env.MIC_HEADED === '1';
console.log(`launch · headless=${!HEADED} · args=${JSON.stringify(LAUNCH_ARGS)}`);
readings.launchArgs = LAUNCH_ARGS;
readings.headless = !HEADED;

const browser = await chromium.launch({ args: LAUNCH_ARGS, headless: !HEADED });
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    if (p.startsWith('/agents/')) {
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}'); return;
    }
    const file = join(WEB, p === '/' ? 'index.html' : p);
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

async function bootPage({ viewport = { width: 1280, height: 900 } } = {}) {
  const ctx = await browser.newContext({ colorScheme: 'light', viewport });
  try { await ctx.grantPermissions(['microphone'], { origin: BASE }); } catch { /* fake-ui 档已自动授权 */ }
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  const consoleWarns = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300));
    if (m.type() === 'warning') consoleWarns.push(m.text().slice(0, 300));
  });
  page.on('pageerror', e => pageErrors.push(String(e).slice(0, 300)));

  await page.route('**/api/**', async (route) => {
    const u = new URL(route.request().url());
    const p = u.pathname;
    if (p === '/api/neblink/status') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        loggedIn: true,
        relay: { available: true, authRejected: false, lastRejectedStatusCode: null, lastRejectedAt: null, selfHeal: 'not-attempted' },
        device: { id: 'self-1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'qa@example.com' },
        peers: [{ deviceId: 'peer-1', deviceName: '书房 Mac', name: '书房 Mac', platform: 'macos', online: true, directOnline: true, avatarUrl: '' }],
      }) });
    }
    if (p === '/api/conversations') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONVS) });
    if (p === '/api/friends') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ friends: [FRIEND], incoming: [], outgoing: [] }) });
    if (p === '/api/groups') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GROUPS) });
    if (p === '/api/groups/invites') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ incoming: [] }) });
    const mm = p.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (mm) {
      const cid = decodeURIComponent(mm[1]);
      const after = Math.max(0, Math.floor(Number(u.searchParams.get('after')) || 0));
      const rows = (MSGS[cid] || []).filter(x => Number(x.id) > after);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(async (raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      wsFrames.push(m.type);
      if (m.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
        return;
      }
      if (m.type === 'transcribe') {
        // ── 真解码断言（不是「收到就回」的哑桩）────────────────────────────
        const idx = transcribeFrames.length + 1;
        const buf = Buffer.from(String(m.audio || ''), 'base64');
        const wavPath = join(EVID, `transcribe-seg${idx}.wav`);
        const parsed = parseWav(buf);
        transcribeFrames.push({ index: idx, language: m.language, bytes: buf.length, wavPath, parsed });
        try { await writeFile(wavPath, buf); } catch { /* 落盘失败不影响断言 */ }
        const valid = parsed.riff === 'RIFF' && parsed.wave === 'WAVE' && parsed.audioFormat === 1
          && parsed.channels === 1 && parsed.sampleRate === 16000 && parsed.bits === 16
          && parsed.samples > 0 && parsed.nonZero > 0 && parsed.rms > 0.01;
        console.log(`WS transcribe #${idx} · ${buf.length}B · ${parsed.riff}/${parsed.wave} · ${parsed.sampleRate}Hz/${parsed.channels}ch/${parsed.bits}bit · ${parsed.samples} samples · ${parsed.durationMs}ms · rms=${parsed.rms.toFixed(4)} · peak=${(parsed.peak || 0).toFixed(3)} · nonZero=${parsed.nonZero} · dom=${parsed.dominantHz}Hz@${parsed.dominantMag} · mod=${parsed.modRatio} · ${valid ? 'VALID' : 'INVALID'}`);
        ws.send(JSON.stringify({ type: 'transcription', text: STUB_TEXT })); // 真后端 SttService 同形回帧
        return;
      }
      if (m.type === 'getConfig') {
        ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
        return;
      }
    });
    // 夹具帧：configData（好友闸）+ serverConfig（**stt 节点** = 云 STT 腿唯一开关；
    // main.js:2700 `if (msg.stt)` 消费面在 serverConfig 处理器里）
    ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: 'test', thinking: 'off', models: [], defaults: {}, stt: { sttConfigured: true, sttEnabled: true } }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#messages-btn', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, consoleErrors, pageErrors, consoleWarns };
}

async function openConv(page, cid) {
  await page.click('#messages-btn'); await sleep(600);
  await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(900);
}

/** 点 mic 键。录音态 = `recording`（微信绿 + voicePulse 呼吸动画，input.css:693）⇒
 *  Playwright 的「element is not stable」等不到位（持续位移动画），故走 force:true ——
 *  真正派发的仍是浏览器真实鼠标事件（坐标 = 元素中心），只是跳过稳定性轮询。 */
const clickMic = (page) => page.click('#fm-chat-overlay .fm-mic-btn', { force: true });

/** mic 键 + 态机观测器：MutationObserver（class / aria-pressed）+ 15ms 采样双通道，
 *  防止极短 processing 窗口被丢。 */
function installMicProbe() {
  const btn = document.querySelector('#fm-chat-overlay .fm-mic-btn');
  if (!btn) return false;
  window.__micTrace = [];
  const push = (why) => window.__micTrace.push({
    t: Date.now(), why,
    cls: btn.className,
    recording: btn.classList.contains('recording'),
    processing: btn.classList.contains('processing'),
    pressed: btn.getAttribute('aria-pressed'),
  });
  push('init');
  const mo = new MutationObserver(() => push('mut'));
  mo.observe(btn, { attributes: true, attributeFilter: ['class', 'aria-pressed'] });
  window.__micProbeStop = () => { mo.disconnect(); clearInterval(window.__micPoll); };
  window.__micPoll = setInterval(() => {
    const last = window.__micTrace[window.__micTrace.length - 1];
    const cur = btn.className + '|' + btn.getAttribute('aria-pressed');
    if (!last || (last.cls + '|' + last.pressed) !== cur) push('poll');
  }, 15);
  return true;
}

// ═══════════════════════════════════════════════════════════════════════════
// 5) 主流程
// ═══════════════════════════════════════════════════════════════════════════
const DEV_CID = 'dev:peer-1';

try {
  console.log(`fm-device-mic-live · WEB=${WEB} · EVID=${EVID}`);
  const { ctx, page, consoleErrors, pageErrors, consoleWarns } = await bootPage();
  try {
    // ── 前置：云 STT 腿必须已激活（否则落 Web Speech 腿 = 本实测无效）─────────
    const sttState = await page.evaluate(async () => (await import('/js/state.js')).default.stt);
    ok('前置：云 STT 腿已激活（state.stt.sttConfigured === true）',
      !!sttState && sttState.sttConfigured === true, JSON.stringify(sttState));
    readings.sttState = sttState;

    await openConv(page, DEV_CID);

    // ── M1 ────────────────────────────────────────────────────────────────
    const m1 = await page.evaluate(() => {
      const bar = document.querySelector('#fm-chat-overlay .fm-input-bar');
      const btn = bar && bar.querySelector('.fm-mic-btn');
      const r = btn ? btn.getBoundingClientRect() : null;
      return {
        bar: !!bar,
        btn: !!btn,
        input: !!document.querySelector('#fm-chat-overlay input.cfg-input.fm-input'),
        cls: btn ? btn.className : '',
        isIconBtn: btn ? btn.classList.contains('icon-btn') : false,
        ariaLabel: btn ? btn.getAttribute('aria-label') : '',
        ariaPressed: btn ? btn.getAttribute('aria-pressed') : null,
        title: btn ? btn.getAttribute('title') : '',
        visible: r ? (r.width > 0 && r.height > 0) : false,
        // lucide `mic`：`<i data-lucide="mic">` 被 createIconsIn 换成 `<svg class="lucide lucide-mic">`
        // （两种都算「真挂上 mic 图标」）；记录实际形态供复核。
        iconTag: btn && btn.firstElementChild ? btn.firstElementChild.tagName.toLowerCase() : '',
        iconSvgOrPlaceholder: btn ? !!btn.querySelector('svg.lucide-mic, i[data-lucide="mic"]') : false,
      };
    });
    ok('M1 设备窗输入条存在 .fm-mic-btn（.icon-btn 族 + lucide mic + 可见）',
      m1.bar && m1.btn && m1.isIconBtn && m1.iconSvgOrPlaceholder && m1.visible && m1.input, JSON.stringify(m1));
    ok('M1 aria-label 在场 且 aria-pressed 初值 = "false"',
      !!m1.ariaLabel && m1.ariaPressed === 'false', JSON.stringify({ label: m1.ariaLabel, pressed: m1.ariaPressed }));
    readings.m1 = m1;

    const probeOk = await page.evaluate(installMicProbe);
    ok('观测器：mic 态机探针已挂载', probeOk === true);

    // ── M2：开始录音 ───────────────────────────────────────────────────────
    await clickMic(page);
    const recOk = await page.waitForFunction(() => {
      const b = document.querySelector('#fm-chat-overlay .fm-mic-btn');
      return !!b && b.classList.contains('recording') && b.getAttribute('aria-pressed') === 'true';
    }, null, { timeout: 8000 }).then(() => true).catch(() => false);
    const m2 = await page.evaluate(() => {
      const b = document.querySelector('#fm-chat-overlay .fm-mic-btn');
      const cs = getComputedStyle(b);
      return { cls: b.className, pressed: b.getAttribute('aria-pressed'), bg: cs.backgroundColor, w: +b.getBoundingClientRect().width.toFixed(1) };
    });
    ok('M2 点击后 .fm-mic-btn 出现 recording 类且 aria-pressed=true',
      recOk && m2.cls.includes('recording') && m2.pressed === 'true', JSON.stringify(m2));
    readings.m2 = m2;

    // 录音中态实拍 + 等真采集进 WS（段切点 SEG_MAX_MS = 2000ms/段）
    const deadline = Date.now() + 12000;
    while (transcribeFrames.length < 2 && Date.now() < deadline) await sleep(120);
    const overlay = await page.$('#fm-chat-overlay');
    if (overlay) await overlay.screenshot({ path: join(EVID, 'mic-recording.png') }).catch(() => {});
    console.log('SHOT mic-recording.png');

    // ── M3：真收到 transcribe 帧并解码断言 ─────────────────────────────────
    const seg1 = transcribeFrames[0] ? transcribeFrames[0].parsed : null;
    ok('M3a 真收到 ≥1 条 WS transcribe 帧', transcribeFrames.length >= 1, `segments=${transcribeFrames.length}`);
    ok('M3b 解码帧 = 合法 RIFF/WAVE · 16000Hz · 单声道 · 16bit · 样本非全零',
      !!seg1 && seg1.riff === 'RIFF' && seg1.wave === 'WAVE' && seg1.audioFormat === 1
      && seg1.sampleRate === 16000 && seg1.channels === 1 && seg1.bits === 16
      && seg1.samples > 0 && seg1.nonZero > 0, JSON.stringify(seg1 && {
        riff: seg1.riff, wave: seg1.wave, fmt: seg1.audioFormat, rate: seg1.sampleRate,
        ch: seg1.channels, bits: seg1.bits, samples: seg1.samples, nonZero: seg1.nonZero, ms: seg1.durationMs,
      }));
    ok('M3c 解码后采样 RMS > 0.01（真的在录：非静音、非桩）',
      !!seg1 && seg1.rms > 0.01, `rms=${seg1 ? seg1.rms.toFixed(5) : 'n/a'} peak=${seg1 ? seg1.peak.toFixed(4) : 'n/a'}`);
    ok('M3d transcribe 帧带 language 字段（契约面 {type,audio,language}）',
      transcribeFrames.length > 0 && typeof transcribeFrames[0].language === 'string' && transcribeFrames[0].language.length > 0,
      JSON.stringify(transcribeFrames.map(f => ({ i: f.index, lang: f.language, bytes: f.bytes, rms: +f.parsed.rms.toFixed(4), ms: f.parsed.durationMs }))));
    // M3e（加强证据）：首段主频落在合成音源的基频带 150..600Hz，且段内存在明显时间调制
    // （音节包络 ⇒ 分段 RMS 高低差 ≥2×）—— 证明录到的是本脚本喂入的时变信号文件，
    // 不是环境宽带噪声、不是静音垫底、不是桩回定值。
    ok('M3e 首段主频落在喂入音源基频带 150..600Hz（非环境噪声）',
      !!seg1 && seg1.dominantHz >= 150 && seg1.dominantHz <= 600 && seg1.dominantMag > 0.01,
      `dominant=${seg1 ? seg1.dominantHz : 'n/a'}Hz mag=${seg1 ? seg1.dominantMag : 'n/a'}（合成源基频 185..267Hz）`);
    ok('M3f 段内能量随时间调制（音节包络：分段 RMS 高低差 ≥2×，证明非稳态真采集）',
      !!seg1 && seg1.modRatio >= 2,
      `分段 RMS 比（max/min）= ${seg1 ? seg1.modRatio : 'n/a'} · 分段读数=${JSON.stringify(seg1 ? seg1.windowRms : [])}`);
    snapshotSegments(); // M3 时刻的快照（关闭后尾段到达时会再刷一次，见收尾）

    // ── M4：再点一次（关闭）→ processing → 收敛 → 文本落输入框 ───────────────
    await clickMic(page);
    const landed = await page.waitForFunction((txt) => {
      const i = document.querySelector('#fm-chat-overlay input.cfg-input.fm-input');
      return !!i && i.value.includes(txt);
    }, STUB_TEXT, { timeout: 15000 }).then(() => true).catch(() => false);
    await sleep(500); // 让态机收敛（onState('idle')）后读数

    const m4 = await page.evaluate(() => {
      const b = document.querySelector('#fm-chat-overlay .fm-mic-btn');
      const i = document.querySelector('#fm-chat-overlay input.cfg-input.fm-input');
      return {
        value: i ? i.value : null,
        micCls: b ? b.className : '',
        pressed: b ? b.getAttribute('aria-pressed') : null,
        trace: window.__micTrace || [],
      };
    });
    readings.micTrace = m4.trace.map(x => ({ t: x.t, why: x.why, cls: x.cls, recording: x.recording, processing: x.processing, pressed: x.pressed }));
    const sawProcessing = m4.trace.some(x => x.processing);
    const converged = !m4.micCls.includes('recording') && !m4.micCls.includes('processing') && m4.pressed === 'false';
    ok('M4a 关闭瞬间出现 processing 类（转写中态）', sawProcessing,
      `trace=${JSON.stringify(m4.trace.map(x => `${x.why}:${x.cls}|pressed=${x.pressed}`))}`);
    ok('M4b 态机收敛（recording/processing 双双摘除，aria-pressed 回 false）', converged,
      JSON.stringify({ cls: m4.micCls, pressed: m4.pressed }));
    ok('M4c 转写文本落到输入框 input.fm-input.value',
      landed && typeof m4.value === 'string' && m4.value.includes(STUB_TEXT), JSON.stringify({ value: m4.value }));
    readings.m4 = { value: m4.value, micCls: m4.micCls, pressed: m4.pressed, sawProcessing, converged };

    // ── M5：发送键随 value 非空放开 ─────────────────────────────────────────
    const m5 = await page.evaluate(() => {
      const b = document.querySelector('#fm-chat-overlay .fm-send-btn');
      const i = document.querySelector('#fm-chat-overlay input.cfg-input.fm-input');
      return { btn: !!b, disabled: b ? b.disabled : null, cls: b ? b.className : '', valueLen: i ? i.value.length : 0 };
    });
    ok('M5 发送键 .fm-send-btn 随 value 非空放开（非 disabled）',
      m5.btn && m5.disabled === false && m5.valueLen > 0, JSON.stringify(m5));
    readings.m5 = m5;

    // 转写落字后实拍
    const overlay2 = await page.$('#fm-chat-overlay');
    if (overlay2) await overlay2.screenshot({ path: join(EVID, 'mic-transcribed.png') }).catch(() => {});
    console.log('SHOT mic-transcribed.png');

    await page.evaluate(() => { try { window.__micProbeStop && window.__micProbeStop(); } catch { /* ignore */ } });

    // ── M7：三面 .fm-mic-btn 同款在场 ───────────────────────────────────────
    const faceProbe = {};
    const probeFace = async () => page.evaluate(() => {
      const b = document.querySelector('#fm-chat-overlay .fm-mic-btn');
      const bar = document.querySelector('#fm-chat-overlay .fm-input-bar');
      return {
        bar: !!bar, btn: !!b, cls: b ? b.className : '',
        pressed: b ? b.getAttribute('aria-pressed') : null,
        ariaLabel: b ? b.getAttribute('aria-label') : '',
        iconSvgOrPlaceholder: b ? !!b.querySelector('svg.lucide-mic, i[data-lucide="mic"]') : false,
      };
    });
    faceProbe[DEV_CID] = await probeFace();
    for (const cid of ['cF', 'g-1']) {
      await page.click('.fm-modal-close').catch(async () => { await page.keyboard.press('Escape'); });
      await sleep(400);
      await page.click(`#fm-conversations .fm-conv-row[data-conversation-id="${cid}"]`); await sleep(800);
      faceProbe[cid] = await probeFace();
    }
    const allThree = ['cF', 'g-1', DEV_CID].every(c => faceProbe[c] && faceProbe[c].btn
      && faceProbe[c].cls.includes('icon-btn') && faceProbe[c].cls.includes('fm-mic-btn')
      && faceProbe[c].iconSvgOrPlaceholder && faceProbe[c].ariaLabel && faceProbe[c].pressed === 'false');
    ok('M7 三面 .fm-mic-btn 同款在场（好友 cF / 群 g-1 / 设备 dev:peer-1）', allThree, JSON.stringify(faceProbe));
    readings.m7 = faceProbe;

    // ── M6：零 console error / 零 pageerror ────────────────────────────────
    await sleep(300);
    ok('M6a 零 console error', consoleErrors.length === 0, JSON.stringify(consoleErrors));
    ok('M6b 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    readings.m6 = { consoleErrors, pageErrors, consoleWarns: consoleWarns.slice(0, 20) };
    readings.wsFrameTypes = wsFrames.reduce((a, t) => (a[t] = (a[t] || 0) + 1, a), {});
  } finally {
    await ctx.close();
  }
} catch (e) {
  failures++;
  console.error('HARNESS ERROR:', (e && e.stack) || e);
  readings.harnessError = String((e && e.message) || e);
} finally {
  try { await browser.close(); } catch { /* non-critical */ }
  try { await new Promise(r => server.close(r)); } catch { /* non-critical */ }
  snapshotSegments(); // 收尾再刷一次：关闭后的尾段（processing 期间提交）也纳入证据
  readings.finishedAt = new Date().toISOString();
  readings.failures = failures;
  readings.verdict = failures === 0 ? 'PASS' : 'FAIL';
  writeFileSync(join(EVID, 'readings.json'), JSON.stringify(readings, null, 2));
  console.log(`\n=== fm-device-mic-live: failures=${failures} · verdict=${readings.verdict} · segments=${transcribeFrames.length} · EVID=${EVID} ===`);
  process.exit(failures === 0 ? 0 : 1);
}
