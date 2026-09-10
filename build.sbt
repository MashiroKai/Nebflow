import Dependencies._

ThisBuild / scalaVersion := "3.5.2"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val root = (project in file("."))
  .settings(
    // L2 rebrand: name/organization (hence the assembly jar name) derive
    // from repo-root brand.conf via project/Branding.scala — the only edit
    // point for a rename. Current values are byte-identical to the previous
    // literals ("nebflow"): zero build behavior change.
    name := BrandingBuild.lowerName,
    version := IO.read(file("VERSION")).trim,
    organization := BrandingBuild.lowerName,
    // Include VERSION file in JAR so runtime version detection works from any directory
    Compile / unmanagedResources += baseDirectory.value / "VERSION",
    // L1 rebrand: repo-root brand.conf is the ONLY edit point for brand values;
    // package it into the JAR so runtime Branding reads it from the classpath.
    // No second copy under src/main/resources — single source, no drift.
    Compile / unmanagedResources += baseDirectory.value / "brand.conf",
    // P1 web bundle: mount build/ (containing web-dist/) as a resource dir ONLY
    // when explicitly requested via -D<lowerName>.webdist=1 (CI: sbt
    // -D${LOWER_NAME}.webdist=1 assembly). The legacy hardcoded -Dnebflow.webdist
    // stays accepted (dual read, L3 parity). A dev machine that once ran
    // scripts/build-web.mjs must not have `sbt run` silently serve the stale
    // dist tree instead of the live sources.
    Compile / unmanagedResourceDirectories ++= {
      val d = baseDirectory.value / "build"
      val prop = s"${BrandingBuild.lowerName}.webdist"
      if (sys.props.contains(prop) && (d / "web-dist" / "index.html").exists()) Seq(d)
      else Seq.empty
    },
    scalaVersion := "3.5.2",

    libraryDependencies ++= Seq(
      // CLI
      scopt,
      // Terminal
      jline3Terminal,
      jline3Reader,
      // HTTP client
      sttpCore,
      sttpFs2Backend,
      // HTTP server (gateway)
      http4sEmberServer,
      http4sDsl,
      http4sCirce,
      // Streaming
      fs2Core,
      // Effect
      catsEffect,
      // JSON
      circeCore,
      circeParser,
      circeGeneric,
      circeYaml,
      // Process / File
      osLib,
      // Logging
      logbackClassic,
      logbackCore,
      // Browser automation (optional — not bundled in distribution, detected at runtime)
      playwright % "provided",
      // Diff
      diffUtils,
      // Testing
      munit,
      munitCatsEffect,
      catsEffectTestkit,
    ),

    // Compiler options
    scalacOptions ++= Seq(
      // ── 两个常量，不是一个数（2026-09-10 收尾补正）─────────────────────
      // 运行时门槛 = 21（README / CONTRIBUTING / Dockerfile / 两份安装器 /
      // doctor / GatewayMain 启动闸 —— 全部由 scripts/check-jdk-baseline.sh 钉）。
      // 编译期字节码目标 = 17（就是下面这一行）。两者刻意**不相等**，且目标
      // 必须严格低于门槛（门禁 A-7a 钉死这个不等式）。
      //
      // 为什么目标要低一档：
      //   · 产物必须在门槛以下那一档 JVM 上**能加载**。否则
      //     src/main/scala/nebflow/core/JvmRequirement.scala 的人话报错
      //     （"requires Java 21 or newer" + 平台升级指引 + Jar:/Java: 诊断）
      //     根本跑不到：17 容器里连 nebflow.Main 都加载不了，JVM 直接抛
      //     UnsupportedClassVersionError（实测 class file version 65 > 61）。
      //   · `nebflow update | doctor | version` 是自修入口，必须在"待升级的
      //     那台机器"上可运行（立项约束：闸放 GatewayMain.run 首行，不放
      //     Main.run）。字节码 65 会把它们一起焊死 —— 用户只剩卸载重装。
      //
      // 为什么这不是"把 API 面放开"：
      //   · `-release` 同时钉 API 面与字节码目标；17 的 API 面是 21 的**子集**，
      //     API 漂移从机制上更不可能，不是更可能。
      //   · Scala 3.5.2 无法把"API 面 21"与"输出字节码 17"拆开：-release 与
      //     -java-output-version 是同一个设置，同时给会直接
      //     "Flag -java-output-version set repeatedly"；单独给
      //     -java-output-version:17 同样把 API 面降到 17（两者均实测）。
      //   · 运行时门槛 21 由启动闸 + doctor + 安装器 + Dockerfile + 本门禁
      //     共同承担，**不靠字节码目标承担**。
      //
      // 因此 A-5 断言的是字节码目标 == 17（不是 21），A-7a 断言 17 < 21，
      // A-7b 断言 jar 内 Main.class major == 61。把这里改回 "-release:21"
      // 会让 17 侧的人话报错重新不可达 —— 那是回退，不是修复。
      // 详见 CONTRIBUTING.md「Prerequisites」与 scripts/check-jdk-baseline.sh 文件头。
      "-release:17",
      "-encoding", "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-Xfatal-warnings",
    ),

    Compile / run / mainClass := Some("nebflow.Main"),
    run / fork := true,
    run / connectInput := true,

    // Tests share PathUtil global state — sequential execution prevents interference
    Test / parallelExecution := false,

    // 阶段 2c（MemoryEditToolSpec 引入）：MemoryStore 的 MtimeCache val 在首次
    // 触碰时把当时的 dataRoot 路径钉进缓存对象——串行化挡不住「先跑的 suite 已
    // 在默认 dataRoot 下初始化 MemoryStore」的跨 suite 污染。MemoryEditToolSpec
    // 全程依赖 dataRoot 重定向 → 独占 forked JVM（组内唯一 suite，初始化顺序
    // 可控）；其余 suite 维持原 in-process 单组不变。
    Test / testGrouping := {
      val tests = (Test / definedTests).value
      val isolated = tests.filter(_.name == "nebflow.core.tools.MemoryEditToolSpec")
      val rest = tests.filterNot(_.name == "nebflow.core.tools.MemoryEditToolSpec")
      Seq(
        Tests.Group("in-process-suites", rest, Tests.InProcess),
        Tests.Group("memoryedit-isolated", isolated, Tests.SubProcess(ForkOptions()))
      )
    },

    // Java options
    javaOptions ++= Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "-XX:+UseZGC",
      "-XX:+ZGenerational",
      "-Xms512m",
      "-Xmx2g",
      "-XX:+UseStringDeduplication",
      "-XX:+AlwaysPreTouch",
    ),

    // Assembly settings (fat JAR fallback)
    assembly / assemblyMergeStrategy := {
      case x if x.endsWith("module-info.class") => MergeStrategy.discard
      // Seed resources pass through untouched: sbt-assembly's default strategy
      // renames license/readme files at ANY depth (even the project's own
      // resources), which corrupted the cold-start seed mirror — e.g. seed
      // plugins/slideblocks/skills/slideblocks/LICENSE shipped as
      // "LICENSE_<assemblyJarName>", breaking seed→plugin byte fidelity and
      // diverging the jar-seeded digest from the repo seed. seed/ has exactly
      // one source jar, so `first` is the faithful pass-through.
      case x if x.startsWith("seed/") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    assembly / mainClass := Some("nebflow.Main"),
  )
  .enablePlugins(AssemblyPlugin)
