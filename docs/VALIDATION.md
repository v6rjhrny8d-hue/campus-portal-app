# 当前版本：1.3.1

本版记录见 [VALIDATION-1.3.1.md](VALIDATION-1.3.1.md)。下文均为历史版本记录。

# 1.2.1 验证记录

日期：2026-10-17（北京时间）。

## 本版已完成

- SDK Platform 35、Build Tools 35.0.0 构建完成；最低 Android 8.0、目标 Android 15。
- 161 项 JVM 离线断言通过，原有 109 项加 52 项回归检查。全部凭据和认证参数为虚构数据。
- 新检查覆盖：wireless-v2 地址结构、HTTP 中间跳转、JavaScript 导航、meta refresh 和属性顺序、编码引号、认证主机限制、公共探测失败后的服务器入口回退、普通 HTTP 探测、已联网不访问回退入口、循环与请求数量上限、取消中间跳转、读取最终页时刷新原始查询串、日志不含查询串和凭据。
- 响应读取检查覆盖：301/302/303/307/308 在正文不可读时保留 Location 且不打开正文、204 无正文、UTF-8 正文、64 KiB 上限、读取错误传播，以及取消前不连接、读取取消时关闭流。
- 最终 APK ZIP 完整性、v2/v3 签名验证通过；证书与 1.2.0 一致。版本号 1.2.1，versionCode 10201。
- 静态兼容性核对通过：设置与定时的核心文件不变，存储和 UI 只有版本文字变化。相关机器检查记录见 RELEASE-CHECKS-1.2.1.json。

## 未在本版重新执行或仍待验证

本版未重新运行 Android 模拟器界面、Keystore 覆盖安装和原生系统接口测试。1.2.0 曾通过的 33 项原生检查与 6 项覆盖安装检查属于历史记录，不计入本版测试通过项；见 VALIDATION-1.2.0.md。

尚未连接用户学校的实际网络，不能证明现场的全部响应或跳转形式已经覆盖。需要在尚未手动登录时，通过同一宿舍 Wi-Fi 测试认证、HTTPS 验证成功后停止、下一时段唤醒，以及移动数据与 VPN 并存行为。没有重新测量耗电或隔夜定时可靠性。

## 构建产物

APK SHA-256：`9bbee5a5749d5a1e31a12ed6bef22d3aef8fed6df24596da663c3595b102c30f`。

## 官方接口依据

- Android HttpURLConnection：https://developer.android.com/reference/java/net/HttpURLConnection
- Android Network.openConnection：https://developer.android.com/reference/android/net/Network

认证页探测继续显式关闭 HttpURLConnection 自动重定向，由核心逻辑校验并处理只读 GET。Wi-Fi Network 绑定和 Proxy.NO_PROXY 保持原实现。
# 1.3.0 验证补充

192 项离线核心断言通过；SDK 35 编译、APK 签名验证与 1.2.1 证书连续性校验通过。新增功能、开发环境连接失败和手机待测项目见 `RELEASE-1.3.0.md`。以下是 1.2.1 历史记录，不表示已为 1.3.0 重跑实机测试。

