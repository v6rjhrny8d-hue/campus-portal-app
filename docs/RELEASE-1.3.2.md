# 校园网助手 1.3.2

针对 10 月 24 日“手动正常，休眠中定时任务全部请求失败”的日志改进后台联网。

## 本版变化

- 先在绑定的宿舍 Wi-Fi 上验证 HTTPS。验证成功即完成本时段，不读取保存的认证入口、不提交账号。系统的 VALIDATED 标记仅作诊断，不能代替本次实际访问。
- Android 12 及以上设备处于 Doze 时，到点优先申请带 Wi-Fi、INTERNET、NOT_VPN 条件的系统加急任务。INTERNET 表示网络配置能力，不要求 VALIDATED，因此仍能处理未认证的校园 Wi-Fi。系统未接受任务时保留前台尝试。
- 前台自动任务一轮所有请求均没有响应且没有提交认证时，交给系统任务恢复，不在相同执行条件下等满整个时段。只在原时段内恢复；手动任务不因此新增自动计划。
- 前台检测使用 task-scoped requestNetwork；等待有序的网络能力、地址和本应用阻止状态回调后才请求。等待就绪最长 10 秒，且受原任务剩余时间限制。结束时注销回调。
- 系统任务严格使用 JobParameters.getNetwork() 分配的网络，再核对宿舍网关和网段。无网络、VPN、移动网络或绑定不匹配时不提交账号；不自行换到另一条网络。系统重新分配网络时取消旧连接，交回有界重试。
- 请求过程中再次核对网络、地址和阻止状态，网络失效后的返回结果不能记作验证成功。保留原来的停止、取消、截止时间、配置版本及单任务所有权保护。
- 日志增加网络标识、onBlockedStatusChanged、后台流量限制、异常原因链及系统 errno（若存在），并统计实际收到的响应。异常消息、账号密码、完整查询串不写入日志。

## 使用

覆盖安装 campus-portal-app-1.3.2.apk，不要先卸载。沿用原签名、包名、凭据密钥别名和配置格式。更新后打开一次 APP，核对原来的自动计划。

周末已联网时，预期记录为 HTTPS 验证成功、尝试提交 0，然后结束本时段。休眠状态下应出现“优先申请带 Wi-Fi 条件的系统加急任务”和“使用系统分配网络”；若系统实际按加急执行，日志会记录“加急执行=true”。

## 验证边界

通过 242 项 JVM 核心断言、30 项 Android 15/API 35 的 Robolectric 模拟测试、SDK 35 构建和原签名核验。其中两个完整流程测试调用实际 TaskRunner/WorkService/RecoveryJobService，只替换网络响应：已联网时仅发一次 HTTPS 请求即完成；全失败后切换系统分配网络、验证成功并完成原时段。

这些检查不代表已在用户的 Xiaomi 24122RKC7C / Android 16 上通过实测，也不能证明此前故障的唯一根因。系统加急额度不足时回退普通任务；普通任务、失败后的延迟重试仍可能被系统推迟。学校 Wi-Fi 本身断开、网关故障或厂商后台策略仍可能阻止网络。到期不补交账号，不通过虚报已联网结束任务。

请以保持熄屏的下一次实际计划作为验收；已登录场景确认零提交且验证成功，再在正常需要认证的早晨确认自动登录。失败时导出新版日志，结合网络阻止回调和异常原因链继续定位。无需为了测试主动退出当前校园网。

## 官方依据

- https://developer.android.com/reference/android/app/job/JobInfo.Builder#setExpedited(boolean)：带网络约束的加急任务可绕过标准 Doze、待机和省电的网络限制，仍受额度及调度条件约束。
- https://developer.android.com/reference/android/app/job/JobParameters#getNetwork()：任务应使用系统分配网络。
- https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback：按有序回调使用网络能力、链路信息和本应用阻止状态，避免同步快照竞态。
- https://developer.android.com/reference/android/app/job/JobService#onNetworkChanged(android.app.job.JobParameters)：系统更换任务分配网络时需处理旧连接。

详细验证范围见 VALIDATION-1.3.2.md。
