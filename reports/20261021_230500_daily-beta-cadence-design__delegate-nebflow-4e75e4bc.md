# 勤发机制设计（候批）——main 有新提交则每日自动发 beta

> 批次：作者令 2026-10-21「勤发机制落成日常」③。**本文是候批设计：作者点头后才挂定时，本批未挂任何调度。**
> 依据：本批（beta.2）已验证的发布全链 + beta.1 先例 + release-pipeline 纪律（VERSION 单一来源 / 上传 gated）。

## 1. 目标与语义

「测试版要勤发」制度化：**每天最多一个检查点，发现 main 有未发的新提交 → 自动走一遍已验证的 beta 发布全链**。语义是「每日至多一发、有新东西才发」，不是「每日必发」。

- 只发 **beta** 渠道；stable 永不自动（stable = 两步发布 + 人工窗口，红线不动）。
- 官网 **prod 面（mystand.space manifest.json）永不自动动**；勤发只写 staging 车道（manifest-beta.json）+ OSS + GitHub prerelease。
- 生效面即时性：beta 安装链的真源头是 **OSS 指针 txt**（install.sh 读 `latest-beta-version.txt` → 拉 jar）+ jar 本体——这两件在发布链早期就生效；官网 staging manifest 是 M3 引擎与展示面。即：**勤发成功 = 用户当天就能装到新 beta**，不以官网部署速度为闸。

## 2. 触发条件（全部满足才起链）

| # | 条件 | 判定 |
|---|---|---|
| T1 | 每日定时到达 | 挂既有 Nebflow scheduled-tasks（建议每日一次，如 10:00 Asia/Shanghai；避开发版批手工窗口夜间时段） |
| T2 | main 有新提交 | `origin/main HEAD short sha` ≠ `manifest-beta.json.build`（上一次已发 beta 的构建号） |
| T3 | 工作区干净 | 发布用 checkout（worktree）`git status` 干净 |
| T4 | 无半途残留 | 上轮无未收口状态：`v<今日版本>` tag 不存在于 origin、OSS 无同名 jar 半截（Content-Length == 0 或缺失视为无） |

T2 是「要不要发」的唯一实质判据；T3/T4 是安全前置。命中不了 → 本轮空转记一行日志，**不告警**（空转是常态）。

## 3. 版本号递增规则

- 沿用本批实测算法：**查最新 tag 递增**。`git ls-remote --tags origin 'vYYYY.MM.DD-beta.*'`（YYYY.MM.DD = 今日）取最大 N → 新版本 `YYYY.MM.DD-beta.(N+1)`；当日无 beta → N=1。
- VERSION 文件为唯一版本号来源（无换行尾，字节格式与 beta.1/beta.2 一致）；jar 内嵌 VERSION + `build` = main HEAD short sha（9 位，与 manifest 口径一致）。
- 跨日 N 重置为 1 的自然语义：tag 前缀含日期，逐日独立空间。

## 4. 防重（三层，任一命中即 skip）

1. **tag 闸**：`v<VERSION>` 已存在于 origin → skip（说明今天已发过或撞名）。
2. **build 闸**：`manifest-beta.json.build` == 本次 main HEAD → skip（内容没变）。
3. **指针闸**：OSS `latest-beta-version.txt` 回读 == 本次 VERSION → skip（已发布）。

三层分别防：重跑、无新提交、上次链中断在 manifest 之后。命中即记 skip 日志（带命中闸名），零副作用退出。

## 5. 发布链（逐步复用本批已验证命令面）

```
bump VERSION → commit → tag v<VERSION>
→ node scripts/build-web.mjs && sbt -Dstand.webdist=1 assembly
→ sha256 本地
→ ossutil cp jar → oss://nebflow-releases/stand-assembly-<VERSION>.jar   （凭据 ~/.stand/secrets/aliyun-oss，mkcfg 式读入，值零打印）
→ OSS 回读下载 → sha256 逐字节比对（不一致 = 硬失败）
→ stand-website: public/updates/manifest-beta.json 入册（version/build/publishedAt/jar url+sha256+sizeBytes；minCompatibleEngine=现役机群版，不超前期——见本批实测注记）
→ node scripts/release/publish-updates.mjs --check-artifacts --upload   （schema 校验 + jar HEAD + 指针上传 + 回读 in sync）
→ push tag → gh release create v<VERSION> <jar> --prerelease
→ stand-website staging commit+push → Vercel 部署
→ 部署后验收：/updates/manifest-beta.json 版本显示 + gate:e2e staging（install:* 断言）→ 证据归档
```

（minCompatibleEngine 注记：beta.2 批实测——floor 若随新版本上抬，会超过活体 M3 机群版而触发拒装断更链；规则=**floor 保持「当前活体机群已 ≥」的版本**，随正式稳定点推进，不逐 beta 上抬。）

## 6. 失败告警与重试

- **构建类失败**（sbt/web 构建/测试性校验）：自动重试 1 次；再失败 → 告警停链。
- **发布面失败**（OSS 上传后任何步）：**绝不自动重试**——部分完成的发布重试可能产生重复面（tag 已在/jar 已在/manifest 已换的中间态），必须人工看清五件套状态再续。告警 → 停链 → 等人工。
- 告警通道：Nebflow 节点 blocked（根会话可见）+ Mail 作者一行摘要；内容含：失败阶段、错误原文、五件套状态盘点（tag / OSS jar / OSS 指针 / manifest-beta.json / GitHub release 各自 当前态 + 期望态）。
- 告警不轰炸：同一轮只发一次，后续进展靠人工触发续跑。

## 7. 需要作者拍板的三个参数（点头时顺带定）

1. **检查时刻**：默认建议每日 10:00（Asia/Shanghai）。
2. **执行宿主**：默认建议本机 Mac 的 Nebflow scheduled-tasks（构建 93s 实测可接受；若后续 CI 迁移落地，构建腿可改派 self-hosted runner，机制不变）。
3. **告警静默窗**：默认无（每失败必告）；如嫌吵可约定「同一 commit 的重复失败只告一次」。

## 8. 本批证据锚点

- 全链命令面与逐环证据：`reports/20261021_*_release-beta2__*.md`（本批发布报告）
- 指针回读 in sync、jar 字节级回读、manifest 校验器、发布原子性设计：`stand-website/scripts/release/publish-updates.mjs`（头部注释即规范）
- beta.1 先例：tag `v2026.10.21-beta.1`（2026-10-21 05:45Z，prerelease + jar 资产）
