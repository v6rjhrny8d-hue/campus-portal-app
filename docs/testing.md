# 测试方式

本项目为 Java 原生 Views 应用。已有纯 Java 核心测试和 Android instrumentation 脚本；网络通过接口注入，不引入生产依赖注入框架。1.3.1 增加针对后台服务的 Robolectric/JUnit4 模拟测试，测试库独立于 APK 构建，不改变现有界面框架、AGP 或签名方式。

## 核心回归

依赖测试专用 `org.json:json:20240303`，APK 继续使用系统 org.json。

```bash
JSON_TEST_JAR=/absolute/path/json-test.jar python3 tests/run_core.py
```

测试位于 `tests/CoreTests.java` 和 `tests/ReliabilityTests.java`，包括模拟时钟、受阻 HTTP 连接及纯协议检查。

## Android 15 生命周期模拟

先用 `tools/build.py` 构建当前应用，生成 `build/classes`。准备 JDK 17、Maven 与 Robolectric 4.16 依赖：

```bash
mvn -B -f tests/lifecycle/pom.xml \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DoutputDirectory=/absolute/path/campus-app/build/lifecycle-libs
```

从 Maven Central 的 `org.robolectric` 取得以下测试运行时，放入同一目录：

- `android-all-15-robolectric-13954326.jar`
- `android-all-instrumented-15-robolectric-13954326-i7.jar`

```bash
python3 tests/run_lifecycle.py \
  --deps /absolute/path/campus-app/build/lifecycle-libs \
  --runtime-dir /absolute/path/robolectric-sdk \
  --android-all /absolute/path/robolectric-sdk/android-all-15-robolectric-13954326.jar
```

脚本解出测试 AAR 的 classes.jar，并使用离线 Android API 35 运行时。测试源码位于 `tests/lifecycle/java/org/campusportal/app/LifecycleTest.java`。测试实例化实际 WorkService/TaskRunner/RecoveryJobService，用平台影子对象模拟前台拒绝、系统超时、JobScheduler 队列、重复和迟到回调。不使用真实账号或学校网络，不是隔夜可靠性实测。

## Android 模拟器及实机

保留 `tests/run_android.py`，只允许可清空的 `emulator-*` 目标，会覆盖模拟器应用数据。运行说明见 DEVELOPMENT.md。本次未运行完整 Android 模拟器或真机测试，不将历史版本的 instrumentation 结果算作本版通过。

本次没有改动布局，未增加截图基准或截图测试。必须在用户手机确认原有设置保留、真实认证、锁屏窗口与次日调度；失败时导出本版日志，区分系统服务中断、备用任务延迟、DNS 失败与学校入口超时。


## 1.3.2 新增验证

当前核心测试为 242 项断言，Android 15 模拟为 30 项测试。tests/run_lifecycle.py 同时执行 LifecycleTest、WifiSessionTest、NetworkFlowTest。后者替换 Network.openConnection 的响应，在真实 TaskRunner/WorkService/RecoveryJobService 中重现已联网直接结束与全失败转交系统分配网络的流程。测试不使用真实网络或用户密码。

最新版结果见 VALIDATION-1.3.2.md 与 RELEASE-CHECKS-1.3.2.json。没有执行 Android 16/HyperOS 实机测试，不将模拟结果解释为后台联网或准时性的系统保证。
