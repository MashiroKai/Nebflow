# Selection actions and contextual Ask

## Intent
Allow a selection in main chat, friend chat or canvas text to be copied, quoted through the existing composer reference, or explained through the existing Ask channel. Support desktop context menus and real touch long press. A closable explanation can be quoted back.

## Proposed acceptance
- Selecting text and invoking the menu copies or quotes exactly the selected text without changing existing whole-message actions when nothing is selected.
- Ask includes a configurable bounded window before and after each selected range, produces an explanation in a closable panel, and permits quoting that explanation.
- Multiple separately selected ranges may be accumulated and are sent as ordered, numbered excerpts, each with its own surrounding context.
- Exercise main, friend and canvas surfaces, including touch, failure states and existing menu behavior; capture dynamic evidence.

## Boundary and status
In progress. File-bound canvas Ask and the main-session Ask have different bindings; this change must not fabricate a file binding or silently route friend text to an unrelated session. Embedded cross-origin frames and editor selection APIs require explicit integration. Main chat now has selection copy, quote and an experimental single-selection Ask panel with up to 300 UTF-16 code units of context on each side, bounded to the bubble where possible. Its protocol has no request correlation identifier; it excludes local concurrent sends and requires further integration verification against real backend traffic, disconnection and other clients. A touch hold handler is present but not validated on a physical device. Friend/canvas and multiple selections remain unimplemented; the browser Ask test uses synthetic server frames, not a model.
