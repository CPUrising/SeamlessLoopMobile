# 任务交接文档

## 任务目标

修复 PC 数据库同步时 AB 式歌曲的 A 段无法匹配的问题。

**问题链路**：
- PC 端 A 段的 `totalSamples = A_samples + B_samples`（合并值）
- 手机端扫描后 A 段的 `totalSamples = A-only`（只含自身）
- PC 导入匹配时三步匹配全部失败 → A 段的循环点/评分/歌单无法同步

## 已完成的工作

### 修改 1: `MusicScannerRepository.kt` — AB 合并 totalSamples
扫描时检测 AB 配对，对 A 段的 `totalSamples` 设为 `A_samples + B_samples`（估算值）。

关键代码位置：`getInitialScannedSongs()` 中 Artist/Album 预创建之后，构建 `abCombinedSamples` 映射，在更新/插入路径中优先使用。

### 修改 2: `AudioScanner.kt` — OGG 采样率检测
目前历次尝试的结果不理想，需要接手的 AI 重新处理。

**当前状态**：最近一次尝试是用 packetData 数组方案重写了 OGG 分支（`detectSampleRate` 函数内），改用 `raf.seek(0L)` → 读完整第一页 packet → 内存数组解析 Vorbis/Opus/FLAC 三种编码的采样率。

**问题**：编译失败或结果不对（具体错误尚待排查）。

## 需要阅读的文件

主要文件：
- `app/src/main/java/com/cpu/seamlessloopmobile/scanner/AudioScanner.kt` — `detectSampleRate` 函数，OGG 分支当前有编译问题
- `app/src/main/java/com/cpu/seamlessloopmobile/data/MusicScannerRepository.kt` — AB 合并逻辑（已写好，可能需要调试）

参考文件：
- `app/src/main/java/com/cpu/seamlessloopmobile/db/PcDatabaseImporter.kt` — PC 导入匹配逻辑
- `app/src/main/java/com/cpu/seamlessloopmobile/model/SongDao.kt` — `getAllSongs` 过滤 IsAbPartB=0
- `app/src/main/java/com/cpu/seamlessloopmobile/audio/PlaybackManager.kt` — 运行时安全边界检查
- `app/src/main/java/com/cpu/seamlessloopmobile/jni/NativeAudio.kt` — `getAccurateMetadata` 无需添加依赖

## 需要完成的改动

### OGG 采样率检测（关键）

`AudioScanner.kt` 的 `detectSampleRate` 函数中 OGG 分支当前崩溃/不正确。

**要求**：
- 能正确处理 `segCount > 1` 的情况（三个 Vorbis header 合在第一页）
- 能正确处理 Vorbis、Opus、FLAC in OGG 三种编码
- 只读文件头（不需要完整解码）
- 不依赖第三方库（如 Media3/ExoPlayer）

**可选的实现方案**（按推荐顺序）：

1. **`getAccurateMetadata` 方案（最推荐）** — 放弃手写 OGG 解析，直接用已有的 C++ 接口 `NativeAudio.getAudioFileDuration()`。这绕过了所有 OGG 解析问题，直接拿到精确帧数。虽然开销略大（打开 fd + native 解码），但只在 AB 歌曲计算时调用，数量极少。

2. **packetData 内存数组方案** — 修复当前编译错误，继续用 `RandomAccessFile` 读完整第一页 packet 到内存数组再解析。

3. **混合方案** — 先快速读 Vorbis identification header（假设是 Vorbis），读到的采样率不合理则降级到 `getAccurateMetadata`。

### 验证链路

修复后需要验证的完整链路：
1. OGG 48kHz 正确识别
2. `getApproximateSamples(A) + getApproximateSamples(B)` ≈ PC exact A+B
3. `abCombinedSamples` 写入 A+B 估算值到数据库
4. PC 导入 Stage 4.2：容差匹配（±10000）成功
5. PC 导入 Stage 4.3：PC 精确 A+B 覆盖估算值
6. PC 导入 Stage 4.4：精确匹配成功 → 歌单添加 A 段

## 当前状态

- **`MusicScannerRepository.kt`**：已修改，AB 合并逻辑已完成，可能需要测试验证
- **`AudioScanner.kt`**：OGG 分支被多次重写（`segCount==1` 修复 → `RandomAccessFile` 跳读 → packetData 数组方案），当前版本有编译/运行问题
- **`FineTuneComponents.kt`** 和 **`PlayingPanel.kt`**：有未提交的预存改动，与本任务无关
- 所有改动已保存但未提交（除了一个之前已提交的 commit）
- `git status` 显示当前有 3 个文件变更
