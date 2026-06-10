# Push System APK Design

## 背景

AppPurge 当前支持扫描 Android 工程中的 application module，并对已连接设备上的应用执行 reinstall、clear data、uninstall 等操作。

新增需求是在 Module APP 的 Options 列增加一个 Push 功能，用于将本地 APK 推送到设备 system 分区指定位置，覆盖或新增 system app，并在必要时处理 data overlay、清数据和重启生效提示。

## 目标

- 在 Module APP 行增加 Push System APK 操作入口。
- 支持检测覆盖安装过的 system app 原始系统路径。
- 支持用户选择或手动编辑本地 APK 和设备目标路径。
- 支持 root/remount、push、权限修正、data overlay 移除、clear data。
- Push 成功后提示重启生效，并在当前 Android Studio 会话内维护待重启提示。

## 非目标

- 不为 Device-only app 提供 Push 功能。
- 不持久化保存重启标记到磁盘，Android Studio 重启后标记可以丢失。
- 不自动执行更侵入性的设备修复动作，例如自动 disable-verity；只提示用户重启后重试。

## Options 入口

当前 Options:

```text
Reinstall | Clear Data | Uninstall
```

新增后:

```text
Reinstall | Clear Data | Uninstall | Push
```

规则:

- Push 仅对 Module APP 启用。
- Device-only app 不显示 Push 或显示为 disabled。
- 模块未找到 APK 时 Push 置灰，tooltip 提示先 build module。
- 点击 Push 后打开二级弹窗，不直接执行 push。

## System APP 原始路径检测

不能只依赖:

```text
pm path <package>
```

覆盖安装 system app 后，`pm path` 可能只返回 `/data/app/.../base.apk`，无法看到原始 system APK 路径。

应优先解析:

```text
dumpsys package <package>
```

### 检测顺序

1. 执行 `dumpsys package <package>`。
2. 定位 `Hidden system packages:` 区块。
3. 在该区块中定位 `Package [目标包名]`。
4. 只在目标 package block 内解析:

   ```text
   codePath=
   resourcePath=
   ```

5. 优先使用 `codePath`，其次使用 `resourcePath`。
6. 只接受 system 分区路径:

   ```text
   /system/
   /system_ext/
   /product/
   /vendor/
   /odm/
   ```

7. 如果 hidden system package 没命中，再 fallback 到 `pm path <package>`，从结果中查找非 `/data/app/` 的 system 路径。
8. 仍然没有检测到时，使用默认目标路径。

### 覆盖安装检测

如果当前 active package path 中存在 `/data/app/`，认为存在 data overlay。

Push 成功后需要移除该 overlay，使系统分区 APK 成为生效来源。

## 默认目标路径规则

目标路径使用 Module 名，不使用本地 APK 文件名里的 `debug`、`release` 等变体后缀。

示例:

```text
moduleName = agent-server
```

默认路径:

```text
/system/app/agent-server/agent-server.apk
```

如果检测到原 system app 位于 `/system/priv-app`，例如:

```text
/system/priv-app/agent-server-debug.apk
```

默认目标路径转换为:

```text
/system/priv-app/agent-server/agent-server.apk
```

规则:

```text
<detected system area>/<moduleNameSanitized>/<moduleNameSanitized>.apk
```

其中 `detected system area` 示例:

```text
/system/app
/system/priv-app
/system_ext/app
/system_ext/priv-app
/product/app
/product/priv-app
```

`moduleNameSanitized` 来自当前 Module 名，清理非法路径字符。不要从 `agent-server-debug.apk` 推导目录名，避免 debug/release 污染 system 路径。

## 本地 APK 选择

Push 功能复用当前 reinstall 的 APK 查找逻辑。

当前查找规则:

1. 优先扫描:

   ```text
   build/outputs/apk
   ```

2. 如果没有 APK，再扫描:

   ```text
   build/intermediates/apk
   ```

3. 排除 androidTest APK。
4. 按修改时间倒序。

因此如果同时存在:

```text
agent-server/build/intermediates/apk/debug/agent-server-debug.apk
agent-server/build/outputs/apk/debug/agent-server-debug.apk
```

实际优先使用:

```text
agent-server/build/outputs/apk/debug/agent-server-debug.apk
```

### UI 表现

```text
Local APK:
[ agent-server/build/outputs/apk/debug/agent-server-debug.apk      folder-button ]
```

规则:

- 只有 1 个 APK 时，显示普通路径控件，不显示下拉。
- 多个 APK 时，显示 ComboBox，下拉选择当前模块 APK。
- 右侧文件夹按钮始终存在，可选择任意本地 APK。
- 选择外部 APK 后，控件显示该 APK 路径。
- 展示优先使用相对项目路径，tooltip 显示完整路径。

## 设备目标路径选择

`Device Target` 是可编辑输入框，右侧也有文件夹按钮。

```text
Device Target:
[ /system/priv-app/agent-server/agent-server.apk                  folder-button ]
```

规则:

- 用户可以直接编辑完整设备路径。
- 右侧文件夹按钮打开设备路径选择器。
- 选择设备目录后，自动补全 module 目录和 apk 文件名。
- 选择设备 APK 文件后，直接使用该完整路径。
- 自动补全后，用户仍可手动删除或修改，最终完全尊重输入框内容。

### 自动补全规则

```text
if selectedPath endsWith ".apk":
    target = selectedPath
else if basename(selectedPath) == moduleName:
    target = selectedPath + "/" + moduleName + ".apk"
else:
    target = selectedPath + "/" + moduleName + "/" + moduleName + ".apk"
```

示例:

选择:

```text
/system/priv-app
```

自动填入:

```text
/system/priv-app/agent-server/agent-server.apk
```

选择:

```text
/system/priv-app/agent-server
```

自动填入:

```text
/system/priv-app/agent-server/agent-server.apk
```

选择:

```text
/system/priv-app/old/old.apk
```

直接填入:

```text
/system/priv-app/old/old.apk
```

## Push 弹窗

建议结构:

```text
Push System APK

Package:
com.lenovo.turboaim.leclawplus.agentserver

Local APK:
[ agent-server/build/outputs/apk/debug/agent-server-debug.apk      folder-button ]

Device Target:
[ /system/priv-app/agent-server/agent-server.apk                  folder-button ]

After push:
[x] Remove /data/app overlay
[x] Clear app data

[Push] [Cancel]
```

说明:

- `Remove /data/app overlay` 默认勾选。
- `Clear app data` 默认勾选。
- 如果后续确认 clear data 必须执行且不可关闭，可以将 checkbox 改为固定说明。

## Push 执行流程

每次点击 Push 都重新执行准备流程:

```text
adb root
adb remount
```

准备成功后执行:

```text
mkdir -p <target-dir>
adb push <local-apk> <target-path>.tmp
mv <target-path>.tmp <target-path>
chmod 644 <target-path>
chown root:root <target-path>
restorecon <target-path>
sync
```

如果存在 `/data/app` 覆盖层:

```text
adb uninstall <package>
```

然后清数据:

```text
pm clear <package>
```

注意:

- `adb uninstall <package>` 对 updated system app 的语义是移除 `/data/app` 覆盖安装层，恢复 system app。
- push system APK 后仍需要重启后 PackageManager 才稳定生效。
- clear data 失败应提示，但可以在结果里单独展示，不一定判定整个 push 失败。

## remount 失败与准备重启

设备首次执行 `adb root + adb remount` 后，可能要求重启后 remount 才真正生效。

如果 remount 失败，或者输出包含类似:

```text
reboot your device
verity is enabled
remount failed
Read-only file system
```

提示:

```text
System partition is not writable.
The device may require reboot after adb root/remount, then run Push again.

[Reboot Now] [Cancel]
```

规则:

- 点 `Reboot Now` 执行 `adb reboot`。
- 点 `Cancel` 不记录任何持久化状态。
- 下次再点 Push，仍然重新执行 `adb root + adb remount`。
- 如果第二次成功，则继续 push。
- 如果仍失败，则再次提示。

该重启属于 remount 准备重启，不能和 push 成功后的生效重启混在一起。

## Push 成功后的重启提示

Push 成功后提示:

```text
Push completed.
Reboot is required for the system app to take effect.

[Reboot Now] [Later]
```

用户选择:

- `Reboot Now`: 执行 `adb reboot`，清除当前设备 pending reboot 标记。
- `Later`: 记录当前设备 pending reboot 标记，并在主弹窗右上角显示 `Reboot Required`。

右上角位置建议放在设备栏最右侧:

```text
Device: [TB323FU (...)] [Refresh] [Show All Installed Apps]       [Reboot Required]
```

## 重启标记管理

pending reboot 标记只在 Android Studio 当前进程内保存，不写磁盘。

保存结构:

```text
serial -> bootIdAtPush
```

获取 boot id:

```text
cat /proc/sys/kernel/random/boot_id
```

检测时机:

- 打开弹窗。
- Refresh。
- 切换设备。
- push 成功后。
- 点击 `Reboot Required` 前。

判断规则:

- 当前设备 serial 没有 pending 标记: 不显示。
- 当前设备 serial 有 pending 标记，但 boot id 查询失败: 清除标记，不显示。
- 当前 boot id 和记录一致: 显示 `Reboot Required`。
- 当前 boot id 和记录不同: 说明设备已重启，清除标记，不显示。

换设备时:

- 因为 key 包含 `serial`，所以不会显示其他设备的标记。
- boot id 只用于判断同一台设备是否已经重启，不单独作为设备身份。

## 状态与错误提示

Push 过程建议分阶段更新 status:

```text
Preparing device...
Remounting system partition...
Pushing APK...
Applying permissions...
Removing data overlay...
Clearing app data...
Push completed, reboot required.
```

失败提示应包含:

- package name
- local APK path
- target path
- 当前步骤
- ADB 输出

示例:

```text
Push failed while remounting system partition.

Package: com.xxx
Target: /system/priv-app/agent-server/agent-server.apk

ADB output:
...
```

## 预计代码改动范围

### AppInstallInfo.kt

- 增加 system target 检测结果字段，或新增独立数据类承载检测结果。

### AdbService.kt

新增或增强:

```kotlin
getSystemApkTarget(...)
getBootId(...)
prepareRootRemount(...)
pushSystemApk(...)
listDeviceDir(...)
```

重点:

- 解析 `dumpsys package` 的 `Hidden system packages`。
- 判断 `/data/app` overlay。
- 封装 root/remount/push/chmod/chown/restorecon/sync。

### UninstallTableModel.kt

- Options 增加 `COL_PUSH`。
- `COLUMNS` 增加一列。
- editable/action 判断增加 Push。

### UninstallDialog.kt

- Options 表头跨度从 3 列改为 4 列。
- 增加 Push action renderer/editor。
- 增加 Push 弹窗。
- 增加设备路径选择器。
- 增加 pending reboot UI。
- 增加 boot id 检测与标记清理。

### resources/icons

- 增加 push enabled/disabled 图标。

## 推荐实施顺序

1. 实现 `dumpsys Hidden system packages` 解析和目标路径生成，尽量拆成纯函数便于验证。
2. 复用 `ApkFinder`，完成 Push 弹窗 UI。
3. 实现 `adb root/remount/push/chmod/chown/restorecon/sync` 流程。
4. 增加 data overlay 移除和 clear data。
5. 增加 push 成功后的 reboot pending 标记。
6. 增加右上角 `Reboot Required` 按钮和 boot id 自动清理。
7. 最后调整 Options 四列布局、图标和 tooltip。
