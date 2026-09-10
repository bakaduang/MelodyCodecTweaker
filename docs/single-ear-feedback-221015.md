# 22:10:15 新反馈：单声道状态未保留与 QQ 音乐直通输出

反馈包：`OPlusHeadsetAudioHelper-feedback-20260910-221015.zip`。本次应用与运行日志都已是 `2.5.0-preview.3 (30)`，新增音频诊断正常工作。

**新证据把问题分成了两层：音频接口返回成功，但单声道状态有时立即读回为关闭；实际播放的 QQ 音乐走 DIRECT PCM，绕过了标准 Android 实现中负责系统单声道混合的普通 MIXER 路径。** 单纯增加重试或只修开关 UI，不能保证补全另一声道。

## 1. 写入结果已经明确

下面只计入录制期间的真实调用。录制开始时重放的 sequence=4 是旧快照，未重复计算。

| 时间 | sequence | 请求 | 返回码 | 调用前 → 后 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 22:09:56.400 | 5 | 开启 | 0 | false → false | 未确认开启，报告失败 |
| 22:10:01.792 | 6 | 开启 | 0 | false → true | 策略标志读回成功 |
| 22:10:07.355 | 7 | 关闭 | 0 | true → false | 正常恢复 |
| 22:10:11.189 | 8 | 开启 | 0 | false → false | 未确认开启，报告失败 |

证据：sequence 5（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 372 行）、sequence 6（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 802 行）、sequence 7（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 1026 行）、sequence 8（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 1168 行）。

这些调用的 `error=none read_error=none`，调用 UID 为 Bluetooth 的 1002。开启调用耗时 390–452 ms，关闭耗时 4 ms。此次失败的具体条件是“返回 0，但读回不匹配”，没有权限拒绝或 Java 反射异常的证据。

sequence 5、8 的 false 是控制器执行失败恢复前的第一次读回。它们之后也没有紧随的 `target=false` 写入记录。因此不能把这些失败解释成模块先成功开启、又主动调用关闭造成的；当前日志没有记录究竟是谁或哪段厂商逻辑使状态未能保留。

22:10:16 生成快照时：

- 模块开关仍为 enabled=true，单耳状态为 `known=3 ear=1 box=2`，最终状态为 `unavailable / audio_write_failed`。模块状态（本地反馈 `20260910-221015/state.json`，第 24 行）
- 用户保存的 `master_mono=0`。系统偏好（本地反馈 `20260910-221015/audio-state.txt`，第 5 行）
- AudioPolicy 实际 `Master mono: off`。策略状态（本地反馈 `20260910-221015/audio-state.txt`，第 53 行）

保存值为 0 符合当前模块“不修改用户持久偏好，只临时改变音频引擎”的设计；它本身不是权限错误。但 AudioService 会根据保存值重新应用 mono，临时值与保存值不一致存在被覆盖的可能。该机制在 Android 16 的 `SettingsObserver.onChange` / `updateMasterMono` 中可见；本机日志也出现了路由重建和 SettingsObserver 通知。**这是有依据的排查方向，尚未捕获实际覆盖者，不能直接把它认定为已经证明的唯一原因。** [AOSP AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/AudioService.java)

## 2. 真正的播放路径是 QQ 音乐 DIRECT PCM

活动音轨 PID 25868、UID 10495、session 73 归属 `com.tencent.qqmusic`。进程与音轨映射（本地反馈 `20260910-221015/audio-state.txt`，第 2417 行）

该音轨使用：

| 层级 | 本包实际状态 |
| --- | --- |
| 应用送往 AudioFlinger | PCM_FLOAT、96000 Hz、双声道 |
| 输出 profile / handle | `direct_pcm_out` / 181 |
| AudioFlinger 线程 | type 1 DIRECT，非 standby，正在写入 |
| 普通混音 / 效果链 | `hasFastMixer=0`，活动音轨效果链为 0 |
| 目标设备 | Bluetooth A2DP，当前耳机 |
| 蓝牙编码会话 | `A2DP_HARDWARE_OFFLOAD_ENCODING_DATAPATH` |

证据：活动输出（本地反馈 `20260910-221015/audio-state.txt`，第 1927 行）、DIRECT 线程（本地反馈 `20260910-221015/audio-state.txt`，第 2928 行）、音轨与效果链（本地反馈 `20260910-221015/audio-state.txt`，第 3002 行）、重建音轨参数（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 1442 行）。

标准 Android 16 的 `PlaybackThread` 默认不要求 mono blending，`MixerThread` 才覆盖 mono 处理；公共播放循环也明确将这段混合限定于 mixer，DIRECT 的循环主要复制音轨缓冲区到输出。**因此即使修复策略标志的保持，也不能据此认为这条 DIRECT PCM 流会执行相同混音。** 该结论来自标准实现；ColorOS 是否有额外厂商处理仍需区别验证，但它与用户“手动开启系统单声道也无效”的测试结果一致。[AOSP Threads.h](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/audioflinger/Threads.h)、[AOSP Threads.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/audioflinger/Threads.cpp)

这里需要修正上一轮仅以“硬件卸载”为重点的描述：**应用侧是 DIRECT PCM，蓝牙侧才是硬件编码卸载，二者是不同环节。** `isOffloadThread=0` 与 A2DP hardware offload 同时存在并不矛盾。单独关闭蓝牙硬件卸载，也不保证 QQ 音乐会退出 DIRECT。

应用输入 96 kHz 不等于蓝牙最终编码采样率。本包蓝牙恢复日志中的 LHDC 参数是 48 kHz / 24 bit。LHDC 配置（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 1268 行） 双声道 channel mask 也不能作为“没有合并”的独立证据，因为两边相同的混合信号仍可装在双声道容器中；本包没有实际音频采样。

## 3. 断音与弹窗警告

失败前出现 `dead IAudioTrack` 和 `Allow Restore for Hires track`，随后 A2DP 停流，重新创建的仍然是 `direct_pcm_out`。22:10:10 的重建（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 1104 行） 这能解释切换时声音中断，却没有可靠获得混音结果。

`state.json` 中的 `ClassNotFoundException: o6.C1381b` 来自确认弹窗的候选类探测。后面成功使用 `builder=F2.e`，而且确实发出了对应音频请求，所以它不是这次单声道失效的原因。实际弹窗 builder（本地反馈 `20260910-221015/logcat-bluetooth-root.txt`，第 1373 行）

## 4. 修复方向

当前证据已足以确定下一步重点，不需要继续发同一类日志增强版：

1. 先用同一声道测试音验证普通 MIXER 路径，尽量保留相同蓝牙编码配置以隔离应用 DIRECT 的影响。可使用 QQ 音乐提供的普通输出选项（若该版本提供），或普通播放器作对照；具体菜单名与能否退出 DIRECT 不能由本包断言。
2. 系统 mono 后端需要协调音频引擎与系统重新应用设置的行为，并做路由稳定后的确认。仅延迟重试不能解决 DIRECT 不参与标准混音的问题。
3. 若必须保留 QQ 音乐当前的直通输出，就需要在真实经过的 PCM 环节处理双声道，或适配厂商音频处理能力。当前模块的四个作用域均不包含 QQ 音乐、system_server 或 audioserver；现有 Bluetooth 软件 LHDC encode Hook 也没有证据覆盖这条硬件编码路径。需要明确新增接入点后再实现，不能用一次 API 成功读回替代声音验证。

本轮完成反馈与源码对照，新增此报告；没有改动运行时代码或生成新 APK，也没有完成 MIXER 对照或真机修复验证。
