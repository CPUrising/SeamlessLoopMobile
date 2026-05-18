# 任务交接文档

## 任务目标

修复 PC 数据库同步时 AB 式歌曲的 A 段无法匹配的问题。

**问题链路**：
1. PC 端 A 段的 `totalSamples = A_samples + B_samples`（合并值）
2. 手机端扫描后 A 段的 `totalSamples = duration * sampleRate / 1000`（估算值），但 OGG 采样率检测错误（返回 44.1kHz 而非真实 48kHz）
3. 错误采样率 → 错误 `totalSamples` → PC 导入三步匹配全部失败

## 已完成的工作

### AudioScanner.kt — detectSampleRate 函数

**当前版本**（已保存）：OGG 分支改用 header 内存数组直接寻址，零额外 I/O。

原理：`raf.read(header, 0, 8192)` 已经读到内存里了，`header[26]` = segCount，`pktStart = 27 + segCount`，所有魔数和采样率从 `header[pktStart + offset]` 读取。

**之前尝试过的方案**（均失败，返回 44.1kHz）：
1. `raf.seek(0L) → readFully(oggHdr) → readFully(segTbl) → readFully(pkt)` — packetData 数组方案，偏移量 Bug 已修复但结果仍不对
2. 当前 header 直接寻址方案 — **待测试**

### MusicScannerRepository.kt — AB 合并 totalSamples
未修改，保持 `getApproximateSamples()` 调用。

## 需要阅读的文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/cpu/seamlessloopmobile/scanner/AudioScanner.kt` | **主战场**，`detectSampleRate` 第 125-173 行 OGG 分支 |
| `app/src/main/java/com/cpu/seamlessloopmobile/data/MusicScannerRepository.kt` | AB 合并逻辑，第 92-102 行 |
| `app/src/main/java/com/cpu/seamlessloopmobile/db/PcDatabaseImporter.kt` | PC 导入匹配逻辑 |
| `app/src/main/cpp/AudioDecoder.cpp` | C++ 解码器，`getAudioFileDuration`/`getAudioFileSampleRate`（NDK MediaCodec） |

## 当前状态与问题

### 症状
- OGG 48kHz 文件在全新扫描后 `getApproximateSamples` 仍返回 44.1kHz 计算值
- 播放时 `NativeAudio.getSampleRate()`（C++ Oboe 引擎实时值）返回正确的 48kHz

### 可能的故障原因（需排查）

1. **`raf.read(header)` 返回值小于预期**：`raf.read(ByteArray(8192))` 可能不保证填满 8192 字节。如果 `bytesRead < pktStart + 16`，第 130 行的 `return@use 44100` 会提前退出

2. **OGG 文件第一个 page 的 segCount 为 0**：`header[26] = 0` → `pktStart = 27`，但实际第一个 packet 可能不在 offset 27。罕见情况，但需要验证

3. **测试文件不是 OGG Vorbis**：可能是 OGG Opus 或其他格式。Opus 分支的魔数检查 `"OpusHead"` 前 7 个字节已经写入，但之前偏移量有 Bug（已修复）

4. **缓存问题**：Gradle 配置缓存可能导致新代码未真正编译到 APK 中。尝试 `gradlew.bat clean assembleDebug` 或在 `gradle.properties` 中关闭 `org.gradle.configuration-cache=true`

5. **`insertOrUpdateSong` 双指纹匹配**：如果 DB 已有相同 fileName+duration 的记录，`SongDao.insertOrUpdateSong()` 会 UPDATE 而非 INSERT，旧 `totalSamples` 被保留

6. **DataColumn 路径问题**：Android 11+ 中 `MediaStore.Audio.Media.DATA` 可能返回空或不可用路径

### 推荐的排查步骤

#### 步骤 1：添加临时日志确认 detectSampleRate 返回值
在 `getApproximateSamples` 中的 sampleRate 获取后加一行 Log：
```kotlin
val sampleRate = detectSampleRate(file)
android.util.Log.d("AudioScanner", "detectSampleRate for $filePath = $sampleRate")
```

#### 步骤 2：确认 header 数组内容
在 OGG 分支开头加日志：
```kotlin
android.util.Log.d("AudioScanner", "OGG: bytesRead=$bytesRead segCount=${header[26].toInt()and 0xFF}")
```

#### 步骤 3：验证测试文件
用 hex 编辑器确认 OGG 文件前 42 字节的内容：
- `header[0..3]` = "OggS"？
- `header[26]` = segCount？
- `header[27 + segCount]` = packet_type（Vorbis = 1）？
- `header[27 + segCount + 1 .. +6]` = "vorbis"？

## 备选方案

如果 header 直接寻址仍然失败，建议切换到 **`getAccurateMetadata` 方案**：

在 `MusicScannerRepository.kt` 的 `abCombinedSamples` 计算中（第 97-98 行），把：
```kotlin
val aSamples = AudioScanner.getApproximateSamples(song.filePath, song.duration)
```
替换为：
```kotlin
val aSamples = AudioScanner.getAccurateMetadata(context, song.mediaId).first
```

`getAccurateMetadata` 通过 JNI 调用 C++ `AudioDecoder::open()`，依赖 NDK `MediaExtractor` + `MediaCodec`，能 100% 正确读取所有格式的采样率和帧数。`context` 在 `getInitialScannedSongs(context)` 作用域内已可用。

缺点是每次调用都要打开 native 解码器，但只对 AB 配对歌曲调用（通常个位数），性能可接受。
