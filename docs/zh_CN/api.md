# REST API

[← 文档首页](README.md)

面板是该 API 的客户端，且不使用任何私有接口。控制台能做的事情，脚本也能做。

## 认证

登录，保持 cookie，在写操作时回传 CSRF token。

```bash
# 登录。Content-Type 是必需的，不是可选的。
curl -c jar.txt -X POST http://localhost:8899/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"user":"admin","password":"..."}'
```

```json
{
  "user": "admin",
  "role": "admin",
  "csrf": "kR7f...",
  "expiresAt": 1730000000000,
  "permissions": ["view", "player_action", "command", "world_action",
                  "manage_users", "manage_config"]
}
```

```bash
# 读取：仅需 cookie。
curl -b jar.txt http://localhost:8899/api/overview

# 写入：cookie 加上 CSRF 请求头。
curl -b jar.txt -X POST http://localhost:8899/api/players/Steve/kick \
  -H 'Content-Type: application/json' \
  -H 'X-GodView-CSRF: kR7f...' \
  -d '{"reason":"spam","confirm":true}'
```

服务端强制执行的三个规则：

- **登录时 `Content-Type: application/json` 是必需的。** 浏览器会对跨域 JSON POST 请求发送预检请求，而 GodView 不回应预检请求，因此恶意页面无法将访客登录到攻击者控制的账户。
- **每个非 GET 请求都需要 `X-GodView-CSRF`。** 会话 cookie 是 `SameSite=Strict`，而该请求头是第二道独立屏障。
- **破坏性操作需要 `"confirm": true`**（当 [`security.require-confirm`](configuration.md#security) 开启时）。在此处强制执行，而非在面板的对话框中。

## 错误

所有错误响应都是 JSON，包含稳定的 `error` 代码和人类可读的 `message`。

```json
{ "error": "server_busy", "message": "the server's main thread did not respond within 3000ms" }
```

| 状态码 | 含义 |
|---|---|
| 400 | 请求格式错误 |
| 401 | 无会话或会话已过期 |
| 403 | 角色缺少权限、CSRF 验证失败或地址不在白名单中 |
| 404 | 端点、玩家或世界不存在 |
| 405 | 方法错误——`Allow` 请求头列出了正确的方法 |
| 409 | 需要确认 |
| 413 | 请求体超过 `web.max-body-kb` |
| 415 | 未使用 JSON 内容类型登录 |
| 429 | 登录锁定——`Retry-After` 指示剩余时间 |
| 503 | 主线程未响应，或依赖服务不可用 |

操作端点的 503 表示服务器没有在 tick。这是一个真实的回答，而且是故意的：面板在服务器最吃力的时候最有用，因此冻结的 tick 循环必须产生清晰的消息，而非挂起的请求。

## 公共端点

无需会话。以下是全部四个未认证的接口。

| | |
|---|---|
| `GET /api/health` | `{"ok":true}`。用于在线监控 |
| `GET /api/auth/state` | 是否存在账号以及你是否已登录 |
| `POST /api/auth/login` | 登录 |
| `GET /api/auth/link/{token}` | 兑换来自 `/godview link` 的一次性链接；303 重定向到面板 |

## 会话

| | 权限 | |
|---|---|---|
| `GET /api/auth/me` | view | 当前会话、角色、权限、CSRF token |
| `POST /api/auth/logout` | view | 结束当前会话 |
| `GET /api/auth/sessions` | view | 你的会话；有 `manage_users` 权限则返回全部 |
| `POST /api/auth/sessions/revoke` | manage_users | `{"user":"..."}`，或省略以结束所有会话 |
| `POST /api/auth/password` | view | `{"currentPassword","newPassword"}`。结束该账号的所有会话 |

## 读取

以下端点都不会接触 Bukkit API。所有内容都从指标采样器在主线程上填充的缓存中提供，因此即使频繁轮询也不会对 tick 循环造成负载。

| | 权限 | |
|---|---|---|
| `GET /api/overview` | view | 仪表板文档：服务器、采样、世界、玩家、性能、存储和 200 个 tick 持续时间 |
| `GET /api/pulse` | view | 紧凑的每秒一帧的数据 |
| `GET /api/series?names=&window=` | view | 内存中的图表数据。`names` 是从 `tps,mspt,heap,cpu,players,chunks,entities` 中逗号分隔选取；`window` 以毫秒为单位选择分辨率 |
| `GET /api/history?from=&to=&points=` | view | 来自存储的长期指标，服务端分桶，最多 `points` 个点 |
| `GET /api/spikes?live=&limit=&from=&to=` | view | 卡顿数据，以及按插件的统计。`live=1` 读取内存环形缓冲区 |
| `GET /api/gc` | view | GC 次数、总暂停时间、近期 GC 事件 |
| `GET /api/worlds` | view | 按世界统计、顶级实体类型、热点区块 |
| `GET /api/players` | view | 在线玩家。仅在有 `player_action` 权限时包含地址 |
| `GET /api/players/{name}` | view | 单个玩家及其近期事件 |
| `GET /api/events?...` | view | `type`、`actor`、`search`、`from`、`to`、`limit`、`offset` |
| `GET /api/console/log?limit=&level=` | view | 控制台输出。低于 WARN 级别需要 `command` 权限 |
| `GET /api/system` | view | JVM、主机、插件、GodView 自身计数器 |
| `GET /api/audit?from=&to=&limit=` | **manage_users** | 谁做了什么，包括拒绝的操作 |
| `GET /api/stream` | view | 服务端推送事件——见下文 |

所有列表端点将 `limit` 限制为 1000，图表请求限制为 2000 个点。

## 实时流

```javascript
const stream = new EventSource('/api/stream');
stream.addEventListener('pulse', e => console.log(JSON.parse(e.data)));
```

| 事件 | 发送时机 |
|---|---|
| `pulse` | 每个快照间隔。关键数字加上自上次以来的 tick 数 |
| `snapshot` | 每几秒一次。完整的 `/api/overview` 文档 |
| `event` | 服务器事件被捕获时 |
| `console` | 控制台输出被记录时 |
| `alert` | 告警触发时 |

两种帧类型的原因在于它们的开销相差两个数量级——以脉冲频率发送完整文档会每分钟向每个打开的面板推送数兆字节的数据，而其中大部分数据没有变化。

连接不会占用 HTTP 工作线程。帧按订阅者排队，由一个共享线程写入；速度太慢跟不上客户端的旧帧会被丢弃，而不会对服务器产生背压。

## 操作

以下所有操作都会跳转到主线程并带有超时，无论成功与否都会被写入审计日志。

| | 权限 | 请求体 |
|---|---|---|
| `POST /api/players/{name}/kick` | player_action | `{"reason"}` |
| `POST /api/players/{name}/ban` | player_action | `{"reason","durationMs","confirm"}`——省略 `durationMs` 表示永久 |
| `POST /api/players/{name}/unban` | player_action | `{"confirm"}` |
| `POST /api/players/{name}/message` | player_action | `{"message"}` |
| `POST /api/players/{name}/gamemode` | player_action | `{"mode"}` |
| `POST /api/players/{name}/teleport` | player_action | `{"target"}` 或 `{"world","x","y","z"}` |
| `POST /api/broadcast` | player_action | `{"message"}` |
| `POST /api/worlds/{name}/weather` | world_action | `{"type"}`——clear、rain、thunder |
| `POST /api/worlds/{name}/time` | world_action | `{"time"}`——0 到 24000 |
| `POST /api/worlds/{name}/save` | world_action | 同步；30 秒超时 |
| `POST /api/console` | command | `{"command","confirm"}` |

控制台端点在命令到达服务器之前应用 [`security.command-blacklist` 和 `command-whitelist`](configuration.md#security)，并审计结果——因此被拒绝的 `/stop` 也会有记录。

输出不会返回。它会出现在 `/api/console/log` 中，带有时间戳和审计条目。

## 集成

所有集成降级而非失败。没有 LuckPerms 的服务器返回 `available: false` 和空的数据，而非 404。

| | 权限 | |
|---|---|---|
| `GET /api/integrations` | view | 哪些集成存在，以及不存在的原�� |
| `GET /api/permissions/groups` | view | LuckPerms 用户组按权重排序 |
| `GET /api/players/{name}/plugins` | view | 一次性获取权限、余额和 EssentialsX 状态 |
| `GET /api/blocks?world=&x=&y=&z=&radius=&seconds=` | view | CoreProtect 查询。半径 ≤ 50，窗口 ≤ 30 天 |
| `POST /api/placeholders` | view | `{"placeholders":[...],"player":"..."}`。每次请求最多 16 个 |

## 地图

| | 权限 | |
|---|---|---|
| `GET /api/map/status` | view | 瓦片几何信息、缓存统计、启用的世界 |
| `GET /api/map/markers?world=` | view | 玩家和实体热点 |
| `GET /api/map/tile/{world}/{x}/{z}?scale=` | view | PNG 图片。`scale` 为 1、2 或 4 |
| `POST /api/map/invalidate/{world}` | world_action | 丢弃缓存的瓦片使其重新渲染 |

仍在渲染中的瓦片返回 **202**，附带 `Retry-After: 1` 和进度信息：

```json
{ "status": "rendering", "capturedChunks": 24, "totalChunks": 64 }
```

请再次请求。瓦片带有 ETag 和私有缓存过期时间，因此返回已经看过的区域只消耗一次 304。

## 告警

| | 权限 | |
|---|---|---|
| `GET /api/alerts?live=&limit=` | view | 历史记录，或 `live=1` 时的内存环形缓冲区 |
| `GET /api/alerts/status` | view | 每条规则的计数器和每个渠道的问题描述 |
| `POST /api/alerts/test/{rule}` | **manage_config** | 通过该规则的渠道发送测试消息 |

---

下一步：[架构](architecture.md) · [面板](panel.md) · [命令](commands.md)
