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

    case "os": {
      const data = await queryFieldDistinct(collection, "app_start", "os", since);
      return { code: 200, data };
    }

    case "pageviews": {
      const data = await queryPageViews(collection, since);
      return { code: 200, data };
    }

    case "response_time": {
      const data = await queryResponseTime(collection, since);
      return { code: 200, data };
    }

    case "usage_pattern": {
      const data = await queryUsagePattern(collection, since);
      return { code: 200, data };
    }

    case "retention": {
      const data = await queryRetention(collection, since);
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

/** 查询网站 page_view 事件，返回每日 PV/UV + 页面排行 */
async function queryPageViews(collection, since) {
  const result = await collection
    .where({
      event: "page_view",
      timestamp: db.command.gte(since),
    })
    .field({ client_id: true, properties: true, timestamp: true })
    .limit(10000)
    .get();

  // 按天分组统计 PV/UV
  const byDay = {};
  // 按路径分组统计
  const byPath = {};
  for (const row of result.data) {
    const day = new Date(row.timestamp).toISOString().slice(0, 10);
    const path = (row.properties && row.properties.path) || "/";

    if (!byDay[day]) byDay[day] = { pv: 0, clients: new Set() };
    byDay[day].pv++;
    byDay[day].clients.add(row.client_id);

    if (!byPath[path]) byPath[path] = { pv: 0, clients: new Set() };
    byPath[path].pv++;
    byPath[path].clients.add(row.client_id);
  }

  const daily = Object.entries(byDay)
    .map(([date, d]) => ({ date, pv: d.pv, uv: d.clients.size }))
    .sort((a, b) => a.date.localeCompare(b.date));

  const pages = Object.entries(byPath)
    .map(([path, d]) => ({ path, pv: d.pv, uv: d.clients.size }))
    .sort((a, b) => b.pv - a.pv)
    .slice(0, 20);

  return { daily, pages };
}

/** 查询平均回复时间 — 按天聚合 */
async function queryResponseTime(collection, since) {
  const result = await querySessionEndRaw(collection, since, {
    properties: true,
    timestamp: true,
  });
  // 按天分组，收集 avg_response_time_ms
  const byDay = {};
  for (const row of result) {
    const p = row.properties || {};
    const avgMs = p.avg_response_time_ms;
    if (!avgMs) continue;
    const day = new Date(row.timestamp).toISOString().slice(0, 10);
    if (!byDay[day]) byDay[day] = { sum: 0, count: 0, max: 0 };
    byDay[day].sum += avgMs;
    byDay[day].count++;
    byDay[day].max = Math.max(byDay[day].max, avgMs);
  }
  const daily = Object.entries(byDay)
    .map(([date, d]) => ({
      date,
      avg_ms: Math.round(d.sum / d.count),
      max_ms: d.max,
      sessions: d.count,
    }))
    .sort((a, b) => a.date.localeCompare(b.date));

  // 总体统计
  const all = daily.reduce(
    (acc, d) => {
      acc.totalMs += d.avg_ms * d.sessions;
      acc.totalSessions += d.sessions;
      acc.maxMs = Math.max(acc.maxMs, d.max_ms);
      return acc;
    },
    { totalMs: 0, totalSessions: 0, maxMs: 0 }
  );
  const overall = {
    avg_ms: all.totalSessions > 0 ? Math.round(all.totalMs / all.totalSessions) : 0,
    max_ms: all.maxMs,
    total_sessions: all.totalSessions,
  };

  return { daily, overall };
}

/** 查询使用时段分布 — 按小时聚合所有 session 活动 */
async function queryUsagePattern(collection, since) {
  // 同时查 app_start 和 session_end 以覆盖更多活动
  const [starts, ends] = await Promise.all([
    collection
      .where({ event: "app_start", timestamp: db.command.gte(since) })
      .field({ client_id: true, timestamp: true })
      .limit(10000)
      .get(),
    collection
      .where({ event: "session_end", timestamp: db.command.gte(since) })
      .field({ client_id: true, properties: true, timestamp: true })
      .limit(10000)
      .get(),
  ]);

  const hourly = new Array(24).fill(0);
  const dailyDuration = {}; // client_id -> { date -> total_sec }
  const clientDays = {}; // client_id -> Set of dates

  for (const row of starts.data) {
    const h = new Date(row.timestamp).getHours();
    hourly[h]++;
    const day = new Date(row.timestamp).toISOString().slice(0, 10);
    if (!clientDays[row.client_id]) clientDays[row.client_id] = new Set();
    clientDays[row.client_id].add(day);
  }

  for (const row of ends.data) {
    const h = new Date(row.timestamp).getHours();
    hourly[h]++;
    const p = row.properties || {};
    const dur = p.duration_sec || 0;
    const day = new Date(row.timestamp).toISOString().slice(0, 10);
    if (!dailyDuration[row.client_id]) dailyDuration[row.client_id] = {};
    dailyDuration[row.client_id][day] =
      (dailyDuration[row.client_id][day] || 0) + dur;
    if (!clientDays[row.client_id]) clientDays[row.client_id] = new Set();
    clientDays[row.client_id].add(day);
  }

  // 计算每个用户的日均使用时长
  const userDurations = Object.entries(dailyDuration).map(([, days]) => {
    const totalSec = Object.values(days).reduce((a, b) => a + b, 0);
    const numDays = Object.keys(days).length;
    return { avg_daily_sec: numDays > 0 ? totalSec / numDays : 0 };
  });
  const avgDailySec =
    userDurations.length > 0
      ? userDurations.reduce((s, u) => s + u.avg_daily_sec, 0) /
        userDurations.length
      : 0;

  return {
    hourly: hourly.map((count, hour) => ({ hour, count })),
    avg_daily_duration_sec: Math.round(avgDailySec),
    total_clients: Object.keys(clientDays).length,
  };
}

/** 留存率分析 — 第 N 天留存 */
async function queryRetention(collection, since) {
  // 查 app_start 事件获取每日活跃 client_id
  const result = await collection
    .where({ event: "app_start", timestamp: db.command.gte(since) })
    .field({ client_id: true, timestamp: true })
    .limit(10000)
    .get();

  // 找每个用户的首次活跃日
  const firstSeen = {};
  const clientByDay = {};
  for (const row of result.data) {
    const day = new Date(row.timestamp).toISOString().slice(0, 10);
    if (!firstSeen[row.client_id] || day < firstSeen[row.client_id]) {
      firstSeen[row.client_id] = day;
    }
    if (!clientByDay[day]) clientByDay[day] = new Set();
    clientByDay[day].add(row.client_id);
  }

  // 按首次活跃日分组（cohort）
  const cohorts = {};
  for (const [cid, firstDay] of Object.entries(firstSeen)) {
    if (!cohorts[firstDay]) cohorts[firstDay] = new Set();
    cohorts[firstDay].add(cid);
  }

  // 计算各 cohort 的留存
  const sortedDays = Object.keys(cohorts).sort();
  const retentionDays = [1, 3, 7, 14, 30];
  const cohortData = sortedDays.map((cohortDay) => {
    const cohortSize = cohorts[cohortDay].size;
    const retention = {};
    for (const d of retentionDays) {
      const targetDay = addDays(cohortDay, d);
      const activeOnDay = clientByDay[targetDay] || new Set();
      let retained = 0;
      for (const cid of cohorts[cohortDay]) {
        if (activeOnDay.has(cid)) retained++;
      }
      retention[`day${d}`] = cohortSize > 0 ? Math.round((retained / cohortSize) * 100) : 0;
    }
    return { date: cohortDay, users: cohortSize, ...retention };
  });

  return cohortData;
}

function addDays(dateStr, n) {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
}

/** 查询 session_end 原始数据（可指定 field） */
async function querySessionEndRaw(collection, since, fieldSpec) {
  const result = await collection
    .where({ event: "session_end", timestamp: db.command.gte(since) })
    .field(fieldSpec)
    .limit(10000)
    .get();
  return result.data;
}
