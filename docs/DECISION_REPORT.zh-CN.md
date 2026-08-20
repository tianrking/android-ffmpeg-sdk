# Android 官方 FFmpeg 源码路线：技术决策与边界

更新日期：2026-08-20

## 结论

本项目只认 FFmpeg 官方上游，不再使用 FFmpegKit、FFmpegKitNext 或社区预编译 AAR：

1. 上游源码固定为 FFmpeg 官方 `n9.0.1`；
2. 从 ffmpeg.org 下载发布包、签名和发布公钥，先验 SHA-256 与 PGP，再编译；
3. 使用 Google Android NDK r29 自行交叉编译四个标准 ABI；
4. 自己维护 Android JNI/Kotlin 层，不暴露任何第三方 wrapper API；
5. 默认只发布 `core-lgpl`，不启用 GPL、nonfree 或外部 codec 库；
6. 每个产物记录源码、工具链、参数、补丁和二进制哈希。

这里的“官方”是源码来源和验证链官方，不是声称本 SDK 得到 FFmpeg 项目背书。本项目仍是独立
Android 集成项目。

## 官方上游到底是哪一个

FFmpeg 的主仓库是 `https://git.ffmpeg.org/ffmpeg.git`。GitHub 上的
[`FFmpeg/FFmpeg`](https://github.com/FFmpeg/FFmpeg) 是 FFmpeg 组织维护并由官方网站列出的镜像，
可用于浏览和固定 commit；发布源码和签名以
[`ffmpeg.org/download.html`](https://ffmpeg.org/download.html) 为准。

当前锁定证据：

| 项目 | 固定值 |
| --- | --- |
| FFmpeg tag | `n9.0.1` |
| tag object | `501bb49457b9dfb25d6a208832e0a6e6cd53108d` |
| commit | `bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa` |
| 发布包 SHA-256 | `cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635` |
| 发布公钥指纹 | `FCF986EA15E6E293A5644F10B4322F04D67658D8` |
| NDK | `r29 / 29.0.14206865` |
| NDK ZIP SHA-256 | `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf` |
| Android API baseline | 24 |

完整机器可读锁位于 `native-runtime/ffmpeg.lock.json`。

## 构建链

```text
ffmpeg.org signed tarball + signature + release key
                     |
                     +-- SHA-256 + pinned fingerprint + PGP verify
                     v
             pristine FFmpeg 9.0.1 source
                     |
                     +-- one audited runner patch
                     v
       NDK r29 cross compile, Android API 24
          |          |          |          |
      arm64-v8a  armeabi-v7a  x86_64      x86
          |          |          |          |
          +----------+----------+----------+
                     v
  libav*.so + libffmpeg_sdk_cli.so + libffmpeg_sdk_bridge.so
                     |
                     +-- SONAME / 16 KiB / RELRO / TEXTREL checks
                     v
          manifest.json + exact artifact SHA-256
```

Windows 入口是 `scripts/build-official-ffmpeg.ps1`，它调用 WSL2 中的
`native-runtime/scripts/build-android.sh`。Linux 可直接运行 Bash 脚本。

## 为什么仍然有一个小补丁

FFmpeg 官方提供稳定的 `libavcodec`、`libavformat`、`libavfilter` 等 C API，但不提供“把完整
`ffmpeg` argv 在进程内执行”的稳定公共库函数。现有 Kotlin planner 已覆盖 remux、转码、裁剪、
缩略图、waveform、字幕和 concat；如果完全重写这些媒体管线，就会复制大量 FFmpeg 命令前端逻辑。

因此本项目对每次刚解压的官方源码只应用一个可审计补丁：

- 把 `main()` 导出为 `ffmpeg_sdk_execute()`；
- 导出 `ffmpeg_sdk_cancel()`；
- 不改 codec、muxer、demuxer、filter 或协议实现。

构建脚本先校验补丁 SHA-256，再以 `--fuzz=0` 应用；任何 hunk 不能精确匹配锁定的官方发布包都
立即失败。

`libffmpeg_sdk_cli.so` 每次任务动态加载，结束后立即卸载。命令前端的私有全局状态由动态加载器重置，
不会串到下一任务；JNI 层同时强制串行执行。版本、许可证、组件枚举和 probe 不经过命令前端，直接
调用官方 public libav API。

这不是“无修改的官方二进制”，而是“签名官方源码 + 一份公开、固定哈希的小补丁 + 自有桥接层”。
任何 Android SDK 都必须有非官方的 Android glue；关键是把 glue 与上游源码边界说清并可复现。

## Android ABI 与 Kotlin/Java 的关系

ABI 是 CPU 原生二进制层：

- `arm64-v8a`：当前主流 64 位真机；
- `armeabi-v7a`：旧 32 位 ARM；
- `x86_64`：主流模拟器；
- `x86`：旧 32 位模拟器/设备。

Kotlin/Java API 不按 ABI 写四份。Gradle/AAB 根据设备选择 `jniLibs/<abi>` 中对应 `.so`，统一通过
`OfficialFfmpegEngine` 调 JNI。Kotlin 使用协程/Flow API；Java 使用 `FfmpegJavaSdk` 提供的
`CompletableFuture`、事件回调和可取消 `JavaMediaTask`。应用应该使用 App Bundle 或 ABI split，
避免把四套库塞进一个通用 APK。

## 运行时能力

`core-lgpl` 默认启用：

- FFmpeg 内建 demuxer、muxer、decoder、encoder、filter；
- JNI；
- Android MediaCodec decoder/encoder；
- Android 官方 `content://` protocol；
- zlib 与 pthread；
- FFmpeg 命令前端、日志、进度和取消。

默认关闭 FFmpeg 网络协议。HTTPS 输入由 Android 平台 TLS 按显式策略下载到有大小上限的 app cache，
再交给 native parser，避免为了 TLS 再混入一套第三方库。普通 `content://` 输入先探测独立 FD：
可 seek 时走 FFmpeg 9 官方 protocol，不可 seek 时复制到有总量上限的 cache；字幕等要求真实文件名
的资源始终 staging。输出先写临时文件，成功后再提交。

默认没有 `libx264`、`libx265`、`libass`、`libwebp` 等外部库。因此：

- H.264/HEVC/AV1 优先使用设备 MediaCodec encoder；
- MPEG-4、AAC、FLAC、Opus、Vorbis、PNG、JPEG 等可使用 FFmpeg 内建实现；
- 需要 x264/x265 或 libass 时必须新增独立 profile、独立源码锁、独立许可证/SBOM，不能偷偷塞进
  `core-lgpl`。

能力不是 README 猜出来的。JNI 在运行时枚举实际 encoder、decoder、filter、muxer 和 demuxer，
planner 缺能力就提前拒绝。

## 许可证边界

FFmpeg 默认是 LGPL 2.1-or-later。`core-lgpl` 明确禁止：

- `--enable-gpl`；
- `--enable-nonfree`；
- 隐式自动探测宿主机外部库。

FFmpeg 库保持动态链接。发布时仍需提供精确对应源码、补丁、构建脚本、配置、LGPL 文本、归属与
可重链接条件。开源许可证不等于 H.264/HEVC/AAC 等专利许可，商业发布仍需按市场和用途由法务判断。

来源：[FFmpeg License and Legal Considerations](https://ffmpeg.org/legal.html)。

## 当前证据与尚未证明的事项

源码签名、NDK 锁、交叉编译参数、Kotlin 编译和静态 ELF 检查可以在本机证明。当前另有一台
Android 13/API 33、arm64、Qualcomm PEGM10 真机证据：官方 FFmpeg 9.0.1 生成 MPEG-4/AAC，
Kotlin SDK 经 JNI 和 `h264_mediacodec` 转为 H.264/AAC MP4，再由独立 host FFmpeg 完整解码。
首次运行暴露默认 `yuv420p` 被设备拒绝，planner 固定 MediaCodec 输入为 NV12 后复测通过。
这一台设备仍不能替代完整矩阵：

- 其他设备 MediaCodec 是否接受各类分辨率、profile、码率和帧率；
- 各厂商 DocumentProvider 的 seek、撤权、满盘和提交语义；
- 16 KiB Android 系统上的加载与执行；
- 取消、进程死亡、前后台、30 分钟与 4K 热测试；
- 恶意媒体、fuzz corpus 和独立安全审查。

这些继续列在 `docs/RELEASE_GATES.md`。在真机矩阵完成前，本项目仍是 engineering preview，不能把
“源码能编译、APK 能打包”宣传成生产 GA。
