package com.kmusic.apkhd.playback

/** Source PCM evidence, not a file's advertised quality or a DAC's maximum capability. */
data class UsbPcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    val channelMask: Int = 0
)

data class UsbSourceFormat(
    val pcm: UsbPcmFormat,
    val precisionKnown: Boolean = false,
    val convertedFromDsd: Boolean = false
)

data class UsbStrictConstraints(
    val dspBypassed: Boolean = false,
    val normalizationDisabled: Boolean = false,
    val truePeakDisabled: Boolean = false,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val offloadDisabled: Boolean = false
) {
    val satisfied: Boolean get() = dspBypassed && normalizationDisabled && truePeakDisabled &&
        offloadDisabled && speed == 1f && pitch == 1f && volume == 1f
}

data class UsbMixerOption(val format: UsbPcmFormat, val bitPerfect: Boolean)

enum class UsbOutputStatus { DISABLED, NO_USB, UNSUPPORTED, REQUESTED, CONFIGURATION_MATCHED, UNSATISFIED }

enum class UsbOutputReason {
    DISABLED, NO_USB, API_TOO_OLD, FOCUS_SUSPENDED, SOURCE_UNKNOWN, DSD_CONVERTED,
    SOURCE_PRECISION_UNKNOWN, SOURCE_ENCODING_UNSUPPORTED, CHANNEL_LAYOUT_UNKNOWN,
    STRICT_CONSTRAINTS, NO_BIT_PERFECT_MIXER, SOURCE_FORMAT_MISMATCH, OUTPUT_UNKNOWN,
    OUTPUT_FORMAT_MISMATCH, PREFERENCE_REJECTED, PREFERENCE_UNCONFIRMED,
    PLATFORM_ERROR, CLEAR_FAILED, CONFIGURATION_MATCHED, CLOSED
}

data class UsbOutputSnapshot(
    val enabled: Boolean = false,
    val status: UsbOutputStatus = UsbOutputStatus.DISABLED,
    val reason: UsbOutputReason = UsbOutputReason.DISABLED,
    val source: UsbSourceFormat? = null,
    val route: OutputRoute? = null,
    val applicationOutput: ApplicationAudioFormat? = null,
    val requestedMixer: UsbMixerOption? = null,
    val preferredMixerConfirmed: Boolean = false,
    val applicationFormatMatches: Boolean = false
) {
    val reasonText: String get() = when (reason) {
        UsbOutputReason.DISABLED -> "USB高保真未启用"
        UsbOutputReason.NO_USB -> "没有已确认的活动USB音频路由"
        UsbOutputReason.API_TOO_OLD -> "需要Android 14及以上公开mixer API"
        UsbOutputReason.FOCUS_SUSPENDED -> "焦点暂停：已清除USB偏好，不主动请求焦点"
        UsbOutputReason.SOURCE_UNKNOWN -> "源PCM格式未知，不能验证精确匹配"
        UsbOutputReason.DSD_CONVERTED -> "DSD已转PCM，不是native DSD或DoP"
        UsbOutputReason.SOURCE_PRECISION_UNKNOWN -> "原始PCM精度未知，不是bit-perfect验证"
        UsbOutputReason.SOURCE_ENCODING_UNSUPPORTED -> "首期仅支持源PCM16/float；24/32位转换回退不是bit-perfect"
        UsbOutputReason.CHANNEL_LAYOUT_UNKNOWN -> "多声道布局尚未支持精确验证"
        UsbOutputReason.STRICT_CONSTRAINTS -> "需要关闭DSP、归一、true-peak、offload，并保持变速/音高/软件音量为1"
        UsbOutputReason.NO_BIT_PERFECT_MIXER -> "设备未公开支持BIT_PERFECT mixer"
        UsbOutputReason.SOURCE_FORMAT_MISMATCH -> "没有与源采样率/声道/编码完全一致的mixer；不强制升频"
        UsbOutputReason.OUTPUT_UNKNOWN -> "等待新AudioTrack实际应用输出格式确认"
        UsbOutputReason.OUTPUT_FORMAT_MISMATCH -> "AudioTrack输出与源/mixer不一致，已请求普通输出回退"
        UsbOutputReason.PREFERENCE_REJECTED -> "系统拒绝USB mixer偏好，普通输出回退"
        UsbOutputReason.PREFERENCE_UNCONFIRMED -> "已请求USB mixer偏好，尚未确认配置匹配"
        UsbOutputReason.PLATFORM_ERROR -> "平台查询或监听失败，不能确认配置匹配"
        UsbOutputReason.CLEAR_FAILED -> "系统未确认偏好清除，阻止新的USB偏好请求"
        UsbOutputReason.CONFIGURATION_MATCHED -> "公开mixer偏好与AudioTrack格式匹配；未验证独占或端到端样本一致"
        UsbOutputReason.CLOSED -> "控制器已销毁并清除偏好"
    }

    /** Matching public configuration is not proof of exclusive access or end-to-end sample identity. */
    val statusText: String get() = when (status) {
        UsbOutputStatus.DISABLED -> "已关闭"
        UsbOutputStatus.NO_USB -> "无USB输出"
        UsbOutputStatus.UNSUPPORTED -> "不支持"
        UsbOutputStatus.REQUESTED -> "已请求（等待配置确认）"
        UsbOutputStatus.CONFIGURATION_MATCHED -> "配置匹配（非端到端直通验证）"
        UsbOutputStatus.UNSATISFIED -> "不满足（普通输出回退）"
    }
}

data class UsbMixerSelection(val option: UsbMixerOption? = null, val reason: UsbOutputReason)

object UsbOutputPolicy {
    private const val PCM_16 = 2
    private const val PCM_FLOAT = 4

    fun isUsb(deviceType: Int): Boolean = deviceType == 11 || deviceType == 12 || deviceType == 22

    fun sourceReason(source: UsbSourceFormat?, constraints: UsbStrictConstraints): UsbOutputReason? = when {
        !constraints.satisfied -> UsbOutputReason.STRICT_CONSTRAINTS
        source == null -> UsbOutputReason.SOURCE_UNKNOWN
        source.convertedFromDsd -> UsbOutputReason.DSD_CONVERTED
        !source.precisionKnown -> UsbOutputReason.SOURCE_PRECISION_UNKNOWN
        source.pcm.encoding != PCM_16 && source.pcm.encoding != PCM_FLOAT -> UsbOutputReason.SOURCE_ENCODING_UNSUPPORTED
        source.pcm.sampleRate <= 0 || source.pcm.channelCount <= 0 -> UsbOutputReason.SOURCE_UNKNOWN
        source.pcm.channelCount > 2 -> UsbOutputReason.CHANNEL_LAYOUT_UNKNOWN
        else -> null
    }

    fun select(source: UsbSourceFormat?, constraints: UsbStrictConstraints, options: List<UsbMixerOption>): UsbMixerSelection {
        sourceReason(source, constraints)?.let { return UsbMixerSelection(reason = it) }
        val bitPerfect = options.filter { it.bitPerfect }
        if (bitPerfect.isEmpty()) return UsbMixerSelection(reason = UsbOutputReason.NO_BIT_PERFECT_MIXER)
        val pcm = requireNotNull(source).pcm
        val expectedMask = pcm.channelMask.takeIf { it != 0 } ?: if (pcm.channelCount == 1) 4 else 12
        val match = bitPerfect.firstOrNull {
            it.format.sampleRate == pcm.sampleRate && it.format.channelCount == pcm.channelCount &&
                it.format.encoding == pcm.encoding && it.format.channelMask == expectedMask
        }
        return UsbMixerSelection(match, if (match == null) UsbOutputReason.SOURCE_FORMAT_MISMATCH else UsbOutputReason.PREFERENCE_UNCONFIRMED)
    }

    fun outputMatches(source: UsbSourceFormat?, mixer: UsbMixerOption, output: ApplicationAudioFormat?): Boolean =
        output != null && output.isPcm && source != null && mixer.bitPerfect &&
            source.pcm.sampleRate == output.sampleRate && source.pcm.channelCount == output.channelCount &&
            source.pcm.encoding == output.encoding && mixer.format.sampleRate == output.sampleRate &&
            mixer.format.channelCount == output.channelCount && mixer.format.encoding == output.encoding
}
