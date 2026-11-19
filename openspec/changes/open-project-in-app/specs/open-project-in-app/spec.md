## ADDED Requirements

### Requirement: [OPA-REQ-001] Opening a registered project in a host application

The project workspace button SHALL offer an accessible destination menu that keeps the built-in
opening available, including for remote-device projects. Installed host applications SHALL be
discovered and unavailable ones omitted, and unsupported hosts SHALL retain the built-in option. The
authenticated launch endpoint SHALL accept a project identity and a destination, SHALL validate the
requested real absolute path against that local registered project's real workspace, and SHALL
invoke an argument array without a shell.

#### Scenario: [OPA-SCN-001] Menu and launch

- **WHEN** the destination menu is opened and an installed application is chosen
- **THEN** the menu stays inside the viewport, Escape and an outside click dismiss it and restore focus, and the launch goes through the validated endpoint

#### Scenario: [OPA-SCN-002] Rejected path

- **WHEN** the launch endpoint is asked for a path that is not the registered project's real workspace
- **THEN** the request is refused and nothing is launched
