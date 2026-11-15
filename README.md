# 2D 人脸解锁 (Pixel4xl-2DFaceUnlock)

[![Android CI](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml/badge.svg)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Vickylines/Pixel4xl-2DFaceUnlock)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

基于 LSPosed 的轻量级纯离线 2D 人脸解锁模块，专为 Android 10–17 类原生系统（AOSP / Pixel / LineageOS 等）设计。

为没有官方 2D 人脸识别或刷了类原生系统的设备提供原生般流畅、无感的锁屏人脸解锁体验。

---

## 核心特性

- **极速响应**：相机秒开、模型预热与并行识别优化，亮屏即识，流畅上滑进入桌面。
- **原生级锁屏融合**：将动效与状态无缝注入原生 SystemUI 锁屏，支持多种锁屏微动效（聚焦、面容轮廓等）。
- **纯本地离线**：基于本地 MobileFaceNet 神经网络推理，应用不申请任何网络权限，不保存原始照片，纯本地特征比对。
- **全架构支持**：单个 APK 覆盖 `arm64-v8a`、`armeabi-v7a`、`x86` 及 `x86_64`，适配 16KB 页对齐。
- **规范安全回退**：仅用于日常锁屏便利解锁；重启后首次解锁、锁定模式或系统强认证超时，均严格遵循系统策略回退 PIN/密码。

---

## 快速上手

> **前置条件**：设备已 Root，并安装支持当前系统的 LSPosed 框架。

1. **安装模块**：前往 [Releases 页面](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases) 下载最新的 `Pixel2DFaceUnlock-universal-*.apk` 并安装；
2. **启用作用域**：在 LSPosed 管理器中启用本模块，作用域**仅需勾选「系统界面 (com.android.systemui)」**；
3. **重启生效**：重启手机或重启 SystemUI（重启后的第一次解锁需手动输入一次锁屏密码）；
4. **录入面容**：打开「2D 人脸解锁」应用，按照界面提示自然看向屏幕完成面容录入即可。

---

## 解锁交互逻辑

- **普通锁屏界面**：亮屏后自动识别人脸，通过后原生锁图标展开为解锁状态，上滑即可直接进入桌面。
- **密码/PIN 界面**：若已滑动到密码输入界面，人脸识别成功后会自动直接进入系统。
- **安全保障机制**：设备重启首次开机、连续识别人脸失败、系统弱生物识别超时，均要求输入 PIN/密码。

---

## 兼容性

- **支持系统**：Android 10 – Android 17 (API 29–37)。
- **推荐 ROM**：Google Pixel 原生系统、LineageOS、Evolution X 等 AOSP / 类原生 ROM。
- **限制说明**：暂未对 MIUI / HyperOS、One UI、ColorOS 等深度定制的厂商 ROM 进行针对性适配。
- 更多详细适配及测试记录请参阅 [兼容性文档](COMPATIBILITY.md)。

---

## 安全须知

1. 本模块基于普通前置 RGB 摄像头实现 2D 特征比对，**不具备** Pixel 4 XL 官方红外雷达/深度硬件或 Class 3 生物级别的防伪能力。
2. 仅作为日常日常便捷解锁使用，请勿用于高价值或高安全级别场景（支付认证请继续使用指纹或密码）。
3. 模块完全离线运行，无任何网络请求，面容特征严格加密保存在应用私有沙盒中。

---

## 编译

需要 JDK 17 及 Android SDK 36：

```bash
# 执行测试与正式包编译
./gradlew :app:assembleRelease
```

Windows 环境下亦可直接运行 `build.ps1`。

---

## License

本项目自有源码遵循 [MIT License](LICENSE)。
