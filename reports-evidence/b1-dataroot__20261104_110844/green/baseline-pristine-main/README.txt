pristine-main 对照证据（2026-11-04，main=7f4202ffc 快照 /tmp/b1-baseline-main-1793771151，/tmp 独立编译跑）

- baseline-run.log   = FriendApiRoutesSpec + Mvp2DeviceRoutesSpec: Total 51, Failed 3, Passed 48（与本腿逐字同 3 红）
- baseline-run2.log  = DelegateNotifyRedirectSpec + ProjectCreatePanelSpec + DelegateFlowHostsSpec: Total 24, Failed 8, Passed 16（与本腿逐字同 8 红）
- isolate-4suites.log = 本腿单独跑同 5 suite: Total 75, Failed 11, Passed 64（⑥ 120s 超时单跑不复现）

结论：scoped 回归 12 红全部既有（非本腿引入），归因见 vault 报告 §6.1.1。
