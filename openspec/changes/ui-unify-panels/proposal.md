## Why

Secondary profile and settings surfaces need consistent panel structure, identity alignment and Canvas control semantics.

## What Changes

- Align personal and contact identity presentations on a shared centered column.
- Unify secondary panel structure and use existing Canvas control materials and dimensions.
- Group settings into account, general, models and services, runtime, advanced and about.
- Show contact save and cancel actions only for pending changes; retain per-field persistence, readback and failure recovery.
- Verify registered control positions, identity structure and duplicate actions with a static gate, and verify geometry and occlusion in rendered views.

## Verification limits

`scripts/check-panel-uniform.mjs` checks selected source markers for the two identity consumers, contact bottom actions and close guard, and six settings group identifiers. It is not an AST inventory of all dynamic controls, does not detect arbitrary unregistered or duplicated actions, and does not verify exceptions, actual position, centering, clipping, safe areas, focus or occlusion. Those require dedicated browser tests and real-instance screenshots; passing this lint is not acceptance evidence for them.

## Impact

Frontend presentation and contact editing. No backend contract, deployment or release changes.
