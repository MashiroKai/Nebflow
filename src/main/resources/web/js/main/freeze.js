// main.js 抽取(FE组件化批次七 2026-09-28):冻结窗口族 模块(行为保持)。
import state from '../state.js';
import { sendWs } from '../ws.js';
import { t } from '../i18n.js';
import { formatResumeClock } from '../chat.js';
import { chatViews, activeView } from '../chatView.js';

// ── ⑩ Local schedule freeze (2026-08-24 ruling) ───────────────────────────
// "处于冻结时间段，如果没有在工作过程（idle），也应该立即冻结消息输入框" —
// the frozen display follows the schedule window itself, not just the
// backend's park events (which only fire at dispatch boundaries mid-work).
// Determined locally from the serverConfig-echoed schedule; the event path
// (frozen/resumed) keeps authority over parked (frozenSessions) sessions.
function freezeWindowState() {
  const ws = state.workSchedule;
  if (!ws || !ws.enabled || !Array.isArray(ws.segments)) return null;
  const now = new Date();
  const cur = now.getHours() * 60 + now.getMinutes();
  for (const seg of ws.segments) {
    const [sh, sm] = String(seg.start || '').split(':').map(Number);
    const [eh, em] = String(seg.end || '').split(':').map(Number);
    if ([sh, sm, eh, em].some(Number.isNaN)) continue;
    const s = sh * 60 + sm, e = eh * 60 + em;
    // Blacklist semantics; cross-midnight segments (start > end) are legal.
    const inside = s < e ? (cur >= s && cur < e) : (cur >= s || cur < e);
    if (inside) {
      const end = new Date(now);
      end.setHours(eh, em, 0, 0);
      if (end <= now) end.setDate(end.getDate() + 1); // cross-midnight → tomorrow
      return { resumeAt: end.getTime() };
    }
  }
  return null;
}

// Frozen state makes the whole input bar inert EXCEPT the "跳过本次" button
// (08-25 22:28 user ruling: no more "send a message to wake" — the freeze window
// blocks input + mic until the user explicitly skips). The textarea and the
// mic/attach/send/stop controls all get disabled; only the skip control stays
// interactive. `disabled` (not readonly) so the inert textarea fires no keydown
// and can never send. Un-frees by removing the attributes.

// The system-freeze targets the MAIN composer (#input-bar = chatViews.primary's
// input bar). During a sub-agent event dispatch ws.js momentarily routes the
// module-global activeView to the popup view (or null); a freeze driven by the
// schedule window must still free/freeze the visible main composer, so never
// rely on the transient activeView here — resolve the primary view (which owns
// #input-bar) with an activeView fallback for pre-boot (before primary is built).
function freezeTargetView() {
  if (chatViews.primary && chatViews.primary.dom && chatViews.primary.dom.inputBar) return chatViews.primary;
  return activeView;
}
export function setFrozenBarState(v, frozen) {
  const bar = v && v.dom && v.dom.inputBar;
  if (!bar) return;
  const input = v.dom.input;
  if (input) {
    if (frozen) { input.setAttribute('disabled', ''); input.setAttribute('aria-disabled', 'true'); }
    else { input.removeAttribute('disabled'); input.setAttribute('aria-disabled', 'false'); }
  }
  for (const sel of ['#voice-btn', '#attach-btn', '#send-btn', '#stop-btn']) {
    const el = bar.querySelector(sel);
    if (!el) continue;
    if (frozen) el.setAttribute('disabled', '');
    else el.removeAttribute('disabled');
  }
}

export function applyLocalFreeze() {
  const v = freezeTargetView();
  const bar = v && v.dom && v.dom.inputBar;
  if (!bar || !v.sessionId) return;
  const win = freezeWindowState();
  const parked = state.frozenSessions.has(v.sessionId); // event path owns it
  const busy = state.busySessionIds.has(v.sessionId);   // woken mid-window: working
  // A user who clicked "跳过本次" voided the current window (08-25 ruling:
  // skip is not permanent — it expires at the window end, so the next window
  // re-freezes). skipActiveForWindow() merges the in-memory mirror with the
  // authoritative backend freezeState.skipped (which survives a reload — that
  // mirror is lost on refresh). See skip-active note below.
  const skipped = skipActiveForWindow(win);
  if (win && !parked && !busy && !skipped) {
    if (!bar.classList.contains('frozen')) {
      bar.classList.add('frozen');
      bar.dataset.frozen = 'true';
      setFrozenBarState(v, true);
      if (v.dom.input) v.dom.input.placeholder = t('chat.frozenPlaceholder', { time: formatResumeClock(win.resumeAt) });
    }
  } else if ((!win || busy || skipped) && !parked && bar.classList.contains('frozen')) {
    bar.classList.remove('frozen');
    delete bar.dataset.frozen;
    setFrozenBarState(v, false);
    import('../input.js').then(({ applyInputModes }) => applyInputModes());
  }
}

// ── Skip-current-freeze (user ruling 08-25 14:40: "跳过本次" makes the current
//    freeze window void so the user can keep talking — the message "跳过了才让
//    说"). Skip is NOT permanent: it expires at the window end (skipFrozenUntil
//    = resumeAt), so the next schedule window freezes again. ──────────────────
// Mirrors the backend skipCurrentFreezeWindow (freezeSkipUntilRef). The button
// also sends {type:'skipFreeze'} — a no-op on backends that have not yet
// implemented the command (it hits the catch-all with empty content, recording
// nothing — no fake bubble, no mis-route), and the authoritative wake once they
// have (skipCurrentFreezeWindow unfreezes all parked agents). The local mirror
// is what makes the UI recover immediately, independent of the backend.
let skipFrozenUntil = 0;

// Is the CURRENT schedule window (win = freezeWindowState()) voided by a skip?
// Two signals — the in-session local mirror (skipFrozenUntil, set by the button
// click, survives immediate UI recovery) and the authoritative backend snapshot
// (state.freezeState.skipped, survives a reload where the mirror is lost). The
// snapshot is time-bounded by nextChangeAt (the skipped window's end) so a stale
// skip expires when that window ends and the next schedule window re-freezes.
function skipActiveForWindow(win) {
  if (!win) return false;
  if (skipFrozenUntil > Date.now()) return true;
  const fs = state.freezeState;
  if (fs && fs.skipped && (fs.nextChangeAt == null || fs.nextChangeAt > Date.now())) return true;
  return false;
}

export function skipCurrentFreeze() {
  // Record the window end so applyLocalFreeze won't re-freeze the rest of this
  // window. Guarded: when no local window is found (e.g. the backend parked via
  // event before the schedule echo landed), we still un-freeze the UI — the
  // button appearing means .frozen is present, so the click must always work.
  const win = freezeWindowState();
  if (win) skipFrozenUntil = win.resumeAt;
  // Best-effort authoritative wake on the backend (safe no-op if unimplemented).
  sendWs({ type: 'skipFreeze' });
  // Immediate local UI unwind: un-freeze the active input bar (skip only the
  // schedule-frozen state — never the amber error-recovery family).
  const v = freezeTargetView();
  const bar = v && v.dom && v.dom.inputBar;
  if (bar) {
    if (!bar.classList.contains('frozen-error')) {
      bar.classList.remove('frozen');
      delete bar.dataset.frozen;
      setFrozenBarState(v, false);
    }
  }
  if (v && v.dom && v.dom.input) {
    import('../input.js').then(({ applyInputModes }) => applyInputModes());
  }
}
