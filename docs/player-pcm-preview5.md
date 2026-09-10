# 播放器 PCM 原生库加载修复 preview.5

版本：`2.5.0-preview.5`，versionCode `32`。继续支持当前 Android 16 / arm64 设备中的 QQ 音乐与网易云音乐，保留单耳开关的确认弹窗。

## 本次反馈确认了什么

`OPlusHeadsetAudioHelper-feedback-20260910-234650` 记录的佩戴状态为左右两侧都已知、左耳入耳、右耳在仓。QQ 音乐有两个进程向蓝牙控制服务报告，但均为 `native_load_failed`、`tracks=0`、`frames=0`。说明报告通路已连通，失败集中在 PCM 原生后端初始化阶段。

preview.4 APK 的 `libmelody_pcm_mono.so` 是压缩条目（ZIP 方法 `DEFLATED`），项目设置为 `useLegacyPackaging=true`。原 PCM 代码仅调用 `System.loadLibrary`；LSPosed 的 [LspModuleClassLoader.findLibrary](https://github.com/LSPosed/LSPosed/blob/master/core/src/main/java/org/lsposed/lspd/util/LspModuleClassLoader.java) 对 APK 内路径只接受 `STORED` 条目，因此这两者不匹配。反馈的 `logcat-bluetooth-root.txt` 第 128 行已证明同一模块的 DexKit 能从 `/data/app/.../lib/arm64/libdexkit.so` 加载。

原反馈未保留 PCM 的原始异常，而且旧代码把库加载和 JNI 初始化异常都写成 `native_load_failed`。因此能确认上述加载缺陷，但不能将某条具体的 `UnsatisfiedLinkError` 文案当成已在手机上捕获的事实。

## 修改

- 使用模块自身的 `getModuleApplicationInfo().nativeLibraryDir`，优先以绝对路径加载已解压的 `libmelody_pcm_mono.so`，失败后再尝试原来的按名查找。模块信息来自 [libxposed API 101.0.1](https://github.com/libxposed/api/blob/101.0.1/api/src/main/java/io/github/libxposed/api/XposedInterfaceWrapper.java#L117)，与播放器宿主的 ApplicationInfo 区分。
- 在加载前检查 Android 16 和 64 位进程，避免 32 位播放器误用模块主 ABI 的目录；C++ 继续执行原来的 arm64 与精确符号检查。
- 区分 `native_load_failed`、`native_binding_failed`、`native_install_failed`，保留加载来源、路径、阶段、SDK、进程位数及原始异常。
- 播放器的这些信息随现有身份校验报告回传到蓝牙进程。诊断页“播放器声道合并”的 `native_detail` 保留一条完整结果并优先显示失败进程；`player_detail` 加入 PID，各进程详情变化另写入 `mono.pcm.native` 日志。

本次没有修改 PCM 运算、音频 Hook 热路径或佩戴控制协议。绝对路径加载仍通过 `dlopen`，符合 LSPosed [按已登记库名后缀调用 native_init 的逻辑](https://github.com/LSPosed/LSPosed/blob/master/core/src/main/jni/src/native_api.cpp#L99)。

## 安装与验证

1. 覆盖安装 preview.5 APK，沿用前几版的本地测试签名。
2. 保留原四个 LSPosed 作用域，以及 QQ 音乐和网易云音乐的作用域。
3. 重启手机，确保两个播放器及蓝牙进程都加载新版。
4. 在 QQ 音乐、网易云音乐中分别播放 `stereo-channel-check.wav`，开启单耳合并。仅左耳、仅右耳都应能听到“一个长音”和“两个短音”；双耳时恢复原立体声。

如果状态仍是不可用，保持开关开启和音乐播放，开始记录并等待约 10 秒再生成反馈。新反馈中的 `native_detail` 应直接包含失败阶段与原始原因。`loaded=true` 或 Hook 安装成功只证明该阶段通过；`pcm_mixed` 和增长的帧数才是实际处理证据，最终声音效果仍需听音确认。

本地检查覆盖加载优先级、失败回退、两条异常保留及反馈限长规则，并运行项目单元测试与 release 构建。没有连接真机，本次修复尚不能代替设备上的加载与听音验证。更多混音和路由限制见 [preview.4 说明](player-pcm-preview4.md)。
