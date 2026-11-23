# 2D 人脸解锁 (Pixel4xl-2DFaceUnlock)

[![Android CI](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml/badge.svg)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Vickylines/Pixel4xl-2DFaceUnlock)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

基于 LSPosed 的轻量纯离线 2D 人脸解锁模块，专为 Android 10–17 类原生系统（AOSP / Pixel / LineageOS 等）打造。

为没有官方 2D 面部识别或刷了类原生 ROM 的设备提供原生般流畅、无感的锁屏人脸解锁体验。

---

## 核心特性

- **极速解锁**：相机预热与模型并行识别优化，亮屏即识，流畅上滑直接进桌面。
- **多种锁屏动效**：无缝注入 SystemUI 原生锁屏，内置面容光环、灵动岛与聚焦三种动效，并在 App 内提供实时预览。
- **便捷重试手势**：识别未通过时显示微胶囊提示，向上轻扫即可重新发起识别，或直接上滑切到密码键盘。
- **纯本地离线**：基于 MobileFaceNet 神经网络本地运行，无网络权限，不保存原始照片，纯本地提取特征向量。
- **在线检查更新**：应用内支持一键检查 GitHub 最新正式发布与更新日志，下载由系统浏览器接管。
- **全架构支持**：单个 APK 覆盖 `arm64-v8a`、`armeabi-v7a`、`x86` 及 `x86_64`，适配 16KB 页对齐。
- **规范安全回退**：仅用于日常锁屏便利解锁；重启后首次解锁、锁定模式或系统强认证超时，均强制回退系统 PIN/密码。

---

## 快速上手

> **前置条件**：设备已 Root，并安装兼容当前 Android 版本的 LSPosed 框架。

1. **安装模块**：前往 [Releases 页面](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases) 下载最新的 `Pixel2DFaceUnlock-universal-*.apk` 安装；
2. **启用作用域**：在 LSPosed 管理器中激活本模块，作用域**仅勾选「系统界面 (com.android.systemui)」**；
3. **重启生效**：重启手机或重启 SystemUI（开机首次解锁需手动输入一次锁屏密码）；
4. **录入面容**：打开「2D 人脸解锁」应用，自然看向屏幕即可完成面容录入。

---

## 解锁交互逻辑

- **时间锁屏界面**：亮屏自动识别人脸，通过后原生锁图标切换为解锁态，上滑直接进入桌面。
- **密码/PIN 界面**：若已上滑至密码输入界面，人脸识别成功后会自动直接进入系统。
- **重试机制**：识别未通过时锁屏底部弹出提示，从提示处向上轻扫即可重试一次人脸识别，也可以继续使用密码解锁。
- **安全保障机制**：设备重启首次开机、连续识别人脸失败、系统弱生物识别超时，均要求输入 PIN/密码。

---

## 兼容性

- **支持系统**：Android 10 – Android 17 (API 29–37)。
- **推荐 ROM**：Google Pixel 官方系统、LineageOS、Evolution X 等 AOSP / 类原生 ROM。
- **限制说明**：暂未对 MIUI / HyperOS、One UI、ColorOS 等深度定制的厂商 ROM 进行针对性适配。
- 更多详细适配及技术细节请参阅 [兼容性文档](COMPATIBILITY.md)。

---

## 安全须知

- **仅供日常便携解锁**：基于普通前摄 2D 识别，防伪能力有限（照片/视频可能绕过），切勿用于高敏感场景。
- **不接管支付认证**：不向应用提供生物认证凭据，微信、支付宝等支付仍需使用指纹或密码。
- **纯本地离线**：不存照片、不上传隐私，仅在本地私有目录提取并保存特征向量。

---

## 编译

需要 JDK 17 及 Android SDK 36：

```bash
./gradlew :app:assembleRelease
```

Windows 环境下亦可直接运行 `build.ps1`。

---

## License

本项目自有源码遵循 [MIT License](LICENSE)。
