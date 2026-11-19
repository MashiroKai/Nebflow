# Chat composer, attachments and desktop conversation host — proposed

This change implements the current authorized frontend repair request (`frontend-repair-requirements.md`, module C items 3, 5, 6, 8 and 9). It is not a historical accepted specification, and no earlier acceptance is claimed.

- Recalling sent input with the history keys keeps the message's attachments, not just its text.
- A failed media item (stacked card layer, conversation card, input thumbnail) is retried on its own.
- Reply attachments uploaded for an AskUser question open like every other attachment.
- Ask history bubbles stay fully legible; nothing is dimmed by age or scroll position.
- Enter that confirms an input-method candidate does not send, proven with the browser's own composition pipeline.
- Each scrolling surface keeps a single return-to-bottom / new-message entry.
- On desktop, friend, group and device conversations use the main chat area instead of a dialog: the header follows the conversation, the left avatar flips to the Agent face and returns to the Agent (by click, or when an Agent session is selected or created), and drafts and scroll positions stay per conversation. Conversations never share stored messages, and a send or sync that answers after the user has moved on touches only its own conversation. An Escape aimed at another layer and an image paste aimed at a conversation do not reach the conversation or the hidden Agent composer. Mobile navigation is unchanged.

Non-goals: the Agent session lifecycle, Canvas media routing, the Note queue, provider/model configuration, voice full-duplex, and any change to the gateway protocol. The desktop host is a large visual change; screenshots are left for the user to confirm and nothing is merged before that.
