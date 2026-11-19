## Implementation and acceptance

- [ ] Give the file-bound Ask write face a single verdict point that normalises the
      requested spelling (relative against the bound directory, `.`/`..`, symlinks) and
      compares document identity, so equivalent spellings pass and every other target fails.
- [ ] Make each refusal readable and route the caller out: state that the session edits
      only its bound document and point at the main-session handoff.
- [ ] Append an audit row for every verdict (allow and deny) carrying identity, tool,
      requested spelling, normalised path, time and result.
- [ ] Turn the handoff into a structured request (task / paths / reason / expected) over the
      existing main-session channel, and surface it as a visible Ask panel state.
- [ ] Prove the write face is closed per identity and add the checkout-only gate that goes
      red when the string-comparison verdict or the audit call is removed.
- [ ] Replace the stale path-discipline and prompt-contract assertions, and record the
      over-permission matrix readings as evidence.
