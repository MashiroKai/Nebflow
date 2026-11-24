# Design

Remove create actions at their rendering and input entrypoints, preserving the shared edit-dismiss handler and the quick-decision action. Reuse the neutral existing empty-state locale key. Keep the store and server write contracts intact; this change is not a server-side prohibition.
