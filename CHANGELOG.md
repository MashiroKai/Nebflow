# Changelog

## 2026.11.26-beta.1

### 界面

- 主会话采用 Ask 式显示：去除 badge、单行流式展示，并直接显示子 agent 的过程。
- Canvas Ask：处理中只显示一行状态，完成后不留可展开的残留，回答整条逐条出现。
- 点击数量 badge 即可展开或收起内容，不再需要单独的展开按钮。
- 改进移动端布局、搜索、代码滚动、选项触控高亮和收藏路径展示。
- 新增面板外观基线工具，各面板外观仍需逐个批准。

### 能力

- 文档配图统一使用仓内 PNG、公式使用 LaTeX，visual-report 的 17 张示例图可通过脚本重建。
- 改进提醒的时区和夏令时处理，以及插件引擎授权与动态工具加载的衔接。
- 完善术语阅读与文档来源导航。
- Ask 工具平权：Canvas 与文件 Ask 的 bash 和基础工具与普通会话一致，不再额外限制；受保护模式下仍然失败关闭，暂停原因可经恢复接口读取。

### 修复

- 修复 Ask 交互中的卡片隔离、选项图片点击、图片凭据和中断恢复问题。
- 修复文件分片上传失败时的残留与资源占用，以及首次登录同意流程的会话问题。
- 修复移动端联系人焦点、界面重叠和部分视觉状态问题。

### 已知问题

- 后台面板生命周期 v3 未包含在本版中，清除操作的安全边界仍在完善。
- CI 发布链尚未修复，本版由发布负责人手动发布。
- #3160 的四条已登记但尚未复现的风险继续保留登记，其中包括外部进程逐 run 登记等风险。
- Ask 暂不挂载插件（没有声明来源，待指定）。
- Ask 平权的两条读法待作者确认：以普通会话为参照、不继承编排身份。

## 2026.11.24-beta.1

### Changes

- Restore the archive-revive batch and unify installer handling.
- Improve canvas favorites (r2 and r3), the model rule engine, and multi-selection Ask interactions.
- Include the integrated six-fix batch and repair twelve historical test failures.

### Known issue and verification

- The full Scala suite reported one failure in `DelegateFreezeE2ESpec.scala:419` (10,978 total, 10,948 passed, 29 skipped; 16 ignored). The same test passed on an isolated rerun (1/1). The reported attribution is a suite-run difference; this does not establish that the underlying issue is fixed. The release owner accepted this residual risk.
- Local evidence: `/Users/kaiyu/.stand/bg-task-logs/b262b06d.log` (full suite) and `/Users/kaiyu/.stand/bg-task-logs/f706cc22.log` (isolated rerun).

## Unreleased — beta candidate (source tip not finalized)

Candidate baseline `beed5aa486bed847f086a98258903dc42f7d6c60`, compared with `v2026.10.24-beta.1`. This is a review candidate, not a release approval or a statement about the eventual release tip. Assign the version from the release-day date only after the final source tip and duplicate-tag check are known.

### Added and changed

- Todo and scheduled-task entries now support separate name and detail text in the web UI; the backend continues to carry the detail field. This describes the name/detail split only, not completion of pending Task-query or passive-reminder work.
- The CLI and configuration use the `stand` identity in selected entry points. Existing `~/.nebflow` data-root and `nebflow.json` configuration fallback paths remain available; migration/rename behavior is not automatic in every path. See the compatibility notes in the evidence matrix before changing existing installations.
- Release workflow evidence in this baseline specifies the assembled jar as the beta artifact; desktop installer jobs are disabled. No installer/platform availability beyond that workflow was assessed.

### Fixed

- Attachment previews have an explicit supported-name/local-path contract and dedicated attachment preview contract tests; this does not claim that every attachment transport or remote path is covered.
- Search scope and chat-search routes have route-level tests. This does not claim every search provider or UI flow is regression-free.

### Compatibility and release gates

- This summary does not claim “no breaking changes.” Renamed CLI entry points, config/data-root naming, on-disk stores, and API/tool contracts have migration or compatibility surfaces that require release-owner review. The evidence matrix records concrete known transitions and unverified boundaries; retain a release hold if any applicable user migration action is unresolved.
- Unmerged T1/T2/T3 work, lifecycle production fixes, and Task query/passive-reminder changes are not represented as completed. Recheck the final integrated tip before release; do not infer their status from this baseline.
- No full build, full suite, end-to-end release rehearsal, or full compatibility audit was run for this changelog review. The old remote CI run is not evidence for this tip. Desktop installers remain disabled; the release workflow’s jar-only contract is the scoped statement.
