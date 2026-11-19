# Design

Secondary profile and settings surfaces are unified onto one panel structure, one identity alignment
and the existing Canvas control materials. `src/main/resources/web/js/profileFace.js` and
`src/main/resources/web/js/mobile/meFace.js` are the two identity consumers that must align on a
shared centered column; `src/main/resources/web/js/sidebar.js` carries the settings grouping and
`src/main/resources/web/css/design-system.css` the shared materials.
`scripts/check-panel-uniform.mjs` is the static contract gate: it asserts the selected source markers
for the two identity consumers, the contact bottom actions and close guard, and the six settings
group identifiers, and it exits non-zero with `FAIL panel-uniform: …` lines. It is explicitly NOT an
AST inventory of all dynamic controls, does not detect arbitrary unregistered or duplicated actions,
and does not verify exceptions, actual position, centering, clipping, safe areas, focus or occlusion —
those require dedicated browser tests and real-instance screenshots, and passing this lint is not
acceptance evidence for them.
