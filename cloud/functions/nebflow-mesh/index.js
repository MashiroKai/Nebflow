/**
 * Nebflow Mesh — Cloud function (account + discovery).
 *
 * Actions:
 *   auth/register     — create account (username + password)
 *   auth/login        — login, get sessionToken
 *   discover/register — register device address for cross-network discovery
 *   discover/lookup   — find peer devices under the same account
 */

const cloudbase = require('@cloudbase/node-sdk')
const crypto = require('crypto')
const app = cloudbase.init()
const db = app.database()

const SESSION_TTL = 30 * 24 * 60 * 60 * 1000 // 30 days
const DEVICE_REGISTRATION_TTL = 30 * 60 * 1000 // 30 minutes

// Ensure required collections exist (idempotent)
const COLLECTIONS = ['mesh_users', 'mesh_sessions', 'mesh_discovery']
let collectionsEnsured = false
async function ensureCollections() {
  if (collectionsEnsured) return
  for (const name of COLLECTIONS) {
    try { await db.createCollection(name) } catch {}
  }
  collectionsEnsured = true
}

exports.main = async (event, context) => {
  // CloudBase HTTP trigger: body arrives as string in event.body
  let payload = event
  if (typeof event.body === 'string') {
    try { payload = JSON.parse(event.body) } catch { payload = event }
  }

  const action = payload.action || ''
  await ensureCollections()

  try {
    switch (action) {
      case 'auth/check-username': return await authCheckUsername(payload)
      case 'auth/register': return await authRegister(payload)
      case 'auth/login':    return await authLogin(payload)
      case 'discover/register': return await discoverRegister(payload)
      case 'discover/lookup':   return await discoverLookup(payload)
      default: throw { status: 400, message: `Unknown action: ${action}` }
    }
  } catch (err) {
    console.error(`[nebflow-mesh] ${action} error:`, err.message || err)
    return { code: err.status || 500, message: err.message || 'Internal error' }
  }
}

// ===== Auth =====

async function authCheckUsername(event) {
  const { username } = event
  if (!username) return { available: false }
  const { data: existing } = await db.collection('mesh_users').where({ username }).get()
  return { available: existing.length === 0 }
}

async function authRegister(event) {
  const { username, password } = event
  if (!username || username.length < 3) throw { status: 400, message: 'Username must be at least 3 characters' }
  if (!password || password.length < 6) throw { status: 400, message: 'Password must be at least 6 characters' }
  if (!/^[a-zA-Z0-9_-]+$/.test(username)) throw { status: 400, message: 'Username can only contain letters, numbers, _ and -' }

  const col = db.collection('mesh_users')
  const { data: existing } = await col.where({ username }).get()
  if (existing.length > 0) throw { status: 409, message: 'Username already taken' }

  const userId = generateId()
  const passwordHash = hashPassword(password)
  const sessionToken = generateToken()
  const now = Date.now()

  await col.add({
    userId,
    username,
    passwordHash,
    createdAt: now
  })

  // Store session
  await db.collection('mesh_sessions').add({
    userId,
    sessionToken,
    createdAt: now,
    expiresAt: now + SESSION_TTL
  })

  return { userId, username, sessionToken }
}

async function authLogin(event) {
  const { username, password } = event
  if (!username || !password) throw { status: 400, message: 'Missing username or password' }

  const { data: users } = await db.collection('mesh_users').where({ username }).get()
  if (users.length === 0) throw { status: 401, message: 'Invalid username or password' }

  const user = users[0]
  if (!verifyPassword(password, user.passwordHash)) {
    throw { status: 401, message: 'Invalid username or password' }
  }

  const sessionToken = generateToken()
  const now = Date.now()

  await db.collection('mesh_sessions').add({
    userId: user.userId,
    sessionToken,
    createdAt: now,
    expiresAt: now + SESSION_TTL
  })

  return { userId: user.userId, username: user.username, sessionToken }
}

// ===== Discovery =====

async function discoverRegister(event) {
  const { userId, sessionToken, address, deviceId, deviceName, platform } = event
  const user = await verifySession(userId, sessionToken)

  if (!address) throw { status: 400, message: 'Missing address' }
  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const col = db.collection('mesh_discovery')
  const now = Date.now()
  const expiresAt = now + DEVICE_REGISTRATION_TTL

  const { data: existing } = await col.where({ userId: user.userId, deviceId }).get()

  const record = {
    userId: user.userId,
    deviceId,
    deviceName: deviceName || 'Unknown',
    platform: platform || '',
    address,
    updatedAt: now,
    expiresAt
  }

  if (existing.length > 0) {
    await col.doc(existing[0]._id).update(record)
  } else {
    await col.add({ ...record, createdAt: now })
  }

  return { ok: true, expiresAt }
}

async function discoverLookup(event) {
  const { userId, sessionToken, deviceId } = event
  const user = await verifySession(userId, sessionToken)
  const now = Date.now()

  // Clean up expired entries
  await db.collection('mesh_discovery').where({ expiresAt: db.command.lte(now) }).remove()

  const query = { userId: user.userId }
  if (deviceId) query.deviceId = db.command.neq(deviceId)

  const { data: devices } = await db.collection('mesh_discovery').where(query).get()

  return {
    peers: devices.map(d => ({
      deviceId: d.deviceId,
      deviceName: d.deviceName,
      platform: d.platform,
      address: d.address
    }))
  }
}

// ===== Session Verification =====

async function verifySession(userId, sessionToken) {
  if (!userId || !sessionToken) throw { status: 401, message: 'Missing userId or sessionToken' }

  const { data: sessions } = await db.collection('mesh_sessions')
    .where({ userId, sessionToken })
    .get()

  if (sessions.length === 0) throw { status: 401, message: 'Invalid session' }

  const session = sessions[0]
  if (Date.now() > session.expiresAt) {
    await db.collection('mesh_sessions').doc(session._id).remove()
    throw { status: 401, message: 'Session expired' }
  }

  return { userId: session.userId }
}

// ===== Helpers =====

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex')
  const hash = crypto.scryptSync(password, salt, 64).toString('hex')
  return `${salt}:${hash}`
}

function verifyPassword(password, stored) {
  const [salt, hash] = stored.split(':')
  const verify = crypto.scryptSync(password, salt, 64).toString('hex')
  return hash === verify
}

function generateId() {
  return crypto.randomUUID()
}

function generateToken() {
  return crypto.randomBytes(32).toString('hex')
}
