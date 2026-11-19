# Report block visibility

## Change

Agent messages expose only paired `<report>...</report>` bodies on user-facing console and social channels. Text outside is process material accessible through the existing turn badge; zero blocks is valid. Node handoffs, mail and delegate results remain complete and unfiltered. The former `<output>` syntax is ordinary text, without a transitional alias. The old outputBlock kill switch is retired rather than allowing a full-text leak.

## Parsing and acceptance

Trim each block and join multiple blocks in order with a blank line. Empty blocks produce no channel message. Ignore tag-looking text in fenced code, inline code, and Markdown blockquotes. Unclosed, stray or nested tags fail closed: do not expose any content; streaming channels hold malformed or partial segments until completion. Test parser parity, both channel seams, streaming and history console display, badge reuse, and prompt assembly. Capture light/dark GUI evidence for report and zero-report turns; compile and run the static gates.
