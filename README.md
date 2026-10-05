# 橙子课表（OrangeKeBiao）

一款纯本地、无广告、无账号的 Android 课表 App。基于 **Jetpack Compose + Material3 + Room** 构建，数据全部保存在设备本地，不联网、不上传。

- 包名：`com.dannr.chengzikb`
- 当前版本：`1.4.0`（versionCode `6`）
- 最低支持：Android 8.0（API 26） / 目标：Android 15（API 35）

## 功能特性

**课表**
- 多套课表并行管理，一键切换当前课表（如「我的课表」「考研表」）
- 周次视图：上/下周滑动、任意周跳转；单双周、不连续周次（如 `1-3,5,7-20`）均支持
- 课表网格按「星期 × 节次」排布，同一时段多门课自动分栏（Lane）不重叠
- 自定义作息时间：每节课起止时间、节次名称可编辑（默认大学作息：上午 4 节 / 下午 4 节 / 晚上 4 节）
- 学期设置：开学日期、总周数、显示天数（如只显示工作日）、显示地点/教师等

**课程**
- 新建课程或从已有课程中选一门排到空位
- 每门课支持简称（网格/小组件用小字显示）、教师、颜色
- 上课地点下放到「每一节安排」，同一门课不同时段可位于不同教室
- 点击网格中的课程块直接编辑，空位点击快速添加

**调休与节假日**
- 内置节假日调休方案：放假区间不上课、补课日按「周几」的课表上课
- 支持手动覆盖某一天的课表（放假 / 补课 / 临时调整）

**数据导入导出**
- 备份/恢复：一键导出**全部课表**（作息 + 学期设置 + 课程 + 安排）为单个 JSON 文件，恢复时单事务整库原子替换
- 导入 `.ics`：兼容 WakeUp 课程表导出的日历文件，自动合并同名课程的多段重复事件
- 备份格式带版本号，向前兼容旧版本（v1.1 / v1.3 / v1.4 均为可选项增量扩展，旧备份照常导入）

**桌面小组件**
- 标准 AOSP AppWidget（RemoteViews），实时显示「今日 / 下一有课日」的课程与下一节课提醒
- 跨 ROM 尺寸自适应：针对 OriginOS / MIUI / OneUI 等不同桌面各自的尺寸上报语义分别决策，绝不裁掉底部内容
- 自刷新闹钟驱动，系统事件（开机 / 覆盖安装 / 改时间 / 改时区）后自动重排

**课前提醒**
- 精确闹钟（`USE_EXACT_ALARM`）保证准点提醒，不因 Doze 被推迟
- 开机、覆盖升级、改时间/时区后自动重新排程

**外观与适配**
- 主题色：自定义种子色对整块暖橙调色板做**色相迁移**，卡片、蒙版、图标整体跟随
- 深色模式、HSV 取色器
- 国产 ROM 后台 / 自启动引导：逐厂商探测可跳转的设置页，引导用户放行自启动以保证提醒与小组件刷新

## 技术栈

| 项 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.12.01）+ Material3 |
| 导航 | Navigation Compose |
| 存储 | Room + KSP（纯本地数据库） |
| 异步 | Kotlin Coroutines / Flow |
| 构建 | AGP 8.7.3，Java 17，minSdk 26 / compileSdk 35 |
| 测试 | JUnit 纯 JVM 单测（`app/src/test`） |

## 项目结构

```
app/src/main/java/com/dannr/chengzikb/
├─ MainActivity.kt / OrangeApp.kt      # 入口、Application
├─ data/
│  ├─ db/            # Room：DAO、AppDatabase、首启播种（DatabaseSeeder）
│  ├─ model/         # Course / CourseSession / Timetable / WeekSet / DayOverride …
│  ├─ repo/          # 仓库层（课程、作息、设置、课表管理）
│  ├─ backup/        # JSON 备份 / 恢复（BackupManager，格式 v4）
│  ├─ import/        # .ics 导入（IcsImporter）
│  └─ notify/        # 课前提醒、开机重排程
├─ domain/           # 纯逻辑：周次计算、分栏布局、今日计划、小组件引擎、调休方案
├─ ui/
│  ├─ nav/           # AppNavHost 顶层导航
│  ├─ main/          # 主界面（课表 / 课程 / 设置 三 Tab）
│  ├─ grid/ day/     # 课表网格、课程块、单日视图
│  ├─ course/        # 课程编辑 / 选择
│  ├─ settings/      # 设置、作息编辑、学期设置、节假日、导入、关于
│  ├─ backup/        # 备份页
│  └─ theme/         # 主题（种子色色相迁移）与取色
├─ widget/           # 桌面小组件：Provider、尺寸策略、刷新任务
└─ util/             # OEM 后台引导等工具
```

`domain/` 与 `widget/WidgetSizePolicy.kt`、`util/OemGuide.kt` 的决策逻辑均为无 Android 依赖的纯函数，便于 JVM 单测。

## 构建与运行

项目**未包含 Gradle Wrapper**，请使用本机独立安装的 Gradle（示例为 8.13），并指定 Android SDK 路径。

```bash
# 编译 Debug 包
gradle :app:assembleDebug

# 出正式签名 Release 包
gradle :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 运行单元测试
gradle :app:testDebugUnitTest

# 安装到已连接设备（覆盖升级、保留数据）
adb install -r app/build/outputs/apk/release/app-release.apk
```

首次构建前请确认 `local.properties` 中的 `sdk.dir` 指向本机 Android SDK，且 JDK 17 已就绪。

### 签名

Release 签名从项目根目录的 `keystore.properties` 读取：

```properties
storeFile=keystore/chengzikb-release.jks
storePassword=********
keyAlias=chengzikb
keyPassword=********
```

该文件与 `keystore/` 目录已被 `.gitignore` 忽略，**不会也不应提交到仓库**。正式签名密钥一旦丢失，将无法再发布可覆盖升级的同签名版本，请务必自行离线备份。

若 `keystore.properties` 不存在，构建会自动退回 debug 签名，方便无密钥环境或他人克隆后照样出包（但装真机时与正式签名不一致，无法覆盖升级，需先卸载）。

## 数据与隐私

- App **不申请联网权限**，不含任何统计 / 广告 SDK。
- 所有课表数据仅存于本机 Room 数据库；导出备份文件由用户自行保存与分享。
- 申请的权限及用途：
  - `POST_NOTIFICATIONS`：发送课前提醒通知
  - `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`：准点触发课前提醒
  - `RECEIVE_BOOT_COMPLETED`：开机后重新排程提醒

## 图标资源

官网 / 宣传用图标统一存放于 `assets/icons/`（SVG 矢量），详见该目录下的说明文件。

## 许可

暂未指定开源许可证。
