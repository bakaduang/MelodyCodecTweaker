# AudioTrack 初始化入口适配 preview.6

版本：`2.5.0-preview.6`，versionCode `33`。适用范围仍是 Android 16 / arm64，QQ 音乐和网易云音乐共用这处原生适配。

## 根因与证据

`OPlusHeadsetAudioHelper-feedback-20260911-001335/logcat-bluetooth-root.txt` 第 187–193 行已证明：

- 模块原生库通过 `module_native_dir` 加载成功，LSPosed Native API 版本为 2。
- 系统 `/system/lib64/libaudioclient.so` 已找到并解析成功，Build ID 为 `e5ed64db3ed9ddca14c494145a509ee6`。
- 八个其他必需符号均可解析，只有 `AudioTrack::set` 的完整名称不匹配。

用户提供的 `lib64提取/lib64提取/libaudioclient.so` 大小为 1,158,544 字节，Build ID 与运行时完全一致，SHA-256：

```text
f485f6e2ac871f8dbedbfd9645a71aaa54a6989d29886cc29725e314dd1c988d
```

该文件唯一导出的 `AudioTrack::set` 位于相对地址 `0xc65b8`，符号大小 6096 字节。两个参数在 C++ 符号名称中的类型不同：

| 参数 | preview.5 预期名称 | 实际系统库名称 | ARM64 传递方式 |
| --- | --- | --- | --- |
| 第 4 项声道掩码 | `unsigned int` | `audio_channel_mask_t` | `w4`，32 位 |
| 第 11 项会话 ID | `int` | `audio_session_t` | 入口 `SP+0x18`，32 位 |

实际机器码也验证了其余参数位置和宽度：18 个显式参数顺序一致，播放速度仍位于 `s0`，引用仍按地址传递。因此可以沿用原来的包装函数，只增加这一个完整且精确的命名枚举签名。

## 修改

1. 同时识别原来的整数别名签名和本次确认的命名枚举签名，记录选中的 `hook.abi audio_track_set=named_audio_enums`。不使用函数名前缀或猜测的对象偏移。
2. 两个名称若指向同一地址，只安装一次；若指向不同地址，报告 `symbol_ambiguous_set` 并停止安装，避免只覆盖一个重载。都不匹配时报告 `symbol_unavailable_set`。
3. 修复反馈 ZIP 的条目列表：此前采集器虽尝试读取 `libaudioclient.so`，归档代码却只写入两份蓝牙库。本版将音频库与来源、大小、哈希一起写入；无法读取时在 manifest 记录 missing。
4. 保留 preview.5 的绝对路径加载、原始异常回传、两个播放器作用域和开关确认弹窗。

该修复只调整入口匹配与反馈归档，PCM 算法、音频热路径和租约控制不变。本地通过实际 ELF 核对全部必需入口与地址条件，并增加符号选择和 ZIP 二进制归档回归测试。系统音频库仅用于本地分析，不打进模块 APK。

## 安装与验证

覆盖安装 preview.6，保留原四个作用域及 QQ 音乐、网易云音乐，重启手机。

分别在两个播放器中播放 `stereo-channel-check.wav`：仅戴左耳或仅戴右耳，开启后都应听到一个长音和两个短音；双耳应恢复左右分离。真机日志应先出现 `hook.abi`、`hook.ready`，播放后再出现 `track.set` 和增长的 `frames`。入口安装成功本身不代表已经处理音频。

如果仍失败，保持音乐播放与开关开启，记录约 10 秒后生成反馈。这次会包含音频库（可读取时），并明确显示缺失或歧义的初始化入口。当前本地检查不能代替真机 Hook 安装、处理计数和听音确认。

混音位置、路由限制及测试方法详见 [preview.4 说明](player-pcm-preview4.md)，加载修复详见 [preview.5 说明](player-pcm-preview5.md)。
