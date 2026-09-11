package nebflow.core.tools

import munit.FunSuite
import nebflow.gateway.WebSocketRoutes

/**
 * A14（C 批 / 票据腿）——工具侧扩展名白名单表与端点侧白名单表必须逐项相等。
 *
 * The two tables exist for two different jobs (FileRefs pre-filters which
 * references the Card/Pop tools turn into proxy URLs; NfFileAllowedExt is the
 * endpoint's own gate) and they have drifted before: the 2026-09-05 fix had to
 * delete avi/eot/wasm/obj/stl/gltf/glb from FileRefs because the endpoint no
 * longer served them. Equality is the cheap invariant that keeps them welded:
 * a future change to one table without the other fails here instead of
 * silently producing dead links (FileRefs-ahead) or 400s (endpoint-ahead).
 *
 * Lives in `nebflow.core.tools` because `FileRefs` is `private[tools]`.
 */
class FileRefsWhitelistSpec extends FunSuite:

  test("A14: FileRefs.AllowedExtensions == WebSocketRoutes.NfFileAllowedExt (no table drift)") {
    assertEquals(FileRefs.AllowedExtensions, WebSocketRoutes.NfFileAllowedExt)
  }

  test("A14: the mirrored table is non-empty and covers the Canvas companions") {
    val ext = FileRefs.AllowedExtensions
    assert(ext.nonEmpty)
    assert(Set("js", "mjs", "css", "json").subsetOf(ext))
  }
