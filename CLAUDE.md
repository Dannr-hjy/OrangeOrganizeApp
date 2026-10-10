本项目所需的环境、依赖等文件全部装在D:/4Application/0Expansions，绝对不能装在C盘。

本项目开发过程中踩过的所有的有学习价值的坑都记录在本文档中。

## 本机环境（全在 D 盘，2026-10-10 装好并验证）

| 组件 | 位置 |
|---|---|
| JDK 17.0.20.1 (Temurin) | `D:/4Application/0Expansions/jdk-17` |
| Gradle 8.13 | `D:/4Application/0Expansions/gradle-8.13` |
| Android SDK（android-35 / build-tools 35.0.0 / platform-tools） | `D:/4Application/0Expansions/android-sdk` |
| Gradle 依赖缓存（GRADLE_USER_HOME） | `D:/4Application/0Expansions/gradle-home` |
| Robolectric 运行时 jar | `D:/4Application/0Expansions/robolectric-deps` |

- 用户级环境变量已写入：`JAVA_HOME`、`GRADLE_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME`、`ANDROID_USER_HOME`；PATH 已加 `jdk-17/bin`、`gradle-8.13/bin`、`android-sdk/platform-tools`、`android-sdk/cmdline-tools/latest/bin`。
- 本机加速/隔离脚本（**不在仓库内**，靠 GRADLE_USER_HOME 对所有构建生效）：`gradle-home/init.d/mirrors.init.gradle.kts`（阿里云 Maven 镜像，官方源留作兜底）、`gradle-home/init.d/robolectric.init.gradle.kts`（Robolectric 依赖目录指向 D 盘）。
- 项目没有 gradle wrapper，直接用 PATH 上的 `gradle`；命令统一加 `-p "D:\2PersonalFiles\OrangeOrganize\OrangeOrganizeApp"`。
- 测试机：OnePlus `PLZ110`（Android 16 / SDK 36），adb 已授权。
- 已验证：`:app:assembleDebug`、`:app:testDebugUnitTest`（142 用例全过）、`:app:assembleRelease` + 装机到真机。

## 签名与装机（硬规则）

**release 包一律用正式签名，禁止退回 debug 签名。** 因为 debug 签名的包与真机上已装的正式签名包冲突，装不进去，只能卸载重装 = 清空用户课表数据。

- 密钥：`keystore/chengzikb-release.jks`（别名 `chengzikb`），口令在同目录外的根目录 `keystore.properties`。
- 这两个文件都被 `.gitignore` 忽略 → **不在 git 里，必须单独备份**；丢了就再也发不出能覆盖升级的同签名新版本。
- 证书指纹 SHA-256：`37:7F:A3:EC:13:ED:7D:E4:5A:96:03:CB:EB:1A:11:BD:3A:13:9D:45:4B:DF:D2:0A:49:6A:65:52:AE:8F:D3:9C`
- `app/build.gradle.kts` 已加守卫：缺 `keystore.properties` 时 `:app:assembleRelease` **直接失败**并给出说明，不再静默退回 debug 签名；`:app:assembleDebug` 和单测在没有密钥的环境里照常能跑。

标准流程——**改完代码就自动走完这条，不用每次来问**（出正式签名包 → 覆盖升级到已连接真机 → 把版本号和装机结果报给用户）：
```bash
gradle -p "D:\2PersonalFiles\OrangeOrganize\OrangeOrganizeApp" :app:assembleRelease
adb install -r "D:/2PersonalFiles/OrangeOrganize/OrangeOrganizeApp/app/build/outputs/apk/release/app-release.apk"
```
装机后核验（`firstInstallTime` 不变 = 确实是覆盖升级、数据保留；用 `apksigner verify --print-certs <apk>` 可比对签名身份）：
```bash
adb shell dumpsys package com.dannr.chengzikb | grep -E "versionName|versionCode|firstInstallTime"
```

## 坑

### 1. 项目路径必须是纯 ASCII，否则全部单测崩掉
`gradle.properties` 里的 `org.gradle.jvmargs=-Dfile.encoding=UTF-8` 让 Gradle daemon 用 UTF-8 写测试 worker 的 classpath argfile，而 Java 启动器按系统 ANSI（本机 GBK）读该文件 → 中文路径 `D:\2个人文件\...` 解码错乱 → **全部用例**以 `ClassNotFoundException` + `initializationError` 失败（不是个别用例红，是整个测试任务全红）。
对照实验证实：同一 argfile 按 UTF-8 写入 → 类加载失败；按 GBK 写入 → 成功。
处理：项目父目录已改名为纯 ASCII 的 `D:/2PersonalFiles/OrangeOrganize/...`，此后 `org.gradle.jvmargs` 原样保留也不会再触发，**不需要**为它去改 `gradle.properties`。
（`gradle.properties` 里遗留的 `android.overridePathCheck=true` 是旧中文路径时代的产物，现为无害冗余。）

### 2. `C:\Users\phcx0\.android` 迁不到 D 盘（C 盘唯一残留，可接受）
adbkey（USB 调试授权密钥，**别删**，删了手机要重新授权）、`analytics.settings`、sdkmanager 下载缓存固定写在 `%USERPROFILE%\.android`，本机 platform-tools 的 adb **忽略 `ANDROID_USER_HOME`**，设了也照样落 C 盘。除此之外 gradle / .m2 / .kotlin / android-SDK / AVD 均无 C 盘残留。
（注意：`debug.keystore` 归 `ANDROID_USER_HOME` 管，AGP 用的是 `D:\4Application\0Expansions\android-home\debug.keystore`；C 盘那个早期生成的已于 2026-10-10 删除。）

### 3. `MSYS_NO_PATHCONV=1` 与 `cmd //c` 不能同时用
- git-bash 会把 `/sdcard/...`、`/data/...` 这类参数改写成 Windows 路径 → 凡涉及**设备内路径**的 adb 参数，先 `export MSYS_NO_PATHCONV=1`。
- 但设了它之后 `cmd //c ...` 就失效（`//c` 不再被转成 `/c`，cmd 会当成交互式启动并打出 banner 什么都不干）→ 跑 `apksigner.bat` 这类 cmd 调用时**不要**设该变量，两种命令分开跑。
- 设了 `MSYS_NO_PATHCONV=1` 后，`adb pull` 的目标路径要写 Windows 形式（`D:\...`）；写 `/tmp/x` 会被 adb 当成 `C:\tmp\x`（通常不存在）而失败。

### 4. release 构建失败会先把上一次的 APK 清掉
Gradle 执行打包任务前会清理该任务的输出目录，之后（守卫校验、签名校验等）才抛错 → 构建失败后 `app/build/outputs/apk/release/` 是**空的**，别以为旧包还在。

### 5. gradle 输出的中文在管道里看是乱码，但终端里正常
Gradle daemon 按本机 ANSI（GBK）输出中文。用 bash 管道 `| grep` 抓日志时会显示成乱码，那是管道按 UTF-8 解码造成的假象；`cmd`/PowerShell 窗口里显示正常。要核对内容可 `iconv -f GBK -t UTF-8`。

> 本文档已被 `.gitignore` 忽略（本机专用笔记，不进仓库）。仓库历史里 `9e75840` 之前那版 CLAUDE.md 含项目技术速览（主题色 `schemeFromSeed`/`rotateScheme`、小组件 `WidgetSizePolicy`/`OemGuide` 等），需要时用 `git show 9e75840:CLAUDE.md` 取回。


开发完成后省去测试环节，仅需通过adb安装新版包，测试由开发者自己完成。