# Repair startup responsiveness

## Problem

Restored folded chain cards share the `solar-node` class with animated nodes but have no orbit rings. A running-to-terminal transition can repeatedly mutate its own observed class without entering a completable coast. Large historical task snapshots also notify the UI on unchanged polls. The initial changed snapshot still reconciled every terminal child by refreshing the entire root indicator and dropdown for each row, including while the dropdown was closed. A 1,070-row history therefore caused repeated full-list construction before the first user interaction. Separately, an empty single-line attachment strip repeatedly removed an absent observed class.

## Scope

Restore responsive timers and interaction without discarding user history. Exclude non-orbit cards from orbit state, release state when rings disappear, and suppress unchanged scoped-task notifications. Preserve real orbit completion animation and terminal task discovery.

## Verification

Use bounded regressions against production modules, including 1,070 historical task rows. Native browser probes supplement these checks; a probe is not acceptance of the user's complete authenticated workspace.
