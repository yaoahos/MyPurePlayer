# LGPL 合规说明

本应用包含以 **GNU 宽通用公共许可证第 2.1 版（LGPL-2.1+）** 授权的组件。本文件说明这些组件是什么、它们如何被集成，以及你如何修改并重新链接它们。

适用对象：下载 APK 的用户、二次分发的第三方、以及想自行构建的同学。

---

## 一、包含哪些 LGPL 组件

| 组件 | 版本 | 许可证 | 在 APK 中的形态 |
|---|---|---|---|
| libass | 随 ass-media 0.6.0-beta01 提供 | LGPL-2.1+（ISC 部分除外） | `lib/arm64-v8a/libass.so`、`libasskt.so` |
| FFmpeg | 6.1.1 | LGPL-2.1+（未启用任何 GPL 组件） | 静态链接进 `lib/arm64-v8a/libffmpegJNI.so` |

FFmpeg 的用途单一：为系统缺少解码器的音频格式（DTS / DTS-HD / TrueHD / MLP）提供软件解码，仅在系统解码器不可用时兜底。configure 未启用 `--enable-gpl`，也未引入任何 GPL 组件，因此整个 APK 仍可按 MIT 分发。

libass 的用途：ASS / SSA 字幕的完整排版与渲染（样式、动画、卡拉OK、内嵌字体）。

**本项目未修改上述两个组件的源代码。**

---

## 二、LGPL 要求我们做什么

分发包含 LGPL 库的二进制程序时，需要保证接收者能够：

1. 获得该库的**完整源代码**（含本项目使用的版本与任何修改）；
2. 用修改后的库**重新链接**出可运行的等价程序。

我们通过以下方式满足：

- **源码公开**：本仓库即为完整源码，版本与发行 APK 的 tag 一一对应（见 [Releases](https://github.com/yaoahos/MyPurePlayer/releases)）。
- **原始库源码**：libass 见 <https://github.com/libass/libass>；FFmpeg 见 <https://ffmpeg.org/download.html>（6.1.1 源码包）。
- **可重新链接**：见下方第三节，提供源码级与二进制级两种替换方式。
- **构建参数公开**：FFmpeg 的编译参数记录在 `app/build.gradle.kts` 对应依赖的注释里，并摘录于本文附录。

---

## 三、如何替换库并重新构建

### 方式 A：从源码构建（推荐）

1. 克隆本仓库，检出与你的 APK 对应的 tag。
2. 用你自己的构建替换目标库：
   - **libass**：`ass-media` 依赖内已含 `libass.so`。把它替换为你编译的同名 `.so`，放到 `app/src/main/jniLibs/arm64-v8a/`，并在打包配置中排除旧的那份（或直接替换 `app/libs` 中对应的 AAR）。
   - **FFmpeg**：按附录参数编译 `libffmpegJNI.so`，替换 `app/libs/media3-ffmpeg-decoder-min.aar` 中 `jni/arm64-v8a/libffmpegJNI.so`，或用你的 AAR 覆盖整个文件。
3. 执行 `./gradlew :app:assembleRelease`，用你自己的签名安装。

> 只保留 `arm64-v8a` 一个 ABI（见 `app/build.gradle.kts` 的 `abiFilters`）。替身库必须同为 arm64、且与 16KB 页对齐（`-Wl,-z,max-page-size=16384`），否则在 Android 15+ 的部分设备上加载失败。

### 方式 B：直接替换 APK 内的 .so（无需完整构建环境）

1. 用 `apktool d` 或任意 zip 工具解包 APK（`.so` 未压缩，可直接从 zip 中取出替换）。
2. 将你编译的 `libass.so` / `libffmpegJNI.so` 放入 `lib/arm64-v8a/`，保持文件名不变。
3. 重新打包、`zipalign`、用 `apksigner` 重新签名后安装。

注意：替换后 APK 的签名与官方不同，无法直接覆盖安装官方版本，需要先卸载（会丢失应用内数据，建议先在设置里导出配置备份）。

---

## 四、许可证文本

- LGPL-2.1：<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- libass 的许可证与版权声明见其源码仓库的 `COPYING`。
- FFmpeg 的许可证说明见 <https://ffmpeg.org/legal.html>。

---

## 附录：FFmpeg 编译参数（本项目的实际配置）

依据 `app/build.gradle.kts` 的依赖注释整理：

- 版本：FFmpeg 6.1.1，JNI 胶水来自 Media3 1.9.0 的 `ffmpeg_jni.cc`
- 目标 ABI：`arm64-v8a`
- 启用的解码器（仅 7 个）：`aac`、`ac3`、`eac3`、`dca`、`truehd`、`mlp`、`flac`
- 关闭项：`--disable-everything --disable-network --disable-avformat` 等（不引入网络与封装层）
- 优化：`-Oz`，去除 `-g` 调试信息
- C++ 运行时：静态链接 libc++（产物不依赖 `libc++_shared.so`）
- 对齐：按 16KB 页对齐链接

对应 `app/libs/media3-ffmpeg-decoder-min.aar`：约 1.10 MB，JNI 导出符号 9 个，`DT_NEEDED` 与上游社区包一致。

---

## 联系方式

对合规有疑问、或需要本文未覆盖的材料，请在本仓库开 issue。
