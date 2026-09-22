// socialChannels.js — Social interface cards: definition layer (data only).
//
// Upstream design (read-only, do not re-derive): the redesigned node card
// `socremote-design` (n-5c95367d) —
//   · §F.2  the channel set is driven by ONE array: order, count and ids all
//           come from SOCIAL_CHANNELS; no second order/count constant exists.
//   · §E.3 X2  `data-status` is the single status authority (style selectors
//           derive from it; the old `.on/.changed` classes are NOT used).
//   · §E.3 X3  array order IS the card order.
//   · §E.3 X4  `webui` is NOT a channel card — it is the remote-access section
//           (REMOTE_ACCESS below), kept out of SOCIAL_CHANNELS.
//   · §D.2  per-card field list, required flags and patterns.
//
// 🔴 PHASE 1 (this batch) — no channel is really connected:
//   `adapterRegistered` is `false` on every channel and there is exactly ONE
//   place that can ever flip the status to `connected` (the branch inside
//   channelStatus below). Nothing in the render layer decides "connected" on
//   its own, so a fake "connected" pill is unreachable by construction.
//   tests/social-panel.spec.mjs pins both directions (W6 production = 0,
//   W7 fixture = the same probe turns red).

/**
 * @typedef {'notConfigured'|'configuredNotLinked'|'configInvalid'|'connected'} SocialStatus
 * `connected` is deliberately IN the closed set (so the state machine is total
 * and the flipped-flag fixture is a real state) but it is unreachable while
 * `adapterRegistered` stays false.
 */

/**
 * @typedef {Object} SocialField
 * @property {string}  key       config key (secret fields store `<key>_ref`)
 * @property {'text'|'secret'|'select'|'url'} kind
 * @property {boolean} required
 * @property {string}  i18n      label key
 * @property {string}  [pattern] JS regex source the value must match
 * @property {string}  [placeholder]
 * @property {string}  [default]
 * @property {string}  [secretName] file name under <dataRoot>/secrets for kind==='secret'
 */

/**
 * @typedef {Object} SocialChannel
 * @property {string} id
 * @property {string} icon        lucide icon name (kebab-case)
 * @property {string} nameKey
 * @property {string} descKey
 * @property {SocialField[]} fields
 * @property {boolean} adapterRegistered  phase 1: always false
 * @property {string} [regionKey]  config key holding the region choice
 * @property {{key: string, base: string}[]} [regions]  the ONLY region source
 * @property {string} [regionDefault]
 */

/**
 * The single source of channel order / count / ids (§F.2 discipline ①②③).
 *
 * Author ruling 2026-09-19 (relayed by the dispatcher 18:28:42): Feishu and
 * Lark are ONE card plus a `region` choice. Flipping back to two cards is a
 * one-line change HERE (split this row into two) — the render layer, the CSS
 * and the tests all read `.length` / the array, so none of them changes.
 *
 * @type {SocialChannel[]}
 */
export const SOCIAL_CHANNELS = [
  {
    id: 'wechat',
    icon: 'message-circle',
    nameKey: 'social.wechat.name',
    descKey: 'social.wechat.desc',
    adapterRegistered: false,
    fields: [
      { key: 'app_id', kind: 'text', required: true, pattern: '^wx[0-9a-f]{16}$',
        i18n: 'social.wechat.field.appId', placeholder: 'wx0123456789abcdef' },
      { key: 'app_secret', kind: 'secret', required: true,
        i18n: 'social.wechat.field.appSecret', secretName: 'social-wechat-app-secret' },
      { key: 'token', kind: 'secret', required: true,
        i18n: 'social.wechat.field.token', secretName: 'social-wechat-token' },
      { key: 'aes_key', kind: 'secret', required: true,
        i18n: 'social.wechat.field.aesKey', secretName: 'social-wechat-aes-key' },
    ],
  },
  {
    // ★ merged card (author ruling): Feishu + Lark, region choice in-card.
    //   `regions` is the ONLY data source for the region select (§F.2 ③).
    id: 'feishu',
    icon: 'send',
    nameKey: 'social.feishu.name',
    descKey: 'social.feishu.desc',
    adapterRegistered: false,
    regionKey: 'region',
    regionDefault: 'feishu',
    regions: [
      { key: 'feishu', base: 'open.feishu.cn' },
      { key: 'lark', base: 'open.larksuite.com' },
    ],
    fields: [
      { key: 'app_id', kind: 'text', required: true, pattern: '^cli_[0-9a-zA-Z]{16,}$',
        i18n: 'social.feishu.field.appId', placeholder: 'cli_xxxxxxxxxxxxxxxx' },
      { key: 'app_secret', kind: 'secret', required: true,
        i18n: 'social.feishu.field.appSecret', secretName: 'social-feishu-app-secret' },
      { key: 'verification_token', kind: 'secret', required: true,
        i18n: 'social.feishu.field.verificationToken', secretName: 'social-feishu-verification-token' },
      { key: 'encrypt_key', kind: 'secret', required: false,
        i18n: 'social.feishu.field.encryptKey', secretName: 'social-feishu-encrypt-key' },
      { key: 'region', kind: 'select', required: true,
        i18n: 'social.feishu.field.region', default: 'feishu' },
    ],
  },
  {
    id: 'telegram',
    icon: 'navigation',
    nameKey: 'social.telegram.name',
    descKey: 'social.telegram.desc',
    adapterRegistered: false,
    fields: [
      { key: 'bot_token', kind: 'secret', required: true,
        i18n: 'social.telegram.field.botToken', secretName: 'social-telegram-bot-token' },
      { key: 'chat_id', kind: 'text', required: true, pattern: '^-?\\d+$',
        i18n: 'social.telegram.field.chatId', placeholder: '-1001234567890' },
      { key: 'api_base', kind: 'url', required: false, pattern: '^https?://',
        i18n: 'social.telegram.field.apiBase', default: 'https://api.telegram.org' },
    ],
  },
];

/**
 * The remote-access face (§A / X4): an independent section, NOT a channel card,
 * and therefore NOT counted by SOCIAL_CHANNELS.length. It keeps the id `webui`
 * for the phase-2 adapter key (BridgePlugin.name) while occupying no channel id.
 */
export const REMOTE_ACCESS = { id: 'webui', kind: 'remote' };

/**
 * The ONE place a card count comes from (§F.2 discipline ②): tests, docs and
 * the render layer all read this instead of a literal.
 * @returns {number}
 */
export function socialChannelCount() {
  return SOCIAL_CHANNELS.length;
}

/**
 * Single lookup point for a channel id (§F.2 discipline ③).
 * @param {string} id
 * @returns {SocialChannel|undefined}
 */
export function channelById(id) {
  return SOCIAL_CHANNELS.find((c) => c.id === id);
}

/**
 * The config key that stores a field's value: secret fields keep only a PATH
 * (`<key>_ref`), never the credential itself (arch §7.2 / §D.1).
 * @param {SocialField} field
 * @returns {string}
 */
export function storedKey(field) {
  return field.kind === 'secret' ? `${field.key}_ref` : field.key;
}

/**
 * The FIRST contradicting fact about a card's config, in exactly the precedence
 * [[channelStatus]] uses — so the pill and its hint can never disagree (one
 * source, §F.2 discipline). `null` = nothing contradicts.
 *
 * Precedence (and its justification):
 *   ① a value that is PRESENT but violates its pattern/kind ⇒ configInvalid.
 *      This is checked BEFORE "required but empty" because the design's W9
 *      fixture is literally「`app_id` 违 pattern ⇒ configInvalid」with no
 *      requirement that the other fields be filled first; the alternative order
 *      would answer「未配置」to a config the author's own anchor calls 配置有误.
 *   ② a secret reference that IS present but whose probe contradicts it
 *      (`exists=false` / `modeOk=false`) ⇒ configInvalid. Gated on the reference
 *      being present: an unconfigured card has no path to probe, so a real
 *      instance's `exists:false` triples for absent files cannot turn an empty
 *      card red (that would break W8 on a live instance).
 *
 * @param {SocialChannel} channel
 * @param {{enabled?: boolean, fields?: Record<string, string>}|null|undefined} cfg
 * @param {Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>|null|undefined} probe
 * @returns {{kind: 'pattern'|'ref', field: SocialField, path: string}|null}
 */
export function channelProblem(channel, cfg, probe) {
  const values = fieldValues(channel, cfg);
  const read = (f) => String(values[storedKey(f)] ?? '').trim();
  for (const f of channel.fields) {
    const v = read(f);
    if (!v || !f.pattern) continue;
    if (!new RegExp(f.pattern).test(v)) return { kind: 'pattern', field: f, path: '' };
  }
  for (const f of channel.fields) {
    if (f.kind !== 'secret') continue;
    const path = read(f);
    if (!path) continue;
    const p = probe && probe[f.key];
    if (p && (p.exists === false || p.modeOk === false)) return { kind: 'ref', field: f, path };
  }
  return null;
}

/**
 * Status of one card — the state machine (§6.1 of arch, re-ordered by X1).
 * Precedence: a contradiction ([[channelProblem]]) → nothing configured →
 * configured-but-unlinked. `connected` is reachable ONLY through
 * `adapterRegistered`, which is `false` for the whole of phase 1 (see the file
 * header); it is therefore the LAST branch before the terminal phase-1 state.
 *
 * @param {SocialChannel} channel
 * @param {{enabled?: boolean, fields?: Record<string, string>}|null|undefined} cfg
 * @param {Record<string, {exists?: boolean, modeOk?: boolean, readable?: boolean}>|null|undefined} probe
 * @returns {SocialStatus}
 */
export function channelStatus(channel, cfg, probe) {
  if (channelProblem(channel, cfg, probe)) return 'configInvalid';
  const values = fieldValues(channel, cfg);
  if (channel.fields.some((f) => f.required && !String(values[storedKey(f)] ?? '').trim())) {
    return 'notConfigured';
  }
  // 🔴 the single change point — phase 2 flips this flag, nothing else.
  if (channel.adapterRegistered === true) return 'connected';
  return 'configuredNotLinked';
}

/**
 * Config values for one card, with the definition-layer defaults filled in.
 * @param {SocialChannel} channel
 * @param {{enabled?: boolean, fields?: Record<string, string>}|null|undefined} cfg
 * @returns {Record<string, string>}
 */
export function fieldValues(channel, cfg) {
  /** @type {Record<string, string>} */
  const out = {};
  const stored = (cfg && cfg.fields) || {};
  for (const f of channel.fields) {
    const k = storedKey(f);
    out[k] = stored[k] ?? f.default ?? '';
  }
  return out;
}
