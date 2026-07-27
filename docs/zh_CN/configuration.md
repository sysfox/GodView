# 配置

[← 文档首页](README.md)

所有配置都在 `plugins/GodView/config.yml` 中。编辑后执行 `/godview reload`。标记为 **[重启]** 的配置项只在启动时读取一次，需要重启服务器。

GodView 永远不会拒绝配置文件。超出范围的数值会被裁剪，错误的正则表达式会被丢弃，未知的值会回退到默认值——每种情况都会记录一行日志说明发生了什么。一个告警规则中的拼写错误不应阻止面板启动。

- [locale](#locale)
- [web](#web)——绑定、TLS、代理
- [security](#security)——会话、锁定、白名单、命令过滤
- [performance](#performance)——采样、卡顿捕获、自动降级
- [storage](#storage)——后端和保留期
- [capture](#capture)——记录哪些事件
- [map](#map)
- [integrations](#integrations)
- [alerts](#alerts)——规则参考详见[告警](alerts.md)

## locale

```yaml
locale: zh_CN
```

游戏内命令输出的语言。`zh_CN` 或 `en_US`；其他值回退到英文。面板语言跟随浏览器设置。

## web

```yaml
web:
  enabled: true
  bind: "0.0.0.0"        # [重启]
  port: 8899             # [重启]
  threads: 3             # [重启]
  max-body-kb: 256
  gzip: true
  behind-proxy: false
  trusted-proxies:
    - "127.0.0.1"
    - "::1"
  tls:
    enabled: false       # [重启]
    keystore: "keystore.p12"
    password: ""
```

| 配置项 | 说明 |
|---|---|
| `enabled` | 关闭后指标、告警和命令仍会运行，但没有面板 |
| `bind` | 在反向代理后面时设为 `127.0.0.1`。详见[保护面板安全](installation.md#保护面板安全) |
| `threads` | 2 个线程真的够了。流式响应不会占用工作线程——SSE 处理器写入头部后将连接交给一个共享线程，因此一百个打开的面板仍然只消耗 `threads` 个工作线程 |
| `max-body-kb` | 请求体上限。在读取时检查，而不是依赖 `Content-Length`，因为该请求头可以是客户端发送的任何值 |
| `gzip` | 压缩超过 512 字节的文本响应。PNG 瓦片从不压缩——它们已经被压缩了 |
| `behind-proxy` | **仅在 `trusted-proxies` 已设置的情况下开启。** 详见下文 |
| `tls.*` | 内置 HTTPS。反向代理通常是更好的选择 |

### 关于 `behind-proxy`

`X-Forwarded-For` 是一个请求头，意味着客户端可以写入它。GodView 仅在 `behind-proxy` 开启**且**连接来自 `trusted-proxies` 中的地址时才查阅它——然后取最右边不属于受信代理的条目，而不是最左边的。

最后这个细节很重要。客户端发送自己的 `X-Forwarded-For` 后，代理会**追加**到其后，所以最左边的值是攻击者可控的。取最左边的值会让任何人伪造地址绕过登录频率限制和 IP 白名单。

## security

```yaml
security:
  session-timeout-minutes: 120
  session-sliding-renew: true
  max-sessions-per-user: 5

  login:
    max-attempts: 5
    window-seconds: 300
    lockout-seconds: 300

  ip-allowlist: []

  command-blacklist:
    - "(?i)^\\s*/?(stop|restart)\\b"
  command-whitelist: []

  require-confirm: true
  audit: true
```

| 配置项 | 说明 |
|---|---|
| `session-timeout-minutes` | 启用 `sliding-renew` 后，活跃的会话在事故处理过程中不会过期 |
| `max-sessions-per-user` | 超过上限的登录会结束最近最少使用的会话，所以新设备会挤掉上周忘关的笔记本 |
| `login.*` | 密码使用 PBKDF2，210,000 次迭代——每次尝试约 0.1 秒。这保护了密码，但也可能成为针对你服务器的拒绝服务攻击向量，这正是频率限制存在的意义 |
| `ip-allowlist` | 纯地址和 CIDR 格式，支持 v4 和 v6。空列表表示不限制 |
| `command-blacklist` | Java 正则表达式，使用 `find()` 匹配。**黑名单优先于白名单** |
| `command-whitelist` | 空列表表示"除了黑名单的都可以"。非空表示"仅允许这些" |
| `require-confirm` | 封禁、解封和控制台命令需要在请求中包含 `"confirm": true`。由服务端强制执行，而不是面板的对话框——只存在于 JavaScript 中的确认对话框保护不了拿着 curl 的人 |
| `audit` | 记录每次状态变更，无论成功还是拒绝。一次被拒绝的授予 operator 尝试正是调查所需要的关键线索 |

## performance

```yaml
performance:
  profile: auto
  idle-suspend: true
  snapshot-interval-ticks: 20
  tick-sampler: true
  spike-threshold-ms: 120
  spike-stack-capture: true
  spike-max-per-minute: 6
  gc-monitor: true
  world-stats-interval-ticks: 100
  degrade:
    tps-below: 17.0
    restore-tps-above: 19.0
```

### profile

`auto`、`low`、`balanced` 或 `full`。

性能档位**缩放**你设置的数值而非替换它们——此文件中的值是 `balanced` 基准线，因此 `snapshot-interval-ticks: 20` 在 `full` 档下变为 10，在 `low` 档下变为 40。任何自行调整过数值的人都会保留他们的比例。

| | 间隔 | 每 tick 预算 | 方块实体统计 |
|---|---|---|---|
| `full` | 减半 | 翻倍 | 是 |
| `balanced` | 按配置 | 按配置 | 是 |
| `low` | 翻倍 | 减半 | 跳过 |

在 `auto` 模式下，GodView 从 `full` 档开始，在连续 **10** 秒低于 `degrade.tps-below` 后降一档，在连续 **60** 秒高于 `restore-tps-above` 后升一档。快速降级、缓慢恢复，加上两个阈值之间的间隔，可以防止徘徊在边界上的服务器每隔几秒就在档位之间来回切换。

注意 `low` 档**不会**禁用以下功能：tick 采样和栈捕获。服务器吃力的时候恰恰是你需要知道哪个插件在占用 tick 的时候，所以捕获**频率**减半而不是完全关闭。

### idle-suspend

没有人在看面板时，开销较大的采集工作会停止——世界实体数量、地图瓦片、快照推送。低成本的采样（TPS、内存、tick 采样器）继续运行，因为告警规则依赖这些数据，而且它们的开销几乎为零。

强烈建议在小型服务器上开启。"没有人查看"的检测受限于流的心跳，因此在关闭的浏览器标签页上延迟最多约二十秒。

### 卡顿检测

| 配置项 | 说明 |
|---|---|
| `tick-sampler` | 每 tick 开销低于 0.01 ms。关闭后将完全移除卡顿检测功能 |
| `spike-threshold-ms` | 超过此值的 tick 周期被视为卡顿。正常值为 50 ms，因此 120 意味着 70 ms 的超时 |
| `spike-stack-capture` | **让 GodView 值得运行的核心功能。** 看门狗线程在 tick 仍然卡住时捕获主线程的栈，让你看到罪魁祸首而非事后结果 |
| `spike-max-per-minute` | 捕获栈并非免费且需要安全点。此配置限制了捕获速率 |

超过上限的卡顿仍会被记录——包含持续时间和 tick 编号，只是没有栈信息。

### world-stats-interval-ticks

按世界统计的实体数量、区块数量和热点区域。遍历是 `O(实体)` 量级的，因此它使用独立的慢时钟运行，遵循 `idle-suspend`，并在 `low` 档下跳过方块实体部分。100 ticks 是五秒；在大型服务器上不要远低于此值。

## storage

```yaml
storage:
  type: sqlite
  file: "godview.db"
  batch-size: 200
  flush-interval-ms: 2000
  retention:
    events-days: 14
    metrics-days: 30
    spikes-days: 30
    audit-days: 90
    alerts-days: 30
```

`sqlite` 持久化到 `plugins/GodView/godview.db`；`memory` 保留一个有界窗口，重启后数据丢失。

`sqlite` 是一种偏好，而非必需。如果驱动没有下载或者目录不可写，GodView 会回退到内存模式并仅提示一次——因为图表无法持久化就拒绝启动，对一个监控插件来说是个糟糕的取舍。

写入永远不会阻塞服务器。数据行进入一个有界队列，由单个线程批量处理；如果磁盘跟不上，数据行会被**丢弃并计数**，而不是拖慢 tick。计数在面板的诊断视图中可见，因此这个取舍永远不会悄无声息。

保留期清理每小时执行一次。指标行每十秒写入一次——一个月大约二十五万行，而任何从中绘制的图表都无法区分每秒写入一次的区别。

## capture

```yaml
capture:
  join-quit: true
  chat: true
  commands: true
  deaths: true
  kicks-bans: true
  teleports: false
  world-load: true
  console-errors: true
  block-place: false
  block-break: false
  container-open: false
  console-buffer-lines: 400
```

禁用的事件**根本不会注册处理器**——而不是注册一个提前返回的处理器。该标记在注册监听器时检查一次。

这对方块事件来说最为重要。在繁忙的服务器上它们每秒会触发数千次，这就是为什么它们默认关闭。如果你需要方块历史记录，请安装 CoreProtect：GodView 会查询它，而不是保留第二个更糟糕的副本。详见[集成](integrations.md#coreprotect)。

`console-buffer-lines` 决定面板在内存中保留多少行控制台输出。设为 0 可完全禁用控制台捕获。

## map

```yaml
map:
  enabled: true
  chunks-per-tick: 4
  load-unloaded: true
  unloaded-per-second: 8
  persist: true
  persist-days: 7
  tile-cache-mb: 24
  tile-ttl-seconds: 300
  chunks-per-tile: 8
  disabled-worlds: []
```

`chunks-per-tick` 是最重要的参数——它是地图对 tick 循环的全部开销。在低端服务器上将其设为 1–2。详情和理由请见[地图](map.md)。

`load-unloaded` 和 `persist` 决定地图是覆盖你的整个世界还是仅覆盖当前加载的少数区块。两者自 1.1.0 起默认开启；`persist` 将渲染好的瓦片写入 `plugins/GodView/map/`，因此覆盖范围在重启后仍然保留；`load-unloaded` 异步拉取缺失的区块，受速率限制，且从不在 tick 循环上执行。

`chunks-per-tile` 必须是 1 到 32 之间的 2 的幂，因为瓦片坐标是通过位移区块坐标得出的。

## integrations

```yaml
integrations:
  luckperms: true
  coreprotect: true
  placeholderapi: true
  vault: true
  spark: true
  essentials: true
```

每个标记是*查询*权限，而非必需。关闭某个集成意味着 GodView 永远不会构造该集成，因此完全不会接触其他插件的 API 类。详见[集成](integrations.md)。

## alerts

`alerts:` 部分有独立的页面：**[告警](alerts.md)**。它涵盖所有规则类型、持续时间和冷却的交互方式、全部六种推送渠道以及静默时段。

有一点值得在此说明：静默时段会静默 `info` 和 `warn` 级别的告警，但**从不静默 `critical`**。设置静默时段的人是想在聊天过滤器误报时睡个安稳觉，而不是在服务器宕机时也被静默。

---

下一步：[命令](commands.md) · [告警](alerts.md) · [架构](architecture.md)
