# Design

The existing orbit state machine is only valid for cards with exactly three spin elements. Validate that shape before phase bookkeeping and before the coast class write. If a rebuilt card loses its rings, clear its existing phase once; subsequent observer deliveries perform no mutation.

Scoped task snapshots retain all historical terminal rows. Reuse unchanged projected entries and the unchanged root map, and return without dispatching task or mirror events when no row changed. Existing fetch timeouts and single-flight ownership remain unchanged; network polling is not removed by this repair.

No production data, local storage, authentication configuration or history is deleted. The separate runtime fixes and scheduler revision repair are verified by their focused regression suites; they are not evidence that the user's full browser state is healthy.
