package com.yaoahos.mypureplayer.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 带音频直通能力的渲染器工厂。
 *
 * ## 为什么必须有这个类
 *
 * Media3 的 [DefaultRenderersFactory.buildAudioSink] 只做三件事：
 * `DefaultAudioSink.Builder(context)` + `setEnableFloatOutput` +
 * `setEnableAudioOutputPlaybackParameters`，**没有把设备的音频能力集交给 AudioSink**。
 * 于是 [DefaultAudioSink] 用的是 `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES`
 * （只标了 PCM）—— 它认为任何位流都不能直通，于是：
 *
 *  - AC3 / E-AC3 / DTS / TrueHD 一律先解码成 PCM 再输出；
 *  - 接入支持 Dolby 的 HDMI 功放 / USB DAC 时，功放收不到原始位流，
 *    全景声（E-AC3 JOC / TrueHD Atmos）的对象音频信息在解码时被丢弃；
 *  - 设备自带的高规格解码器（这台机器有 `OMX.dolby.eac3-joc.decoder`）用不上。
 *
 * 补上 `setAudioCapabilities(AudioCapabilities.getCapabilities(context))` 之后，
 * Media3 会按系统报告的直通能力挑路径：能直通就位流输出，不能就仍然解成 PCM。
 * 手机外放走后者，行为与之前一致；接 HDMI / 功放时才多出这条直通路径。
 *
 * ## 已知限制
 *
 * 能力集是在播放器创建时读一次的，插拔 HDMI 后需要重启应用（或重新进视频）
 * 才会按新设备重新判定。Media3 没有提供运行中更新能力集的公开接口，
 * 而重建播放器会打断正在播的内容，代价更大。
 */
internal class AppRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val capabilities = AudioCapabilities.getCapabilities(context)
        Log.i(TAG, "音频输出能力: ${describe(capabilities)}")
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioCapabilities(capabilities)
            .build()
    }

    /** 把能力集翻成一行可读日志，接功放排查时直接看这行。 */
    private fun describe(capabilities: AudioCapabilities): String {
        val encodings = listOf(
            C.ENCODING_AC3 to "AC3",
            C.ENCODING_E_AC3 to "E-AC3",
            C.ENCODING_E_AC3_JOC to "E-AC3 JOC(Atmos)",
            C.ENCODING_DTS to "DTS",
            C.ENCODING_DTS_HD to "DTS-HD",
            C.ENCODING_DOLBY_TRUEHD to "TrueHD",
            C.ENCODING_AC4 to "AC-4",
        )
        val passthrough = encodings
            .filter { capabilities.supportsEncoding(it.first) }
            .joinToString("、") { it.second }
        return if (passthrough.isEmpty()) "仅 PCM（无直通）" else "可直通 $passthrough"
    }

    private companion object {
        const val TAG = "Playback"
    }
}
