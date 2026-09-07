# AskUser 预览图标防裁切 R4 — 证据存档

分支 `实施-AskUser预览图标防裁切R3`（基线 db49c958，R3 tip 4dfec825 之上递进）。
作者看图打回 R3 修复（6427fa29）：「Icon 的底部还是有问题，没有完全显示，整体的感觉
是上面空间大，下面空间小，icon 不是居中显示的。」

## 根因（像素级取证链）

1. **离线扫描**（scan-png.mjs，对 R3 归档 after 截图）：图标墨迹在槽位内
   top=4.5 / bottom=0.0 / left=right=8.0 CSS px——底部贴死裁切线、上大下小，作者观察量化属实。
2. **活体四层探针**（previewclip-r4-probe.mjs，真实渲染路径 showOptions → buildOptionPreview）：
   全部图槽（SVG ×3 变体 + raster）一致 **img 盒在 40px 槽位内下移 dy=+4px**，40px 高 + 4px
   下移 → 溢出槽底 4px 被 `.option-preview` 的 `overflow:hidden` 裁掉（probe-r4.json）。
3. **机制隔离探针**（previewclip-r4-probe2.mjs）：computed `marginTop: 4px` 实锤；无 .bubble
   祖先的合成对照 dy=0；页内变异 `img.style.margin='0'` → dy=0（probe2-r4.json）。
4. **规则定位**：chat.css `.bubble.ai img, .bubble.injected img { …; margin-top: 4px }`
   （气泡内全部 img 的通用规则）作用于 AskUser 卡片里的 `.preview-img`；R3 的
   `.option-preview .preview-img` 规则未声明 margin，级联不冲突故 4px 存活——这才是 R1–R3
   一贯的裁切机制本身（cover→contain 只改了 letterbox 形态，没切除根因）。

## 修复（chat.css 单文件 +11 行）

`.option-preview .preview-img` → 选择器升为 `.option-box .option-preview .preview-img`
（(0,3,0) > (0,2,1)，否则 margin reset 在级联中败给气泡规则），声明补：
- `margin: 0` —— 切断气泡级 margin-top: 4px 侵入；
- `padding: 2px` —— 配合全局 border-box 把 object-fit 内容盒缩到 52×36，任意纵横比四向
  ≥2px 呼吸空间（方形图标渲染 36×36 完整在槽内）。

R3 的 contain + normalizeSvgDataUri 保留不动。

## 验收（previewclip-r4.mjs + run-r4.sh，标准配方 :8097 + /tmp/qa-previewclip）

- 像素断言（槽位截图墨迹扫描，dev>25 阈值，2x DPR）after 全过：4 图槽 × 明暗
  min4 ≥ 2（≥1 要求）、|top-bottom| ≤ 1（≤2 要求）、|left-right| ≤ 1；
  q1 computed margin-top = 0px。before 阶段同表全数复现（min4=0、dV 3–8px、margin-top=4px）。
- 五槽位口径：q1（无尺寸 SVG）/q1b（带尺寸 SVG）/q2（base64 SVG）/q3（160×60 raster）为像素
  断言对象；q4 swatch 按设计填满槽位（结构性断言 2 stripes 不变）；card 为合成截图。
- 结构回归网（R3 全量保留）：E1 无预览纯净按钮 / E2 坏 src 槽自隐 / E3 swatch /
  objectFit=contain / normalizer 注入与不动 / raster natural 160x60 —— 全过。
- 门禁：verify-web-assets 281/281 全 200；checkJs 288 < 基线 313 零新增
  （worktree 需 `ln -s <主仓>/node_modules node_modules` 以解析 typescript，符号链接不入库）。
- console 门禁豁免：`GET /api/nf-file` → 400 为 tip 既有背景噪声（R3 存档 before/after 均在），
  仅豁免该 URL，其余任何 console 错误即 fail。

## 文件

- `previewclip-r4.mjs` / `run-r4.sh` —— 验收 harness 与标准配方 runner（before/after 两相）
- `previewclip-r4-probe.mjs` / `previewclip-r4-probe2.mjs` / `scan-png.mjs`（+run-r4-diag*.sh）—— 诊断取证
- `harness-r4-{before,after}.json` —— 断言与像素测量数据
- `probe-r4.json` / `probe2-r4.json` —— 根因证据
- `instance-r4-{before,after}.log` / `web-assets-r4-after.log` / `checkjs-r4-after.log` —— 实例与门禁日志
- `shots/` —— before/after × 明暗 × (card+q1+q1b+q2+q3+q4) 共 24 图
  （同批拷贝于 /tmp/nebflow-previewclip-r4-shots/）
