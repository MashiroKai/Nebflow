# Changelog

All notable changes to Nebflow are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); `VERSION`
is the single source of truth for the version number, so sections are added here only
when a release is cut. A release's notes are grouped by [`.github/release.yml`](.github/release.yml).

## [Unreleased]

### Added

- **Friends** — add a friend by username or email, message them from the workspace, and
  hand a turn to their agents from an orchestrated run. Requires a signed-in nebflow
  account (the same sign-in that pairs your machines over Device Interconnect); messages
  travel over Device Interconnect and are delivered on a best-effort basis, and friend
  conversations carry no attachments. Documented in the README with a panel screenshot
  and a short demo recording — quoting only the capability that is visible in this
  release, with no dates or promises for anything else.

### Changed

- The README no longer claims that desktop installers are published: releases ship a
  single self-contained JAR, and the install scripts add Java 21 and ripgrep when either
  is missing.
- Release notes for future releases are grouped by category through
  [`.github/release.yml`](.github/release.yml). The first release that carries Friends
  must name the feature explicitly in the human-written notes — see the header of that
  file for the wording contract.

### Fixed

- The README named the device subsystem by its retired internal name; the user-visible
  name is **Device Interconnect**.
