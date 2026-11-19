# logto-email · STAND 邮件模板双模式工装（v3 修复批）

四件套：模板源（spec + 生成器）、质量闸（静态断言 + 双模式渲染断言）、推送器（M2M PATCH 快照+回滚）、QA 验证码触发器。

## 背景（2026-10-20 修复批 v3）

作者症状：「暗色模式邮件是亮的，亮色模式邮件是暗的」——亮暗完全互换。
根因=模板 `prefers-color-scheme` 两分支配对反了。打捞结论：本批第一/二棒（delegate 5b4d6ed7 / 2225dcb0）均无落盘残留（无分支/worktree/悬空提交/项目元数据），但 **网站侧 lockup 资产已于当日 17:36 +08:00 发布**（`lockup-light.png`=黑墨、`lockup-dark.png`=白墨，ink 实测核实），且租户侧四模板在打捞时已是正确配对——即前棒已完成修复推送、死于验证之前。本批（第三棒）完成剩余管道：逐字核对（对映射表）→ 钉色加固 → 双模式渲染验证 → M2M PATCH 推送 → 真实验证码邮件。

## 设计映射表（唯一权威配对，template_spec.json）

| 面 | 页面底 | 卡片 | 标题/码 | 正文 | meta | 码板 | 分隔线 | lockup |
|---|---|---|---|---|---|---|---|---|
| light | #f5f6f7 | #ffffff | #141416 | #55565c | #6f727b | #f4f5f6 | #e8e9ec | lockup-light.png（黑墨） |
| dark | #101112 | #1b1c1e | #f2f2f4 | #b9bbc2 | #8f929b | #26272a | #313236 | lockup-dark.png（白墨） |

lockup 文件名按「服务的背景」命名（light 面=黑墨字）。**本批加固**：`<body>` 显式文字钉色（light `#141416` 内联 + dark `#f2f2f4 !important`）——此前 body 文字色依赖 UA 默认值（Chromium 暗色下默认变白），钉死后全元素零 UA 默认色依赖。

## 用法

```bash
# 1. 生成四模板（SignIn/Register/ForgotPassword/Generic）
python3 build_templates.py            # 写 src/main/resources/web/brand/stand/email/templates/
python3 build_templates.py --check    # 漂移闸（spec↔文件一致）

# 2. 质量闸
python3 test_email_templates.py [--net]   # 231 项静态断言：分支配对/零混色/钉色/锁kup墨色
node render_dualmode.mjs [outDir]         # playwright 双 colorScheme 渲染断言+截图

# 3. 推送（M2M；凭据走 env 文件，零回显）
python3 push_templates.py --env-file ~/.stand/logto-m2m-sync.env            # 漂移检查（只读）
python3 push_templates.py --env-file ~/.stand/logto-m2m-sync.env --apply    # 快照+PATCH+复核
python3 push_templates.py --env-file ... --restore snapshots/email-connector-<ts>.json  # 回滚

# 4. 真实验证码邮件（浏览器驱动真实注册面）
python3 send_qa_code.py --to <inbox> --usage ForgotPassword
node qa_send_observe.mjs <inbox>       # 带网络观测的复驱动（拿 send-code 响应状态）
```

## 本批终态读数（2026-10-20 20:0x–20:1x +08:00）

- 质量闸：静态 231 checks 0 failed；渲染闸双模式全绿（亮=亮卡黑 lockup，暗=暗卡白 lockup，零越面色）。
- PATCH：`PATCH /api/connectors/cmlj173ef4ho`（Email/SMTP connector，auth.mystand.space）应用四模板 body 钉色加固；re-GET 逐字节复核 **PASS**；复测漂移=0。快照：`snapshots/email-connector-20261020-201005.json`（0600，含 SMTP auth，**禁入库禁回显**）。
- 真实邮件：`POST /api/auth/forgot-password/send-code` → `200 {"ok":true,"verificationId":"9q1ky4jpwf8huxf3e5n0m"}`， ForgotPassword 模板验证码已发 QA 收件箱（qa-e2e@neblink.space），候作者 iOS 真机复验。
- 注意：本租户 SignIn 主路径=密码式（无码），SignIn 模板验证码邮件当前无可触发的用户路径（模板在役但仅密码式登录不触发）；作者如需真机验 SignIn 面，需先在租户启用 passcode 登录（未授权不动）。

## 边界

- 零 push、零 `--prod` 标志（PATCH 目标=auth.mystand.space 生产租户，系任务书④明令的 M2M 推送面）。
- 网站侧 lockup 资产系前棒已发布，本批零触碰（只实测墨色入库为闸）。
- Generic 模板现沿 SignIn 文案（subject「STAND sign-in code」）——沿前棒既定设计未改，如需独立文案另批。
