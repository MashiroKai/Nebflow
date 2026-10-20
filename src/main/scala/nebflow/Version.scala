package nebflow

import nebflow.shared.Branding

object Version:

  val string: String =
    // Try classpath first (VERSION is bundled in JAR), then filesystem fallback
    try
      Option(getClass.getClassLoader.getResourceAsStream("VERSION")) match
        case Some(is) =>
          try
            val content = scala.io.Source.fromInputStream(is, "UTF-8").mkString.trim
            if content.nonEmpty then content else sys.props.getOrElse("nebflow.version", "dev")
          finally is.close()
        case None =>
          val v = java.nio.file.Files.readString(java.nio.file.Paths.get("VERSION")).trim
          if v.nonEmpty then v else "dev"
    catch
      case _: Exception =>
        sys.props.getOrElse("nebflow.version", "dev")

  /**
   * 构建身份（统一更新系统 M3，DESIGN §5 `build` 字段）：构建期的 git short sha，
   * 由 build.sbt 的 BUILD_SHA 资源生成器打进 JAR。读取链与 [[string]] 同构：
   * classpath BUILD_SHA → 系统属性 `nebflow.buildSha` → `"dev"`（tarball/无 git
   * 构建回退）。**展示与鉴别用，不参与 hasUpdate 判定**（开放问题 #2 采默认：
   * VERSION 逐发布 bump + build 仅展示）。
   */
  lazy val build: String =
    val fromProps = sys.props.getOrElse("nebflow.buildSha", "dev")
    try
      Option(getClass.getClassLoader.getResourceAsStream("BUILD_SHA")) match
        case Some(is) =>
          try
            val content = scala.io.Source.fromInputStream(is, "UTF-8").mkString.trim
            if content.nonEmpty then content else fromProps
          finally is.close()
        case None => fromProps
    catch case _: Exception => fromProps

  /**
   * 更新通道自报（统一更新系统 M3，心跳 `channel` 字段；link-server M2 收侧已就位，
   * 字段值域 = `stable` / `beta`）。判定链：
   *   1. 安装器落盘记录 `<home>/<homeDirName>/install-manifest.json` 的 `channel`
   *      字段（`release/install.sh` write_manifest 唯一写入点——装机时的权威通道）；
   *   2. 记录不可读 ⇒ 版本串启发（含 `-beta` ⇒ beta——beta 线版本号恒带该尾）；
   *   3. 兜底 `stable`。
   * 惰性求值一次（心跳每拍读盘不可取；文件在进程寿命内不变——换通道 = 重装 = 新进程）。
   */
  lazy val channel: String =
    val fromManifest =
      try
        val path =
          java.nio.file.Paths.get(
            sys.props.getOrElse("user.home", ""),
            Branding.homeDirName,
            "install-manifest.json"
          )
        if java.nio.file.Files.exists(path) then
          // 固定格式小 JSON（install.sh write_manifest 生成）——正则单字段提取，
          // 不为此引入 JSON 解析依赖。
          val text = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
          "\"channel\"\\s*:\\s*\"([^\"]+)\"".r
            .findFirstMatchIn(text)
            .map(_.group(1).trim)
            .filter(_.nonEmpty)
        else None
      catch case _: Exception => None
    fromManifest.getOrElse(if string.contains("-beta") then "beta" else "stable")
end Version
