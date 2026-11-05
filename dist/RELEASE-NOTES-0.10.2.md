# 0.10.2 人脸识别与授权修复

本版集中修复录入授权、锁屏会话竞态和数据恢复问题，沿用已有签名及 v3/v4 模板，无需重新录入。

- 录入人脸、修改识别设置和删除模板前，必须完成一次系统 PIN、图案或密码确认。取消、页面重建和重新锁屏后，旧确认不能继续授权。
- 模块信号采用签名权限，锁屏相机宿主限制为系统状态栏服务调用；配置变更会撤销旧授权。
- 先在同一帧检查人数，再读取轮廓，修复轮廓模式只返回主要人脸时漏掉多人画面的问题。
- 眼睛信息缺失不再计为持续闭眼；闭眼与信息缺失仍拒绝识别并清空旧票。
- 修复冷却结束后无法重试、宿主退出或迟到唤醒丢弃有效结果，以及已撤销的认证被异步恢复的问题。
- 成功与失败记录按顺序落盘，避免迟到的失败写入覆盖成功后的计数重置。损坏模板可经身份确认后删除重录；正常读取不会覆盖损坏文件，实际 I/O 错误仍会报错。
- 显式排除云备份和设备迁移中的全部应用数据域，保持人脸模板和授权数据留在本机。

本地验证：198 项 JVM/Robolectric 测试通过，0 失败、0 跳过；正式包、独立验证包及两套设备测试 APK 编译通过；Release Lint 为 0 错误、7 条已有警告。已检查安装包签名、权限、模型哈希和 Xposed 入口。此次未安装到手机，未执行真机锁屏复测；旧版实测记录仍按原版本保留。

下载 `Pixel2DFaceUnlock-coral-0.10.2.apk` 覆盖安装，LSPosed 作用域只选系统界面，然后重启 SystemUI 或手机。系统要求强认证时，先输入 PIN/密码。`Pixel2DFaceUnlock-0.10.2-validation.apk` 用于独立录入和主动测试，不接管锁屏。GitHub Actions 构建使用 CI 证书，覆盖升级请使用本 Release 附件。

本次模型和身份阈值未调整。Android 10–16 为源码适配范围；普通 RGB 2D 人脸仍只用于便利解锁，不用于支付认证。

[中文安装说明](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.2/dist/INSTALL-ZH-0.10.2.txt) · [SHA-256 校验](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.2/dist/SHA256SUMS-0.10.2.txt) · [模型来源](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.2/app/src/main/assets/models/provenance.json)
