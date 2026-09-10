# Enco X3 单耳自动合并声道：APK 分析

> 历史调研记录：本文保留实现前的接口分析和方案判断。当前实现与使用方法见 [README 的单耳自动合并说明](../README.md#单耳自动合并声道)。

分析日期：2026-09-10。目标是单耳佩戴时听到左右声道的混合内容，双耳佩戴时恢复原来的音频设置。

**结论：这版无线耳机 App 确实具有左右耳入耳状态及后台更新通路，检测部分已有具体接入点。建议新增底层佩戴状态观察器和独立单声道控制器。APK 静态分析不能证明目标真机的上报时机和音频处理效果，仍需安装诊断版本验证。**

输入与分析范围：

| 项目 | 内容 |
| --- | --- |
| 用户设备 | OPPO Enco X3；Find X9 Ultra；系统 16.0.10 |
| 用户报告的 App 版本 | 16.8.3_4280da7_260627 |
| APK | `提取apk/无线耳机_16.8.3.apk` |
| Manifest 确认 | `com.oplus.melody`；versionName `16.8.3`；versionCode `16008003`；minSdk 31；targetSdk 36 |
| APK SHA-256 | `07077bfdf0426d0ce6cdf3f3eedcd03d25d03f7b7d9ef2bb94a1f06c09f2c899` |
| DEX | `classes.dex`、`classes2.dex` |
| 分析工具 | JADX 1.5.6；来源为 [官方发布页](https://github.com/skylot/jadx/releases/tag/v1.5.6) |
| 生成目录 | `build/apk-analysis/`，已被项目现有 `build/` 忽略规则覆盖 |

Manifest 只确认到 `16.8.3`，没有独立确认用户界面中的构建后缀。没有修改模块实现，也没有操作手机或验证声音输出。

**已确认的状态含义**

以下路径以 `build/apk-analysis/melody-16.8.3/sources/` 为基目录；行号对应本次输出。

| 证据 | 文件与行号 | 含义 |
| --- | --- | --- |
| 协议解析 | `com/oplus/melody/btsdk/api/data/StatusInfo.java:86` | 每项包含设备类型及状态字节，入耳、在仓、盒盖状态分别解析 |
| 左右映射 | `J6/b.java:16` | `deviceType=1` 为左耳，`2` 为右耳；仓使用 `3` |
| 双耳判断 | `com/oplus/melody/model/repository/earphone/EarStatusDTO.java:55` | 左右分别用 `status & 2` 判断入耳 |
| 状态写入 | `com/oplus/melody/btsdk/api/data/DeviceInfo.java:937` | `setStatusInfo(List)` 替换本次列表，并更新 elapsedRealtime 时间戳 |
| 后台事件 | `com/oplus/melody/btsdk/multidevice/HeadsetCoreService.java:892` | 收到耳状态消息后调用 setter，再分发事件 `0x100016` |

原始协议状态字节的解释是：

```text
inBox      = (rawStatus & 0x01) == 0
inEar      = (rawStatus & 0x02) != 0
coverClose = (rawStatus & 0x04) == 0
```

注意在仓位和盒盖位在原始协议中是反向判断。高层 `EarStatusDTO` 已归一化：bit 1 表示在仓，bit 2 表示入耳。因此原始协议字节不能直接当成 DTO 状态使用。

这能区分“右耳明确未入耳、放在桌上”与“双耳入耳”。但 `StatusInfo` 没有显式 unknown 字段，缺失某侧条目必须由模块另行记录为未知。

**查询与实时通知走同一条更新链**

```text
主动查询 0x0109 → 响应 0x8109 ─┐
                              ├→ StatusInfo 列表
实时耳状态通知 0x8202 ─────────┘
    → HeadsetCoreService handler 消息 12
    → DeviceInfo.setStatusInfo(List)
    → BluetoothReceiveData 事件 0x100016
    → 官方 Repository / 页面 / 其他功能
```

- 查询入口是 `HeadsetCoreService.P(String address)`（`HeadsetCoreService.java:2107`），先检查设备能力 `265/0x0109` 再发送查询。方法名 `P` 是本 APK 的名称，后续版本需要重新定位。
- 查询响应在 `btsdk/protocol/commands/o.java:386`，通知在 `btsdk/protocol/commands/h.java:342`。
- 共用解析器位于 `btsdk/protocol/commands/e.java:218`；它验证数量和长度，每两字节解析一项，**并不保证每次包含左右两侧**。
- `HeadsetCoreService` 属于蓝牙服务链路，不依赖耳机面板 Activity。Manifest 的 `BluetoothService` 未单独指定 `:fg` 进程。

源码支持在页面关闭时监听，锁屏、后台回收及关闭耳机佩戴检测后的实际行为仍需真机确认。初始化可以使用官方能力检查后的查询来补全状态，但不能假定耳机一定回复双侧。

**不能直接复用的几个快捷判断**

1. `J6.b.getLeftInEar()/getRightInEar()` 背后的 `isInEar(List,int)` 对 null 或缺失侧直接返回 false（`J6/b.java:24`）。直接 XOR 会把未知侧解释为未佩戴。
2. 官方 Repository 会继承缺失侧的旧值；没有旧值时左右默认 `1`（在仓）。任一侧更新之后，合并 DTO 的整体时间戳也会更新。这会掩盖某一侧是否在本次连接中真正上报过。
3. 原版 `WearDetectDTO` 有连接时间校验，约以连接时间减 3000 ms 作为参考，但这是防旧连接缓存的判断，不是周期性过期时间，也不能证明继承的另一侧刚刚更新。
4. 原版还有从 BLE 广播构造 `StatusInfo` 的路径（Repository `U.java:1583`）。协议响应、主动通知和广播推算需要区分来源。

第 2 点的主反编译 Java 有类型和循环重建错误，已使用 `build/apk-analysis/earphone-n-fallback.java` 校验关键流程：

- 第 96 行起：读取旧状态，缺失时使用默认值。
- 第 129 行起：遍历本次列表，只覆盖列表中存在的侧。
- 第 156 行起：左右状态为 `(inBox ? 1 : 0) | (inEar ? 2 : 0)`。
- 第 237 行起：保存合并 DTO，然后保存整包接收时间。

实际 DEX 类是 `com.oplus.melody.model.repository.earphone.n`。主输出文件名 `C0827n.java` 是 JADX 为 Windows 大小写冲突生成的别名，不能直接用于运行时 Hook。`AbstractC0816c` 同样是显示别名，原类为 `com.oplus.melody.model.repository.earphone.c`。

**高层 Provider 可作交叉核对，不能仅 Hook 回调就假定一直生效**

`EarphoneControlProvider` 已实现 `onWearChanged(EarphoneDTO)`（第 365 行），左右分别检查 `& 2`，通过 ContentResolver 通知变化。它也有 `melody_method_control_wear` 查询路径，返回 `ear_left`、`ear_right`。

但它存在以下约束：

- `onCreate()` 只注册 URI（第 724 行），没有默认启动佩戴订阅。
- `setWearingListenerStatus(..., 1)` 才订阅 Repository 的活动耳机（第 605 行）。
- 订阅采用 `V7.g.k(...)`，其内部使用 observeForever（JADX 文件 `V7/C0464g.java:141`）；可以借鉴后台订阅方式。
- Provider 受 `com.oplus.permission.safe.IOT` 权限保护（Manifest 第 1164 行）。
- 查询分支使用 `status == 2` 且 null 返回 0（第 913 行）；回调使用位判断，两者不完全等价，也都不提供逐侧 known 信息。

此外，`com.oplus.melody.oneshot.WEAR_STATUS_CHANGE` 广播面向指定的语音助手包，并受功能开关限制（`Z8/q.java:1510`），不适合作为模块的通用状态来源。

**现有声道功能没有提供本需求的现成开关**

- `ChannelSwitchItem` 的中文说明是摘下耳机后将通话及部分应用声音切到手机听筒或扬声器；这是输出设备切换。见 `resources/res/values-zh-rCN/strings.xml:1895` 和 `ChannelSwitchItem.java:78`。
- `AdaptiveEarItem` 的说明是识别佩戴位置并调整左右声道及按键，对应自由左右佩戴。见同一 strings 文件第 1442 行，以及 `ui/component/detail/adaptiveear/AdaptiveEarItem.java:79`。
- 本次没有找到“单耳自动把 L/R 混合”的明确实现。不能仅凭 channel、左右声道等名称认定功能等价。

**建议实现结构**

1. 增加 `WearStateHookInstaller`：首选 HookAfter `DeviceInfo.setStatusInfo(java.util.List)`，读取 receiver 的设备地址、连接信息，以及参数中实际出现的侧；必要时使用官方 `0x100016` 事件交叉确认来源。只复制需要的字段，不持有可变宿主对象。
2. 增加 `EarWearStateTracker`：按设备和连接代际记录 `leftKnown/rightKnown`、`leftInEar/rightInEar`、在仓状态和来源。重连清空 known；同一连接允许部分更新继承。单侧没有条目时保留未知，不能补 false。
3. 增加 `AutoMonoController`：只有目标耳机确为当前音频输出、左右都已知且恰好一侧入耳时接管。对状态抖动做短暂防抖；矛盾状态、连接结束或来源不可信时恢复原设置。
4. 在现有官方面板注入按耳机保存的“单耳自动合并声道”开关，默认关闭；诊断页显示左右状态、known、来源、当前处理模式及最近失败原因。
5. 沿用已有跨进程身份校验和 owner 思路，避免 Melody 主进程与 `:fg` 重复控制。补齐关闭功能、断开、输出切换、进程异常及用户手动修改系统设置时的恢复行为。

核心判断示意：

```text
eligible = featureEnabled
        && targetIsCurrentAudioOutput
        && currentConnectionIsValid
        && leftKnown && rightKnown
        && stateIsConsistent

shouldMerge = eligible && (leftInEar XOR rightInEar)
```

耳状态是事件驱动的，稳定佩戴时可能没有新包。不能因为几秒没收到变更就丢弃有效状态；需要分别处理连接有效性、观察器存活和状态来源。

混音第一版建议验证系统 `Settings.System.master_mono`。它可让两个输出通道包含混合后的内容，传输仍可保持立体声编码配置。Android 的 [AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/audio/AudioService.java) 已有对应逻辑。

该设置作用于全局输出，不能保证同时存在的其他输出不受影响；AOSP [AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/master/services/audiopolicy/managerdefault/AudioPolicyManager.cpp) 在启用时会退出压缩音频卸载输出。写入权限、音频效果和切换连续性需要在目标 ROM 验证，不能将“设置写入成功”当成“声音已正确合并”。恢复时保留用户原先的单声道偏好。

如果需要只处理 LHDC 音频，可以研究模块已有 `native_lhdc_patch.cpp:687` 的编码前 PCM 入口。该路线还要验证采样格式、缓冲区及实际是否经过此编码器，且不会自动覆盖 AAC、LDAC 或 LE Audio。

**真机验证顺序**

先用只记录状态的诊断版本确认：双耳、仅左、仅右、均未佩戴；另一侧分别放仓内和桌上；锁屏及关闭面板；关闭佩戴检测；断开重连；LE Audio 切换。日志至少包含连接代际、实际报告的侧、known、入耳/在仓状态及来源，地址沿用项目脱敏规则。

再使用左右声道独立测试音，分别验证单耳能听到两路内容、双耳恢复立体声，以及输出切换和关闭功能后恢复原设置。最后测试快速摘戴、宿主或蓝牙进程重启、用户原本已开启系统单声道等情况。

本次静态分析已确定检测入口及主要误判风险。完整 JADX 输出有 58 个错误节点，关键 Repository 状态合并已通过 fallback 输出复核；没有把其余反编译异常当成真机行为结论。
