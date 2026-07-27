# 故障排除

[← 文档首页](README.md)

按症状分类整理。从 `/godview status` 开始——它在一个界面中显示 GodView 是否在采集、面板绑定到什么地址、以及存储是否正常工作。

## 无法登录

### "还没有账号"

全新安装时的预期情况。GodView 没有默认账号：

```
/godview user add admin admin
```

密码在控制台中打印一次。

### 密码丢失

```
/godview user passwd admin
```

生成新密码并结束该账号的所有会话。

### "尝试次数过多"

登录频率限制已锁定你的地址。等待 `Retry-After` 指定的时间，或在控制台中：

```
/godview unlock <your-ip>
/godview unlock all
```

### 账号或密码错误，但我确定是对的

账号名不区分大小写；密码区分。检查从控制台复制时是否有尾随空格。

如果 GodView 在反向代理后面，且你开启了 `behind-proxy` 但没有将代理加入 `trusted-proxies`，锁定会被应用在代理的地址上——这意味着一个人被锁定会影响所有人。详见[保护面板安全](installation.md#保护面板安全)。

## 面板加载不了

### 8899 端口没有反应

检查启动时的控制台输出。如果端口被占用，你会看到：

```
[GodView] GodView could not start the web panel on 0.0.0.0:8899 — the panel is
          unavailable, everything else keeps running.
```

更改 `web.port` 然后**重启**——端口不会被 `/godview reload` 重新读取。

### "API 在运行，但面板的前端文件不在这个 jar 中"

jar 是使用 `-Dgodview.panel.skip=true` 构建的，或者没有 Node 工具链。API 可以工作；控制台不行。重新启用前端构建——详见[开发](development.md)。

### 空白页面

打开浏览器控制台。GodView 在 Content-Security-Policy 下提供面板服务，该策略仅允许同源资源，因此被阻止的脚本会显示在那里。

如果你在代理后面，而代理注入脚本或重写 HTML，那就是原因。

## 面板加载了但没有显示实时数据

### 头部显示"重连中"

实时流没有连接。几乎总是由反向代理缓冲服务端推送事件引起。对于 nginx：

```nginx
proxy_buffering off;
proxy_read_timeout 3600s;
```

GodView 会发送 `X-Accel-Buffering: no`，nginx 在大多数配置中会遵守，但显式的 `proxy_buffering off` 更可靠。

### 图表为空

图表读取存储的历史数据。全新安装时还没有数据——指标行每十秒写入一次，因此等一分钟。

如果仍然为空，检查诊断 → 存储。如果 `kind` 为 `memory`，说明 SQLite 无法打开；见下文。

### 所有数据都显示为零

`/godview status` 会说明原因。如果 TPS 是 20.00、MSPT 是 0.00 且没有其他数据填充，说明指标服务没有启动。在 Folia 上它会在启动时明确说明——逐 tick 计时需要单条主线程。

## 历史数据没有保留

### 存储显示为"memory"但配置文件是 sqlite

GodView 回退了。控制台在启动时有原因：

```
GodView could not open plugins/GodView/godview.db — falling back to in-memory
history, which is lost on restart. Check that sqlite-jdbc downloaded and that
the folder is writable.
```

两个常见原因：

- **驱动没有下载。** 它在首次启动时通过 Paper 的库加载器获取，需要网络访问。离线首次启动会导致它缺失。
- **目录不可写。** 检查 `plugins/GodView/` 的所有权。

### 数据行被丢弃

诊断 → 存储显示 `queued / dropped`。丢弃应为零。非零值意味着磁盘跟不上，数据行被丢弃而非拖慢服务器——这是刻意的取舍，但意味着你在丢失历史数据。

通常是磁盘慢或有争用。提高 `storage.batch-size` 和 `flush-interval-ms` 可以通过减少写入次数并以更大的事务写入来改善。

### 事件为空

检查 [`capture:`](configuration.md#capture)。禁用的事件根本不注册处理器，因此没有任何内容被记录。方块事件默认关闭。

## 告警没有到达

### 没有任何告警触发

告警 → 规则列表显示每条规则及其触发次数以及类型是否被识别。标记为"未知类型"的规则在其 `type` 字段中有拼写错误——GodView 在启动时也会记录这个。

### 规则触发了但没有消息到达

告警 → 渠道显示每个渠道及其不可用的原因，例如 "alerts.channels.discord.webhook is empty"。

在规则上使用**发送测试**。它会绕过静默时段并通过该规则的渠道推送一条模拟告警。如果失败，控制台中有原因——通常是来自服务的 HTTP 状态码。

### 告警触发了但只有夜间没人被通知

静默时段。`info` 和 `warn` 在窗口期间被静默；`critical` 不会。被静默的告警仍然出现在面板的历史记录中，标记为未发送。

### 一条告警变成了五十条

提高规则的 `cooldown-seconds`，或设置 `sustain-seconds` 使短暂的条件不满足触发条件。详见[告警](alerts.md#规则的运作方式)。

## 地图慢或空白

### 只渲染了一小片世界区域

检查 `GET /api/map/status`：

- `loadUnloaded: false`——地图只能绘制当前加载的区块，因此远离玩家的区域都是虚空。设置 `map.load-unloaded: true`。自 1.1.0 起默认开启；如果显示 false，说明你的 `config.yml` 早于此版本。
- `persist: false`——瓦片仅在内存中存在 `tile-ttl-seconds` 时间，因此地图在永久地重建同一小块区域。设置 `map.persist: true`。
- `tilesIncomplete` 在增长——瓦片在其区块到达之前开始渲染。它们会自行重绘；如果太慢，提高 `map.unloaded-per-second`。
- `areasRefused` 在增长——你平移的速度超过了 `chunks-per-tick` 的捕获能力。面板会重试；如果服务器能承受，提高预算。

覆盖范围随着面板的使用而增长。它不是后台预渲染——详见[这不是什么](map.md#这不是什么)。

### 瓦片永远填不满

如果 `chunksSkippedUnloaded` 在增长且 `loadUnloaded` 为 true，说明从磁盘读取世界的速度慢于瓦片被请求的速度。提高 `map.unloaded-per-second`，或者在游戏中前往该区域使区块被加载。

### 地图导致服务器卡顿

将 `map.chunks-per-tick` 降低到 1。这是地图对 tick 循环的全部开销。详见[地图](map.md#开销)。

### 瓦片数据过时

它们在 `map.tile-ttl-seconds`（默认五分钟）后过期。要强制立即重绘，在地图 → 世界选择器中操作，或：

```
POST /api/map/invalidate/{world}
```

## 命令没有作用

### /godview 显示用法信息然后停止

子命令未被识别。单独执行 `/godview` 会列出你可以运行的命令——没有 `godview.admin` 时列表更短。

### "你没有权限"

`godview.admin` 拥有所有权限，`godview.link` 仅限登录链接。Operator 默认拥有两者。

### 面板中的控制台命令被拒绝

`security.command-blacklist` 阻止了它——`/stop` 和 `/restart` 默认被阻止。拒绝操作被写入审计日志。详见[配置 → security](configuration.md#security)。

### "此操作需要 confirm: true"

`security.require-confirm` 已开启。面板会自动处理；脚本需要在请求体中发送 `"confirm": true`。这是由服务端而不是面板的对话框强制执行的。

## 性能问题

### GodView 自身出现在卡顿归因中

它不应该出现——但如果出现了，帧会说明是哪个部分。通常的嫌疑对象是在拥有大量实体的服务器上的世界统计功能。提高 `performance.world-stats-interval-ticks`，或让 `profile: auto` 自动处理。

### 我希望它开销更小

按效果排序：

1. `map.chunks-per-tick: 1` 或 `map.enabled: false`
2. `performance.world-stats-interval-ticks: 200`
3. `performance.idle-suspend: true`（已经是默认值）
4. `capture.chat: false`、`capture.commands: false`（如果你不需要历史记录）
5. `storage.type: memory`（如果你不需要跨重启保留历史）

**不要**为了节省时间而关闭 `performance.tick-sampler`。它每 tick 的开销低于 0.01 ms，而且是 GodView 能告诉你什么在拖慢服务器的唯一原因。

## 仍然卡住

`/godview status` 和诊断视图几乎覆盖了所有情况。两者都可以安全地粘贴到错误报告中——它们不包含密码、会话 token 或玩家 IP 地址。

---

下一步：[配置](configuration.md) · [架构](architecture.md) · [开发](development.md)
