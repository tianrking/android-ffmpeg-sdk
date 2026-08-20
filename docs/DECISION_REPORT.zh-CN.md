# Android 上做 FFmpeg SDK：技术尽调与产品决策

更新日期：2026-08-20

## 结论

**FFmpeg 很适合在 Android 使用，但“不值得再做一个 FFmpeg 命令执行壳”；值得做的是一个
Android-first 的专业媒体任务 SDK。**

这两个判断必须同时成立：

1. 底层继续复用 FFmpeg 与 FFmpegKitNext，不维护 FFmpeg 私有分叉；
2. 产品价值放在 Android 资源、类型安全、任务生命周期、硬件能力、二进制供应链、合规和
   设备证据上，而不是把字符串交给 `ffmpeg_main()`。

本仓库已经按这条边界实现首个可构建基础版本。它不是“调研文档中的设想”：核心策略测试、
三个模块编译和示例 APK 构建都进入自动化验证；但它也还不能被诚实地称为生产 GA，真实设备、
16 KB 系统、长任务恢复和隔离进程等发布门槛仍列在 `RELEASE_GATES.md`。

## 为什么技术上合适

FFmpeg 的优势并不是“也能编码 H.264”，而是完整的容器、编解码、过滤、探测、字幕、流复制和
协议组合能力。对于只做 MP4 裁剪、缩放、特效和导出的应用，Android 官方 Media3 Transformer
已经基于 MediaCodec 和 OpenGL 提供硬件加速、API 23+ 支持及设备兼容处理；它应当是首选。
但 Media3 的输入输出仍受平台解码器/编码器和 muxer 范围约束，默认输出也是 MP4。需要 FFprobe、
非主流容器、复杂 filtergraph、字幕烧录、音频处理或无重编码 remux 时，FFmpeg 的覆盖面仍明显更强。

来源：

- [Media3 Transformer 总览](https://developer.android.com/media/media3/transformer)
- [Media3 支持格式](https://developer.android.com/media/media3/transformer/supported-formats)
- [MediaCodecList API](https://developer.android.com/reference/android/media/MediaCodecList)

性能上，FFmpeg 并不等于纯 CPU。Android 构建可以启用 MediaCodec，视频编码可显式选择
`h264_mediacodec`、`hevc_mediacodec` 等。但硬件编码器能力是**设备事实**，不能从 ABI、Android
版本或 FFmpeg 编译选项推断，所以 SDK 同时检查：

- FFmpeg 运行时是否真的编入相应 encoder；
- 当前设备 `MediaCodecList.findEncoderForFormat()` 是否接受具体分辨率、帧率、码率和 profile；
- 真实文件在真实设备上是否跑完并得到正确输出。

当前实现完成了前两层 API，第三层需要设备实验室持续积累。

## 为什么普通移植不值得做

原 FFmpegKit 已经证明 Android/移动端存在大量需求，但它退休后留下的空缺也已经有人填：
[FFmpegKitNext](https://github.com/arthenica/ffmpeg-kit-next) 是原作者明确标注的官方延续，支持
Android API 24+、五类 ABI、Kotlin/Java、SAF 与本地 AAR。2026-07-28 的 8.1.1 对应 FFmpeg
8.1.2。关键差别是它只提供源码构建，不再向 Maven Central 发布现成二进制。

所以，以下项目没有足够壁垒：

- 再复制一套 `execute("-i ...")` API；
- 每隔几个月上传一个不可复现的 full AAR；
- 用“支持几百种格式”做营销，却不声明具体 build configuration；
- 把 `content://` 临时复制成路径，当成 Android 集成完成；
- 看到 `h264_mediacodec` 就宣称所有手机都有硬件加速。

真正的机会来自 FFmpegKitNext 的源码交付和 Android 应用之间仍缺一层产品化保证：任务模型、
运行时治理、设备探测、失败语义、供应链证据和发行合规。

## 产品定义

一句话产品：

> 把 Android 的路径、`content://` 和 HTTPS 媒体输入变成可取消、可观测、可复现、许可证明确的
> FFmpeg 媒体任务，并输出足以解释“为什么成功或失败”的执行证据。

不可替代动作不是“运行一条命令”，而是：**应用提交一个稳定 JSON 任务，SDK 选择并记录精确
encoder、解析 Android 资源、执行、取消、探测、报告进度，并把运行时来源与许可证一起留下。**

首个目标客户不是简单短视频编辑器，而是：

- 相机、无人机、行车记录仪、工业设备的素材接入；
- 播客、录音、字幕、转封装与批量媒体工具；
- 需要离线处理、格式杂、文件来源不可控的企业 Android 应用；
- 已依赖旧 FFmpegKit，想降低迁移和供应链风险的团队。

## 当前实现的差异化

### 1. 类型化参数，不走 shell

`CommandArgument` 把 literal 和 resource 分开；资源直到 engine 层才解析。SDK 调用
`executeWithArgumentsAsync(String[])`，避免空格、引号、分号或 URI 被二次拆词。当前 job JSON 带
`schemaVersion = 1`，后续可以迁移而不猜旧字段含义。

### 2. Android SAF 是一等资源

`content://` 不是伪路径。FFmpegKit engine 使用其 SAF protocol 获取读写参数；示例用
`OpenDocument`/`CreateDocument`，不申请传统外部存储权限。生产版仍需增加非 seekable provider 的
自动 staging 和失败后残留文件清理。

### 3. 显式编码器与许可证门

H.264 的硬件候选是 `h264_mediacodec`，软件候选是 `libx264`。后者会把 FFmpeg 运行时带入 GPL，
因此 LGPL runtime 下 planner 直接移除它，不会悄悄改用 MPEG-4，也不会假装有软件 fallback。
HEVC/`libx265` 同理。用户看到的是具体 attempt，而不是一个模糊的 `AUTO`。

### 4. 引擎可替换且不传递原生二进制

核心模块不依赖 FFmpegKit；适配模块对 FFmpegKit 仅 `compileOnly`。消费者必须主动放入自己审核过的
AAR，并声明 `RuntimeLicense` 和允许的 FFmpeg major。v0.1 默认只接受 FFmpeg 8.x，防止 9.x 在
未回归时无声进入生产。

### 5. 设备能力与 FFmpeg 能力分开

`MediaCodecSurvey` 枚举当前设备 codec、硬件/软件属性、profile/level、实例数，并可用完整
`MediaFormat` 查询平台选择。FFmpeg engine 另外解析 `-encoders`/`-decoders`。两者不能相互替代。

## 版本策略

截至 2026-08-20，FFmpeg 官方最新稳定版是 9.0.1（2026-08-12）；8.1.2 发布于
2026-06-17。FFmpegKitNext 当前 Android 版本 8.1.1 正好使用 8.1.2，而且 FFmpeg 安全页面确认
8.1.2 包含 CVE-2026-8461、CVE-2026-30999 修复。因此：

- **validated**：FFmpeg 8.1.2 + FFmpegKitNext 8.1.1；
- **canary**：FFmpeg 9.0.1，等待 wrapper、构建和设备矩阵；
- 每月检查 FFmpeg 安全页；高危解析漏洞触发 runtime 重建，而不是等 SDK API 发版；
- tarball 必须验证 FFmpeg 官方 PGP 签名，并记录源码 commit、补丁、configure 参数、NDK、CMake、
  依赖哈希和产物哈希。

来源：

- [FFmpeg 官方下载与签名](https://www.ffmpeg.org/download.html)
- [FFmpeg 安全公告](https://www.ffmpeg.org/security.html)
- [FFmpegKitNext Android 构建说明](https://github.com/arthenica/ffmpeg-kit-next/tree/main/android)

## Android 发行现实

本项目当前选择 `minSdk 24`、`compileSdk 37`、样例 `targetSdk 36`、AGP 9.3.1、Gradle 9.5、
JDK 17。API 37.0 已作为当前编译平台使用；target 仍固定在已经纳入本轮行为边界的 API 36，target
37 需要单独回归。Google Play 从 2026-08-31 起要求普通新应用和更新至少 target Android 16/
API 36；AGP 9.3 官方兼容表要求 Gradle 9.5、Build Tools 36 和 JDK 17。

原生 SDK 还必须过 16 KB 页大小：当前 Android 官方规则是，target API 35+ 的 64 位应用必须支持
16 KB；从 2027-02-01 起，不支持的更新无法发布。AGP 8.5.1+ 能正确处理未压缩 `.so` 的 ZIP
对齐，NDK r28+ 默认生成 16 KB ELF alignment。当前稳定 NDK 是 r29；生产 runtime 应迁移到 r29，
并对每个 ABI 的每个 `.so` 同时检查 `LOAD align >= 2**14`、`GNU_RELRO` 和 APK `zipalign -P 16`。

来源：

- [Google Play target API 要求](https://developer.android.com/google/play/requirements/target-sdk)
- [Android 16 KB 页大小指南](https://developer.android.com/guide/practices/page-sizes)
- [AGP 9.3 兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [Android NDK 当前版本](https://github.com/android/ndk/wiki)

## 体积与运行时档位

本次仅用于 API/构建验证的社区 `ffmpeg-kit-full:8.1.7` AAR，下载体积约 29.52 MiB，包含
`arm64-v8a` 和 `x86_64`；它证明现成 artifact 能让样例构建，但它不是我们建议的生产供应链。
其 POM 还有重复 license/developer 元数据，并漏掉 FFmpegKit 实际使用的
`com.arthenica:smart-exception-java:0.2.1`：Debug 构建没有立刻暴露问题，R8 release 构建才以
missing class 失败。样例为评估显式补了依赖，但这进一步说明仓库信任和源码对应关系必须独立审计。

正式发行应只提供构建 recipe，不提供一个万能 full 包：

| Profile | 用途 | 许可证预期 | 默认 ABI |
| --- | --- | --- | --- |
| `minimal-lgpl` | probe、常见容器、AAC、MediaCodec | LGPL | arm64 + x86_64 |
| `full-lgpl` | 更多 BSD/LGPL 外部库、字幕与网络协议 | LGPL | 按需 |
| `gpl-optin` | x264/x265 等明确 GPL 需求 | GPL | 按需 |

每个 profile 都要发布独立坐标、独立 SBOM 和独立 source bundle，不能让 GPL 能力成为一个隐藏
Gradle 开关。

## 授权与专利判断

FFmpeg 默认 LGPL 2.1-or-later；启用 GPL 组件后整个 FFmpeg build 适用 GPL。FFmpeg 官方合规清单
要求避免 `--enable-gpl/--enable-nonfree`、动态链接、提供与二进制精确对应的源码和构建配置、保留
归属信息，并检查外部库。FFmpegKitNext 自身说明默认 LGPL 3.0、启用 GPL 库后 GPL 3.0。

这不是专利许可。H.264、HEVC、AAC 等在不同国家、用途和分发规模下可能涉及专利池或设备/平台
许可；开源许可证合规不等于专利清零。商业发行必须由法务按市场和用例判断。本仓库提供工程证据，
不提供法律结论。

来源：[FFmpeg License and Legal Considerations](https://ffmpeg.org/legal.html)

## 主要技术风险

| 风险 | 当前处理 | GA 前还要做 |
| --- | --- | --- |
| 恶意媒体触发 native 漏洞 | 固定安全版本、网络默认关闭 | 独立 `isolatedProcess` worker、崩溃恢复、fuzz corpus |
| MediaCodec 碎片化 | FFmpeg/Android 双能力探测、显式 attempt | 至少 12 台真机矩阵、热降频与长视频 |
| `content://` 不可 seek | SAF 原生接入 | 自动探测，必要时 cache staging + 原子复制 |
| 失败留下半成品 | 返回每次 attempt 结果 | 输出事务、清理策略、provider 合约测试 |
| AAR 过大 | engine 不传递 runtime、ABI 限定 | 三种 profile 的真实 size/功能基线 |
| GPL 被误带入 | planner runtime license gate | CI 扫描 configure/SBOM/符号与 POM |
| 长任务被系统杀死 | 任务可取消 | foreground service/WorkManager 集成层与恢复 token |
| FFmpeg API/ABI 更新 | major allow-list | 8.x/9.x 双线 CI 与 golden corpus |

## Go / No-Go

建议 **Go**，但只批准下面这条产品路线：

- 不 fork FFmpeg；上游安全更新优先；
- SDK 的公开 API 不暴露 FFmpegKit session 类型；
- 默认 runtime 为最小 LGPL source build；GPL 单独坐标、单独文档；
- 所有能力声明来自构建 manifest + 设备测试，不来自 README 想象；
- 先做 10 个高价值 recipe（probe、remux、H.264/H.265 export、音频提取/转码、缩略图、字幕、
  waveform、trim、concat），不追求覆盖全部 FFmpeg flags；
- 通过 `RELEASE_GATES.md` 后才发布 `1.0.0`。

如果目标只是“在 Maven 上放一个 full AAR，提供 execute(command)”，则建议 **No-Go**：既缺少壁垒，
又把安全、许可证、专利、体积和维护压力全部接过来。
