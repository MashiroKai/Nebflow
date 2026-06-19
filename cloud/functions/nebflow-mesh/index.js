/**
 * Nebflow Mesh — Cloud function (account + discovery + session sync + relay).
 *
 * Actions:
 *   auth/register         — create account (username + password)
 *   auth/login            — login, get sessionToken
 *   auth/check-username   — check if username is available
 *   discover/register     — register device address for cross-network discovery
 *   discover/lookup       — find peer devices under the same account
 *   session/push-index    — upload session metadata + folders (merge by updatedAt)
 *   session/pull-index    — download merged session index
 *   session/push          — upload single session messages + uiMessages
 *   session/pull          — download single session messages + uiMessages
 *   session/delete        — delete a session from cloud
 *   session/busy          — set/get busy lock (with TTL auto-expiry)
 *   device/capabilities   — update device capabilities + user description
 *   relay/submit          — submit a command to a remote device via relay
 *   relay/poll            — target device polls for pending relay commands
 *   relay/result          — target device submits execution result
 *   relay/fetch-result    — originator fetches relay command result
 */

const cloudbase = require('@cloudbase/node-sdk')
const crypto = require('crypto')
const app = cloudbase.init()
const db = app.database()

const SESSION_TTL = 30 * 24 * 60 * 60 * 1000 // 30 days
const DEVICE_REGISTRATION_TTL = 7 * 24 * 60 * 60 * 1000 // 7 days
const BUSY_LOCK_TTL = 5 * 60 * 1000 // 5 minutes — stale busy locks auto-expire
const RELAY_TTL = 30 * 60 * 1000 // 30 minutes — relay commands expire if not picked up
const MAX_SESSION_SIZE = 14 * 1024 * 1024 // 14MB — leave headroom under CloudBase 16MB limit

// Ensure required collections exist (idempotent)
const COLLECTIONS = [
  'mesh_users', 'mesh_sessions', 'mesh_discovery',
  'mesh_sync_index', 'mesh_sync_data', 'mesh_busy', 'mesh_relay',
  'mesh_sync_files',
  'mesh_blobs', 'mesh_session_state', 'mesh_session_logs', 'mesh_file_refs',
  'mesh_file_chunks'
]
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
      // Session sync
      case 'session/push-index': return await sessionPushIndex(payload)
      case 'session/pull-index': return await sessionPullIndex(payload)
      case 'session/push':       return await sessionPush(payload)
      case 'session/pull':       return await sessionPull(payload)
      case 'session/delete':     return await sessionDelete(payload)
      case 'session/busy':       return await sessionBusy(payload)
      // Device capabilities
      case 'device/capabilities': return await deviceCapabilities(payload)
      // Cloud relay
      case 'relay/submit':       return await relaySubmit(payload)
      case 'relay/poll':         return await relayPoll(payload)
      case 'relay/result':       return await relayResult(payload)
      case 'relay/fetch-result': return await relayFetchResult(payload)
      // File sync (cloud-based, replaces P2P)
      case 'file/sync':          return await fileSync(payload)
      case 'file/upload':        return await fileUpload(payload)
      case 'file/download':      return await fileDownload(payload)
      case 'file/upload-chunk':  return await fileUploadChunk(payload)
      case 'file/upload-complete': return await fileUploadComplete(payload)
      // Blob-based incremental sync (v2)
      case 'blob/batch-check':     return await blobBatchCheck(payload)
      case 'blob/upload':          return await blobUpload(payload)
      case 'blob/batch-download':  return await blobBatchDownload(payload)
      case 'session/state-sync':   return await sessionStateSync(payload)
      case 'session/log-sync':     return await sessionLogSync(payload)
      case 'session/log-get-all':  return await sessionLogGetAll(payload)
      case 'file/ref-sync-v2':     return await fileRefSyncV2(payload)
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
  const { userId, sessionToken, address, deviceId, deviceName, platform, capabilities, userDescription, deviceSecret } = event
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
    capabilities: capabilities || {},
    userDescription: userDescription || '',
    deviceSecret: deviceSecret || '',
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
      address: d.address,
      capabilities: d.capabilities || {},
      userDescription: d.userDescription || '',
      deviceSecret: d.deviceSecret || ''
    }))
  }
}

// ===== Session Sync =====

/**
 * Push session index (metadata + folders). Merges with existing cloud index:
 * sessions and folders are deduplicated by ID, newest updatedAt wins.
 */
async function sessionPushIndex(event) {
  const { userId, sessionToken, sessions, folders } = event
  const user = await verifySession(userId, sessionToken)
  const now = Date.now()

  const col = db.collection('mesh_sync_index')
  const { data: existing } = await col.where({ userId: user.userId }).get()

  if (existing.length > 0) {
    const doc = existing[0]
    const cloudSessions = doc.sessions || []
    const cloudFolders = doc.folders || []

    // Merge sessions: union by ID, newest updatedAt wins
    const mergedSessions = mergeById(cloudSessions, sessions || [], 'updatedAt')
    const mergedFolders = mergeById(cloudFolders, folders || [], 'updatedAt')

    await col.doc(doc._id).update({
      sessions: mergedSessions,
      folders: mergedFolders,
      updatedAt: now
    })

    return { ok: true, sessionCount: mergedSessions.length, folderCount: mergedFolders.length }
  } else {
    await col.add({
      userId: user.userId,
      sessions: sessions || [],
      folders: folders || [],
      createdAt: now,
      updatedAt: now
    })

    return { ok: true, sessionCount: (sessions || []).length, folderCount: (folders || []).length }
  }
}

/**
 * Pull merged session index (metadata + folders).
 */
async function sessionPullIndex(event) {
  const { userId, sessionToken, since } = event
  const user = await verifySession(userId, sessionToken)

  const col = db.collection('mesh_sync_index')
  const { data: docs } = await col.where({ userId: user.userId }).get()

  if (docs.length === 0) {
    return { sessions: [], folders: [], updatedAt: 0 }
  }

  const doc = docs[0]
  return {
    sessions: doc.sessions || [],
    folders: doc.folders || [],
    updatedAt: doc.updatedAt || 0
  }
}

/**
 * Push a single session's messages + uiMessages.
 * Overwrites the cloud copy (the pushing device is the active one).
 */
async function sessionPush(event) {
  const { userId, sessionToken, sessionId, messages, uiMessages, meta } = event
  const user = await verifySession(userId, sessionToken)

  if (!sessionId) throw { status: 400, message: 'Missing sessionId' }

  const payload = JSON.stringify({ messages, uiMessages })
  if (payload.length > MAX_SESSION_SIZE) {
    throw { status: 413, message: `Session data too large (${payload.length} bytes, max ${MAX_SESSION_SIZE}). Consider compacting.` }
  }

  const now = Date.now()
  const col = db.collection('mesh_sync_data')
  const { data: existing } = await col.where({ userId: user.userId, sessionId }).get()

  const record = {
    userId: user.userId,
    sessionId,
    messages: messages || [],
    uiMessages: uiMessages || [],
    meta: meta || {},
    updatedAt: now
  }

  if (existing.length > 0) {
    await col.doc(existing[0]._id).update(record)
  } else {
    await col.add({ ...record, createdAt: now })
  }

  return { ok: true, updatedAt: now }
}

/**
 * Pull a single session's messages + uiMessages.
 */
async function sessionPull(event) {
  const { userId, sessionToken, sessionId } = event
  const user = await verifySession(userId, sessionToken)

  if (!sessionId) throw { status: 400, message: 'Missing sessionId' }

  const col = db.collection('mesh_sync_data')
  const { data: docs } = await col.where({ userId: user.userId, sessionId }).get()

  if (docs.length === 0) {
    return { found: false }
  }

  const doc = docs[0]
  return {
    found: true,
    messages: doc.messages || [],
    uiMessages: doc.uiMessages || [],
    meta: doc.meta || {},
    updatedAt: doc.updatedAt || 0
  }
}

/**
 * Delete a session from cloud sync data.
 */
async function sessionDelete(event) {
  const { userId, sessionToken, sessionId } = event
  const user = await verifySession(userId, sessionToken)

  if (!sessionId) throw { status: 400, message: 'Missing sessionId' }

  // Remove session data
  await db.collection('mesh_sync_data')
    .where({ userId: user.userId, sessionId })
    .remove()

  // Remove from index
  const idxCol = db.collection('mesh_sync_index')
  const { data: idxDocs } = await idxCol.where({ userId: user.userId }).get()
  if (idxDocs.length > 0) {
    const doc = idxDocs[0]
    const updatedSessions = (doc.sessions || []).filter(s => s.id !== sessionId)
    await idxCol.doc(doc._id).update({ sessions: updatedSessions, updatedAt: Date.now() })
  }

  // Clear busy lock if any
  await db.collection('mesh_busy')
    .where({ userId: user.userId, sessionId })
    .remove()

  return { ok: true }
}

/**
 * Set or query busy lock for a session.
 *
 * Body: { userId, sessionToken, sessionId, busy, deviceId }
 *   - busy=true + deviceId: acquire lock (only if not held by another active device)
 *   - busy=false + deviceId: release lock (only if held by this device)
 *   - busy omitted: just query current state
 *
 * Returns: { busy, busyDeviceId, busyDeviceName }
 */
async function sessionBusy(event) {
  const { userId, sessionToken, sessionId, busy, deviceId, deviceName } = event
  const user = await verifySession(userId, sessionToken)

  if (!sessionId) throw { status: 400, message: 'Missing sessionId' }

  const col = db.collection('mesh_busy')
  const now = Date.now()

  // Clean up stale busy locks
  await col.where({ expiresAt: db.command.lte(now) }).remove()

  const { data: locks } = await col.where({ userId: user.userId, sessionId }).get()

  if (busy === undefined) {
    // Query mode
    if (locks.length === 0) return { busy: false, busyDeviceId: null, busyDeviceName: null }
    return {
      busy: true,
      busyDeviceId: locks[0].deviceId,
      busyDeviceName: locks[0].deviceName || ''
    }
  }

  if (busy === true) {
    // Try to acquire lock
    if (locks.length > 0) {
      const holder = locks[0]
      if (holder.deviceId !== deviceId) {
        // Lock held by another device
        return {
          busy: true,
          busyDeviceId: holder.deviceId,
          busyDeviceName: holder.deviceName || '',
          acquired: false
        }
      }
      // Already holding the lock — refresh
      await col.doc(holder._id).update({ timestamp: now, expiresAt: now + BUSY_LOCK_TTL })
      return { busy: true, busyDeviceId: deviceId, busyDeviceName: deviceName || '', acquired: true }
    }
    // No existing lock — acquire
    await col.add({
      userId: user.userId,
      sessionId,
      deviceId,
      deviceName: deviceName || '',
      timestamp: now,
      expiresAt: now + BUSY_LOCK_TTL
    })
    return { busy: true, busyDeviceId: deviceId, busyDeviceName: deviceName || '', acquired: true }
  }

  if (busy === false) {
    // Release lock (only if held by this device)
    if (locks.length > 0 && locks[0].deviceId === deviceId) {
      await col.doc(locks[0]._id).remove()
    }
    return { busy: false, busyDeviceId: null, busyDeviceName: null, acquired: true }
  }

  return { busy: false, busyDeviceId: null, busyDeviceName: null }
}

// ===== Device Capabilities =====

/**
 * Update device capabilities and user description.
 * Stored in mesh_discovery record.
 */
async function deviceCapabilities(event) {
  const { userId, sessionToken, deviceId, capabilities, userDescription } = event
  const user = await verifySession(userId, sessionToken)

  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const col = db.collection('mesh_discovery')
  const { data: existing } = await col.where({ userId: user.userId, deviceId }).get()

  if (existing.length === 0) throw { status: 404, message: 'Device not registered' }

  const update = {}
  if (capabilities !== undefined) update.capabilities = capabilities
  if (userDescription !== undefined) update.userDescription = userDescription
  update.updatedAt = Date.now()

  await col.doc(existing[0]._id).update(update)

  return { ok: true }
}

// ===== Cloud Relay =====

/**
 * Submit a command to a remote device via cloud relay.
 * Used as fallback when direct P2P connection fails (NAT, firewall, etc.).
 *
 * Body: { userId, sessionToken, fromDeviceId, toDeviceId, action, params }
 * Returns: { relayId }
 */
async function relaySubmit(event) {
  const { userId, sessionToken, fromDeviceId, toDeviceId, action, params } = event
  const user = await verifySession(userId, sessionToken)

  if (!fromDeviceId || !toDeviceId || !action) {
    throw { status: 400, message: 'Missing fromDeviceId, toDeviceId, or action' }
  }

  const now = Date.now()
  const relayId = generateId()

  await db.collection('mesh_relay').add({
    relayId,
    userId: user.userId,
    fromDeviceId,
    toDeviceId,
    action,
    params: params || {},
    status: 'pending',
    result: null,
    error: null,
    createdAt: now,
    expiresAt: now + RELAY_TTL
  })

  return { relayId }
}

/**
 * Target device polls for pending relay commands.
 *
 * Body: { userId, sessionToken, deviceId }
 * Returns: { commands: [{ relayId, fromDeviceId, action, params }] }
 */
async function relayPoll(event) {
  const { userId, sessionToken, deviceId } = event
  const user = await verifySession(userId, sessionToken)

  if (!deviceId) throw { status: 400, message: 'Missing deviceId' }

  const now = Date.now()

  // Clean up expired relay commands
  await db.collection('mesh_relay').where({ expiresAt: db.command.lte(now) }).remove()

  const { data: commands } = await db.collection('mesh_relay')
    .where({ userId: user.userId, toDeviceId: deviceId, status: 'pending' })
    .get()

  // Mark as "running" so other polls don't pick them up
  for (const cmd of commands) {
    await db.collection('mesh_relay').doc(cmd._id).update({ status: 'running', pickedAt: now })
  }

  return {
    commands: commands.map(c => ({
      relayId: c.relayId,
      fromDeviceId: c.fromDeviceId,
      action: c.action,
      params: c.params
    }))
  }
}

/**
 * Target device submits execution result for a relay command.
 *
 * Body: { userId, sessionToken, relayId, result, error }
 */
async function relayResult(event) {
  const { userId, sessionToken, relayId, result, error } = event
  const user = await verifySession(userId, sessionToken)

  if (!relayId) throw { status: 400, message: 'Missing relayId' }

  const col = db.collection('mesh_relay')
  const { data: docs } = await col.where({ relayId }).get()

  if (docs.length === 0) throw { status: 404, message: 'Relay command not found' }

  await col.doc(docs[0]._id).update({
    status: error ? 'error' : 'done',
    result: result || null,
    error: error || null,
    completedAt: Date.now()
  })

  return { ok: true }
}

/**
 * Originator fetches the result of a relay command.
 *
 * Body: { userId, sessionToken, relayId }
 * Returns: { status: "pending" | "running" | "done" | "error", result, error }
 */
async function relayFetchResult(event) {
  const { userId, sessionToken, relayId } = event
  const user = await verifySession(userId, sessionToken)

  if (!relayId) throw { status: 400, message: 'Missing relayId' }

  const { data: docs } = await db.collection('mesh_relay').where({ relayId }).get()

  if (docs.length === 0) {
    return { status: 'not_found' }
  }

  const doc = docs[0]
  return {
    status: doc.status,
    result: doc.result,
    error: doc.error
  }
}

// ===== File Sync (content in COS, metadata in document DB) =====

/**
 * Fingerprint exchange — returns ONLY fileIDs, no content.
 * Client downloads each file separately via file/download.
 *
 * Body: { userId, sessionToken, fingerprints: { path: {mtime, size, hash} } }
 * Returns: { download: [{path, fileID}], uploadNeeded: [paths] }
 */
async function fileSync(event) {
  const { userId, sessionToken, fingerprints } = event
  const user = await verifySession(userId, sessionToken)

  const localFps = fingerprints || {}
  const col = db.collection('mesh_sync_files')
  const { data: cloudFiles } = await col.where({ userId: user.userId }).get()
  const cloudMap = new Map()
  for (const f of cloudFiles) cloudMap.set(f.path, f)

  const download = []
  const uploadNeeded = []

  for (const [path, localFp] of Object.entries(localFps)) {
    const cloud = cloudMap.get(path)
    if (!cloud || !cloud.fileID) {
      // Cloud doesn't have it (or old record without COS fileID) — local should upload
      uploadNeeded.push(path)
    } else if (localFp.hash === cloud.fingerprint?.hash) {
      // Same — skip
    } else if (localFp.mtime > (cloud.fingerprint?.mtime || 0)) {
      uploadNeeded.push(path)
    } else {
      download.push({ path, fileID: cloud.fileID })
    }
  }

  for (const [path, cloud] of cloudMap) {
    if (!localFps[path] && cloud.fileID) {
      // Cloud-only file with valid fileID — client needs to download
      download.push({ path, fileID: cloud.fileID })
    }
  }

  return { download, uploadNeeded }
}

/**
 * Upload file to COS. Stores fileID in document DB (not content).
 *
 * Body: { userId, sessionToken, path, content (base64), fingerprint }
 */
async function fileUpload(event) {
  const { userId, sessionToken, path, content, fingerprint } = event
  const user = await verifySession(userId, sessionToken)
  if (!path) throw { status: 400, message: 'Missing path' }

  const buffer = Buffer.from(content || '', 'base64')
  const col = db.collection('mesh_sync_files')
  const { data: existing } = await col.where({ userId: user.userId, path }).get()

  // Upload to COS
  const cloudPath = `nebflow-files/${user.userId}/${path}`
  const uploadResult = await app.uploadFile({ cloudPath, fileContent: buffer })
  const fileID = uploadResult.fileID

  const now = Date.now()
  const record = { userId: user.userId, path, fileID, fingerprint: fingerprint || {}, updatedAt: now }

  if (existing.length > 0) {
    if (existing[0].fileID && existing[0].fileID !== fileID) {
      try { await app.deleteFile({ fileIDList: [existing[0].fileID] }) } catch {}
    }
    await col.doc(existing[0]._id).update(record)
  } else {
    await col.add({ ...record, createdAt: now })
  }

  return { ok: true, size: buffer.length }
}

/**
 * Download file from COS by fileID.
 *
 * Body: { userId, sessionToken, fileID }
 * Returns: { content: base64 }
 */
async function fileDownload(event) {
  const { userId, sessionToken, fileID } = event
  const user = await verifySession(userId, sessionToken)
  if (!fileID) throw { status: 400, message: 'Missing fileID' }

  const result = await app.downloadFile({ fileID })
  return { content: result.fileContent.toString('base64') }
}

// ===== Chunked file upload (for large files) =====

/**
 * Receive a single chunk of a large file.
 * Body: { userId, sessionToken, path, chunkIndex, totalChunks, content (base64) }
 */
async function fileUploadChunk(event) {
  const { userId, sessionToken, path, chunkIndex, totalChunks, content } = event
  const user = await verifySession(userId, sessionToken)
  if (!path) throw { status: 400, message: 'Missing path' }

  await db.collection('mesh_file_chunks').add({
    userId: user.userId,
    path,
    chunkIndex,
    totalChunks,
    content: content || '',
    createdAt: Date.now()
  })

  return { ok: true, chunkIndex }
}

/**
 * Assemble chunks, upload to COS, clean up.
 * Body: { userId, sessionToken, path, fingerprint }
 */
async function fileUploadComplete(event) {
  const { userId, sessionToken, path, fingerprint } = event
  const user = await verifySession(userId, sessionToken)
  if (!path) throw { status: 400, message: 'Missing path' }

  const chunkCol = db.collection('mesh_file_chunks')
  const { data: chunks } = await chunkCol
    .where({ userId: user.userId, path })
    .orderBy('chunkIndex', 'asc')
    .get()

  if (chunks.length === 0) throw { status: 400, message: 'No chunks found for path' }

  // Assemble content
  const fullContent = chunks.map(c => c.content).join('')
  const buffer = Buffer.from(fullContent, 'base64')

  // Upload to COS
  const cloudPath = `nebflow-files/${user.userId}/${path}`
  const uploadResult = await app.uploadFile({ cloudPath, fileContent: buffer })
  const fileID = uploadResult.fileID

  // Store metadata
  const col = db.collection('mesh_sync_files')
  const { data: existing } = await col.where({ userId: user.userId, path }).get()
  const now = Date.now()
  const record = { userId: user.userId, path, fileID, fingerprint: fingerprint || {}, updatedAt: now }

  if (existing.length > 0) {
    if (existing[0].fileID && existing[0].fileID !== fileID) {
      try { await app.deleteFile({ fileIDList: [existing[0].fileID] }) } catch {}
    }
    await col.doc(existing[0]._id).update(record)
  } else {
    await col.add({ ...record, createdAt: now })
  }

  // Clean up chunks
  await chunkCol.where({ userId: user.userId, path }).remove()

  return { ok: true, size: buffer.length, chunks: chunks.length }
}

// ===== Blob-Based Incremental Sync (v2) =====

const MAX_BLOB_SIZE = 10 * 1024 * 1024

async function blobBatchCheck(event) {
  const { userId, sessionToken, hashes } = event
  const user = await verifySession(userId, sessionToken)
  if (!hashes || hashes.length === 0) return { missing: [] }
  const { data: existing } = await db.collection('mesh_blobs')
    .where({ userId: user.userId, hash: db.command.in(hashes) })
    .field({ hash: true }).get()
  const existingSet = new Set(existing.map(d => d.hash))
  return { missing: hashes.filter(h => !existingSet.has(h)) }
}

async function blobUpload(event) {
  const { userId, sessionToken, hash, content } = event
  const user = await verifySession(userId, sessionToken)
  if (!hash || !content) throw { status: 400, message: 'Missing hash or content' }
  const col = db.collection('mesh_blobs')
  const { data: existing } = await col.where({ userId: user.userId, hash }).get()
  if (existing.length > 0) return { ok: true, exists: true }
  const buffer = Buffer.from(content, 'base64')
  if (buffer.length > MAX_BLOB_SIZE) throw { status: 413, message: `Blob too large: ${buffer.length}` }
  const cloudPath = `nebflow-blobs/${user.userId}/${hash}`
  const result = await app.uploadFile({ cloudPath, fileContent: buffer })
  await col.add({ userId: user.userId, hash, size: buffer.length, fileID: result.fileID, createdAt: Date.now() })
  return { ok: true, size: buffer.length }
}

async function blobBatchDownload(event) {
  const { userId, sessionToken, hashes } = event
  const user = await verifySession(userId, sessionToken)
  if (!hashes || hashes.length === 0) return { blobs: {} }
  const { data: blobDocs } = await db.collection('mesh_blobs')
    .where({ userId: user.userId, hash: db.command.in(hashes) }).get()
  const results = {}
  for (const doc of blobDocs) {
    try {
      const dl = await app.downloadFile({ fileID: doc.fileID })
      results[doc.hash] = dl.fileContent.toString('base64')
    } catch (e) { console.error(`[blob-dl] ${doc.hash}: ${e.message}`) }
  }
  return { blobs: results }
}

async function sessionStateSync(event) {
  const { userId, sessionToken, states } = event
  const user = await verifySession(userId, sessionToken)
  const col = db.collection('mesh_session_state')
  for (const s of (states || [])) {
    const { data: existing } = await col.where({ userId: user.userId, sessionId: s.sessionId }).get()
    if (existing.length > 0) {
      if ((s.updatedAt || 0) > (existing[0].updatedAt || 0)) {
        await col.doc(existing[0]._id).update({ name: s.name, agentName: s.agentName, folderId: s.folderId, updatedAt: s.updatedAt })
      }
    } else {
      await col.add({ userId: user.userId, sessionId: s.sessionId, name: s.name, agentName: s.agentName, folderId: s.folderId, updatedAt: s.updatedAt || Date.now(), createdAt: Date.now() })
    }
  }
  const { data: allStates } = await col.where({ userId: user.userId }).get()
  return { states: allStates.map(s => ({ sessionId: s.sessionId, name: s.name, agentName: s.agentName, folderId: s.folderId, updatedAt: s.updatedAt || 0 })) }
}

async function sessionLogSync(event) {
  const { userId, sessionToken, sessionId, messageHashes, uiMessageHashes } = event
  const user = await verifySession(userId, sessionToken)
  if (!sessionId) throw { status: 400, message: 'Missing sessionId' }
  const col = db.collection('mesh_session_logs')
  const { data: existing } = await col.where({ userId: user.userId, sessionId }).get()
  let existingMsg = [], existingUi = []
  if (existing.length > 0) { existingMsg = existing[0].messageHashes || []; existingUi = existing[0].uiMessageHashes || [] }
  const existingMsgSet = new Set(existingMsg), existingUiSet = new Set(existingUi)
  const newMsg = (messageHashes || []).filter(h => !existingMsgSet.has(h))
  const newUi = (uiMessageHashes || []).filter(h => !existingUiSet.has(h))
  const now = Date.now()
  if (existing.length > 0) {
    const update = { updatedAt: now }
    if (newMsg.length > 0) update.messageHashes = db.command.push(newMsg)
    if (newUi.length > 0) update.uiMessageHashes = db.command.push(newUi)
    await col.doc(existing[0]._id).update(update)
    return { messageHashes: [...existingMsg, ...newMsg], uiMessageHashes: [...existingUi, ...newUi], updatedAt: now }
  } else {
    const msgH = messageHashes || [], uiH = uiMessageHashes || []
    await col.add({ userId: user.userId, sessionId, messageHashes: msgH, uiMessageHashes: uiH, createdAt: now, updatedAt: now })
    return { messageHashes: msgH, uiMessageHashes: uiH, updatedAt: now }
  }
}

async function sessionLogGetAll(event) {
  const { userId, sessionToken } = event
  const user = await verifySession(userId, sessionToken)
  const { data: logs } = await db.collection('mesh_session_logs').where({ userId: user.userId }).get()
  return { logs: logs.map(l => ({ sessionId: l.sessionId, messageHashes: l.messageHashes || [], uiMessageHashes: l.uiMessageHashes || [], updatedAt: l.updatedAt || 0 })) }
}

async function fileRefSyncV2(event) {
  const { userId, sessionToken, files } = event
  const user = await verifySession(userId, sessionToken)
  const col = db.collection('mesh_file_refs')
  const { data: cloudFiles } = await col.where({ userId: user.userId }).get()
  const cloudMap = new Map(cloudFiles.map(f => [f.path, f.hash]))
  const localFiles = files || {}
  const needUpload = [], needDownload = []
  for (const [path, hash] of Object.entries(localFiles)) {
    const cloudHash = cloudMap.get(path)
    if (cloudHash !== hash) {
      needUpload.push(path)
      const { data: existing } = await col.where({ userId: user.userId, path }).get()
      if (existing.length > 0) await col.doc(existing[0]._id).update({ hash, updatedAt: Date.now() })
      else await col.add({ userId: user.userId, path, hash, createdAt: Date.now(), updatedAt: Date.now() })
    }
  }
  const resultFiles = {}
  for (const f of cloudFiles) resultFiles[f.path] = f.hash
  for (const [path, hash] of Object.entries(localFiles)) resultFiles[path] = hash
  for (const [path, cloudHash] of cloudMap) { if (localFiles[path] !== cloudHash) needDownload.push(path) }
  return { files: resultFiles, needUpload, needDownload }
}

// ===== Blob-Based Incremental Sync (v2) =====

const MAX_BLOB_SIZE = 10 * 1024 * 1024

async function blobBatchCheck(event) {
  const { userId, sessionToken, hashes } = event
  const user = await verifySession(userId, sessionToken)
  if (!hashes || hashes.length === 0) return { missing: [] }
  const { data: existing } = await db.collection('mesh_blobs')
    .where({ userId: user.userId, hash: db.command.in(hashes) })
    .field({ hash: true }).get()
  const existingSet = new Set(existing.map(d => d.hash))
  return { missing: hashes.filter(h => !existingSet.has(h)) }
}

async function blobUpload(event) {
  const { userId, sessionToken, hash, content } = event
  const user = await verifySession(userId, sessionToken)
  if (!hash || !content) throw { status: 400, message: 'Missing hash or content' }
  const col = db.collection('mesh_blobs')
  const { data: existing } = await col.where({ userId: user.userId, hash }).get()
  if (existing.length > 0) return { ok: true, exists: true }
  const buffer = Buffer.from(content, 'base64')
  if (buffer.length > MAX_BLOB_SIZE) throw { status: 413, message: `Blob too large: ${buffer.length}` }
  const cloudPath = `nebflow-blobs/${user.userId}/${hash}`
  const result = await app.uploadFile({ cloudPath, fileContent: buffer })
  await col.add({ userId: user.userId, hash, size: buffer.length, fileID: result.fileID, createdAt: Date.now() })
  return { ok: true, size: buffer.length }
}

async function blobBatchDownload(event) {
  const { userId, sessionToken, hashes } = event
  const user = await verifySession(userId, sessionToken)
  if (!hashes || hashes.length === 0) return { blobs: {} }
  const { data: blobDocs } = await db.collection('mesh_blobs')
    .where({ userId: user.userId, hash: db.command.in(hashes) }).get()
  const results = {}
  for (const doc of blobDocs) {
    try {
      const dl = await app.downloadFile({ fileID: doc.fileID })
      results[doc.hash] = dl.fileContent.toString('base64')
    } catch (e) { console.error(`[blob-dl] ${doc.hash}: ${e.message}`) }
  }
  return { blobs: results }
}

async function sessionStateSync(event) {
  const { userId, sessionToken, states } = event
  const user = await verifySession(userId, sessionToken)
  const col = db.collection('mesh_session_state')
  for (const s of (states || [])) {
    const { data: existing } = await col.where({ userId: user.userId, sessionId: s.sessionId }).get()
    if (existing.length > 0) {
      if ((s.updatedAt || 0) > (existing[0].updatedAt || 0)) {
        await col.doc(existing[0]._id).update({ name: s.name, agentName: s.agentName, folderId: s.folderId, updatedAt: s.updatedAt })
      }
    } else {
      await col.add({ userId: user.userId, sessionId: s.sessionId, name: s.name, agentName: s.agentName, folderId: s.folderId, updatedAt: s.updatedAt || Date.now(), createdAt: Date.now() })
    }
  }
  const { data: allStates } = await col.where({ userId: user.userId }).get()
  return { states: allStates.map(s => ({ sessionId: s.sessionId, name: s.name, agentName: s.agentName, folderId: s.folderId, updatedAt: s.updatedAt || 0 })) }
}

async function sessionLogSync(event) {
  const { userId, sessionToken, sessionId, messageHashes, uiMessageHashes } = event
  const user = await verifySession(userId, sessionToken)
  if (!sessionId) throw { status: 400, message: 'Missing sessionId' }
  const col = db.collection('mesh_session_logs')
  const { data: existing } = await col.where({ userId: user.userId, sessionId }).get()
  let existingMsg = [], existingUi = []
  if (existing.length > 0) { existingMsg = existing[0].messageHashes || []; existingUi = existing[0].uiMessageHashes || [] }
  const existingMsgSet = new Set(existingMsg), existingUiSet = new Set(existingUi)
  const newMsg = (messageHashes || []).filter(h => !existingMsgSet.has(h))
  const newUi = (uiMessageHashes || []).filter(h => !existingUiSet.has(h))
  const now = Date.now()
  if (existing.length > 0) {
    const update = { updatedAt: now }
    if (newMsg.length > 0) update.messageHashes = db.command.push(newMsg)
    if (newUi.length > 0) update.uiMessageHashes = db.command.push(newUi)
    await col.doc(existing[0]._id).update(update)
    return { messageHashes: [...existingMsg, ...newMsg], uiMessageHashes: [...existingUi, ...newUi], updatedAt: now }
  } else {
    const msgH = messageHashes || [], uiH = uiMessageHashes || []
    await col.add({ userId: user.userId, sessionId, messageHashes: msgH, uiMessageHashes: uiH, createdAt: now, updatedAt: now })
    return { messageHashes: msgH, uiMessageHashes: uiH, updatedAt: now }
  }
}

async function sessionLogGetAll(event) {
  const { userId, sessionToken } = event
  const user = await verifySession(userId, sessionToken)
  const { data: logs } = await db.collection('mesh_session_logs').where({ userId: user.userId }).get()
  return { logs: logs.map(l => ({ sessionId: l.sessionId, messageHashes: l.messageHashes || [], uiMessageHashes: l.uiMessageHashes || [], updatedAt: l.updatedAt || 0 })) }
}

async function fileRefSyncV2(event) {
  const { userId, sessionToken, files } = event
  const user = await verifySession(userId, sessionToken)
  const col = db.collection('mesh_file_refs')
  const { data: cloudFiles } = await col.where({ userId: user.userId }).get()
  const cloudMap = new Map(cloudFiles.map(f => [f.path, f.hash]))
  const localFiles = files || {}
  const needUpload = [], needDownload = []
  for (const [path, hash] of Object.entries(localFiles)) {
    const cloudHash = cloudMap.get(path)
    if (cloudHash !== hash) {
      needUpload.push(path)
      const { data: existing } = await col.where({ userId: user.userId, path }).get()
      if (existing.length > 0) await col.doc(existing[0]._id).update({ hash, updatedAt: Date.now() })
      else await col.add({ userId: user.userId, path, hash, createdAt: Date.now(), updatedAt: Date.now() })
    }
  }
  const resultFiles = {}
  for (const f of cloudFiles) resultFiles[f.path] = f.hash
  for (const [path, hash] of Object.entries(localFiles)) resultFiles[path] = hash
  for (const [path, cloudHash] of cloudMap) { if (localFiles[path] !== cloudHash) needDownload.push(path) }
  return { files: resultFiles, needUpload, needDownload }
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

/**
 * Merge two arrays of objects by their "id" field.
 * When both arrays contain the same id, the one with newer `dateField` wins.
 */
function mergeById(cloudArr, localArr, dateField) {
  const map = new Map()
  for (const item of cloudArr) {
    if (item.id) map.set(item.id, item)
  }
  for (const item of localArr) {
    if (!item.id) continue
    const existing = map.get(item.id)
    if (!existing) {
      map.set(item.id, item)
    } else {
      // Newer updatedAt wins
      const existingDate = existing[dateField] || 0
      const newItemDate = item[dateField] || 0
      if (newItemDate >= existingDate) {
        map.set(item.id, item)
      }
    }
  }
  return Array.from(map.values())
}

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
