# Design

The project workspace button opens an accessible destination menu instead of directly opening the
built-in view. `src/main/resources/web/js/projectTab.js` renders the button, the destination menu
(`.project-open-menu`) and the current-application icon, and keeps the built-in opening — including
for remote-device projects — as a menu option; the mobile button stays hidden. The authenticated
server leg is `POST /api/projects/<name>/open-in-app` in
`src/main/scala/nebflow/gateway/ProjectsRoutes.scala:103`, which validates the requested real
absolute path against that local registered project's real workspace and invokes an argument array
without a shell. Installed host applications are discovered and unavailable ones are omitted;
unsupported hosts keep the built-in option. The menu dismisses on outside click or Escape, supports
keyboard navigation (ArrowDown moves the highlight, Escape restores focus to the button) and stays
inside the viewport.
