package nebflow.neblink

import cats.effect.IO
import nebflow.agent.SendConfirm
import nebflow.core.tools.ToolContext
import nebflow.core.{FriendRosterPort, SendConfirmPort}

/**
 * Test-side self-sufficiency kit for the two static port registrars consumed by the
 * friend-face send path (`FriendMessageTool.resolveFriend` -> `FriendRosterPort`,
 * `FriendMessageTool.sendTo` / `sendToGroup` -> `SendConfirmPort`).
 *
 * Why this file exists (111.8): both registrars are installed ONLY on the production
 * boot path -- `FriendRosterPort.install(FriendRoster)` at `NeblinkWiring.scala:31`
 * (plus the `FriendRoster` object-init self-registration at `FriendRoster.scala:36`)
 * and `SendConfirmPort.install(...)` at `SharedResources.scala:391`. An uninstalled
 * port fails CLOSED by design (`FriendRosterPort.scala:43` /
 * `AgentRuntimePort.scala:252` throw `IllegalStateException`).
 *
 * The wide 814-example scan happened to construct `SharedResources` (or load
 * `NeblinkWiring`) in some earlier spec, so the install landed as a side effect and
 * both specs went green. In a narrow or regrouped batch no such spec runs first, so
 * the registrars are absent and the friend-face specs go red -- i.e. the red was a
 * test-order coupling, not a production defect.
 *
 * The fix is therefore test-side self-sufficiency: any friend-face suite calls
 * `install()` in `beforeAll`, so it no longer depends on another spec's side effect
 * and no `src/main` wiring change is needed.
 *
 * Idempotent and order-independent: repeated installs are no-ops, and a later real
 * `SharedResources` construction simply re-installs the identical production faces.
 */
object RosterFaceSpecKit:

  @volatile private var installed = false

  /**
   * Install both faces if they are not installed yet. Safe to call from every suite
   * (`beforeAll`) and from several suites in the same JVM -- suites run sequentially
   * (`Test / parallelExecution := false`). The guard is only an optimization: both
   * installs are idempotent and semantically identical to the production ones.
   */
  def install(): Unit = this.synchronized {
    if !installed then
      // Same registration as the production boot path: the real FriendRoster face
      // (pure functions, zero IO, zero side effects) via the object-init-era
      // precedent `ListFriendsToolRegistrationSpec.scala:219`.
      FriendRosterPort.install(FriendRoster)
      // Verbatim mirror of `SharedResources.scala:391-395` (the `locally` +
      // `targetFor` merged image); a test-side stub would be a second
      // implementation of the confirmation chain, so mirror the production face.
      SendConfirmPort.install(
        new SendConfirmPort.Face:
          def locally[A](ctx: ToolContext, recipientLabel: String)(io: IO[A]): IO[A] =
            SendConfirm.locally(SendConfirm.targetFor(ctx, recipientLabel))(io)
      )
      installed = true
  }

end RosterFaceSpecKit
