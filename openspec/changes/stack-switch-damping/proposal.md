## Why

Media-stack switching should feel slower and damped without changing the existing continuous card path or input semantics.

## What Changes

- Use one shared duration and nonlinear decelerating timing curve for the desktop and mobile media-stack switch.
- Keep the existing compositor transforms, hidden z-order handoff and queued navigation; reduced-motion switches immediately.

## Verification

Run the existing stack continuity and touch motion tests, token checker and browser measurements of both viewport sizes and reduced motion. The prior implementation is established by the existing stack-continuity test, not by a separately located current specification.
