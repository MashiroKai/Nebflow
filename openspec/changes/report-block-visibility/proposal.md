# Report block visibility

## Change

Only root-session messages expose paired `<report>...</report>` bodies on the user-facing console and social channels. Text outside is process material accessible through the existing turn badge; zero blocks is valid. Other sessions retain their existing presentation. Node handoffs, mail and delegate results remain complete and unfiltered. For root messages the former `<output>` syntax is ordinary text, without a transitional alias. The old outputBlock kill switch is retired for root rather than allowing a full-text leak.

## Scope and unresolved implementation

The root-only prompt is assembled in the `isRootAgent` section, not the shared prefix. Frontend scope must be derived from the owning session's verified agent identity, not the primary window, because the primary view may display non-root sessions. The session-agent map is populated from session lists, so loading and unknown-identity states require explicit handling. Social bindings accept arbitrary session identifiers; root identity must be verified before filtering rather than inferred from channel membership. These scope controls are not yet implemented in the current draft.

## Parsing and acceptance

Trim each block and join multiple blocks in order with a blank line. Empty blocks produce no channel message. Ignore tag-looking text in fenced code, inline code, and Markdown blockquotes. Unclosed, stray or nested tags fail closed: do not expose any content; streaming channels hold malformed or partial segments until completion. Test parser parity, both channel seams, streaming and history console display, badge reuse, and prompt assembly. Capture light/dark GUI evidence for report and zero-report turns; compile and run the static gates.
