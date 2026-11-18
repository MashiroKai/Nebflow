## Design intent

The author's words for this batch (2026-11-18 08:54) are the design, not a ticket: **「首先我们登录登出是按设备来的 我们这个控制台 是全设备共用的 而不是说落于某一个设备专属 因此 要在个人主页这里面 放类似…」**. Read together with the existing rulings this fixes four things, and the change below is only the mechanical consequence of them:

1. **个人主页 = 设备管理.** The personal profile is where devices are read and acted on, because that is the one surface whose subject *is* this account across its devices.
2. **每设备独立登出.** Sign-out is per device. The console's account-wide control was strictly less precise than the mechanism (each device stores its own credential locally) — the UI was wrong about its own product, not merely incomplete.
3. **单页铺开、无次级页.** One page. A subpage that repeats the same readout, or an action that opens a face with nothing to act on, is a fake affordance; it is retired rather than hidden.
4. **设计思想进插件与 spec.** The rule is reusable and therefore belongs in the plugin that carries engineering discipline and in this spec — not scattered in code comments.

## Where the device identity comes from

One readout, `GET /api/neblink/status`, consumed at `js/profileDevices.js:72` (`getDeviceList`):

- **this device** ← `state.device` (`deviceId` / `deviceName` / `platform`), rendered as the first row with `current: true` (`js/profileDevices.js:76-85`);
- **paired peers** ← `state.peers[]` (`deviceId` / `deviceName` / `platform` / `lastSeen` / `online` / `directOnline`), rendered in upstream order (`js/profileDevices.js:86-95`).

No new fetch, no new endpoint, no second platform map (`platformDisplay` is reused from `neblink.js`). Unknown values render as unknown (`js/profileDevices.js:61-65` status, `:110-113` meta text).

## What the sign-out scope is

「独立」means **the scope of a row's action is that one device**:

- **current-device row** → the existing single logout chain, `neblink.js openEndSessionHandoff(false)` (`js/profileDevices.js:232-236`). It tears down *this machine's* credential file and its relay/presence entries; every other device's credential lives in that device's own file, which this process cannot reach. Device scope here is a property of the mechanism, not of front-end restraint.
- **peer row** → `POST /api/neblink/devices/{deviceId}/sign-out` carrying that row's own id (`js/profileDevices.js:239-247`). The gateway authorizes with the same credential gate as `/neblink/logout`, validates the id against the local roster (self id or a real peer; anything else is `404`, so the route is not an arbitrary-id proxy), performs the same `performLocalLogout` for the self id, and answers `409 {"code":"remote-sign-out-unsupported"}` for a peer — a truthful reading, never a fabricated `200`. Source: `src/main/scala/nebflow/gateway/NeblinkRoutes.scala:546-590`.

**Non-goal, stated as the boundary of this design:** terminating another device's credential requires a command channel the receiving device honours; the remote-update channel was retired by author ruling, so this change does not rebuild it. The property it does guarantee is the one the ask is about — acting on one device never signs out another.

**Second boundary:** console sessions (`GatewaySessionStore.SessionRecord`) carry no device id, so a browser console session stays transparent to which device it reaches. That is precisely why the console must not be *presented* as belonging to one device; credentials and sign-out are the device-scoped domain, and the profile lists them.

## Why these placements

- `js/profileDevices.js` — one readout, one markup source (`buildDeviceList`, built with the shared light-DOM builder `js/dom.js h()`), one action implementation (`signOutDevice`). Two surfaces rendering two lists from two fetches is the failure the placement prevents; the same reason puts the R5 discipline (no raw HTML-string assignment) on the new module rather than on its consumers.
- Desktop face = the existing personal profile overlay (`js/settingsIdentity.js`), mobile face = the single `me` page (`js/mobile/meFace.js`) — both consume the shared node; neither assembles its own markup.
- `css/settingsRows.css` — existing tokens only; the row separator reuses `--color-frame-border-light`.
- `NeblinkRoutes.scala` — `/neblink` already owns `logout` and `auth/end-session`; a device-scoped spelling of the same teardown belongs next to them.

## Reuse in the plugin

The reusable form of this design is carried verbatim in `plugin-doc-delta.md` as a ready-to-apply patch for the `stand-dev` plugin's frontend discipline references. It is **not landed**: the plugin source is a separate repository whose local checkout lives inside the protected production data root (`/Users/kaiyu/.stand/plugins/stand-dev/`, `/Users/kaiyu/.stand/plugin-repos/stand-dev/`), and writing there is a live-configuration change that this batch is not authorized to make. Landing it is an explicit open item.
