# Design

The media-stack footer follows the expansion state. A collapsed stack keeps one message footer whose
quote references the complete row and whose download saves all row attachments as one ZIP; an
expanded stack places a footer below each media card, with quote and direct download scoped to that
card, and the group controls are hidden while expanded.
`src/main/resources/web/js/msgQuote.js` owns the footer quote button and the group download
(`.msg-group-dl`, and around line 430 it hides the group buttons once any `.pop-stack-area` reports
`popExpanded`); `src/main/resources/web/js/popArtifacts.js` owns the attachment/ZIP material the
group download consumes. Existing stack switch and expansion motion are unchanged. A member with
neither path nor inline data disables the group button with a visible reason, and each unavailable
item keeps a disabled download button and a visible reason; a source that fails at runtime fails the
group download. The controls reuse the existing message-footer button geometry and work with touch.
