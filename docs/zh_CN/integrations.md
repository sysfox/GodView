# 集成

[← 文档首页](README.md)

GodView 在以下六个插件存在时读取它们的信息。每个插件都是双重可选的：插件可能没有安装，你也可以在 [`integrations:`](configuration.md#integrations) 中将其关闭。

两种情况都不会导致错误。没有 LuckPerms 的服务器会得到空白的权限面板，而不是页面崩溃——每个端点返回 `available: false` 而非 404，因此面板无论是哪种情况都渲染相同的视图。

关闭某个标记意味着 GodView 永远不会构造该集成，因此完全不会接触其他插件的 API 类。

诊断视图列出哪些集成已连接，以及未连接的原因。

## LuckPerms

**读取：** 主用户组、前缀和后缀、用户组成员、权限节点，以及每个已加载的用户组及其权重。

**写入：无。** 这是刻意的。输错一个节点就可能授予了管理员权限，而 LuckPerms 已经有设计良好的界面来管理权限。GodView 展示权限是什么，并指引你去 LuckPerms 进行修改；一个监控面板不应该成为第二个更糟糕的权限编辑器。

仅查询 LuckPerms 已在内存中保存的用户——加载离线用户需要一次数据库往返，而面板刷新不应成为每个玩家一次查询。未加载的玩家显示为"未加载"而非"无用户组"。

出现在：面板的玩家详情，以及 `GET /api/permissions/groups`。

## CoreProtect

**读取：** 某点周围的方块历史。

GodView 刻意不保留自己的方块历史——这正是 `capture.block-place` 和 `block-break` 默认关闭的原因。这些事件每秒触发数千次而已，而专门为此构建的插件已经做得更好。安装了 CoreProtect 后，GodView 会查询它，而不是保留第二个更糟糕的副本。

**不回滚操作。** 回滚会重写世界，难以撤销，并且从网页表单中弄错半径是一个非常昂贵的错误。CoreProtect 自身的命令有用于此的确认流程。

查询在 HTTP 工作线程上运行，绝不会在主线程上——CoreProtect 的 API 要求如此，而在 tick 循环上执行数据库查询正是 GodView 存在的目的。半径上限为 50，时间窗口上限为 30 天。

出现在：`GET /api/blocks?world=&x=&y=&z=&radius=&seconds=`。

需要 CoreProtect API 版本 9 或更新版本，并在其自己的配置中启用 API。

## Vault

**读取：** 玩家余额，使用经济插件的自身格式格式化，以及货币名称和提供该功能的插件名称。

安装了 Vault 并不等同于存在经济系统——许多服务器仅用 Vault 作为权限桥接。"可用"意味着有经济提供者已注册。

仅读取。从监控面板编辑余额会引发未经记录、未经审查的变更，而经济插件本身已经有审计过的命令用于此目的。

出现在：面板的玩家详情，以及 `GET /api/players/{name}/plugins`。

## PlaceholderAPI

**解析**占位符表达式，因此面板可以显示 GodView 不知道的值——公会名称、其他插件的排名、自定义统计数据。

```bash
curl -b jar.txt -X POST http://localhost:8899/api/placeholders \
  -H 'Content-Type: application/json' -H 'X-GodView-CSRF: ...' \
  -d '{"player":"Steve","placeholders":["%vault_eco_balance%","%player_ping%"]}'
```

扩展是可能接触世界的第三方代码，因此解析会跳转到主线程并带有超时——这使其成为 API 中最昂贵的端点。**每次请求最多 16 个表达式。** 一个出错的扩展会为自身返回空值，而不会影响其他十五个。

输出会剥离格式化代码；扩展返回的是为聊天设计的文本，原始 `§a` 在浏览器中会显示为乱码。

## EssentialsX

**读取：** 昵称、禁言状态及原因、AFK、监禁、上帝模式、隐身、家园数量。

这些是解释你所看到现象的事实。一个人一小时没说话看起来像挂机，直到你发现他们被禁言；一个似乎卡在一个地方的玩家，面板显示被监禁后就一目了然了。

通过反射访问，每个 getter 都单独保护——EssentialsX 在大版本之间重命名内容，一个缺失的方法只影响该字段而非整个集成。

出现在：面板的玩家详情，以及 `GET /api/players/{name}/plugins`。

## spark

**检测并指向它**，刻意保持浅层集成。

spark 是生态系统中最好的性能分析器，GodView 不与其竞争。两者回答不同的问题：GodView 告诉你*某个* tick 耗时 800 ms 以及哪个插件的帧在最顶层；spark 告诉你数千个样本中的时间分布情况，并提供完整的调用树。

因此该集成仅报告 spark 可用并展示要运行的命令：

```
/spark profiler start --timeout 60
```

包装其性能分析器 API 会产生一个比 spark 自身输出更糟糕的版本。

其 TPS 读数仅作为交叉检查进行轮询。如果 GodView 和 spark 对 TPS 的数据不一致，说明其中一个有问题，你应该知道。

## 为什么这些集成以这样的方式构建

Vault、spark 和 EssentialsX 通过反射访问；LuckPerms、CoreProtect 和 PlaceholderAPI 是编译时依赖。

这种划分并非随意。前三者中的每一个都会为少数几个 getter 增加一个仓库和一个 jar 到构建中——这不值得。后三个发布到 Maven 仓库，且 GodView 使用了它们足够的 API 来证明编译依赖的合理性。

无论哪种方式，每次调用都被保护，每次失败意味着"此集成没有数据给你"而非在面板请求中出现异常。测试套件对此有明确断言：在没有任何插件安装的情况下，每个集成报告自己不可用、返回空数据，并且仍然能序列化为有效的 JSON。

---

下一步：[面板](panel.md) · [REST API](api.md#integrations) · [配置](configuration.md#integrations)
