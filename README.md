# DroidScreenTimer

Android 桌面时钟应用：全屏时间/农历/天气显示，呼吸灯背景，支持后台摄像头持续录像（分段保存）、多闹钟（中国公休日避响）、MJPEG 局域网实时画面推流与 HTTP 调试服务器。

- 包名：`com.droidscreentimer`
- minSdk 24 / targetSdk 34，纯 Java（无第三方依赖）
- 支持 armeabi-v7a / arm64-v8a / x86 / x86_64
- 调试环境：**仅真机**（当前设备 `ac5b007f`，小米 2109119BC，Android 13），不使用模拟器

## 生成方式、引用与参考资料

### AI 生成说明

本项目由 **CodeBuddy**（AI 编程助手）辅助生成与迭代开发，使用的模型为 **Tencent Hunyuan Hy**。功能实现、代码结构与文档均在 AI 辅助下完成，并结合真机调试结果做了修正。

### 开源项目与第三方脚本引用

- 应用运行时**未引入任何第三方开源库或第三方脚本**，源码为纯 Java，仅依赖 Android 平台 API（上文“无第三方依赖”即指此）。
- 构建期使用 Android Gradle Plugin 8.1.4、Gradle 8.2、JDK 17 与 Android SDK，各自的开源许可证（Apache-2.0、GPLv2+Classpath Exception 等）随工具分发；它们属于构建工具链，不作为应用源码引用。
- `LunarCalendar` 内置的 1900–2100 年农历压缩数据表 `LUNAR_INFO` 参考了中文开发者社区广泛流传的公开农历编码表，以常量形式自包含实现；未引入第三方农历库，原始数据表作者未在本项目中记录。

### 参考资料与出处

| 资料 | 用途 | 出处 |
|---|---|---|
| Open-Meteo 免费天气 API | 当前/明日天气、温度与 `weather_code` | https://open-meteo.com/ ，接口文档 https://open-meteo.com/en/docs |
| Open-Meteo Weather Code | `iconOf()` 晴雨 emoji 映射 | https://open-meteo.com/en/docs#weathercode |
| Android Developers：Camera2 / ImageReader | 相机抓帧与会话管理 | https://developer.android.com/reference/android/hardware/camera2 ，https://developer.android.com/reference/android/media/ImageReader |
| Android Developers：MediaCodec / MediaMuxer | H.264 手动编码与 MP4 封装 | https://developer.android.com/reference/android/media/MediaCodec ，https://developer.android.com/reference/android/media/MediaMuxer |
| Android Developers：AlarmManager / 前台服务 | 闹钟调度与后台录像保活 | https://developer.android.com/reference/android/app/AlarmManager ，https://developer.android.com/guide/components/foreground-services |
| 中国政府网《国务院办公厅关于 XXXX 年部分节假日安排的通知》 | `ChineseHoliday` 公休/调休数据（2025–2027） | 中国政府网 https://www.gov.cn/ ；2027 年数据依据“国办发明电〔2026〕10号” |
| Unicode Emoji | 天气图标使用 Unicode 字符，无图标库 | https://unicode.org/emoji/ |
| Android Gradle Plugin / Gradle 文档 | 构建配置 | https://developer.android.com/build ，https://gradle.org/ |

> 说明：上述资料均为公开可查资料；农历编码表属于社区通用公开数据，未追溯到单一原始出处，故按“社区流传的公开数据表”注明。

## 功能总览

| 模块 | 说明 |
|---|---|
| 主界面（竖屏） | 全屏时钟（时:分，可 12/24 制）、公历日期+星期、农历、今天/明天天气、电池状态 |
| 主界面（横屏） | 信息框分区布局：左上（宽 3/4、高 2/3）时间字号自动铺满；左下日期星期+农历折行；右列（宽 1/4、全高）温度在上、晴雨 emoji 图标在下 |
| 呼吸灯背景 | `FlowingBackgroundView` 单色相对角渐变，亮度按正弦呼吸（约 6s 周期）；录像开启时冷色（青蓝紫）明亮，关闭时暖色（红橙黄）偏暗；色相每 12s 随机漂移防烧屏 |
| 录像 | Camera2 抓帧 → MediaCodec H.264 → MediaMuxer MP4；按 1/3/5/10 分钟分段；剩余空间不足 2GB 自动删旧循环录入；后台由 camera 类型前台服务保活 |
| 录像参数 | 分辨率(320x240~1920x1080)/帧率(15/24/30/60)/码率(1/2/4/8Mbps)/音频/保存画面角度(0/90/180/270)/前/后置摄像头/保存目录(公共 Movies 或 SAF 自选) |
| 闹钟 | 多闹钟列表管理；周一~周日生效位掩码；中国公休日不响铃（2025~2027 数据表）；AlarmManager 精确调度、开机重排 |
| 系统设置 | 调试服务器与流媒体服务两个**独立 Web 服务器**、独立开关 |
| 调试预览 | 录像设置页内嵌实时预览（仅设置页显示），切换摄像头即时刷新 |
| 设置页交互 | 所有设置 Activity 跟随传感器方向（`fullSensor`）；顶部一排「返回 / 保存 / 保存并返回」按钮，保存=仅保存不退出，保存并返回=保存后关闭页面 |

## 代码结构（app/src/main/java/com/droidscreentimer/）

- `MainActivity`：主界面生命周期、横竖屏布局切换（`applyLayout()`）、时钟/天气刷新、录像启停、两个服务器的生命周期管理
- `CameraRecorder`（应用级单例）：录像核心。ImageReader(YUV_420_888) 抓帧 → MediaCodec(AVC) 编码 → MediaMuxer 写文件；支持前台预览/后台纯录像会话切换；`attachStreamSurface()` 供流媒体复用录像会话
- `CameraStream`（应用级单例）：MJPEG 推流帧源。录像空闲时自开相机，录像中复用录像会话输出面；YUV→NV21→JPEG（按录像朝向旋转）；**按客户端存在性与推流帧率跳过冗余编码**
- `StreamServer`：独立流媒体 Web 服务器（端口 8090 起），`/view` 观看页、`/stream` MJPEG 流
- `DebugServer`：独立调试 Web 服务器（端口 8080 起），`/status`、`/camera`、`/log`
- `CameraDebugPreview`：设置页调试预览（不编码、不写文件）
- `Prefs`：SharedPreferences 统一存取；`saveRecord()` 含全部录像参数
- `RecordService`：camera 类型前台服务（Android 10+ 需 `FOREGROUND_SERVICE_TYPE_CAMERA` 才允许后台持有相机）
- `AlarmItem / AlarmStore / AlarmScheduler / AlarmReceiver / AlarmSoundService / BootReceiver`：多闹钟体系
- `ChineseHoliday`：中国公休/调休上班数据表（2025~2027）
- `LunarCalendar / WeatherHelper`：农历与天气（Open-Meteo，无需 API Key；`iconOf()` 映射晴雨 emoji）
- `FlowingBackgroundView`：呼吸灯背景（冷暖两态、防烧屏）

## 构建与部署

```powershell
$env:JAVA_HOME='X:\CodeBuddy\DroidScreenTimer\.tools\jdk-17.0.2'
Set-Location 'X:\CodeBuddy\DroidScreenTimer'
& '.tools\gradle-8.2\bin\gradle.bat' --no-daemon assembleDebug
```

- Gradle 8.2 / AGP 8.1.4、JDK 17 已内置在 `.tools\`，无需全局安装
- Android SDK：`E:\AndroidSDK`（`local.properties`），由 `E:\cmdline-tools` 安装 `platforms;android-34` / `build-tools;34.0.0` / `platform-tools`
- 真机 adb：`E:\ADB_ToolboX\ADB\adb.exe`（设备 `ac5b007f`）
- **版本号自增**：根目录 `version.properties` 的 `VERSION_CODE`，每次执行 assemble/bundle/package 任务自动 +1（**构建失败也会 +1**，出现版本号跳号属正常现象）
- APK 产物：`app/build/outputs/apk/debug/app-debug.apk`（不再复制到应用宝下载目录）
- **传输约定**：`adb push` 到真机一律使用带版本号的文件名，目标为 `/sdcard/Download/`，例如：

```powershell
& 'E:\ADB_ToolboX\ADB\adb.exe' -s ac5b007f push 'app\build\outputs\apk\debug\app-debug.apk' '/sdcard/Download/DroidScreenTimer-v39.apk'
```

- MIUI 设备 adb 安装需在手机上开启开发者选项中的「USB 安装」，否则报 `INSTALL_FAILED_USER_RESTRICTED`；也可直接在手机文件管理中点击 Download 里的 APK 手动安装

## HTTP 接口

调试服务器（8080 起，`debug_server_on` 开关）：

| 端点 | 说明 |
|---|---|
| `/status` | 版本、配置、录像状态（frameCount/encodedFrameCount/muxerStarted/cameraId/cameraFacing）、推流状态、录像文件列表 |
| `/camera` | 各相机支持的流尺寸、硬件等级、视频编码器列表 |
| `/log` | logcat（`?pid=1` 只看本进程，`?tag=Xxx` 过滤） |

流媒体服务器（8090 起，`stream_server_on` 开关，与调试服务器独立）：

| 端点 | 说明 |
|---|---|
| `/view` | 网页观看页（浏览器直接打开） |
| `/stream` | MJPEG 原始流（VLC 等播放器也可用） |
| `/` | 推流状态文本 |

地址显示在 设置 → 系统，点击地址可直接在设备浏览器打开观看页。

## 关键技术经验

### 1. 手动编码链路（Camera2 → MediaCodec → MediaMuxer）

模拟器 LEGACY HAL 上 `MediaRecorder` 的 encoder surface 路径输出全黑帧（历史起因），改用手动链路后真机同样适用：

```
Camera2 → ImageReader(YUV_420_888) → 手动转 NV12 → MediaCodec(AVC) → MediaMuxer → MP4
```

要点：
- 选编码器用 `MediaCodecList(REGULAR_CODECS)` 按 `video/avc` 过滤
- 色彩格式优先 `COLOR_FormatYUV420SemiPlanar`，回退 Planar/Flexible
- 分辨率必须从 `StreamConfigurationMap` 里选（选面积最大且 ≤ 期望的，回退最小）
- 画面角度用 `MediaMuxer.setOrientationHint()` 写入容器，不改像素数据
- PTS 用首帧时间戳归零：`ptsUs = (ptsNs - firstFramePtsNs) / 1000`

### 2. 一台相机的三路复用（录像/推流/设置页预览）

Android 相机同一时刻只能被一个会话持有，多路输出的做法是把多个 Surface 加入同一次 `createCaptureSession`：

- 录像中：`frameReader`（编码）+ 可选 `previewSurface`（主界面预览）+ 可选 `streamReader`（推流），一个会话全带上
- 录像空闲时推流：`CameraStream` 自开相机，仅挂 `streamReader`
- 录像即将启动：`CameraRecorder.openCamera()` 先调 `CameraStream.releaseForRecorder()` 让出自开相机并改挂录像会话；录像停止后 `onRecorderIdle()` 自动恢复自开推流
- 设置页调试预览：`CameraDebugPreview` 录像空闲时自开相机；录像中被占用时改为 `CameraRecorder.attachPreview()` 复用录像会话
- 任何输出面变化都要 `rebuildSession()` 重建会话

### 3. MJPEG 流媒体（浏览器零依赖方案）

选 `multipart/x-mixed-replace`（MJPEG）而非 H.264/FLV：浏览器 `<img src="/stream">` 原生播放，无需 JS 解码器。实现要点：

- 服务端对每个连接循环写 `--boundary\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<jpeg>\r\n`
- 帧由 ImageReader 回调里"最新帧覆盖"（`acquireLatestImage`），HTTP 线程按帧率间隔取最新帧推送，慢客户端自然丢帧
- 5 秒无新帧断开连接，浏览器刷新重连
- YUV_420_888→NV21 要按 `rowStride/pixelStride` 逐行/逐像素拷贝，不能整块 memcpy
- **CPU 优化**：相机出帧率常高于推流帧率，且录像中复用会话时即使无客户端相机也在出帧。`CameraStream.onImageAvailable` 里无客户端或未到帧率间隔时直接 `acquireLatestImage + close` 丢帧，只有到期才做 YUV→JPEG 编码，高分辨率下 CPU 占用大幅下降

### 4. 后台录像的权限与保活

- Android 10+ 后台使用相机必须：前台服务 + `FOREGROUND_SERVICE_TYPE_CAMERA` 类型（Android 11+），且先于离开前台启动
- 应用切后台不停录像，只把预览面从会话中移除（`detachPreview`）
- `CAMERA` 权限拒绝时所有相机路径都要有友好降级（预览提示文本、`/status` 错误字段）

### 5. 多闹钟调度

- `AlarmItem` 用位掩码 `repeatDays`（bit0=周一…bit6=周日），`dayBit = 1 << ((Calendar.DAY_OF_WEEK + 5) % 7)`
- `AlarmScheduler.nextTrigger()` 在未来 8 天内找"星期匹配且非公休日"的触发点，`setExactAndAllowWhileIdle` + `requestCode=alarmId` 区分多个闹钟
- 单次闹钟（repeatDays==0）响过即自动禁用
- 公休日判断走 `ChineseHoliday.isRestday()`，内置数据表按年更新（见交接文档"更新节假日数据"章节）

### 6. 呼吸灯背景

- 单色相对角线性渐变（左上略亮、右下略暗），亮度按正弦呼吸，周期约 6 秒（100ms tick）
- 冷暖两态由 `setWarm(!isRecording)` 驱动：录像中冷色亮（value 0.32→0.92），空闲暖色暗（value 0.10→0.54）
- 防烧屏：色相在冷暖区间内每 12 秒随机漂移；信息框整体还有 ±5% 缓慢位移

### 7. 横竖屏布局切换（configChanges 场景）

- `MainActivity` 在 manifest 配置了 `configChanges="orientation|screenSize|..."`，旋转时 **Activity 不重建、布局不重新 inflate**
- 因此横竖屏两套布局（`activity_main.xml` / `activity_main_land.xml`）由代码在 `onConfigurationChanged` → `applyLayout()` 手动 `setContentView` 切换并重新绑定视图
- 横屏时间字号铺满：`fitTimeToFill()` 按 TextView 实际宽高与 `paint.measureText` 反算字号（宽取 96%、高取 82% 上限）
- 布局切换后需恢复：背景冷暖态、天气缓存（`applyWeatherViews()`）、重力翻转（`applyFlipState()`）

## 踩坑记录

1. **TextureView 不支持 `android:background`**：inflate 时直接抛 `UnsupportedOperationException` 导致崩溃。需要黑底时用外层 `FrameLayout` 包裹设置背景。
2. **自定义 LinearLayout style 忘记 `android:orientation="vertical"`**：LinearLayout 默认水平，match_parent 子控件被挤出屏幕，表现为"设置项消失"。样式里统一补上。
3. **`CameraCaptureSession.StateCallback` 只有 `onConfigured`/`onConfigureFailed`**：没有 `onDisconnected`/`onError`（那是 `CameraDevice.StateCallback` 的），写错签名编译报"不会覆盖或实现超类型的方法"。
4. **`ImageFormat` 在 `android.graphics` 包**，与 `android.media.ImageFormat` 不是同一个；`MediaCodecList` 需要显式 import。
5. **Camera2 流数上限**：LEGACY HAL 同时输出流数量有限，预览+编码+推流三路并存时若会话配置失败，优先减少输出面。
6. **构建失败也递增版本号**：调试期版本号可能跳号，以最后成功构建的 APK 为准。
7. **设置修改即时生效**：服务器开关在 `MainActivity.onResume()` 里同步（`syncDebugServerState`/`syncStreamServerState`），从设置页返回即生效，无需重启应用。
8. **Gradle 脚本里的中文路径会被读成乱码**（Windows 下脚本源码编码与 JVM 编码不一致）：`app/build.gradle` 中含中文的字符串一律用 `\uXXXX` Unicode 转义书写。
9. **MIUI 真机限制**：`adb install` 报 `INSTALL_FAILED_USER_RESTRICTED` 需在开发者选项开启「USB 安装」；`adb shell input`（点击/滑动注入）同样被安全策略拦截，无法用 adb 自动化操作界面。
10. **SDK 路径失效要重建**：`local.properties` 的 `sdk.dir` 指向的目录被移动/删除时构建直接失败；用 `E:\cmdline-tools` 的 `sdkmanager.bat --sdk_root=E:\AndroidSDK` 重装即可（需先接受 licenses，可直接写入 `E:\AndroidSDK\licenses\android-sdk-license` 哈希文件跳过交互）。

## 已知限制 / 待办

- MediaCodec 编码链路需在真机长时间运行验证稳定性（关注 `/status` 的 `encodedFrameCount` 与文件大小，预期 320x240@15fps@2Mbps 约 15MB/分钟）
- 中国公休日数据为静态表（2025~2027，2027 依据国办发明电〔2026〕10号），后续年份需按年更新
- 横屏布局中天气为"当前天气"（温度+图标），竖屏保留今天/明天文本双行
