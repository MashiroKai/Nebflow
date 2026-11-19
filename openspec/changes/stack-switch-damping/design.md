# Design

## Existing path and evolution

The media stack is one fixed card face with fan layers; a switch rotates the front index and
animates every visible slot from behind the incoming face to its final transform. The continuous
drag path, the buried-layer z-order handoff and the queued navigation predate this change and are
kept as they are — this change only replaces the switch's timing with one shared damped response.

## Implementation

`src/main/resources/web/css/design-system.css:362` states the intent — "Media-stack switch: sampled
critically damped response, shared by all viewports." — and `:363-364` declare the single token pair
inside the `:root` block:

- `--ds-dur-stack-switch: 0.55s`
- `--ds-ease-stack-switch: linear(0, 0.18 10%, 0.42 20%, 0.62 30%, 0.77 40%, 0.87 50%, 0.93 60%, 0.965 70%, 0.984 80%, 0.995 90%, 1)`

The pair is declared once and has no per-viewport override; the only consumer is
`src/main/resources/web/js/popArtifacts.js:965-968`, which reads the computed style of the stack view
and turns the pair into one options object per switch:

```
const motionStyle = getComputedStyle(view);
const duration = parseFloat(motionStyle.getPropertyValue('--ds-dur-stack-switch')) * 1000;
const easing = motionStyle.getPropertyValue('--ds-ease-stack-switch').trim();
const options = { duration, easing };
```

Because the read is viewport-independent, desktop and mobile take the same duration and the same
curve. The sample curve is monotonic with shrinking increments (0 → 0.18 by 10% → … → 1), i.e. a
decelerating response, not a browser default.

Reduced motion switches immediately: `reduceStackMotion()` is
`window.matchMedia('(prefers-reduced-motion: reduce)').matches`
(`src/main/resources/web/js/popArtifacts.js:942`) and the switch returns before any animation is
scheduled — `if (reduceStackMotion() || !motionEnabled()) { finish(); return; }` (`:960`).

What is preserved unchanged: the queued navigation drain (`:958`,
`switchFront(pendingSteps.shift())`), the switch flag and class (`:961`, `:963`), the buried-layer
handoff (`:977-978` computing `outgoingBuried` and removing `is-buried`, consumed at `:995` as the
outgoing target transform) and the existing compositor transforms.

## Acceptance

`tests/stack-continuity.spec.mjs` exercises the real switch on both viewport variants (`fwd`,
`rev`) across the phase set: `jump window ${variant} [${phase}]` (:259), `handoff ${variant}
[${phase}]` (:336), `settled parity [${phase}]` (:395), `reveal curve [${phase}]` (:451), `buried
handoff ${phase}` (:527) and `two-card handoff ${phase}` (:566). These are the existing harness the
proposal points at as the establishment of the prior implementation; they assert continuity,
handoff and reveal-monotonicity behaviour, not the token values themselves.

## What this supplement does NOT establish

Stated plainly rather than papered over:

- No gate asserts the token pair. `--ds-dur-stack-switch` and `--ds-ease-stack-switch` are read at
  switch time and nothing lints their presence, their single declaration or their curve shape.
- The reduced-motion branch has no automated test: no named browser test drives
  `prefers-reduced-motion` for the stack switch. SSD-SCN-002 therefore carries a reasoned manual
  exception in `trace.json` instead of a test literal.
- The proposal's "browser measurements of both viewport sizes and reduced motion" were not executed
  in this batch; the stack-continuity harness was not re-run here either.
- The token values are the release line's existing literals. This change adds no new value and does
  not restate the curve as a specification number.
