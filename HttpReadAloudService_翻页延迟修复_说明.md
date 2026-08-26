# HttpReadAloudService 翻页/切章延迟修复说明

## 一、问题描述

在使用 `@js:` 前缀的复杂 TTS 配置（如「内置TTS插件」）作为朗读引擎时：

- 音频已提前缓存完毕的前提下；
- **手动翻页到下一页** 或 **当前章节朗读完毕自动切换到下一章** 时；
- 会出现 **2～4 秒的延迟** 才开始播放；
- 而使用简单 HTTP 配置（如 `K·TTS Server` 转发器）时，同样的操作几乎没有延迟；
- 从静止状态直接点「开始朗读」时，两者都能迅速开始播放。

> 补充说明：测试均在「未开启朗读播放状态」下进行，问题发生在翻页/切章触发重新 `play()` 的时机。

## 二、根因分析

问题出在 `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt` 的两个下载方法中：

- `downloadAndPlayAudios()`（普通模式）
- `downloadAndPlayAudiosStream()`（流式模式）

在这两处，预下载下一章的逻辑 `preDownloadAudios()` / `preDownloadAudiosStream()` **被放在了 `downloadTaskActiveLock.withLock { ... }` 锁块的内部**。

### 完整延迟链路

1. 朗读开始 → `downloadAndPlayAudios()` 获取 `downloadTaskActiveLock` 锁。
2. 当前章节音频已缓存 → 快速遍历完毕。
3. 调用 `preDownloadAudios()` 预下载下一章 → 遇到未缓存段落 →
   `getSpeakStreamResult()` → `httpTts.evalJS()` 执行 `@js:` 脚本。
4. `evalJS()` 是 **阻塞式 Java 调用**，不响应协程的 `cancel()`，锁一直被持有。
5. 用户翻页 / 切章 → `pageChanged = true` → `play()` → 再次 `downloadAndPlayAudios()`。
6. 旧任务 `downloadTask?.cancel()` 被调用，但旧任务卡在 `evalJS()` 中无法立即响应取消。
7. 新任务尝试 `downloadTaskActiveLock.withLock { ... }` → **锁被旧任务持有，被阻塞等待**。
8. 等 2～4 秒后 `evalJS()` 执行完毕 → 旧任务释放锁 → 新任务拿到锁 → 播放已缓存音频。

### 为什么简单 HTTP 配置不延迟

简单 HTTP 配置（如 `K·TTS Server`）不经过 `evalJS()`，而是通过 `AnalyzeUrl` 发起 HTTP 请求。OkHttp 请求可以被立即取消，锁会迅速释放，不存在阻塞。

### 为什么静止开始朗读不延迟

静止状态没有旧任务在运行，新任务可直接获取锁，不存在锁竞争。

## 三、修复方案

将 `preDownloadAudios()` / `preDownloadAudiosStream()` 从 `withLock` 锁块 **内部移到锁块外部**（仍在同一个 `execute` 协程内执行），使预下载不再持有锁。

### 修改前结构

```
downloadTask = execute {
    downloadTaskActiveLock.withLock {
        ensureActive()
        val httpTts = ...
        // 处理当前章节（缓存命中直接播放）
        preDownloadAudios(httpTts)   // ← 锁内！evalJS 阻塞时锁不释放
    }
}
```

### 修改后结构

```
downloadTask = execute {
    ensureActive()
    val httpTts = ...
    downloadTaskActiveLock.withLock {
        ensureActive()
        // 处理当前章节（缓存命中直接播放）
    }                              // ← 锁到这里已释放
    preDownloadAudios(httpTts)     // ← 锁外！即使 evalJS 阻塞也不影响新任务拿锁
}
```

流式版本同理，并将 `downloaderChannel.close()` 一并移出锁外。

### 效果

- 旧任务在后台执行 `evalJS()` 预下载时，锁早已释放；
- 翻页/切章触发的 `play()` 能 **立即获取锁**，直接播放已缓存音频，不再等待 2～4 秒；
- 旧任务完成 `evalJS()` 后通过 `ensureActive()` 检查，若已被取消会自动抛出 `CancellationException` 终止，不会产生残留任务。

## 四、改动文件清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/io/legado/app/service/HttpReadAloudService.kt` | `downloadAndPlayAudios()` 与 `downloadAndPlayAudiosStream()` 中将预下载移出 `withLock` 锁块 |

## 五、打包内容

压缩包 `HttpReadAloudService_翻页延迟修复.tar.gz` 包含：

- `HttpReadAloudService.kt` —— 修复后的完整源文件
- `HttpReadAloudService.diff` —— 相对于原始代码的 git diff（精确改动）

### 使用方法

将 `HttpReadAloudService.kt` 覆盖到工程中对应路径并重新编译即可（建议先对照 `.diff` 确认改动与本地其他修改无冲突）。
