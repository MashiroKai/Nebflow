## ADDED Requirements

### Requirement: [MVZ-REQ-001] The media viewer zooms with each platform's own input

Viewing an image or a video in the app SHALL offer zoom and pan, driven on a phone by two-finger pinch and on the desktop by the mouse wheel and trackpad. Zoom SHALL be anchored: the content point under the pinch midpoint, or under the cursor, SHALL stay where it is instead of the zoom being taken about the centre.

#### Scenario: [MVZ-SCN-001] Phone pinch is anchored at the finger midpoint

- **WHEN** two fingers pinch outward over the media in the full-screen viewer
- **THEN** the zoom level rises within the closure bounds
- **AND** the content point that was under the finger midpoint is still under the midpoint afterwards (drift within a few pixels)
- **AND** motion of the midpoint itself moves the content with the fingers

#### Scenario: [MVZ-SCN-002] Desktop wheel and trackpad act through one convention

- **WHEN** the mouse wheel turns while the cursor is over the media, away from the centre
- **THEN** the content point under the cursor stays under the cursor
- **AND** a trackpad pinch (`wheel` carrying `ctrlKey`) zooms through the same anchored convention and the same maximum level
- **AND** a two-finger pixel-step or `deltaX` wheel pans the content instead of zooming it

### Requirement: [MVZ-REQ-002] Zoom gestures SHALL NOT eat the shipped gestures

Adding zoom SHALL NOT remove paging, drag-to-close, keyboard or the media's own controls. The viewer SHALL state, per state, which gesture wins, and SHALL leave any control surface it cannot address by selector to the element that owns it.

#### Scenario: [MVZ-SCN-003] Paging and drag-to-close survive a zoom round-trip

- **WHEN** the viewer has been zoomed in and back out to fit-screen
- **THEN** a horizontal single-finger drag still pages to the neighbouring image and a downward drag still closes the viewer, with the shipped commit thresholds unchanged
- **AND** while zoomed, a drag pans instead, and continuing past the axis bound pages to the neighbouring image so left/right switching is not lost
- **AND** Esc and the left/right keys keep working on the desktop

#### Scenario: [MVZ-SCN-004] The video control band is never claimed

- **WHEN** a zoom gesture starts inside the native control band of a video (the strip that carries play, progress and volume)
- **THEN** the zoom pipeline does not claim that pointer and the zoom level is unchanged
- **AND** a gesture starting on the video's picture zooms it through the same state, and once zoomed a drag pans the video without toggling its playback

### Requirement: [MVZ-REQ-003] Levels, bounds and initial state are explicit

The viewer SHALL state its minimum and maximum level, SHALL NOT let content be dragged indefinitely out of the viewport, and SHALL return to its initial view whenever the viewer is opened or the page changes.

#### Scenario: [MVZ-SCN-005] Over-zooming out and over-dragging both resolve predictably

- **WHEN** the user keeps pinching smaller after the minimum (fit-screen) level
- **THEN** the level is damped during the gesture and springs back to fit-screen and centred on release
- **AND** the viewer stays open (shrinking past the minimum does not close it)
- **AND** a drag that reaches an axis bound stops at the bound rather than following the finger out of the viewport

#### Scenario: [MVZ-SCN-006] Re-opening starts from the initial view

- **WHEN** a media item was zoomed and panned, then the viewer is closed and the same item is opened again
- **THEN** the level is the fit-screen level with no leftover translation
- **AND** paging to another item in the same group also shows it at the initial view
