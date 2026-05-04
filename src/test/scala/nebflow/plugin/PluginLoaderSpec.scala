package nebflow.plugin

import cats.effect.IO
import munit.CatsEffectSuite

class PluginLoaderSpec extends CatsEffectSuite:

  test("scan returns empty list when plugin directory does not exist") {
    val nonExistent = os.pwd / "this-dir-does-not-exist-12345"
    PluginLoader.scan(nonExistent).map { manifests =>
      assertEquals(manifests, Nil)
    }
  }

  test("scan parses valid plugin.yaml manifest") {
    val tmp = os.temp.dir()
    val pluginDir = tmp / "test-plugin"
    os.makeDir.all(pluginDir)
    os.write(pluginDir / "plugin.yaml",
      """name: test-plugin
        |version: 1.2.3
        |description: A test plugin
        |mcp:
        |  command: npx
        |  args:
        |    - -y
        |    - mcp-server-filesystem
        |frontend:
        |  scripts:
        |    - main.js
        |  styles:
        |    - style.css
        |""".stripMargin)

    PluginLoader.scan(tmp).map { manifests =>
      assertEquals(manifests.length, 1)
      val pm = manifests.head
      assertEquals(pm.name, "test-plugin")
      assertEquals(pm.version, "1.2.3")
      assertEquals(pm.description, "A test plugin")
      assert(pm.mcp.isDefined)
      assert(pm.mcp.get.command.contains("npx"))
      assert(pm.frontend.isDefined)
      assertEquals(pm.frontend.get.scripts, List("main.js"))
      assertEquals(pm.frontend.get.styles, List("style.css"))
    }.guarantee(IO.blocking(os.remove.all(tmp)))
  }

  test("scan parses valid plugin.json manifest") {
    val tmp = os.temp.dir()
    val pluginDir = tmp / "json-plugin"
    os.makeDir.all(pluginDir)
    os.write(pluginDir / "plugin.json",
      """{"name":"json-plugin","version":"0.1.0","description":"JSON manifest"}""")

    PluginLoader.scan(tmp).map { manifests =>
      assertEquals(manifests.length, 1)
      val pm = manifests.head
      assertEquals(pm.name, "json-plugin")
      assertEquals(pm.version, "0.1.0")
      assertEquals(pm.description, "JSON manifest")
    }.guarantee(IO.blocking(os.remove.all(tmp)))
  }

  test("scan skips directories without manifest") {
    val tmp = os.temp.dir()
    val emptyDir = tmp / "empty-plugin"
    os.makeDir.all(emptyDir)

    PluginLoader.scan(tmp).map { manifests =>
      assertEquals(manifests, Nil)
    }.guarantee(IO.blocking(os.remove.all(tmp)))
  }

  test("extractMcpConfigs prefixes serverId with plugin__") {
    val manifests = List(
      PluginManifest("fs", mcp = Some(PluginMcpConfig(command = Some("npx")))),
      PluginManifest("git", mcp = Some(PluginMcpConfig(command = Some("node")))),
      PluginManifest("no-mcp")
    )
    val configs = PluginLoader.extractMcpConfigs(manifests)
    assertEquals(configs.size, 2)
    assert(configs.contains("plugin__fs"))
    assert(configs.contains("plugin__git"))
    assertEquals(configs("plugin__fs").command, Some("npx"))
    assertEquals(configs("plugin__git").command, Some("node"))
  }

  test("extractFrontendConfigs collects assets correctly") {
    val manifests = List(
      PluginManifest("a", frontend = Some(FrontendConfig(scripts = List("a.js"), styles = List("a.css")))),
      PluginManifest("b", frontend = Some(FrontendConfig(scripts = List("b.js")))),
      PluginManifest("c")
    )
    val configs = PluginLoader.extractFrontendConfigs(manifests)
    assertEquals(configs.size, 2)
    assertEquals(configs("a").scripts, List("a.js"))
    assertEquals(configs("a").styles, List("a.css"))
    assertEquals(configs("b").scripts, List("b.js"))
    assertEquals(configs("b").styles, Nil)
  }
