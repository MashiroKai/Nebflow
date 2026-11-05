# jev-imagegen-align-llm 机械验收（2026-11-03 作者令 → 2026-11-05 落地）

作者令：Jev / 生图配置面板向 LLM 配置面板全面对齐（空态零预填、填完出已配置卡片、
`/model` 与设置里可切两面模型、零「API 密钥引用」）。

## 本目录

| 文件 | 内容 |
|---|---|
| `shots.cjs` | 自包含 DOM 断言 + 截图器（`page.route` 从磁盘服务真实前端、MockWebSocket 走真实分发，不碰运行实例）|
| `red.log` | 改前树（main `2af513702` 的 `resources/web` 快照）——**12/66** 通过（54 条红）|
| `green.log` | 本批树——**66/66** 通过，退出码 0 |

大图证据（14 张，三面 × {桌面 1440×900, 手机 390×844} × {深, 浅} + 空态）落在数据根：
`~/.stand/evidence/20261103_jev-imagegen-align-llm/shots/{red,green}/`。

复跑：

```bash
NG_WEB=<web 根> NG_OUT=<截图根> NG_LABEL=green node shots.cjs
```

## 结论

- 后端结构钉 `JevProvidersAlignmentSpec`：11/11 通过。
- 前端门禁：js-types / design-tokens / design-spec-sync / component-contract /
  self-explaining / circular 全 PASS；`check-scala-layers` 为 main 存量 CONFIG ERROR
  （未知顶层包 `perception`），非本批引入。
- 法医：11-03 两条批次链均只跑完侦察契约节点、分支零提交 ⇒ **从未落地**（不是被冲掉）。
