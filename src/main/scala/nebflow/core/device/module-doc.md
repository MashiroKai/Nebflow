# core / device

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

设备信息缓存（进程级）。

**入口建议**：先读 [`DeviceHmac.scala`](DeviceHmac.scala)（本模块最大文件）。

## 文件（3）

| 文件 | 说明 |
|---|---|
| [`DeviceHmac.scala`](DeviceHmac.scala) | 设备腿 HMAC 签名（v2 §A.4 / v2.1 §A.4 全文有效，阶段 1 落地）。 四元组头（wire 形状冻结）： |
| [`DeviceRegistry.scala`](DeviceRegistry.scala) | Device pairing registry + HMAC shared keys（v2.1 阶段 1 §A.4 双轨灰度的**登记面**）。 编排权威侧（host）的配对登记表：每个已配对设备一条 [[DeviceE |
| [`FleetConfig.scala`](FleetConfig.scala) | Fleet（多设备）引擎配置 —— `nebflow.json` 顶层 `fleet` 块的热读单点（台账 #913 ③，2026-10-26）。 |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)
