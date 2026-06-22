# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

AppPurge 是一个 Android Studio / IntelliJ IDEA 插件，扫描当前 Android 工程的所有 application 模块，检测其在已连接 ADB 设备上的安装状态，并提供单个或批量卸载功能。

## 编译方式

### 前置条件

必须在 `local.properties` 中配置 Android Studio 本地路径（或设置环境变量 `APP_PURGE_STUDIO_PATH`）：

```properties
studioPath=D:/Android/Android Studio Panda
sdk.dir=C:\\Users\\xxx\\AppData\\Local\\Android\\Sdk
```

`studioPath` 是插件编译时依赖的本地 IDE 平台，缺少此项 Gradle 会直接报错退出。

### 编译命令（Windows）

```bat
# 生成插件 zip 包（输出到 build/distributions/）
./gradlew.bat buildPlugin

# 仅编译 Kotlin 源码（不打包）
./gradlew.bat compileKotlin
```

> **注意**：系统 Java 版本需 ≥ 21，且 Gradle wrapper 需 ≥ 8.14（Java 24 兼容性要求）。

### 版本号管理

版本号在**两处**均需同步修改：
- `gradle.properties` → `pluginVersion`
- `src/main/kotlin/com/lenovo/tools/apppurge/UninstallDialog.kt` → `PLUGIN_VERSION` 常量

## 架构概览

### 入口流程

```
AdbUninstallerAction（工具栏/菜单点击）
  └─ AppModuleScanner.scan()         # 扫描工程所有 application 模块
  └─ AdbService.getConnectedDevices()# 列出 ADB 已连接设备
  └─ UninstallDialog.show()          # 打开主对话框
```

### 核心类职责

| 类/对象 | 职责 |
|---|---|
| `AdbUninstallerAction` | IntelliJ Action 入口，在后台线程扫描后打开对话框 |
| `AppModuleScanner` | 通过 `AndroidFacet` + `build.gradle` 解析工程所有 app 模块及包名 |
| `AdbService` | 所有 ADB 命令的封装（安装/卸载/清数据/包名查询等），同步执行，带超时 |
| `ApkFinder` | 在模块 `build/outputs/apk` 目录下查找最新构建产物 |
| `AppInstallInfo` | 数据模型，持有模块引用、包名、安装状态、APK 文件列表 |
| `UninstallTableModel` | 表格数据模型，仅维护当前工程 application module，并按模块名称稳定排序 |
| `UninstallDialog` | 主对话框，包含全部 UI 构建、事件处理、行渲染器 `UniversalRenderer` |

### UninstallDialog 内部设计要点

**表格行渲染**：`UniversalRenderer` 是 `UninstallDialog` 的内部类，对所有列使用同一个渲染器实例（rubber-stamp 模式）。COL_ACTION 列的三个操作按钮（reinstall / clear / uninstall）使用共享 `JButton` 实例，不依赖 JButton 自身的 pressed 状态绘制。

**点击态实现**：`actionPanel` 是一个匿名 `JPanel` 子类，重写 `paintComponent` 在按压按钮的坐标上绘制圆角矩形高亮。坐标计算公式与 `actionAt()` 方法完全一致，保证视觉反馈与点击判定区域同步。不使用 `model.isPressed` / `isContentAreaFilled` 等 JButton 状态，因为在 renderer 上下文中不可靠。

**模块数据**：`UninstallTableModel` 只展示当前工程的 application module，不管理设备上的非项目应用；模块行按名称稳定排序，设备安装状态变化不会改变顺序。

**ADB 操作线程模型**：所有 ADB 调用在 daemon 线程中执行，结果通过 `SwingUtilities.invokeLater` 回到 EDT 更新 UI。

### 图标资源

`src/main/resources/icons/` 下每个操作对应两个 SVG：`action_xxx.svg`（启用态）和 `action_xxx_disabled.svg`（禁用态）。`RowAction` 枚举通过 `svgName` 属性自动映射图标路径。
