// voiceEngine.js — Voice dictation via Web Speech API OR cloud STT proxy.
//
// Dual mode (#295, user ruling 2026-08-18):
//   1. Cloud STT (serverConfig.stt.sttConfigured === true): getUserMedia →
//      PCM capture → WAV (16 kHz mono) → base64 → WS {type:'transcribe',
//      audio, language} → transcription {text|error}. The apiKey lives
//      server-side only — the frontend never touches it.
//   2. Browser Web Speech (default, zero config): Chrome/Edge/Safari built-in
//      recognition. No API key, no backend dependency, no model download.
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
const CLOUD_TRANSCRIBE_TIMEOUT_MS = 20000;

let recognition = null;
let dictating = false;
let lastInterim = '';
const cb = {};

// Cloud recording state — one push-to-talk session at a time.
let cloudRec = null; // { stream, ctx, processor, chunks, token }
let pendingTranscribe = null; // { token, timer }

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
  cancelPendingTranscribe(); // a fresh recording invalidates any stale result
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
  const chunks = [];
  const token = Date.now() + '_' + Math.random().toString(36).slice(2);
  cloudRec = { stream, ctx, processor, chunks, token };
  processor.onaudioprocess = (e) => {
    if (cloudRec && cloudRec.token === token) {
      chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
    }
  };
  source.connect(processor);
  processor.connect(zero);
  zero.connect(ctx.destination);
  cb.onState?.('listening');
}

function cloudStop() {
  const rec = cloudRec;
  cloudRec = null;
  if (!rec) return;
  const { stream, ctx, processor, chunks, token } = rec;
  try { processor.disconnect(); } catch (_) {}
  try { sourceTracks(stream); } catch (_) {}
  try { ctx.close(); } catch (_) {}

  const total = chunks.reduce((n, c) => n + c.length, 0);
  if (total === 0) {
    // Recorded nothing (mic stream opened but onaudioprocess never fired, or
    // the push-to-talk was too short) — surface it instead of silently
    // dropping back to idle (#stt-hotfix).
    cb.onState?.('error', t('stt.noAudio'));
    return;
  }
  const all = new Float32Array(total);
  let off = 0;
  for (const c of chunks) { all.set(c, off); off += c.length; }
  const samples = ctx.sampleRate !== CLOUD_SAMPLE_RATE
    ? downsample(all, ctx.sampleRate, CLOUD_SAMPLE_RATE)
    : all;
  const wav = encodeWav(samples, CLOUD_SAMPLE_RATE);
  const audioB64 = bufferToBase64(wav);
  cb.onState?.('processing');

  const pt = { token, timer: null };
  pendingTranscribe = pt;
  pt.timer = setTimeout(() => {
    if (pendingTranscribe === pt) {
      pendingTranscribe = null;
      cb.onState?.('error', t('stt.timeout'));
    }
  }, CLOUD_TRANSCRIBE_TIMEOUT_MS);
  sendWs({ type: 'transcribe', audio: audioB64, language: getLang() });
}

function sourceTracks(stream) {
  stream.getTracks().forEach(tr => tr.stop());
}

function cancelPendingTranscribe() {
  if (pendingTranscribe) {
    clearTimeout(pendingTranscribe.timer);
    pendingTranscribe = null;
  }
}

// The backend answers {type:'transcription', text} or {type:'transcription',
// error} — no sessionId, so ws.js routes it to the primary view and we consume
// it here via the pending token (one push-to-talk at a time).
onMessage('transcription', (msg) => {
  if (!pendingTranscribe) return; // stale / not ours
  const pt = pendingTranscribe;
  pendingTranscribe = null;
  clearTimeout(pt.timer);
  if (msg.error) {
    cb.onState?.('error', msg.error);
    return;
  }
  const text = (msg.text || '').trim();
  if (text) cb.onText?.(text);
  cb.onState?.('idle');
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

// ── Web Speech path (unchanged) ────────────────────────────────────────

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

  // Clean up any previous instance
  if (recognition) {
    try { recognition.abort(); } catch (_) {}
    recognition = null;
  }

  recognition = new SpeechRecognitionAPI();
  recognition.lang = getLang();
  recognition.continuous = true;
  recognition.interimResults = true;

  recognition.onstart = () => {
    dictating = true;
    cb.onState?.('listening');
  };

  recognition.onaudiostart = () => {
    cb.onState?.('listening');
  };

  recognition.onspeechstart = () => {
    cb.onState?.('speaking');
  };

  recognition.onresult = (event) => {
    let interim = '';
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i];
      if (result.isFinal) {
        const text = result[0].transcript.trim();
        if (text) {
          lastInterim = '';
          cb.onText?.(text);
          if (dictating) cb.onState?.('listening');
        }
      } else {
        interim += result[0].transcript;
      }
    }
    // Track and stream interim text to UI for real-time display
    if (interim && dictating) {
      lastInterim = interim;
      cb.onInterim?.(interim);
    } else if (!interim) {
      lastInterim = '';
    }
  };

  recognition.onerror = (event) => {
    const err = event.error || 'unknown';
    // 'no-speech' and 'aborted' are not real errors during dictation
    if (err === 'no-speech' || err === 'aborted') {
      if (dictating) cb.onState?.('listening');
      return;
    }
    console.error('[voiceEngine] Speech recognition error:', err);
    cb.onState?.('error', err);
    if (dictating) {
      // Try to restart after a brief error
      try { recognition.start(); } catch (_) {}
    }
  };

  recognition.onend = () => {
    // Auto-restart if still dictating (browser stops after silence)
    if (dictating) {
      try { recognition.start(); } catch (_) {}
    } else {
      cb.onState?.('idle');
    }
  };

  try {
    recognition.start();
  } catch (e) {
    // If already started, ignore
    if (e.name !== 'InvalidStateError') {
      cb.onState?.('error', e.message);
    }
  }
}

export function stopDictation() {
  dictating = false;
  // Cloud path: stop recording + send the captured WAV for transcription.
  if (cloudRec) {
    cloudStop();
    return;
  }
  // Flush any pending interim text as final before stopping
  if (lastInterim) {
    cb.onText?.(lastInterim.trim());
    lastInterim = '';
  }
  if (recognition) {
    try { recognition.abort(); } catch (_) {}
    recognition = null;
  }
}
