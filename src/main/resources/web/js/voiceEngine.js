// voiceEngine.js — Voice dictation via Web Speech API OR cloud STT proxy.
//
// Dual mode (#295, user ruling 2026-08-18):
//   1. Cloud STT (serverConfig.stt.sttConfigured === true): getUserMedia →
//      PCM capture → sliced incremental submit → WS {type:'transcribe',
//      audio, language} → transcription {text|error}. The apiKey lives
//      server-side only — the frontend never touches it.
//   2. Browser Web Speech (default, zero config): Chrome/Edge/Safari built-in
//      recognition. No API key, no backend dependency, no model download.
//
// Cloud streaming (2026-08-21, user feedback 「边说边出字」): the cloud ASR
// (MiMo chat/completions) is whole-utterance with no streaming API, so the
// frontend slices the recording — every 1.5-2.0s (or on energy-VAD silence)
// a segment is cut and submitted through the existing WS transcribe endpoint
// (backend untouched). Segments flow through a SERIALIZED pipeline (the next
// submit fires only after the previous answer lands) so ordering is
// guaranteed by construction — the wire protocol carries no seq echo. Interim
// display = concatenation of finalized segments; on stop the unsubmitted tail
// merges with any queued segments into one final request. Segments shorter
// than 0.3s with low energy are dropped as pure noise.
//
// Public API:
//   startDictation(cb)  → start listening; transcribe until stopDictation()
//   stopDictation()     → stop listening
//   isModelReady()      → always true (browser handles everything)
//
// Callbacks (all optional):
//   { onText(text), onState(state, data), onInterim(text) }
//
// States: 'idle' | 'listening' | 'speaking' | 'processing' | 'error'

import { getLocale, t } from './i18n.js';
import state from './state.js';
import { sendWs, onMessage } from './ws.js';

const SpeechRecognitionAPI =
  window.SpeechRecognition || window.webkitSpeechRecognition;

// Cloud STT: target sample rate for the WAV we send upstream. 16 kHz mono
// 16-bit PCM is the de-facto standard for speech-to-text providers.
const CLOUD_SAMPLE_RATE = 16000;

let recognition = null;
let dictating = false;
const cb = {};

// Cloud recording state — one push-to-talk session at a time.
let cloudRec = null; // { stream, ctx, processor, token, recorded }

// Orphan-answer skip (2026-09-02 dup fix): when a session starts while the
// PREVIOUS session still has a segment request on the wire (stop cut a tail,
// its answer hasn't landed), that orphan answer must never be consumed by the
// new session's pipeline — it would insert the old utterance into the new
// dictation (旧段+新段一起插入) and the real answer that follows (WS is FIFO)
// would be dropped, stalling the pipeline. The wire protocol carries no
// request id, so we skip the FIRST consumed answer after a reset-with-orphan:
// it is the orphan by FIFO construction. The orphan's text itself is lost
// (same as the pre-fix drop) — the guarantee is the NEW session stays clean.
let skipNextAnswer = false;

// Streaming slice parameters (user feedback 2026-08-21: 边说边出字).
const SEG_MIN_MS = 1500;   // never cut a segment shorter than this (time cut)
const SEG_MAX_MS = 2000;   // hard time-based cut
const SILENCE_MS = 450;    // sustained silence that triggers an early cut
const SILENCE_RMS = 0.01;  // chunk RMS below this counts as silence

/* Mic volume hook for the orb (v8.2.2 spec §10.5): the listening state shakes
   with mic loudness — the orb registers a listener receiving a normalized
   volume [0,1] per audio chunk (RMS above the silence floor, ×10 gain). */
let volumeListener = null;
/** @param {((v: number) => void) | null} fn */
export function setMicVolumeListener(fn) { volumeListener = typeof fn === 'function' ? fn : null; }
const NOISE_MIN_MS = 300;  // segments shorter than this AND low-energy = noise
const NOISE_RMS = 0.012;
const SEG_TIMEOUT_MS = 20000;

// Streaming session state (reset on cloudStart).
let segBuf = null;    // { samples: Float32Array[], len, startMs, silentMs, sumSq, n }
let segQueue = [];    // downsampled 16k Float32Array segments awaiting submit
let segFinals = [];   // finalized segment texts, in order
let segInFlight = false;
let segStopping = false;
let segTimer = null;  // watchdog for the in-flight segment request

function getLang() {
  const loc = getLocale();
  if (loc.startsWith('zh')) return 'zh-CN';
  if (loc.startsWith('en')) return 'en-US';
  return loc.replace('-', '_');
}

function useCloud() {
  return !!(state.stt && state.stt.sttConfigured);
}

export function isModelReady() {
  return true;
}

// ── Cloud path: PCM capture → WAV → WS transcribe ─────────────────────

/**
 * Map a getUserMedia DOMException to a user-visible, actionable i18n message.
 * The raw error name is appended so support/debugging keeps ground truth.
 * (2026-08-20 #stt-hotfix: errors were previously console-only — the user
 * saw nothing when the mic was denied or held by another tab/app.)
 */
function classifyMicError(e) {
  const name = e?.name || '';
  const raw = e?.message || name || 'UnknownError';
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError')
    return t('stt.micDenied') + ' (' + name + ')';
  if (name === 'NotReadableError' || name === 'TrackStartError' || name === 'OverconstrainedError')
    return t('stt.micBusy') + ' (' + name + ')';
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError' || name === 'ConstraintNotSatisfiedError')
    return t('stt.micNoDevice') + ' (' + name + ')';
  return raw;
}

async function cloudStart() {
  // An in-flight (or queued) request from a previous session means its answer
  // is still on the wire — arm the orphan skip so the new session cannot
  // consume it. Watchdog bounds the flag so a silent server can't make the
  // new session swallow its own first answer forever.
  if (segInFlight || segQueue.length) {
    skipNextAnswer = true;
    setTimeout(() => { skipNextAnswer = false; }, SEG_TIMEOUT_MS + 5000);
  }
  resetStreaming(); // a fresh recording invalidates any stale pipeline state
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    cb.onState?.('error', classifyMicError(e));
    return;
  }
  const Ctx = window.AudioContext || window['webkitAudioContext'];
  if (!Ctx) {
    stream.getTracks().forEach(t => t.stop());
    cb.onState?.('error', 'AudioContext not supported in this browser.');
    return;
  }
  const ctx = new Ctx();
  const source = ctx.createMediaStreamSource(stream);
  const processor = ctx.createScriptProcessor(4096, 1, 1);
  // ScriptProcessorNode only fires onaudioprocess while connected to
  // destination — route through a zero-gain node so the mic never feeds back
  // to the speakers.
  const zero = ctx.createGain();
  zero.gain.value = 0;
  const token = Date.now() + '_' + Math.random().toString(36).slice(2);
  cloudRec = { stream, ctx, processor, token, recorded: 0 };
  processor.onaudioprocess = (e) => {
    if (cloudRec && cloudRec.token === token) {
      onMicChunk(ctx, new Float32Array(e.inputBuffer.getChannelData(0)));
    }
  };
  source.connect(processor);
  processor.connect(zero);
  zero.connect(ctx.destination);
  cb.onState?.('listening');
}

// ── Streaming slice pipeline (2026-08-21: 边说边出字) ────────────────────

/** Locale-aware joiner for segment texts (zh concatenates, en spaces). */
function joinParts(parts) {
  const clean = parts.map(p => (p || '').trim()).filter(Boolean);
  return clean.join(getLang().startsWith('zh') ? '' : ' ');
}

function resetStreaming() {
  segBuf = null;
  segQueue = [];
  segFinals = [];
  segInFlight = false;
  segStopping = false;
  if (segTimer) { clearTimeout(segTimer); segTimer = null; }
}

/** Accumulate a mic chunk; energy-VAD + time budget decide when to cut. */
function onMicChunk(ctx, chunk) {
  if (segStopping) return; // stop pressed — slicing frozen, tail already cut
  cloudRec.recorded += chunk.length;
  if (!segBuf) {
    segBuf = { samples: [], len: 0, startMs: Date.now(), silentMs: 0, sumSq: 0, n: 0 };
  }
  const chunkMs = (chunk.length / ctx.sampleRate) * 1000;
  let sumSq = 0;
  for (let i = 0; i < chunk.length; i++) sumSq += chunk[i] * chunk[i];
  const rms = Math.sqrt(sumSq / Math.max(1, chunk.length));
  if (volumeListener) volumeListener(Math.min(1, Math.max(0, (rms - SILENCE_RMS) * 10)));
  segBuf.samples.push(chunk);
  segBuf.len += chunk.length;
  segBuf.sumSq += sumSq;
  segBuf.n += chunk.length;
  segBuf.silentMs = rms < SILENCE_RMS ? segBuf.silentMs + chunkMs : 0;

  const dur = Date.now() - segBuf.startMs;
  if (dur >= SEG_MAX_MS || (dur >= SEG_MIN_MS && segBuf.silentMs >= SILENCE_MS)) {
    cutSegment(ctx);
  }
}

/** Close the current segment: noise-drop or enqueue, then pump the pipeline. */
function cutSegment(ctx) {
  if (!segBuf) return;
  const buf = segBuf;
  segBuf = null;
  const dur = Date.now() - buf.startMs;
  const rms = Math.sqrt(buf.sumSq / Math.max(1, buf.n));
  const all = new Float32Array(buf.len);
  let off = 0;
  for (const c of buf.samples) { all.set(c, off); off += c.length; }
  if (dur < NOISE_MIN_MS && rms < NOISE_RMS) return; // pure noise — drop
  const samples = ctx.sampleRate !== CLOUD_SAMPLE_RATE
    ? downsample(all, ctx.sampleRate, CLOUD_SAMPLE_RATE)
    : all;
  if (samples.length === 0) return;
  segQueue.push(samples);
  // While stopping, cloudStop owns the submit choreography (merge-then-pump);
  // pumping here would send queued segments one-by-one before the merge.
  if (!segStopping) pumpSegments();
}

/** Serialized pipeline: at most one segment request in flight — ordering is
 *  guaranteed by construction (the wire protocol carries no seq echo).
 *  NOTE: merge-on-stop happens in cloudStop (single submit point). By the time
 *  the pump runs while stopping there is exactly one tail request left. */
function pumpSegments() {
  if (segInFlight) return;
  if (segQueue.length === 0) return; // finalize lives in the transcription handler
  const seg = segQueue.shift();
  const wav = encodeWav(seg, CLOUD_SAMPLE_RATE);
  const audioB64 = bufferToBase64(wav);
  segInFlight = true;
  segTimer = setTimeout(() => {
    if (segInFlight) {
      segInFlight = false;
      abortStreaming(t('stt.timeout'));
    }
  }, SEG_TIMEOUT_MS);
  sendWs({ type: 'transcribe', audio: audioB64, language: getLang() });
}

function abortStreaming(errMsg) {
  const rec = cloudRec;
  cloudRec = null;
  if (rec) {
    try { rec.processor.disconnect(); } catch (_) {}
    try { sourceTracks(rec.stream); } catch (_) {}
    try { rec.ctx.close(); } catch (_) {}
  }
  resetStreaming();
  cb.onState?.('error', errMsg);
}

/** Stop path with nothing left to submit: finalize whatever segments said. */
function finishStreamingNoTail() {
  const rec = cloudRec;
  cloudRec = null;
  const recorded = rec ? rec.recorded : 0;
  if (rec) {
    try { rec.processor.disconnect(); } catch (_) {}
    try { sourceTracks(rec.stream); } catch (_) {}
    try { rec.ctx.close(); } catch (_) {}
  }
  const text = joinParts(segFinals);
  resetStreaming();
  if (recorded === 0) {
    // Mic opened but onaudioprocess never fired, or push-to-talk too short
    // (#stt-hotfix: surface instead of silently dropping to idle).
    cb.onState?.('error', t('stt.noAudio'));
    return;
  }
  if (text) cb.onText?.(text);
  cb.onState?.('idle');
}

/** Stop recording. The unsubmitted tail is cut once (noise rule applies),
 *  any queued segments merge into ONE final-bound request (tail latency ≤ 2
 *  requests: the in-flight one + the merged tail), and the transcription
 *  handler finalizes the whole utterance when the last answer lands. */
function cloudStop() {
  const rec = cloudRec;
  if (!rec) return;
  segStopping = true;
  // Cut the unsubmitted tail once; slicing freezes afterwards (onMicChunk
  // guards on cloudRec which survives until finalize/abort).
  cutSegment(rec.ctx);
  // Merge the remaining queue into a single tail request (stop is the only
  // place that merges — the pump sees at most one queued segment afterwards).
  if (segQueue.length > 1) {
    const total = segQueue.reduce((n, s) => n + s.length, 0);
    const merged = new Float32Array(total);
    let off = 0;
    for (const s of segQueue) { merged.set(s, off); off += s.length; }
    segQueue = [merged];
  }
  // input.js strips the interim slot synchronously on stopVoice — re-hang the
  // finalized segments right away so the box never goes blank while the tail
  // request drains (the final onText appends the tail text atomically).
  if (segFinals.length) cb.onInterim?.(joinParts(segFinals));
  if (segQueue.length === 0 && !segInFlight) {
    finishStreamingNoTail();
    return;
  }
  pumpSegments(); // send the lone queued tail when the pipeline is idle
}

function sourceTracks(stream) {
  stream.getTracks().forEach(tr => tr.stop());
}

// The backend answers {type:'transcription', text} or {type:'transcription',
// error} — no sessionId, no seq echo. The serialized pipeline (one request in
// flight) makes arrival order = segment order, so each answer appends to the
// finalized list and refreshes the interim display (边说边出字). The LAST
// answer (stopping + queue drained) finalizes synchronously via onText —
// input.js strips the interim slot on stopVoice, so only onText survives it.
onMessage('transcription', (msg) => {
  // First answer after a reset-with-orphan IS the orphan (WS FIFO) — whether
  // it lands before or after the new session's first submit. Consume the skip
  // flag on arrival so the new session's own answers are never swallowed.
  if (skipNextAnswer) {
    // Orphan frame — pure no-op. segInFlight still belongs to the live
    // request (pumping the queue here would reorder the tail ahead of the
    // live answer and finalize the utterance prematurely). The live request's
    // real answer follows next (WS FIFO) and flows through the normal path.
    skipNextAnswer = false;
    return;
  }
  if (!segInFlight) return; // stale / not ours
  segInFlight = false;
  if (segTimer) { clearTimeout(segTimer); segTimer = null; }
  if (msg.error) {
    abortStreaming(msg.error);
    return;
  }
  const text = (msg.text || '').trim();
  if (text) segFinals.push(text);
  if (segStopping && segQueue.length === 0) {
    finishStreamingNoTail(); // last answer — commit the whole utterance
    return;
  }
  if (!segStopping) cb.onInterim?.(joinParts(segFinals));
  pumpSegments(); // next queued segment
});

// ── WAV encoding helpers ───────────────────────────────────────────────

/** Linear-interpolation downsample (e.g. 48k → 16k). */
function downsample(input, fromRate, toRate) {
  if (fromRate === toRate) return input;
  const ratio = fromRate / toRate;
  const outLen = Math.floor(input.length / ratio);
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const pos = i * ratio;
    const idx = Math.floor(pos);
    const frac = pos - idx;
    const a = input[idx] || 0;
    const b = input[idx + 1] || 0;
    out[i] = a + (b - a) * frac;
  }
  return out;
}

/** Mono 16-bit PCM WAV (RIFF/WAVE). */
function encodeWav(samples, sampleRate) {
  const bytesPerSample = 2;
  const dataSize = samples.length * bytesPerSample;
  const buffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buffer);
  const writeStr = (o, s) => { for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);          // fmt chunk size
  view.setUint16(20, 1, true);           // PCM
  view.setUint16(22, 1, true);           // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * bytesPerSample, true);
  view.setUint16(32, bytesPerSample, true);
  view.setUint16(34, 16, true);          // bits per sample
  writeStr(36, 'data');
  view.setUint32(40, dataSize, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return buffer;
}

function bufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

// ── Web Speech path ───────────────────────────────────────────────────

/**
 * Retire the live recognition instance: mark it DEAD first, then abort.
 * Chrome can deliver result/end events that were queued BEFORE abort() took
 * effect — with the old code those stray events passed the shared `dictating`
 * guard once the next session had started and re-committed the previous
 * utterance's cumulative finals at the new session's anchor (the duplicate the
 * user saw after editing between dictations). Per-instance `alive` closes that
 * window deterministically: a retired instance can never commit or restart.
 */
function retireRecognition() {
  if (!recognition) return;
  const r = recognition;
  recognition = null;
  if (r.__vs) r.__vs.alive = false;
  try { r.abort(); } catch (_) {}
}

export async function startDictation(callbacks = {}) {
  Object.assign(cb, callbacks);

  // Cloud STT configured → record + proxy path.
  if (useCloud()) {
    await cloudStart();
    return;
  }

  if (!SpeechRecognitionAPI) {
    cb.onState?.('error', 'Browser does not support speech recognition. Use Chrome, Edge, or Safari.');
    return;
  }

  retireRecognition(); // any previous instance must not leak into this session

  const inst = new SpeechRecognitionAPI();
  inst.lang = getLang();
  inst.continuous = true;
  inst.interimResults = true;

  // Per-instance session state. The old module-level markers (lastFinalIdx /
  // lastInterim) were shared across instances: an aborted instance's late
  // event re-scanned its cumulative results with a freshly-reset final index
  // and committed them into whatever session was active — duplication.
  const vs = { alive: false, finalIdx: -1, interim: '' };
  inst.__vs = vs;

  inst.onstart = () => {
    vs.alive = true;
    vs.finalIdx = -1; // fresh result list — commit finals from index 0 again
    vs.interim = '';
    dictating = true;
    cb.onState?.('listening');
  };

  inst.onaudiostart = () => {
    if (vs.alive) cb.onState?.('listening');
  };

  inst.onspeechstart = () => {
    if (vs.alive) cb.onState?.('speaking');
  };

  inst.onresult = (event) => {
    // Retired instance (already aborted) or post-stop stray — never commit.
    // The `alive` flag is per-instance: it stays false even after a NEW
    // session flips the shared `dictating` flag back on.
    if (!vs.alive || !dictating) return;
    // event.results is a CUMULATIVE list; resultIndex may re-report slots that
    // already fired (S2: the same final committed repeatedly → "为什么为什么…")
    // and multiple non-final slots may coexist (S3: stale drafts summed into
    // "…我输…" fragments). Two mitigations:
    //   1. Commit a final only once — skip indexes ≤ finalIdx.
    //   2. Stream only the NEWEST (last) result as interim — never the sum.
    let interim = '';
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (i <= vs.finalIdx) continue; // already committed this session
      const result = event.results[i];
      if (result.isFinal) {
        const text = result[0].transcript.trim();
        if (text) {
          vs.finalIdx = i;
          vs.interim = '';
          cb.onText?.(text);
          if (dictating) cb.onState?.('listening');
        }
      }
    }
    const lastRes = event.results[event.results.length - 1];
    if (lastRes && !lastRes.isFinal) {
      interim = lastRes[0].transcript;
    }
    // Track and stream interim text to UI for real-time display
    if (interim) {
      vs.interim = interim;
      if (dictating) cb.onInterim?.(interim);
    } else {
      vs.interim = '';
    }
  };

  inst.onerror = (event) => {
    const err = event.error || 'unknown';
    // 'no-speech' and 'aborted' are not real errors during dictation
    if (err === 'no-speech' || err === 'aborted') {
      if (dictating && vs.alive) cb.onState?.('listening');
      return;
    }
    console.error('[voiceEngine] Speech recognition error:', err);
    cb.onState?.('error', err);
    if (dictating && vs.alive) {
      // Try to restart after a brief error
      try { inst.start(); } catch (_) {}
    }
  };

  inst.onend = () => {
    // Auto-restart if still dictating (browser stops after silence). The
    // retired-instance case is excluded by vs.alive — only the CURRENT live
    // instance may restart, so a late 'end' from an aborted instance can
    // never resurrect a dead session.
    if (dictating && vs.alive) {
      try { inst.start(); } catch (_) {}
    } else if (!dictating) {
      cb.onState?.('idle');
    }
  };

  recognition = inst;

  try {
    inst.start();
  } catch (e) {
    // If already started, ignore
    if (e.name !== 'InvalidStateError') {
      cb.onState?.('error', e.message);
    }
  }
}

export function stopDictation() {
  dictating = false;
  // Orb volume hook (v8.2.2): zero the shake amplitude on stop so the orb
  // does not freeze at the last loudness until the next listening session.
  if (volumeListener) volumeListener(0);
  // Cloud path: stop recording + send the captured WAV for transcription.
  if (cloudRec) {
    cloudStop();
    return;
  }
  // Flush any pending interim text as final before stopping. input.js has
  // already stripped the displayed interim slot synchronously in stopVoice —
  // this re-inserts the final text exactly once at that slot.
  const vs = recognition?.__vs;
  if (vs && vs.interim) {
    const pending = vs.interim;
    vs.interim = '';
    cb.onText?.(pending.trim());
  }
  retireRecognition();
}
