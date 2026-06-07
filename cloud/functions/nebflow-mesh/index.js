/**
 * Nebflow Mesh — CloudBase 云函数（单入口路由）
 *
 * 认证方式：邀请码配对（Pairing Code）
 *   - 第一台设备调用 auth/create-group 生成 6 位邀请码（10 分钟有效）
 *   - 其他设备调用 auth/join-group 输入邀请码加入同一用户组
 *   - groupId 作为所有设备共享的 userId
 *
 * 调用方式：
 *   HTTP 触发: POST https://{envId}.service.tcloudbase.com/nebflow-mesh
 *     Body: { action: 'auth/create-group', ... }
 *     Headers: Authorization: Bearer <groupId>
 *
 * 数据库集合（需在控制台创建）：
 *   - groups: 用户组（groupId, pairingCode, expiresAt）
 *   - devices: 设备注册
 *   - relay_messages: 中继消息
 */

const cloudbase = require('@cloudbase/node-sdk')
const app = cloudbase.init()
const db = app.database()

// ===== 主入口 =====
exports.main = async (event, context) => {
  const action = event.action || event.path || ''
  const groupId = extractGroupId(event, context)

  try {
    const result = await route(action, event, groupId, context)
    return { code: 200, data: result }
  } catch (err) {
    console.error(`[nebflow-mesh] ${action} error:`, err)
    return { code: err.status || 500, message: err.message }
  }
}

async function route(action, event, groupId, context) {
  switch (action) {
    // Auth (no group required)
    case 'auth/create-group': return authCreateGroup(event)
    case 'auth/join-group':   return authJoinGroup(event)

    // Device (require groupId)
    case 'device/register': requireAuth(groupId); return deviceRegister(event, groupId)
    case 'device/list':     requireAuth(groupId); return deviceList(groupId)

    // Sync (require groupId)
    case 'sync/status':   requireAuth(groupId); return syncStatus(event, groupId)
    case 'sync/upload':   requireAuth(groupId); return syncUpload(event, groupId)
    case 'sync/download': requireAuth(groupId); return syncDownload(event, groupId)

    // Relay (require groupId)
    case 'relay/send': requireAuth(groupId); return relaySend(event, groupId)
    case 'relay/poll': requireAuth(groupId); return relayPoll(event, groupId)

    default: throw { status: 400, message: `Unknown action: ${action}` }
  }
}

// ===== Auth — Pairing Code =====

async function authCreateGroup(event) {
  const { deviceId, deviceName, platform, nebflowUrl } = event
  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const groupId = generateId()
  const pairingCode = generatePairingCode()
  const now = Date.now()
  const expiresAt = now + 10 * 60 * 1000 // 10 minutes

  // Create group
  await db.collection('groups').add({
    groupId,
    pairingCode,
    createdAt: now,
    expiresAt,
    createdBy: deviceId
  })

  // Auto-register the creating device
  await db.collection('devices').add({
    groupId,
    deviceId,
    deviceName: deviceName || 'Unknown',
    platform: platform || 'unknown',
    nebflowUrl: nebflowUrl || '',
    localIP: event.localIP || '',
    publicIP: event.publicIP || '',
    online: true,
    registeredAt: now,
    lastSeen: now
  })

  return { groupId, pairingCode, expiresAt }
}

async function authJoinGroup(event) {
  const { pairingCode, deviceId, deviceName, platform, nebflowUrl } = event
  if (!pairingCode) throw { status: 400, message: 'Missing pairingCode' }
  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  // Find group by pairing code
  const { data: groups } = await db.collection('groups')
    .where({ pairingCode })
    .get()

  if (groups.length === 0) throw { status: 404, message: 'Invalid pairing code' }

  const group = groups[0]
  if (Date.now() > group.expiresAt) {
    throw { status: 410, message: 'Pairing code expired' }
  }

  const groupId = group.groupId
  const now = Date.now()

  // Register the joining device
  const col = db.collection('devices')
  const { data: existing } = await col.where({ groupId, deviceId }).get()

  if (existing.length > 0) {
    await col.doc(existing[0]._id).update({
      deviceName: deviceName || existing[0].deviceName,
      platform: platform || existing[0].platform,
      nebflowUrl: nebflowUrl || existing[0].nebflowUrl || '',
      online: true,
      lastSeen: now
    })
  } else {
    await col.add({
      groupId,
      deviceId,
      deviceName: deviceName || 'Unknown',
      platform: platform || 'unknown',
      nebflowUrl: nebflowUrl || '',
      localIP: event.localIP || '',
      publicIP: event.publicIP || '',
      online: true,
      registeredAt: now,
      lastSeen: now
    })
  }

  // Invalidate pairing code after successful join
  await db.collection('groups').doc(group._id).update({
    expiresAt: now // expire immediately
  })

  // Return group info + peer list
  const { data: peers } = await col.where({ groupId, deviceId: db.RegExp({ neq: deviceId }) }).get()

  return {
    groupId,
    peers: peers.map(d => ({
      deviceId: d.deviceId,
      deviceName: d.deviceName,
      platform: d.platform,
      online: d.online,
      lastSeen: d.lastSeen,
      address: d.nebflowUrl || ''
    }))
  }
}

// ===== Device =====

async function deviceRegister(event, groupId) {
  const { deviceName, platform, localIP, deviceId: existingDeviceId } = event
  const deviceId = existingDeviceId || generateId()

  const col = db.collection('devices')
  const { data: existing } = await col.where({ groupId, deviceId }).get()

  const now = Date.now()
  const update = {
    deviceName, platform,
    localIP: localIP || '',
    publicIP: event.publicIP || '',
    online: true,
    lastSeen: now
  }

  if (existing.length > 0) {
    await col.doc(existing[0]._id).update(update)
  } else {
    await col.add({ groupId, deviceId, ...update, registeredAt: now })
  }

  const { data: peers } = await col.where({ groupId, deviceId: db.RegExp({ neq: deviceId }) }).get()
  return {
    deviceId,
    peers: peers.map(d => ({
      deviceId: d.deviceId,
      deviceName: d.deviceName,
      platform: d.platform,
      online: d.online,
      lastSeen: d.lastSeen
    }))
  }
}

async function deviceList(groupId) {
  const { data: devices } = await db.collection('devices').where({ groupId }).get()
  return devices.map(d => ({
    deviceId: d.deviceId,
    deviceName: d.deviceName,
    platform: d.platform,
    online: d.online,
    lastSeen: d.lastSeen,
    localIP: d.localIP,
    publicIP: d.publicIP,
    address: d.nebflowUrl || ''
  }))
}

// ===== Sync =====

async function syncStatus(event, groupId) {
  try {
    const result = await app.downloadFile({ fileID: `nebflow-sync/${groupId}/_sync_index.json` })
    if (result && result.fileContent) return JSON.parse(result.fileContent.toString('utf-8'))
  } catch (e) { /* index doesn't exist yet */ }
  return {}
}

async function syncUpload(event, groupId) {
  const { path: filePath, content } = event
  if (!filePath || content === undefined) throw { status: 400, message: 'Missing path or content' }

  const buffer = Buffer.from(content, 'base64')
  await app.uploadFile({ cloudPath: `nebflow-sync/${groupId}/${filePath}`, fileContent: buffer })
  await updateSyncIndex(groupId, filePath, buffer)
  return { ok: true }
}

async function syncDownload(event, groupId) {
  const { path: filePath } = event
  if (!filePath) throw { status: 400, message: 'Missing path' }

  try {
    const result = await app.downloadFile({ fileID: `nebflow-sync/${groupId}/${filePath}` })
    return { content: result.fileContent.toString('utf-8'), path: filePath }
  } catch (e) {
    throw { status: 404, message: 'File not found' }
  }
}

// ===== Relay =====

async function relaySend(event, groupId) {
  const { fromDeviceId, toDeviceId, payload } = event
  if (!toDeviceId || !payload) throw { status: 400, message: 'Missing toDeviceId or payload' }

  await db.collection('relay_messages').add({
    groupId,
    id: event.id || generateId(),
    fromDeviceId: fromDeviceId || '',
    toDeviceId,
    payload,
    createdAt: event.createdAt || Date.now(),
    delivered: false
  })
  return { ok: true }
}

async function relayPoll(event, groupId) {
  const { deviceId } = event
  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const col = db.collection('relay_messages')
  const { data: messages } = await col
    .where({ groupId, toDeviceId: deviceId, delivered: false })
    .orderBy('createdAt', 'asc')
    .limit(50)
    .get()

  if (messages.length > 0) {
    for (const msg of messages) {
      await col.doc(msg._id).update({ delivered: true })
    }
  }

  return messages.map(m => ({
    id: m.id,
    fromDeviceId: m.fromDeviceId,
    toDeviceId: m.toDeviceId,
    payload: m.payload,
    createdAt: m.createdAt
  }))
}

// ===== Helpers =====

function extractGroupId(event, context) {
  // From CloudBase custom auth
  if (context && context.openId) return context.openId
  if (context && context.customUserId) return context.customUserId
  // From HTTP header
  if (event && event.headers) {
    const auth = event.headers['authorization'] || event.headers['Authorization'] || ''
    if (auth.startsWith('Bearer ')) return auth.slice(7)
  }
  return null
}

function requireAuth(groupId) {
  if (!groupId) throw { status: 401, message: 'Unauthorized — no groupId' }
}

function generateId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}

function generatePairingCode() {
  // 6-digit numeric code
  return String(Math.floor(100000 + Math.random() * 900000))
}

async function updateSyncIndex(groupId, filePath, buffer) {
  const crypto = require('crypto')
  const hash = crypto.createHash('sha256').update(buffer).digest('hex').slice(0, 12)

  let index = {}
  try {
    const result = await app.downloadFile({ fileID: `nebflow-sync/${groupId}/_sync_index.json` })
    if (result && result.fileContent) index = JSON.parse(result.fileContent.toString('utf-8'))
  } catch (e) { /* doesn't exist */ }

  index[filePath] = { hash, mtime: Date.now(), size: buffer.length }
  await app.uploadFile({
    cloudPath: `nebflow-sync/${groupId}/_sync_index.json`,
    fileContent: Buffer.from(JSON.stringify(index, null, 2))
  })
}
