# AGENTS.md

本文件为 Codex 在本仓库中工作时使用的项目说明。

## 项目简介

AppPurge / AdbUninstaller 是一个 Android Studio / IntelliJ IDEA 插件，用于扫描当前 Android 工程中的 application 模块，检测这些应用在已连接 ADB 设备上的安装状态，并提供单个或批量卸载、清数据、重新安装等操作。

插件入口为 IntelliJ Action，主要流程为：

```text
AdbUninstallerAction
  -> AppModuleScanner.scan()
  -> AdbService.getConnectedDevices()
  -> UninstallDialog.show()
```

核心代码位于 `src/main/kotlin/com/lenovo/tools/apppurge/`，图标资源位于 `src/main/resources/icons/`。

## 编译方式

编译方式参考 `CLAUDE.md`。

### 前置条件

必须在 `local.properties` 中配置 Android Studio 本地路径，或设置环境变量 `APP_PURGE_STUDIO_PATH`：

```properties
studioPath=D:/Android/Android Studio Panda
sdk.dir=C:\\Users\\xxx\\AppData\\Local\\Android\\Sdk
```

`studioPath` 是插件编译时依赖的本地 IDE 平台路径，缺少该配置时 Gradle 会直接报错退出。

系统 Java 版本需大于等于 21。Gradle wrapper 需大于等于 8.14，以满足 Java 24 兼容性要求。

### Windows 编译命令

生成插件 zip 包，输出目录为 `build/distributions/`：

```bat
.\gradlew.bat buildPlugin
```

仅编译 Kotlin 源码，不打包插件：

```bat
.\gradlew.bat compileKotlin
```

## 版本号管理

只要需要执行 `.\gradlew.bat buildPlugin` 生成交付测试包或发布包，就必须先递增 patch 版本号，并同步更新两处：

- `gradle.properties` 中的 `pluginVersion`
- `src/main/kotlin/com/lenovo/tools/apppurge/UninstallDialog.kt` 中的 `PLUGIN_VERSION` 常量

## 开发注意事项

- `AdbService` 负责封装 ADB 命令，相关操作通常涉及外部设备状态，修改后应尽量验证连接设备场景。
- `UninstallDialog` 包含主要 UI、事件处理和表格渲染逻辑，修改渲染或点击区域时需要同时检查视觉反馈和点击判定。
- `UninstallTableModel` 将工程模块应用和仅设备安装应用分区展示，中间使用 Divider 行分隔。
- 所有 ADB 调用应保持在后台线程执行，UI 更新回到 EDT。
- 每次完整修改后，应先运行可行的验证命令；验证通过且需要交付测试时，必须先按“版本号管理”递增版本号，再执行 `.\gradlew.bat buildPlugin` 编译出插件包。
