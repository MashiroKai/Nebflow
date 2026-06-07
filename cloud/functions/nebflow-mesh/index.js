/**
 * Nebflow Mesh — CloudBase 云函数（单入口路由）
 *
 * 调用方式：
 *   tcb.callFunction({ name: 'nebflow-mesh', data: { action: 'auth/wechat-login', ... } })
 *   或 HTTP 触发: POST https://{envId}.service.tcloudbase.com/nebflow-mesh
 *     Body: { action: 'device/register', ... }
 *     Headers: Authorization: Bearer <userId>
 *
 * 数据库集合（需在控制台创建）：
 *   - users: 用户信息
 *   - devices: 设备注册
 *   - relay_messages: 中继消息
 */

const cloudbase = require('@cloudbase/node-sdk')
const app = cloudbase.init()
const db = app.database()

// ===== 主入口 =====
exports.main = async (event, context) => {
  const action = event.action || event.path || ''
  const userId = extractUserId(event, context)

  try {
    const result = await route(action, event, userId, context)
    return { code: 200, data: result }
  } catch (err) {
    console.error(`[nebflow-mesh] ${action} error:`, err)
    return { code: err.status || 500, message: err.message }
  }
}

async function route(action, event, userId, context) {
  switch (action) {
    // Auth
    case 'auth/wechat-login': return authWechatLogin(event)

    // Device
    case 'device/register': requireAuth(userId); return deviceRegister(event, userId)
    case 'device/list':     requireAuth(userId); return deviceList(userId)

    // Sync
    case 'sync/status':   requireAuth(userId); return syncStatus(event, userId)
    case 'sync/upload':   requireAuth(userId); return syncUpload(event, userId)
    case 'sync/download': requireAuth(userId); return syncDownload(event, userId)

    // Relay
    case 'relay/send': requireAuth(userId); return relaySend(event, userId)
    case 'relay/poll': requireAuth(userId); return relayPoll(event, userId)

    default: throw { status: 400, message: `Unknown action: ${action}` }
  }
}

// ===== Auth =====

async function authWechatLogin(event) {
  const { code } = event
  if (!code) throw { status: 400, message: 'Missing code' }

  const wxResp = await fetch(
    `https://api.weixin.qq.com/sns/oauth2/access_token?appid=${process.env.WX_APPID}&secret=${process.env.WX_SECRET}&code=${code}&grant_type=authorization_code`
  )
  const wxData = await wxResp.json()
  if (wxData.errcode) throw { status: 400, message: `WeChat error: ${wxData.errmsg}` }

  const { openid, unionid, access_token } = wxData
  const uid = unionid || openid

  // Get user info
  let nickname = 'Nebflow User', avatar = ''
  try {
    const userResp = await fetch(`https://api.weixin.qq.com/sns/userinfo?access_token=${access_token}&openid=${openid}`)
    const userData = await userResp.json()
    nickname = userData.nickname || nickname
    avatar = userData.headimgurl || avatar
  } catch (e) { /* non-critical */ }

  // Upsert user
  const usersCol = db.collection('users')
  const { data: existing } = await usersCol.where({ uid }).get()

  let userId
  if (existing.length > 0) {
    userId = existing[0]._id
    await usersCol.doc(userId).update({ nickname, avatar, lastLoginAt: Date.now() })
  } else {
    const result = await usersCol.add({ uid, nickname, avatar, loginMethods: { wechat: { openid, unionid } }, createdAt: Date.now(), lastLoginAt: Date.now() })
    userId = result.id
  }

  // Generate CloudBase custom auth ticket
  const ticket = app.auth().createTicket(userId, { refresh: 3600 * 24 * 30 })

  return { userId, nickname, avatar, ticket, expiresIn: 3600 * 24 * 7 }
}

// ===== Device =====

async function deviceRegister(event, userId) {
  const { deviceName, platform, localIP, deviceId: existingDeviceId } = event
  const deviceId = existingDeviceId || generateId()

  const col = db.collection('devices')
  const { data: existing } = await col.where({ userId, deviceId }).get()

  const update = { deviceName, platform, localIP: localIP || '', publicIP: event.publicIP || '', online: true, lastSeen: Date.now() }
  if (existing.length > 0) {
    await col.doc(existing[0]._id).update(update)
  } else {
    await col.add({ userId, deviceId, ...update, registeredAt: Date.now() })
  }

  const { data: peers } = await col.where({ userId, deviceId: db.RegExp({ neq: deviceId }) }).get()
  return { deviceId, peers: peers.map(d => ({ deviceId: d.deviceId, deviceName: d.deviceName, platform: d.platform, online: d.online, lastSeen: d.lastSeen })) }
}

async function deviceList(userId) {
  const { data: devices } = await db.collection('devices').where({ userId }).get()
  return devices.map(d => ({ deviceId: d.deviceId, deviceName: d.deviceName, platform: d.platform, online: d.online, lastSeen: d.lastSeen, localIP: d.localIP, publicIP: d.publicIP }))
}

// ===== Sync =====

async function syncStatus(event, userId) {
  // Return remote fingerprints from cloud storage index
  try {
    const result = await app.downloadFile({ fileID: `nebflow-sync/${userId}/_sync_index.json` })
    if (result && result.fileContent) return JSON.parse(result.fileContent.toString('utf-8'))
  } catch (e) { /* index doesn't exist yet */ }
  return {}
}

async function syncUpload(event, userId) {
  const { path: filePath, content } = event
  if (!filePath || content === undefined) throw { status: 400, message: 'Missing path or content' }

  const buffer = Buffer.from(content, 'base64')
  await app.uploadFile({ cloudPath: `nebflow-sync/${userId}/${filePath}`, fileContent: buffer })
  await updateSyncIndex(userId, filePath, buffer)
  return { ok: true }
}

async function syncDownload(event, userId) {
  const { path: filePath } = event
  if (!filePath) throw { status: 400, message: 'Missing path' }

  try {
    const result = await app.downloadFile({ fileID: `nebflow-sync/${userId}/${filePath}` })
    return { content: result.fileContent.toString('utf-8'), path: filePath }
  } catch (e) {
    throw { status: 404, message: 'File not found' }
  }
}

// ===== Relay =====

async function relaySend(event, userId) {
  const { fromDeviceId, toDeviceId, payload } = event
  if (!toDeviceId || !payload) throw { status: 400, message: 'Missing toDeviceId or payload' }

  await db.collection('relay_messages').add({ userId, id: event.id || generateId(), fromDeviceId: fromDeviceId || '', toDeviceId, payload, createdAt: event.createdAt || Date.now(), delivered: false })
  return { ok: true }
}

async function relayPoll(event, userId) {
  const { deviceId } = event
  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const col = db.collection('relay_messages')
  const { data: messages } = await col.where({ userId, toDeviceId: deviceId, delivered: false }).orderBy('createdAt', 'asc').limit(50).get()

  if (messages.length > 0) {
    for (const msg of messages) {
      await col.doc(msg._id).update({ delivered: true })
    }
  }

  return messages.map(m => ({ id: m.id, fromDeviceId: m.fromDeviceId, toDeviceId: m.toDeviceId, payload: m.payload, createdAt: m.createdAt }))
}

// ===== Helpers =====

function extractUserId(event, context) {
  // From CloudBase custom auth
  if (context && context.openId) return context.openId
  if (context && context.customUserId) return context.customUserId
  // From HTTP header (fallback)
  if (event && event.headers) {
    const auth = event.headers['authorization'] || event.headers['Authorization'] || ''
    if (auth.startsWith('Bearer ')) return auth.slice(7)
  }
  return null
}

function requireAuth(userId) {
  if (!userId) throw { status: 401, message: 'Unauthorized' }
}

function generateId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}

async function updateSyncIndex(userId, filePath, buffer) {
  const crypto = require('crypto')
  const hash = crypto.createHash('sha256').update(buffer).digest('hex').slice(0, 12)

  let index = {}
  try {
    const result = await app.downloadFile({ fileID: `nebflow-sync/${userId}/_sync_index.json` })
    if (result && result.fileContent) index = JSON.parse(result.fileContent.toString('utf-8'))
  } catch (e) { /* doesn't exist */ }

  index[filePath] = { hash, mtime: Date.now(), size: buffer.length }
  await app.uploadFile({ cloudPath: `nebflow-sync/${userId}/_sync_index.json`, fileContent: Buffer.from(JSON.stringify(index, null, 2)) })
}
