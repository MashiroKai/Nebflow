# Plugin doc delta — device-scoped identity surfaces (landable patch, NOT LANDED)

**Status**: NOT LANDED — and this batch is not authorized to land it. The `stand-dev` plugin is a separate, source-managed repository (`git@github.com:MashiroKai/stand-dev.git`); its working checkouts live inside the protected production data root — `/Users/kaiyu/.stand/plugins/stand-dev/` (the installed copy the live instance loads) and `/Users/kaiyu/.stand/plugin-repos/stand-dev/` (the source clone). Writing to either is a **live-configuration change** (it moves the plugin digest, which the panel surfaces as 「内容已变更」) with its own authority requirement, and the batch brief forbids touching the data root. So the rule is carried here, in the repository that owns the batch, as a patch the plugin owner applies.

**Where the rule applies in the plugin**: `skills/stand-dev/references/frontend/stand-frontend.md` (frontend discipline) and `skills/stand-dev/references/design/design-spec.md` (design rules). Add the section below; nothing else in the plugin changes.

**How to land it** (owner-run, outside this batch):

```sh
cd /Users/kaiyu/.stand/plugin-repos/stand-dev
# paste the 「Device-scoped identity surfaces」 section into
#   skills/stand-dev/references/frontend/stand-frontend.md
# and cross-reference it from
#   skills/stand-dev/references/design/design-spec.md
git add skills/stand-dev/references/frontend/stand-frontend.md skills/stand-dev/references/design/design-spec.md
git commit -m "Document device-scoped identity surfaces"
# then re-install the plugin so the installed copy matches the source (separately authorized)
```

**In-repo half of the same rule**: the normative form is landed here as this change's spec delta (`specs/device-identity-surfaces/spec.md`) and its rationale in `design.md` — the repository-tracked half. The plugin half is the reusable form for *other* surfaces (contacts, project device pickers, agent device selection).

## The spec delta is the normative form

`specs/device-identity-surfaces/spec.md` carries this as `DIS-REQ-001..005` with scenarios; `design.md` carries the reasoning and the file:line scope statements. The section below is the same rule written for a plugin reference, where "must" replaces requirement IDs.

## Proposed rule (add under the plugin's frontend/design discipline references)

> ### Device-scoped identity surfaces
>
> A client that can see more than one device must not present an account-wide sign-out control without saying which device it acts on.
>
> - **One readout, one action.** A device list rendered by two surfaces (desktop overlay and mobile page) must share one readout and one action implementation. Two lists built from two fetches will disagree the first time one is updated.
> - **Device-scoped, not account-scoped.** A row's action carries that row's own device id and nothing else. Acting on one device must never terminate another's session; assert this by request scoping in a browser test, not by reading the code.
> - **Read-only identity names the editing place.** If identity fields are read-only because the account service offers no write endpoint, say where editing actually happens. Do not replace that sentence with an in-app control that cannot write, and do not re-add an external navigation that pretends to be the edit path.
> - **Status is three-valued.** A device is online, offline, or unknown. "Unknown" must render as unknown — never as offline, never as a guess.
> - **A capability that does not exist answers truthfully.** A request for an unsupported device-scoped action returns an explicit failure code; a fabricated `200` that the UI reports as success is the failure mode this rule exists to prevent.
>
> Console sessions and device credentials are different domains: a browser console session may be transparent to which device it reaches while credentials and sign-out stay device-scoped. Say which one a change touches.

## Why it belongs in the plugin

The plugin carries reusable engineering rules, not feature specifications (its own SKILL.md discipline). This rule is reusable: any Stand surface that lists devices — contacts, project device pickers, agent device selection — hits the same three traps (two readouts, guessed status, fabricated success). It is not specific to the personal profile.
