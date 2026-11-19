# Design

A selection in main chat can be copied, quoted through the existing composer reference, or explained
through the existing Ask channel. `src/main/resources/web/js/selectionAsk.js` owns the selection Ask
panel and its bounded context window, and `src/main/resources/web/js/contextMenu.js` owns the
context menu / long-press entry that hosts the three actions; the quote leg goes through the
existing composer reference rather than a new one. Main chat currently has selection copy, quote and
an experimental single-selection Ask panel with up to 300 UTF-16 code units of context on each side,
bounded to the bubble where possible. Its protocol has no request-correlation identifier, it excludes
local concurrent sends, and it still requires integration verification against real backend traffic,
disconnection and other clients; the browser Ask test uses synthetic server frames rather than a
model. A touch long-press handler is present but not validated on a physical device. Friend chat,
canvas surfaces and multiple accumulated selections remain unimplemented and must not be reported as
done; no file binding is fabricated for a surface that has none.
