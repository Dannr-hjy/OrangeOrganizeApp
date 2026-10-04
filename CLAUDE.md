# 橙子课表（OrangeKeBiao）项目说明

Android · Jetpack Compose 课表 App。包名 `com.dannr.chengzikb`。

**真实代码目录：`D:\Dannr\OrangeKeBiao`**（本文件所在目录）。

> ⚠️ `D:\Dannr\橙子课表` 是**旧 P0 骨架副本**（只有启动屏、无设置界面，代码很久没动）。一般不要改它。
> 若某次会话从那个中文目录启动，先按本文件把目标切到 `D:\Dannr\OrangeKeBiao` 再动手。

## 本机环境（这台 Windows 机器）
- 项目**没有 gradle wrapper**，PATH 上也没有 gradle → 使用独立安装的 Gradle：
  `C:\Users\Dannr\gradle\gradle-8.13\bin\gradle.bat`
- Android SDK：`C:\Users\Dannr\AppData\Local\Android\Sdk`
- adb：`C:\Users\Dannr\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- 已连接测试机：`10AF8D1YMB002E7`（vivo V2463A），USB 调试已开启，device 状态可安装。
- JDK 17（Temurin）已就绪。
- 编译/构建需在 OrangeKeBiao 目录下进行，命令统一加 `-p "D:\Dannr\OrangeKeBiao"`。

## 改动后的标准流程（用户明确要求）
每次改完代码，**自动出正式签名 release 包并安装到已连接手机**（覆盖升级、保留数据），不用再问：
```
cmd //c "C:\Users\Dannr\gradle\gradle-8.13\bin\gradle.bat" -p "D:\Dannr\OrangeKeBiao" :app:assembleRelease --console=plain
"C:\Users\Dannr\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "D:\Dannr\OrangeKeBiao\app\build\outputs\apk\release\app-release.apk"
```
git-bash 会把 `/sdcard/...` 这类参数改写成 Windows 路径；凡涉及 adb 的路径参数，先 `export MSYS_NO_PATHCONV=1`。

## 正式版 / 签名（⚠️ 重要，勿丢失）
- 正式签名 keystore：`D:\Dannr\OrangeKeBiao\keystore\chengzikb-release.jks`，别名 `chengzikb`
- 密钥 store/key 密码保存在同目录 `keystore.properties`。**务必备份这两个文件** —— 丢失后无法再发布可覆盖升级的同签名新版本。
- 出正式包：`:app:assembleRelease` → 产物 `app/build/outputs/apk/release/app-release.apk`
- 当前版本：`versionCode=5`、`versionName=1.3.0`；改版本号在 `app/build.gradle.kts`。
- **本机真机装机固定用此 release 包**（正式签名）：同签名可 `adb install -r` 覆盖升级、保留 app 数据。调试看 logcat，不再用 debug 包上真机。
- release 已配正式签名；若 `keystore.properties` 不存在会自动退回 debug 签名（便于无密钥/他人克隆仍能出包）。注意：退回 debug 签名后装真机因签名不同无法覆盖现有 release，需先卸载（会清数据），动手前先与用户确认。

## 技术速览
- Compose + Material3；导航在 `ui/nav/AppNavHost.kt`；主界面、设置、课表网格、课程编辑等分目录在 `ui/`。
- 主题色 = 自定义种子色对整块暖橙调色板做**色相迁移**，逻辑集中在 `ui/theme/Theme.kt`（`schemeFromSeed`/`rotateScheme`）。修"卡片/蒙版不跟主题色"等问题改那里。
- 技术栈：minSdk 26 / target & compile 35、AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.12.01、Room + KSP。
- 单测在 `app/src/test`（纯 JVM）；数据层首次会种示例课表（`DatabaseSeeder`）。
- 桌面小组件是**标准 AOSP AppWidget**（RemoteViews，无厂商私有 API）。跨 ROM 尺寸决策集中在 `widget/WidgetSizePolicy.kt`（OriginOS 保 168dp 地板、其余信上报且 MIN 优先、provider.min 兜底，绝不裁底）；OEM 后台/自启动引导在 `util/OemGuide.kt`。真机刷新的尺寸/行数会打 `logcat -s OrangeWidget`（仅在 logcat，应用内不再提供复制入口）。适配其它桌面时先看该日志的 `hpx/heightSource` 再调参。
