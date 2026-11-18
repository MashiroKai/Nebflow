# Plugin doc delta — device-scoped identity surface (ready to land in `stand-dev`)

**Status**: NOT LANDED. The `stand-dev` plugin source lives at `/Users/kaiyu/.stand/plugins/stand-dev/` (outside this repository and outside this task's worktree/branch). The dispatch brief asked for "appropriate plugin documentation"; writing into that path would be a second repository outside the assigned seat, so this file carries the delta verbatim for the plugin owner to land, instead of an unowned write.

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
