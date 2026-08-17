# REBRAND.md — 改名执行手册（人工 checklist）

> 本文档由批4（2026-08-17）落地。机械部分由 `scripts/rebrand.sh --apply` 完成；
> 本清单覆盖**无法脚本化**的外部步骤。执行顺序自上而下，灰度期双跑。

## 0. 前置（脚本执行）

- [ ] `brand.conf` 全字段改为新品牌值（唯一编辑点——包括 `domain` 从占位换成真值）
- [ ] `scripts/rebrand.sh --dry-run` 审阅替换清单
- [ ] `scripts/rebrand.sh --apply`（前置校验+包树 rename+验证+sbt 全绿+冒烟+迁移冒烟+dmg/msi+安装脚本渲染）
- [ ] 审阅 `REBRAND-CHECKLIST.md`（脚本生成的本清单实例化版本）

## 1. DNS / 网络（阿里云）

- [ ] 新域名 A/CNAME 记录指向 VPS（203.0.113.10）
- [ ] 设备子域名（如 device.新域名）同指向
- [ ] 旧域 nebflow.space 301 → 新域名（保留 ≥12 月）

## 2. VPS（Caddy 灰度）

- [ ] Caddyfile 增加新域名 server block（旧域名 block 保留灰度期）
- [ ] `docker compose up -d` 生效；双域名均出证书

## 3. GitHub

- [ ] 新建 org；Settings → Transfer repository（旧 URL 自动重定向）
- [ ] OAuth App（`Ov23liu3nC6jzBmNIQNB`）回调 URL 更新（新域名 + 新仓库路径）；Client Secret 更新到 VPS `.env`

## 4. COS（发布镜像）

- [ ] 新桶开通（brand.conf `cosBucket` 值）
- [ ] CI 双写开关（新旧桶同时上传）灰度期开启
- [ ] 旧桶 `nebflow-releases-1411212853` 保留 ≥12 月；最后推送一个版本文件指向新桶

## 5. 官网（Vercel）

- [ ] 官网仓配置改新域名/新仓库地址 → deploy
- [ ] `NEBLINK_SERVER_URL` env 注入（指向设备服务新域名）

## 6. neblink-server（VPS 独立部署）

- [ ] `src/brand.rs` 品牌常量更新（PRODUCT_SITE / DEVICE_ORIGIN）
- [ ] 灰度期可用 `NEBLINK_CORS_ORIGINS` env 先加新域名 origin
- [ ] `.env`（参照 `deploy/docker/.env.example`）：SITE_DOMAIN / OAUTH_REDIRECT_URL / APP_PUBLIC_URL 三件套同源一致
- [ ] git remote 换新 org；VPS `git pull` + `docker compose up -d --build`

## 7. 资产

- [ ] 新 logo 三件套：favicon.svg / logo.svg / og.png（人工设计）
- [ ] dmg/msi 图标随 packaging 脚本（brand.conf 驱动）重打包

## 8. 公告

- [ ] README 更名说明（旧名 → 新名，数据目录兼容承诺：旧 `~/.nebflow` 自动迁移，copy 非 move 可回滚）
- [ ] 版本公告；旧桶版本文件推送迁移版指引

## 凭据红线

VPS 凭据在主仓 `NEBLINK_HANDOVER.md`（**gitignored**）。本清单及任何代码/文档
只允许引用其**路径**，一律不复制内容。

## 兼容性承诺（用户侧）

- 数据目录：旧 `~/.nebflow` 首次以新名启动自动 **copy** 迁移（原目录保留可回滚，`.rebrand-migrated` 标记防重跑）
- 环境变量：旧 `NEBFLOW_*` 前缀永久回落识别（launchd/脚本不破）
- 配置文件：旧 `nebflow.json` 双读回落，首次写回新名
- localStorage/cookie：前端 branding.js 旧键迁移（读旧写新删旧）
- `window.Nebflow`：新全局名 + 永久兼容别名
- 协议（D2）：`X-Neblink-Device` / `/api/device/*` / `neblinkServer` 字段**不变**——存量配对设备零影响
