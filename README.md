# SeamlessLoopMobile 🔄

Android 端高性能**无缝循环音频播放器**，采用 Kotlin + C++ (Oboe) 混合架构，专门为实现游戏音乐及各种循环音频的完美无缝衔接而设计。

现在支持 PC 端数据库导入与手机端数据导出为 PC 端可识别数据库；手机上的循环点、评分、歌单等修改可以通过“导出 PC 端数据库”传回电脑端使用。

当前界面参考了 NeriPlayer 的本地播放器体验，并结合本项目的无缝循环定位重新实现：底部主导航、毛玻璃迷你播放器、封面展示、独立搜索/设置/播放统计页面，以及统一的弹出式页面切换动画。

同时新增 GitHub 同步：可通过 GitHub Contents API 在单个 JSON 快照中同步歌单、循环点、评分和播放统计；自动同步默认关闭，用户开启后会在网络可用时由后台周期任务同步。

![a0e7472702aab23e61a4fb1f948aa075](./image/README/a0e7472702aab23e61a4fb1f948aa075.jpg)

---

## 📱 核心功能与亮点

- **高性能 Native 播放引擎**：基于 Google Oboe 1.9.3 + NDK minimp3 解码器，实现亚毫秒级别的低延迟无缝循环播放。
- **自动循环点探测引擎 (`loopfinder`)**：集成基于 FFT & Chroma 提取的高级音频算法，支持一键分析音频的最佳循环衔接点（提供 Top 5 候选推荐）。
- **A/B 式音乐支持**：独创支持由 Intro 段（A轨）和 Loop 段（B轨）组成的双轨歌曲无缝拼接播放，自动标记 B 轨并于 UI 中优雅过滤。
- **Room 3NF 数据持久化**：精心设计的 Room 2.7.0-alpha11 规范 3NF 数据库，支持双指纹去重（优先匹配 `FileName+Duration`，兜底 `FilePath` 匹配）。
- **现代播放器界面**：Compose + Material3 构建媒体库、搜索、设置、播放统计和全屏播放页；底部 `MiniPlayer` 支持 Haze 毛玻璃、封面、进度与常用播放控制。
- **真实收听时长统计**：只统计处于播放状态的墙钟收听时间，按累计时长排行；不统计播放次数和循环次数，文件缺失时仍保留历史记录。
- **GitHub 同步**：将歌单、循环点、评分和播放统计同步到 GitHub 仓库中的单个 JSON 文件；支持手动同步、数据摘要、来源设备管理、删除云端快照，以及默认关闭的 WorkManager 自动同步。
- **封面与音频格式展示**：扫描时写入封面 URI、MIME、采样率和码率，在列表、迷你播放器和播放页中统一展示。
- **主题与触感反馈**：支持跟随系统/浅色/深色主题偏好，并可在设置中开关按钮触感反馈。

---

## 🚀 用户使用指南

### 🎧 媒体库、搜索、设置与统计

- 底部导航提供 **媒体库 / 搜索 / 设置** 三个主入口。
- 媒体库右上角的统计入口可打开播放统计页，查看累计收听时长、Top 5 条形图和歌曲排行。
- 播放统计可在“设置 → GitHub 同步 → 数据管理 → 清理本机数据”中与歌单、循环点和评分一起选择清理。
- 搜索页不会自动弹出键盘，进入后可以直接浏览或手动输入关键词。

### 💻 导入 / 导出 PC 数据库
> [!TIP]
> **点击底部“设置”，进入“数据同步与管理”。**
> - **导入 PC 端数据库**：选择电脑端 `.db` 文件，APP 会自动进行容差匹配和增量批量写入，补充手机端没有的循环点、候选循环点、评分、歌单等数据。
> - **导出 PC 端数据库**：将手机端当前的歌曲元数据、循环点、候选循环点、评分、歌单与队列转换为 PC 端 3NF schema，可传回电脑端继续使用。

下面以微信传输助手为例讲解同步方法：

1.首先**下载**电脑端传输的db文件

![3ebc5a27a7cd3b6641e3182d7d9f0cb3](./image/README/3ebc5a27a7cd3b6641e3182d7d9f0cb3.jpg)

2.在播放器的设置界面寻找到db文件，如果一开始没有找到db文件，可以在文件夹的“下载”位置找到

![e05ae63c6360b5ffa2cac43a7409e50f](./image/README/e05ae63c6360b5ffa2cac43a7409e50f.jpg)

3.找到所需的db文件，按下即同步

![0431d448f108bd3314c14b89944bc93c](./image/README/0431d448f108bd3314c14b89944bc93c.jpg)

导出时在同一设置区域点击 **“导出 PC 端数据库”**，选择保存位置即可。导出的文件名默认为：

```text
seamless_loop_pc_export_yyyyMMdd_HHmmss.db
```

### ☁️ GitHub 同步

> [!TIP]
> **点击底部“设置”，进入“GitHub 同步”。**
1. 在 GitHub 创建一个 token，并只授予目标仓库的 Contents 读写权限。
2. 在 App 中填写并保存 Token、Owner、Repository、Branch 和同步文件 Path。默认 path 为 `seamless-loop/sync.json`。
3. 第一次使用时，若云端文件不存在，可点击 **立即同步**；也可在数据管理中执行“初始化云端”。云端文件已存在时，使用普通 **立即同步** 做双向合并，不会无条件用本机覆盖云端。
4. 可选开启 **自动同步**。任务默认关闭，网络可用时由 WorkManager 约每小时执行一次。
5. 在 **数据管理** 中查看本机/云端摘要，清理歌单、循环点、评分或播放统计，也可以按来源设备删除播放统计历史；删除云端快照前请确认仓库内容。

同步内容包括歌单、循环点、评分和播放统计。不会上传音频文件、播放队列/位置、封面与音频格式展示字段或 App 设置。循环点 `0/0` 与评分 `0` 表示未设置，不会清空已有实质值。

播放统计严格使用“规范化 basename + 精确时长”的线上身份，本地重新扫描时才按容差规则重绑定；绑定不会改写云端身份。完整规则见 [播放统计与 GitHub 同步 Schema V2](./docs/2026-07-12_播放统计与GitHub同步SchemaV2.md)。

如果旧文件或手工 JSON 导致 `schemaVersion`、`playbackStatistics`、日期或规范化文件名校验失败，请先备份并检查 JSON。schema 2 以外的快照不能直接同步；云端已存在时不要使用 seed，改用普通同步或先明确删除云端文件。





---

## 🛠️ 开发者快速上手

### 💻 运行环境
- **操作系统**：原生 Windows 11 (命令行推荐使用 PowerShell)。
- **编译依赖**：Min SDK 26, Target SDK 35, Gradle 9.1.0, Kotlin 2.1.0, Room KSP 启用，Compose compiler 插件启用。
- **主要 UI 依赖**：Jetpack Compose Material3、Haze 0.7.0、Coil 2.7.0。

### ⌨️ 常用开发命令
- **编译 Debug 包**：
  ```powershell
  .\gradlew.bat -q assembleDebug
  ```
- **运行单元测试**：
  ```powershell
  .\gradlew.bat -q testDebugUnitTest
  ```
- **运行特定测试（例如播放模式测试）**：
  ```powershell
  .\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.viewmodel.PlayModeTest"
  ```
- **一键调试部署 (需 Root 设备)**：
  运行根目录下的部署脚本：
  ```powershell
  .\run.bat
  ```

---

## 📖 深入架构与避坑说明
如果您是参与本项目的**智能体(AI)** 或 **人类开发者**，在进行任何实质性代码修改前，请**务必先阅读 [AGENTS.md](./AGENTS.md) 了解以下关键细节**：
1. **构建天坑**：`app/build.gradle.kts` 中 `kotlin-android` 插件保持注释；Compose compiler 插件不要删除。
2. **JNI / fopen 路径限制**：Native 音频分析无法直接读取 `content://`，必须先拷贝到私有 cache 目录。
3. **UI / Haze 层级**：页面内容是唯一 Haze source；底部 `MiniPlayer` 是上层 `hazeChild` sibling，不能放进 source 内。
4. **播放统计与 GitHub schema-v2**：统计按真实收听时长累计；线上身份、代际贡献和本地重绑定规则见 [权威文档](./docs/2026-07-12_播放统计与GitHub同步SchemaV2.md)。
5. **Room Schema**：Room 9 张实体表和 3 个 DAO 的详细映射图。
6. **包结构速查**：子模块（audio, data, db, viewmodel, model, scanner, jni 等）职责定义。
