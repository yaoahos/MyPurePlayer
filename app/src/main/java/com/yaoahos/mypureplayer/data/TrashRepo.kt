package com.yaoahos.mypureplayer.data

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.yaoahos.mypureplayer.media.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 回收站：删除的本地视频先移动到应用自己的外部目录（不占媒体库，卸载即清理），
 * 只有「回收站内再次删除」才会真正抹掉数据。
 *
 * 落盘形式（1.0.5 起）：
 * - 目录是 `Android/media/<包名>/.trash`（点开头，文件管理器默认不显示）；
 * - 数据文件名随机化，后缀统一 `.trash`，不再保留原文件名与扩展名；
 * - 旁边一份同名 `.meta`（Base64 包着 JSON），记原始路径、原文件名、大小、时长等；
 * - `trash.json` 仍是列表缓存，但即使它丢了（清应用数据、写入被中断），
 *   只要 `.trash` 与 `.meta` 还在，[load] 会把它们重新认领回来。
 *
 * 兼容：旧版本用的 `trash`（无点）目录与 `时间戳_原名.mp4` 命名仍会被读取和认领，
 * 见 [recoverRoots] 与 [adoptLegacy]。
 */
class TrashRepo(
    private val context: Context,
    /**
     * 文件被放回原处之后回调「恢复出来的绝对路径」。
     *
     * 恢复后需要在历史里重新记一条（用户要求），而历史写入依赖媒体库的新 id，
     * 只能由掌握全部恢复路径的这一层统一上报，界面不必在每个恢复入口各补一次。
     */
    private val onRestored: suspend (List<String>) -> Unit = {},
) {

    /**
     * 旧版回收站目录（应用私有）。
     *
     * 现在不再往这里放新文件，但历史版本删进回收站的条目还躺在这儿，
     * 读写都要兼容，否则升级后回收站里的东西会「全部消失」。
     */
    private val legacyRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "trash")

    /**
     * 回收站根目录：**必须与源文件同卷**，且目录名以点开头。
     *
     * 老实现固定放在应用私有的 `/Android/data/<包名>/files/trash`，而
     * `/storage/emulated/0/Android/data` 是单独挂载点（与 `/storage/emulated/0`
     * 不同设备号）。跨挂载点 `rename` 只会返回 EXDEV，代码只好退化成整文件复制 ——
     * 一个几 GB 的视频要几十秒，用户感受就是「删除特别慢」。
     *
     * 改成放在源文件所在卷的 `/Android/media/<包名>/.trash`：它和公共媒体目录在
     * 同一个挂载点上，rename 命中即瞬间完成。`Android/media` 本身在 Android 11+
     * 对文件管理器可见，点开头的目录默认不显示，用户不会在「刚删完一个视频」时
     * 顺手看到一个显眼的 trash 目录。
     *
     * `.nomedia` 仍然要写：目录隐藏不等于媒体库不扫，两重保险最稳。
     */
    private fun rootFor(sourcePath: String): File {
        val volume = volumeRootOf(sourcePath) ?: return legacyRoot.also { it.mkdirs() }
        val dir = File(volume, "Android/media/${context.packageName}/$DATA_DIR")
        if (!dir.isDirectory && !dir.mkdirs()) return legacyRoot.also { it.mkdirs() }
        val noMedia = File(dir, NOMEDIA)
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return dir
    }

    /** 从绝对路径取所在存储卷的根：/storage/emulated/0、/storage/XXXX-XXXX */
    private fun volumeRootOf(path: String): File? {
        val segs = path.trim().split('/').filter { it.isNotEmpty() }
        if (segs.size < 3 || segs[0] != "storage") return null
        val root = if (segs[1] == "emulated") File("/storage/emulated/${segs[2]}") else File("/storage/${segs[1]}")
        return root.takeIf { it.isDirectory }
    }

    private val store = JsonStore(
        File(context.filesDir, "trash.json"),
        ListSerializer(TrashEntry.serializer()),
    ) { emptyList() }

    private val _entries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val entries: StateFlow<List<TrashEntry>> = _entries.asStateFlow()

    private val mutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val secureRandom = SecureRandom()

    /**
     * 条目 id 的发号器。
     *
     * 原来直接用 `System.currentTimeMillis()`：毫秒只有 1 位分辨率，一次批量删除里
     * 相邻两个文件（同卷 rename 只要几毫秒）就可能拿到同一个 id。撞了之后
     * `items(key = { it.id })` 会让 Compose 直接抛「Key was already used」，
     * 多选勾选会联动，`removeEntry` 也会一次删掉两条记录。
     *
     * 这里保证单调递增：同一毫秒内连续取号也能拿到不同的值；[load] 会把基线抬到
     * 已有条目之上，历史数据里的重复 id 由 [ensureUniqueIds] 重新分配。
     */
    private val lastId = AtomicLong(0)

    private fun nextId(): Long {
        while (true) {
            val prev = lastId.get()
            val next = maxOf(System.currentTimeMillis(), prev + 1)
            if (lastId.compareAndSet(prev, next)) return next
        }
    }

    /**
     * 回收站的落盘形式：`<随机串>.trash` + `<随机串>.meta`。
     *
     * 删除后不再保留原文件名与扩展名。目的不是加密（内容仍是明文，按内容嗅探照样能识别），
     * 而是让文件名不泄露内容，也不被按扩展名归类的相册 / 清理软件当成视频收录。
     * 播放、详情、还原全部依赖 meta 与 trash.json，两者都不依赖物理文件名。
     */
    private fun randomBase(): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(12)
        bytes.forEach { sb.append((it.toInt() and 0xff).toString(16).padStart(2, '0')) }
        return sb.toString()
    }

    /** 生成一个不冲突的随机数据文件（后缀固定 .trash） */
    private fun newDataFile(bucket: File): File {
        repeat(16) {
            val candidate = File(bucket, "${randomBase()}.$DATA_SUFFIX")
            if (!candidate.exists() && !metaOf(candidate).exists()) return candidate
        }
        return File(bucket, "${UUID.randomUUID().toString().replace("-", "")}.$DATA_SUFFIX")
    }

    /** 数据文件对应的 meta 路径：同目录、同随机串、后缀换成 .meta */
    private fun metaOf(data: File): File =
        File(data.parentFile, "${data.nameWithoutExtension}.$META_SUFFIX")

    /** 条目的 meta 路径：文件夹条目把 meta 放在目录内部 */
    private fun metaOf(entry: TrashEntry): File {
        val data = fileOf(entry)
        return if (data.isDirectory) File(data, FOLDER_META) else metaOf(data)
    }

    /**
     * 把条目信息写到数据文件旁边；写失败不影响删除流程，还原时还能退回 trash.json。
     *
     * 内容是 Base64 包着的 JSON：数据文件名已经随机化了，meta 里再明写
     * `"fileName":"第一集.mp4"` 等于把刚藏起来的信息又摊开。这不是加密（能 base64
     * 解码就能读），只是不让文件管理器里随手一点就看到片名。
     */
    private fun writeMeta(entry: TrashEntry) {
        val file = metaOf(entry)
        runCatching {
            file.parentFile?.mkdirs()
            val raw = json.encodeToString(TrashEntry.serializer(), entry)
            file.writeText(Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }.onFailure {
            android.util.Log.w("TrashRepo", "写 meta 失败：${file.absolutePath}", it)
        }
    }

    /** 读数据文件旁边的 meta；没有或损坏返回 null，调用方退回 trash.json 里的条目 */
    private fun readMeta(data: File): TrashEntry? {
        val file = if (data.isDirectory) File(data, FOLDER_META) else metaOf(data)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText().trim()
            // 兼容直接写明文 JSON 的 meta（1.0.5 开发期落下的数据），靠首字符区分
            val raw = if (text.startsWith("{")) {
                text
            } else {
                String(Base64.decode(text, Base64.NO_WRAP), Charsets.UTF_8)
            }
            json.decodeFromString(TrashEntry.serializer(), raw)
        }.getOrNull()
    }

    suspend fun load() {
        val stored = store.read()
        val merged = withContext(Dispatchers.IO) {
            val alive = stored.filter { isReachable(it) }
            // 旧版可见目录（`Android/media/<包名>/trash`）里的条目顺手搬进 `.trash`。
            // 只做同卷 rename，搬不动就留在原地，条目照样能用。
            val migrated = alive.map { migrateLegacyLocation(it, fileOf(it)) }
            // 1.0.7 起回收站里不再有「文件夹」这一种条目：删除文件夹时逐个视频入库。
            // 历史上落下的目录条目在这里就地拆成一条条视频记录（见 flattenFolders）。
            // 拆出来的记录已经落在 `.trash` 根下，不需要再过一遍 legacy 迁移。
            val flattened = flattenFolders(migrated)
            val all = (recoverOrphans(flattened) + flattened)
                .distinctBy { fileOf(it).absolutePath }
                .sortedByDescending { it.deletedAt }
            ensureUniqueIds(all)
        }
        // 重建结果与落盘内容不一致才回写（认领/自愈/迁移都会改它）。
        // 走 writeIndex 而不是直接 store.write：load 是页面进入时的第一跳，
        // 这里抛异常同样会闪退，而索引写不进去下次还能重建。
        if (merged != stored) writeIndex(merged) else _entries.value = merged
    }

    /**
     * 把旧的「文件夹条目」拆成一条条视频记录。
     *
     * 删除文件夹的语义已经改成「文件夹里的视频逐个进回收站、各自记原路径」，
     * 这样回收站列表只有视频、不用担心一条记录代表一堆文件；恢复时每个视频回到自己
     * 原来所在的位置（原目录不存在会重建）。旧数据里的目录条目必须在这里展开，
     * 否则升级之后回收站里还会冒出几个文件夹，跟新语义打架。
     *
     * 顺带把目录里的数据文件搬回 `.trash` 根下（同卷 rename，瞬间完成）：
     * 目录一旦空了就删掉，回收站的落盘结构从此只有「一层扁平文件 + 各自的 meta」，
     * 认领逻辑（[recoverOrphans]）不用再区分目录内外的条目。
     */
    private fun flattenFolders(list: List<TrashEntry>): List<TrashEntry> {
        val out = mutableListOf<TrashEntry>()
        list.forEach { entry ->
            if (!entry.isFolder) {
                out += entry
                return@forEach
            }
            val bucket = fileOf(entry)
            if (!bucket.isDirectory) {
                // 目录不在：外置卡被拔掉时条目要留着（插回来还在），
                // 内置存储上不存在就是真没了，交给 isReachable 那一步过滤。
                if (volumeMissing(bucket)) out += entry
                return@forEach
            }
            // listFiles 返回 null（权限不足、IO 抖动）与"目录里没有数据文件"长得一模一样，
            // 但后果天差地别：前者按空目录删掉就是丢文件。读不到就整条保留，下次 load 再拆。
            val listing = bucket.listFiles()
            if (listing == null) {
                out += entry
                return@forEach
            }
            val files = listing.filter { it.isFile && it.extension.equals(DATA_SUFFIX, true) }
            if (files.isEmpty()) {
                // 确实没有数据文件：只清目录级索引，空壳才删 ——
                // 不用 deleteRecursively，万一目录里还剩别的文件也不该被这次迁移抹掉
                runCatching { File(bucket, FOLDER_META).delete() }
                runCatching { if (bucket.listFiles().orEmpty().isEmpty()) bucket.delete() }
                return@forEach
            }
            files.forEach { f ->
                // 每个视频自己的 meta 里就是它最初的文件名与原路径，用它重建记录
                val meta = readMeta(f)
                val flat = migrateOutOf(f)
                val id = nextId()
                val record = TrashEntry(
                    id = id,
                    fileName = meta?.fileName?.takeIf { it.isNotBlank() } ?: "恢复的视频_$id",
                    originalPath = meta?.originalPath.orEmpty(),
                    originalFolderName = meta?.originalFolderName ?: entry.originalFolderName,
                    sizeBytes = flat.length(),
                    durationMs = meta?.durationMs ?: 0L,
                    width = meta?.width ?: 0,
                    height = meta?.height ?: 0,
                    deletedAt = meta?.deletedAt?.takeIf { it > 0 }
                        ?: flat.lastModified().takeIf { it > 0 }
                        ?: entry.deletedAt,
                    storedName = flat.absolutePath,
                    isFolder = false,
                    fileCount = 1,
                )
                writeMeta(record)
                out += record
            }
            // 目录级索引没有用了；目录空了就删，别把空壳留在 .trash 里
            runCatching { File(bucket, FOLDER_META).delete() }
            runCatching { if (bucket.listFiles().orEmpty().isEmpty()) bucket.delete() }
        }
        return out
    }

    /**
     * 把子目录里的数据文件搬回同卷的 `.trash` 根下。
     *
     * 搬不动（权限、被占用）就留在原处：条目指向的仍是那个文件，能播能还原，
     * 只是回收站里多一个子目录。这种情况下它的 meta 还躺在旁边，认领逻辑靠
     * [recoverOrphans] 里的 knownDirs 判断跳过它，不会重复认领。
     */
    private fun migrateOutOf(source: File): File {
        val target = newDataFile(rootFor(source.absolutePath))
        if (source.renameTo(target)) {
            runCatching { metaOf(source).delete() }
            return target
        }
        return source
    }

    /**
     * 条目对应位置是否还能读到。
     *
     * 不能只判 `exists()`：外置存储卡被拔出时，卡上的文件当然「不存在」，
     * 但它没丢 —— 直接过滤掉会让回收站里的条目凭空消失（插回卡后才被重新认领，
     * id 也换了）。所以路径不存在时再判一次所在卷是否挂载，卷不在就保留条目。
     */
    private fun isReachable(entry: TrashEntry): Boolean {
        val f = fileOf(entry)
        return f.exists() || volumeMissing(f)
    }

    /**
     * 文件所在的外置卷是不是被拔掉了。
     *
     * 内置存储与 `Android/data` 私有目录恒为 false —— 它们不会「暂时不可用」，
     * 文件不存在就是真的没了。
     */
    private fun volumeMissing(f: File): Boolean {
        val segs = f.absolutePath.trim('/').split('/')
        if (segs.size < 2 || segs[0] != "storage") return false
        if (segs[1] == "emulated") return false
        return !File("/storage/${segs[1]}").isDirectory
    }

    /**
     * 把历史数据里重复的 id 重新发号。
     *
     * 1.0.5 之前 id 取 `System.currentTimeMillis()`，批量删除会产生重复值。重复的 id
     * 会让 Compose 的 `items(key = { it.id })` 抛异常（回收站整页打不开），所以加载时
     * 必须自愈一次，并把新 id 写回该条目自己的 meta，避免下次认领回来又变回重复值。
     */
    private fun ensureUniqueIds(list: List<TrashEntry>): List<TrashEntry> {
        val maxExisting = list.maxOfOrNull { it.id } ?: 0L
        // 基线抬到已有条目之上，保证新号不会撞上历史值（系统时间被回拨时也安全）
        lastId.updateAndGet { prev -> maxOf(prev, maxExisting) }
        val seen = HashSet<Long>(list.size)
        var changed = false
        val out = list.map { entry ->
            if (seen.add(entry.id)) {
                entry
            } else {
                changed = true
                entry.copy(id = nextId()).also { writeMeta(it) }
            }
        }
        return if (changed) out else list
    }

    /**
     * 认领「文件还在、但不在 trash.json 里」的条目。
     *
     * meta 是删除时写在数据文件旁边的，即使 trash.json 丢了（清应用数据、写入过程中被杀），
     * 只要文件还在就能重新出现在回收站里，而不是变成占着空间又看不见的残留。
     *
     * 三种情况都要收：新格式的 `.trash` + meta、`trash.json` 有记录但 meta 被删（走 [adoptOrphan]），
     * 以及旧版本目录里没有 meta 的 `时间戳_原名.mp4`（走 [adoptLegacy]）。
     */
    private fun recoverOrphans(known: List<TrashEntry>): List<TrashEntry> {
        val knownPaths = known.mapTo(HashSet()) { fileOf(it).absolutePath }
        // 条目所在的目录也算「已知」：旧文件夹条目的子目录被拆开之后，里面的文件
        // 已经是独立条目了，扫描时不能再把它们按目录整体认领一遍（会生成重复记录、
        // 而且新 id 排在前面会把原记录挤掉）。
        val knownDirs = known.mapNotNullTo(HashSet()) { fileOf(it).parentFile?.absolutePath }
        val out = mutableListOf<TrashEntry>()
        recoverRoots().forEach { root ->
            root.listFiles().orEmpty().forEach entry@{ f ->
                if (f.name == NOMEDIA || f.extension.equals(META_SUFFIX, true)) return@entry
                if (f.absolutePath in knownPaths) return@entry
                if (f.isDirectory) {
                    if (f.absolutePath in knownDirs) return@entry
                    val meta = readMeta(f)
                    if (meta != null) {
                        out += meta.copy(storedName = f.absolutePath, isFolder = true)
                    } else {
                        // 没有目录级 meta 的目录：把里面的数据文件各自认领
                        f.listFiles().orEmpty().forEach inner@{ inner ->
                            if (!inner.isFile || !inner.extension.equals(DATA_SUFFIX, true)) return@inner
                            (readMeta(inner) ?: adoptOrphan(inner))?.let {
                                out += it.copy(storedName = inner.absolutePath, isFolder = false)
                            }
                        }
                    }
                } else if (f.extension.equals(DATA_SUFFIX, true)) {
                    (readMeta(f) ?: adoptOrphan(f))?.let {
                        out += it.copy(storedName = f.absolutePath, isFolder = false)
                    }
                } else {
                    adoptLegacy(f)?.let {
                        out += it.copy(storedName = f.absolutePath, isFolder = false)
                    }
                }
            }
            // 认领完再清无主 meta：顺序反了会把马上要用到的索引删掉
            pruneOrphanMeta(root)
        }
        return out.map { migrateLegacyLocation(it, fileOf(it)) }
    }

    /**
     * 把条目从旧版可见目录搬到同卷的 `.trash`。
     *
     * 1.0.5 之前目录名不带点，文件管理器能直接点进去；升级后这些文件虽然被认领进了
     * 回收站，物理位置还在那个显眼的目录里，只有用户逐个还原/删除才会消失。
     * 同卷 rename 是瞬间的，顺手搬到正确的位置更干净。
     *
     * 搬不动（权限、目录被占）就原样返回，条目仍可用 —— 迁移失败不该让文件变得看不见。
     */
    private fun migrateLegacyLocation(entry: TrashEntry, from: File): TrashEntry {
        if (from.parentFile?.name != LEGACY_DATA_DIR) return entry
        val legacyDir = from.parentFile ?: return entry
        val bucket = File(legacyDir.parentFile, DATA_DIR)
        if (!bucket.isDirectory && !bucket.mkdirs()) return entry
        File(bucket, NOMEDIA).takeIf { !it.exists() }?.let { runCatching { it.createNewFile() } }
        val dest = if (from.isDirectory) {
            File(bucket, "${randomBase()}.$DATA_SUFFIX")
        } else {
            newDataFile(bucket)
        }
        if (!from.renameTo(dest)) return entry
        // 旧位置的 .meta 不再需要：writeMeta 会在新位置写一份完整的
        if (!from.isDirectory) {
            runCatching { File(legacyDir, "${from.nameWithoutExtension}.$META_SUFFIX").delete() }
        }
        return entry.copy(storedName = dest.absolutePath).also { writeMeta(it) }
    }

    /**
     * 清掉没有对应数据文件的 `.meta`。
     *
     * 数据文件被外部删掉（清理软件、用户手动）后 meta 会留下，它既不是数据文件、
     * 也不会被认领，除了「清空回收站」没有任何路径会碰它 —— 结果是 `.trash` 里
     * 越攒越多几百字节的无主索引。
     */
    private fun pruneOrphanMeta(root: File) {
        val files = root.listFiles().orEmpty()
        val dataNames = files.asSequence()
            .filter { !it.isDirectory && it.extension.equals(DATA_SUFFIX, true) }
            .mapTo(HashSet()) { it.nameWithoutExtension }
        files.forEach { f ->
            if (f.isFile &&
                f.extension.equals(META_SUFFIX, true) &&
                f.nameWithoutExtension !in dataNames
            ) {
                runCatching { f.delete() }
            }
        }
    }

    /**
     * 认领 `.trash` 数据文件在、但 meta 丢了的条目。
     *
     * 随机名里没有任何原名信息，`fileName` 只能用随机串本身、`originalPath` 留空，
     * 还原时回落到公共 Movies 目录。相比"看不见的占用"，至少让用户知道它存在、
     * 能播放、能彻底删除。
     */
    private fun adoptOrphan(f: File): TrashEntry? {
        if (!f.isFile) return null
        val deletedAt = f.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return TrashEntry(
            id = nextId(),
            // 随机名里没有任何原名信息，只能给占位名。用删除时间而不是随机串：
            // 至少能认出是哪一天删的、能按名字排序，不会显示成一串十六进制让人以为坏了。
            fileName = "恢复的视频_" + java.text.SimpleDateFormat(
                "yyyyMMdd_HHmm",
                java.util.Locale.US,
            ).format(java.util.Date(deletedAt)),
            originalPath = "",
            originalFolderName = "",
            sizeBytes = f.length(),
            deletedAt = deletedAt,
            storedName = f.absolutePath,
            isFolder = false,
            fileCount = 1,
        ).also { writeMeta(it) }
    }

    /**
     * 认领旧版回收站里的文件（1.0.5 之前是 `<13 位时间戳>_<原名>.mp4`，没有 meta）。
     *
     * 走进这个分支的前提是 `trash.json` 已经丢了（否则条目本来就在），所以原路径无从得知：
     * `originalPath` 置空，[restore] 会回落到公共 Movies 目录 —— 内容能救回来，
     * 总比永远躺在回收站里、界面还看不见要好。
     */
    private fun adoptLegacy(f: File): TrashEntry? {
        if (!f.isFile || f.name.startsWith(".")) return null
        val match = LEGACY_NAME.matchEntire(f.name)
        val fileName = match?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: f.name
        val deletedAt = match?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
            ?: f.lastModified().takeIf { it > 0 }
            ?: System.currentTimeMillis()
        return TrashEntry(
            id = nextId(),
            fileName = fileName,
            originalPath = "",
            originalFolderName = "",
            sizeBytes = f.length(),
            deletedAt = deletedAt,
            storedName = f.absolutePath,
            isFolder = false,
            fileCount = 1,
        ).also { writeMeta(it) }
    }

    /**
     * 可能存有回收站文件的目录：内置存储 + 各外置卷的 `Android/media/<包名>` 下，
     * `.trash`（新）与 `trash`（旧）都要扫，最后加上旧版应用私有目录。
     */
    private fun recoverRoots(): List<File> {
        val dirs = linkedSetOf<File>()
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val volume = dir.absolutePath.substringBefore("/Android/")
            if (volume.isNotBlank()) {
                dirs += File(volume, "Android/media/${context.packageName}/$DATA_DIR")
                dirs += File(volume, "Android/media/${context.packageName}/$LEGACY_DATA_DIR")
            }
        }
        dirs += legacyRoot
        return dirs.filter { it.isDirectory }
    }

    /**
     * 回收站条目的落盘位置。
     *
     * 新条目把绝对路径整体存进 `storedName`（回收站可能在任意一卷的 Android/media 下）；
     * 旧条目只存文件名，仍按应用私有目录解析。
     */
    fun fileOf(entry: TrashEntry): File {
        val raw = entry.storedName
        return if (raw.startsWith("/")) File(raw) else File(legacyRoot, raw)
    }

    fun totalSize(): Long = _entries.value.sumOf { it.sizeBytes }

    /** 把视频移入回收站；文件不可读或没有权限时返回失败 */
    suspend fun moveToTrash(item: VideoItem): Result<TrashEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            moveOne(item)
        }
    }

    /**
     * 批量移入回收站。
     *
     * 逐个调 [moveToTrash] 时每搬一个都要全量重写 `trash.json`、并推一次 StateFlow，
     * 50 个视频就是 50 次写盘 + 50 次列表重组。这里在同一把锁内搬完，最后只落盘一次。
     */
    suspend fun moveManyToTrash(items: List<VideoItem>): MoveManyResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val moved = mutableListOf<TrashEntry>()
            var firstError: String? = null
            items.forEach { item ->
                // scan = false：整批搬完统一扫一次，逐个扫等于每个文件都发一次媒体库请求
                moveOne(item, commit = false, scan = false).fold(
                    onSuccess = { moved += it },
                    onFailure = { if (firstError == null) firstError = it.message },
                )
            }
            if (moved.isNotEmpty()) {
                // 新删除的排在最前，与单条删除的顺序保持一致
                val next = moved.reversed() + _entries.value
                writeIndex(next)
                moved.mapNotNull { File(it.originalPath).parentFile?.absolutePath }
                    .distinct()
                    .forEach { FileOps.scan(context, it) }
            }
            MoveManyResult(moved, firstError)
        }
    }

    /** 批量移入的结果：成功的条目 + 第一条失败原因（部分成功时整体仍算成功） */
    data class MoveManyResult(val moved: List<TrashEntry>, val firstError: String?)

    /** 单条搬运的实际逻辑；[commit] 为 false 时只搬文件、不落盘，由批量调用方统一写一次 */
    private suspend fun moveOne(
        item: VideoItem,
        commit: Boolean = true,
        scan: Boolean = true,
    ): Result<TrashEntry> {
        val path = item.localPath
            ?: return Result.failure(IllegalStateException("这个视频没有真实文件路径"))
        val source = File(path)
        if (!source.exists()) return Result.failure(IllegalStateException("文件已不存在"))

        return moveInto(
            source = source,
            originalPath = path,
            fileName = item.fileName,
            folderName = item.folderName,
            sizeBytes = item.sizeBytes,
            durationMs = item.durationMs,
            width = item.width,
            height = item.height,
            isFolder = false,
            fileCount = 1,
            commit = commit,
            scan = scan,
        )
    }

    /**
     * 把**整个文件夹里的视频**移入回收站。
     *
     * 语义是「文件夹里的视频逐个进回收站、各自记自己的原路径」，不再为整个目录建一条
     * 目录记录。这样回收站里看见的永远是视频本身，删掉一个二十集的目录就是二十条记录，
     * 每条都能单独播放、单独还原、单独彻底删除；还原时每个视频回到它原来所在的位置，
     * 原目录不在了会按需重建 —— 恢复出来的结果和不删时完全一致。
     *
     * 早先的做法是在回收站里建一个随机目录把视频装进去、只留一条「文件夹」记录。
     * 好处是一次恢复能整批放回，代价是回收站列表里混着两种东西：文件夹点不开、不能播，
     * 选中的是文件夹时「播放」「详情」这些操作用不上，用户还得记住那条记录代表几个文件。
     *
     * 不整目录 rename 的理由没变：一个「视频文件夹」里往往混着字幕、封面、文档，
     * 整目录搬走等于把它们一起删了。所以只搬视频，其它文件原地不动；
     * 原目录搬空之后才删掉这个空目录。
     */
    suspend fun moveFolderToTrash(videos: List<VideoItem>): MoveManyResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parents = videos.mapNotNull { it.localPath }
                .mapNotNull { File(it).parentFile?.absolutePath }
                .distinct()
            val moved = mutableListOf<TrashEntry>()
            var firstError: String? = null
            videos.forEach { item ->
                // scan = false：整批搬完统一扫一次目录，不然一百集就是一百次媒体库请求
                moveOne(item, commit = false, scan = false).fold(
                    onSuccess = { moved += it },
                    onFailure = { if (firstError == null) firstError = it.message },
                )
            }
            if (moved.isNotEmpty()) {
                // 新删除的排在最前，与单条删除的顺序保持一致
                writeIndex(moved.reversed() + _entries.value)
            }
            // 原目录搬空了才删；还留着非视频文件就原样保留
            parents.forEach { p ->
                runCatching {
                    val dir = File(p)
                    val left = dir.listFiles()
                    if (left != null && left.isEmpty()) dir.delete()
                }
                FileOps.scan(context, p)
            }
            MoveManyResult(moved, firstError)
        }
    }

    /** 实际搬运 + 记账；文件与文件夹共用一条路径 */
    private suspend fun moveInto(
        source: File,
        originalPath: String,
        fileName: String,
        folderName: String,
        sizeBytes: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        isFolder: Boolean,
        fileCount: Int,
        commit: Boolean = true,
        scan: Boolean = true,
    ): Result<TrashEntry> {
        // 目标目录按源文件所在的卷算，保证 rename 走的是同一挂载点（见 rootFor）
        val bucket = rootFor(source.absolutePath)
        val target = newDataFile(bucket)

        if (!movePath(source, target)) {
            runCatching {
                if (target.isDirectory) target.deleteRecursively() else target.delete()
                metaOf(target).delete()
            }
            return Result.failure(
                IllegalStateException("没有写入权限，请开启「所有文件访问权限」"),
            )
        }
        // 文件换了路径，把缩略图缓存一起搬到回收站路径的 key 下。
        // 否则回收站卡片读不到旧图、只能重新抽帧，用户看到的就是「删进回收站封面变了」。
        Thumbnails.moveCacheKey(context, originalPath, target.absolutePath)

        val entry = TrashEntry(
            id = nextId(),
            fileName = fileName,
            originalPath = originalPath,
            originalFolderName = folderName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            width = width,
            height = height,
            deletedAt = System.currentTimeMillis(),
            storedName = target.absolutePath,
            isFolder = isFolder,
            fileCount = fileCount,
        )
        writeMeta(entry)
        if (commit) {
            val next = listOf(entry) + _entries.value
            writeIndex(next)
        }
        // 通知媒体库原路径已失效
        if (scan) FileOps.scan(context, originalPath)
        return Result.success(entry)
    }

    /** rename 优先（同分区瞬间完成），跨分区退化为递归复制 + 删除 */
    private fun movePath(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return runCatching {
            source.copyRecursively(target, overwrite = false) && source.deleteRecursively()
        }.getOrDefault(false)
    }

    /** 批量恢复的结果：成功条数 + 失败明细（文件名 → 原因） */
    data class RestoreManyResult(val restored: Int, val failures: List<Pair<String, String>>)

    /**
     * 恢复到原目录；同名文件已存在时自动改名。
     *
     * **只通过结果表达失败，绝不抛业务异常**。调用方是页面的 `scope.launch`，
     * 那里没有异常兜底，任何漏出来的异常都会把整个页面带走 —— 而恢复是「先搬文件、
     * 后写索引」，搬到一半出错时文件其实已经在原位了，用户看到的就是「恢复了，然后闪退」。
     * 所以每个失败分支都收敛成 [RestoreManyResult.failures] 里的一条，界面能准确说明原因。
     *
     * 必须在仓储层一次做完，不能让界面循环调用：每条单独恢复都要跑一遍
     * [onRestored]，而它要等媒体库回调、重扫本地库、重写 history.json。
     * 恢复「删掉的整个文件夹」时动辄几十条，逐条排队就是几十次串行等待 ——
     * 删文件夹改成逐条入库之后，这个代价才浮出来（旧版一条文件夹记录只回写一次）。
     *
     * 每条只在自己那一小段持锁，回写历史统一放在锁外一次做完，
     * 与 [moveManyToTrash] 的扫描合并是同一个道理。
     */
    suspend fun restoreMany(entries: List<TrashEntry>): RestoreManyResult {
        if (entries.isEmpty()) return RestoreManyResult(0, emptyList())
        val paths = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()
        var ok = 0
        withContext(Dispatchers.IO) {
            entries.forEach { entry ->
                val result = try {
                    mutex.withLock { restoreLocked(entry, paths) }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    android.util.Log.e("TrashRepo", "恢复失败：${entry.fileName}", t)
                    Result.failure(IllegalStateException(t.message ?: "恢复失败，请重试"))
                }
                result.fold(
                    onSuccess = { ok++ },
                    onFailure = { failures += entry.fileName to (it.message ?: "恢复失败") },
                )
            }
        }
        if (paths.isNotEmpty()) {
            try {
                onRestored(paths)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                android.util.Log.w("TrashRepo", "恢复后回写历史失败", t)
            }
        }
        return RestoreManyResult(ok, failures)
    }

    /** [restore] 的锁内实现：每个失败分支都走 Result，不留可抛出的业务异常 */
    private suspend fun restoreLocked(
        entry: TrashEntry,
        restored: MutableList<String>,
    ): Result<String> {
        val source = fileOf(entry)
        if (!source.exists()) {
            return Result.failure(
                IllegalStateException(
                    // 卡被拔掉和文件真被删是两回事，提示必须分开：
                    // 前者插回去就能恢复，后者只能彻底删掉这条记录
                    if (volumeMissing(source)) "存储卡未插入，插回后再恢复"
                    else "回收站里的文件已丢失",
                ),
            )
        }

        if (entry.isFolder) return restoreFolder(entry, source, restored)

        // 物理文件名已随机化，原名与目标路径只认 meta；meta 缺失（旧版条目或 meta 被
        // 第三方删掉）才退回 trash.json 里的字段 —— 老数据没有 meta，这条路径必须保留。
        val info = readMeta(source) ?: entry
        val recorded = info.originalPath.ifBlank { entry.originalPath }
        val fileName = info.fileName.ifBlank { entry.fileName }
        // 原路径未知（认领回来的旧文件 / 丢了 meta 的数据文件）：回落到公共 Movies，
        // 至少把内容放回用户能看见的地方，而不是留在回收站里出不去
        val originalPath = recorded.ifBlank { File(fallbackDir(), fileName).absolutePath }

        val parent = File(originalPath).parentFile
            ?: return Result.failure(IllegalStateException("原目录不可用"))
        if (!parent.exists() && !parent.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建原目录"))
        }

        val target = if (File(originalPath).exists()) {
            FileOps.uniqueTarget(parent, fileName)
        } else {
            File(originalPath)
        }
        if (target.exists()) {
            return Result.failure(IllegalStateException("目标位置已有同名文件"))
        }

        val metaFile = metaOf(source)
        if (!movePath(source, target)) {
            return Result.failure(IllegalStateException("恢复失败，请检查存储权限"))
        }
        runCatching { metaFile.delete() }
        // 与删除时对称：把缩略图缓存从回收站路径搬回恢复后的路径，封面保持不变。
        Thumbnails.moveCacheKey(context, source.absolutePath, target.absolutePath)

        removeEntry(entry)
        FileOps.scan(context, target.absolutePath)
        restored += target.absolutePath
        return Result.success(target.absolutePath)
    }

    /** 原路径未知时的落点：公共 Movies 目录 */
    @Suppress("DEPRECATION")
    private fun fallbackDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            ?: File("/storage/emulated/0/Movies")

    /**
     * 恢复一个文件夹：把回收站里这个随机目录中的视频按各自 meta 的原名搬回原目录。
     *
     * 原目录可能已经不在（用户删过、或被系统清理），这里按需重建；
     * 目标位置有同名文件就自动改名，绝不覆盖 —— 覆盖等于又删了一次用户的文件。
     * 全部搬完再把回收站里的空壳删掉；只要还剩一个没搬走，这条记录就继续留着，
     * 让用户能再试一次（并看到还剩几个）。
     */
    private suspend fun restoreFolder(
        entry: TrashEntry,
        bucket: File,
        restored: MutableList<String>,
    ): Result<String> {
        // 只认数据文件（.trash）：目录里还有每个视频的 .meta 和目录级 folder.meta，
        // 它们是恢复用的索引，不能被当成视频搬回原目录。
        val files = bucket.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(DATA_SUFFIX, true) }
        if (files.isEmpty()) {
            // 只剩空壳：没有可恢复的内容，直接收掉这条记录
            runCatching { bucket.deleteRecursively() }
            removeEntry(entry)
            return Result.failure(IllegalStateException("回收站里的文件夹已经空了"))
        }

        // 目录级原路径未知时同样回落到公共 Movies（认领回来的条目可能没有原路径）
        val originalPath = entry.originalPath.ifBlank {
            File(fallbackDir(), entry.fileName.ifBlank { "恢复的文件夹" }).absolutePath
        }
        val dir = File(originalPath)
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建原目录"))
        }

        var moved = 0
        var failed = 0
        var lastError: String? = null
        files.forEachIndexed { index, f ->
            // 每个视频的原名在它自己的 meta 里；meta 缺失只能给一个可用的占位名，
            // 至少让文件回到原目录，而不是留在回收站里再也拿不出去。
            val name = readMeta(f)?.fileName?.takeIf { it.isNotBlank() }
                ?: "恢复的文件_${index + 1}"
            val metaFile = metaOf(f)
            val target = if (File(dir, name).exists()) FileOps.uniqueTarget(dir, name)
            else File(dir, name)
            if (movePath(f, target)) {
                runCatching { metaFile.delete() }
                moved++
                restored += target.absolutePath
                FileOps.scan(context, target.absolutePath)
            } else {
                failed++
                if (lastError == null) lastError = "无法写入「${dir.absolutePath}」"
            }
        }

        if (failed == 0) {
            runCatching { bucket.deleteRecursively() }
            removeEntry(entry)
            return Result.success(dir.absolutePath)
        }

        // 部分成功：更新剩余数量与体积，让回收站里这条记录仍是"真实的样子"
        val left = bucket.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(DATA_SUFFIX, true) }
        val updated = entry.copy(
            fileCount = left.size,
            sizeBytes = left.sumOf { it.length() },
        )
        // folder.meta 也要跟着改，否则 trash.json 丢了以后重建出来的数量是旧的
        writeMeta(updated)
        val next = _entries.value.map { if (it.id == entry.id) updated else it }
        writeIndex(next)
        return Result.failure(
            IllegalStateException(
                "已恢复 $moved 个，还有 $failed 个没能放回（${lastError ?: "请检查存储权限"}）",
            ),
        )
    }

    /**
     * 从列表里摘掉一条记录。
     *
     * 同时比对 id 与落盘路径：历史数据里可能存在重复 id（1.0.5 之前用毫秒时间戳发号），
     * 只按 id 过滤会把另一条不相干的记录一起抹掉，表现就是「彻底删一条，列表少两条」。
     */
    private suspend fun removeEntry(entry: TrashEntry) {
        val next = _entries.value.filterNot {
            it.id == entry.id && it.storedName == entry.storedName
        }
        writeIndex(next)
    }

    /**
     * 索引落盘，顺带更新内存状态。
     *
     * **写盘失败不能抛给上层**。回收站里的每一次写操作都是「先动文件、后记索引」：
     * 走到这里时 rename / delete 都已经落定，索引只是一份缓存。抛出去的后果非常具体 ——
     * 文件已经恢复成功，紧接着写索引失败，异常冒到 UI 的 `scope.launch` 里没人接，
     * 整个页面闪退，用户以为恢复坏了（1.0.4 及之前的"偶发恢复后闪退"就是这条链）。
     *
     * 而且这份索引本来就允许落后：下次启动 `load()` 会按 `.trash` 实际内容重建，
     * 搬走/删掉的文件不存在、条目自然消失。所以写失败只记日志，不影响操作结果。
     */
    private suspend fun writeIndex(next: List<TrashEntry>) {
        runCatching { store.write(next) }.onFailure {
            android.util.Log.w("TrashRepo", "回收站索引写入失败，下次启动会按目录内容重建", it)
        }
        _entries.value = next
    }

    /** 彻底删除单条 */
    suspend fun deletePermanently(entry: TrashEntry) = deletePermanently(listOf(entry))

    /** 多选彻底删除：同一把锁内完成删除与一次落盘 */
    suspend fun deletePermanently(entries: Collection<TrashEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 只把**真的删掉了**的条目从索引里摘掉。
            // 之前不管删除成功与否都抹索引：文件被占用 / 卡只读时删不掉，下次 load()
            // 扫目录又把它认领回来，用户看到的就是「彻底删除的东西过两天又回来了」。
            val removed = entries.filter { entry ->
                runCatching { deleteEntryFiles(entry) }.getOrDefault(false)
            }
            val keys = removed.mapTo(HashSet()) { it.id to it.storedName }
            val next = _entries.value.filterNot { (it.id to it.storedName) in keys }
            writeIndex(next)
        }
    }

    /**
     * 删掉一条回收站条目的物理文件；返回是否真的删干净。
     *
     * 文件本来就不存在也算成功（用户可能手动清理过），此时索引留着没有意义。
     */
    private fun deleteEntryFiles(entry: TrashEntry): Boolean {
        val f = fileOf(entry)
        return if (f.isDirectory) {
            // 文件夹条目：目录里连 .trash 与 .meta 一起删掉
            f.deleteRecursively()
        } else {
            val meta = metaOf(f)
            val dataGone = !f.exists() || f.delete()
            val metaGone = !meta.exists() || meta.delete()
            dataGone && metaGone
        }
    }

    /** 自动清理的结果：删了几个、释放了多少字节 */
    data class CleanResult(val removed: Int, val freedBytes: Long)

    /**
     * 按「超期 + 总量超标」双条件自动清理。
     *
     * 两个条件必须同时成立才动手：
     * - 只看天数：回收站里只有两个刚过期的视频也会被清掉，用户明明还没腾空间的需要；
     * - 只看体量：几百 MB 的老视频会永远留着，因为总量一直没超过阈值。
     *
     * 清理从最旧的开始，删到「不再超标」就停 —— 不会把一批超期文件一次性删光，
     * 尽量少删、刚好把占用压回阈值以下。返回真实删除数量与释放字节，供界面告知用户。
     */
    suspend fun autoClean(
        retentionDays: Int,
        thresholdBytes: Long = AUTO_CLEAN_THRESHOLD,
    ): CleanResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val total = _entries.value.sumOf { it.sizeBytes }
            if (total <= thresholdBytes) return@withLock CleanResult(0, 0L)
            val cutoff = System.currentTimeMillis() -
                retentionDays.coerceAtLeast(1) * 24L * 3600_000L
            val expired = _entries.value
                .filter { it.deletedAt in 1..cutoff }
                .sortedBy { it.deletedAt }
            if (expired.isEmpty()) return@withLock CleanResult(0, 0L)

            var remaining = total
            val victims = mutableListOf<TrashEntry>()
            for (entry in expired) {
                if (remaining <= thresholdBytes) break
                victims += entry
                remaining -= entry.sizeBytes
            }
            // 同彻底删除：只把真删掉的从索引里摘掉，删不掉的留着并如实回填数量，
            // 否则下次扫描又把它们认领回来（「自动清理了两次，文件还在」）
            val removed = victims.filter { entry ->
                runCatching { deleteEntryFiles(entry) }.getOrDefault(false)
            }
            val keys = removed.mapTo(HashSet()) { it.id to it.storedName }
            val next = _entries.value.filterNot { (it.id to it.storedName) in keys }
            writeIndex(next)
            CleanResult(removed.size, removed.sumOf { maxOf(0L, it.sizeBytes) })
        }
    }

    /**
     * 清空回收站。
     *
     * 返回**没能删掉、仍留在里面的条目数**（正常为 0）。删除失败的条目必须留在索引里：
     * 否则下次启动扫描目录又会把它们认领回来，用户看到的是「清空了，过一会儿又出现」，
     * 比直接告诉他「这 2 个被占用、没删掉」更难理解。
     */
    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 回收站可能分散在多个卷（内置存储 / SD 卡），而且新旧目录并存：
            // 先把条目涉及目录收集起来，再把所有可能的根目录都扫一遍，清掉没记进
            // JSON 的残留（否则清空后重启会被 recoverOrphans 认领回来，看起来像"清不掉"）
            val dirs = HashSet<String>()
            _entries.value.forEach {
                runCatching {
                    val f = fileOf(it)
                    f.parentFile?.let { p -> dirs += p.absolutePath }
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
            dirs += legacyRoot.absolutePath
            recoverRoots().forEach { dirs += it.absolutePath }
            dirs.forEach { d ->
                File(d).listFiles()?.forEach {
                    // .nomedia 要留下：清空之后目录还在，没了它回收站里的新文件又会被媒体库收录
                    if (it.name != NOMEDIA) runCatching { it.deleteRecursively() }
                }
            }
            val survivors = _entries.value.filter { fileOf(it).exists() }
            writeIndex(survivors)
            survivors.size
        }
    }

    private companion object {
        /** 回收站目录名：点开头，文件管理器默认不显示 */
        const val DATA_DIR = ".trash"
        /** 1.0.5 之前的目录名，只读兼容，不再写入 */
        const val LEGACY_DATA_DIR = "trash"
        /** 数据文件后缀：看不出是视频，媒体扫描器也不认 */
        const val DATA_SUFFIX = "trash"
        /** 索引文件后缀：与数据文件同名、只换后缀 */
        const val META_SUFFIX = "meta"
        /** 文件夹条目放在目录内部的索引文件 */
        const val FOLDER_META = "folder.meta"
        const val NOMEDIA = ".nomedia"
        /** 旧版回收站文件名：`<13 位毫秒时间戳>_<原文件名>` */
        val LEGACY_NAME = Regex("^(\\d{13})_(.+)$")
        /** 自动清理的体量阈值：2GB */
        const val AUTO_CLEAN_THRESHOLD = 2L * 1024 * 1024 * 1024
    }
}
