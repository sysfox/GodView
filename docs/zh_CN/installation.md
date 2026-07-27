# 安装

[← 文档首页](README.md)

## 系统要求

| | |
|---|---|
| 服务端 | Paper 1.21 – 1.21.11 |
| Java | 21 或更新版本 |

GodView 针对最旧的 1.21 API 编译，所以一个 jar 包覆盖整个版本线。它**不**兼容 Spigot——GodView 使用 Paper 独有的 API 实现聊天捕获、区块计数和异步区块加载。在 Folia 上它会启动、给出警告然后关闭指标采样：逐 tick 计时和主线程栈捕获都假设只有一条主线程，而报告毫无意义的数据比不报告更糟糕。

## 安装步骤

1. 将 `GodView-1.0.0.jar` 放入 `plugins/` 目录。
2. 启动服务器。

首次启动时 GodView 会写入 `plugins/GodView/config.yml`，通过 Paper 的库加载器下载 SQLite 驱动，并将面板绑定到 `0.0.0.0:8899`。

控制台会看到以下内容：

```
[GodView] Running on Paper 1.21.4
[GodView] GodView history is persisted to godview.db
[GodView] GodView panel listening on http://0.0.0.0:8899
[GodView] No panel account exists yet. Run this in the console or in game:
[GodView]   /godview user add <name> admin
```

最后一行很重要：**GodView 没有预设的默认账号和默认密码。** 一个带着 `admin/admin` 的已知端口面板，不出一个小时就会被扫描器发现。在创建账号之前，没有人能登录——包括你自己。

## 创建第一个账号

在服务端控制台或游戏中以管理员身份执行：

```
/godview user add admin admin
```

第一个 `admin` 是账号名，第二个是角色。GodView 会生成一个密码并打印一次：

```
[GodView] Created account 'admin' with role admin.
Password: xTmqRfPjbWnKdVs3Hh7A
This is shown once and is now in your chat log. Change it after signing in.
```

复制密码，在 `http://your-server:8899` 登录，然后在面板中更改密码。

三种角色的说明详见[命令 → 角色](commands.md#角色)。除非确实需要管理账号，否则给用户 `viewer` 或 `operator` 角色即可。

### 如果你不想输入密码

游戏中的管理员可以生成一次性登录链接：

```
/godview link
```

该链接可点击、有效期为五分钟、仅可使用一次。但它仍然通过聊天频道传输——聊天内容会写入磁盘并回显到控制台——所以命令在打印时会给出提示。

## 保护面板安全

默认绑定为 `0.0.0.0:8899`，这很方便但在**公共主机上不安全**。在暴露面板之前请选择以下方案之一。

### 方案 1——使用反向代理 + TLS（推荐）

将 GodView 绑定到仅本地地址：

```yaml
web:
  bind: "127.0.0.1"
  port: 8899
  behind-proxy: true
  trusted-proxies:
    - "127.0.0.1"
    - "::1"
```

然后在前面放 nginx 或 Caddy。Caddy 的完整配置：

```
godview.example.com {
    reverse_proxy 127.0.0.1:8899
}
```

nginx 需要额外两行配置来支持实时流，否则面板会显示连接挂起：

```nginx
location / {
    proxy_pass http://127.0.0.1:8899;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # Server-sent events：没有这两行，nginx 会缓冲实时流，面板永远收不到更新。
    proxy_buffering off;
    proxy_read_timeout 3600s;
}
```

**`behind-proxy` 和 `trusted-proxies` 必须同时设置。** `X-Forwarded-For` 是客户端写入的请求头，因此 GodView 仅在连接来自你列入白名单的地址时才信任它。如果开启 `behind-proxy` 但没有列出你的代理服务器，该请求头仍会被忽略——这是故意的，因为对配置错误的保守处理就是不信任任何东西。反过来配置错误的话，任何人都可以伪造地址绕过登录频率限制和 IP 白名单。

### 方案 2——内置 TLS

可行，但反向代理能处理证书续期，而且你几乎肯定需要一个反向代理。

```yaml
web:
  tls:
    enabled: true
    keystore: "keystore.p12"
    password: "your-keystore-password"
```

密钥库为 PKCS#12 格式，路径相对于 `plugins/GodView/`。转换 Let's Encrypt 证书：

```bash
openssl pkcs12 -export \
  -in fullchain.pem -inkey privkey.pem \
  -out keystore.p12 -name godview
```

TLS 设置仅在启动时读取——修改后需要重启服务器，而不是 `/godview reload`。

### 方案 3——按地址限制

如果面板只能从你自己的网络访问：

```yaml
security:
  ip-allowlist:
    - "192.168.1.0/24"
    - "10.0.0.5"
    - "2001:db8::/32"
```

支持纯 IP 地址和 CIDR 范围，IPv4 和 IPv6 均可。空列表表示不做限制。来自其他地址的请求在认证之前就会收到 403。

这可以与其他方案组合使用——反向代理后面的白名单比两者单独使用更严格，前提是 `trusted-proxies` 已正确设置，以便检查的是真实的客户端地址。

## 默认开启的安全措施

| | 默认值 | 说明 |
|---|---|---|
| 登录锁定 | 5 分钟内失败 5 次 | 锁定该地址 5 分钟 |
| 会话有效期 | 2 小时，活跃时自动续期 | |
| 每账号会话数 | 5 | 第 6 次登录会结束最近最少使用的会话 |
| 审计日志 | 开启 | 面板触发的每次变更，无论成功与否 |
| 命令过滤 | `/stop` 和 `/restart` 被拦截 | 详见[配置](configuration.md#security) |
| 确认 | 必需 | 封禁、解封和控制台命令需要显式确认 |

## 升级

替换 jar 文件并重启。`config.yml` 不会被覆盖；新增的配置项会采用默认值，GodView 会记录任何需要裁剪或无法解析的值的警告。

SQLite 模式有版本标记并使用 `IF NOT EXISTS` 创建，因此已有的 `godview.db` 会保留。

## 卸载

删除 jar 文件。`plugins/GodView/` 目录会被保留——如果要清除历史记录和账号，请自行删除该目录。数据库是纯 SQLite 文件，如果你想保留的话可以单独保存。

---

下一步：[配置](configuration.md) · [命令](commands.md) · [面板](panel.md)
