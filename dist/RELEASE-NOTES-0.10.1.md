# v0.10.1 · 修复锁屏冷启动，正式发行版

修复“应用内测试成功，锁屏却打不开”的冷启动竞态：在异步模型初始化前准备透明锁屏窗口，避免页面尚未开启相机就被 Keyguard 停止。相机及宿主就绪通知仍需一次性授权、配置和系统策略检查。

- 正式模块与验证包分离 Xposed 入口资源，验证包删除模块发现元数据，避免在 LSPosed 中误启用只支持主动测试的包。现场同时恢复了正式包的 SystemUI 作用域。
- 同签名覆盖升级保留已有 v3/v4 人脸与设置，无需重录。本次模型、身份阈值、睁眼、多帧门槛和系统弱认证限制均未调整。
- 离线 MobileFaceNet、五点 RGB 对齐与严格 v4 模板由 0.10.0 候选版延续；原 v3 模板继续使用旧后端。独立验证包不自动同步人脸到正式模块。

验证：154 项 JVM 测试通过，正式包和验证包各通过 329 项设备断言，Release Lint 为 0 错误、7 警告。Pixel 4 XL / Android 16 三轮强停后冷启动均取得首帧并在熄屏后释放相机。新版本日志记录完整的识别成功、SystemUI 认证复核、交付和退出锁屏；用户确认上滑可进入桌面。该轮页面启动至识别成功约 1.1 秒。另验证了无脸超时与恢复、实际撤销相机权限后的拒绝及恢复授权后解锁、非 SystemUI Provider 拒绝、未知授权拒绝。

下载正式模块 `Pixel2DFaceUnlock-coral-0.10.1.apk` 覆盖安装，LSPosed 只启用正式包 `com.yusd.pixel2dface`，作用域只选系统界面，再重启 SystemUI 或手机。首次如要求强认证，请自行输入 PIN/密码。Release 安装包沿用旧签名；GitHub Actions 的 CI 包使用不同证书。本次发行 APK 与已安装、执行设备回归的文件完全相同。

| 安装包 | SHA-256 |
| --- | --- |
| 正式模块 | `3f80a5e5c59ad6b6c1ab25a78a44aa872ed81731a066da0cd7535dce3189b425` |
| 独立验证包 | `4e2d12757ca723448aaef81626bc975910b0ef3b0ae2df7a1d79afcc4d9d2ef3` |

仅一台 Pixel 4 XL / Android 16、单个录入用户完成本次验证。异人拒绝统计、低光/眼镜、照片/视频防伪、PIN 界面和跨 ROM 路径尚未完成新后端复测；Android 10–15 仍为源码适配。RGB 2D 人脸仅用于便利解锁，不用于支付或应用生物认证。v4 模板不兼容旧 APK，回退需要旧配置备份。

[中文安装说明](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.1/dist/INSTALL-ZH-0.10.1.txt) · [修复与实测记录](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.1/LOCKSCREEN-FIX-0.10.1.md) · [模型来源与许可](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.1/app/src/main/assets/models/provenance.json)
