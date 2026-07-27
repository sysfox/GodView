# 开发

[← 文档首页](README.md)

## 构建

```bash
mvn package
```

生成 `target/GodView-1.0.0.jar`——服务端和控制台在一个文件中。

要求：**JDK 21+** 和 **Maven 3.9+**。Node **不是**必需的：构建会将固定版本的 Node 下载到 `target/` 并使用它，因此任何检出都会生成相同的 jar，CI 无需安装任何额外工具。

首次构建需要几分钟（Paper API、Node、npm 包）。之后只需几秒。

### 仅修改 Java 代码

```bash
mvn package -Dgodview.panel.skip=true
```

跳过整个前端工具链。jar 仍然工作；服务器会提供一个占位页面说明 API 可用而面板不可用。

### 修改面板

```bash
cd panel
npm install
npm run dev
```

Vite 在 `:5173` 提供服务，并将 `/api` 代理到 `127.0.0.1:8899`，因此你可以针对真实运行的服务器进行开发。面板中的几乎所有内容都需要一个后端才有意义。

> **需要注意一点。** 开发服务器没有 Content-Security-Policy；生产环境有，而且它禁止内联脚本。`npm run build` 配置为不内联任何内容，因此生产环境中得到的就是你测试过的——但如果你更改了 `vite.config.js`，之后请检查 `target/classes/web/index.html`，确保每个 `<script>` 仍然有 `src` 属性。测试套件对此有断言，这就是安全保障。

## 项目结构

```
src/main/java/dev/godview/
  GodViewPlugin.java       入口点；拥有所有服务，确定启动顺序
  compat/                  所有 1.21.x 差异，在一个文件中
  config/                  类型化的、经过验证的 config.yml 视图
  util/                    JSON 编解码、原始类型环形缓冲区、时间序列、文本、主线程桥接
  metrics/                 tick 采样器、卡顿看门狗、归因、GC、世界统计
  storage/                 存储契约、SQLite 和内存实现
  web/                     HTTP 服务器、路由、会话、认证
  web/api/                 REST 处理器、SSE 流、快照构建
  listener/                事件捕获、控制台镜像
  command/                 /godview
  alert/                   规则引擎和推送渠道
  integration/             LuckPerms、CoreProtect、Vault、PlaceholderAPI、EssentialsX、spark
  map/                     方块颜色、瓦片渲染器、瓦片缓存、捕获调度
  i18n/                    命令消息表

panel/                     Vue 控制台，编译到 jar 中
src/test/java/dev/godview/ 验证套件
```

## 测试

```bash
mvn test
```

十一个套件。它们驱动真实组件而非模拟：绑定的 socket、磁盘上的 SQLite 文件、附加的 Log4j appender、从渲染器中解码回来的真实 PNG、通过真实静态处理器服务的已构建 Vue 包。

| 套件 | 覆盖范围 |
|---|---|
| `UtilSuiteTest` | JSON 编解码、原始类型环形缓冲区、多分辨率时间序列 |
| `MetricsSuiteTest` | 档位、调控器迟滞、归因、端到端模拟卡顿 |
| `StorageSuiteTest` | 两个后端使用同一组预期 |
| `AuthSuiteTest` | 代理请求头、密码哈希、角色、登录守卫 |
| `WebSuiteTest` | 通过真实请求的完整 HTTP 管道 |
| `ApiSuiteTest` | 路由表和实时事件流 |
| `ConsoleSuiteTest` | 通过真实 appender 的控制台捕获 |
| `IntegrationSuiteTest` | 未安装任何插件时的降级契约 |
| `AlertSuiteTest` | 规则和推送，针对记录到达内容的端点 |
| `MapSuiteTest` | 颜色、瓦片缓存、来自合成地形的真实 PNG |
| `PanelSuiteTest` | 通过真实静态处理器的已构建面板 |

运行单个套件：

```bash
mvn test -Dtest=MapSuiteTest
```

它们比单元测试慢，而这正是关键。它们捕获的大多数问题存在于组件之间的接缝处——两个存储后端之间不同的分桶规则、在异常路径上泄漏的流标记、编译器不可见的 log4j 版本不匹配。模拟会掩盖所有三种问题。

### 编写新测试

所有测试使用来自 `dev.godview.Checks` 的一个断言：

```java
check("cooldown suppresses repeats", rule.timesFired(), 1L);
```

名称读起来像关于系统的一句话，因为失败的断言通常由几个月后没有编写它的人阅读，而名称是他们获得的全部解释。比较通过字符串形式进行，这使断言关于行为，而非关于 JSON 解析器碰巧选择了哪种数值类型。

## 重要的约定

**主线程上不做开销大的操作。** 如果一项工作是 `O(实体)`、`O(区块)` 或涉及磁盘或网络，它应该属于另一个线程或一个遵循性能档位的慢时钟。当它确实必须在主线程上运行时，它会有预算。

**软失败，并说明原因。** 缺失的插件、不可读的数据库、不可解析的配置值——这些都不能阻止 GodView 启动。降级，记录一次原因，并使降级状态在面板中可见。唯一的例外是诊断本身：控制台 appender 静默地吞下其失败，因为在 `append` 内部记录日志的 appender 会递归直到栈溢出。

**版本差异在 `Compat` 中。** 在静态初始化器中解析一次，每次调用不再重复。每秒运行二十次的采样器无法承担 `getMethod` 查找的开销，更无法承担异常。

**在调用处压制弃用警告，并说明原因。** 构建运行 `-Xlint:all` 且无警告。当遗留 Bukkit API 是正确的选择时——通常因为其 Adventure 替代品在支持的范围内不存在——压制是局部的并带有说明原因的注释。

**注释解释为什么，而不是什么。** 代码说明它做了什么。注释通过记录决策、约束或陷阱来赢得它的位置。

## 发布

1. 在 `pom.xml` 中更新 `<version>`。`plugin.yml` 从中过滤，因此会跟随更新。
2. `mvn clean package`——这会运行测试。
3. 检查 `target/GodView-<version>.jar` 包含 `web/index.html` 和 `web/assets/`。
4. 在真实的 Paper 服务器上冒烟测试：创建账号、登录、观察 tick 条移动。

SQLite 版本是 `pom.xml` 中的单个属性，被插值到 `plugin.yml` 中，因此声明的运行时库和编译时依赖不会分离。

---

下一步：[架构](architecture.md) · [故障排除](troubleshooting.md) · [文档首页](README.md)
