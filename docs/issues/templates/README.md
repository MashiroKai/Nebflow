# Issue 模板使用指南

## 快速开始

1. **选择模板类型**

   | 场景 | 模板 | 关闭标记 | 说明 |
   |------|------|----------|------|
   | 功能需求 | [`feature.md`](./feature.md) | `[结束]` | 新功能、enhancements |
   | Bug 修复 | [`bug.md`](./bug.md) | `[结束]` | 行为异常、错误修复 |
   | 性能退化 | [`perf.md`](./perf.md) | `[结束]` | 性能指标异常下降 |
   | 代码重构 | [`refactor.md`](./refactor.md) | `[结束]` | 结构优化、职责分离 |
   | 技术债务 | [`tech-debt.md`](./tech-debt.md) | `[结束]` | 已知妥协、待偿还债务 |
   | 测试建设 | [`test.md`](./test.md) | `[结束]` | 测试补充、测试基础设施 |
   | 极小修复 | [`quickfix.md`](./quickfix.md) | `[结束]` | 单行修复、笔误、配置调整 |

2. **复制模板**，按 `[创建]` 标记填写必填项
3. **开发过程中**更新状态和实际工时
4. **关闭前**填写 `[结束]` 标记项

## 术语规范

所有枚举值必须来自 [`_glossary.md`](./_glossary.md)，禁止自定义：

- **优先级**: P0-阻塞 / P1-高 / P2-中 / P3-低
- **严重程度**: Critical / High / Medium / Low
- **状态**: 按类型使用对应状态机（见 glossary）
- **关闭原因**: 按类型选择（见 glossary）

## 模板维护

### 修改公共区块

公共区块定义在 [`_base.md`](./_base.md) 中，使用 `<!-- BLOCK: name -->` 标记：

```markdown
<!-- BLOCK: meta -->
## [创建] 元信息
...
<!-- END_BLOCK -->
```

各模板通过 `<!-- INJECT: block_name -->` 引用：

```markdown
<!-- INJECT: meta -->
```

### 重新生成模板

修改 `_base.md` 或 `_glossary.md` 后，运行：

```bash
cd docs/issues/templates && python3 _build.py
```

脚本会自动：
1. 将 `_base.md` 中的公共区块注入所有模板
2. 校验各模板的状态值与 glossary 一致
3. 校验关闭原因与类型匹配
4. 校验生命周期标记完整（统一使用 `[创建]` / `[结束]`）
5. 校验优先级、严重程度、影响范围、风险等级等术语与 glossary 一致
6. 校验必填字段存在
7. 校验关键区块完整
8. 校验可选区块标注一致性
9. 校验无残留 INJECT 标记

额外校验实际 issue 文件：

```bash
cd docs/issues/templates && python3 _build.py --check-issues
```

这会额外检查 `docs/issues/*.md` 中是否残留 `@username`、`Foo.scala` 等模板占位符。

### 添加新模板类型

1. 创建 `newtype.md`，使用 `<!-- INJECT: -->` 引用公共区块
2. 在 `_build.py` 中更新 `TYPE_STATUS_MAP` 和 `TYPE_CLOSE_REASONS`
3. 在 `_glossary.md` 中添加状态机定义和关闭原因映射
4. 运行 `python3 _build.py`
5. 更新本 README 的模板选择表

## 文件结构

```
templates/
├── README.md          # 本文件
├── _build.py          # 模板构建与校验脚本
├── _base.md           # 公共区块定义（不直接使用）
├── _glossary.md       # 术语与状态机定义
├── bug.md             # Bug 模板
├── feature.md         # 功能模板
├── refactor.md        # 重构模板
├── perf.md            # 性能退化模板
├── tech-debt.md       # 技术债务模板
├── test.md            # 测试模板
└── quickfix.md        # 快速修复模板
```

## Quick Fix 升级规则

满足以下任一条件时，必须关闭 quickfix，改用完整模板：

- 涉及 >=3 个文件
- 需要架构/接口调整
- 涉及行为变更（非纯修复）
- 预估工时 > 2h
- 需要新增测试覆盖
- 需要多阶段部署
