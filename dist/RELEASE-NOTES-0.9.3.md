# v0.9.3 · 人脸模块重构与启动优化

解决识别成功后上滑不能进入桌面、闭眼失败后触摸异常，以及启动中的多余等待。拆分相机、识别、存储与 SystemUI 会话职责，合并状态读取和一次性授权事务：相机启动前的 Provider 调用由两次减为一次，模板解码由四次减为两次，并移除固定的 620 ms 等待。

- 失败、超时和取消立即撤销本轮会话；持续闭眼约 1.5 秒停止识别，退出时释放相机宿主自己的遮挡和常亮标志。
- 加强旧回调隔离、配置撤销、跨进程文件事务、模板校验和一次性授权消费；保留旧调用协议供覆盖升级使用。
- 保留 v3 纹理/几何双信号、睁眼门禁及多帧阈值。有效 0.8.x / 0.9.x 模板无需重新录入，覆盖安装保留数据。
- 139 项 JVM 测试通过；真机存储回归在 11 个隔离流程中通过 216 次断言；Release Lint 为 0 错误、13 条警告。Pixel 4 XL / Android 16 人工确认正常解锁、上滑、闭眼失败恢复、速度和触摸正常。实际耗时仍受光线、姿态和首次匹配影响。

下载安装包 `Pixel2DFaceUnlock-coral-0.9.3-refactor.apk`，直接覆盖旧版，再重启 SystemUI 或手机。重启后的首次解锁使用 PIN；LSPosed 作用域只选系统界面。Release 安装包与旧版签名一致且不可调试；GitHub Actions 的 CI APK 使用不同证书，供构建验证使用。

SHA-256：`1f67d1eb27b57887081ac7cbcbfddfb1b1013b7b5e2229b1b67d4e66ec9c120b`

目标为 Pixel arm64、Android 10–16（API 29–36）；本次真机验证仅覆盖 Pixel 4 XL / LineageOS 23.2 / Android 16。普通 RGB 2D 人脸仍可能被照片或视频绕过，只适合便利解锁，不用于支付或应用生物认证。

[中文安装说明](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.9.3/dist/Pixel_2D%E4%BA%BA%E8%84%B8%E8%A7%A3%E9%94%81_%E5%AE%89%E8%A3%85%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.txt) · [架构说明](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.9.3/ARCHITECTURE.md) · [验证报告与边界](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.9.3/REFACTOR-REPORT.md)
