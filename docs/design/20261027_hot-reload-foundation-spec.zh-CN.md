# 热更新地基 — 方案段与地基层（台账 #919，承 #916 / 作者令 2026-10-26 12:37）

批次：hot-reload-foundation（方案段 + 地基层 + 验证钩子；**本批不承诺全量热更**）

## 0. 目标与口径

作者令：「为『改完不重启就生效』打地基——先出方案段（前端静态资源与引擎代码分别能热到什么程度、边界在哪），再实现地基层；本批只做地基与验证钩子」。

「生效」的判据分层（每层独立可验证，不混谈）：

| 层 | 改动面 | 不重启生效程度 | 生效载体 |
|---|---|---|---|
| L1 前端静态资源（web/ → build/web-dist） | JS/CSS/HTML/资产 | **改完 → 重建 bundle → 已开页面自动生效**（无需重启引擎、无需手动刷新） | 磁盘 dist 逐请求读 + 内容寻址 URL（既有）+ 本批 stamp 轮询自愈 |
| L2 前端 dev 树（sbt run 热编辑） | JS/CSS/HTML | 改完 → **手动刷新即生效**（文件恒 no-cache）；HTML 编辑可被 stamp 探测 | 逐请求读源文件（既有） |
| L3 引擎代码（Scala → jar） | 引擎行为 | **不能进程内热替换**（边界，见 §2）——走既有热重启链（#916 detached apply + HealthProbe + 回滚） | 进程替换，秒级、无感 |

## 1. 前端静态资源：能热到什么程度（方案段）

### 1.1 既有链（本批不重建，只补最后一环）

```
src/main/resources/web  ──scripts/build-web.mjs──▶  build/web-dist/（内容寻址：assets/app-<hash>.js）
                                                            │
StaticRoutes（引擎，逐请求）                                  ▼
  · DiskDist: index.html = build/web-dist/index.html   ← 每请求读盘，no-cache ⇒ 永远新鲜
  · 资产: /assets/* 带 ?v=<16hex> ⇒ immutable（Content-Addressed，4df51ddcd 批）
  · sw.js: 缓存键 = 完整 URL（含版本参数）——新 index ⇒ 新 URL ⇒ 绕开旧缓存
```

由 4df51ddcd（缓存击穿批）与 W1/磁盘双面批奠基：**引擎侧服务面已经零重启可换字节**。
缺口只在**最后一环**：已打开的页面不知道「服务端换字节了」——用户必须手动刷新。

### 1.2 本批地基：asset stamp 轮询自愈（前端自动生效面）

- ** stamp 单源**：`GET /api/nf-asset-stamp` → `{stamp, source, version}`。`stamp` = 引擎**实际伺服的 index.html 原始字节的 SHA-256 前 16 hex**（三个静态面各自的单源读数，与 indexResponse 同源——见 AssetStampRoutes；brand 注入是逐请求动态面，不进指纹）。重建 bundle 必改 index.html 引用的 hash 文件名 ⇒ index 字节变 ⇒ stamp 变。
- **前端钩**：`web/js/hotStamp.js`（零依赖叶子模块）——启动捕获 boot stamp → 45s 低频轮询（`document.visibilityState === 'visible'` 才发）+ focus 即时补查 → stamp 变化 ⇒ **空闲时自动 `location.reload()`**：
  - 忙时让位：`state.busySessionIds` 非空（有在跑回合）不 reload，挂 pending 待空闲拍；
  - 防环：reload 前写 sessionStorage 时间戳，20s 内不二次 reload（stub 侧异常不会把页面打进 reload 循环）；
  - 诊断/测试缝：`window.__nfHotStamp` + `_hotStampTestHooks()`（checkNow / applyRemote / state）。
- **生效链闭环**：改 web 源 → `build-web.mjs` → 磁盘 dist index 换字节 → 引擎逐请求读盘（零重启）→ 下一个轮询拍 stamp 变 → 页面空闲自 reload → 新 index → 新 hash URL → immutable 缓存自然绕开。**全程零引擎重启、零手动刷新。**

### 1.3 边界（诚实清单）

1. **DevTree 面**（sbt run 热编辑）：js/css 源文件是 no-cache 裸腿，手动刷新即生效；stamp 只跟 index.html 字节（module 编辑不挪 stamp）——dev 树的全文件监听属 watcher 级特性，不在地基批（开放项 R1）。
2. **BundledDist 面**（jar 打包）：classpath 冻结 ⇒ stamp 每 boot 恒定 ⇒ 轮询零 no-op 语义正确（服务面本来就不会变）；换字节必须换 jar = 走 L3。
3. **reload 时机**：忙时（有在跑回合）永不自动 reload；后台标签页不轮询（visibility 门）——回来 focus 时补一拍。
4. **sw.js 双保险不变**：SW 缓存键=完整 URL；reload 后新 index 引用新 URL，SW 网络优先回源。stamp 轮询不替代 sw 更新流，是它的上游扳机。
5. **隧道脸内联打包面**（ConsoleAssetBatchRoutes）：index 同样逐请求生成 ⇒ stamp 同样有效（hash 取的是源 index 字节，与打包面同源）。

## 2. 引擎代码：边界在哪（方案段）

- **进程内热替换不做**：JVM 生产态换已加载 Scala 类（HotSwapAgent/类加载器隔离 + cats-effect 运行态迁移）成本与风险都远超收益——**明确不做**，这不是「暂缓」是「边界」。
- **正解 = 既有热重启链**（#916 批，`core/hotrestart` + `core/hotupdate`）：两段式 update（download+verify / apply 分离、校验不过不切换、健康自检不过回滚）+ detached apply（launchd/schtasks 内建）+ SuccessorGate/HealthProbe（起不来绝不顶掉活实例）。引擎代码「改完生效」的产品形态 = **秒级无感进程替换**，由该链承担；本批零触碰（避免两批共写一面的冲突）。
- **衔接点（本批唯一交集）**：stamp 载荷带 `version`（`nebflow.Version.string`）——前端可诊断「stamp 没变但 version 变了 = 引擎被热重启过」；重启链的健康自检（tier-4 version-match）读的是同一单源。两链通过这个字段互认，不共享代码。

## 3. 地基交付面（本批实现）

| 件 | 落点 | 说明 |
|---|---|---|
| stamp 端点 | `gateway/AssetStampRoutes.scala`（新）+ RestApiRoutes 级联挂载 | 三静态面单源指纹；disk 面走 (path,mtime)→hash 缓存（AssetVersions 同形） |
| 前端钩 | `web/js/hotStamp.js`（新）+ main.js 一行 init + vendor-globals.d.ts 声明 | 零依赖叶子；忙让位 / 防环 / 诊断缝 |
| 验证钩（Scala） | `gateway/AssetStampRoutesSpec.scala`（新） | DiskDist 指纹随文件字节变；source 标签映射；缺文件回落 |
| 验证钩（前端） | `tests/hot-stamp-reload.spec.mjs`（新） | 变 stamp → 空闲自动 reload；忙时挂起、闲时补 reload；防环闸 |

## 4. 开放项（登记，不在本批）

- R1：DevTree module 编辑的自动探测（chokidar/OS watcher 级，dev 工作流增强）。
- R2：reload 前的 toast 预告（「新版本已就绪」）——先静默自动（作者口径「改完就生效」），反馈再补。
- R3：stamp 轮询与 restartStatus 帧合流（热重启链广播相位时顺带推 stamp，省一轮 REST）。
