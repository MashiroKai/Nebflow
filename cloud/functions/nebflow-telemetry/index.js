/**
 * Nebflow Telemetry — CloudBase 云函数
 *
 * 接收 Nebflow 客户端的匿名使用数据，存入 CloudBase 数据库。
 * Dashboard 从这里查询聚合指标。
 *
 * 认证：
 *   - POST /events: 无需认证（公开写入端点）
 *   - GET /metrics: 需要 admin password
 *
 * 调用方式：
 *   HTTP 触发: POST https://{envId}.service.tcloudbase.com/nebflow-telemetry
 *     Body: { action: 'events', client_id, app_version, os, events: [...] }
 *
 * 数据库集合（自动创建）：
 *   - telemetry_events: 遥测事件
 */

const cloudbase = require("@cloudbase/node-sdk");
const app = cloudbase.init();
const db = app.database();

const MAX_BATCH = 100;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "";
const COLLECTION = "telemetry_events";

// ===== 主入口 =====
exports.main = async (event, context) => {
  // CloudBase HTTP 触发时，body 可能嵌套在不同字段
  // SDK 调用时 event 直接是参数对象；HTTP 触发时 body 在 event.body (string)
  let payload = event;
  if (typeof event.body === "string") {
    try { payload = JSON.parse(event.body); } catch { payload = event; }
  }
  const action = payload.action || event.action || "";

  try {
    switch (action) {
      case "events":
        return await handleEvents(payload);
      case "metrics":
        return await handleMetrics(payload);
      default:
        return { code: 400, message: `Unknown action: ${action}` };
    }
  } catch (err) {
    console.error(`[telemetry] ${action} error:`, err);
    return { code: 500, message: err.message };
  }
};

// ===== POST /events — 接收遥测数据 =====
async function handleEvents(event) {
  const { client_id, app_version, os, events } = event;

  if (!client_id || !events || !Array.isArray(events)) {
    return { code: 400, message: "Missing required fields: client_id, events" };
  }
  if (events.length > MAX_BATCH) {
    return { code: 400, message: `Too many events (max ${MAX_BATCH})` };
  }

  const collection = db.collection(COLLECTION);
  const now = new Date();

  // CloudBase add 一次最多 20 条，分批写入
  const rows = events.map((ev) => ({
    client_id,
    app_version: app_version || "unknown",
    os: os || "unknown",
    event: ev.event || "",
    timestamp: ev.timestamp ? new Date(ev.timestamp) : now,
    properties: ev.properties || {},
    received_at: now,
  }));

  const BATCH_SIZE = 20;
  try {
    for (let i = 0; i < rows.length; i += BATCH_SIZE) {
      const batch = rows.slice(i, i + BATCH_SIZE);
      await collection.add(batch);
    }
  } catch (err) {
    // Auto-create collection on first write
    if (err.code === "DATABASE_COLLECTION_NOT_EXIST") {
      await db.createCollection(COLLECTION);
      for (let i = 0; i < rows.length; i += BATCH_SIZE) {
        const batch = rows.slice(i, i + BATCH_SIZE);
        await collection.add(batch);
      }
    } else {
      throw err;
    }
  }

  return { code: 200, data: { status: "ok", count: rows.length } };
}

// ===== GET /metrics — 查询聚合指标 =====
async function handleMetrics(event) {
  // 简单密码认证
  const password = event.password || event.headers?.["x-admin-password"] || "";
  if (ADMIN_PASSWORD && password !== ADMIN_PASSWORD) {
    return { code: 401, message: "Unauthorized" };
  }

  const metric = event.metric || "overview";
  const days = parseInt(event.days || "30");
  const since = new Date(Date.now() - days * 86400000);

  const collection = db.collection(COLLECTION);

  switch (metric) {
    case "overview": {
      const [dau, versions, os] = await Promise.all([
        queryGrouped(collection, "app_start", since),
        queryFieldDistinct(collection, "app_start", "app_version", since),
        queryFieldDistinct(collection, "app_start", "os", since),
      ]);
      return { code: 200, data: { dau, versions, os } };
    }

    case "dau": {
      const data = await queryGrouped(collection, "app_start", since);
      return { code: 200, data };
    }

    case "versions": {
      const data = await queryFieldDistinct(collection, "app_start", "app_version", since);
      return { code: 200, data };
    }

    case "tasks": {
      const data = await querySessionEnd(collection, since);
      return { code: 200, data };
    }

    case "tools": {
      const data = await querySessionEnd(collection, since);
      return { code: 200, data };
    }

    case "sessions": {
      const data = await querySessionEnd(collection, since);
      return { code: 200, data };
    }

    case "retention": {
      const data = await queryGrouped(collection, "app_start", since);
      return { code: 200, data };
    }

    case "os": {
      const data = await queryFieldDistinct(collection, "app_start", "os", since);
      return { code: 200, data };
    }

    default:
      return { code: 400, message: `Unknown metric: ${metric}` };
  }
}

// ===== 查询工具函数 =====

/** 按 event 类型查询，返回 { date, client_ids[] } 的按天聚合 */
async function queryGrouped(collection, eventType, since) {
  const result = await collection
    .where({
      event: eventType,
      timestamp: db.command.gte(since),
    })
    .field({ client_id: true, timestamp: true })
    .limit(10000)
    .get();

  // 按天分组
  const byDay = {};
  for (const row of result.data) {
    const day = new Date(row.timestamp).toISOString().slice(0, 10);
    if (!byDay[day]) byDay[day] = new Set();
    byDay[day].add(row.client_id);
  }
  return Object.entries(byDay)
    .map(([date, clients]) => ({ date, count: clients.size }))
    .sort((a, b) => a.date.localeCompare(b.date));
}

/** 查询某字段的分布（去重 client_id 计数） */
async function queryFieldDistinct(collection, eventType, field, since) {
  const result = await collection
    .where({
      event: eventType,
      timestamp: db.command.gte(since),
    })
    .field({ [field]: true, client_id: true })
    .limit(10000)
    .get();

  const grouped = {};
  for (const row of result.data) {
    const key = row[field] || "unknown";
    if (!grouped[key]) grouped[key] = new Set();
    grouped[key].add(row.client_id);
  }
  return Object.entries(grouped)
    .map(([name, clients]) => ({ name, count: clients.size }))
    .sort((a, b) => b.count - a.count);
}

/** 查询 session_end 事件（含 tool_profile、inferred_task 等） */
async function querySessionEnd(collection, since) {
  const result = await collection
    .where({
      event: "session_end",
      timestamp: db.command.gte(since),
    })
    .field({ properties: true, timestamp: true })
    .limit(10000)
    .get();

  return result.data;
}
