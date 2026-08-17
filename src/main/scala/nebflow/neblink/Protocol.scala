package nebflow.neblink

/**
  * Wire-contract constants for the device-interop subsystem (L3 rebrand
  * batch 3, 2026-08-17). D2 decision: the neblink subsystem name and its
  * protocol fields STAY across the product rename — they are wire contracts
  * spoken by deployed servers and older clients, not brand strings. This
  * object only CENTRALIZES the literals that used to be scattered, so the
  * rebrand script and future audits have a single authority. Zero semantic
  * change: every value here is byte-identical to the literal it replaces.
  *
  * Do NOT derive anything in this file from Branding — that would couple a
  * frozen wire contract to a mutable brand source.
  */
object Protocol:

  /** Relay/tunnel device identity header (direct P2P and relayed requests). */
  val DeviceHeader: String = "X-Neblink-Device"

  /** Device-flow and enrollment REST paths on neblink-server (proxied by the
    * gateway; spoken directly by NeblinkClient). */
  object DeviceApi:
    val code: String = "/api/device/code"
    val token: String = "/api/device/token"
    val enroll: String = "/api/device/enroll"
    val session: String = "/api/device/session"
    val login: String = "/api/device/login"
  end DeviceApi

  /** Config field under the neblink block of the config file
    * ("neblinkServer"; legacy alias "coordinator" is handled at the decoder). */
  val neblinkServerField: String = "neblinkServer"

end Protocol
