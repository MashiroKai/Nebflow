# PR #41 致谢关闭文案草稿

- 日期：2026-09-03
- 用途：作者确认后用于 GitHub PR #41 关闭评论（本文件仅为草稿，未发布；push 与 PR 关闭动作等作者确认）
- 贡献者：@JiashengZeng（分支署名 JWIN <contributor@example.com>）

---

## 中文版

感谢你为 #41 做的这份报告——根因分析非常准确。你定位到「跳转失败与 UI 语言相关」这个方向是对的：工具卡片的标签和摘要在渲染时做了本地化，而搜索索引里的摘要是原始（英文）文本，两者对不上，所以非英文界面下按文本片段跳转必然落空。这个诊断直接决定了修复的形状，我们完全采纳了。

审查补丁的过程中，我们发现在字段传递层还有一个缺口：点击跳转时，运行时会逐字段重建结果对象（关键词搜索路径和流式包装路径各有一处），你补丁里新增的 summary/content 候选字段在这两处重建时被丢掉了，跳转点实际拿到的仍是空值——如果只改跳转侧，反而会影响原本正常的英文路径。维护侧已在主线上把这条字段链路补齐（索引侧保留原始 summary/content，两处重建点原样透传，跳转侧按 content → summary → 原始拼接文本的顺序尝试候选），并补了覆盖中英文两种界面语言的回归测试。修复会随下批版本一起推送远程。

再次感谢这份高质量的贡献，欢迎继续参与 Nebflow 的开发——无论是继续报 issue 还是直接提 PR 都非常欢迎。

## English (short)

Thanks for the excellent root-cause analysis in #41 — your direction was spot-on: tool cards render localized label/summary while the search index keeps the raw text, so text-snippet jumps could never match under a non-English locale. While reviewing the patch we found a gap one layer deeper: the runtime click path rebuilds result objects field-by-field (keyword-literal rebuild + stream wrapper), so the new summary/content candidates never reached the jump site, and switching candidates there alone would have broken the working English path. We've completed the field-passing layer on the mainline (raw segments preserved at index time, copied through both rebuild sites, tried in order content → summary → legacy joined text at the jump site) and added regression tests covering both locales. The fix will ship with the next release push. Thanks again, and looking forward to more contributions.

---

## 事实底稿（内部备查，发布时删除本节）

- PR #41 分支：`pr-41-search-jump`（本地保留，未 push）
- 贡献者补丁：e843c998（normalizeMessage 保留 summary/content + scrollToMessage 候选切换）
- 缺口：runSearch 关键词字面量重建 + wrapItem 流式包装重建均逐字段构造，丢 summary/content
- 维护侧修复：主线 commit（含「Fixes #41」与根因致谢说明），字段链路四处补齐
- 回归测试：tests/pr41-locale-search-jump.spec.mjs（zh-CN Edit/Bash + en Edit，11/11 PASS）；msg-search-v3-smoke.mjs 38/38 PASS
- 措辞约束：不贴 commit 链接、不声称「已 merge 该 PR」；说「维护侧已在主线补齐、将随下批发布推送远程」
