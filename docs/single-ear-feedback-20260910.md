# 2026-09-10 单耳合并失效分析

后续 `22:10:15` 的 preview.3 反馈已确认 QQ 音乐使用 DIRECT PCM，且出现返回码为 0、实际读回为 false 的请求。具体见 [新反馈分析](single-ear-feedback-221015.md)，其中区分了应用直通输出与蓝牙硬件编码卸载。

这次反馈把问题缩小到了音频控制与实际混音路径：模块收到的状态持续为单耳，但系统单声道请求间歇性失败；用户进一步确认，同一播放场景中手动打开手机系统的单声道也无法补全另一声道。**现有系统单声道后端尚不能视为适配完成。**

审查对象是 `反馈zip包` 中的 `20260910-212501`、`212536`、`212959` 三个 ZIP。三个包的应用版本和运行日志均为 `2.5.0-preview.1 (28)`，设备为 PMA120 / `16.0.10.501(CN01)`，无线耳机 App 为 16.8.3。preview.2 仅增加确认弹窗，因此版本差异不能解释或解决这次声音问题。

## 已确认的事实

三包中的单耳控制状态均为 `known=3 ear=1 box=2`：左右侧均已确认，记录的是左耳佩戴、右耳在仓。失败时没有变成 `unknown_ears`、`host_timeout` 或 `waiting_route`。这说明模块在这些记录中确实发出了音频控制请求；日志不能独立证明传感器状态与每一时刻的实际佩戴完全一致。

排除录制开始时重新发布的旧状态快照后，有五次开启动作：

| 反馈包 | 结果时刻 | 控制结果 | 同一耳机的媒体流暂停时长 |
| --- | --- | --- | --- |
| 212501 | 21:24:41.334 | `mono owned=true` | 约 569 ms |
| 212501 | 21:24:56.569 | `audio_write_failed` | 约 561 ms |
| 212536 | 21:25:27.926 | `audio_write_failed` | 约 659 ms |
| 212959 | 21:29:48.932 | `audio_write_failed` | 约 597 ms |
| 212959 | 21:29:54.248 | `mono owned=true` | 约 561 ms |

证据：212501 ZIP 内 `logcat-bluetooth-root.txt` 第 200、330 行；212536 日志（本地反馈 `20260910-212536/logcat-bluetooth-root.txt`，第 219 行）；212959 第一次结果（本地反馈 `20260910-212959/logcat-bluetooth-root.txt`，第 201 行）和第二次结果（本地反馈 `20260910-212959/logcat-bluetooth-root.txt`，第 280 行）。暂停时长依据 STOPPED / SUSPENDED 与后续 STARTED 的日志时间差，未测量实际静音波形。

成功和失败均伴随同一耳机媒体流的暂停、恢复。因此短暂断音本身不能证明合并成功，也不能据此认定切到了手机扬声器。

**实际播放使用 Qualcomm A2DP 硬件卸载。** 212536 第 330–333 行（本地反馈 `20260910-212536/logcat-bluetooth-root.txt`，第 330 行）明确记录 `is_software_audio_config: 0`、`is_a2dp_offload_audio_config: 1` 以及 `btaudio_offload_aidl` 的 LHDC 配置。212501 ZIP 内同名日志第 408、411 行一致；212959 第 210、289 行记录恢复时 offload 仍在运行。没有切换到 Bluetooth 进程软件编码路径的证据。

用户在本轮对照测试中关闭模块自动合并，再手动开启系统设置的“单声道音频”，结果仍缺少一边。这表明问题还有“系统功能未覆盖当前实际播放内容”的层面；仅修改开关确认、写入重试或成功判定，无法据此保证声音被混合。

## 实现与证据的边界

[MonoOverride](../app/src/main/java/xyz/melodylsp/codec/mono/MonoOverride.java#L91) 的成功条件是 AudioSystem 返回成功、随后 `getMasterMono` 为 true。它验证的是策略标志，没有读取播放 PCM，也没有验证音频效果。原来的“左右声道已合并”文案超出了这一证据范围。

`audio_write_failed` 同时涵盖非零返回码、调用异常，以及返回成功但读回为 false。旧包没有记录具体返回码或前后值，不能从这个字符串判断究竟是接口拒绝、服务重建、外部覆盖还是 ROM 行为。反复重置是一种待查可能，现有日志没有证明发生了这种情况。

[现有 LHDC encode Hook](../app/src/main/cpp/native_lhdc_patch.cpp#L687) 只在 Bluetooth 进程的 `liblhdcv5BT_enc.so` 软件入口记录 handle 和调用时间，之后把 PCM 原样交给原函数。结合实际 offload 配置，推断这个入口很可能被当前音频流绕过。不能直接在这里加平均混音就宣称覆盖本机；必须先验证真实调用、缓冲区格式与帧计数。

需要区分两类“卸载”：AudioPolicy 的压缩媒体播放卸载与蓝牙 A2DP 编码卸载并非同一层。AOSP 的系统 mono 会关闭带 `COMPRESS_OFFLOAD` 标志的输出并对其余输出更新 mono，但这不能单独证明本机的蓝牙硬件路径会怎样处理。上面的 A2DP offload 标志与用户听音结果共同提供排查方向，仍不足以证明硬件卸载是唯一根因。[AOSP AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/8a778855da3453b9ab3940d2d60fae93dc005bf4/services/audiopolicy/managerdefault/AudioPolicyManager.cpp)

三包缺少 AudioPolicy / AudioFlinger 明细、原始 API 返回值和实际 PCM 内容。`state.json` 还漏列了已经存在于诊断偏好中的 mono 三项状态。录制开始的 `scope.*` / `mono.*` 是缓存状态重发，不能把这些时间当成新进程启动时间；`streaming=false bitrate=0` 依赖软件编码器捕获，也不能解释为耳机没有播放。

## 本轮完成的诊断改进

`2.5.0-preview.3` 保留当前控制策略和 preview.2 的确认弹窗，增加以下证据：

- `mono.audio.write` 记录每次真实音频写入的原始返回码、异常、前后策略标志、系统保存值和耗时，使用原控制器已有的读回调用。
- 反馈包新增 `audio-state.txt`，在生成时只读采集当前用户的系统 mono 设置、A2DP offload 属性、AudioPolicy 和 AudioFlinger 输出。各段带时间，服务错误或超时也会保留；不采集声音。
- root 持续日志补充 AudioSystem、AudioPolicy、AudioFlinger、AudioService 和 A2DP offload 标签；`state.json` 补齐 mono 三项。
- 面板改为“系统单声道已开启 · 混音效果待确认”，避免把策略读回当成已验证的混音。

本版用于定位，**未修复实际混音**。没有修改硬件卸载属性、编解码器、PCM 数据或系统保存的 mono 偏好。

下一次只需保持原播放器和编码，开始记录后在单耳场景开启一次功能，等约 5–10 秒，保留开关开启及音乐播放状态生成反馈。分析新包时，先确认接口成功与否，再对照 AudioPolicy 的实际 mono 值、输出 flags 和 AudioFlinger 线程，以决定适配音频服务路径还是验证软件 PCM 入口。

如果后续进行软件路径对照，需要使用设备实际提供的开发者设置并重启验证；不能把“禁用蓝牙 A2DP 硬件卸载”当成本轮已经验证的修复。AOSP 对该设置使用持久属性并提供重启确认，ColorOS 的可用性和音质、功耗影响仍需本机确认。[AOSP 开发者设置实现](https://android.googlesource.com/platform/packages/apps/Settings/+/5a0d63c5cd7/src/com/android/settings/development/BluetoothA2dpHwOffloadPreferenceController.java)

本机没有连接 ADB 设备，因此本轮没有远程操作手机，也没有完成新包的真机听音验证。
