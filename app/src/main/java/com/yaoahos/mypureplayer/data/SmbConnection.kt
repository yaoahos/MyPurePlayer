package com.yaoahos.mypureplayer.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * 一条到某台服务器的 SMB 会话（TCP 连接 + 认证会话 + 若干共享）。
 *
 * 与 jcifs 的 CIFSContext 相比，这里最关键的差别是**并发模型**：
 * jcifs 的同一条会话上一次只能有一个请求在途，播放读和抽帧读会互相排队，旧代码
 * 只能靠「播放用一套 context、预览用另一套」绕开；smbj 的 Connection 内置
 * SequenceWindow，同一连接上可以同时挂多个 READ，播放与抽帧各开各的句柄就不再互堵。
 * 即便如此，上层仍保留两套连接（播放 / 预览），因为抽帧一屏十几个文件并发读
 * 会占满窗口，让播放请求排在后面 —— 分开更稳，代价只是多一条 TCP。
 */
internal class SmbConnection(
    val server: SmbServer,
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
) : Closeable {

    private val shares = HashMap<String, DiskShare>()
    private val shareLock = Any()

    val isAlive: Boolean get() = runCatching { connection.isConnected }.getOrDefault(false)

    /** 已协商的方言，用于日志与「为什么这么慢」的排查 */
    val dialect: String
        get() = runCatching {
            connection.negotiatedProtocol?.dialect?.toString() ?: "SMB2/3"
        }.getOrDefault("SMB2/3")

    /** 拿到共享；同一连接内复用 TREE_CONNECT，断开的共享会自动重连一次 */
    fun diskShare(name: String): DiskShare = synchronized(shareLock) {
        shares[name]?.takeIf { it.isConnected }?.let { return it }
        shares.remove(name)?.let { runCatching { it.close() } }
        val share = session.connectShare(name) as? DiskShare
            ?: throw SmbFailure("「$name」不是文件共享，无法浏览")
        shares[name] = share
        share
    }

    fun list(loc: SmbPath.Loc): List<FileIdBothDirectoryInformation> {
        if (loc.share.isEmpty()) throw SmbFailure(NoShareHint)
        return diskShare(loc.share).list(loc.path)
    }

    /** 打开共享内的文件做随机读。调用方负责 close()。 */
    fun openReader(loc: SmbPath.Loc): SmbRandomReader {
        if (loc.share.isEmpty()) throw SmbFailure(NoShareHint)
        if (loc.path.isEmpty()) throw SmbFailure("这是一个共享根目录，不是文件")
        // 必须把「取共享 + CREATE 文件」与 close() 放在同一把锁里：
        // 另一线程遇到传输错误会重建连接并 close() 旧连接，若它恰好插在 diskShare()
        // 与 openFile() 之间，smbj 就会抛 "DiskShare has already been closed"，
        // 表现为播放列表里多个条目同时报「Source error」。
        return synchronized(shareLock) {
            val share = diskShare(loc.share)
            val file = share.openFile(
                loc.path,
                // 只读：只要数据与属性，不给写权限，避免服务器把文件标成占用
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            val total = runCatching { file.getLength() }.getOrDefault(0L)
            SmbRandomReader(file, total)
        }
    }

    override fun close() {
        synchronized(shareLock) {
            shares.values.forEach { runCatching { it.close() } }
            shares.clear()
            // 会话与底层连接也放进同一把锁：防止另一线程正在 connectShare/openFile
            // 时把连接抽掉（同样会产生已关闭共享/连接的伪业务错误）。
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    companion object {

        /** 用户没填共享名时的统一提示：SMB 协议本身没有「列共享」的能力，只能引导填写 */
        const val NoShareHint =
            "请先填写「默认共享目录」（例如 media）。SMB 协议不提供共享列表，客户端必须知道要进哪个共享。"

        /**
         * 全局共享的配置。
         *
         * - 方言：明确列出 SMB 2.0.2 ~ 3.1.1；
         * - 关闭 DFS：家用共享不需要 DFS 引用，开着会让根目录枚举挂住（沿用旧实现的取舍）；
         * - 关闭目录租约：租约会让「别人改了目录」的可见性变复杂，浏览场景不需要；
         * - 缓冲：读 1MB，配合上层 512KB 的分块读，尽量把往返压到最少；
         * - 超时：连接 20s、读写 60s，与旧实现一致，NAS 休眠唤醒时不会立刻失败。
         */
        fun buildConfig(): SmbConfig = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_2_0_2,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            )
            .withDfsEnabled(false)
            .withDirectoryLeasingEnabled(false)
            .withMultiProtocolNegotiate(false)
            .withReadBufferSize(1024 * 1024)
            .withTransactBufferSize(1024 * 1024)
            // 关键：smbj 默认用 ProxySocketFactory，建 socket 时既不设 SO_RCVBUF/
            // SO_SNDBUF 也不开 TCP_NODELAY，全用系统默认（Android 约 64KB）。
            // 2.4G Wi-Fi 实测 RTT ~20ms，TCP 吞吐上限 ≈ 窗口/RTT ≈ 64KB/20ms ≈ 3MB/s，
            // 这正是过去「网络视频只有 1.8MB/s、起播/快进都慢」的真正瓶颈 —— 不是网络，
            // 是客户端 TCP 窗口。把收发缓冲提到 1MB（窗口/RTT ≈ 50MB/s，远超内网所需），
            // 并关掉 Nagle（小控制包不等 40ms 凑包），吞吐立刻回到网络本身的速度。
            .withSocketFactory(TunedSocketFactory)
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withReadTimeout(60, TimeUnit.SECONDS)
            .build()

        fun open(server: SmbServer): SmbConnection {
            val client = SMBClient(buildConfig())
            val connection = client.connect(server.host)
            val auth = if (server.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(server.username, server.password.toCharArray(), server.domain)
            }
            val session = connection.authenticate(auth)
            return SmbConnection(server, client, connection, session)
        }
    }
}

/**
 * 为 SMB 传输调优的 TCP Socket 工厂。
 *
 * smbj 的 [SmbConfig.Builder.withSocketFactory] 会在连接时调用 [createSocket]。
 * 必须在大缓冲区 connect 之前设置 SO_RCVBUF / SO_SNDBUF，Linux/Android 上连接之后再设
 * 不会生效（只会保留系统默认的小窗口）。TCP_NODELAY 同理，关掉 Nagle 后 SMB 的
 * CREATE / QUERY_INFO 这类小控制包不必等 ACK 凑包，目录列举和 seek 的往返更短。
 *
 * 1MB 是保守值：局域网 RTT 20ms 时理论吞吐 ≈ 1MB/20ms = 50MB/s，已经高于 2.4G
 * 实际可用带宽；再大只会浪费内存（每条连接收发各 1MB），不会更快。
 */
private object TunedSocketFactory : SocketFactory() {
    private const val SOCKET_BUFFER_BYTES = 1024 * 1024

    private fun tune(socket: Socket): Socket {
        runCatching {
            socket.receiveBufferSize = SOCKET_BUFFER_BYTES
            socket.sendBufferSize = SOCKET_BUFFER_BYTES
            socket.tcpNoDelay = true
        }
        return socket
    }

    override fun createSocket(): Socket = tune(Socket())

    override fun createSocket(host: String?, port: Int): Socket =
        tune(Socket()).also { it.connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        tune(Socket()).also {
            it.bind(java.net.InetSocketAddress(localHost, localPort))
            it.connect(java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        tune(Socket()).also { it.connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = tune(Socket()).also {
        it.bind(java.net.InetSocketAddress(localAddress, localPort))
        it.connect(java.net.InetSocketAddress(address, port))
    }
}

/** 目录项是不是目录：smbj 给的是原始 FILE_ATTRIBUTE_* 位图 */
internal fun FileIdBothDirectoryInformation.isDirectoryEntry(): Boolean =
    (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
