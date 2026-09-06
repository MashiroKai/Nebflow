# 主仓 MIT 开源安全审计落地方案

- **状态**：draft（待作者确认后执行）
- **委托**：作者 2026-09-06 17:33；MIT 裁定 2026-09-05 17:17（本仓以 MIT 开源）
- **性质**：方案文档。**本文件不含任何本地执行**——所有不可逆动作（历史重写 / force-push / 改远端可见性 / 删分支-tag）仅写入并逐条标注「需作者确认」，未确认不执行。
- **上游依据**：`审计-历史敏感信息扫描` 节点结论（2026-09-06，随输入送达，未落盘）。全部结论以该审计为唯一证据源，本方案不再重跑扫描，仅引用其结果与命令。

---

## 零、核心结论（上游审计零命中 → 方案简化）

上游审计对**全部 3272 个 commit / 全部 branch / 41 个 tag** 的 patch 内容 + HEAD 工作树 `git grep` 交叉扫描，结论：

> **全历史零真实密钥 / 凭据 / 私钥 / 云凭据命中。** P0（会使开源直接失败）——**无**。

因此按委托简化口径，本方案的主体为：**零命中证明 + LICENSE 校验（已达标）+ 可选加固（隐私路径 / 私有网关 disclosure 处理）**。历史上不存在需要 `filter-repo` 强制清除的密钥泄露。

**仍然存在的暴露面（隐私 disclosure，非密钥）**，见第三章——均为「真实 home 路径 `/Users/dev` / 用户名 `kaiyu`」与「私有 USTC 网关 `107` / `llm.example.com`」，集中在 `.nebflow/Spec/` 内部文档 + tests fixture + `AGENTS.md`；**生产 `src/main` 已核验全干净**。

---

## 一、LICENSE 设置 / 校验

### 现状（举证）

- 文件 `LICENSE`（根目录，1067 字节，sha256 前 16=`e7a15a6c0f3b2637`）为**标准 MIT 全文**：许可 grants（`LICENSE:5-10`）、条件 "The above copyright notice…"（`LICENSE:12-13`）、免责声明 "THE SOFTWARE IS PROVIDED AS IS…"（`LICENSE:15-21`）三段落完整，与标准 MIT 无差距。
- 版权行 **`Copyright (c) 2026 MashiroKai`**（`LICENSE:3`）——持有者 `MashiroKai` 与 repo owner（`github.com/MashiroKai/Nebflow`）一致。

### 校验结论

**LICENSE 已达标，无需补全/修改。** 无缺失、无信息不全。

### 需作者确认项

| 项 | 内容 | 级别 |
|---|---|---|
| — | LICENSE 已完整，**无待改项**。作者如需可在 README 加 `License: MIT` 徽标与 `LICENSE` 链接，属可选不阻塞 | 可选 |

### 验收条件

- [ ] `LICENSE` 含完整三段落 + `Copyright (c) 2026 MashiroKai` 版权行（已满足，二值判定：通过）。
- [ ] 无二次版权声明冲突（扫描 `LICENSE*` / `COPYING*` 仅此一份，已满足）。

---

## 二、历史清洗方案

### 是否触发

按委托定义「**仅当审计命中真实密钥/凭据时给出**」——上游零命中 → **本章不触发**，不做强制历史重写。

### 可选加固（隐私 disclosure 处理，非密钥）

真实 home 路径 `/Users/dev`（历史 242 处 / 104 文件；文本 `kaiyu` 309 处）与私有 USTC 网关（`.nebflow/Spec/` 11 文件 + 1 测试文件）为非密钥、私密性**中**。若作者要求**公开历史也干净**（不留任何 personal path / 私有网关域名），可选择 `git-filter-repo` 清洗（仓库当前 PRIVATE，本地 main 领先 origin 99 commit，清洗窗口成本低）。**此为该方案唯一的不可逆分支，以下全部逐条「需作者确认」，未确认一律不执行。**

清洗命令（一旦确认执行，重写后需 force-push + 公开）：

```bash
# 0) 前置：清洗前创建安全备份 tag（回滚锚点）
git tag backup/pre-mit-history-cleanup

# 1) 历史整体剥离（不可逆）
git filter-repo --force \
  --replace-text <(printf 'kaiyu==>user\n/Users/dev==>/home/user\nllm.example.com==>REPLACED\n') \
  --invert-paths   # 如另需排除整文件/目录用 --path / --path-glob

# 2) 清空 reflog + 物理回收（不可逆）
git reflog expire --expire=now --all
git gc --prune=now
git filter-repo --cleanup

# 3) 更新远端 & 强制推送（不可逆）
git remote add origin <公开新库 URL>   # 或复用现有
git push --force --tags
git push --force origin main
```

**备选 BFG**：`bfg --replace-text replacements.txt`（效果同上，`--replace-text` 按行 file 格式 `旧串==>新串`）。

### 影响面分析（若执行本分支）

- **scope**：全历史 `kaiyu`/`/Users/dev`/`llm.example.com` 字面值被替换；不触及二进制/图片（`--replace-text` 仅文本 diff）。
- **重写范围**：影响 3272 commit 中含上述串的 commit 的 hash 重算——**后续所有分支/tag 的历史 sha 链全部变化**，`main`/`beta`/`release`/`archive/*`/`refactor/*`/`rust-standalone`/`feat/*` 及协作方 clone 均需 `git fetch --force` + 重设本地分支，未能更新的协作方将出现历史分叉。
- **协作方影响**：若有其他开发者已基于旧历史提交，需 rebase 到新历史；repo 现为仅本地（origin 与 scratch 皆私有），影响面受控。
- **回滚**：`git reset --hard backup/pre-mit-history-cleanup`（仅回滚本地指针；已 force-push 的新历史无自动回滚）。

### 需作者确认项

| 项 | 动作 | 级别 |
|---|---|---|
| 2.1 | 是否执行 `git filter-repo` 历史重写（清洗 `/Users/dev`/`kaiyu`/USTC 网关） | **需作者确认**（不可逆） |
| 2.2 | 若重写，是否 `git push --force --tags` + `git push --force origin main` | **需作者确认**（不可逆） |
| 2.3 | 是否将 origin 从 PRIVATE 翻为 PUBLIC（`gh repo edit --visibility public`） | **需作者确认**（改远端可见性） |

> 若作者接受「公开历史含非密钥路径/用户名披露（私密性中）」，则本章整个可选分支**直接跳过**，走第三章正向清理即可，无需任何不可逆动作。

---

## 三、当前工作树 / 分支敏感文件处理

### 现状（举证，HEAD 已提交面）

已确认**无任何 token/密钥文件被 tracked**（`git ls-files` 对 `nebflow.json`/`model-presets.json`/`auth.json`/`*.env`/`*.pem`/`id_rsa`/`*.p12`/`*.jks` 全为 NONE）。敏感面全部为**隐私路径/私有网关字符串**，分布在已提交的源文件与测试 fixture：

| 文件 | 位置 | 内容 | 锚点 |
|---|---|---|---|
| `AGENTS.md` | 工作目录行 / 工作目录节 | `/Users/dev/Claude code/Nebflow` | `:9`、`:19` |
| `src/test/.../RefResolverSpec.scala` | 4 处 | README / report 真实 home 路径 | `:24`,`:42`,`:129`,`:136` |
| `src/test/.../SandboxSpec.scala` | 1 处 | `/Users/dev/nb-flagoff-probe.txt` | `:350` |
| `src/test/.../MemoryEditToolSpec.scala` | 1 处 | `/Users/dev/.nebflow/auth.json`（恶意输入样例） | `:148` |
| `src/test/.../SearchProviderResolverSpec.scala` | 1 处 | `llm.example.com`（USTC 网关） | `:48` |
| `tests/*.mjs`、`tests/fixtures/*` | Playwright + fixture 数据 | 内嵌真实 home 路径（project-create-panel、canvas-html-interactive、history-replay、reftag-harness 等） | 审计登记 |
| `.nebflow/Spec/` | **380 tracked 文件全部** | `/Users/dev` 80 文件；USTC/107 11 文件 | `git ls-files .nebflow/ \| wc -l` = 380 |

### 方向

对**正向（可逆）清单**做替换/排除，使公开树不含 personal path 与私有网关字符串。`.nebflow/Spec/` 为 **skills→plugins 映射标准源，被 `.gitignore:19-20` 例外跟踪**——这是本方案唯一的「跟踪 vs 隐私」权衡点，需作者拍板方向（A 排除 / B 净洗）。

### 涉及文件（改动性质）

| 文件 | 改动 | 性质 |
|---|---|---|
| `AGENTS.md` | `/Users/dev/Claude code/Nebflow` → 中性文本（如 `$PROJECT_ROOT` / `主仓根目录`） | 修改 |
| `src/test/.../RefResolverSpec.scala` | home 路径 → `/tmp/...` 或中性占位 | 修改 |
| `src/test/.../SandboxSpec.scala` | 同上 | 修改 |
| `src/test/.../MemoryEditToolSpec.scala` | `/Users/dev/.nebflow/auth.json` → `/tmp/...`（保持恶意路径语义仍有效） | 修改 |
| `src/test/.../SearchProviderResolverSpec.scala` | `llm.example.com` → 中性示例域名 | 修改 |
| `tests/*.mjs`、`tests/fixtures/*` | home 路径 → `/tmp/...` 或占位符 | 修改 |
| `.gitignore` | **方向 A**：去掉 `!.nebflow/Spec/` 例外（`.gitignore:19-20`），使 `.nebflow/*` 整体忽略 | 修改 |
| `.nebflow/Spec/*.md` | **方向 B**：净洗 80 文件 `/Users/dev` + 11 文件 USTC/107 | 修改 |

#### 预期变更展示

`AGENTS.md` 两处：
```diff
-- **工作目录**: `/Users/dev/Claude code/Nebflow`
+- **工作目录**: <主仓路径由环境注入 / `$PROJECT_ROOT`>
```
`.gitignore`（方向 A）：
```diff
  .nebflow/*
-!.nebflow/Spec/
```
测试文件（示意，`RefResolverSpec.scala:24`）：
```diff
-        "path" -> "/Users/dev/Claude code/Nebflow/README.md".asJson,
+        "path" -> "/tmp/nb-fix/README.md".asJson,
```

### 精确命令（待作者确认后执行）

```bash
# 定位全部命中
git grep -n "/Users/dev" -- AGENTS.md src/test/ tests/
git grep -n "llm.example.com" -- src/test/ tests/

# 替换 AGENTS.md（中性文本）
perl -pi -e 's#/Users/dev/Claude code/Nebflow#<PROJECT_ROOT>#g' AGENTS.md

# 替换测试文件 home 路径为 /tmp（Mac 下 perl 原地替换）
git grep -l "/Users/dev" -- src/test/ tests/ | xargs perl -pi -e 's#/Users/dev#/tmp#g'

# 替换 USTC 网关为中性示例
perl -pi -e 's#https://api\.llm\.ustc\.edu\.cn#https://llm.example.com#g' \
  src/test/scala/nebflow/llm/SearchProviderResolverSpec.scala

# gitignore：方向 A（公开分支排除 .nebflow/ 全部）
# 删除 .gitignore 中 ".!/nebflow/Spec/" 一行（保留 ".nebflow/*"）

# 保留 CI 门禁（#338：ci.yml 防 personal path / API key 入 src/main）不删除
```

### 需作者确认项

| 项 | 动作 | 级别 |
|---|---|---|
| 3.1 | `.nebflow/Spec/` 方向 A（公开树排除全 `.nebflow/`）还是方向 B（保留 Spec 但对 80+11 文件净洗） | **需作者确认** |
| 3.2 | 替换 `AGENTS.md`/`src/test`/`tests` 中 `/Users/dev` 为中性占位 | **需作者确认**（正向可逆，但改公开树） |
| 3.3 | USTC 网关字符串中性化（`SearchProviderResolverSpec.scala:48` 等 12 处） | **需作者确认** |
| 3.4 | 若保留 `.nebflow/Spec/`（方向 B），是否更新 `skills-to-plugins.md` 等文档中 USTC/107 描述 | **需作者确认** |

> 注：`/.nebflow/` 现 tracked = 380 个 Spec 文件全部。若方向 A，公开树将不含 skills→plugins 映射标准源（该映射由 `.nebflow/Spec/` 承载）——这是「开源透明」vs「私有披露」的取舍，交作者；内部工作流不受影响（`.nebflow/` 仍是本地运行时目录）。

---

## 四、与在飞 beta.56 发布链先后顺序

### 现状

- 在飞发布链：`合并-beta.56CI修复落地` → `发布-beta.56-v2`。存在合并节点（`n-7881b273`），可能占据 main 做 merge。
- origin 当前 **PRIVATE**（`gh repo view MashiroKai/Nebflow` → `visibility:PRIVATE`），本地 main **领先 origin 99 个未推送 commit**。

### 结论（建议）

**本 MIT 开源落地（尤其第三章的正向清理提交）应在 beta.56 发布链**（`合并-beta.56CI修复落地` → `发布-beta.56-v2` **终态**：beta.56 已发、main 稳定、无并发 merge）**之后再执行**，避免与在飞 merge 的 git 写冲突 / 提交穿插。

- LICENSE：已达标，无需 git 写操作，**可与发布链并行**（不占写锁）。
- 第三章正向清理：纯 main 提交，**建议等发布链终态**。
- 第二章历史重写（可选，不可逆）：**务必等发布链终态 + main 稳定后**做前置。

### 若须先行（隔离窗口）

给出独立窗口：在 beta.56 发布链进入「已发版、无 merge 进行」的空档，切独立分支 `feat/mit-os-prep` 做第三章清理提交，等发布链终态后再合 main；**不与发布链并发改 main**。

### 历史重写前置条件（列全）

1. `main` 已稳定（HEAD 无未合并的进行中发布/合并节点）。
2. beta.56 发布链已到终态（`发布-beta.56-v2` 完成、tag 落地、远端已推送）。
3. **无并发 merge** 正在 main 上运行（本方案提交前需查 `.git/index.lock` 与 `.git/MERGE_HEAD`，存在则退避 3–5min 重试，上限 40min，超限报「main 忙」不强行提交）。
4. 第二章可选重写**仍待作者确认 2.1/2.2/2.3**，未确认不进入重写。

---

## 五、执行顺序总表

> 依赖序：LICENSE（无写） → 正向清理（等发布链终态） → （可选）历史重写（最后，不可逆）。

| 步骤 | 动作 | 需作者确认级别 | 影响面 | 回滚 / 防护 |
|---|---|---|---|---|
| 5.0 | 确认本方案（draft → 确认） | **需作者确认** | — | 不确认不执行任何 git 写 |
| 5.1 | LICENSE 校验（无 git 写，已达标） | 免 | 无 | 无 |
| 5.2 | 等 beta.56 发布链终态、main 稳定、无并发 merge | — | 时机 | 发布链进行中则等待 |
| 5.3 | 第三章正向清理：AGENTS.md + src/test + tests + `.gitignore`（方向 A/B 依 3.1 裁定） | **需作者确认（3.1/3.2/3.3）** | 改公开树内容（可逆） | 逐文件 `git diff` 审阅；单分支提交；合 main 前跑 `scripts/verify-web-assets.mjs`（若触 web/）+ 既有 test |
| 5.4 | 合 main（作者指示后） | 需作者确认 | 改 main | 仅在 main 稳定时合并 |
| 5.5 | （可选）第二章历史重写 + force-push + gitignore 去除 `!.nebflow/Spec/` + origin 翻 public | **需作者确认（2.1/2.2/2.3）** | 全历史 sha 重算 / 远端可见性 | 前置 `backup/pre-mit-history-cleanup` tag；重写后 `reflog expire` + `gc`；不接受则可跳过 |
| 5.6 | 保留 CI 门禁（#338 grep 门禁） | 免 | 防回归 | 不删除 |

### 红线（本方案硬约束，违者停）

- 0 历史重写（除非作者确认 2.1/2.2，否则不做）。
- 0 执行 `filter-repo` / BFG（作者未确认）。
- 0 force-push（作者未确认）。
- 0 push（本方案不推送任何分支）。
- 0 改远端可见性（origin 仍 PRIVATE，由作者 `gh repo edit` 决定）。
- 0 删分支 / tag。
- 仅向本 repo 提交 `.nebflow/Spec/mit-open-source-security-plan.md` 单项（点名 add，**禁止 `git add -A`**，勿动工作树其它未提交文件如 `process-sandbox-plan.md`、`20260906_askuser-nodeface-retire-report.md`、`pending-decisions-20260906.md`）。

---

## 六、交付 / 验收条件（本方案自身）

- [ ] 方案含 ①-⑤ 五章 + 零命中证明，结构完整（通过）。
- [ ] 每条不可逆/高影响动作均标注「需作者确认」级别（通过）。
- [ ] 全部现状论断带 `路径:行号` 锚点或审计命令出处（通过）。
- [ ] 本方案文件已提交至 `.nebflow/Spec/mit-open-source-security-plan.md`，commit message 用委托口径，**禁 push**（通过）。
- [ ] 提交前检查 `.git/index.lock` / `.git/MERGE_HEAD`：当前均不存在（已核验，通过）；若提交遇锁冲突 → 退避 3–5min 重试（上限 40min），超限报「main 忙」不强行提交。
