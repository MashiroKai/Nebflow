# impl-row-reply — 行互动框 + 多行回复（证据档）

批次：impl-row-reply · 2026-11-06（骨架 3709b3b0b → 本批收尾）
作者口径：点待办行**不再跳详情/编辑页**，原位浮出互动框（图3「浮起 + 轻微上移」）；
点外部 / 再点同一行 / Esc 收起；回复 = 多行输入（回车只换行），发送 = 独立钮，
消息体 `#<待办名> <回复>` 逐字进主会话（input.js `send()` 单源出口，不拼帧）。

## 实跑

```
node tests/todo-row-reply.spec.mjs        # 16 PASS / 0 FAIL
node tests/todo-create-bottom.spec.mjs    # 26 PASS / 0 FAIL（回归）
node tests/todo-mobile-topmenu.spec.mjs   # 27 PASS / 0 FAIL（回归）
```

- `spec-output.txt` — 逐条判据读数（A 开合零位移 / B 多行逐字 / C 门禁名字 / D 收起+命中盒）
- `spec-readings.json` — 汇总（含 WS 客户端帧录制原文）
- `box-open.png` — 互动框浮出态（起点几何 = 行盒：left/top/width 逐值同 ±2px）
- `reply-multiline.png` — 多行输入态（第一行⏎第二行，回车未发送）
- `after-send.png` — 发送后（框收、双输入清空）

## 关键读数（详见 spec-output.txt）

| 判据 | 读数 |
|---|---|
| A3 零位移不变量 | 首条行 rect / 列表滚区 / 文档滚轴 / dock 逐值不变（开前→开时） |
| A4 动效单源 | 280ms × 4 腿（height/border-radius/box-shadow/transform）+ `cubic-bezier(0.4, 0, 0.2, 1)`，= liquidMorph.morphTransition 原文 |
| B2 回车 | 值 `第一行\n第二行`、框不收、用户帧 0 条、主输入框零污染 |
| B3 发送 | WS 用户帧 content = `#3d打印模型上色 第一行\n第二行` **逐字**（换行原样） |
| C 门禁名字 | 投递腿：数字头名照发逐字；展示腿：正常名 chip ×1、数字头名 chip ×0（matchTodoAt 形状门禁——任务号不认作待办引用，同源生效） |
| D4 命中盒 | `.tfl-hit-send` min 48×48（--ds-hit-tap-min 单源）实测 48×48 |

## 本批落码

- `js/todoFace.js` — 互动框全链（open/close/toggle/build/autosize/grow/sendReply/reanchor）+ 缺失 locale 键消费点修复
- `css/mobileLayer.css` ⑥ 块 — `.tfl-hit-*` 全套样式（此前骨架**零 CSS**：材质 nf-glass 单源、
  起点=行盒圆角无影 → 终点=浮层卡、发送钮 48 命中盒、回复面 `.tfl-edit-notes` 同族裸件）
- `js/locales/zh-CN.js` / `en.js` — 补 `todoface.edit / replyPh / send` 三键（骨架消费了
  不存在的键，UI 曾会显示裸键名）
- `js/motion/liquidMorph.js` — **零改动**（复用契约：时长/曲线/属性白名单全部单源消费）
