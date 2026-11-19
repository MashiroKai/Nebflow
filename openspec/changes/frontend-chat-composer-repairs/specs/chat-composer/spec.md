## ADDED Requirements

### Requirement: [FCC-REQ-001] Recall sent input with its attachments
Recalling a sent message with the input-history keys MUST restore its text and its attachments (images, files, references, task references) in their original order, whichever path sent it. Attachments whose content is gone MUST be reported and MUST NOT be dropped silently or sent hollow. Text-only history from earlier builds MUST keep working. The retry of a lost connection MUST NOT do nothing silently.

#### Scenario: [FCC-SCN-001] Recall restores text and attachments
- **WHEN** a message with text, an image and a file was sent and the user recalls it with the history keys
- **THEN** the input shows the text and the same attachments in order, stepping to another entry or back to the draft removes only what the history added, and sending the recalled input sends the same attachment bytes, and a reference whose text is long is kept with the attachment payloads, not in the small page storage

#### Scenario: [FCC-SCN-002] Missing bytes and legacy entries
- **WHEN** an entry's attachment bytes are no longer stored, or the entry was written by an earlier text-only build
- **THEN** the text is restored, the missing attachments are reported once, and nothing hollow can be sent

#### Scenario: [FCC-SCN-014] Retry after a lost connection
- **WHEN** the newest history entry is a message that had attachments and no text, and the retry control of a lost-connection row is used
- **THEN** the newest entry that has text is sent again, and the control does not do nothing silently

### Requirement: [FCC-REQ-002] Retry one failed media item
A media item that fails to load, in a stacked card, a conversation card or an input thumbnail, MUST stay visible as a failed item and MUST offer a keyboard-reachable retry that reloads only that item, without resending the message or reloading its siblings.

#### Scenario: [FCC-SCN-003] Stacked card layer
- **WHEN** one layer of a stacked media group fails and the user retries it, from the expanded face or with a real pointer click on the collapsed front face
- **THEN** only that layer is requested again, the failed state of both faces of that layer clears together, it shows the image when the retry succeeds, a second failure stays retryable, the click retries instead of opening the item, and the other layers and the message are untouched

#### Scenario: [FCC-SCN-004] Conversation card and input thumbnail
- **WHEN** a conversation card image or an attachment thumbnail in the input strip fails to load
- **THEN** a retry control for that item is shown and the thumbnail is not removed silently

### Requirement: [FCC-REQ-003] Open AskUser reply attachments
An attachment chip uploaded as an AskUser reply MUST open its preview through the shared attachment route on click, or on Enter or Space on its preview button. The remove and retry controls MUST NOT open it, and no interactive control may sit inside another one. Upload cancel, the nine-item limit and the secret and choice-question restrictions stay as they are.

#### Scenario: [FCC-SCN-005] Open a reply attachment
- **WHEN** a reply image or file chip is clicked, or its preview button is activated with Enter or Space
- **THEN** the preview of that item opens, the remove and retry controls do not open it, no interactive control is nested inside another, and a secret question offers no attachment entry

### Requirement: [FCC-REQ-004] Ask history stays legible
Ask history bubbles MUST NOT be dimmed: no bubble opacity below 1, by age or by scroll position. A bubble that crosses the top edge of the column is clipped, never faded.

#### Scenario: [FCC-SCN-006] Bubble at the column top
- **WHEN** the history is scrolled so that a bubble crosses the top edge of the column
- **THEN** its computed opacity is 1 in the light and dark themes, and its text and controls stay operable

### Requirement: [FCC-REQ-005] Input-method Enter
While an input-method composition is active, the Enter that confirms a candidate MUST NOT send, and the next independent Enter MUST send exactly one message, in the main composer, the Ask dock and the friend, group and device composers.

#### Scenario: [FCC-SCN-007] Composition Enter
- **WHEN** a composition produced by the browser's own input-method pipeline is confirmed with Enter
- **THEN** no message frame is sent, and a following Enter sends exactly one frame

### Requirement: [FCC-REQ-006] One return entry per meaning
Each scrolling surface MUST show at most one new-message / return-to-bottom control, and the search-return control MUST stay a separate control with its own meaning.

#### Scenario: [FCC-SCN-008] No duplicate entries
- **WHEN** the user scrolls away from the bottom, new messages arrive and a search jump happens
- **THEN** at most one new-message pill and at most one search-return pill exist per surface, and each is cleared by its own return

### Requirement: [FCC-REQ-007] Desktop conversations use the main chat area
On desktop, opening a friend, group or device conversation MUST show it in the main chat area instead of a dialog, switch the header to that conversation and flip the left avatar to the Agent face. Clicking that avatar, or selecting or creating an Agent session, MUST return to the Agent. Each conversation and the Agent MUST keep their own draft and scroll position and never share stored messages. Mobile navigation MUST be unchanged.

#### Scenario: [FCC-SCN-009] Open and return
- **WHEN** a friend, group or device conversation is opened on desktop and later the flipped avatar is clicked
- **THEN** the conversation fills the main chat area with no dialog, the header names the conversation, the Agent view is hidden but intact, and the click returns to the Agent and restores the avatar, header and view

#### Scenario: [FCC-SCN-010] State isolation
- **WHEN** the user switches between the Agent and two conversations while drafts, scroll positions and late responses exist
- **THEN** each keeps its own draft and scroll position, two conversations that do not exist yet keep separate drafts, the Agent's draft and attachments are untouched, and a response, a sync or a refusal that comes back for a conversation that is no longer shown is not painted into the current one, is not stored under the current one's key and gives an unsent text back to its own conversation

#### Scenario: [FCC-SCN-011] User confirmation of the visual change
- **WHEN** the desktop conversation host is implemented
- **THEN** real desktop light and dark screenshots and a mobile regression are provided for the user to confirm, and the change is not merged before that confirmation

#### Scenario: [FCC-SCN-012] Agent session selection
- **WHEN** a conversation is shown and an Agent session is selected (the current one included), or the gateway creates or activates one
- **THEN** the conversation closes and the Agent view comes back, while an unrelated refresh of the session list leaves the conversation alone

#### Scenario: [FCC-SCN-013] The flipped avatar
- **WHEN** a conversation is opened, and later left, including within the first half of the flip
- **THEN** the flip runs to its end, the tooltip says where a click goes through a status refresh, and the account tooltip is back after the return

### Requirement: [FCC-REQ-008] Keys and pastes reach the layer they are aimed at
While a conversation is shown in the main area, an Escape aimed at another layer MUST NOT also leave the conversation, and an image pasted into a conversation input MUST NOT be added to the hidden Agent composer.

#### Scenario: [FCC-SCN-015] Escape and paste
- **WHEN** Settings is closed with Escape while a conversation is shown, and an image is pasted into the conversation's input
- **THEN** the conversation stays, a later Escape from its composer still leaves it, and the Agent's attachments are unchanged
