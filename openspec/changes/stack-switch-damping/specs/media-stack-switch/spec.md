## ADDED Requirements

### Requirement: [SSD-REQ-001] One damped switch response for the media stack on every viewport

The media-stack switch SHALL take its duration and its easing from one shared token pair declared
once for all viewports, SHALL use a nonlinear decelerating curve rather than a browser default
timing function, SHALL keep the existing compositor transforms, the buried-layer z-order handoff and
the queued navigation, and SHALL switch immediately when the user prefers reduced motion or motion
is disabled.

#### Scenario: [SSD-SCN-001] The shared token pair drives the switch on both viewports

- **WHEN** the stack switches one card forward or backward on the desktop or the mobile viewport
- **THEN** the animation duration and easing are read from the single shared token pair
  (`--ds-dur-stack-switch`, `--ds-ease-stack-switch`) rather than from per-viewport literals, and the
  easing is a monotonic decelerating sample curve rather than a browser `ease` or `linear` default

#### Scenario: [SSD-SCN-002] Reduced motion switches immediately

- **WHEN** the user prefers reduced motion, or motion is otherwise disabled
- **THEN** the switch completes without scheduling an animation, and the final slot geometry equals
  the geometry the animated path settles on

#### Scenario: [SSD-SCN-003] The continuous card path and input semantics are unchanged

- **WHEN** the stack is handed off, buried, revealed or switched while another switch is still
  pending
- **THEN** the compositor transforms, the buried-layer handoff and the queued navigation behave as
  they did before this change, and input handling on the stack is untouched
