// voiceEngine.js — Voice dictation via Web Speech API.
//
// Uses the browser's built-in speech recognition (Chrome, Edge, Safari).
// No API key, no backend dependency, no model download.
//
// Public API:
//   startDictation(cb)  → start listening; transcribe until stopDictation()
//   stopDictation()     → stop listening
//   isModelReady()      → always true (browser handles everything)
//
// Callbacks (all optional):
//   { onText(text), onState(state, data) }
//
// States: 'idle' | 'listening' | 'speaking' | 'processing' | 'error'

import { getLocale } from './i18n.js';

const SpeechRecognitionAPI =
  window.SpeechRecognition || window.webkitSpeechRecognition;

let recognition = null;
let dictating = false;
let lastInterim = '';
const cb = {};

function getLang() {
  const loc = getLocale();
  if (loc.startsWith('zh')) return 'zh-CN';
  if (loc.startsWith('en')) return 'en-US';
  return loc.replace('-', '_');
}

export function isModelReady() {
  return true;
}

export async function startDictation(callbacks = {}) {
  Object.assign(cb, callbacks);

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
