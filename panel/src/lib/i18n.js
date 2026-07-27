/* Translations for the panel.
 *
 * Two dictionaries, flat dotted keys, no library. The panel ships inside the
 * plugin jar with no network access, so an i18n framework would be another
 * dependency to bundle for a lookup and a substitution.
 *
 * The locale is chosen in this order, first hit wins:
 *   1. what the operator picked in the panel, kept in localStorage,
 *   2. `locale` from config.yml, reported by /api/auth/state,
 *   3. the browser's language,
 *   4. English.
 *
 * Only step 1 is sticky. An operator who has never touched the switcher follows
 * whatever the server is configured for, which is what makes `locale: zh_CN`
 * mean something for the panel and not just for chat messages. */

import { reactive } from 'vue';

export const LOCALES = [
  { id: 'zh_CN', label: '中文' },
  { id: 'en_US', label: 'English' },
];

const STORAGE_KEY = 'godview.locale';
const FALLBACK = 'en_US';

const zh = {
  /* ------------------------------------------------------------ chrome --- */
  'app.name': 'GodView',
  'lang.label': '语言',

  'nav.live': '实时',
  'nav.metrics': '指标',
  'nav.players': '玩家',
  'nav.console': '控制台',
  'nav.events': '事件',
  'nav.map': '地图',
  'nav.alerts': '告警',
  'nav.diagnostics': '诊断',
  'nav.account': '账号',
  'nav.label': '视图',

  'gate.subtitle': '登录以查看服务器状态。',
  'gate.unconfigured': '还没有任何账号。请在服务端控制台创建一个。',
  'gate.hint': '在服务器执行 {command}，然后用它打印的密码登录。',
  'gate.account': '账号',
  'gate.password': '密码',
  'gate.signIn': '登录',
  'gate.signingIn': '登录中',
  'gate.brandLabel': 'GodView，实时视图',

  'topbar.tps': 'TPS',
  'topbar.mspt': 'MSPT',
  'topbar.heap': '堆内存',
  'topbar.players': '在线',
  'topbar.link': '实时连接',
  'topbar.live': '实时',
  'topbar.lost': '重连中',
  'topbar.connecting': '连接中',
  'topbar.signOut': '退出',

  'toast.signedOut': '已退出登录。',
  'toast.sessionEnded': '会话已结束，请重新登录。',
  'toast.done': '完成',

  /* -------------------------------------------------------------- live --- */
  'live.title': '实时',
  'live.profile': '性能档位 {profile}',
  'live.updated': '· 更新于 {time}',

  'verdict.starting': '启动中',
  'verdict.waiting': '正在等待第一次采样。',
  'verdict.healthy': '健康',
  'verdict.slipping': '轻微下滑',
  'verdict.struggling': '吃力',
  'live.verdictLine': '{tps} TPS，最近十秒最差 tick {ms} ms。',
  'live.blame': '{plugin} 卡住服务器 {ms} ms，{ago}。',
  'live.noStall': '近期没有插件卡住服务器。',

  'live.legendUnder': '低于 50 ms',
  'live.legendOver': '超过 {ms} ms',

  'live.tps': '每秒 tick 数',
  'live.tpsUnit': '/ 20',
  'live.tpsMeta': '{percent}% 的 tick 超时',
  'live.mspt': '每 tick 毫秒数',
  'live.msptMeta': 'p95 tick 周期 {ms} ms',
  'live.heap': '堆内存占用',
  'live.heapMeta': '{used} / {max}',
  'live.playersOnline': '在线玩家',
  'live.playersMeta': '{chunks} 区块，{entities} 实体',

  'live.tpsHour': 'TPS，最近一小时',
  'live.heapHour': '堆内存，最近一小时',

  'live.metric': '指标',
  'live.value': '数值',
  'live.players': '玩家',
  'live.loadedChunks': '已加载区块',
  'live.entities': '实体',
  'live.blockEntities': '方块实体',

  'live.recentSpikes': '近期卡顿',
  'live.fromMemory': '来自内存',
  'live.when': '时间',
  'live.duration': '时长',
  'live.blamedOn': '归因',
  'live.topFrame': '栈顶帧',
  'live.noSpikes': '没有卡顿记录',
  'live.noSpikesDetail': '自插件启动以来，每个 tick 都在阈值内完成。',

  /* ----------------------------------------------------------- metrics --- */
  'metrics.title': '指标',
  'metrics.note': '来自内存的时间序列，随选定窗口自动降采样。',
  'metrics.range': '时间范围',
  'metrics.range.5m': '5 分钟',
  'metrics.range.1h': '1 小时',
  'metrics.range.6h': '6 小时',
  'metrics.range.24h': '24 小时',
  'metrics.band': '显示最小/最大范围',
  'metrics.failure': '无法加载指标',
  'metrics.tps': 'TPS',
  'metrics.mspt': '每 tick 毫秒数',
  'metrics.heap': '堆内存占用 %',
  'metrics.cpu': '进程 CPU %',
  'metrics.players': '在线玩家',
  'metrics.chunks': '已加载区块',
  'metrics.entities': '实体',
  'metrics.avg': '平均',
  'metrics.min': '最小',
  'metrics.max': '最大',
  'metrics.now': '当前',
  'metrics.empty': '暂无数据',
  'metrics.emptyDetail': '面板打开后，指标会在几秒内出现。',
  'metrics.bucket': '每点 {seconds} 秒',

  /* ----------------------------------------------------------- players --- */
  'players.title': '玩家',
  'players.online': '{count} 人在线',
  'players.failure': '无法加载玩家列表',
  'players.onlineNow': '当前在线',
  'players.player': '玩家',
  'players.ping': '延迟',
  'players.health': '生命',
  'players.mode': '模式',
  'players.position': '坐标',
  'players.played': '在线时长',
  'players.address': '地址',
  'players.actions': '操作',
  'players.kick': '踢出',
  'players.ban': '封禁',
  'players.kickReason': '踢出 {name} 的理由',
  'players.banReason': '封禁 {name} 的理由',
  'players.kickDefault': '被管理员踢出',
  'players.banDefault': '被管理员封禁',
  'players.nobody': '当前没有人在线',
  'players.nobodyDetail': '玩家加入后会立即出现在这里。',

  /* ----------------------------------------------------------- console --- */
  'console.title': '控制台',
  'console.allLevels': '全部级别',
  'console.infoUp': 'Info 及以上',
  'console.warnUp': '警告及以上',
  'console.errorsOnly': '仅错误',
  'console.minLevel': '最低级别',
  'console.follow': '跟随输出',
  'console.restricted': '你的角色只能看到警告和错误。',
  'console.lines': '服务器启动以来共 {count} 行',
  'console.off': '控制台捕获已关闭',
  'console.offDetail': '在 config.yml 中把 {key} 设为大于 0，然后执行 {command}。',
  'console.placeholder': '执行一条控制台命令',
  'console.run': '执行',
  'console.dispatched': '命令已下发',

  /* ------------------------------------------------------------ events --- */
  'events.title': '事件',
  'events.note': '服务器记录的一切，最新在前。',
  'events.all': '全部事件',
  'events.type': '事件类型',
  'events.player': '玩家',
  'events.searchText': '搜索文本',
  'events.search': '搜索',
  'events.failure': '无法加载事件',
  'events.count': '{count} 条事件',
  'events.when': '时间',
  'events.typeCol': '类型',
  'events.who': '谁',
  'events.detail': '详情',
  'events.nothing': '没有匹配结果',
  'events.nothingDetail': '事件保留时长由 storage.retention.events-days 决定。',

  'eventType.join': '加入',
  'eventType.quit': '退出',
  'eventType.chat': '聊天',
  'eventType.command': '命令',
  'eventType.death': '死亡',
  'eventType.kick': '踢出',
  'eventType.ban': '封禁',
  'eventType.console_error': '控制台错误',
  'eventType.world_load': '世界加载',
  'eventType.teleport': '传送',

  /* --------------------------------------------------------------- map --- */
  'map.title': '地图',
  'map.position': '{x}, {z} · 1:{scale}',
  'map.off': '地图已关闭',
  'map.offDetail': '在 config.yml 中把 {key} 设为 true，然后执行 {command}。',
  'map.failure': '无法访问地图',
  'map.world': '世界',
  'map.hint': '拖动平移，滚轮缩放',
  'map.zoomIn': '放大',
  'map.zoomOut': '缩小',
  'map.follow': '跟随玩家',
  'map.recenter': '回到原点',
  'map.jump': '跳转到坐标',
  'map.jumpPrompt': '跳转到坐标，格式 "x z"',
  'map.jumpBad': '坐标无法识别。请输入两个数字，例如 "128 -512"。',
  'map.refresh': '重绘可见区域',
  'map.refreshed': '可见瓦片将重新渲染。',
  'map.coverage': '{ready} / {total} 瓦片就绪',
  'map.rendering': '{count} 张瓦片正在渲染',
  'map.incomplete': '部分区块尚未加载——它们会在补齐后自动重绘。',
  'map.unloadedHint': '地图只画得出已加载的区块。想补全未加载的区域，请在 config.yml 里开启 {key}。',

  /* ------------------------------------------------------------ alerts --- */
  'alerts.title': '告警',
  'alerts.fired': '已触发 {count} 次',
  'alerts.off': '告警在 config.yml 中已关闭。',
  'alerts.quiet': '· 当前处于静默时段',
  'alerts.failure': '无法加载告警',
  'alerts.rules': '规则',
  'alerts.rule': '规则',
  'alerts.type': '类型',
  'alerts.severity': '级别',
  'alerts.firedCount': '触发次数',
  'alerts.last': '最近一次',
  'alerts.test': '测试',
  'alerts.sendTest': '发送测试',
  'alerts.unknownType': '未知类型',
  'alerts.never': '从未',
  'alerts.channels': '通知渠道',
  'alerts.channel': '渠道',
  'alerts.state': '状态',
  'alerts.problem': '问题',
  'alerts.on': '开',
  'alerts.off2': '关',
  'alerts.history': '历史',
  'alerts.what': '发生了什么',
  'alerts.sentTo': '推送到',
  'alerts.notSent': '未推送',
  'alerts.nothing': '还没有任何告警触发',
  'alerts.nothingDetail': '规则一旦触发就会出现在这里。',

  'severity.info': '提示',
  'severity.warn': '警告',
  'severity.critical': '严重',

  /* ------------------------------------------------------- diagnostics --- */
  'diag.title': '诊断',
  'diag.note': 'GodView 自身在做什么。',
  'diag.failure': '无法加载诊断信息',
  'diag.server': '服务器',
  'diag.software': '服务端',
  'diag.java': 'Java',
  'diag.host': '主机',
  'diag.cores': '{count} 核',
  'diag.uptime': '运行时长',
  'diag.load': '负载均值',
  'diag.unavailable': '不可用',
  'diag.heap': '堆内存',
  'diag.nonHeap': '非堆内存',
  'diag.of': '{used} / {max}',
  'diag.godview': 'GodView',
  'diag.profile': '性能档位',
  'diag.viewers': '实时观看数',
  'diag.streamFrames': '推流帧',
  'diag.streamFramesValue': '已发送 {sent}，丢弃 {dropped}',
  'diag.httpRequests': 'HTTP 请求',
  'diag.httpRequestsValue': '{total} 次（{errors} 次失败）',
  'diag.sessions': '会话',
  'diag.loginFailures': '登录失败',
  'diag.loginFailuresValue': '{failures} 次（锁定 {lockouts} 次）',
  'diag.consoleCapture': '控制台捕获',
  'diag.consoleLines': '{count} 行',
  'diag.consoleOff': '已关闭',
  'diag.storage': '存储',
  'diag.events': '事件',
  'diag.metrics': '指标',
  'diag.spikes': '卡顿',
  'diag.audit': '审计',
  'diag.alerts': '告警',
  'diag.onDisk': '磁盘占用',
  'diag.queuedDropped': '排队 / 丢弃',
  'diag.gc': '垃圾回收',
  'diag.gcNotifications': 'JMX 通知',
  'diag.gcPolled': '轮询',
  'diag.collections': '回收次数',
  'diag.totalPause': '累计暂停',
  'diag.worstPause': '近期最差',
  'diag.integrations': '插件集成',
  'diag.plugin': '插件',
  'diag.note2': '备注',
  'diag.connected': '已连接',
  'diag.absent': '未安装',
  'diag.plugins': '插件列表',
  'diag.installed': '已安装 {count} 个',
  'diag.version': '版本',
  'diag.enabled': '已启用',
  'diag.disabled': '已禁用',
  'diag.mapCache': '地图瓦片',
  'diag.mapMemory': '内存缓存',
  'diag.mapDisk': '磁盘缓存',
  'diag.mapRendered': '已渲染瓦片',

  /* ----------------------------------------------------------- account --- */
  'account.title': '账号',
  'account.note': '你的账号、会话，以及（管理员）面板用户。',
  'account.profile': '个人资料',
  'account.name': '账号',
  'account.role': '角色',
  'account.permissions': '权限',
  'account.expires': '会话到期',

  'account.password': '修改密码',
  'account.currentPassword': '当前密码',
  'account.newPassword': '新密码',
  'account.confirmPassword': '确认新密码',
  'account.passwordRule': '至少 {min} 个字符。',
  'account.mismatch': '两次输入的新密码不一致。',
  'account.tooShort': '新密码至少需要 {min} 个字符。',
  'account.same': '新密码不能和当前密码相同。',
  'account.changePassword': '修改密码',
  'account.changing': '修改中',
  'account.changed': '密码已修改，其他会话已全部注销。',

  'account.sessions': '会话',
  'account.sessionsAside': '此账号当前登录的地方',
  'account.sessionUser': '账号',
  'account.sessionIp': '地址',
  'account.sessionAgent': '客户端',
  'account.sessionSeen': '最近活动',
  'account.sessionCurrent': '当前',
  'account.revoke': '注销',
  'account.revokeAll': '注销全部会话',
  'account.revoked': '已注销 {count} 个会话。',
  'account.noSessions': '没有活动会话',

  'users.title': '面板用户',
  'users.aside': '{count} 个账号',
  'users.name': '账号',
  'users.role': '角色',
  'users.created': '创建于',
  'users.lastLogin': '最近登录',
  'users.never': '从未',
  'users.state': '状态',
  'users.actions': '操作',
  'users.enabled': '已启用',
  'users.disabled': '已停用',
  'users.enable': '启用',
  'users.disable': '停用',
  'users.resetPassword': '重置密码',
  'users.remove': '删除',
  'users.add': '新建账号',
  'users.addName': '账号名',
  'users.addRole': '角色',
  'users.addPassword': '密码（留空则自动生成）',
  'users.create': '创建',
  'users.created2': '已创建账号 {name}。',
  'users.generated': '生成的密码：{password} —— 只显示这一次。',
  'users.removeConfirm': '确定删除账号 {name} 吗？该操作无法撤销。',
  'users.removed': '已删除账号 {name}。',
  'users.resetPrompt': '{name} 的新密码（留空则自动生成）',
  'users.passwordSet': '已为 {name} 设置新密码。',
  'users.roleSet': '{name} 的角色已改为 {role}。',
  'users.enabledSet': '{name} 已启用。',
  'users.disabledSet': '{name} 已停用。',
  'users.linked': '绑定玩家',

  'role.viewer': '只读',
  'role.operator': '运维',
  'role.admin': '管理员',

  'audit.title': '审计日志',
  'audit.aside': '面板发起的每一次变更',
  'audit.when': '时间',
  'audit.who': '操作者',
  'audit.action': '动作',
  'audit.target': '对象',
  'audit.detail': '详情',
  'audit.result': '结果',
  'audit.ok': '成功',
  'audit.failed': '失败',
  'audit.empty': '暂无审计记录',
  'audit.emptyDetail': '面板发起的变更会记录在这里。',

  /* ------------------------------------------------------------- time --- */
  'time.never': '从未',
  'time.justNow': '刚刚',
  'time.secondsAgo': '{n} 秒前',
  'time.minutesAgo': '{n} 分钟前',
  'time.hoursAgo': '{n} 小时前',
  'time.daysAgo': '{n} 天前',
  'time.seconds': '{n} 秒',
  'time.minutes': '{n} 分钟',
  'time.hoursMinutes': '{h} 小时 {m} 分',
  'time.daysHours': '{d} 天 {h} 小时',

  /* ------------------------------------------------------------ shared --- */
  'common.cancel': '取消',
  'common.save': '保存',
  'common.close': '关闭',
  'common.unreachable': '服务器没有响应。',
  'common.sessionEnded': '会话已结束，请重新登录。',
};

const en = {
  'app.name': 'GodView',
  'lang.label': 'Language',

  'nav.live': 'Live',
  'nav.metrics': 'Metrics',
  'nav.players': 'Players',
  'nav.console': 'Console',
  'nav.events': 'Events',
  'nav.map': 'Map',
  'nav.alerts': 'Alerts',
  'nav.diagnostics': 'Diagnostics',
  'nav.account': 'Account',
  'nav.label': 'Views',

  'gate.subtitle': 'Sign in to watch the server.',
  'gate.unconfigured': 'No account exists yet. Create one in the server console.',
  'gate.hint': 'Run {command} on the server, then sign in with the password it prints.',
  'gate.account': 'Account',
  'gate.password': 'Password',
  'gate.signIn': 'Sign in',
  'gate.signingIn': 'Signing in',
  'gate.brandLabel': 'GodView, live view',

  'topbar.tps': 'TPS',
  'topbar.mspt': 'MSPT',
  'topbar.heap': 'Heap',
  'topbar.players': 'Players',
  'topbar.link': 'Live connection',
  'topbar.live': 'Live',
  'topbar.lost': 'Reconnecting',
  'topbar.connecting': 'Connecting',
  'topbar.signOut': 'Sign out',

  'toast.signedOut': 'Signed out.',
  'toast.sessionEnded': 'Your session ended. Sign in again.',
  'toast.done': 'Done',

  'live.title': 'Live',
  'live.profile': 'Profile {profile}',
  'live.updated': '· updated {time}',

  'verdict.starting': 'Starting',
  'verdict.waiting': 'Waiting for the first sample.',
  'verdict.healthy': 'Healthy',
  'verdict.slipping': 'Slipping',
  'verdict.struggling': 'Struggling',
  'live.verdictLine': '{tps} TPS, worst tick {ms} ms in the last ten seconds.',
  'live.blame': '{plugin} stalled the server for {ms} ms, {ago}.',
  'live.noStall': 'No plugin has stalled the server recently.',

  'live.legendUnder': 'under 50 ms',
  'live.legendOver': 'over {ms} ms',

  'live.tps': 'Ticks per second',
  'live.tpsUnit': '/ 20',
  'live.tpsMeta': '{percent}% of ticks ran long',
  'live.mspt': 'Milliseconds per tick',
  'live.msptMeta': 'p95 tick period {ms} ms',
  'live.heap': 'Heap in use',
  'live.heapMeta': '{used} of {max}',
  'live.playersOnline': 'Players online',
  'live.playersMeta': '{chunks} chunks, {entities} entities',

  'live.tpsHour': 'TPS, last hour',
  'live.heapHour': 'Heap, last hour',

  'live.metric': 'Metric',
  'live.value': 'Value',
  'live.players': 'Players',
  'live.loadedChunks': 'Loaded chunks',
  'live.entities': 'Entities',
  'live.blockEntities': 'Block entities',

  'live.recentSpikes': 'Recent spikes',
  'live.fromMemory': 'from memory',
  'live.when': 'When',
  'live.duration': 'Duration',
  'live.blamedOn': 'Blamed on',
  'live.topFrame': 'Top frame',
  'live.noSpikes': 'No spikes recorded',
  'live.noSpikesDetail':
    'Every tick has finished inside the threshold since the plugin started.',

  'metrics.title': 'Metrics',
  'metrics.note': 'In-memory time series, downsampled to fit the window you pick.',
  'metrics.range': 'Range',
  'metrics.range.5m': '5 minutes',
  'metrics.range.1h': '1 hour',
  'metrics.range.6h': '6 hours',
  'metrics.range.24h': '24 hours',
  'metrics.band': 'Show the min/max band',
  'metrics.failure': 'Could not load metrics',
  'metrics.tps': 'TPS',
  'metrics.mspt': 'Milliseconds per tick',
  'metrics.heap': 'Heap in use %',
  'metrics.cpu': 'Process CPU %',
  'metrics.players': 'Players online',
  'metrics.chunks': 'Loaded chunks',
  'metrics.entities': 'Entities',
  'metrics.avg': 'avg',
  'metrics.min': 'min',
  'metrics.max': 'max',
  'metrics.now': 'now',
  'metrics.empty': 'No data yet',
  'metrics.emptyDetail': 'Metrics appear within seconds of the panel being open.',
  'metrics.bucket': '{seconds}s per point',

  'players.title': 'Players',
  'players.online': '{count} online',
  'players.failure': 'Could not load players',
  'players.onlineNow': 'Online now',
  'players.player': 'Player',
  'players.ping': 'Ping',
  'players.health': 'Health',
  'players.mode': 'Mode',
  'players.position': 'Position',
  'players.played': 'Played',
  'players.address': 'Address',
  'players.actions': 'Actions',
  'players.kick': 'Kick',
  'players.ban': 'Ban',
  'players.kickReason': 'Reason for kicking {name}',
  'players.banReason': 'Reason for banning {name}',
  'players.kickDefault': 'Kicked by an operator',
  'players.banDefault': 'Banned by an operator',
  'players.nobody': 'Nobody is online',
  'players.nobodyDetail': 'Players appear here as soon as they join.',

  'console.title': 'Console',
  'console.allLevels': 'All levels',
  'console.infoUp': 'Info and above',
  'console.warnUp': 'Warnings and above',
  'console.errorsOnly': 'Errors only',
  'console.minLevel': 'Minimum level',
  'console.follow': 'Follow output',
  'console.restricted': 'Your role sees warnings and errors only.',
  'console.lines': '{count} lines since the server started',
  'console.off': 'Console capture is off',
  'console.offDetail': 'Set {key} above zero in config.yml and run {command}.',
  'console.placeholder': 'Run a console command',
  'console.run': 'Run',
  'console.dispatched': 'Command dispatched',

  'events.title': 'Events',
  'events.note': 'Everything the server recorded, newest first.',
  'events.all': 'All events',
  'events.type': 'Event type',
  'events.player': 'Player',
  'events.searchText': 'Search text',
  'events.search': 'Search',
  'events.failure': 'Could not load events',
  'events.count': '{count} events',
  'events.when': 'When',
  'events.typeCol': 'Type',
  'events.who': 'Who',
  'events.detail': 'Detail',
  'events.nothing': 'Nothing matched',
  'events.nothingDetail':
    'Events are kept for as long as storage.retention.events-days allows.',

  'eventType.join': 'join',
  'eventType.quit': 'quit',
  'eventType.chat': 'chat',
  'eventType.command': 'command',
  'eventType.death': 'death',
  'eventType.kick': 'kick',
  'eventType.ban': 'ban',
  'eventType.console_error': 'console error',
  'eventType.world_load': 'world load',
  'eventType.teleport': 'teleport',

  'map.title': 'Map',
  'map.position': '{x}, {z} · 1:{scale}',
  'map.off': 'The map is off',
  'map.offDetail': 'Set {key} to true in config.yml and run {command}.',
  'map.failure': 'Could not reach the map',
  'map.world': 'World',
  'map.hint': 'Drag to pan, scroll to zoom',
  'map.zoomIn': 'Zoom in',
  'map.zoomOut': 'Zoom out',
  'map.follow': 'Follow a player',
  'map.recenter': 'Back to origin',
  'map.jump': 'Jump to coordinates',
  'map.jumpPrompt': 'Jump to coordinates, as "x z"',
  'map.jumpBad': 'Those coordinates did not parse. Give two numbers, like "128 -512".',
  'map.refresh': 'Redraw what is visible',
  'map.refreshed': 'The visible tiles will be redrawn.',
  'map.coverage': '{ready} of {total} tiles ready',
  'map.rendering': '{count} tiles rendering',
  'map.incomplete': 'Some chunks are not loaded yet — those tiles redraw once they are.',
  'map.unloadedHint':
    'The map can only draw chunks that are loaded. To fill in the rest, turn on {key} in config.yml.',

  'alerts.title': 'Alerts',
  'alerts.fired': '{count} fired',
  'alerts.off': 'Alerts are off in config.yml.',
  'alerts.quiet': '· quiet hours are active',
  'alerts.failure': 'Could not load alerts',
  'alerts.rules': 'Rules',
  'alerts.rule': 'Rule',
  'alerts.type': 'Type',
  'alerts.severity': 'Severity',
  'alerts.firedCount': 'Fired',
  'alerts.last': 'Last',
  'alerts.test': 'Test',
  'alerts.sendTest': 'Send test',
  'alerts.unknownType': 'unknown type',
  'alerts.never': 'never',
  'alerts.channels': 'Channels',
  'alerts.channel': 'Channel',
  'alerts.state': 'State',
  'alerts.problem': 'Problem',
  'alerts.on': 'on',
  'alerts.off2': 'off',
  'alerts.history': 'History',
  'alerts.what': 'What happened',
  'alerts.sentTo': 'Sent to',
  'alerts.notSent': 'not sent',
  'alerts.nothing': 'Nothing has fired',
  'alerts.nothingDetail': 'Alerts appear here as soon as a rule triggers.',

  'severity.info': 'info',
  'severity.warn': 'warn',
  'severity.critical': 'critical',

  'diag.title': 'Diagnostics',
  'diag.note': 'What GodView itself is doing.',
  'diag.failure': 'Could not load diagnostics',
  'diag.server': 'Server',
  'diag.software': 'Software',
  'diag.java': 'Java',
  'diag.host': 'Host',
  'diag.cores': '{count} cores',
  'diag.uptime': 'Uptime',
  'diag.load': 'Load average',
  'diag.unavailable': 'unavailable',
  'diag.heap': 'Heap',
  'diag.nonHeap': 'Non-heap',
  'diag.of': '{used} of {max}',
  'diag.godview': 'GodView',
  'diag.profile': 'Profile',
  'diag.viewers': 'Live viewers',
  'diag.streamFrames': 'Stream frames',
  'diag.streamFramesValue': '{sent} sent, {dropped} dropped',
  'diag.httpRequests': 'HTTP requests',
  'diag.httpRequestsValue': '{total} ({errors} failed)',
  'diag.sessions': 'Sessions',
  'diag.loginFailures': 'Sign-in failures',
  'diag.loginFailuresValue': '{failures} ({lockouts} lockouts)',
  'diag.consoleCapture': 'Console capture',
  'diag.consoleLines': '{count} lines',
  'diag.consoleOff': 'off',
  'diag.storage': 'Storage',
  'diag.events': 'Events',
  'diag.metrics': 'Metrics',
  'diag.spikes': 'Spikes',
  'diag.audit': 'Audit',
  'diag.alerts': 'Alerts',
  'diag.onDisk': 'On disk',
  'diag.queuedDropped': 'Queued / dropped',
  'diag.gc': 'Garbage collection',
  'diag.gcNotifications': 'JMX notifications',
  'diag.gcPolled': 'polled',
  'diag.collections': 'Collections',
  'diag.totalPause': 'Total pause',
  'diag.worstPause': 'Worst recent',
  'diag.integrations': 'Integrations',
  'diag.plugin': 'Plugin',
  'diag.note2': 'Note',
  'diag.connected': 'connected',
  'diag.absent': 'absent',
  'diag.plugins': 'Plugins',
  'diag.installed': '{count} installed',
  'diag.version': 'Version',
  'diag.enabled': 'enabled',
  'diag.disabled': 'disabled',
  'diag.mapCache': 'Map tiles',
  'diag.mapMemory': 'Memory cache',
  'diag.mapDisk': 'Disk cache',
  'diag.mapRendered': 'Tiles rendered',

  'account.title': 'Account',
  'account.note': 'Your account, your sessions, and — for admins — panel users.',
  'account.profile': 'Profile',
  'account.name': 'Account',
  'account.role': 'Role',
  'account.permissions': 'Permissions',
  'account.expires': 'Session expires',

  'account.password': 'Change password',
  'account.currentPassword': 'Current password',
  'account.newPassword': 'New password',
  'account.confirmPassword': 'Confirm new password',
  'account.passwordRule': 'At least {min} characters.',
  'account.mismatch': 'The two new passwords do not match.',
  'account.tooShort': 'A new password needs at least {min} characters.',
  'account.same': 'The new password must differ from the current one.',
  'account.changePassword': 'Change password',
  'account.changing': 'Changing',
  'account.changed': 'Password changed. Every other session has been signed out.',

  'account.sessions': 'Sessions',
  'account.sessionsAside': 'where this account is signed in',
  'account.sessionUser': 'Account',
  'account.sessionIp': 'Address',
  'account.sessionAgent': 'Client',
  'account.sessionSeen': 'Last seen',
  'account.sessionCurrent': 'current',
  'account.revoke': 'Revoke',
  'account.revokeAll': 'Revoke every session',
  'account.revoked': 'Revoked {count} session(s).',
  'account.noSessions': 'No active sessions',

  'users.title': 'Panel users',
  'users.aside': '{count} accounts',
  'users.name': 'Account',
  'users.role': 'Role',
  'users.created': 'Created',
  'users.lastLogin': 'Last sign-in',
  'users.never': 'never',
  'users.state': 'State',
  'users.actions': 'Actions',
  'users.enabled': 'enabled',
  'users.disabled': 'disabled',
  'users.enable': 'Enable',
  'users.disable': 'Disable',
  'users.resetPassword': 'Reset password',
  'users.remove': 'Remove',
  'users.add': 'New account',
  'users.addName': 'Account name',
  'users.addRole': 'Role',
  'users.addPassword': 'Password (blank to generate one)',
  'users.create': 'Create',
  'users.created2': 'Created account {name}.',
  'users.generated': 'Generated password: {password} — shown once.',
  'users.removeConfirm': 'Remove the account {name}? This cannot be undone.',
  'users.removed': 'Removed account {name}.',
  'users.resetPrompt': 'New password for {name} (blank to generate one)',
  'users.passwordSet': 'Set a new password for {name}.',
  'users.roleSet': '{name} is now {role}.',
  'users.enabledSet': '{name} is enabled.',
  'users.disabledSet': '{name} is disabled.',
  'users.linked': 'Linked player',

  'role.viewer': 'viewer',
  'role.operator': 'operator',
  'role.admin': 'admin',

  'audit.title': 'Audit log',
  'audit.aside': 'every change the panel made',
  'audit.when': 'When',
  'audit.who': 'Who',
  'audit.action': 'Action',
  'audit.target': 'Target',
  'audit.detail': 'Detail',
  'audit.result': 'Result',
  'audit.ok': 'ok',
  'audit.failed': 'failed',
  'audit.empty': 'Nothing audited yet',
  'audit.emptyDetail': 'Changes made from the panel are recorded here.',

  'time.never': 'never',
  'time.justNow': 'just now',
  'time.secondsAgo': '{n}s ago',
  'time.minutesAgo': '{n}m ago',
  'time.hoursAgo': '{n}h ago',
  'time.daysAgo': '{n}d ago',
  'time.seconds': '{n}s',
  'time.minutes': '{n}m',
  'time.hoursMinutes': '{h}h {m}m',
  'time.daysHours': '{d}d {h}h',

  'common.cancel': 'Cancel',
  'common.save': 'Save',
  'common.close': 'Close',
  'common.unreachable': 'The server is not responding.',
  'common.sessionEnded': 'Your session ended. Sign in again.',
};

const TABLES = { zh_CN: zh, en_US: en };

function normalise(id) {
  if (!id) return null;
  const lower = String(id).toLowerCase().replace('-', '_');
  if (lower.startsWith('zh')) return 'zh_CN';
  if (lower.startsWith('en')) return 'en_US';
  return null;
}

function stored() {
  try {
    return normalise(window.localStorage.getItem(STORAGE_KEY));
  } catch {
    // Private browsing, or storage disabled. Not worth failing over.
    return null;
  }
}

function fromBrowser() {
  const languages = window.navigator?.languages ?? [window.navigator?.language];
  for (const language of languages) {
    const match = normalise(language);
    if (match) return match;
  }
  return FALLBACK;
}

/** `chosen` is true only once the operator has picked, which is what makes the
 *  server's configured locale a default rather than an override. */
const i18n = reactive({
  locale: stored() ?? fromBrowser(),
  chosen: stored() !== null,
});

export { i18n };

export function setLocale(id, explicit = true) {
  const next = normalise(id);
  if (!next) return;
  i18n.locale = next;
  if (explicit) {
    i18n.chosen = true;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // The switcher still works for this tab; only persistence is lost.
    }
  }
  document.documentElement.lang = next.replace('_', '-');
}

/** Applies `locale` from config.yml, unless the operator has already picked one. */
export function adoptServerLocale(id) {
  if (i18n.chosen) return;
  setLocale(id, false);
}

/**
 * Looks up a key.
 *
 * Reads `i18n.locale` on every call, so any template that calls this re-renders
 * when the language changes -- which is the whole reason there is no cached
 * lookup here.
 */
export function t(key, params) {
  const table = TABLES[i18n.locale] ?? TABLES[FALLBACK];
  // A missing key falls back to English rather than showing the raw key, so a
  // half-finished translation degrades into mixed language, not into debris.
  const template = table[key] ?? TABLES[FALLBACK][key];
  if (template === undefined) return key;
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name) =>
    (params[name] === undefined ? whole : String(params[name])));
}

/** The current locale as a BCP 47 tag, for Intl and `lang` attributes. */
export function bcp47() {
  return i18n.locale.replace('_', '-');
}

setLocale(i18n.locale, i18n.chosen);
