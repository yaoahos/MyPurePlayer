# ---------------------------------------------------------------- 发布日志
# release 不保留 verbose/debug 日志：其中带媒体文件路径、SMB 地址、投屏流地址等隐私信息。
# warn/error 保留用于必要诊断，且不打印凭据。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int isLoggable(...);
}

# ---------------------------------------------------------------- libass / Media3
# libass 的 JNI 绑定（ass-kt / ass-media）按类名/方法名查找 native 方法，不能被裁剪
-keep class io.github.peerless2012.ass.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn io.github.peerless2012.ass.**

# ---------------------------------------------------------------- smbj
# 传输层、认证器与协议扩展通过反射/工厂实例化，整包保活最稳。
-keep class com.hierynomus.** { *; }
# 事件总线 mbassador 的真实包名是 net.engio.mbassy（artifact 叫 mbassador）。
# 它按「构造函数参数类型」反射找订阅方法所需的对象，混淆后会抛
# NoSuchMethodException: <混淆名>.<init>，表现为一进共享目录就报连接失败。
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
# mbassador 带一个可选的 JSR-341 表达式过滤模块，Android 上没有 javax.el。
# smbj 只用它的普通订阅，这部分类不会被调用。
-dontwarn javax.el.**

# smbj 用 BouncyCastle 的轻量 API 算 NTLM 散列与 AES-CMAC 签名。
# 这些算法由工厂按字符串挑选，R8 看不到直接引用；不保活会重演 jcifs 时代的老问题：
#   release 包填了账号密码就认证失败，debug（不混淆）却一切正常。
-keep class org.bouncycastle.crypto.digests.** { *; }
-keep class org.bouncycastle.crypto.macs.** { *; }
-keep class org.bouncycastle.crypto.engines.** { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------- 序列化与数据模型
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
# 只保留真正参与 JSON 序列化的数据模型，不再整包 keep（原先 -keep data.** 把
# Crypto/SmbClient/Repo 等实现类全量保活，dex 白白膨胀）。serializer 由下面
# 的 **$$serializer 规则单独覆盖。
-keep class com.yaoahos.mypureplayer.data.VideoItem { *; }
-keep class com.yaoahos.mypureplayer.data.SmbServer { *; }
-keep class com.yaoahos.mypureplayer.data.StreamItem { *; }
-keep class com.yaoahos.mypureplayer.data.HistoryEntry { *; }
-keep class com.yaoahos.mypureplayer.data.TrashEntry { *; }
-keep class com.yaoahos.mypureplayer.data.VaultEntry { *; }
-keep class com.yaoahos.mypureplayer.data.VaultConfig { *; }
-keep class com.yaoahos.mypureplayer.data.MediaSource { *; }
