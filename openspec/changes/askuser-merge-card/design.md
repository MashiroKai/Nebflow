# Design

The merge question reuses the existing AskUser shell and its one-string-per-question answer wire.
`src/main/scala/nebflow/shared/askUser.scala` carries one optional `merge` field on `AskItem`
(an opaque JSON object, absent for every pre-existing question, so old payloads stay byte-identical).
`src/main/scala/nebflow/core/tools/AskUserQuestionTool.scala` declares the `merge` schema on question
items and parses it: `merge` is rejected for `secret` questions (a secret question never renders a
card body) and a question carrying `merge` is forced single-select (`multiple` is cleared), because
the answer is one JSON object, not a set of option labels.

The card is rendered where the existing option body is rendered — `src/main/resources/web/js/chat.js`
— inside the same `.option-box` shell, so the confirm button, the reject-reason gate and the
single-shot answer path are the pre-existing ones. `src/main/resources/web/css/chat.css` holds the
`.ask-merge-*` block plus the row checkbox face. The row checkbox is a `role="checkbox"` button with
the shared `.option-check` shape, not a native `input[type=checkbox]`: the repository's toggle
unification gate rejects a checkbox that co-exists with `-thumb`/`-toggle` class names in the same
file, and `chat.js` has attachment thumbnails. This follows the existing checkbox face in
`js/messages.js` (`attachCheck`).

The card does not execute Git and does not read repository state: `target`, `targets`, `candidates`,
`operations` and `branches` are supplied by the caller, and a missing relation for the selected
target renders as unknown rather than being inferred. The answer is serialized by the existing
answer path into the existing single answer string:

`{"decision":"approve|reject|supplement|defer","branches":[{"name":"…","note":"…"}],"target":"…","text":"…"}`

Branches are the selected entries in displayed order. Approve requires at least one selected branch;
reject requires non-blank text; supplement and defer are distinct decisions. Nothing in this change
grants the tool merge authority — the caller still decides what to do with the answer.

Known boundary: the browser interaction proof (`tests/askuser-merge-browser.cjs`) drives the real
frontend modules against a static harness with a mocked socket; it is not registered in the static
profile and was not run as part of this landing, so the interaction claim rests on the Scala
contract tests plus a manual browser run of that script.
