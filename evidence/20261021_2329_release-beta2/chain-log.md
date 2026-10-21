# 发布链证据 · v2026.10.21-beta.2（终端读数实录）

> 批次：作者令 2026-10-21「测试版要勤发」首拍。链路 = beta.1 已验证发布管道全链照跑。
> 时间：2026-10-21 22:40–23:35 (+0800)。执行宿主：本机 Mac（Mashiros-MacBook-Pro）。

## 1. 版本号与 tag

- 最新 tag 实测：`git tag --sort=-creatordate | head` → `v2026.10.21-beta.1`（其余为 2026.9.x / v1.4.1-beta.5x 旧代）
- 新版本：**2026.10.21-beta.2**；VERSION 文件 17 字节无换行尾，字节格式与 beta.1 一致（`git show 02f7626e1:VERSION | xxd` 对照）
- 提交：`1ab024df2` chore(release): VERSION 2026.10.21-beta.1 -> 2026.10.21-beta.2（delegate/delegate-nebflow-4e75e4bc 分支）
- tag：`v2026.10.21-beta.2`（annotated），与分支同推 origin（GitHub）成功

## 2. 构建（sbt assembly + web dist）

```
node scripts/build-web.mjs
  chunks: 160 lazy chunks · js 176 sources, orphans: 0 · form: UNSEALED
sbt -Dstand.webdist=1 assembly
  [success] Total time: 93 s
  Built: target/scala-3.5.2/stand-assembly-2026.10.21-beta.2.jar (110,274,860 B)
```

- jar sha256 = `502fcf0495b3eab32660aeab7fbaf7c092ed5e9195117bb583909db7013c7661`
- 版本来源 = VERSION 文件（build.sbt `version := IO.read(file("VERSION")).trim`），jar 内嵌 VERSION

## 3. OSS 上传与回读核验

- 工具：ossutil 1.7.19（sha256-pinned zip `10ece4d3…fd02ec5` 校验后解压；凭据 `~/.stand/secrets/aliyun-oss` 经 0600 文件按行解析注入，值零打印）
- 上传：`ossutil cp -f …jar oss://nebflow-releases/stand-assembly-2026.10.21-beta.2.jar -e oss-cn-hangzhou.aliyuncs.com` → Succeed 110,274,860 B / 19.1s
- 回读：`HTTP 200, Content-Length: 110274860`；全量下载回读 sha256 与本地 jar **逐字节一致**（`502fcf04…3c7661`）

## 4. manifest-beta.json 入册 + 指针回读 in sync

- 仓：`~/.stand/projects/stand-website`（staging 分支，commit `67ea391`）
- 内容：version=2026.10.21-beta.2 / build=1ab024df2 / publishedAt=2026-10-21T14:58:24Z / jar url+sha256+sizeBytes=110274860 / minCompatibleEngine=**2026.10.21-beta.1**（决策注记：floor 保持「活体 M3 机群已达」版本；若随新 beta 上抬会拒装断更链——beta.1 机群是当前唯一 manifest 读取面）
- 校验器：`node scripts/release/publish-updates.mjs --check-artifacts --upload`
  - 两 manifest schema 校验 PASS
  - stable jar HEAD 200 (64,456,812 B)；beta jar HEAD 200 (**110,274,860 B** = 入册值)
  - 双指针上传 OSS；回读：`latest-beta-version.txt -> 2026.10.21-beta.2 (in sync)`；`latest-version.txt -> 2026.9.19 (in sync)`

## 5. GitHub prerelease + jar 资产

```
gh release create v2026.10.21-beta.2 <jar> --prerelease --title "Nebflow v2026.10.21-beta.2"
→ https://github.com/MashiroKai/Nebflow/releases/tag/v2026.10.21-beta.2
API 核验: isPrerelease=true · publishedAt=2026-10-21T15:01:41Z
assets=[stand-assembly-2026.10.21-beta.2.jar, size=110274860]  ← 与 OSS 字节数一致
```

- GitHub 侧 443 直连不稳：git push / gh api / release 上传全程 `https_proxy=http://127.0.0.1:7890`

## 6. 官网 staging 部署与版本显示

- manifest 入册 commit `67ea391` push staging → Vercel 自动部署 `dpl_AmPeWxDwmt` READY（ref=staging, sha=67ea391d；注：排障时曾误判「未触发部署」——v6 deployments API 带 teamId 过滤返回旧页，project-scoped 无 teamId 复查即见；git 集成在位且工作正常）
- **staging.mystand.space/updates/manifest-beta.json 实测**：`version=2026.10.21-beta.2, build=1ab024df2, jar.sha256=502fcf04…3c7661` ✓

## 7. 部署门禁（deploy-e2e 留档，本目录 JSON 同存）

| 轮 | 判级 | 拆解 |
|---|---|---|
| pre-push（2026-10-21-1500-staging） | GREEN-PARTIAL 33/0/2 | skip=idp:google/github 网络不可达（已知环境边界，人工臂项） |
| post-deploy 首跑（2026-10-21-1521-staging） | RED 29/4/2 | ①install:manifest-beta-version + install:rendered 两红 = **测试夹具钉过期**（钉=beta.1，run-e2e.mjs `EXPECTED_BETA_VERSION` 为发版批自有维护点）——站点已正确服 beta.2；②launch:github/google fetch failed = github 直连间歇（pre-push 同刻全绿，已知边界）。**非部署缺陷** |
| **终跑（2026-10-21-1530-staging）** | **GREEN-PARTIAL 33/0/2** | 钉 bump（e82a259）后 install:* 全绿（版本显示=beta.2、四命令齐、页=manifest 一致）；launch:github/google 本轮全绿；唯二 skip = IdP 人工臂（google 网络不可达 / github 需真机登录往返），逐条已交代。**beta.2 staging 验收收口** |

## 8. 未做 / 边界

- stable 面（manifest.json / mystand.space 生产域）零触碰
- 桌面三平台安装包（dmg/msi/deb）未打：CI 打包 job 自 2026-09-06 FROZEN（证书期封印），beta 渠道只发 assembly jar（beta.1 同口径）
- IdP 侧社交键真实往返 = 人工臂，本批无真机补证（两轮 gate 的 skip/fail 已逐条交代）
