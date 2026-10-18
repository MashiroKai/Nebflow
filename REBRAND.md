# Stand 品牌与启动

Stand 原名 Nebflow。网站入口是 https://mystand.space，跨设备控制台是 https://mystand.space/portal。启动器会携带已有的设备 ID，避免打开另一台设备。设备中继仍使用 `https://neblink.nebflow.space`，登录身份提供方仍兼容旧部署；它们是服务地址，不是浏览器入口，不能按产品名猜测新子域。

已有数据继续使用 `~/.nebflow` 和 `nebflow.json`，无需复制到 `.stand`，会话、密钥和设备身份留在原处。环境变量支持 `STAND_*`，兼容 `NEBFLOW_*`，新前缀优先。Scala 的 `nebflow.Main` 仍是兼容入口。

本机使用可设置 `STAND_OPEN_UI=local`。启动或再次打开时，启动器用现有本机凭据换取一次性浏览器会话票据，不把长期令牌放进地址栏。门户配置在 `<home>/neblink/config.json` 顶层 `portalUrl`；设为空字符串同样选择本机入口。云端模式通过网站账号登录，本机会话不能代替云端账号登录。

如果提示已有实例，说明网关已经在运行；无需再启动第二个进程。本地地址为 http://localhost:8080。旧版本需要运行 `sbt "run token"` 获取一次性票据并在登录卡片中使用。不要删除数据目录来解决登录问题。更新源码后，已运行的旧 JVM 需要由其所属会话重启才能加载新版本。

## Logo

统一真源是 `brand.conf` 的 `activeLogo` 和 `src/main/resources/web/brand/logos.json` 中归档的原图。`/brand/index.html` 只展示历史版本，不提供浏览器私有覆盖。

```sh
python3 scripts/switch-logo.py --list
python3 scripts/switch-logo.py black-cat --website <官网仓库> --auth-repo <neblink-server仓库>
python3 scripts/switch-logo.py --website <官网仓库> --auth-repo <neblink-server仓库> --check
```

一条同步命令生成客户端浅深色标志、favicon、桌面安装图标，以及官网、PWA、独立登录页的资源与清单。生成资源带内容摘要，原图和旧版本保留；各端不能再通过各自环境变量选择不同 Logo。`--check` 检测生成件漂移，不写文件。目标仓库必须显式传入，避免误改其他检出。

官网资源发布之后，用同一清单同步 Logto 品牌和邮箱验证码模板：

```sh
python3 scripts/sync-brand-auth.py --env-file <官网凭据文件> --apply
python3 scripts/sync-brand-auth.py --env-file <官网凭据文件>
```

脚本检查官网实际图片字节后才写认证配置；不打印或保存凭据，保留邮件主题、正文、占位符、SMTP 和账号配置。SignIn、Register、ForgotPassword、Generic 四种邮件中的品牌图片统一取版本化官网 URL。未来切换只需选一次原图并重新运行同一流程，不需要逐个查找页面或邮件模板。生成和上线分开：官网、登录页重建发布，客户端重建重启，桌面图标随安装包生效；不会把尚未发布的文件说成线上已更新。

## 发布与兼容边界

- 官网登录使用 `https://auth.mystand.space`，现有 Logto 应用保留旧域回调并追加新域回调；不新建账号、不更改密码。
- 数据目录继续为 `~/.nebflow`，配置继续为 `nebflow.json`。本次更名不复制或搬移已有账号、设备、密钥和会话。
- `nebflow.Main`、存储键、Scala 包名和中继协议继续兼容；更名不要求改写历史数据。
- 官网和登录页通过已授权的部署发布；源码保留在修复分支，未经明确指示不合并、不推送 Git 分支、不打标签、不改 VERSION。
- 自动检查覆盖 OIDC 初始化、回调注册、租户配置、错误密码路径和资源一致性。真实密码登录、社交身份提供方往返与邮件实际收件需要真实用户完成，不能用自动检查代替。
