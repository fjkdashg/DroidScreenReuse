# DroidScreenUse 项目记忆与上下文

> 本文件保存本项目特定的记忆与约定。跨项目通用偏好、本机工具路径与全局行为规则保留在用户全局记忆中；功能说明见 `README.md`，接手指南见 `HANDOVER.md`。

## 项目身份

- 显示名称：**DroidScreenUse**（历史名称 DroidScreenTimer；GitHub 仓库名仍为 DroidScreenReuse）
- 包名：`com.droidscreentimer`（历史包名，未随显示名变更）
- 类型：Android 桌面时钟（时间/农历/天气/呼吸灯背景）+ 后台摄像头分段录像 + MJPEG 局域网推流 + HTTP 调试服务器 + 多闹钟（公休日避响）
- 技术栈：纯 Java，无第三方运行时依赖；minSdk 24 / targetSdk 34；Gradle 8.2 + AGP 8.1.4 + JDK 17（工具链内置于 `.tools/`）
- 仓库：`https://github.com/fjkdashg/DroidScreenReuse`，默认分支 `main`
- 调试设备：**仅真机**，小米 2109119BC（Android 13），adb 序号 `ac5b007f`，不使用模拟器

## 本机工具路径（本项目使用）

- 真机 adb：`E:\ADB_ToolboX\ADB\adb.exe`
- Android SDK：`E:\AndroidSDK`（`local.properties` 的 `sdk.dir`）
- cmdline-tools：`E:\cmdline-tools\bin\sdkmanager.bat`
- Git：`E:\Git\bin\git.exe`
- 项目内置：`X:\CodeBuddy\DroidScreenReuse\.tools\jdk-17.0.2`、`.tools\gradle-8.2`

## 构建与部署约定

- 构建命令：
  ```powershell
  $env:JAVA_HOME='X:\CodeBuddy\DroidScreenReuse\.tools\jdk-17.0.2'
  Set-Location 'X:\CodeBuddy\DroidScreenReuse'
  & '.tools\gradle-8.2\bin\gradle.bat' --no-daemon assembleDebug
  ```
- 版本号：`version.properties` 的 `VERSION_CODE` 每次执行 assemble/bundle/package 自动 +1（**构建失败也会 +1**，跳号属正常）
- APK 产物：`app/build/outputs/apk/debug/app-debug.apk`
- **不再复制 APK 到 `E:\腾讯应用宝文件管理\下载`**；`copyApkToAppbao` 复制任务已从 `app/build.gradle` 移除
- adb 推送约定：目标 `/sdcard/Download/`，文件名必须带版本号序号，例如：
  ```powershell
  & 'E:\ADB_ToolboX\ADB\adb.exe' -s ac5b007f push 'app\build\outputs\apk\debug\app-debug.apk' '/sdcard/Download/DroidScreenUse-v40.apk'
  ```
  不要放到 `/data/local/tmp`
- MIUI 限制：adb 安装需在开发者选项开启「USB 安装」，否则报 `INSTALL_FAILED_USER_RESTRICTED`；`adb shell input` 注入被安全策略拦截，UI 验证只能靠截屏或人工操作

## Git 与发版

- **未经用户明确要求，不执行 `git push` 或任何远端写操作**；本地 `init/add/commit` 也仅在任务需要或用户要求时进行
- **推送时必须包含项目记忆目录**：`.codebuddy/` 及其旗下文件（含本文件）属于项目主体，不得在 `.gitignore` 中排除，提交/推送时需确认已纳入版本控制
- 推送到 `main` 会触发 `.github/workflows/release.yml`：云端重新构建 debug APK → 创建/更新 tag `v<VERSION_CODE>` 的 Release → 上传 `DroidScreenUse-v<版本号>-debug.apk`（同名覆盖）→ 用提交记录生成版本说明
- 使用仓库内置 `GITHUB_TOKEN`（需 `contents: write`），无需额外密钥
- GitHub Actions 已在仓库启用；已验证成功发版（Release `v40`）

## 参考与致谢

- 本项目由 **CodeBuddy**（AI 编程助手，模型 **Tencent Hunyuan Hy**）辅助生成与迭代
- 运行时无第三方库/脚本；构建期使用 AGP、Gradle、JDK、Android SDK（各自开源许可证）
- 天气数据：Open-Meteo（`https://open-meteo.com/`）
- 节假日数据：中国政府网《国务院办公厅关于 XXXX 年部分节假日安排的通知》，2027 年依据国办发明电〔2026〕10号
- 农历：社区公开的 1900–2100 年编码表，以常量内置
- 完整出处表见 `README.md` 的“生成方式、引用与参考资料”

## 文档索引

- `README.md`：功能总览、构建部署、HTTP 接口、关键技术经验与踩坑记录
- `HANDOVER.md`：环境搭建、构建→推送→安装→验证闭环、代码导览、MIUI 限制、项目约定
