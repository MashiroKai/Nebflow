# Canvas document mention negative controls

Run `ENGINE=chromium node tests/canvas-document-mentions-real.mjs` and with `ENGINE=webkit`; both must pass. Set `FAULT=duplicate`, `FAULT=heading`, `FAULT=link`, or `FAULT=code` to remove the corresponding guard at response time; each must fail its named assertion. The code fault also asserts the injected predicate executed and checks inline, fenced and indented code.

Baseline new-feature negative: run the same test with `NEBFLOW_WEB_ROOT` set to an unmodified checkout at `4164dd49f7043fbf6da5ba07420ec2599b645519`. Both engines fail waiting for the new mention, as expected. No baseline red is required for protection assertions: those protections are tested by fault injection on the feature branch.
