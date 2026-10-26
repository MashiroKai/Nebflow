// pw.config.mjs — 实截套件独立配置（testDir=本目录，不进 tests 收集面）。
export default {
  testDir: import.meta.dirname,
  workers: 1,
  timeout: 120_000,
  retries: 0,
  use: { headless: true },
  reporter: [['list']],
};
