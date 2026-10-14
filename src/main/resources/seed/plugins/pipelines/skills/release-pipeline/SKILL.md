---
name: release-pipeline
description: 发版流水线纪律（由 release-stable flow 蒸馏，按当前 semver 口径修正）——审→做→包→复审四角色闭环、VERSION 单一来源（semver）、beta/release 分支合并 --no-ff、tag 必须打在 release 分支的 merge commit 上、三平台打包验证、上传 gatted（永不自动 push/publish）。Use when 准备 beta 或 stable 发版：bump VERSION、合并进 beta/release 分支、打 tag、构建桌面安装包、CI 矩阵确认。
when_to_use: beta 迭代发版或 stable 发版；低频高危——走样代价大（tag 打错分支/漏 merge commit/假 artifact），本 skill 把验证清单文档化，执行前通读。
language: zh
status: active
last_verified: 2026-09-03
---

# Release Pipeline — 发版纪律

> 历史注记：旧 flow coder 流程为日期版号（YYYY.MM.DD）+ archive/scala 开发线，均已过时；本 skill 按当前现实改写——VERSION 为 semver（如 `1.4.1-beta.54`），开发主线为 `main`。若分支布局再变，以主仓 AGENTS.md「版本与发布管理」为权威。

## 分支与版本模型

| 分支 | 角色 |
|---|---|
| `main` | 开发主线——feature 合并进来时递增 PATCH |
| `beta` | beta 发版线——beta tag 在此 |
| `release` | stable 发版线——stable tag 在此；推送到 release = 递增 MINOR、PATCH 归零 |

- **VERSION 文件是唯一版本号来源**（repo 根，semver `MAJOR.MINOR.PATCH[-beta.N]`）；build.sbt 与 CI 原样透传该字符串。
- 准备 beta：版本号追加 `-beta.N`（N 从 1 递增）。
- Release 必须有 `CHANGELOG.md`。

## 四角色闭环（审→做→包→复审）

1. **审（reviewer 前置）**：核对发版前提（工作区干净、上一 beta 已验证、CHANGELOG 就绪），产出执行指令。
2. **做（coder）**：按程序执行版本 bump + 合并 + tag（下节）。
3. **包（packager）**：构建 + 验证桌面产物（下下节）。
4. **复审（reviewer 终审）**：过完整验证清单（下下下节），PASS 才算发版完成；FAIL → 带清单逐项打回重做。

打回循环：复审未过 → 执行方按报告逐项修复重做 → 再复审（LoopNode 或重触发由分发器选型）。

## 版本与 tag 程序（beta 为例；stable 同构）

1. 确认开发线工作区干净。
2. 读当前 VERSION；计算新版本（今日已有 beta → N+1；否则 N 重置为 1）。
3. 在开发线 commit：`chore: bump version to <VERSION>`。
4. 切到 `beta` 分支，`git merge <开发线> --no-ff -m "merge: ... for v<VERSION>"`。
5. **tag 打在 merge commit（beta 分支 HEAD）**：`git tag v<VERSION>`。
6. 切回开发线，报告新版本与 tag。

**铁律**：tag 必须在 release 分支（beta/release）的 merge commit 上——绝不直接打在开发线 commit；`--no-ff` 强制；不 amend 发版 commit；不强推；不 push（除非明确指示——push 由触发方/用户执行，push 到 release 分支即触发 CI 全矩阵）。

## 打包验证（packager）

构建：`sbt assembly` 后按平台：

- **macOS（dmg）**：`packaging/build-dmg.sh` → `build/dist/<PRODUCT_NAME>-<VERSION>-<ARCH>.dmg`；安装性最低验证 = mount smoke：`hdiutil attach -nobrowse -readonly <artifact>` 确认卷内 .app 结构后 `hdiutil detach`（有 GUI 会话才做 launch smoke）。
- **Windows（msi）**：`packaging/build-msi.sh`（需 WiX 3.x on PATH）→ 文件存在 + 尺寸合理；实际安装需用户窗口。
- **Linux（deb + app-image）**：`packaging/build-linux.sh`（需 dpkg/fakeroot）→ `dpkg-deb --info` 过。

产物命名规范：`<PRODUCT_NAME>-<VERSION>-<arch>.{dmg,msi,deb}` + `-app-image.tar.gz`。

CI 确认：`.github/workflows/auto-release.yml` 含三平台 package job（macOS dmg arm64+x64 / Windows x64 msi / Linux deb+app-image），release job 的资产收集与命名规范一致。

**上传 gated**：本流水线永不发布。资产附加发生在用户发布时（push release 分支 → CI 挂全矩阵）；如需向已发布 release 补挂 host 产物，**只 stage 不执行**：`packaging/upload-release-assets.sh v<VERSION>`。绝不 `gh release create`、绝不未经明确指示 publish。

## 复审验证清单（全绿才 PASS）

1. **VERSION 文件**：格式正确（beta `X.Y.Z-beta.N` / stable `X.Y.Z`）；相对上一版递增正确。
2. **tag 存在且格式正确**：`git tag -l "v<VERSION>"`；`v` 前缀必备。
3. **tag 位置（CRITICAL）**：`git branch --contains <tag>` 必须包含对应 release 分支——tag 只在开发线上 = 发版已坏。
4. **merge commit**：release 分支 HEAD 是双亲合并提交（`git log -1 --format="%P"` 两个 SHA）；commit message 匹配 merge 模式。
5. **分支一致性**：`git show <branch>:VERSION` 与 tag 一致。
6. **桌面产物**：packager 报告含版本+tag、产物清单（路径+尺寸，符合命名规范）、安装性验证结果、CI 矩阵确认——缺任一 → 打回。

受阻判定：git 状态异常无法安全继续（解不了的脏树/缺分支）→ blocked，描述现场并停。
