# DroidScreenTimer 项目交接文档

> 本文档面向接手本项目的开发者，目标是让新人能在 30 分钟内搭好环境、完成一次"构建 → 推送 → 安装 → 验证"的完整闭环，并了解项目约定与历史坑点。功能与技术细节的完整说明见 `README.md`。

## 1. 项目概述

- **做什么**：Android 桌面时钟（全屏时间/农历/天气），同时是常驻摄像头录像设备（分段 MP4 循环录制），附带 MJPEG 局域网推流、HTTP 调试接口、多闹钟（公休日避响）。
- **技术栈**：纯 Java（无第三方依赖、无 Kotlin），minSdk 24 / targetSdk 34，Gradle 8.2 + AGP 8.1.4，JDK 17。
- **运行环境**：**只考虑真机**，本机不使用模拟器。当前调试真机为小米 2109119BC（Android 13），adb 序号 `ac5b007f`。

## 2. 环境与工具链（本机全局路径约定）

| 用途 | 路径 | 说明 |
|---|---|---|
| 项目根目录 | `X:\CodeBuddy\DroidScreenTimer` | 工作区 |
| JDK 17 | `X:\CodeBuddy\DroidScreenTimer\.tools\jdk-17.0.2` | 项目内置，勿装全局 JDK |
| Gradle 8.2 | `X:\CodeBuddy\DroidScreenTimer\.tools\gradle-8.2` | 项目内置 |
| Android SDK | `E:\AndroidSDK` | 由 cmdline-tools 安装；`local.properties` 的 `sdk.dir` 指向这里 |
| cmdline-tools | `E:\cmdline-tools` | `sdkmanager.bat`，装/补 SDK 组件用 |
| 真机 adb | `E:\ADB_ToolboX\ADB\adb.exe` | 非模拟器 adb，含 scrcpy 等工具 |
| Git | `E:\Git` | 项目当前**未建 git 仓库**，建议接手后先 `git init` |

> 旧文档/旧记忆中的 `X:\Programs_Ext\AndroidSDK`、`X:\DevProjects\...` 路径均已失效，以上表为准。

### 重建 SDK（如 `sdk.dir` 失效）

```powershell
$env:JAVA_HOME='X:\CodeBuddy\DroidScreenTimer\.tools\jdk-17.0.2'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'E:\cmdline-tools\bin\sdkmanager.bat' --sdk_root=E:\AndroidSDK 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools'
```

若卡在 license 交互，直接写入许可文件即可跳过：

```
E:\AndroidSDK\licenses\android-sdk-license
  8933bad161af4178b1185d1a37fbf41ea5269c55
  d56f5187479451eabf01fb78af6dfcb131a6481e
  24333f8a63b6825ea9c5514f83c2829b004d1fee
```

## 3. 日常开发流程

### 3.1 构建

```powershell
$env:JAVA_HOME='X:\CodeBuddy\DroidScreenTimer\.tools\jdk-17.0.2'
Set-Location 'X:\CodeBuddy\DroidScreenTimer'
& '.tools\gradle-8.2\bin\gradle.bat' --no-daemon assembleDebug
```

- 产物：`app\build\outputs\apk\debug\app-debug.apk`
- 版本号：`version.properties` 的 `VERSION_CODE` 每次构建 **自动 +1（构建失败也会 +1）**，跳号属正常，以最后成功构建为准
- **不要**把 APK 复制到 `E:\腾讯应用宝文件管理\下载`（该复制任务已从 `app/build.gradle` 移除）

### 3.2 推送到真机（约定：Download 目录 + 带版本号文件名）

```powershell
& 'E:\ADB_ToolboX\ADB\adb.exe' -s ac5b007f push 'app\build\outputs\apk\debug\app-debug.apk' '/sdcard/Download/DroidScreenTimer-v<N>.apk'
```

- 文件名必须带序号（版本号），如 `DroidScreenTimer-v39.apk`；推送前可 `adb shell rm -f '/sdcard/Download/DroidScreenTimer-v<N-1>.apk'` 清理旧版
- **不要**放到 `/data/local/tmp`

### 3.3 安装

两种方式：

1. adb 安装（需手机端一次性授权）：在手机开发者选项中开启「USB 安装」，然后：
   ```powershell
   & 'E:\ADB_ToolboX\ADB\adb.exe' -s ac5b007f install -r 'app\build\outputs\apk\debug\app-debug.apk'
   ```
   未授权时报 `INSTALL_FAILED_USER_RESTRICTED`（MIUI 安全策略，adb 侧无法绕过）。
2. 手动安装：手机文件管理 → Download → 点击 APK 安装。大多数情况下用这种最省事。

### 3.4 运行时验证

- 调试服务器：设置 → 系统 → 开启「调试服务器」，浏览器访问 `http://<手机IP>:8080/status`
- `/status` 关注：`recording`、`frameCount`、`encodedFrameCount`、`muxerStarted`、录像文件列表（预期 320x240@15fps@2Mbps 约 15MB/分钟）
- `/log?pid=1&tag=Xxx` 远程看本进程日志
- 推流：`stream_server_on` 开启后访问 `http://<手机IP>:8090/view`（浏览器直接看 MJPEG）
- 屏幕截图：`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`（用完删掉手机上的临时文件）

## 4. MIUI 真机调试注意事项（重要）

| 限制 | 现象 | 处理 |
|---|---|---|
| USB 安装确认 | `INSTALL_FAILED_USER_RESTRICTED` | 开发者选项开启「USB 安装」，或改手动安装 |
| 输入注入被禁 | `adb shell input tap/swipe` 抛 `INJECT_EVENTS` SecurityException | **无法**用 adb 自动化点界面；验证 UI 只能截屏或人工操作 |
| `WRITE_SECURE_SETTINGS` 不可用 | `settings put secure ...` 报权限拒绝 | 同上，只能在手机上手动改设置 |

## 5. 代码导览

```
app/src/main/java/com/droidscreentimer/
├─ MainActivity.java            # 主界面：横竖屏布局切换、时钟/天气、录像启停、两个服务器
├─ CameraRecorder.java          # 录像核心（应用级单例）：Camera2→MediaCodec→MediaMuxer，分段/空间清理
├─ CameraStream.java            # MJPEG 帧源（应用级单例）：自开相机/复用录像会话，含丢帧省 CPU 逻辑
├─ StreamServer.java            # 流媒体服务器 8090（/view /stream）
├─ DebugServer.java             # 调试服务器 8080（/status /camera /log）
├─ CameraDebugPreview.java      # 设置页调试预览
├─ RecordService.java           # camera 类型前台服务（后台保活）
├─ Prefs.java                   # SharedPreferences 统一存取
├─ AlarmItem/AlarmStore/        # 多闹钟体系
│  AlarmScheduler/AlarmReceiver/
│  AlarmSoundService/BootReceiver
├─ ChineseHoliday.java          # 公休日/调休数据表（2025~2027）
├─ LunarCalendar.java           # 农历
├─ WeatherHelper.java           # Open-Meteo 天气 + emoji 图标映射
└─ FlowingBackgroundView.java   # 呼吸灯背景（冷暖两态）
```

关键交互（改代码前必读）：

1. **相机三路复用**：同一时刻只有一个 CameraCaptureSession；推流/预览通过"附加输出面"挂进录像会话，输出面变化必须 `rebuildSession()`。改相机相关代码时注意 `releaseForRecorder()` / `onRecorderIdle()` / `attachStreamSurface()` / `detachStreamSurface()` 的时序。
2. **MJPEG 丢帧优化**：`CameraStream.onImageAvailable` 中无客户端或未到帧率间隔时直接丢弃帧（acquire+close），只有到期才编码 JPEG。调帧率/分辨率问题先看这里。
3. **横竖屏布局**：manifest 配置了 `configChanges`，旋转时 Activity 不重建，由 `MainActivity.applyLayout()` 手动 `setContentView` 切换 `activity_main.xml`（竖屏）与 `activity_main_land.xml`（横屏）。新增视图必须两套布局都考虑（或判空），并在 `applyLayout()` 重新绑定；切换后恢复背景冷暖、天气缓存、翻转状态。
4. **横屏时间铺满字号**：`fitTimeToFill()` 按视图实际宽高反算；其他文本用 `fitText()`（只缩不放）。
5. **设置页按钮语义**：顶部一排「返回 / 保存 / 保存并返回」——保存=持久化不退出；保存并返回=持久化+finish；返回=不保存退出。涉及 `DisplaySettingsActivity` / `RecordSettingsActivity` / `SystemSettingsActivity` 三个页面（闹钟页是即时保存的对话框，无此按钮）。

## 6. 当前状态（交接时点）

- 版本：`v39`（`version.properties` VERSION_CODE=39），已推送真机 `/sdcard/Download/DroidScreenTimer-v39.apk`
- 最近完成：
  - 公休日数据更新至 2027（依据中国政府网国办发明电〔2026〕10号）
  - MJPEG 高分辨率 CPU 优化（丢帧策略）
  - 背景改为呼吸灯效果（冷暖两态亮暗区间区分）
  - 横屏信息框分区布局 + 时间字号铺满 + 右侧温度/晴雨图标
  - 设置页顶部按钮排（返回/保存/保存并返回）、设置页 `fullSensor` 跟随传感器
  - 构建链修复：SDK 重建到 `E:\AndroidSDK`、Gradle 中文路径 Unicode 转义、移除应用宝复制任务
- 待办：
  - MediaCodec 编码链路真机长时间稳定性验证（`/status` 观察 encodedFrameCount 与文件大小）
  - 中国公休日数据按年更新（每年 11 月左右等国务院公告）
  - 建议接手后初始化 git 仓库（当前无版本控制）

## 7. 常见任务操作指南

### 7.1 更新年份公休日数据

1. 每年 11 月查中国政府网《国务院办公厅关于 XXXX 年部分节假日安排的通知》
2. 编辑 `ChineseHoliday.java` 的 static 块：`putRange(start, end, FLAG_OFF)` 标放假、`put(date, FLAG_WORK)` 标调休上班
3. 同步更新：类注释年份、`strings.xml` 的 `holiday_data_note`、`README.md` 待办说明
4. 构建推送验证：临时把手机日期改到目标年份，设置一个闹钟验证避响

### 7.2 修改主界面布局

- 竖屏：`res/layout/activity_main.xml`；横屏：`res/layout/activity_main_land.xml`
- 新增控件：两套布局同步加（或接受某侧为 null 并在 `MainActivity` 判空）、`applyLayout()` 绑定、文本更新走 `updateClock()`/`applyWeatherViews()`
- 信息框整体占屏 70% 并缓慢漂移（防烧屏），见 `setupInfoGroupBounds()` / `startMovement()`

### 7.3 调整背景呼吸灯

`FlowingBackgroundView.java`：
- 冷暖区间：`WARM_START/WARM_SPAN`（红橙黄 0–60°）、`COOL_START/COOL_SPAN`（青蓝紫 170–280°）
- 亮暗区间：`onDraw` 中 `value = warm ? 0.10f + 0.44f*wave : 0.32f + 0.60f*wave`
- 呼吸周期：ticker 里 `breathPhase += 1f/60f`（100ms tick → 约 6s）
- 色相漂移周期：`lastHueChange` 的 12000ms

### 7.4 排查录像问题

1. 手机上开启录像（设置 → 录像设置 → 启用录像）
2. 电脑访问 `http://<手机IP>:8080/status`，看 `lastError`、`frameCount`、`encodedFrameCount`、`muxerStarted`
3. 看 `/camera` 确认相机支持的尺寸与编码器
4. 日志：`/log?pid=1&tag=CameraRecorder`

## 8. 项目约定与偏好（务必遵守）

1. **仅真机调试**，不使用模拟器（包括应用宝模拟器）
2. **APK 不再复制**到 `E:\腾讯应用宝文件管理\下载`，构建产物就在 `app/build/outputs/apk/debug/`
3. **adb 推送 APK 到真机**：目标 `/sdcard/Download/`，文件名必须带版本号序号
4. 纯 Java、零第三方依赖；需要新能力优先用平台 API（如天气图标用 emoji 而非图标库）
5. 应用内 UI 文案全中文；`build.gradle` 中如出现含中文的路径常量，用 `\uXXXX` 转义（编码坑，见 README 踩坑 #8）
6. 信息类文本一律不折行自适应缩放（时间铺满例外）；这是桌面时钟应用，布局变化前先想"烧屏"与"夜间观感"

## 9. 风险与已知问题

- **无版本控制**：项目目前不在 git 仓库中，接手第一件事建议 `git init` 并提交基线
- **版本号跳号**：构建失败也会 +1，出现 v38→v39 这类跳号属正常
- **MediaCodec 编码输出未长期验证**：真机短时验证通过（frameCount/encodedFrameCount 增长、文件落盘），长时间运行稳定性待观察
- **MIUI 权限**：UI 自动化（input 注入）与 secure settings 均被禁，任何"自动化操作手机"的方案都要绕开这两点
- **横屏天气只显示当前天气**：温度+图标；如需恢复"今天/明天"双行，竖屏布局仍保留该实现可参考
