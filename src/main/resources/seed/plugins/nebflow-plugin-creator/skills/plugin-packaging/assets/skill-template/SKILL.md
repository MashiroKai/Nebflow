---
name: example-skill
description: 一句话说清本 skill 做什么（what）与何时用（when）——这是唯一进 catalog 的文本，缺 name 或 description 任一键装载器会静默跳过本 skill。
---

# example-skill

（正文写作要点——生成时替换为本 skill 的真实内容，本模板即骨架：）

1. 本文是按需查阅件（面板胶囊深链打开，不随分配整文注入——包根 Plugin.md 才是
   分配注入体）——正文保持精炼，深内容下沉 references/（节点按需读），可执行
   脚本放 scripts/，输出模板资源放 assets/。
2. 正文写步骤与判据，不写散文；每一步给出可判定的完成标准。
3. 需要引用本 skill 目录内资源时用 ${SKILL_DIR} 变量（装载时替换为目录绝对路径），
   例如：`python3 ${SKILL_DIR}/scripts/your_check.py`。

## Steps

1. 第一步——做什么 + 判据。
2. 第二步——做什么 + 判据。
3. 失败路径——异常时怎么办（上报/重试/降级纪律）。
