# 任务列表行重启对账 · 前后对照证据（2026-10-22）

作者实测：引擎重启后任务列表残留死会话的尸体系——旧行显示死会话的旧简报文本+打勾态，
接续的新会话另开一行，观感=「卡住/错乱」（原始截图见 uploads
`588942207641166_截屏2026-10-22 10.48.10.jpg`，即 #712 行：标题为断点续作简报、
其前会话 #708 被重启打断）。

样本 = 今日真实重启孤儿（delegates/ 与 subagent-tasks/ 盘面原文）：

| 台账号 | 会话 | 真实形态 | 改前面板观感 | 改后面板观感 |
|---|---|---|---|---|
| #708 | delegate-nebflow-fc5da94a | 重启打断（failed，指纹 lastError），被 #712 接续 | 被 ACTIVE 闸隐行；接续关系不可见 | 折进 #712 区块灰腿：「重启打断」徽标 + 「已由 #712 接续」 |
| #712 | delegate-nebflow-84a285e5 | 接续者（running） | 独占一行，标题即死会话断点简报 | 同左（本就是活态真源），区块下多折叠灰腿 |
| #706 | delegate-nebflow-1cc53dfe | 重启打断、未被接续 | 隐行 | 独立灰行：「重启打断」+「已中断」（灰点，永不勾态） |
| #703 | delegate-nebflow-24232ae5 | 退避态（restarting）孤儿，旧 boot sweep 只认 running → 永久冻结 | 「进行中 4h」僵尸行 | boot 对账收宽后翻「已中断」灰行 |

## 文件

- `before-panel-dark.png` — 改前：#703 死会话冻结活态（进行中 4h）+ #712 断点简报行；
  两打断行被隐行，接续关系零可见面。（旧管线输出夹具直渲）
- `after-panel-dark.png` — 改后（折叠态）：#708 折进 #712 区块（灰腿一行，不双行并存）；
  #706/#703 独立灰行「已中断」。
- `after-panel-dark-expanded.png` — 改后展开：折叠灰腿与成员行同框；徽标/关系词/灰态全量。
- `after-panorama-dark.png` / `after-panorama-card-dark.png` — 全景审计面：打断卡
  st-stop 灰态 + 顶徽标 + 「已由 #712 接续」虚线 chip（卡不折走、关系随身）。

## 复跑

```bash
node tests/tasklist-reconcile-shots.mjs
```

两页同源记录、两代管线输出（BEFORE = 旧适配层推导夹具；AFTER = 重启对账管线夹具），
非线上引擎截图——引擎侧（boot 对账 sweep + 行投影 `continuedBy`）候重启窗生效。
