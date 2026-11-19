# Design — where each reading comes from, and what "unknown" is allowed to mean

## The author's words are the design

「我们个人主页里的信息很多都没获取全 比如github绑定信息 这些都[需要]补全 另外在个人主页
再次点击头像要能跳转官网」 (2026-11-18 16:45). Read against the profile surfaces that
already exist, that fixes three things:

1. **A reading the client already holds must be rendered.** 未知 is reserved for "this
   machine has no such reading", not for "nobody wired the renderer yet".
2. **未知 must carry its reason.** A field that cannot be read has to say which layer lacks
   it and who owns it — the author asked for the information, not for a tidier blank.
3. **The profile page's avatar is a website entry.** It had no behaviour, so this adds one
   instead of overloading an existing gesture.

## Data chain, per field (the table the delivery report repeats)

| Field | Rendered from | Source of truth | Layer that would have to change if it is wrong |
| --- | --- | --- | --- |
| 用户名 | `getIdentityReadout().username` | self-probe `GET /api/users/search?q=<email>` → `relation_status === 'self'` → `username` (`js/friendsApi.js` `searchUser` → `js/settingsIdentity.js` `probeSelf`) | the account service behind the friend-search contract |
| 昵称 | `getIdentityReadout().nickname` | `device.displayName` ← `GET /api/neblink/status` ← id_token `name` claim (`gateway/NeblinkRoutes.scala` decodes it) | the Logto tenant's `name` claim |
| 邮箱 | `getIdentityReadout().email` | `device.email` ← the same status payload ← id_token `email` claim | the Logto tenant's `email` claim |
| GitHub 绑定 | `getIdentityReadout().github` | `device.githubLogin` ← status payload (`NeblinkRoutes.scala:296`) ← persisted identity (`device.json`) ← enroll response `githubUsername` (`neblink/identity/NeblinkEnrollment.scala:160`) | the NebLink server's `github_username` for this account |
| Google 绑定 | nothing — always `null` | **no layer** | the website account page / account service that owns bindings |

## Why username needed a probe entry point, not a backend field

The login id_token *does* carry a `username` claim on this account, and surfacing it from
`NeblinkRoutes` would have been a smaller frontend diff. It was rejected: the existing,
documented authoritative read path is the self-probe (it is the one that self-certifies with
`relation_status === 'self'`, and it is what the desktop face and the settings card already
use), and adding a second authoritative source for the same field would create two readings
that can disagree. The defect was that the mobile page never started the probe — so the fix
is one exported entry point (`ensureIdentityLive`) that every identity surface calls, not a
new data source.

## Why `githubLogin` is now mapped although a previous comment refused to map it

`js/neblink.js` used to drop `device.githubLogin` with the note "the client is Logto-only now
and the field had no consumer". Both halves of that note are addressed rather than ignored:
there is now a consumer (the binding row), and the field is *labelled by provenance* — it is
the GitHub account name the server sent, not a claim that the account is currently authorised
against GitHub. When the server sends an empty string the row renders 未知 and the gap
sentence names it, so a stale or absent value can never masquerade as a live binding.

## What "unknown with a reason" looks like

One function, `settingsIdentity.bindingGapNote(id)`, returns the sentence for whichever
readings are genuinely missing (logged-out state returns nothing: that 未知 is explained by
the page's own signed-out reading). The mobile page renders it as `.mf-me-gapnote` and the
desktop face as `#settings-profile-bindnote`; the class names stay distinct so a test can
point at the field-gap sentence and the website-guidance sentence separately.

## The avatar entry

`getHomeUrl()` in `js/brand.js` derives the origin from the injected account-page URL and
falls back to `https://mystand.space`; `brand.domain` is deliberately not used (it ships the
`neblink.example` placeholder — the same reason `getProfileUrl()` exists). The mobile profile
card's avatar frame keeps its exact classes and structure and changes only its tag: `div` →
`a`. No geometry, paint or two-state logic is touched, and the desktop face keeps its
anchor-free read-only shape.

## What this change deliberately does not do

- It does not add a binding endpoint, a local binding cache, or any inferred value: an
  unreadable binding stays unreadable and says so.
- It does not touch `openspec/changes/profile-multidevice`; this proposal supersedes its
  "GitHub / Google binding reads — all still absent upstream" sentence for GitHub and
  restates the Google half with its layer named.
- It does not land, push, or tag anything.
