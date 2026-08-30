# Pixel4xl 2D Face Unlock

[![Android CI](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml/badge.svg)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Vickylines/Pixel4xl-2DFaceUnlock)](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

这是一个面向已 Root、已安装 LSPosed 的 Google Pixel 4 XL 便利解锁模块。0.8.0 在 Android 10–16 的 AOSP SystemUI 运行时适配基础上，同时校验局部纹理与脸部几何比例，并保留眼位对齐、自动校准和 4/6 多帧确认。

> 本项目不是 Google、LineageOS、Magisk 或 LSPosed 的官方项目。普通 RGB 2D 人脸可能被照片或视频绕过，只适合便利解锁。

## 下载

- [下载最新版 APK](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/latest)
- [完整中文安装说明](dist/Pixel_2D人脸解锁_安装使用说明.txt)
- v0.8.0 本地 APK SHA-256：`45130C0C6DA5C889ECC9BF6FF166C6004911A3D13DA6952950DA192C012AD45D`

## 适配边界

- 目标设备：带前置摄像头的 Google Pixel4xl 设备（arm64-v8a）。
- 目标系统：Android 10–16（API 29–36）。
- 优先 ROM：Pixel 原生系统以及 AOSP/LineageOS 类 SystemUI。
- LSPosed 作用域：只勾选 `com.android.systemui`（系统界面）。

“Android 10–16 适配”不等于所有组合都已真机验证：0.8.0 已在 Pixel 4 XL（coral）、LineageOS 23.2、Android 16 / API 36 完成覆盖安装、LSPosed/SystemUI 注入、相机宿主输入层、睁眼解锁、双眼/单眼闭合拒绝和快速重锁回归。Android 10–15 的路径依据对应 AOSP SystemUI 数据结构进行了兼容编码和构建检查，仍需在各版本真机上逐一验证。非 Pixel、深度改造的厂商 SystemUI、访客／辅助用户、Android 9 以下及 Android 17 以上会安全停用钩子。

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

## 0.7.0 触控与自适应识别

- Android 16 下，相机宿主通过受会话令牌保护的 Activity token 通知 SystemUI，由 SystemUI 使用其系统权限关闭该 Activity 的全屏输入接收层；同时宿主窗口保持 `1×1`、`alpha=0`、不可聚焦且不可触摸。
- 描述子采样按 ML Kit 双眼位置做尺度和旋转对齐，减少裁剪抖动及轻微持机角度变化。
- 双眼是否睁开以 ML Kit 双眼概率为主，并要求眼睑轮廓满足最低合理范围；轮廓不能再覆盖明确的闭眼结果。它不要求眨眼，但闭上任意一只眼时不会进入身份匹配。
- 录入改为自然正视屏幕采集 10 帧并自动推导保守阈值，不再要求左右转头。
- 识别从“连续 3 帧、任一坏帧清零”改为 6 帧滑动窗口至少 4 帧通过；允许最多 2 个瞬时坏帧，但平均身份距离和分数离散度仍需满足安全限制。
- 画质、姿态和取景从单一硬门槛改为“宽硬边界 + 边缘帧收紧身份阈值”，不会通过放宽身份阈值补偿差画质。
- 设置页不再暴露容易误调的原始距离系数，改为“严格 / 均衡 / 便捷”三档；所有档位都受 `0.35` 的全局硬上限约束。
- 模板格式升级为 v2。旧版特征不会被删除，但 0.7.0 必须重新录入一次后才能启用。

## 0.8.0 双信号安全匹配

- 模板升级为 v3：一次录入同时建立 49 个局部纹理区的个性化稳定度、核心面部区域约束，以及由眼、鼻、嘴位置形成的 7 维归一化几何模型。
- 每一帧必须同时通过加权纹理距离、至少 40/49 个局部区域、至少 21/25 个核心区域、几何平均偏差和几何单项偏差；任何一个门槛失败都不能参与 4/6 多帧投票。
- 新门槛复用同一次 ML Kit 结果和同一份 LBP 描述子，不增加第二次神经网络推理。Pixel 4 XL 热启动三轮相机到成功为 `358 / 348 / 354 ms`，收紧后复测为 `383 ms`。
- 真机持续闭合双眼 8 秒、闭合单眼 6 秒均无成功回调；睁眼正常帧达到 44–49/49 个局部区域和 25/25 个核心区域。
- 快速重锁回归中，第一轮成功后第二轮闭眼没有复用旧认证；Android 16 输入接收层仍为 `NOT_TOUCHABLE`。
- 这些结果不等同于经过多人样本测得的误识率。普通 RGB 2D 图像仍可能被本人照片或视频重放绕过。

## 当前解锁逻辑

日常解锁不会显示摄像头画面。动画注入原生 SystemUI 锁屏，可选择“面容光环”或“灵动岛”。

- 停留在普通时间锁屏：人脸通过后原生锁图标变为解锁状态，仍需向上滑动进入桌面。
- 已进入 PIN/密码界面：人脸通过后直接完成当前解锁。
- 重启后第一次解锁、锁定模式、系统要求强认证或弱生物识别超时：必须输入 PIN/密码，模块不会绕过。
- 快速锁屏再亮屏：创建新会话，不复用上一次的人脸结果。

## 使用方法

1. 覆盖安装 APK，不要卸载旧版；相同包名升级会保留设置和旧模板文件。
2. 在 LSPosed 中启用本模块，作用域只选择“系统界面”。
3. 重启手机或重启 SystemUI；重启后的第一次解锁先输入 PIN。
4. 打开“Pixel 2D 人脸解锁”，确认“LSPosed 钩子已加载”，并查看下面的运行时适配报告。
5. 从 0.7.x 或更早版本升级到 0.8.0 后，按提示重新录入一次以建立 v3 双信号模板；自然看向屏幕即可，不要求眨眼或转头。

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
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

Windows 也可以运行 `build.ps1`。调试产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

项目发布 APK 沿用现有 Android Debug 证书，以便覆盖升级先前测试版；证书 SHA-256 为 `769bae7364e73ad5c73835c7fbce30994ea4e9ff9efd32fde51404886d5ad7da`。自行编译的 APK 如果签名不同，不能直接覆盖安装。

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
