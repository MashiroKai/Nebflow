# Open a registered project in a host application

## Intent

Replace the project workspace button's direct built-in opening with an accessible destination menu. Built-in opening remains available, including for remote-device projects. The mobile button remains hidden.

## Boundaries and acceptance

- Discover installed host applications; unavailable applications are omitted, and unsupported hosts retain the built-in option.
- The authenticated launch endpoint accepts a project identity and destination, validates the requested real absolute path against that local registered project's real workspace, and invokes an argument array without a shell.
- Dismiss the menu on outside click or Escape, support keyboard navigation, and keep it within the viewport.
- Verify actual host discovery, launch, negative path validation and nearby frontend and server regression tests.
