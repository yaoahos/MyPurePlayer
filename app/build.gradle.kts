import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 内置 Kotlin：不再 apply org.jetbrains.kotlin.android。
    // Compose 与序列化仍以编译器插件形式挂载，版本与根项目的 KGP 一致。
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名材料（release + debug）统一放在项目根目录的 signing/ 下（整个目录不入库，
// 生产 release 用 CI secret 注入）。storeFile 按 signingDir 解析，整个目录可整体搬迁。
val signingDir = rootProject.file("signing")
val keystorePropertiesFile = File(signingDir, "keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { stream -> load(stream) }
    }
}

// 产物命名与版本号共用同一份常量：AGP 9 移除了旧 Variant API，
// 拿不到 variant.versionName，这里自己持有一份，避免两处不同步。
val appVersionName = "1.0.6"

android {
    namespace = "com.yaoahos.mypureplayer"
    // 最新的 AndroidX（core 1.19 / compose-ui 1.12）要求 compileSdk ≥ 37。
    // targetSdk 仍停在 36：compileSdk 只决定「能用到哪些 API」，targetSdk 才决定运行时行为，
    // 首发版本不引入新的系统行为变更。
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.yaoahos.mypureplayer"
        minSdk = 24
        targetSdk = 36
        // 正式版本号与 versionName 对齐（1.0.0 = 1，1.0.1 = 2，1.0.2 = 3，1.0.3 = 4，1.0.4 = 5，1.0.5 = 6，1.0.6 = 7）。
        // 调试包是独立包名（.debug 后缀）、独立的应用，不需要抬高版本号来覆盖；
        // 需要拿真机验证新改动时直接 assembleDebug 即可，与正式版并存。
        versionCode = 7
        versionName = appVersionName
        // 桌面名称：正式版用 app_name，debug 构建在 buildTypes 里换成「调试版」
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    // 签名配置：凭据来自不入库的 signing/keystore.properties。
    // - release 私有签名：配置缺失时不可构建，避免退回公开 debug 签名造成冒名更新风险。
    // - debug 签名：用项目内 signing/ 的标准 debug 密钥，替代 AGP 默认的 ~/.android/debug.keystore，
    //   换机器/清缓存后 debug 签名保持不变，方便真机覆盖安装。
    signingConfigs {
        getByName("debug") {
            if (keystorePropertiesFile.exists()) {
                storeFile = File(signingDir, keystoreProperties.getProperty("debug.storeFile"))
                storePassword = keystoreProperties.getProperty("debug.storePassword")
                keyAlias = keystoreProperties.getProperty("debug.keyAlias")
                keyPassword = keystoreProperties.getProperty("debug.keyPassword")
            }
        }
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = File(signingDir, keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // libass 原生库只保留 arm64-v8a：调试与交付目标（平板）都是 arm64，
    // 通用包里放其它 ABI 只是白占体积。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // R8 压缩代码 + 资源：SMB 客户端会带进 BouncyCastle，不裁剪时 dex 有 30MB+
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式包只用私有证书（凭据见 signingConfigs.release）。
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 调试包 = 另一个应用，不是同一个应用的另一个版本：
            //   - 包名加 .debug 后缀，可与正式包并排安装、互不覆盖；
            //   - 桌面名称「MyPurePlayer 调试版」，图标下能一眼分清；
            //   - 版本名带 -debug，关于页/错误上报里也不会看错；
            //   - 用项目内 signing/ 的 debug 证书，正式包始终是私有证书。
            // 代价是数据目录独立（/data/data/<applicationId>/）：设置、网络位置、播放列表、
            // 历史与缩略图缓存都不共享，调试包需要重新配一遍。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "@string/app_name_debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // 只保留中英文资源，去掉依赖库里几十种语言的翻译（AGP 8.5 起 resourceConfigurations 的后继写法）
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // libc++_shared.so 是死重量：libass.so / libasskt.so / libffmpegJNI.so 的
        // DT_NEEDED 里都没有它（三个库各自静态链了 C++ 运行时），体积却有 1.2MB。
        // 它由某个 AAR 的 jniLibs 顺带打包进来，ELF 加载器不会去加载没人 NEEDED 的库。
        jniLibs {
            excludes += "**/libc++_shared.so"
            excludes += "**/libdatastore_shared_counter.so"
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/module-info.class",
                "module-info.class",
                "META-INF/*.kotlin_module",
                // BouncyCastle 的抗量子算法参数文件，SMB 认证用不到
                "org/bouncycastle/**/*.properties",
                "META-INF/versions/**",
                // smbj 的 bouncycastle 依赖带 jspecify 的多版本 MANIFEST，与打包冲突
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/11/OSGI-INF/MANIFEST.MF"
            )
        }
        // 不启用 useLegacyPackaging：让 AGP 保持 .so 页对齐（16KB 页设备要求），
        // libass 原生库已在 0.6.0 修复 16KB 对齐问题。
    }

    lint {
        // release 也跑 lint（exported 组件、缺失权限、资源引用这类问题能进构建输出），
        // 但不让告警阻塞出包：历史遗留的告警先人工评审，不武断开 abortOnError
        checkReleaseBuilds = true
        abortOnError = false
    }
}

// AGP 9 移除 applicationVariants.all + BaseVariantOutputImpl，改用 androidComponents。
// 产物命名：MyPurePlayer-<版本号>-<构建类型>.apk。只打 arm64 一个 ABI，不会出现重名。
androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: "release"
        variant.outputs.forEach { output ->
            output.outputFileName.set("MyPurePlayer-$appVersionName-$buildType.apk")
        }
    }
}

// AGP 9 移除 android.kotlinOptions，Kotlin 编译选项改到顶层 kotlin 块
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    // MainActivity 继承 FragmentActivity；Activity Result API 必须配套 fragment，
    // 否则目录选择器会抛 “Can only use lower 16 bits for requestCode”。
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    // 云端备份的定时上传：WorkManager 负责「联网 + 到点才跑」，省电策略下由系统择机唤醒
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // 保险箱指纹解锁
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // ---- Jetpack Media3 播放内核 ----
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    // 媒体会话与前台播放服务：通知、媒体键、锁屏与车机控件由库维护
    implementation("androidx.media3:media3-session:$media3Version")
    // 不引入 media3-cast：投屏走项目内独立的 SSDP/SOAP（DLNA）实现，源码里没有任何
    // CastContext / CastPlayer 引用。media3-cast 会把 play-services-cast-framework 与
    // datatransport 整条依赖链拖进来（R8 后仍有 700+ 个类），纯占体积。

    // ASS/SSA 复杂图形字幕渲染：libass 完整样式、动画、卡拉OK、内嵌字体，
    // 解决 Media3 原生 SSA 只渲染纯文本、丢失复杂图形的问题。
    implementation("io.github.peerless2012:ass-media:0.6.0-beta01")

    // FFmpeg 软件解码扩展：本仓库自建的 AAR（libs/media3-ffmpeg-decoder-min.aar），
    // 由 media3 1.9.0 的 JNI 胶水（ffmpeg_jni.cc）+ FFmpeg 6.1.1 静态库编译而成。
    // 手机 ROM 普遍只有 Dolby 的 AC3/EAC3 解码器，DTS / DTS-HD / TrueHD 没有；
    // 走 MediaCodec 就是"找不到解码器"，表现是没声音或直接报错。
    // 只在系统解不了时兜底（EXTENSION_RENDERER_MODE_ON），不影响 AAC/AC3 的硬解与功耗。
    //
    // 相对上游社区包（org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1，1.39MB）：
    //  - 只编入家庭影院实际用到的 7 个解码器：aac / ac3 / eac3 / dca / truehd / mlp / flac，
    //    移除 mp3 / alac / pcm_alaw / pcm_mulaw / aac_latm（前四个系统 MediaCodec 都有，
    //    aac_latm 与 aac 共用解码器、几乎不占体积）；
    //  - configure 用 --disable-everything --disable-network --disable-avformat 等，
    //    CFLAGS 用 -Oz 并去掉 -g，静态链 libc++（原 .so 也不依赖 libc++_shared）；
    //  - 结果：1.39MB → 1.10MB，DT_NEEDED 与 JNI 导出符号（9 个）与原包一致。
    // 许可：FFmpeg 以 LGPL-2.1+ 编译（未启用任何 GPL 组件），比原 AAR 的 GPL-3.0 更宽松。
    implementation(files("libs/media3-ffmpeg-decoder-min.aar"))

    // SMB2/SMB3 客户端：smbj 原生多路复用（同一连接并发多个请求）+ 完整 SMB3 加密/签名，
    // 替代停止维护的 jcifs-ng。只支持 SMB2/3，不再保留 SMB1 回退。
    implementation("com.hierynomus:smbj:0.15.0")
    // smbj 走 slf4j 2.x；项目不需要日志输出，用 nop 绑定避免 "no provider" 告警
    implementation("org.slf4j:slf4j-nop:2.0.18")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
