/**
 * Nebflow Mesh Discovery — lightweight cloud function.
 *
 * Only handles cross-network device discovery:
 *   - discover/register: register { tokenHash, address } so other devices can find you
 *   - discover/lookup:  find other devices with the same tokenHash
 *
 * No data relay, no file sync, no pairing codes.
 * All real-time communication is P2P direct between devices.
 */

const cloudbase = require('@cloudbase/node-sdk')
const app = cloudbase.init()
const db = app.database()

const REGISTRATION_TTL = 30 * 60 * 1000 // 30 minutes — refresh periodically

exports.main = async (event, context) => {
  const action = event.action || ''
  const tokenHash = event.tokenHash || ''
  const authHash = extractAuth(event, context)

  try {
    switch (action) {
      case 'discover/register':
        if (!tokenHash || tokenHash.length < 12) throw { status: 400, message: 'Invalid tokenHash' }
        if (authHash && authHash !== tokenHash) throw { status: 403, message: 'Auth mismatch' }
        return await discoverRegister(event, tokenHash)

      case 'discover/lookup':
        if (!tokenHash || tokenHash.length < 12) throw { status: 400, message: 'Invalid tokenHash' }
        if (authHash && authHash !== tokenHash) throw { status: 403, message: 'Auth mismatch' }
        return await discoverLookup(event, tokenHash)

      default:
        throw { status: 400, message: `Unknown action: ${action}` }
    }
  } catch (err) {
    console.error(`[nebflow-mesh-discovery] ${action} error:`, err.message || err)
    return { code: err.status || 500, message: err.message || 'Internal error' }
  }
}

// ===== Discovery =====

async function discoverRegister(event, tokenHash) {
  const { address, deviceId, deviceName, platform } = event
  if (!address) throw { status: 400, message: 'Missing address' }
  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const col = db.collection('mesh_discovery')
  const now = Date.now()
  const expiresAt = now + REGISTRATION_TTL

  // Upsert: update if same deviceId, otherwise insert
  const { data: existing } = await col.where({ tokenHash, deviceId }).get()

  const record = {
    tokenHash,
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

async function discoverLookup(event, tokenHash) {
  const { deviceId } = event
  const now = Date.now()

  // Clean up expired entries
  await db.collection('mesh_discovery').where({ expiresAt: db.command.lte(now) }).remove()

  // Find all devices with same tokenHash, excluding self
  const query = { tokenHash }
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

// ===== Helpers =====

function extractAuth(event, context) {
  if (context && context.customUserId) return context.customUserId
  if (event && event.headers) {
    const auth = event.headers['authorization'] || event.headers['Authorization'] || ''
    if (auth.startsWith('Bearer ')) return auth.slice(7)
  }
  return null
}
