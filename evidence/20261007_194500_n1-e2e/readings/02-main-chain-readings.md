# N1 E2E 主链读数汇编（rig :31849, HEAD 10a95018c 阶段）
rig=隔离 home /tmp/n1-e2e-rig/home · 随机端口 31849 · 🔴 宿主 8080/8090/8091/8787 零触碰

## 步骤① root(Nebula) 用 Delegate 真实 spawn
- root turn1 (POST /api/sessions/ad10d0cb…/turn, headless 同步面): status=completed 88.7s
- toolCalls: Project(create:n1proj)→mounted; Delegate(【DELEGATE BRIEF】)→task #1, session=delegate-nebflow-a8379b0f
- DelegateRegistry/ChainStore delegate-spawn 行: branch=delegate/delegate-nebflow-a8379b0f, worktree=/tmp/n1-e2e-rig/proj/.nebflow/worktrees/delegate-nebflow-a8379b0f, project=n1proj
- brief 注入行(转录[0]): [workspace] …worktrees/delegate-nebflow-a8379b0f + [working branch] delegate/… NEVER commit to main …

## 步骤②③ ChainWrite 整链登记＋barrier 真执行
- delegate 转录[2] ChainWrite ack: 「Chain 'n1-main' registered with 3 node(s) [leg-a, leg-b, merge-line] — released: entry node(s) leg-a, leg-b starting」
- 节点会话真任务读数（results/*.md）:
  - leg-a: 「DATA-VALUE=hello-n1e2e-20261007」（真 Read /tmp/n1-e2e-rig/proj/data.txt）
  - leg-b: 「brand-line: n1-e2e-proj-marker-42」（真 Read README.md）
  - merge-line: 「DATA=hello-n1e2e-20261007 BRAND=n1-e2e-proj-marker-42」←双上游结果沿边真实投递+合并
- /api/chains/1 中段实测: leg-a=completed leg-b=completed merge-line=running（barrier 齐后启动）

## 步骤④ node_report finish 真终态
- 归档批 flow-map-archive/n1-main.json: leg-a=completed leg-b=completed merge-line=completed（三节点全终态）

## 步骤⑤ 完成通知投创建者 delegate（S19① 改向）
- 基工作区审计 /tmp/n1-e2e-rig/proj/.nebflow/flow-map-events.jsonl: {type:notify-redirected, target=creator-delegate, channel=immediate-input, scope=delegate-nebflow-a8379b0f, status=completed, outcome=offered}
- delegate 转录[27] 真实注入: 「[Node 'MergeLine' completed] 任务完成。……DATA=hello-n1e2e-20261007 BRAND=n1-e2e-proj-marker-42」
- 复活补投面: 未触发（creator 全程 live）——按 R3 语义 park 腿不适用，如实申报

## 步骤⑦ per-delegate flowmap 读面（REST）
- GET /api/chains 行投影: rows=[{id:delegate-nebflow-a8379b0f, taskId:1, live:{status:running}, cwd:<worktree>}]
- GET /api/chains/1 链面: {key:1, record:{entries:[delegate-spawn]}, chain:{scope:delegate-nebflow-a8379b0f, mounted:true, nodes:[…rowStatusOf 映射…]}}
- 前端 delegateChainProvider: setSource 换源单缝（main.js import），UI 帧待截图腿

## 专项⑴ id+name 双寻址（真实投）
- Mail(→node:merge-line) 命中: 「Message appended to node 'MergeLine' task (status=pending)…NodeMessage」
- Mail(→node:MergeLine) 命中: 同上（name 路由解析到同一节点）
- 落盘物证 tasks/merge-line.md: 两行「== 分发器补充（NodeMessage 2026-10-07 19:49:07）== id-route probe / ==…19:49:11 == name-route probe」
- CHAIN_ADDR_AMBIGUOUS: 未遇（作用域内 name 唯一域约束下无法自然复现；候选列表机制=resolveNodeRefIO id→name唯一→歧义列候选，A5 序，代码在位未触发现场）

## 专项⑵ 链齐才跑（双入 barrier 停等时序，delegate #4 链 n1-timing，REST 轮询）
- 20:05:25 slow-a=pending after-a=pending（登记未放行完成期）
- 20:05:41 slow-a=running after-a=pending ←上游未齐下游不跑（停等 3.5min 窗口逐帧）
- 20:09:05 slow-a=completed after-a=pending（结算瞬间）
- 20:09:20 after-a=running（≤15s 内 barrier 释放即跑）
- 20:09:50 after-a=completed

## 专项⑶ merge 态申报
- merge 落地语义=delegate 层 MergeQueue（NODE_MERGE seal 后节点级 merge 退役）→读数见②合并FIFO件
- merge=true 探针（NodeEdit）: delegate#1 四连发 {merge:"true"}(字符串) 全部未触发 seal→建位成功且 merge 意图静默丢失（n-1108af95 实证 merge=false 落库）＝断点#1（已修 ae2432039）
- delegate#4 布尔探针被 in=[leg-a] 跨作用域引用先挡（Referenced node 'leg-a' not found——leg-a 属 delegate#1 作用域，作用域隔离正确行为）