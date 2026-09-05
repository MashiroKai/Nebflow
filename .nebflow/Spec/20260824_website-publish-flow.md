# 官网发布流程：双域名分工方案

> 2026-08-24 用户拍板：nebflow.space 发正式版，neblink.space 发调试/预览版。本文档是发布流程的操作手册与基础设施选型记录。

## 1. 目标

| 域名 | 角色 | 内容来源 |
|------|------|----------|
| **nebflow.space** | 正式版（stable/release） | 仅 main 分支，经过合并把关的内容 |
| **neblink.space** | 调试过程版（dev/beta 预览） | 任意 feature 分支 / 在途工作，随时可覆盖 |

原则：正式域名永不被调试内容污染；调试域名随时可牺牲、可重建。

## 2. 现状（2026-08-24 核实）

- **Git**：`~/.nebflow/projects/nebflow-website`，main @ 59b52c2 为线上版本；当前开发分支 feat/logto-stage1；积压未合并分支：feat/docs-six-topics（7 commits）、feat/hub-page（4 commits）、style/visual-logo（6 commits）、integ/overnight。本方案建立的双通道流程首先用于消化这批积压。
- **Vercel**：单项目 `nebflow-website`（team `kais-projects-e8bd6cfb`）→ nebflow.space（Production，最后部署 10 天前）；无 vercel.json（纯 UI 配置）；CLI 已登录 kaimashiro-2206。
- **DNS**：两域名同注册商（阿里云 hichina，dns17/18.hichina.com）。nebflow.space A → 76.76.21.21（Vercel）；neblink.space 已注册但**无任何解析记录**，完全空闲可绑 Vercel。NebLink 服务端在 neblink.nebflow.space 子域（VPS 203.0.113.10）——与 neblink.space 主域零冲突，本方案不影响它。

## 3. Vercel 选型

| 方案 | 结构 | 优点 | 缺点 |
|------|------|------|------|
| ① **双项目**（推荐） | `nebflow-website`（prod）+ `nebflow-website-preview`（debug）各自绑域名 | 部署历史/域名/env 完全隔离；误操作爆炸半径各自独立；preview 项目可整删重建不动正式版 | 两个项目要各自维护（env 需同步一次） |
| ② 单项目 + 每部署 alias | 调试部署产生 `xxx.vercel.app` 后 `vercel alias` 到 neblink.space | 单项目管理面小 | alias 是指针操作，调试域名指向随每次手动 alias 漂移；正式/调试共享同一项目部署列表，历史混杂；alias 链无分支语义 |
| ③ 单项目双域名同内容 | 两域名都绑同一项目 | 零维护 | 完全违背目标——两域名永远同内容，调试域名形同虚设 |

**推荐 ① 双项目**，核心理由：

1. **隔离性**：调试部署的错误（构建失败挂掉的页面、实验性 env）永远不会出现在正式项目的部署历史里；preview 项目可以整个删掉重建，nebflow.space 零感知。
2. **分支映射清晰**：prod 项目 = main 自动部署；preview 项目 = 任意分支手动 `vercel --prod`（或后续接 beta 分支自动部署），语义一一对应。
3. **持久性**：neblink.space 作为 preview 项目的 Production 域名是稳定绑定，不像 alias 方案那样是指向某个具体部署的临时指针。

方案 ② 的唯一优势是少一个项目，但"调试域名"本质上需要一个**独立的部署目标**，alias 提供的是指针不是目标——故不采用。

## 4. 目标架构

```
GitHub MashiroKai/nebflow-website
├── main 分支 ──(自动部署)──► Vercel 项目 nebflow-website ──► nebflow.space
│                                                    （现有，不动）
└── 任意分支 ──(手动 vercel --prod)──► Vercel 项目 nebflow-website-preview ──► neblink.space
```

- **nebflow-website**（现有）：Git 连接 main，push/merge 到 main 即自动发正式版。**本方案不对其做任何改动。**
- **nebflow-website-preview**（新增）：**不连接 Git**（v1），部署全部走 CLI 手动触发——从哪个目录/分支部署，neblink.space 就显示哪份代码。零自动部署意外。

## 5. 正式发布流程（nebflow.space）

1. feature 分支开发完成 → 本地预览验证 → commit
2. 合并到 main（rebase + fast-forward 或 PR）
3. `git push origin main` —— Vercel 自动构建部署到 nebflow.space
4. 部署完成后访问 nebflow.space 抽查关键页面
5. 如需回滚：Vercel 控制台 `nebflow-website` → Deployments → 选中上一个好部署 → "Promote to Production"（或 `git revert` + push）

## 6. 调试发布流程（neblink.space）

**前提（一次性）**：本地有 preview 项目的 `.vercel` link（`.vercel` 已在 .gitignore，不会进仓库）：

```bash
cd ~/.nebflow/projects/nebflow-website   # 或任意 worktree
vercel link --project nebflow-website-preview --yes
```

> 注意：主目录当前 link 到正式项目。调试部署**务必在独立 worktree 里 link preview 项目**，避免主目录的 link 被覆盖后误把调试内容发上正式域名。推荐每个调试 worktree 各 link 一次。

**每次调试发布**：

```bash
cd /tmp/nb-web-<topic>          # 要预览的分支所在 worktree
vercel link --project nebflow-website-preview --yes   # 该 worktree 首次
vercel --prod                    # 构建并部署 → neblink.space 立即更新
```

- `vercel --prod` 把**当前目录的当前 checkout** 原样构建部署——分支不需要 push 到 GitHub，未 commit 的改动也会被打包（适合"还没想好要不要合并"的调试态）。
- 连续调试重复最后一条命令即可；neblink.space 始终指向 preview 项目最新的 production 部署。
- 不带 `--prod` 的 `vercel` 产生随机 `*.vercel.app` 预览 URL，不影响 neblink.space。

**未来可选增强（非 v1）**：preview 项目连接 Git 仓库并把 Production Branch 设为 `beta` 分支——push 到 beta 自动更新 neblink.space。该设置只能在 Vercel 控制台改（CLI 不支持设 production branch），v1 手动 CLI 部署已够用，不急。

## 7. DNS 记录清单（需用户在阿里云控制台操作）

登录阿里云 → 云解析 DNS → 域名 `neblink.space` → 添加记录：

| 记录类型 | 主机记录 | 记录值 | 用途 |
|----------|----------|--------|------|
| A | @ | 76.76.21.21 | 根域 neblink.space → Vercel |
| CNAME | www | cname.vercel-dns.com | www.neblink.space → Vercel（可选但建议） |

- TTL 默认 10 分钟即可。
- 添加后 Vercel 侧域名状态会从 pending 变为 valid（验证周期秒级到数分钟）。
- **不要**动 nebflow.space 的任何现有记录；**不要**把 neblink.space 的 NS 改到 Vercel（保持阿里云 DNS，NebLink 服务端子域在 nebflow.space 下不受影响）。

## 8. 环境变量方案

- **v1 零代码改动**：preview 项目不配置任何 env——官网的 NebLink 代理路由直连 `https://neblink.nebflow.space`（代码内常量），Hub mock 由 `HUB_API_MOCK=1` 显式开启（默认关，生产语义安全）。JWT secret 等已有 env 若调试页需要登录态，从正式项目复制同名变量到 preview 项目即可（Vercel 控制台一次性操作，按需）。
- **可选增强（仅记录，不实现）**：`NEXT_PUBLIC_SITE_MODE=preview` 注入 preview 项目，前端读它在页脚显示"调试版"标识。需要代码改动，待有真实误判需求时再做。

## 9. 回滚 / 恢复

| 场景 | 操作 | 对正式版影响 |
|------|------|-------------|
| 调试域名内容错了 | preview 项目再 `vercel --prod` 覆盖即可 | 无 |
| 调试域名不想要了 | Vercel 控制台删 `nebflow-website-preview` 项目 + 阿里云删上述两条 DNS 记录 | 无——两项目零共享状态 |
| 正式版发布出问题 | 正式项目 Deployments → Promote 上一版本，或 git revert + push | 本方案范围外（现有流程） |

## 10. 执行清单

### 已由 agent 执行（Vercel 侧，只增不改）
- [x] 创建项目 `nebflow-website-preview`（team kais-projects-e8bd6cfb）
- [x] 绑定域名 neblink.space 到 preview 项目（状态 pending——等 DNS）
- [ ] ~~配置部署分支策略~~ v1 采用手动 CLI 部署（见 §6），无需配置

### 待用户执行
- [ ] 阿里云云解析添加 §7 的两条 DNS 记录
- [ ] DNS 生效后在 Vercel 控制台确认域名变 valid；打开 https://neblink.space 验证（首次内容需先跑一次 §6 的 `vercel --prod`，或由 agent 代跑）
- [ ] （可选）preview 项目复制正式项目的必要 env 变量
