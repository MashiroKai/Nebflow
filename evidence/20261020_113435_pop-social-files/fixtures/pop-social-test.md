# Pop 社交腿文件类投递 · E2E 测试件

**用途**：验证 md → 单张长图 PNG 转换管线（pop-social-files batch, 2026-10-20）。

## 结论先行

- 转换腿：md/markdown/html/htm → 单张长图 PNG（微信/飞书可看）
- 降级：转不动 → 「文件名+本地路径+一句话摘要」文本卡
- 去重：同一文件多次 Pop 不重复推（同字节指纹）

## 表格与代码

| 腿 | 通道面 | 状态 |
|----|--------|------|
| 长图主腿 | image_path / image_key | 新增 |
| 文件腿（html 直发） | file_path | 退役 |

```scala
val out = longshot.shot(md, png) // 30s 预算
```

> 引用块：飞书聊天预览不了 md——社交腿交付 md 一律转单张长图 PNG 或直接文本推送。

- 列表项一
- 列表项二
