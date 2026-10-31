# 「手机接入」入口撤除批截图取证（2026-10-31 22:55 作者令）

生成：`node tests/phone-access-removal-shots.mjs`（隔离 mock 实例，前端真实文件路由 + WS 夹具）。

| 文件 | 内容 | probe 结论 |
|---|---|---|
| `mobile-contacts-no-entry.png` | 手机端联系人页（390×844 暗档） | 页顶行 = 新的朋友/设备/发起群聊；`.mf-social-entry`=0、`[data-page="social"]`=0、活动栏 `display:none`、`#social-overlay` 未开；零 console error |
| `desktop-root-social-btn.png` | 桌面端聊天根（1440×900 暗档） | 左活动栏 `#social-btn`（smartphone 键）在（36×36）；mobile-layer 未激活 |
| `desktop-social-overlay-open.png` | 桌面端点按 social 键后 | `#social-overlay.on=true`，社交接口面（微信 iLink/飞书渠道卡）正常弹出——桌面同入口零改动 |
