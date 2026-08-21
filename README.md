# Pixel 2D Face Unlock

[![Android CI](https://github.com/Vickylines/Pixel2DFaceUnlock/actions/workflows/android.yml/badge.svg)](https://github.com/Vickylines/Pixel2DFaceUnlock/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Vickylines/Pixel2DFaceUnlock)](https://github.com/Vickylines/Pixel2DFaceUnlock/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

这是一个面向已 Root、已安装 LSPosed 的 Google Pixel 设备的便利解锁模块。0.6.0 在原 Pixel 4 XL / Android 16 版本上增加了 Android 10–16 的 AOSP SystemUI 运行时适配。

> 本项目不是 Google、LineageOS、Magisk 或 LSPosed 的官方项目。普通 RGB 2D 人脸可能被照片或视频绕过，只适合便利解锁。

## 下载

- [下载最新版 APK](https://github.com/Vickylines/Pixel2DFaceUnlock/releases/latest)
- [完整中文安装说明](dist/Pixel_2D人脸解锁_安装使用说明.txt)
- v0.6.0 APK SHA-256：`8AED7C99ED4014BF02E5E90B6EAB68CED931E33122C99867D3B982EB1F19E59D`

## 适配边界

- 目标设备：带前置摄像头的 Google Pixel 设备（arm64-v8a）。
- 目标系统：Android 10–16（API 29–36）。
- 优先 ROM：Pixel 原生系统以及 AOSP/LineageOS 类 SystemUI。
- LSPosed 作用域：只勾选 `com.android.systemui`（系统界面）。

“Android 10–16 适配”不等于所有组合都已真机验证：0.6.0 已在 Pixel 4 XL（coral）、LineageOS 23.2、Android 16 / API 36 完成覆盖安装、模板保留、LSPosed/SystemUI 注入、普通锁屏上滑解锁、PIN 页直解、快速重锁和动画离场测试。Android 10–15 的路径依据对应 AOSP SystemUI 数据结构进行了兼容编码和构建检查，仍需在各版本真机上逐一验证。非 Pixel、深度改造的厂商 SystemUI、访客／辅助用户、Android 9 以下及 Android 17 以上会安全停用钩子。

详细状态见 [COMPATIBILITY.md](COMPATIBILITY.md)。

## 0.6.0 通用适配

- 最低系统由 Android 12 降到 Android 10。
- 唤醒同时监听 AOSP `KeyguardUpdateMonitor` 回调和系统亮屏广播；重复事件由会话窗口去重。
- Android 10/部分旧版使用布尔认证缓存，较新版使用带“是否强生物识别”标记的对象缓存；模块运行时探测后选择对应适配器。
- PIN/密码界面的人脸成功回调同时兼容单参数和双参数签名。
- 普通锁屏写入弱认证缓存后立即反查系统 `getUserCanSkipBouncer`；反查失败会撤销结果并保持锁定。PIN/密码页在策略与用户检查通过后调用 SystemUI 官方异步人脸回调。
- 锁屏动画根节点兼容新版 `NotificationShadeWindowView` 和旧版 `StatusBarWindowView`；找不到时只停用动画层。
- 动画退出同时监听 `KeyguardUpdateMonitor`、`KeyguardViewMediator`、`KeyguardStateControllerImpl` 和相机宿主结束通知，减少旧版系统进入桌面后的残留帧。
- 相机宿主的 Keyguard 遮挡保护改为按能力探测，远程动画回调不再使用固定参数位置。
- 录入／测试 Activity 不再对其他应用导出；SystemUI 相机宿主改用一次性、15 秒有效且启动即销毁的私有会话授权。
- 设置页显示当前设备、Android API 和实际加载的唤醒/认证/动画适配状态。

## 当前解锁逻辑

日常解锁不会显示摄像头画面。动画注入原生 SystemUI 锁屏，可选择“面容光环”或“灵动岛”。

- 停留在普通时间锁屏：人脸通过后原生锁图标变为解锁状态，仍需向上滑动进入桌面。
- 已进入 PIN/密码界面：人脸通过后直接完成当前解锁。
- 重启后第一次解锁、锁定模式、系统要求强认证或弱生物识别超时：必须输入 PIN/密码，模块不会绕过。
- 快速锁屏再亮屏：创建新会话，不复用上一次的人脸结果。

## 使用方法

1. 覆盖安装 APK，不要卸载旧版；相同包名升级会保留已录入模板和动画选择。
2. 在 LSPosed 中启用本模块，作用域只选择“系统界面”。
3. 重启手机或重启 SystemUI；重启后的第一次解锁先输入 PIN。
4. 打开“Pixel 2D 人脸解锁”，确认“LSPosed 钩子已加载”，并查看下面的运行时适配报告。
5. 尚未录入时录入人脸；已录入用户无需因 0.6.0 升级重新录入。

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
./gradlew :app:assembleDebug :app:lintDebug --no-daemon
```

Windows 也可以运行 `build.ps1`。调试产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

GitHub Release 中的 v0.6.0 APK 使用项目现有的 Android Debug 证书签名，以便覆盖升级本项目先前发布的测试版；证书 SHA-256 为 `769bae7364e73ad5c73835c7fbce30994ea4e9ff9efd32fde51404886d5ad7da`。自行编译的 APK 如果签名不同，不能直接覆盖安装。

## 开源协议与第三方组件

本项目自有源码采用 [MIT License](LICENSE)。AndroidX、CameraX、Google ML Kit 和 Xposed API 等第三方组件仍分别遵循其自身许可证；MIT 许可证不会改变这些依赖的授权条款。

欢迎阅读 [贡献指南](CONTRIBUTING.md)、[安全说明](SECURITY.md)、[兼容性矩阵](COMPATIBILITY.md) 和 [真机测试报告](TEST-REPORT.md)。

## 支持我

如果您觉得这个项目对您有帮助，您可以扫描以下二维码进行捐赠：

<p align="left">
  <img src="assets/donate-alipay.png" alt="支付宝收款二维码" width="260">
  &nbsp;&nbsp;
  <img src="assets/donate-wechat.png" alt="微信支付收款二维码" width="260">
</p>
