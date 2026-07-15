# aneb-probe（app/）— Android 测量客户端 Gradle 工程

阶段 0 目标：对仿真服务器完整跑一次 S1，并把全部时间戳打到屏幕日志。

## 工程布局

```
app/                          Gradle 工程根（rootProject.name = "aneb-probe"）
├── settings.gradle.kts       include(":probe")
├── build.gradle.kts          仅声明插件版本（apply false）
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml    版本目录：全部精确钉死（禁 +/动态版本）
│   └── wrapper/
│       └── gradle-wrapper.properties   Gradle 8.7 + distributionSha256Sum（官方 SHA 已写入）
└── probe/                    应用模块（com.aneb.probe）
```

## 供应链钉死状态

- `gradle-wrapper.properties`：`distributionUrl=gradle-8.7-bin.zip`，
  `distributionSha256Sum=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d`
  （2026-07-12 取自 https://services.gradle.org/distributions/gradle-8.7-bin.zip.sha256 ）。
- `libs.versions.toml`：AGP 8.5.2 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 / Compose BOM 2024.09.03 /
  OkHttp 4.12.0 / Room 2.6.1 / kotlinx-serialization-json 1.7.3 / coroutines 1.8.1。
- **wrapper jar 未提交**：`gradle/wrapper/gradle-wrapper.jar` 与 `gradlew`/`gradlew.bat`
  无法离线生成。装好任意 Gradle（>=8.7）后在本目录运行一次：

  ```
  gradle wrapper --gradle-version 8.7
  ```

  生成 wrapper 脚本与 jar 后**提交入库**，此后一律经 `.\gradlew` 构建（禁系统 gradle）。

## 工具链就绪后的构建步骤

1. 安装 JDK 17、Android SDK（platform 35 + build-tools），`local.properties` 写 `sdk.dir=...`。
2. 补齐 wrapper（见上）。
3. `.\gradlew :probe:assembleDebug`
4. 装到模拟器：`adb install probe\build\outputs\apk\debug\probe-debug.apk`；Debug 包名为
   `com.aneb.probe.codex`，可与正式/对比版 `com.aneb.probe` 并存。启动组件为
   `com.aneb.probe.codex/com.aneb.probe.ui.MainActivity`。
   服务器地址默认 `http://10.0.2.2:8443`（模拟器指向宿主机）；真机改填宿主机局域网 IP。
   明文 HTTP 仅 debug 变体允许（`src/debug/res/xml/network_security_config.xml`），release 禁明文。

服务端 wire 约定（阶段 0 联调依赖）见 `probe/README.md`。
