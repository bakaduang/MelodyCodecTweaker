package xyz.melodylsp.codec.host;

/**
 * Hard-coded user-facing strings. We deliberately avoid {@code context.getString(R.string.x)}
 * inside the host process: the module APK has its own resource id range that happens to
 * collide with arbitrary host resources, so {@code Resources#getText(int)} resolves to
 * unrelated XML resources from the host APK. Using literal strings sidesteps the entire
 * resource subsystem.
 */
public final class Strings {

    public static final String CODEC_BLOCK_TITLE = "蓝牙音质";

    public static final String CODEC_MODE_OPTION_TITLE = "编解码器";
    public static final String QUALITY_OPTION_TITLE = "播放质量";
    public static final String SAMPLE_RATE_OPTION_TITLE = "采样率";
    public static final String REMEMBER_TOGGLE_TITLE = "记住此耳机的选择";
    public static final String REMEMBER_TOGGLE_SUMMARY =
            "关闭时每次重连按系统默认值；开启后下次连接会自动应用你上次的设置。";

    public static final String AUTO_MONO_TITLE = "单耳自动合并声道";
    public static final String AUTO_MONO_DIALOG_TITLE_ON = "开启单耳自动合并声道";
    public static final String AUTO_MONO_DIALOG_TITLE_OFF = "关闭单耳自动合并声道";
    public static final String AUTO_MONO_DIALOG_MSG_ON =
            "开启后，仅佩戴一只耳机时会合并 QQ 音乐和网易云音乐的左右声道，双耳佩戴时保留原声道。需要先启用这两个播放器的模块作用域并重启。是否继续？";
    public static final String AUTO_MONO_DIALOG_MSG_OFF =
            "关闭后，将停止自动合并左右声道，保留播放器的原始声道。是否继续？";
    public static final String AUTO_MONO_EXPERIMENTAL_NOTE =
            "支持 QQ 音乐和网易云音乐；需要启用对应播放器的模块作用域。";
    public static final String AUTO_MONO_SUMMARY_LOADING = "正在获取设置…";
    public static final String AUTO_MONO_SUMMARY_WAITING_SERVICE = "等待系统服务响应";
    public static final String AUTO_MONO_SUMMARY_WAITING_HOOK = "已开启，等待佩戴状态检测就绪";
    public static final String AUTO_MONO_SUMMARY_OFF = "已关闭";
    public static final String AUTO_MONO_SUMMARY_NO_DEVICE = "等待识别当前耳机";
    public static final String AUTO_MONO_SUMMARY_WAITING_CONNECTION = "已开启，等待耳机连接";
    public static final String AUTO_MONO_SUMMARY_WAITING_EARS = "已开启，等待左右耳佩戴状态";
    public static final String AUTO_MONO_SUMMARY_LEFT_ONLY = "仅左耳佩戴";
    public static final String AUTO_MONO_SUMMARY_RIGHT_ONLY = "仅右耳佩戴";
    public static final String AUTO_MONO_SUMMARY_MERGED = "左右声道已合并";
    public static final String AUTO_MONO_SUMMARY_STEREO = "双耳佩戴 · 保留原声道";
    public static final String AUTO_MONO_SUMMARY_NEITHER = "双耳均未佩戴 · 已停止自动合并";
    public static final String AUTO_MONO_SUMMARY_SYSTEM_MONO = "系统原本已开启单声道，保留原设置";
    public static final String AUTO_MONO_SUMMARY_RESTORING = "正在恢复原音频设置…";
    public static final String AUTO_MONO_TOAST_REQUEST_FAILED = "单耳自动合并请求未发送，请稍后重试";

    /** Bridge reason codes stay out of the host UI, including when a future code is unknown. */
    public static String autoMonoReason(String reason) {
        if (reason == null || reason.isEmpty()) return "等待状态更新";
        switch (reason) {
            case "unknown_ears": return "等待左右耳佩戴状态";
            case "debouncing": return "正在确认佩戴状态…";
            case "restore_failed": return "系统音频恢复尚未确认，正在重试";
            case "disconnected": return "等待耳机连接";
            case "both_ears": return "双耳佩戴，使用原音频设置";
            case "neither_ear": return "双耳均未佩戴，使用原音频设置";
            case "route_unknown": return "等待确认声音输出设备";
            case "other_route": return "声音正在输出到其他设备";
            case "multiple_outputs": return "存在多个声音输出，暂不合并";
            case "call_active": return "通话期间暂停自动合并";
            case "user_override": return "已按你的系统音频设置暂停，关闭后重新开启可恢复自动控制";
            case "host_timeout": return "佩戴状态服务暂未响应";
            case "master_disabled": return "模块总开关已关闭";
            case "audio_api_unavailable": return "当前系统暂不支持自动合并";
            case "audio_write_failed": return "系统未接受音频设置，请稍后重试";
            case "journal_failed": return "无法保存音频恢复信息，暂不合并";
            case "query_timeout": return "系统服务暂未响应，请稍后重试";
            case "hook_unavailable": return "佩戴状态检测不可用，请重启无线耳机 App 后重试";
            case "state_conflict": return "耳机状态不一致，等待重新上报";
            case "pcm_player_not_loaded": return "请启用 QQ 音乐或网易云音乐的模块作用域，并重启播放器";
            case "pcm_hook_unavailable": return "播放器音频处理入口不可用，请查看模块诊断";
            case "pcm_bridge_unavailable": return "播放器控制服务不可用，请重启手机";
            case "pcm_waiting_audio": return "等待 QQ 音乐或网易云音乐播放音频";
            case "pcm_track_route": return "播放器正在切换输出，等待确认耳机通路";
            case "pcm_format_unsupported": return "当前播放格式暂不支持自动合并";
            case "pcm_mixed": return "左右声道已合并";
            default: return "当前状态暂不可用，请稍后重试";
        }
    }

    public static final String STATE_NO_DEVICE = "未连接";
    public static final String STATE_CODEC_UNKNOWN = "暂时无法获取编解码器信息";
    public static final String STATE_A2DP_WAITING = "等待耳机就绪";
    public static final String STATE_SWITCHING_CODEC = "正在切换…";

    public static final String BANNER_VIA_SETTINGS = "已写入开发者选项";
    public static final String BANNER_VIA_ROOT = "已通过 root 写入开发者选项并确认生效";
    public static final String TOAST_APPLY_FAILED = "切换未生效，请重试";
    public static final String TOAST_NATIVE_PATCH_UNSUPPORTED = "未适配，请联系开发者反馈";
    public static final String TOAST_NATIVE_PATCH_PARTIAL =
            "未完整适配，强行使用可能出现异常卡顿";
    public static final String TOAST_A2DP_WAITING = "等待耳机就绪";
    public static final String TOAST_CODEC_MODE_UNSUPPORTED = "当前耳机不支持高品质编解码器切换";
    public static final String TOAST_LHDC_CEILING_900 = "1Mbps码率应用失败，耳机侧报告限制最大码率为900kbps";

    public static final String FRESHNESS_LABEL_FORMAT = "上次同步：%s";
    public static final String QUALITY_UNKNOWN_VALUE_FORMAT = "未知档位（specific1=%s）";

    public static final String CODEC_LABEL_SBC = "SBC";
    public static final String CODEC_LABEL_AAC = "AAC";
    public static final String CODEC_LABEL_APTX = "aptX";
    public static final String CODEC_LABEL_APTX_HD = "aptX HD";
    public static final String CODEC_LABEL_APTX_ADAPTIVE = "aptX Adaptive";
    public static final String CODEC_LABEL_LDAC = "LDAC";
    public static final String CODEC_LABEL_LHDC = "LHDC";
    public static final String CODEC_LABEL_OPUS = "Opus";
    public static final String CODEC_LABEL_LC3 = "LC3";

    public static final String QUALITY_LDAC_990 = "990 kbps（音质优先）";
    public static final String QUALITY_LDAC_660 = "660 kbps（标准）";
    public static final String QUALITY_LDAC_330 = "330 kbps（连接优先）";
    public static final String QUALITY_LHDC_CONNECTION = "64 kbps";
    public static final String QUALITY_LHDC_STANDARD = "标准";
    public static final String QUALITY_LHDC_LOW_400 = "400 kbps";
    public static final String QUALITY_LHDC_MID_500 = "连接优先";
    public static final String QUALITY_LHDC_FIXED_900 = "音质优先";
    public static final String QUALITY_LHDC_FIXED_1000 = "音质优先";
    public static final String QUALITY_LHDC_ABR = "自适应";
    public static final String QUALITY_LHDC_BALANCED = "自适应";
    public static final String QUALITY_LHDC_HIGH = "音质优先";
    public static final String CODEC_MODE_HIGH_QUALITY = "高品质";
    public static final String CODEC_MODE_STANDARD = "标准";

    // LE Audio switch (TODO B1 / B2). The confirmation dialog text lives in
    // leaudio.LeAudioStrings because the dialog is shown from the wirelesssettings process.
    public static final String LE_AUDIO_TITLE = "LE Audio";
    public static final String LE_AUDIO_SUMMARY_ON = "已开启（LC3 低功耗音频）";
    public static final String LE_AUDIO_SUMMARY_CONNECTING = "已开启，正在连接 LE Audio…";
    public static final String LE_AUDIO_SUMMARY_OFF = "已关闭（经典蓝牙音频）";
    public static final String LE_AUDIO_SUMMARY_UNKNOWN = "正在获取状态…";
    public static final String STATE_LE_AUDIO_CONNECTING = "LE Audio 正在连接…";

    private Strings() {
    }
}
