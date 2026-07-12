package com.cpu.seamlessloopmobile.data.sync

/**
 * 播放列表合并策略。
 * resolve 方法根据具体规则合并远程与本地歌单，返回合并结果。
 */
interface PlaylistMergePolicy {
    /**
     * 合并远程和本地歌单。
     * @param remote 从后端下载的歌单快照
     * @param local 本地的歌单数据
     * @return 合并后的歌单
     */
    fun resolve(remote: SyncPlaylist, local: SyncPlaylist): SyncPlaylist
}

/**
 * 循环点合并策略。
 */
interface LoopPointMergePolicy {
    /**
     * 合并远程和本地的循环点数据。
     * @param remote 后端的循环点（可能为 null）
     * @param local 本地的循环点（可能为 null）
     * @return 合并后的循环点，若双方均为 null 则返回 null
     */
    fun resolve(remote: SyncLoopPoint?, local: SyncLoopPoint?): SyncLoopPoint?
}

/**
 * 评分合并策略。
 */
interface RatingMergePolicy {
    /**
     * 合并远程和本地的评分数据。
     * @param remote 后端的评分（可能为 null）
     * @param local 本地的评分（可能为 null）
     * @return 合并后的评分，若双方均为 null 则返回 null
     */
    fun resolve(remote: SyncRating?, local: SyncRating?): SyncRating?
}

// ---------------------------------------------------------------------------
// 默认实现
// ---------------------------------------------------------------------------

/**
 * 默认播放列表合并策略：
 * - 歌单元数据采用 Last-Writer-Wins（根据 modifiedAt）
 * - 歌单内的歌曲条目按 fileName(忽略大小写)+durationMs 去重合并；totalSamples 不参与去重
 * - 同一歌曲两端 totalSamples 不同时，尽量保留远端 SyncSongIdentity，避免跨端反复改 JSON
 */
object DefaultPlaylistMergePolicy : PlaylistMergePolicy {

    override fun resolve(remote: SyncPlaylist, local: SyncPlaylist): SyncPlaylist {
        val (winner, loser) = if (remote.modifiedAt >= local.modifiedAt) remote to local else local to remote
        val remoteItemsByKey = remote.items.associateBy { it.song.stableKey() }
        val mergedItems = mergeItems(winner.items, loser.items, remoteItemsByKey)
        return winner.copy(items = mergedItems)
    }

    private fun mergeItems(
        base: List<SyncPlaylistItem>,
        other: List<SyncPlaylistItem>,
        remoteItemsByKey: Map<SyncSongStableKey, SyncPlaylistItem>
    ): List<SyncPlaylistItem> {
        val result = base.map { item ->
            val remoteItem = remoteItemsByKey[item.song.stableKey()]
            if (remoteItem != null) item.copy(song = remoteItem.song) else item
        }.toMutableList()
        val seenIdentities = base.map { it.song.stableKey() }.toMutableSet()
        for (item in other) {
            val key = item.song.stableKey()
            if (seenIdentities.add(key)) {
                result.add(remoteItemsByKey[key] ?: item)
            }
        }
        return result.sortedBy { it.sortOrder }
    }
}

/**
 * 默认循环点合并策略：Last-Writer-Wins（根据 lastModified），
 * 附加保护规则：零值/未设置循环点不能覆盖有实质内容的循环点。
 *
 * - 如果只有一方有数据则采用该方数据
 * - 如果双方都有数据且都有效则采用 lastModified 更新的一方
 * - 保护规则：若 LWW 胜方为未设置（loopStart==0 && loopEnd==0），
 *   而负方有实质内容，则保留负方数据，防止意外清空
 * - 如果双方都为 null 则返回 null
 */
object DefaultLoopPointMergePolicy : LoopPointMergePolicy {

    override fun resolve(remote: SyncLoopPoint?, local: SyncLoopPoint?): SyncLoopPoint? {
        if (remote == null) return local
        if (local == null) return remote

        val remoteIsUnset = remote.loopStart == 0L && remote.loopEnd == 0L
        val localIsUnset = local.loopStart == 0L && local.loopEnd == 0L

        return when {
            // 保护规则：胜方未设置，负方有实质 → 保留负方
            remoteIsUnset && !localIsUnset -> local
            !remoteIsUnset && localIsUnset -> remote
            // 双方都未设置或都有实质 → LWW
            else -> if (remote.lastModified >= local.lastModified) remote else local
        }
    }
}

/**
 * 默认评分合并策略：Last-Writer-Wins（根据 lastModified），
 * 附加保护规则：评分 0 视为未设置，不能覆盖非零评分。
 *
 * - 如果只有一方有数据则采用该方数据
 * - 如果双方都有数据则采用 lastModified 更新的一方
 * - 保护规则：若 LWW 胜方评分为 0（未设置），而负方有非零评分，
 *   则保留负方数据
 * - 如果双方都为 null 则返回 null
 */
object DefaultRatingMergePolicy : RatingMergePolicy {

    override fun resolve(remote: SyncRating?, local: SyncRating?): SyncRating? {
        if (remote == null) return local
        if (local == null) return remote

        return when {
            // 保护规则：远程 0 未设置，本地有值 → 保留本地
            remote.rating == 0 && local.rating != 0 -> local
            // 保护规则：本地 0 未设置，远程有值 → 保留远程
            local.rating == 0 && remote.rating != 0 -> remote
            // 双方都未设置（0）或都有值 → LWW
            else -> if (remote.lastModified >= local.lastModified) remote else local
        }
    }
}
