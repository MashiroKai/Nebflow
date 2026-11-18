# Isolated AskUser heavy checks

These opt-in checks start a real foreground gateway and loopback mock LLM; they are not part of the self-contained fast suite. Use a disposable Mac `/private/tmp/qa-*` directory, never a production data root. Each invocation provisions its own home, uses a random loopback port for each process, and retains evidence and the home for inspection. Provisioning resets `home-input` or `home-refresh` under that root.

From the repository root with JDK 23, sbt and Node installed:

```sh
npm ci
npm install --no-save --no-package-lock @playwright/test@1.62.0
npx playwright install chromium
sbt e2eClasspath
mkdir -p /private/tmp/qa-askuser-example
ASK_RUN_ROOT=/private/tmp/qa-askuser-example node tests/heavy/askuser-runner.mjs input
ASK_RUN_ROOT=/private/tmp/qa-askuser-example node tests/heavy/askuser-runner.mjs refresh
```

On a machine with an existing compatible browser cache, set `PLAYWRIGHT_BROWSERS_PATH` instead of installing Chromium. The runner reads the isolated gateway's generated token without printing it, passes it to the test, writes screenshots under the run root, requests `quit` on both owned processes, waits for exit and checks both ports are free. A failed check or failed cleanup returns nonzero. If cooperative shutdown times out, investigate the reported PID; no signals are sent. The gateway and tests can make network requests; use a reviewed network sandbox where strict offline execution is required. Do not use this runner against a live gateway. These suites currently have known failures and a zero exit is not assumed.
