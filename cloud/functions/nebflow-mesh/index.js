/**
 * Nebflow Mesh — CloudBase 云函数入口
 *
 * 部署方式：
 *   1. npm install（安装 @cloudbase/node-sdk）
 *   2. 使用 CloudBase CLI 部署：tcb functions:deploy nebflow-mesh
 *   3. 或者在 CloudBase 控制台手动创建函数，把本目录内容粘贴进去
 *
 * 环境变量（在 CloudBase 控制台设置）：
 *   - WX_APPID: 微信开放平台 AppID
 *   - WX_SECRET: 微信开放平台 AppSecret
 *
 * 数据库集合（需要在 CloudBase 控制台创建）：
 *   - users: 用户信息
 *   - devices: 设备注册信息
 *   - relay_messages: 中继消息
 *
 * 云存储目录：
 *   - nebflow-sync/{userId}/: 同步文件
 */

const cloudbase = require('@cloudbase/node-sdk')
const app = cloudbase.init()
const db = app.database()

// ===== 认证 =====

/**
 * 微信扫码登录
 * POST /auth/wechat-login
 * Body: { code: string }
 */
exports.authWechatLogin = async (event) => {
  const { code } = event
  if (!code) return { code: 400, message: 'Missing code' }

  try {
    // 用 code 换取 access_token + openid
    const wxResp = await fetch(
      `https://api.weixin.qq.com/sns/oauth2/access_token?appid=${process.env.WX_APPID}&secret=${process.env.WX_SECRET}&code=${code}&grant_type=authorization_code`
    )
    const wxData = await wxResp.json()

    if (wxData.errcode) {
      return { code: 400, message: `WeChat error: ${wxData.errmsg}` }
    }

    const { openid, unionid, access_token } = wxData

    // 获取用户信息
    const userResp = await fetch(
      `https://api.weixin.qq.com/sns/userinfo?access_token=${access_token}&openid=${openid}`
    )
    const userData = await userResp.json()

    // 查找或创建用户（用 unionid 去重）
    const uid = unionid || openid
    const usersCol = db.collection('users')
    const { data: existingUsers } = await usersCol.where({ uid }).get()

    let userId
    if (existingUsers.length > 0) {
      userId = existingUsers[0]._id
    } else {
      const result = await usersCol.add({
        uid,
        nickname: userData.nickname || 'Nebflow User',
        avatar: userData.headimgurl || '',
        loginMethods: { wechat: { openid, unionid } },
        createdAt: Date.now()
      })
      userId = result.id
    }

    // 签发自定义 JWT（CloudBase 自定义登录凭证）
    // 使用 CloudBase 的 auth().createTicket() 生成前端可用的登录凭证
    const ticket = app.auth().createTicket(userId, {
      refresh: 3600 * 24 * 30 // 30 天刷新
    })

    return {
      code: 200,
      data: {
        userId,
        nickname: userData.nickname || 'Nebflow User',
        avatar: userData.headimgurl || '',
        ticket, // 前端用此 ticket 登录 CloudBase
        expiresIn: 3600 * 24 * 7 // JWT 有效期 7 天
      }
    }
  } catch (err) {
    console.error('WeChat login error:', err)
    return { code: 500, message: err.message }
  }
}

// ===== 设备管理 =====

/**
 * 注册/更新设备，返回同用户的其他设备列表
 * POST /device/register
 * Body: { deviceName, platform, localIP? }
 * Headers: Authorization: Bearer <jwt>
 */
exports.deviceRegister = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  const { deviceName, platform, localIP } = event
  const deviceId = event.deviceId || generateId()

  const devicesCol = db.collection('devices')

  // Upsert 设备
  const { data: existing } = await devicesCol.where({ userId, deviceId }).get()
  if (existing.length > 0) {
    await devicesCol.doc(existing[0]._id).update({
      deviceName,
      platform,
      localIP: localIP || '',
      publicIP: event.publicIP || '',
      online: true,
      lastSeen: Date.now()
    })
  } else {
    await devicesCol.add({
      userId,
      deviceId,
      deviceName,
      platform,
      localIP: localIP || '',
      publicIP: event.publicIP || '',
      online: true,
      lastSeen: Date.now(),
      registeredAt: Date.now()
    })
  }

  // 返回同用户的其他设备
  const { data: allDevices } = await devicesCol
    .where({ userId, deviceId: db.RegExp({ neq: deviceId }) })
    .get()

  return {
    code: 200,
    data: {
      deviceId,
      peers: allDevices.map(d => ({
        deviceId: d.deviceId,
        deviceName: d.deviceName,
        platform: d.platform,
        online: d.online,
        lastSeen: d.lastSeen
      }))
    }
  }
}

/**
 * 列出同用户的所有设备
 * GET /device/list
 */
exports.deviceList = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  const { data: devices } = await db.collection('devices').where({ userId }).get()

  return {
    code: 200,
    data: devices.map(d => ({
      deviceId: d.deviceId,
      deviceName: d.deviceName,
      platform: d.platform,
      online: d.online,
      lastSeen: d.lastSeen,
      localIP: d.localIP,
      publicIP: d.publicIP
    }))
  }
}

// ===== 数据同步 =====

/**
 * 对比文件指纹
 * POST /sync/status
 * Body: { fingerprints: { path: { hash, mtime, size } } }
 */
exports.syncStatus = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  // 从云存储读取远端指纹索引
  const indexFile = `nebflow-sync/${userId}/_sync_index.json`
  let remoteFingerprints = {}
  try {
    const result = await app.downloadFile({ fileID: indexFile })
    if (result.fileContent) {
      remoteFingerprints = JSON.parse(result.fileContent.toString('utf-8'))
    }
  } catch (e) {
    // 索引文件不存在，远端为空
  }

  return {
    code: 200,
    data: remoteFingerprints
  }
}

/**
 * 上传文件到云存储
 * POST /sync/upload
 * Body: { path: string, content: string (base64) }
 */
exports.syncUpload = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  const { path: filePath, content } = event
  if (!filePath || !content) return { code: 400, message: 'Missing path or content' }

  // 写入云存储
  const storagePath = `nebflow-sync/${userId}/${filePath}`
  const buffer = Buffer.from(content, 'base64')
  await app.uploadFile({
    cloudPath: storagePath,
    fileContent: buffer
  })

  // 更新同步索引（简单 LWW：直接覆盖）
  await updateSyncIndex(userId, filePath, buffer)

  return { code: 200, data: { ok: true } }
}

/**
 * 从云存储下载文件
 * GET /sync/download?path=...
 */
exports.syncDownload = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  const { path: filePath } = event
  if (!filePath) return { code: 400, message: 'Missing path' }

  const storagePath = `nebflow-sync/${userId}/${filePath}`
  try {
    const result = await app.downloadFile({ fileID: storagePath })
    const content = result.fileContent.toString('utf-8')
    return { code: 200, data: { content, path: filePath } }
  } catch (e) {
    return { code: 404, message: 'File not found' }
  }
}

// ===== 消息中继 =====

/**
 * 发送中继消息
 * POST /relay/send
 * Body: { id, fromDeviceId, toDeviceId, payload, createdAt }
 */
exports.relaySend = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  const { id, fromDeviceId, toDeviceId, payload, createdAt } = event
  if (!toDeviceId || !payload) return { code: 400, message: 'Missing toDeviceId or payload' }

  await db.collection('relay_messages').add({
    userId,
    id: id || generateId(),
    fromDeviceId,
    toDeviceId,
    payload,
    createdAt: createdAt || Date.now(),
    delivered: false
  })

  return { code: 200, data: { ok: true } }
}

/**
 * 轮询中继消息
 * GET /relay/poll?deviceId=...
 * 返回所有发给该设备的未读消息，并标记为已读
 */
exports.relayPoll = async (event, context) => {
  const userId = extractUserId(context)
  if (!userId) return { code: 401, message: 'Unauthorized' }

  const { deviceId } = event
  if (!deviceId) return { code: 400, message: 'Missing deviceId' }

  const col = db.collection('relay_messages')
  const { data: messages } = await col
    .where({ userId, toDeviceId: deviceId, delivered: false })
    .orderBy('createdAt', 'asc')
    .limit(50)
    .get()

  // 标记为已投递
  if (messages.length > 0) {
    const ids = messages.map(m => m._id)
    // 批量更新
    for (const msgId of ids) {
      await col.doc(msgId).update({ delivered: true })
    }
  }

  return {
    code: 200,
    data: messages.map(m => ({
      id: m.id,
      fromDeviceId: m.fromDeviceId,
      toDeviceId: m.toDeviceId,
      payload: m.payload,
      createdAt: m.createdAt
    }))
  }
}

// ===== 辅助函数 =====

function extractUserId(context) {
  // CloudBase 自定义登录后，用户信息在 context 中
  if (context && context.openId) return context.openId
  // 从自定义 token 中提取
  if (context && context.customUserId) return context.customUserId
  return null
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

  const indexPath = `nebflow-sync/${userId}/_sync_index.json`
  let index = {}
  try {
    const result = await app.downloadFile({ fileID: indexPath })
    if (result.fileContent) {
      index = JSON.parse(result.fileContent.toString('utf-8'))
    }
  } catch (e) { /* index doesn't exist yet */ }

  index[filePath] = {
    hash,
    mtime: Date.now(),
    size: buffer.length
  }

  await app.uploadFile({
    cloudPath: indexPath,
    fileContent: Buffer.from(JSON.stringify(index, null, 2))
  })
}
