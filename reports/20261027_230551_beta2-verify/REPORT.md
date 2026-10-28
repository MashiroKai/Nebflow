# 重启后全量验证批（beta.2）REPORT

- 任务：#989 重启后全量验证批（引擎已重启到 beta.2，构建自 main tip 8989391ef）
- 执行窗口：2026-10-27 23:05–23:34（首轮）＋ 2026-10-28 09:51–09:58（引擎 09:37 二次重启后补完 #1/#6，并跨引擎重启加固 #5）
- 载体：本机 127.0.0.1:6138 控制台，真实 Chrome（browser-use 持久会话 beta2verify，CDP attach），playwright 黑盒 GUI 操作
- 版本自证：设置 → 关于 显示 `stand 2026.10.26-beta.2`（首轮设置面截图取证）
- 证据目录：本目录 `shots/`（24 张，全部落盘）

## 总表：项 → 预期 → 实测 → 判定

| # | 项 | 预期 | 实测 | 判定 | 截图 |
|---|----|------|------|------|------|
| 1 | PDF 大文件 | >5MB PDF 在 Canvas 整本渲染（修复前「Cannot display PDF: network error」） | 打开 `CZT/literature/MPT3128_TNS004172026.pdf`（7,487,883 B）：Canvas 页签正常打开，正文/公式/芯片架构图逐页渲染，滚动 1500px 后续页持续渲染（整本滚动视图，右下缩放控件 80% 在位），全文零「Cannot display PDF / network error / 加载失败」字样，控制台零 JS error | **PASS** | 71_pdf_7MB_open.png, 72_pdf_7MB_scrolled.png |
| 2a | apikey 明文（JeV keyRef） | 设置页 JeV keyRef 明文显示 | 设置 → Jev（插件分配）密钥管理：15 个 keyRef 全部明文直显（aliyun-oss / demo-accounts / … / social-feishu-app-secret / **typesafe-api-key** / vercel-…），与磁盘 ground truth（`.jev.keyRef` = `typesafe-api-key`，16 字符）一致；带权限过宽警示徽标 | **PASS** | 22_jev_keys_list.png, 21_settings_scrolled.png |
| 2b | apikey 明文（LLM 编辑弹窗预填真值） | LLM API 编辑弹窗预填已存真值 | 编辑服务商 zhipu 弹窗打开即预填：API Key 输入框 DOM value = 49 字符、前缀 `1ca`，与磁盘 `.llm.providers.zhipu.apiKey`（49 字符，前缀 `1ca`）逐位一致；kimi 同机制（未截图，机制同源）；带明文切换眼睛钮 | **PASS** | 23_llm_edit_dialog_masked.png |
| 3 | 设备角标 | 执行命令胶囊角标显示 `Mashiros-MacBook-Pro`（无 local、无 -iso- 后缀，hover 有完整原值） | 全量 DOM 普查：**66/66** 个 `tool-device-tag` 徽章文本恰为 `Mashiros-MacBook-Pro`（零 `local`、零 `-iso-` 后缀）；66/66 携带 `title="Mashiros-MacBook-Pro-iso-a659864a"`（完整原值）。视觉证据：胶囊「✓ 执行命令 — 29 行输出 *Mashiros-MacBook-Pro*」。注：title 原生提示为浏览器渲染层，无 DOM 节点，不可截入图，判定以 66/66 title 属性普查为准 | **PASS** | 31_capsule_badge.png, 32_capsule_badge_hover.png |
| 4 | 刷新按钮钉角 | yaml/docx 类自滚页签滚 600px 后刷新按钮视口位置不变、钉右下 | **docx 腿**：BGO正样真空热试验电测试流程-V1.1.docx 打开，⟳（从磁盘重新读取）初始 (1333,682)；滚轮 600px（scrollTop 实测 0→600 精确），按钮 rect 逐像素不变，钉右下（约距底 35px/距右 27px）。**yaml 腿**：CMakeConfigureLog.yaml 打开渲染，滚 600px 后 ⟳ 仍钉右下（显示坐标 1856,962 @1920 尺度） | **PASS** | 41_docx_tab_open.png, 42_docx_after_scroll600.png, 43_yaml_tab_open.png, 44_yaml_after_scroll600.png |
| 5 | 标签页防复活 | 关闭页签→重载控制台→不复活且不再自动加载 | 打开我自建的 CMakeConfigureLog.yaml 页签 → 页签胶囊右键菜单「关闭」→ page.reload()：重载后页签栏无该 yaml 页签、活动页签为既有图片页签（图09_GCR与SEP…png）、零自动加载请求；磁盘 `~/.stand/canvas_tabs.json` 墓碑记录 closedAt=1793115194932（23:33:14）。**加固证据**：今晨 09:37 引擎整机重启后重登，该页签仍未复活、墓碑仍在——防复活跨引擎重启成立 | **PASS** | 50_tabs_before_close.png, 53_tab_context_menu.png, 51_tab_closed.png, 52_after_reload_no_revive.png, 70_after_engine_restart_relogin.png |
| 6 | 多设备透明 | 本机控制台 bg-agents 面板对远端会话行可见（KAI 当前在线；拿不到远端活体→标待真机验证，不算失败） | 「后台子智能体」胶囊（badge=5）点开「后台任务·智能体」面板：5 条进行中 Delegate 行全部正常渲染（含本验证批自身一行，带实时 ↳Bash 子活动行与时长 21m43s——面板活体准确性自证）。行内无设备归属标注（deviceTokens 普查为空）；当前 KAI/DESKTOP-IJBBEOT 虽在线但**无远端活体后台会话**，远端行可见性无法黑盒触发。按任务口径：本地面 PASS，远端面**待真机验证**（不算失败） | **PASS（本地面）／待真机验证（远端面）** | 61_bgagents_panel2.png, 63_bgagents_rows_dump.png |
| 7 | 感知面板 | boot 不崩（P0 回归钉在位即可，无需真连邮箱） | 顶栏「感知」@731 点开：面板正常渲染「邮件 / 尚未连接邮箱」＋页脚「本地处理：邮件全文不出机」，无 JS error、无白板、应用不死。加固：今晨 09:37 引擎二次重启后控制台 boot 正常（重登后全功能可用） | **PASS** | 10_awareness_panel.png, 70_after_engine_restart_relogin.png |

## 结论

**7/7 项判定完成：6 项 PASS，1 项（#6）本地面 PASS＋远端面待真机验证；0 FAIL。**

## 附注（如实记录）

1. **跨两次引擎重启**：首轮 23:05–23:34 完成 #7/#2/#3/#4/#5；引擎 09:37 二次重启中断后，本晨补完 #1（PDF）、#6（bg-agents），并顺带把 #5 加固为「跨引擎重启不复活」、#7 加固为「二次重启 boot 不崩」。
2. 首轮 60_bgagents_panel.png 实拍为「7 任务」面板（按钮命中偏差），非 bg-agents 面板——已作废，item 6 以 61/63 两张重拍为准。
3. 首轮会话建立时控制台出现 WS 403（引擎重启后旧控制台会话失效），走 `stand token` → `POST /api/auth/console-ticket` → `/?ticket=` 票据链路重登（本地 console 账号，非门户）；今晨二次重启后同链路复用成功（04/70 两张登录后截图）。该现象与任务背景（引擎重启致旧会话失效）自洽，非缺陷。
4. 按任务纪律：只验证、未修任何代码；本批零代码改动，交付物仅本报告＋截图＋驱动脚本（`.nebflow/tmp/70~63-*.mjs`，工作区内不入库）。
5. #984 门户登录恢复不在本批范围（任务明示另行）；涉及门户隧道的项本次清单未含需门户登录的验证点。
