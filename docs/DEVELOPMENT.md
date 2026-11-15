# 开发说明

## 项目结构

主体界面、网络、认证、定时、凭据和文件导入导出使用原生 Java 与 Android SDK。1.3.0 新增可选的独立进程 WebView 登录页；自动认证仍为原生请求，不向网页注入密码，不依赖 Termux 或本地 HTTP 服务。

| 文件 | 责任 |
| --- | --- |
| `MainActivity.java` | 权限入口、设置保存、文件选择器和界面动作处理 |
| `AppUi.java` | 原生首页、设置、日志、参数编辑与界面状态 |
| `MaterialStyle.java` | Material 3 色彩角色、状态层、圆角与统一图标 |
| `AppStore.java` | 私有设置、Keystore 凭据、最近状态与最近30个自然日的日志 |
| `NetworkController.java` | 枚举 Wi-Fi，匹配网关/网段，通过 `Network.openConnection(URL, Proxy.NO_PROXY)` 发请求 |
| `WorkService.java` | 前台服务入口、一次性进入前台、进度通知、系统超时及备用任务接续 |
| `TaskRunner.java` | 两种执行入口共用的单任务所有权、认证循环、停止原因、资源收尾和截止检查 |
| `core/RunControl.java` | 双时钟截止时间、不可覆盖的结束原因和备用任务时段资格 |
| `core/BoundHttpTransport.java` | 网络绑定注入、超时缩短、停止时断开连接、提交前再次检查 |
| `RecoveryScheduler.java` / `RecoveryJobService.java` | Android 系统备用任务、Wi-Fi 约束、配置版本与取消代次、一次有界尝试及延迟重试 |
| `ScheduleManager.java` | 用户授权的精确闹钟，只安排下一次开始时间 |
| `ScheduleReceiver.java` | 闹钟事件与开机、校时、更新后的计划恢复 |
| `core/Schedule.java` | 北京时间、星期、跨午夜和结束边界，纯 Java 可测试 |
| `core/PortalProtocol.java` | 受信任主机、页面发现、原始查询串保留、双重百分号编码 |
| `core/PortalClient.java` | 可注入网络与取消控制的认证状态机、认证后 HTTPS 验证 |

网络备注不是 SSID 鉴权。选择网络依靠用户保存的网关和 IPv4 网段；无需定位权限，同一网关和网段的另一个 Wi-Fi 也可能匹配。实际发送凭据的主机还必须与用户配置的内网 IPv4 完全一致，路径必须是 `/eportal/index.jsp`，只允许 HTTP 80/8080，不自动跟随认证 POST 的重定向。

## 认证适配约定

1. 在指定 Wi-Fi 上探测 HTTP 地址，提取当前跳转页面中的完整原始 query；不保存用户之前那条会过期的认证链接。
2. 只接受配置的内网 IPv4 主机上的 `/eportal/index.jsp?…`。
3. 检查 `passwordEncrypt`；遇到当前不支持的加密模式时停止并关闭自动计划。
4. 向同一主机和端口的 `/eportal/InterFace.do?method=login` 提交以下字段：`userId`、`password`、`service`、`queryString`、`operatorPwd`、`operatorUserId`、`validcode`、`passwordEncrypt=false`。
5. 每个值执行两次 RFC3986 百分号编码；空格使用 `%20`。queryString 保留跳转 URL 中的原始百分号编码字节。
6. 接口 JSON `result=success` 之后，在同一个 Wi-Fi 上验证 HTTPS。成功访问百度或 QQ 之一后才显示联网验证通过；否则保持未验证状态。
7. 账号拒绝后冷却 15 分钟，避免每分钟提交错误密码。不要将服务器原始回复直接写进日志。

新增学校协议时，可扩展 `PortalClient` 或新增协议策略类，但必须保留主机验证、只读检测、取消控制和实际联网验证。不要把账号密码传到网页脚本、日志或共享配置中。

## 计划与后台

- 固定北京时间 GMT+08:00，与设备显示时区无关。星期掩码从周一的 bit0 到周日的 bit6。跨午夜的时段归属于开始那天。
- 开始时刻包含，结束时刻不包含；禁止在结束后开启下一次认证请求。当前连接超时也根据剩余时段缩短。
- 使用 `SCHEDULE_EXACT_ALARM`，由用户主动授予；未授权就显示待授权，不用不精确计划伪装成准时任务。
- 闹钟触发后启动 `dataSync` 前台服务，仅在窗口内检测，结束后释放唤醒锁并停止服务。手动任务最多运行 90 秒。
- 开机广播只恢复闹钟，不直接在开机接收器启动 `dataSync` 服务。APK 更新、校时、时区和权限变化时重新规划。
- 用户强行停止、厂商后台限制、系统 VPN 禁止直连等情况仍会影响任务，需要实机验证。
- 本版本限制每次时段最多 2 小时，间隔 30～300 秒，避免长期前台运行。不要直接改成全天循环而不重新审查后台设计。

## Android Studio 构建

环境：JDK 17、Android SDK Platform 35、Android Build Tools 35.0.0。Gradle 插件为 AGP 8.7.3，建议使用兼容的 Gradle 8.9。项目未附 Gradle Wrapper 二进制，使用 Android Studio 配置本地 Gradle 或自行生成 Wrapper。

打开项目根目录，安装所需 SDK，构建 debug APK 用于开发。正式更新需要使用同一签名密钥且提高 `versionCode`；版本号需同时更新 `app/build.gradle` 和 `app/src/main/AndroidManifest.xml`，自定义命令行构建读取后者。

```bash
gradle :app:assembleDebug
```

提供的 APK 使用单独保存的项目签名。私有签名备份不包含在公开源码包内，不应转发给其他宿舍。丢失签名后无法覆盖更新已安装的同包名应用；卸载重装会丢失本机凭据。

## 不依赖 Gradle 的命令行构建

`tools/build.py` 直接使用官方 SDK 的 aapt2、Java 编译器、D8、zipalign 和 apksigner。已实际用于本版 APK。

```bash
export ANDROID_HOME=/absolute/path/to/android-sdk
read -s -p 'Signing password: ' CAMPUS_SIGNING_PASSWORD
export CAMPUS_SIGNING_PASSWORD
python3 tools/build.py \
  --keystore /absolute/path/to/campus-app-signing.jks \
  --alias campus-app \
  --output /absolute/path/to/campus-portal-app-1.1.1.apk
unset CAMPUS_SIGNING_PASSWORD
```

SDK 目录必须包含 `platforms/android-35/android.jar`、`build-tools/35.0.0/aapt2`、`lib/d8.jar`、`zipalign`、`lib/apksigner.jar`。工具会验证 APK 签名并输出 SHA-256。SDK、构建产物和签名密钥均不放入源码包。

## 运行验证

协议和时段测试依赖测试专用 `org.json:json:20240303` JAR；APK 使用 Android 自带的 org.json，不包含此依赖。

```bash
JSON_TEST_JAR=/path/to/json-20240303.jar python3 tests/run_core.py
```

`tests/run_android.py` 会构建与目标 APK 同签名的 instrumentation 测试包，验证真实 Android 原生表单、Keystore 密码往返、明文不落盘、网络选择、Wi-Fi 绑定的本地 HTTP 请求、精确闹钟规划和前台服务停止。它只能用于可清空的模拟器，会替换模拟器中本应用的数据；不会使用真实校园网账号。

```bash
python3 tests/run_android.py \
  --sdk /path/to/android-sdk \
  --adb /path/to/adb \
  --serial emulator-5558 \
  --apk /path/to/campus-portal-app-1.1.1.apk \
  --keystore /path/to/campus-app-signing.jks
```

需要设置 `CAMPUS_SIGNING_PASSWORD`。先构建目标 APK，确保 `build/classes` 与 APK 对应。测试脚本会在本机启动仅供模拟器访问的 HTTP 测试服务。模拟器、adb 和测试脚本必须在同一个可互通的本地网络环境中运行。测试会保持模拟器屏幕开启，并验证原生控件，不需要 WebView。

实际手机验收应覆盖：移动数据开/关、VPN 开/关且应用排除、未认证/已认证、密码错误、路由器断电、Wi-Fi 网段变化、精确闹钟授权、锁屏进入计划、重启后计划恢复。

## 高级配置字段

| 字段 | 含义 |
| --- | --- |
| schema | 配置版本，当前为 1 |
| label | 本地网络备注，1～40 字 |
| bound | 是否已经绑定网络 |
| gateway | 宿舍 IPv4 网关，例如 192.168.31.1 |
| subnet | 宿舍 IPv4 CIDR，例如 192.168.31.0/24 |
| portalHost | 受信任的认证服务器内网 IPv4；端口从本次跳转读取 |
| loginUrl | 可选的完整登录网址；旧配置缺省为空。登录优先此入口、失败回退；共享配置导出会清除此字段 |
| service | 认证页中的服务名称 |
| start / end | 北京时间当天分钟数，07:00 为 420，07:10 为 430 |
| days | 星期掩码，127 为每天，31 为周一到周五 |
| interval | 检测间隔秒数，30～300 |

应用不直接执行用户输入的代码。保留的是参数 JSON 和完整项目源码；需要改变网络策略或认证协议时修改源代码并重新构建。

## 官方接口参考

- [Android 闹钟与精确闹钟授权](https://developer.android.com/develop/background-work/services/alarms/schedule)
- [Android 15 前台服务变更](https://developer.android.com/develop/background-work/services/fgs/changes/foreground-services-android-15)
- [Android Network API](https://developer.android.com/reference/android/net/Network)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

## 界面与图标

视觉规范、日夜色彩、原生控件实现及图标生成提示词见 `docs/DESIGN.md`。图标原图位于 `assets/design/campus-icon.png`；APK 资源提供单独的前景、白色背景和单色图层。

## 1.1.1 成功后休眠

WorkService 在定时任务 result.verified 为真时保存 completedWindowEnd，提前 break 并经 finally 释放唤醒锁、停止前台服务。ScheduleManager 保留下一次开始的闹钟，Schedule.nextStart 的完成窗口参数抑制同一窗口的 catch-up；WorkService 入口也检查该窗口，避免重复广播启动网络请求。手动任务不受完成窗口限制。已完成窗口后掉线不自动重试，等待下次计划或手动操作。

## 1.2.0 交互层

`core/HomeState.java` 为可离线测试的首页状态投影，将当前任务、最近联网结果和下一次计划分开。AppUi 的首次设置卡、持续错误提示、处理入口与保存并测试均沿用 MainActivity 的原生动作通道。保存并测试在网络未绑定时不写入；通过同一个保存入口完成参数和凭据校验后才启动一次登录。通知状态同时检查系统通知开关与运行时权限。认证、网络与后台模块保持 1.1.1 的实现。

`tests/run_android.py --upgrade-from /path/to/campus-portal-app-1.1.1.apk` 在可清空的模拟器上先写入虚构配置与凭据，再覆盖安装本版，验证账号解密、网络绑定、时段、星期、间隔、开关和完成标记；随后执行原生界面测试。禁止把此流程用于用户手机。


## 1.2.1 认证探测修复

- core/HttpResponseReader.java：可在 JVM 回归检查的响应读取层，保留重定向头且不等待重定向正文。
- PortalProtocol.nextDiscoveryPage：仅解析明确的 HTTP/JS/meta 导航，目标须为当前配置的认证主机；不运行 JavaScript。
- PortalClient：有界探测链、认证服务器入口回退和无凭据的诊断事件。
- PortalClient.Control.diagnostic 默认空实现，Android 任务接入 AppStore.log。
- 本版核心检查 161 项，详情及实网限制见 VALIDATION.md。

## 1.3.1 生命周期约定

所有 Android 生命周期收尾在主线程处理。TaskRunner 只允许一个当前所有者；旧线程的状态写入与终止互斥，结束原因只接受第一次写入，旧完成回调不释放新任务。startForeground 只出现在 WorkService 初始提升阶段；进度调用 NotificationManager.notify，不能在系统超时后重新提升前台。重复启动保留原任务、原截止时间，停止时使用最近收到的 startId。

网络请求使用 RunControl 的墙钟窗口与 SystemClock.elapsedRealtime 上限，另外由主线程每秒检查截止时间；网络断开通过独立关闭线程执行，不阻塞系统超时回调。onTimeout 首先停止前台服务，再记录 startId、服务类型、实例存活时长及任务阶段。USER、PLAN_DISABLED、DEADLINE、SYSTEM_TIMEOUT、START_REJECTED、JOB_STOPPED、SERVICE_DESTROYED 分开记录。

前台系统超时或拒绝启动时，只在原窗口仍有效时排一次 JobScheduler 备用任务。同窗口禁止重复拉起受限前台服务。API 31+ 第一次尝试加急，失败申请普通任务；API 28+ 要求 Wi-Fi 和 INTERNET 能力，不要求 VALIDATED，否则未认证 Wi-Fi 无法触发认证。API 26/27 的约束是 NETWORK_TYPE_ANY，但所有实际请求仍由 NetworkController 绑定宿舍 Wi-Fi，绝不拿移动数据验证结果。系统备用任务每次最多 90 秒，等待由系统调度，不另持有应用唤醒锁。

备用任务携带窗口结束、配置修订号、取消代次和任务 token，执行前再核对。成功、停止、禁用、配置变化、到期均不能续跑；onStopJob 记录系统 stopReason，尊重系统用户停止，其余中断由 JobScheduler 的退避控制。JobService 受 BIND_JOB_SERVICE 权限保护，只有系统可调用。不会用切换前台服务类型规避系统限制，也不自动开关 Wi-Fi/VPN。

诊断记录的 24 小时前台累计是应用已收尾的计时，不是系统额度读数。未收尾标记与 15 秒心跳用于识别进程直接终止，不能单凭这些标记判断具体终止原因。Robolectric 模拟测试不等同于真机、模拟器系统调度或厂商省电测试，执行方式见 testing.md。


## 1.3.2 网络生命周期

WifiSession 负责每次检测的 Wi-Fi 请求/监听、完整回调就绪、网关/网段匹配、应用 blocked 状态和资源释放。前台使用 requestNetwork 维持请求；系统 Job 使用 registerNetworkCallback 监听且固定 JobParameters.getNetwork 返回的对象。API 28+ Job 未分配网络时不回退其他网络；API 26/27 仍按绑定 Wi-Fi 匹配。

TaskRunner 在每次连接检查、发送 POST 前和接受结果时再次验证该会话。网络或地址变化产生 NETWORK_UNAVAILABLE，停止旧请求。普通自动任务完整一轮零响应且零提交时也转交系统恢复。手动任务不会自动建 Job；系统任务不会递归切换回前台。系统重新分配网络时停止旧任务并走有界延迟恢复。

WorkService.start 在 API 31+ 的 isDeviceIdleMode=true 时优先排带 Wi-Fi 条件的加急 Job；不以系统 VALIDATED 标记代替真实 HTTPS。PortalClient 先尝试现有 HTTPS 验证，再按原策略发现认证页，认证后继续实际验证。先前版本的认证入口优先顺序被这一预检覆盖。

系统加急额度不足会降为普通任务；恢复后的延迟任务也是普通调度，可能错过原窗口。仍保留原始窗口、完成标记、用户停止、配置版本和取消代次，不使用伪造的 deadline 绕过约束。


## 1.5.5 检测提速

WifiSession 的认证能力回调只用于选择探测顺序，不作为认证授权或联网成功结论。PortalClient.Control 注入单调时钟；验证阶段预算与 BoundHttpTransport 请求预算分层约束。Transport.headers 采用 HTTPS GET 仅读响应头，保留系统 TLS 验证，不改用明文或信任全部证书。请求超时 watchdog 不改变任务结束原因，任务取消仍优先。认证接口已接受后的本任务重试仅验证联网。ScheduleManager 保留用户授权的精确闹钟，用同一开机周期的墙钟/elapsed 对照诊断到达延迟。


## 1.5.6 日志保留与展示

core/LogHistory只处理北京时间日期、旧日志年份标准化、30天保留和日期分组；新日志追加timestamp，原time/text兼容。AppStore.logs在同一日志锁中清理和持久化，log和exportLogs复用它；不新增后台清理调度。未知日期的异常旧记录不盲删。

AppUi.LogDayView仅日期标题处理折叠，条内完整TextView可选择复制；历史组延迟创建内容，正常追加保留原视图。展开状态保存在本AppUi及主题Draft，不写账号配置或自动计划。动画取消和dispose处理旧完成回调，关闭动画即时落定。记录展示不再截断为200条，存储不再截断为1000条。

AUTHOR_CONTACT提供邮箱复制，当前作者邮箱2042322026@qq.com。测试使用虚构日志和剪贴板影子，不发送邮件。明暗原生绘图位于临时build/history-qa；公开源码不包含这些构建截图。


## 1.5.7 弹窗按钮质量约定

YiduSheets.ActionBar以实际Paint文字宽度和字体缩放判断布局：三操作纵排、两操作仅足够宽时等高横排；至少48dp点击区域。长正文单独滚动，面板高度按实际测量限制；额外按钮沿用统一工厂并移除平台阴影，不能靠字符数估高或缩字号掩盖窄列问题。快速点击只派发一次。

SheetUiTest覆盖真实后台自测入口、长结果滚到底、默认/1.3倍字体、393dp/320dp宽度、明暗原生绘图和关闭去重。以后改弹窗必须检查实际业务路径，不把主页面截图当成弹窗验收。原生绘图在临时build/sheet-qa；源码包不包含构建截图。

NightPowerGuide集中提供夜间休眠省电说明和手动系统设置入口。通用电池优化豁免不能代表厂商开关状态；不增加不可验证的厂商私有深链或伪造状态。系统限制仍须实机熄屏与隔夜确认。
