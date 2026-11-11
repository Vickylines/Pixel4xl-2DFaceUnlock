# 一瞥

[![Android CI](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml/badge.svg)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Vickylines/Pixel4xl-2DFaceUnlock)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **[0.13.8 正式版](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/tag/v0.13.8)**：减少重复推理和亮屏等待。Pixel 4 XL 同机前后各测 8 轮，亮屏到认证完成的中位数缩短 17.7%，详见 [更新说明](dist/RELEASE-NOTES-0.13.8.md)。

一瞥是 Pixel4xl 2D Face Unlock 的新名字。0.13.8 通用构建支持 Android 10–17，面向已 Root、已安装 LSPosed 且保留兼容 SystemUI 接口的设备；同一 APK 包含 ARM32、ARM64、x86 和 x86_64。锁屏接口按运行时能力选择，不再限制 Google Pixel 品牌。

识别使用离线 MobileFaceNet 与 v4 模板，已有 v3 模板继续使用纹理与几何双信号后端。相机、识别、存储与 SystemUI 会话分开管理；识别与一次性授权、系统弱认证策略及锁屏退出分别复核。适配范围见 [兼容性矩阵](COMPATIBILITY.md)。

设计见 [架构说明](ARCHITECTURE.md)，模型来源与授权见 [模型信息](app/src/main/assets/models/provenance.json)。

> 本项目不是 Google、LineageOS、Magisk 或 LSPosed 的官方项目。普通 RGB 2D 人脸可能被照片或视频绕过，只适合便利解锁。

## 下载

- [下载 0.13.8 正式模块](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.8/Pixel2DFaceUnlock-universal-0.13.8.apk) · [安装说明](dist/INSTALL-ZH-0.13.8.txt) · [SHA-256](dist/SHA256SUMS-0.13.8.txt)

- [下载 0.13.7 正式模块](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.7/Pixel2DFaceUnlock-universal-0.13.7.apk) · [安装说明](dist/INSTALL-ZH-0.13.7.txt) · [SHA-256](dist/SHA256SUMS-0.13.7.txt)

- [下载 0.13.4 正式模块](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.4/Pixel2DFaceUnlock-universal-0.13.4.apk) · [安装说明](dist/INSTALL-ZH-0.13.4.txt) · [SHA-256](dist/SHA256SUMS-0.13.4.txt)

日常使用安装正式模块即可。[0.13.4 独立诊断包](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.4/Pixel2DFaceUnlock-0.13.4-validation.apk)用于开发测试和故障排查，使用独立数据目录，不接入 LSPosed 锁屏解锁。

- [下载 0.13.3 正式模块](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.3/Pixel2DFaceUnlock-universal-0.13.3.apk) · [独立诊断包](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.3/Pixel2DFaceUnlock-0.13.3-validation.apk) · [安装说明](dist/INSTALL-ZH-0.13.3.txt) · [SHA-256](dist/SHA256SUMS-0.13.3.txt)

- [下载 0.13.2 正式模块](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.2/Pixel2DFaceUnlock-universal-0.13.2.apk) · [独立诊断包](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.2/Pixel2DFaceUnlock-0.13.2-validation.apk) · [安装说明](dist/INSTALL-ZH-0.13.2.txt) · [SHA-256](dist/SHA256SUMS-0.13.2.txt)

## 适配边界

- 目标设备：带前置摄像头、已 Root 且安装 LSPosed 的设备，不限制品牌。
- CPU：`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`，单 APK 覆盖；64 位原生库及 APK 对齐已检查 16 KB 页兼容。
- 目标系统：Android 10–17（API 29–37）。
- 优先 ROM：Pixel 原生系统以及 AOSP/LineageOS 类 SystemUI。
- LSPosed 作用域：只勾选 `com.android.systemui`（系统界面）。

保留版本在 Pixel 4 XL / LineageOS 24.0 / Android 17 上的验证记录见各版发布说明。其他 Android 版本与厂商 ROM 的状态见兼容性矩阵；关键认证接口不兼容时保持锁定。访客／辅助用户、Android 9 及更早版本、Android 18 及后续版本不在当前适配范围。

详细状态见 [COMPATIBILITY.md](COMPATIBILITY.md)。

## 0.13.8 解锁提速

模型对同一张脸的主体计算从两次减为一次，保留原始末端运算；亮屏后按系统实际就绪状态启动，省去固定等待。Pixel 4 XL 同机前后各测 8 轮，亮屏到认证完成的中位数由 1.176 秒降至 0.968 秒，进入桌面由 1.818 秒降至 1.595 秒，两组均一次上滑成功 8/8。模型推理中位数由 39.9 毫秒降至 19.3 毫秒，136 组输出逐位一致。身份阈值、睁眼和连续确认要求保持原值，详见 [性能实测记录](docs/performance-0.13.8.md)。

## 0.13.3 启动提速

相机开启、检测器预热和模型初始化重叠进行，后续会话复用已校验的只读模型权重。Pixel 4 XL 上重复初始化中位数从 28 ms 降至 8 ms，44 组输入的识别向量与旧路径逐项一致。241 项回归检查、329 项真机断言及关屏复测通过。用户确认三次正常识别均能进入桌面且体感更快，识别耗时中位数 1.13 秒。身份阈值、睁眼、多人拒绝、连续帧和系统认证要求保持不变，详见 [性能验证记录](dist/PERFORMANCE-0.13.3-20261109.md)。

## 0.13.2 全量检查

逐文件检查 54 个生产 Java 源文件及清单、资源、构建和维护脚本。修复损坏数据页反复录入却无法保存的问题，恢复入口改为先确认删除并验证系统密码，再重新录入；眼睛轮廓改为沿双眼方向测量，避免歪头被计入开合高度。

238 项回归检查、正式包和诊断包构建通过，Release Lint 无错误。2026-11-09 已在 Pixel 4 XL / Android 17 完成安装、关屏及相机释放复测，用户确认正脸和轻微歪头均可识别并上滑进入桌面。详见 [全量检查记录](FULL-AUDIT-0.13.2.md) 与 [真机验证记录](dist/DEVICE-VERIFICATION-0.13.2-20261109.md)。

## 当前解锁逻辑

日常解锁不会显示摄像头画面。动画注入原生 SystemUI 锁屏，可从六种样式中选择，并在设置中预览。

- 停留在普通时间锁屏：人脸通过后原生锁图标变为解锁状态，仍需向上滑动进入桌面。
- 已进入 PIN/密码界面：人脸通过后直接完成当前解锁。
- 重启后第一次解锁、锁定模式、系统要求强认证或弱生物识别超时：必须输入 PIN/密码，模块不会绕过。
- 快速锁屏再亮屏：创建新会话，不复用上一次的人脸结果。

## 使用方法

1. 覆盖安装 APK，不要卸载旧版；相同包名升级会保留设置和旧模板文件。
2. 在 LSPosed 中启用本模块，作用域只选择“系统界面”。
3. 重启手机或重启 SystemUI；重启后的第一次解锁先输入 PIN。
4. 打开“一瞥”，检查首页连接状态；若显示“重启以完成更新”，重启后再查看右上角“连接与关于”。
5. 已有有效 v3/v4 模板无需重新录入；没有有效模板时，按提示重新录入一次以建立 v4 模板。自然看向屏幕即可，不要求眨眼或转头。

当前版本保留已有 v3/v4 模板；新录入建立 MobileFaceNet v4 模板。验证版拥有独立数据，不能替代正式模块，也不会把录入自动同步到正式模块。升级验证包后它不再作为 LSPosed 模块显示；请确认启用的是正式包 `com.yusd.pixel2dface`，作用域只选系统界面。

## 安全说明

普通 RGB 前摄 2D 人脸不具备 Pixel 4 XL 原装红外/深度硬件或 Class 3 生物识别的安全强度。当前无动作模式不要求眨眼或转头，因此高质量照片、视频或屏幕重放仍可能绕过，误识别概率不可能保证为零。

它只适合便利解锁，不向支付或应用提供生物认证。请勿依赖它保护银行、密码库、工作资料或其他高价值数据。模块没有网络权限；人脸特征保存在应用私有目录，不保存原始照片，也不会上传或参与系统支付认证。

## 卸载与恢复

1. 在 LSPosed 中停用本模块并重启手机，原系统锁屏行为即恢复。
2. 如需删除人脸模板，再卸载应用或在应用中选择“删除人脸数据”。

模块不修改 ROM、boot、system 或 vendor 分区。

## 构建

需要 JDK 17 和 Android SDK 36：

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease --no-daemon
```

Windows 也可以运行 `build.ps1`。默认执行单元测试、Release 构建和 Lint，产物位于 `app/build/outputs/apk/release/app-release.apk`。真机存储回归可额外构建 `:app:assembleDebugAndroidTest`，其合成数据仅写入隔离的缓存目录。

本地 Release APK 关闭调试标记，但沿用现有 Android Debug 签名证书，以便覆盖升级先前测试版；证书 SHA-256 为 `769bae7364e73ad5c73835c7fbce30994ea4e9ff9efd32fde51404886d5ad7da`。自行编译的 APK 如果签名不同，不能直接覆盖安装。

GitHub Actions 的 APK 使用 CI 环境生成的证书，供构建检查使用。覆盖升级请下载 GitHub Release 中与旧版签名一致的安装包；每个版本的实际验证范围见对应发布说明。

## 开源协议与第三方组件

本项目自有源码采用 [MIT License](LICENSE)。AndroidX、CameraX、Google ML Kit 和 Xposed API 等第三方组件仍分别遵循其自身许可证；MIT 许可证不会改变这些依赖的授权条款。

欢迎阅读 [贡献指南](CONTRIBUTING.md)、[安全说明](SECURITY.md)、[兼容性矩阵](COMPATIBILITY.md) 和 [真机验证记录](dist/DEVICE-VERIFICATION-0.13.2-20261109.md)。
