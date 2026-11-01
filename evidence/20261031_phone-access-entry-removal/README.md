# 「手机接入」入口撤除批截图取证（2026-10-31 22:55 作者令）

生成：`node tests/phone-access-removal-shots.mjs`（隔离 mock 实例，前端真实文件路由 + WS 夹具）。

| 文件 | 内容 | probe 结论 |
|---|---|---|
| `mobile-contacts-no-entry.png` | 手机端联系人页（390×844 暗档） | 页顶行 = 新的朋友/设备/发起群聊；`.mf-social-entry`=0、`[data-page="social"]`=0、活动栏 `display:none`、`#social-overlay` 未开；零 console error |
| `desktop-root-social-btn.png` | 桌面端聊天根（1440×900 暗档） | 左活动栏 `#social-btn`（smartphone 键）在（36×36）；mobile-layer 未激活 |
| `desktop-social-overlay-open.png` | 桌面端点按 social 键后 | `#social-overlay.on=true`，社交接口面（微信 iLink/飞书渠道卡）正常弹出——桌面同入口零改动 |

## 重派核验重拍（2026-11-01 08:17，delegate-nebflow-956a86c8）

原批三图于 main tip（c29b35be7）状态重拍覆盖（`node tests/phone-access-removal-shots.mjs`，同脚本同夹具）：

- 手机端 probe：`entryRows=0、socialPages=0、contactsBodyChildren=1（收编面本体）、firstRows=["新的朋友"]、activityBar display:none、#social-overlay 未开`；零 console error。
- 桌面端 probe：`#social-btn` 36×36 可见，点按后 `#social-overlay.on=true`——桌面同入口零改动。
- spec：`tests/mobile-dock.spec.mjs` 21/21 绿（含 D6 撤除判据）。D14/D14b 的行徽标判据收窄到 `.fm-conv-row .fm-row-badge`——待办改版批（e51dabcdd）在 `#fm-conversations` 顶加的入口行自带恒在 DOM 的 hidden `.fm-row-badge`，页级宽选误计；断言语义（会话行徽标面）不变。
