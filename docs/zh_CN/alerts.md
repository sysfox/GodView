# 告警

[← 文档首页](README.md)

告警的存在是为了在正确的时间告诉正确的人一次。以下两个行为决定了一个告警系统在一周之内是被保留还是被静音。

## 规则的运作方式

```yaml
alerts:
  rules:
    - id: tps-low
      type: tps_below
      threshold: 15.0
      sustain-seconds: 30
      severity: warn
      channels: [panel, discord]
```

| 字段 | 含义 |
|---|---|
| `id` | 由你指定。出现在每条通知中的面板中 |
| `type` | 详见[规则类型](#规则类型) |
| `threshold` | 对阈值规则来说是阈值，对速率规则来说是每分钟的计数阈值 |
| `sustain-seconds` | 条件必须持续多长时间才会触发。仅限阈值规则 |
| `severity` | `info`、`warn` 或 `critical` |
| `cooldown-seconds` | 可选。未设置时回退到 `default-cooldown-seconds` |
| `pattern` | Java 正则表达式，用于匹配型规则 |
| `channels` | 推送渠道。默认为 `[panel]` |

### 持续时间

TPS 每次世界保存时都会短暂低于 15 一两个样本。就此发出告警会让人学会忽略告警，因此阈值规则只有在条件连续持续了 `sustain-seconds` 后才会触发。计数器在条件清除的那一刻重置。

### 冷却

触发后，规则会在冷却期间保持静默**即使条件仍然存在**——因为通常情况就是这样；服务器仍然有问题。关键是要只通知一次，而不是在他们已经在查看问题时每秒都在通知。

有一点值得了解：如果条件从未消失，规则会在冷却结束的那一刻重新告警，而无需再等待一个持续时间窗口。一个持续了五分钟的问题应及时提醒。而如果在冷却后期恢复了然后又复发，则仍然需要完整的持续窗口才会再次触发。

## 规则类型

### 阈值——每秒采样一次，遵循 `sustain-seconds`

| 类型 | 触发条件 |
|---|---|
| `tps_below` | 1 分钟 TPS 低于 `threshold` |
| `mspt_above` | 平均每 tick 毫秒数超过 `threshold` |
| `memory_above` | 堆内存使用率超过 `threshold` |
| `player_count_above` | 在线玩家数超过 `threshold` |
| `player_count_below` | 在线玩家数低于 `threshold` |

### 发生型——在离散事件发生时触发

| 类型 | 触发条件 | `pattern` 匹配对象 |
|---|---|---|
| `tick_spike` | tick 超时超过 `threshold` ms | — |
| `gc_pause` | GC 暂停超过 `threshold` ms | — |
| `player_join` | 有玩家加入 | 玩家名 |
| `first_join` | 有玩家首次加入 | 玩家名 |
| `player_quit` | 有玩家离开 | 玩家名 |
| `player_death` | 有玩家死亡 | 死亡消息 |
| `chat_match` | 聊天内容匹配 | 消息内容 |
| `command_match` | 执行了命令 | 完整命令 |

没有 `pattern` 时，这些规则会在每次发生时触发——在繁忙的服务器上，`player_join` 意味着你需要慷慨的冷却时间，或者指定你关心的少数玩家的模式。

### 速率——在一分钟窗口内计数

| 类型 | 触发条件 |
|---|---|
| `console_error` | 过去一分钟内 `threshold` 条控制台错误 |
| `login_failed` | 过去一分钟内 `threshold` 次面板登录失败 |

任何服务器上出现一条控制台错误都是正常的；一分钟内出现二十条则说明某个插件在死循环中。这些规则对速率而非单次发生进行告警。

## 推送渠道

```yaml
alerts:
  channels:
    panel:
      enabled: true
    discord:
      enabled: false
      webhook: ""
      mention: ""            # 例如 "<@&123456789012345678>"
    telegram:
      enabled: false
      bot-token: ""
      chat-id: ""
    webhook:
      enabled: false
      url: ""
      method: "POST"
      headers: {}
    serverchan:
      enabled: false
      key: ""
    bark:
      enabled: false
      url: ""                # https://api.day.app/<your-key>
```

| 渠道 | 说明 |
|---|---|
| `panel` | 在实时流上发布。无需网络；打开的面板会立即显示 |
| `discord` | 使用嵌入消息的 Webhook。`mention` 仅用于 `critical`——没人被 @ 的告警只是一行日志 |
| `telegram` | Bot API。故意使用纯文本：告警正文包含玩家名和聊天内容，任何标记模式都会让这些内容破坏消息 |
| `webhook` | 告警自身的 JSON，POST 到你指定的地址。支持自定义请求头 |
| `serverchan` | ServerChan 推送 |
| `bark` | iOS 推送。`critical` 告警会请求使用闹钟声音 |

推送**不会重试**，超时为八秒。一个关于五秒卡顿的告警在九十秒后才到达已被你打开面板的行动所取代。永远不会跟随重定向——开始重定向的告警 URL 要么是配置错误要么是被劫持，不值得默默追下去。

每条告警都会记录实际接受了它的渠道。面板会显示哪些渠道成功，因此"触发了但没人被告知"与"从未触发"是明显不同的。

## 静默时段

```yaml
alerts:
  quiet-hours: "23:00-07:00"
```

使用服务器本地时间。跨越午夜的时间窗口是常见情况，按写入的方式工作。

**`info` 和 `warn` 会被静默。`critical` 不会。** 设置静默时段的人是想在聊天过滤器误报时睡个安稳觉，而不是在服务器宕机时也被静默——静默所有内容会让这个设置没人敢打开。

被静默的告警仍然会被记录，因此面板可以显示夜间发生了什么。

## 测试渠道

在面板中，告警 → 在任意规则上点击**发送测试**。或者：

```
POST /api/alerts/test/{rule}
```

它会通过该规则的渠道推送一条模拟告警并绕过静默时段。需要 `admin` 权限，因为即使它不改变服务器上的任何内容，也会连接到外部服务。

## 默认规则

附带的 `config.yml` 中有七条规则，作为起点：

| 规则 | 捕获内容 |
|---|---|
| `tps-low` | TPS 低于 15 持续 30 秒 |
| `tps-critical` | TPS 低于 10 持续 15 秒 |
| `tick-spike` | 任何超过 500 ms 的 tick |
| `heap-pressure` | 堆内存超过 92% 持续一分钟 |
| `console-error-burst` | 一分钟内 20 条控制台错误 |
| `panel-bruteforce` | 一分钟内 5 次登录失败 |
| `op-granted` | `/op`、`/deop`，或 LuckPerms parent 设为 admin/owner |

最后一条值得保留。授予管理员权限是最值得立即知晓的事件，默认在每条渠道上以 `critical` 级别推送。

## 编写自定义规则

一些常见的模式：

```yaml
# 特定玩家加入
- id: owner-online
  type: player_join
  pattern: "^Notch$"
  severity: info
  channels: [panel, discord]

# 有人使用危险命令
- id: dangerous-commands
  type: command_match
  pattern: "(?i)^/?(gamemode|give|tp)\\s"
  severity: warn
  cooldown-seconds: 60
  channels: [panel]

# 服务器意外空无一人
- id: everyone-left
  type: player_count_below
  threshold: 1
  sustain-seconds: 300
  severity: info
  channels: [discord]
```

模式是 Java 正则表达式，使用 `find()` 匹配——不需要匹配整个字符串。编译失败的模式会被丢弃并显示一条包含规则名称的警告；文件的其他部分仍会正常加载。

---

下一步：[配置](configuration.md) · [面板](panel.md) · [故障排除](troubleshooting.md)
