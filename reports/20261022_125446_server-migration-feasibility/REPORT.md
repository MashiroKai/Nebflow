# 服务器迁移可行性调研报告（台账 #722）

> 批次：服务器迁移可行性调研批（只调研 + 出方案，未动任何生产服务器）
> 日期：2026-10-22 · 执行：Nebflow 节点（delegate-nebflow-0d11cd1e）
> 纪律遵守声明：全程零写操作、零重启、零 DNS 变更；对 VPS 只做了只读 HTTP/DNS/ICMP 探测，未登录。

---

## 0. 结论先行

**混合可行。推荐「东风云承接全部自托管后端」，不建议「Mac 当服务器」。**

| 路线 | 结论 |
|---|---|
| ① 迁到本地主机（Mac） | ❌ 不可行（作为 7×24 服务器）。笔记本睡眠/离校/合盖即下线；实测当前网络**无全局 IPv6**；隧道地板时间虽然能降到 ~5ms，但在线率不可接受 |
| ② 迁到东风云（8C16G，免费到 2031-07-23） | ✅ **推荐主线**。性能（8C16G ≫ 现役 Vultr 小规格）、成本（0 元 vs 持续付费）、延迟（教育网同域，Mac↔relay 预计从 ~130-150ms 降到 **~5-30ms**）三赢。**唯一前置条件**：弹性公网 IP 的 443 入站可达性需 10 分钟实测（见 §2.2 / §4.0） |
| ③ 维持现状（Vultr 美国） | ✅ 作为基线与回滚兜底。全球可达、稳定、但慢且持续花钱 |

**关键事实（一行版）**：现在跑服务的是 **Vultr 美国一台小鸡（45.32.62.185，Debian 13，Docker）**，上面有 3 个 mystand vhost（link / auth=Logto / console）+ neblink.nebflow.space + staging；mystand.space 主站与 staging 在 Vercel（**不在迁移范围**）。东风云不是第三方商业云，是 **科大网络信息中心自建的 ZStack 私有云**（cloud.ustc.edu.cn，CERNET 骨干地址 114.214.0.0/16），「107比赛」实例即「一〇七杯」大赛奖励机。

---

## 1. 任务一：现付费 VPS 盘点（只读，已完成）

### 1.1 DNS 解析（dig @8.8.8.8，2026-10-22 12:40 +08:00 实测）

| 域名 | 解析 | 归属 | 用途 |
|---|---|---|---|
| `mystand.space` | 76.76.21.21 | Vercel（VERCEL-01） | 主站（Next.js，**不迁移**） |
| `staging.mystand.space` | CNAME `cname.vercel-dns.com` → 76.76.21.241 / 66.33.60.193 | Vercel | staging 前端（**不迁移**） |
| `auth.mystand.space` | **45.32.62.185** | **Vultr**（AS20473，45.32.32.0/19，US） | Logto SSO |
| `link.mystand.space` | **45.32.62.185** | 同上 | link-server 中继（= neblink.nebflow.space 别名） |
| `console.mystand.space` | **45.32.62.185** | 同上 | 控制台入口 |
| staging 中继 | `neblink-stg.45.32.62.185.sslip.io`（sslip.io 通配解析到 IP 自身） | 同上 | staging relay |
| NS | `dns29/30.hichina.com`（阿里云/万网） | — | mystand.space 与 nebflow.space 均在阿里云 DNS |
| TTL | A 记录均 **600s** | — | 切换前建议降到 60s |

**服务器唯一性**：已唯一确认 = Vultr 45.32.62.185，**无「待作者确认」项**；staging 与生产同机（靠 Host 头区分 vhost）。

### 1.2 延迟与 TLS（只读 GET 实测）

| 目标 | ping RTT | TCP connect | HTTPS 完整 GET | 丢包 |
|---|---|---|---|---|
| Mac → VPS 45.32.62.185 | min/avg/max = **114/151/186 ms** | **127 ms** | 0.44-0.52s（auth/link） | 20%（ICMP 限速可能） |
| Mac → `mirrors.ustc.edu.cn`（校园网基准） | **6.2 ms** | 19 ms（TLS） | 0.043s | 0% |
| Mac → Vercel 主站 | — | 151 ms（边缘 anycast） | 2.0-2.2s | — |

- traceroute（Mac→VPS）：14 跳全在 **CERNET 骨干（101.4.x）**，第 15 跳 38.150.75.x（Cogent）~101ms 后出境 → **VPS 在美国**，与 whois/BGP（AS20473 Vultr，US）互证。历史证据（仓内 evidence）：Mac↔VPS 隧道 ping 264-333ms 波动。
- TLS：全部 Let's Encrypt，auth/link 证书（YE1/YE2）有效期至 2027-01-15，主站/staging（YR1，Vercel 自动）至 2027-01-14。HTTP/3 已开（alt-svc）。

### 1.3 VPS 上跑的服务清单（来源：仓内 `NEBLINK_HANDOVER.md`（gitignore 的内部交接文档）+ 在线指纹实测）

| 服务 | 形态 | 端口 | 数据/密钥落盘 | 证据 |
|---|---|---|---|---|
| **neblink-caddy**（Caddy 2, alpine） | Docker | 80/443 对外 | `deploy/docker/caddy-data/`（证书，可自动重发） | 交接文档 §1；实测 `via: 1.1 Caddy` |
| **neblink-server**（Rust，=「link-server」） | Docker，内部 9090 | 仅内网 | `deploy/docker/data/neblink.db`（SQLite：users/networks/devices/refresh_tokens/device_credentials）；`deploy/docker/.env`（NEBFLOW_JWT_SECRET、GITHUB_CLIENT_SECRET、OAUTH_REDIRECT_URL 等） | 交接文档 §1/§5/§9；实测 `link.mystand.space/api/health → {"status":"ok"}` |
| **Logto（SSO/OIDC）** | 推断为 Docker（**交接文档未覆盖，后加的服务**） | 经 Caddy 反代 | 推断含独立 DB（Logto 官方要 PostgreSQL）+ SMTP connector 密钥（快照见 Mac `~/.stand/logto-m2m-sync.env` 与 `tools/logto-email/snapshots/`，0600） | 实测 `logto-core-request-id` 头 + `/oidc/.well-known/openid-configuration` 活跃；仓内 reports 证实 auth.mystand.space 租户 |
| **staging relay** | 同机另一 vhost（sslip.io 域名含 IP，不占 DNS） | 经 Caddy | 独立 staging 网络 | `scripts/coldstart.sh:181`；实测 health ok |

依赖项盘点（迁移要动的全部外部接线）：
1. **GitHub OAuth App**（Client ID `Ov23liu3nC6jzBmNIQNB`，回调 `https://neblink.nebflow.space/api/auth/github/callback`）——回调按域名，**域名不变则无需改**；
2. **阿里云 DNS** 两区（mystand.space / nebflow.space）——A 记录 45.32.62.185 → 新 IP，共 4 条（link/auth/console/auth.nebflow.space 别名，作者到控制台核对条数）；
3. **Logto**：应用 redirect URI 全部是域名（域名不变则不用动）；邮件 SMTP connector 密钥；M2M 同步凭据（Mac 侧）；
4. **coldstart.sh:181** 的 staging 默认值 `neblink-stg.45.32.62.185.sslip.io` **硬编码了旧 IP**——换 IP 后此域名失效，需改代码（唯一一处代码级耦合）。

---

## 2. 任务二：两条路线的公网可达性核实

### 2.1 路线一：USTC 校园网（自建服务器）

| # | 论断 | 定性 | 来源 |
|---|---|---|---|
| 1 | IPv4 出校需「网络通」认证；**IPv6 出校完全开放** | VERIFIED | LUG 官方 FAQ lug.ustc.edu.cn/wiki/doc/ustcnet-faq/ |
| 2 | 网络通**出口 1（教育网）无 NAT，直接公网 IP**；但**校外入站 80/443/3389 等敏感端口被封** | VERIFIED | 同上（FAQ 原文「该出口仅会阻止一些敏感端口被校外访问（如 80, 443, 3389 等）」） |
| 3 | **2021-11 起宿舍区、ustcnet、eduroam 的 IP 一律禁止校外主动 TCP 入站**（NAT/防火墙丢包） | VERIFIED | 同上；南七茶馆实证帖（taoky/LUG）「生活区的入站 TCP 包会被丢掉」 |
| 4 | 实验室有线网口大概率不受入站封锁（非 80/443 端口）；无线（ustcnet/eduroam）可能全禁入站 | 社区实证（非官方） | 南七茶馆 ustcforum.com/d/2341（taoky：「实验室的有线网口应该不是，其他的不确定…最好自己试一下」） |
| 5 | 静态（固定）公网 IP **仅限在校事业编制教职工申请**（¥5/月教育网、¥100/月国际权限=全互联网可见）；学生不可申请 | VERIFIED | NIC 官页 ustcnet.ustc.edu.cn/2015/0323/c33583a592045/page.htm |
| 6 | 近年科大 IPv4 紧张，部分有线口可能拿到非公网 v4 | 社区实证 | 南七茶馆 d/2341（taoky） |
| 7 | 宿舍 IPv6 为公网地址（/64），社区共识「对外暴露要么 frp 要么 IPv6」；NAT6 会大幅掉上行 | 社区实证 | 南七茶馆 d/2341、d/2500-nas |
| 8 | USTC 对校园网自建 WEB 服务未见公开的成文备案条款（NIC 官网规章制度区未检索到对个人服务器对外服务的专项规定） | **UNCHECKED（检索 3 次未获官方文本）** | 需向 NIC 求证（nic@ustc.edu.cn / 0551-63600800） |
| 9 | 作者 Mac 当前网络**无全局 IPv6**（实测仅 fe80 链路本地 + UTA 网段），校园 v6 直连路线的前提在作者当前位置未验证 | 实测 | 本机 `curl -6` 三目标全部失败 |

**小结**：校园网自建服务的公网可达性 = 「**v4 仅高端口 + 限教育网可达性 + 宿舍区全禁 + 学生无固定 IP 资格**」；v6 是唯一原生全端口通道但受位置与终端 v6 覆盖制约。作为**主用公网入口不可靠**。

### 2.2 路线二：东风云（作者手上这台 8C16G）

**平台身份（本次调研关键发现）**：

| 论断 | 定性 | 来源 |
|---|---|---|
| 东风云服务平台是 **USTC 网络信息中心自建/运营的校内云**，列于 NIC 官网「服务指南 → 基础平台」 | VERIFIED（官网导航条目） | ustcnet.ustc.edu.cn 首页导航（网页快照实测） |
| 云平台底座 = **ZStack Cloud**（控制台 `cloud.ustc.edu.cn`，build 标识 `kd-zuma`（科大），后端 `114.214.240.5:8100`） | VERIFIED（指纹实测） | 页面 JS `__ZSTACKOEM__: "ZStack Cloud"`；`cloud.ustc.edu.cn` HTTP 200 实测 |
| 后端地址段 = **CERNET 教育网（AS4538，114.214.0.0/16）** → 东风云「弹性公网 IP」池属于**教育网公网地址**，非商业云 BGP | VERIFIED | Team Cymru IP-to-ASN：114.214.240.5 = ERX-CERNET-BKB |
| 「107比赛」实例 = 「一〇七杯」算力与智能体开发大赛（NIC 主办，基于 107.ustc.edu.cn 本科生算力平台）的奖励云主机 | 高置信推断（命名 + 平台归属 + 获奖名单公告时间线吻合），非官方文档确认 | teach.ustc.edu.cn/notice/notice-info/20247.html；ustcnet 新闻 09/21 获奖名单公告 |
| ZStack 的「弹性公网 IP」= 1:1 DNAT（公网池 IP ↔ 云主机网卡），「端口转发」= 端口级 DNAT，「安全组」= 主机防火墙 | VERIFIED（产品机制，ZStack 公开产品能力） | ZStack 产品文档口径（平台侧参数仍未知，见开放问题） |

**平台截图既定事实**（作者提供）：8C16G，Ubuntu 24.04.4，内网 192.167.78.3，**无 IPv6**，弹性公网 IP 未绑定，有「弹性公网IP / 端口转发 / 安全组」三个功能页，到期 2031-07-23，宣传「高可用 NeverStop」。

**可达性推演（决定性约束）**：
- 东风云 EIP 属教育网地址 → **大概率继承教育网出口的端口策略**：非敏感端口（如 8443/9090 等）国内可达性较好；**80/443 校外入站存在被封的现实风险**（对齐 §2.1-#2 的出口策略）；**国际可达性需要「国际权限」**（NIC 静态 IP 页口径），东风云 EIP 池是否有国际可达配置**未知**。
- **但这不是判死刑而是 10 分钟可实测的事**：EIP/端口转发是平台正式功能页，NIC 既把它当奖励发给学生跑赛题服务，大概率允许对外暴露——具体到「443 能不能入站、带宽多大」必须实测（步骤见 §4.0）。
- 安全组默认策略未知（通常 ZStack 默认全放行或仅 ICMP，需控制台核对）。

---

## 3. 任务三：三方案对比与推荐

### 3.1 对比矩阵

| 维度 | ③ 现状：Vultr 美国（基线） | ② 东风云 8C16G（科大 ZStack） | ① 本地主机：作者 Mac（笔记本） | ①' 变体：实验室台式机（若存在，开放问题） |
|---|---|---|---|---|
| **公网可达性 v4** | ✅ 全端口全互联网 | ⚠️ EIP 绑定后国内大概率可达；**443 入站待实测**；国际可达存疑 | ❌ 家宽/校园 NAT；无固定 IP 资格（学生） | ⚠️ 实验室有线口高端口可入站（社区实证）；80/443 封锁同校园网 |
| **公网可达性 v6** | ❌ 未配置 | ❌ 平台未给 v6（截图） | ⚠️ 作者当前网络无全局 v6（实测） | ⚠️ 校园 v6 需现场验证（宿舍 /64 公网可参考） |
| **80/443 标准 TLS 入站** | ✅ 无障碍 | ⚠️ 待实测；封则走 DNS-01 + 高端口 | ❌ | ❌（同封） |
| **在线率/供电** | ✅ 商用机房 | ✅ 校级机房 +「NeverStop」高可用宣称；校级维护窗口风险 | ❌ **睡眠/合盖/离校/断电即下线** | ⚠️ 实验室供电随作息；假期风险 |
| **性能（relay 隧道地板）** | Mac↔VPS **127-151ms**（实测），隧道 ping 264-333ms | 预计 **5-30ms**（同在教育网，参照 Mac↔mirrors 6ms） | ~1-5ms（但无意义，在线率崩） | 预计 <10ms |
| **算力** | 小规格（Vultr 最低档推断，链接服务轻负载够用） | **8C16G 富余**（可再跑 CI/算力型任务） | 开发机本职，不能占用 | 视台式机配置 |
| **成本** | 持续付费（「又要花钱」痛点本体） | **¥0 至 2031-07-23** | ¥0 | ¥0（电费） |
| **运维负担** | 现状零增量 | 一次性迁移 + 之后同级；多一份 Ubuntu 24.04 环境 | 高（macOS 不适合做服务宿主，Docker Desktop 开销） | 中（自管硬件） |
| **数据合规/条款风险** | 低（境外，但个人数据出境侧问题反而不存在——服务端在境外） | ⚠️ 校内资源对外提供 web 服务的条款**未见成文**；个人项目属性需向 NIC 报备确认 | 低 | 低-中 |
| **周期风险** | 付费即续 | ⚠️ **毕业/离校后资源归属未知**（开放问题）；平台政策随学校变 | 无 | 毕业/离校即失效 |
| **单点与回滚** | 单点但稳定 | 与现 VPS 互为热备最易实现（双机分摊角色） | — | — |

### 3.2 推荐

**主推：迁移到东风云（方案②），保留 Vultr 至少 2 周作回滚兜底。** 理由：
1. **成本归零**：免费到 2031，直接消掉「又要花钱」；
2. **性能大幅提升**：relay 隧道地板从 ~130-150ms 量级降到 ~5-30ms 量级（10× 量级改善），手机经隧道的每次 REST 往返（现 ~0.6-1.5s）预计显著缩短——这是仓内 #538/#mobile-waterfall 两批 evidence 里反复点名的结构性瓶颈；
3. **算力升级**：8C16G 对 link-server + Logto 是碾压级冗余，还能兜住 staging；
4. **运维形态不变**：仍是 Docker Compose + Caddy，交接文档的命令原样可搬。

**否决 Mac 当服务器**：在线率是硬伤（睡眠/离校），且作者当前网络无 v6、无公网 v4；仅保留「开发节点」角色。

**实验室台式机**：若有 7×24 条件，可作为东风云的**教育网内备份节点**（非公网主入口），列开放问题。

**若 §4.0 实测判「443 不可入站」**：两个分支——
- **B1（仍迁东风云）**：对外改高端口（如 `:8443`）+ Caddy DNS-01 签证书（阿里云 DNS API）+ 设备/回调带端口。缺点：URL 带端口、部分企业网封高端口。
- **B2（暂不迁）**：维持 Vultr，等向 NIC 问清东风云 EIP 的端口/国际策略后再动。**若实测不过关且作者不能接受 B1，推荐 B2（不动优于动坏）**。

（Cloudflare Tunnel 路线——免端口入站——评估过：需把 mystand.space NS 迁到 Cloudflare、免费档流量绕境外边缘，引入新延迟与灰色合规带，**不主推，仅登记为备选**。）

---

## 4. 任务四：迁移步骤（纸面方案，不执行）

> 原则：**域名全部不变，只换 A 记录 IP** → OAuth 回调、Logto redirect URI、Cookie 域**都不用改**，迁移面收缩为「数据搬家 + DNS 一次切换 + 一处代码改 IP」。全程可回滚（老机保留）。
> 标注：🔒 = 需作者手动（平台控制台/密码/付费/实名类操作）。

### 4.0 第 0 步：东风云 EIP 可达性实测（10 分钟，决定走主线还是 B 分支）🔒

```bash
# 1) 🔒 东风云控制台：申请/绑定「弹性公网IP」到 107比赛 实例（记下 EIP，记作 <NEW_IP>）
# 2) 🔒 安全组：临时放行 TCP 443/80/22（若默认非全通）
# 3) 实例内起监听（东风云实例内，SSH 进去）：
sudo apt install -y ncat && sudo ncat -l 443 --keep-open   # 同法 80
# 4) 【关键】用手机「蜂窝网络（关 WiFi）」访问 http://<NEW_IP>/ 测试：
#    - 通      → 443 入站开放，走主线 §4.1-4.5
#    - 不通    → 试端口转发 8443→8443 再测；8443 通 → 走 B1；全不通 → 走 B2（暂不迁）
# 5) 顺带记录：绑 EIP 后的带宽实测（speedtest-cli）与是否限流量
```

同时 🔒 向 NIC（nic@ustc.edu.cn / 63600800）问一句：**学生用东风云 EIP 对外提供个人 web 服务是否需要校内备案/有无端口限制**（开放问题 #1 的官方口径）。

### 4.1 数据/密钥盘点（老 VPS 上，只读收集；作者手动 SSH）

```bash
ssh root@45.32.62.185
cd /root/neblink-server/deploy/docker
docker compose ps                                   # 确认容器清单（顺带看 Logto 是否也在此机 🔒 只读确认）
cp data/neblink.db /tmp/neblink-$(date +%Y%m%d).db  # link-server 全量数据
cp .env /tmp/env-backup                             # JWT secret / GitHub secret（0600 保管）
# Logto：若为独立 compose（推断含 Postgres），打包其 DB 与 connector 配置
docker exec -t <logto-db容器> pg_dumpall -U <user> | gzip > /tmp/logto-pg-$(date +%Y%m%d).sql.gz
scp 本地拉回三件套到 Mac：/tmp/neblink-*.db /tmp/env-backup /tmp/logto-pg-*.sql.gz
```
Mac 侧已有件：`~/.stand/logto-m2m-sync.env`（M2M 凭据）、`tools/logto-email/snapshots/`（邮件模板快照，可重推）。

### 4.2 新机初始化（东风云 Ubuntu 24.04）

```bash
# 🔒 控制台确认：弹性公网IP已绑、安全组放行 22/80/443（或 8443）
ssh ubuntu@<NEW_IP>
sudo apt update && sudo apt -y upgrade
sudo apt install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh          # Docker Engine + compose plugin
sudo usermod -aG docker ubuntu && newgrp docker
sudo timedatectl set-timezone Asia/Shanghai
# 加固：改 SSH 密钥登录、禁密码；ufw allow 22,80,443 && sudo ufw enable
# unattended-upgrades 默认已在 Ubuntu 启用，确认即可
```

### 4.3 服务重部署

```bash
# A. link-server（neblink-server 仓；若 GitHub 已转私有，从 Mac 本地镜像/备份推）
git clone <neblink-server 仓库> && cd <repo>/deploy/docker
cp <Mac拉回的 env-backup> .env && chmod 600 .env     # 沿用原 JWT/secret——设备凭证与刷新令牌才不会全体失效
mkdir -p data && cp <Mac拉回的 neblink-*.db> data/neblink.db
docker compose up -d --build                         # 结构与老机完全一致（交接文档 §8 同款命令）

# B. Logto（按老机实际形态 1:1 搬；若老机为 compose：同法 clone + .env + 导入 PG dump）
gunzip -c logto-pg-*.sql.gz | docker exec -i <新logto-db> psql -U <user> -d postgres
# C. SMTP connector/M2M：从 Mac 侧重推模板+连接器（仓内现成工装）：
cd tools/logto-email && python3 push_templates.py --env-file ~/.stand/logto-m2m-sync.env --apply
```

### 4.4 TLS 证书（两分支）

```bash
# 主线（443 入站实测通过）：Caddy 自动 HTTP-01，零配置变更
docker compose restart caddy   # 新 IP + 域名解析已切后自动签发

# B1（仅高端口入站）：Caddy 需 DNS-01（阿里云 DNS API）
# 🔒 阿里云 RAM 建子账号，授权 alidns DNS 读写，得 AccessKey
# Caddyfile 改用 caddy-dns/alidns 插件构建（xcaddy build --with github.com/caddy-dns/alidns）
# 并在 site 块加 tls { dns alidns {env.ALIYUN_AK} {env.ALIYUN_SK} }
```

### 4.5 DNS 切换（灰度顺序 + 回滚预案）🔒

```bash
# T-24h：🔒 阿里云 DNS 控制台把 link/auth/console/auth.nebflow 四条 A 记录 TTL 600→60
# T-0：🔒 按序改 A 记录 45.32.62.185 → <NEW_IP>：
#   1) staging relay 所用域（sslip 域随 IP 自解析，无需 DNS，直接改 coldstart 默认值）
#   2) link.mystand.space   → 等 health ok + 设备心跳复验
#   3) console.mystand.space
#   4) auth.mystand.space（最后切——SSO 会话 Cookie 域在 auth，切早了影响登录态）
# 代码侧一次性小改（唯一 IP 硬编码）：scripts/coldstart.sh:181
#   COLDSTART_STAGING_SERVER 默认值 → https://neblink-stg.<NEW_IP>.sslip.io
# 验证清单（逐条打勾）：
#   curl https://link.mystand.space/api/health                 # {"status":"ok"}
#   curl https://auth.mystand.space/oidc/.well-known/openid-configuration
#   浏览器走一次完整登录（auth → console），旧会话不被踢
#   设备端重新心跳可见对端；手机经隧道开一次控制台，对比 RTT 读数（应 5-30ms 级）
#   cert 有效期刷新为最新签发时间
# 回滚预案：老 Vultr 全程不销毁、原数据留存；回滚 = 🔒 阿里云控制台把 A 记录改回旧 IP
#   （TTL 已 60，~10 分钟全球生效），老机 Caddy 证书仍在有效期内，无需重签。
#   代价：切换窗口后新写入的数据（SQLite/PG 增量）回滚即丢 → 回滚前先在新机再 dump 一次。
# T+2 周：确认稳定后 🔒 销毁/停机 Vultr（成本归零点）。
```

---

## 5. 开放问题清单（不阻塞主线，按需补）

| # | 问题 | 谁来解 |
|---|---|---|
| 1 | 东风云 EIP 的 443/80 入站政策、带宽/流量上限、国际可达性；个人 web 服务的校内备案要求 | §4.0 实测 + 🔒 问 NIC |
| 2 | Logto 在老 VPS 的部署形态与 DB 落盘（交接文档早于 Logto 上线，未覆盖） | 作者只读 `docker ps` 确认 |
| 3 | 作者毕业后东风云资源归属/续期政策（2031 前毕业与否影响价值判断） | 作者与平台确认 |
| 4 | 实验室是否有可 7×24 的台式机（可作教育网内备份节点） | 作者 |
| 5 | mystand.space 是否有海外访客登录 auth（教育网 IP 国际可达性存疑会被此放大） | 作者看 Vercel/Logto 地域统计 |
| 6 | auth.nebflow.space 别名记录的准确清单（本次只按域名推断 4 条 A 记录，切换前在阿里云控制台逐条核对） | 作者控制台核对 |
| 7 | 东风云「无 IPv6」是否影响任何依赖方（当前链路均 v4，无影响；登记备查） | 已核：无 |

---

## 6. 来源与实测附录

**实测（本批，2026-10-22 12:40-12:55 +08:00）**：dig/DoH 四域名解析、whois+BGP（AS20473 Vultr US / AS4538 CERNET）、curl 延迟与 TLS 证书、traceroute（CERNET→Cogent 出境）、ping（VPS 114-186ms / 校园 3-10ms）、`link/api/health`、Logto OIDC discovery、staging sslip health、`cloud.ustc.edu.cn` ZStack 指纹、本机 v6 连通（无全局 v6）。
**仓内文档**：`NEBLINK_HANDOVER.md`（服务器架构/部署/数据/运维全量）、`scripts/coldstart.sh:181`、`tools/logto-email/README.md`、`evidence/20261019_183728_mobile-boot-waterfall/`（隧道 RTT 264-333ms）、`REBRAND.md`。
**网络来源**：LUG USTC 官方校园网 FAQ（lug.ustc.edu.cn/wiki/doc/ustcnet-faq/）；NIC 静态 IP 申请页（ustcnet.ustc.edu.cn/2015/0323/c33583a592045/page.htm）；NIC 官网服务指南（东风云服务平台条目）；南七茶馆实证帖（ustcforum.com/d/2341、/d/2500-nas）；科大教务处「一〇七杯」大赛通知（teach.ustc.edu.cn/notice/notice-info/20247.html）；ZStack 产品能力（公开产品文档口径）。
**UNCHECKED 项**：USTC 对个人自建 WEB 服务的成文条款（检索 3 次未获官方文本，转为开放问题 #1，未以猜测补齐）。
