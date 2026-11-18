# Stack footer by expansion state

## Behavior delta
A collapsed media stack has one message footer: quote references the complete row and download saves all row attachments as one ZIP. An expanded stack places a footer below each media card, with quote and direct download scoped to that card. The group controls are hidden while expanded. Existing stack switch and expansion motion remain unchanged.

## Acceptance
- Collapsed quote retains every attachment; ZIP contains every attachment with its name and bytes. A member with neither path nor inline data disables the group button with a visible reason. Each unavailable item also keeps a disabled download button and visible reason. A source that fails at runtime fails the group download.
- Expanded quote carries only the selected item; its download saves that item directly.
- Controls use the existing message footer button geometry and work with touch. Verify desktop and mobile, downloads and reference payloads, and both frontend gates.
