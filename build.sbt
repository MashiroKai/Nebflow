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
      // JDK baseline is 21 (see README.md / CONTRIBUTING.md): -release pins
      // the API surface to 21 so a newer build JDK (e.g. 23) can never
      // silently reintroduce an API above the declared baseline — that drift
      // is the mechanism that put HttpClient#close (21+) on a 17 runtime.
      "-release:21",
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
