# 头像上传不可用——线上链路断点排查报告（只读）

- 日期：2026-09-02
- 排查方式：全程只读（vps-ssh 只执行 docker ps/inspect/exec-grep/log、git log、cat、admin API dump；curl 仅无副作用探测：OPTIONS 预检、空 body POST、dig）。未改任何配置、未 reload、未部署、未 kill。
- 结论先行：**断点只有一层——neblink-server 的 CORS 白名单不含官网 staging 域 `mashiro.staging.nebflow.space`**。后端已部署、端点可达、Caddy 已配、共享卷正常。次级问题：前端把一切失败伪装成「暂未开放」，掩盖了真实错误。

## 排查矩阵（任务要求 5 层）

| # | 层 | 状态 | 证据 |
|---|----|------|------|
| 1 | 后端部署版本 | ✅ 正常 | VPS `/root/neblink-server` 在 `5308e54`（含 `e30bb30` 头像实现 + `01ca6f4` opaque token 内省修复，后者 2026-08-28 提交、早于镜像构建 09-01 15:12 UTC）；运行容器内 `/app/neblink-server` 二进制 grep 命中 `api/avatar` ×1、`oidc/me` ×1 |
| 2 | 端点可达性 | ✅ 正常 | `POST https://neblink.nebflow.space/api/avatar` → **403 `{"error":"Missing token"}`**（路由存在、鉴权层应答，非 404）；`https://api.nebflow.space/api/avatar` → 同样 403；`api.neblink.space` → NXDOMAIN（已退役）；DNS：两域均 → 203.0.113.10（VPS Caddy） |
| 3 | Caddy 反代 | ✅ 正常 | 运行时配置（admin API `GET /config/` dump）含全部 5 个 host；`neblink.nebflow.space` 与 `api.nebflow.space` 两块均有 `handle_path /avatars/* → root /var/www/avatars + file_server` 和 `handle → reverse_proxy neblink-server:9090`；无 body 限制指令（Caddy 默认不限，2MiB 由应用层 `DefaultBodyLimit` 执行）；两容器同在 `docker_neblink-net` 网络 |
| 4 | 共享卷 | ✅ 正常 | `docker_avatars` 卷同挂 server（rw）与 caddy（**ro**）；卷内有 2026-08-28 的两个成功上传文件（`63mdczgnd8gy-*.jpg` 73KB、`kt3tw0h7eee4-*.png` 70B），证明整链路历史上通过过 |
| 5 | staging 前端 env | ⚠️ 未设（非断点） | Vercel 项目 `nebflow-website-preview` env 列表**无** `NEXT_PUBLIC_AVATAR_UPLOAD_URL`、无 `NEXT_PUBLIC_LOGTO_API` → 前端走代码默认值 `https://neblink.nebflow.space/api/avatar`。该默认域名可达且正确，**未设 env 不是断点**；但 `account-api.ts:23-26` 注释仍说默认值是「placeholder that 404s」——已过时，与实际矛盾 |

## 断点定位：CORS 白名单（浏览器在预检处拦截）

**运行容器 env**（`docker inspect neblink-server`）：

```
NEBLINK_CORS_ORIGINS=https://neblink.space,https://www.nebflow.space
```

叠加代码内建（`src/brand.rs:26-40`，`cors_allowed_origins()` = PRODUCT_SITE + DEVICE_ORIGIN + dev + env 追加）：

- `https://nebflow.space`（PRODUCT_SITE）
- `https://neblink.nebflow.space`（DEVICE_ORIGIN）
- `http://localhost:3000`、`http://localhost:9090`
- env 追加：`https://neblink.space`、`https://www.nebflow.space`

**`https://mashiro.staging.nebflow.space` 不在列表中。**

### 实测预检对照（2026-09-02 14:24 UTC）

| 请求 | 响应 | 判定 |
|------|------|------|
| `OPTIONS https://neblink.nebflow.space/api/avatar`，Origin=`https://mashiro.staging.nebflow.space` | 200，回了 allow-methods/allow-headers/allow-credentials，**唯独没有 `access-control-allow-origin`** | ❌ 浏览器拦截实际请求 |
| 同上，Origin=`https://www.nebflow.space`（env 白名单内） | 200 + `access-control-allow-origin: https://www.nebflow.space` | ✅ |
| 同上，Origin=`https://nebflow.space`（代码内建） | 200 + `access-control-allow-origin: https://nebflow.space` | ✅ |

机制：前端 `uploadAvatar()`（`lib/account-api.ts:165-177`）发的是**带 `Authorization` 头的跨域 POST** → 必触发预检 → 预检无 ACAO → 浏览器抛 TypeError → `SettingsContent.tsx:227-229` 的 catch-all 把它显示成「Avatar upload is not available yet」。用户看到的文案与真实故障（CORS）完全无关。

佐证：server 日志 24h 内 **零** avatar 相关条目——请求从未穿透到 handler（被浏览器挡在预检）；`patchAccount` 目标 Logto（`auth.neblink.space`）实测对 staging 与 prod origin **都放行**（预检均回 ACAO），链路第二步无断点。

### 时间线备注（非断点，记录备查）

- Caddy 容器 StartedAt = 09-02 01:11 UTC，早于 Caddyfile 最后修改（09-02 04:16 UTC）和 `5308e54` 提交（01:22 UTC）；但运行时配置 dump 证实已是最新状态（含 console/api 块、无 basic_auth）→ 期间有人做过 reload，**运行配置 ≠ 磁盘文件不是问题**。
- `NEBLINK_AVATAR_PUBLIC_BASE` 未设 → 返回头像 URL base 回退 `DEVICE_ORIGIN` = `https://neblink.nebflow.space`，Caddy 在服务 `/avatars/*` ✅，持久化 URL 无断点。

## 修复动作清单

### 主修复（必做）——CORS 白名单加 staging 域

1. **VPS** `/root/neblink-server/deploy/docker/.env`：
   ```
   NEBLINK_CORS_ORIGINS=https://neblink.space,https://www.nebflow.space,https://mashiro.staging.nebflow.space
   ```
2. **重建容器使 env 生效**：`cd /root/neblink-server/deploy/docker && docker compose up -d neblink-server`
   - ⚠️ 注意：`docker compose restart` **不会**重新解析 compose environment 注入——必须 `up -d`（重建容器）。server 无状态敏感数据（SQLite 在 `/data` 卷），重启窗口数秒。
   - 风险：低。JWT secret 不变，现有会话 token 继续有效；卷数据不丢。
3. **建议顺手**：把该值同步进仓库（`deploy/docker/.env.example` 注释示例或直接更新 VPS 上已提交的 compose 注释），避免下次重建 .env 时回退。本地仓库 `95cda59` 已修过透传链路，env 本身能到位（本次容器 env 已证实）。

### 备选方案 B（如需覆盖 Vercel 每分支随机 preview 域）——代码级后缀匹配

`src/brand.rs:68-71` 的 `AllowOrigin::predicate` 改为支持 `*.nebflow.space` 后缀匹配（精确匹配优先，后缀兜底）。
- 收益：所有 staging/preview 域自动可用，不再逐个加白。
- 风险：中——安全边界放宽到「任意 nebflow.space 子域」；需重新构建镜像 + 部署（`docker compose build && up -d`）；需评估是否接受。

### 备选方案 C（彻底消 CORS）——官网同域反代

`next.config.ts` rewrites 把 `/api/avatar/:path*` 代理到 `https://neblink.nebflow.space/api/avatar`，前端 `AVATAR_UPLOAD_URL` 改相对路径。浏览器同源请求 → 无预检。
- 收益：任何部署环境（含所有 preview 域）零 CORS 问题。
- 风险：中低——需 Vercel 部署；server 端收到 `Origin` 不再是浏览器页面域（同源请求不带 Origin，无影响）；改动跨两个项目。

### 次级修复（建议，不阻塞主链路）

4. **`lib/account-api.ts:22-26` 注释更新**：默认值已是真实可用的生产端点，删除「placeholder that 404s」误导性注释。
5. **`app/settings/SettingsContent.tsx:227-229` catch 细化**：至少 `console.error(err)` 保留诊断；理想情况区分 TypeError（网络/CORS）、401/403（token）、413（超 2MiB）、400（格式），分别给文案——别把一切伪装成「暂未开放」。风险：纯前端文案/日志，低。

### 无需改动（明确排除）

- Caddyfile：无需改（/api 反代、/avatars 静态块都已在线上生效）。
- 共享卷：无需改（挂载正确、权限正确、历史文件可读）。
- 后端代码/镜像：无需重建（env-only 修复）。
- Vercel env：无需设 `NEXT_PUBLIC_AVATAR_UPLOAD_URL`（默认值即正确端点）。

## 验收条件（修复后逐条可测）

1. `curl -sS -D - -o /dev/null -X OPTIONS https://neblink.nebflow.space/api/avatar -H 'Origin: https://mashiro.staging.nebflow.space' -H 'Access-Control-Request-Method: POST'` → 200 且含 `access-control-allow-origin: https://mashiro.staging.nebflow.space`（二值）
2. staging 页 `/settings` 真机上传 <2MiB PNG → 绿色成功提示，头像刷新（用户可见）
3. `docker exec neblink-server ls /var/www/avatars` 出现新 `*.<hash8>.png` 文件（二值）
4. server 日志出现 `avatar uploaded + persisted` 条目（二值）
5. 回归：prod `https://nebflow.space` 与 `https://www.nebflow.space` origin 预检仍回 ACAO（二值）
