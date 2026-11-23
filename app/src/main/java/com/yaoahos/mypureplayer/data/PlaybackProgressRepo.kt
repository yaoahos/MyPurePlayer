package com.yaoahos.mypureplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 一条播放进度。
 *
 * [key] 有两种形态，互不干扰：
 *  - **原文件绝对路径**：视频被移入回收站 / 保险箱时，历史记录要按用户要求摘掉；
 *    但在回收站/保险箱里看过的进度不能丢，于是先按原路径存这里，恢复时再连同历史一起放回去。
 *  - **`vault:<id>`**：保险箱内的播放进度。保险箱内容永远不进历史记录，
 *    却必须能续播 —— 这条路径就是给它用的。
 *
 * 与 [HistoryEntry] 分开存是刻意的：历史页只读 history.json，
 * 保险箱/暂存的进度根本不会出现在那个文件里，"保险箱视频出现在历史里"这一类
 * 泄漏在数据层就不可能发生，不依赖界面记得过滤。
 */
@Serializable
data class PlaybackMark(
    val key: String,
    val positionMs: Long,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
)

class PlaybackProgressRepo(context: Context) {

    private val store = JsonStore(
        File(context.filesDir, "playback_marks.json"),
        ListSerializer(PlaybackMark.serializer()),
    ) { emptyList() }

    private val rw = Mutex()

    private val _marks = MutableStateFlow<List<PlaybackMark>>(emptyList())

    /** 是否已从磁盘载入。写操作前必须为 true，否则会把整张表覆盖成一条 */
    @Volatile
    private var loaded = false

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        _marks.value = store.read()
            .filter { it.positionMs > 0L }
            .sortedByDescending { it.updatedAt }
        loaded = true
    }

    suspend fun load() {
        rw.withLock {
            loaded = false
            ensureLoadedLocked()
        }
    }

    fun find(key: String): PlaybackMark? = _marks.value.firstOrNull { it.key == key }

    fun positionOf(key: String): Long = find(key)?.positionMs ?: 0L

    fun durationOf(key: String): Long = find(key)?.durationMs ?: 0L

    /**
     * 记一条进度。
     *
     * [force] 用于暂停 / 切后台 / 换片这些"必须落盘"的时机；
     * 普通轮询（false）在位置只挪了不到 3 秒时不重复写盘 —— 与历史同一个节流口径。
     */
    suspend fun record(key: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (key.isBlank() || positionMs < MIN_POSITION_MS) return
        rw.withLock {
            ensureLoadedLocked()
            val previous = find(key)
            if (!force && previous != null &&
                kotlin.math.abs(previous.positionMs - positionMs) < 3_000L
            ) {
                return@withLock
            }
            if (previous != null && previous.positionMs == positionMs &&
                previous.durationMs == durationMs
            ) {
                return@withLock
            }
            val next = (_marks.value.filterNot { it.key == key } +
                PlaybackMark(key, positionMs, durationMs, System.currentTimeMillis()))
                .sortedByDescending { it.updatedAt }
                .take(MAX_MARKS)
            store.write(next)
            _marks.value = next
        }
    }

    /** vault:<id> → 原路径：移出保险箱时把进度挂到恢复出来的文件上 */
    suspend fun rename(from: String, to: String) {
        if (from == to) return
        rw.withLock {
            ensureLoadedLocked()
            val mark = find(from) ?: return@withLock
            val next = (_marks.value.filterNot { it.key == from || it.key == to } +
                mark.copy(key = to, updatedAt = System.currentTimeMillis()))
                .sortedByDescending { it.updatedAt }
            store.write(next)
            _marks.value = next
        }
    }

    suspend fun drop(key: String) = dropAll(listOf(key))

    suspend fun dropAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        rw.withLock {
            ensureLoadedLocked()
            val snapshot = _marks.value
            val next = snapshot.filterNot { it.key in keys }
            if (next.size == snapshot.size) return@withLock
            store.write(next)
            _marks.value = next
        }
    }

    suspend fun clear() {
        rw.withLock {
            loaded = true
            store.write(emptyList())
            _marks.value = emptyList()
        }
    }

    companion object {
        /** 与播放器写历史的门槛一致：看不够 3 秒不留进度 */
        const val MIN_POSITION_MS = 3_000L

        /** 保险箱条目的播放 id 前缀（见 VaultRepo.entryToVideoItem） */
        const val VAULT_PREFIX = "vault:"

        fun vaultKey(entryId: Long): String = "$VAULT_PREFIX$entryId"

        private const val MAX_MARKS = 600
    }
}
