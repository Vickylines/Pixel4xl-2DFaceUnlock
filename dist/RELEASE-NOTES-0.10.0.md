# v0.10.0-candidate · 离线 MobileFaceNet 识别后端重构

将原先写死的纹理/脸型比对抽象为可替换的身份接口，加入离线 MobileFaceNet、五点对齐和严格 v4 模板。覆盖升级保留现有 v3 身份与原算法；在正式应用中重新录入后才使用新后端。本版本作为预发布候选分发，0.9.3 保留为稳定版。

- 新后端使用 192 维归一化特征与余弦距离，保留质量、睁眼、会话时钟、代次、多帧确认和一次性授权保护。未知模型、损坏模板或校验失败会拒绝认证。
- 相机 YUV 帧直接采样到对齐 RGB 区域，模型在分析线程离线运行，退出后释放相机与推理资源。正式包和验证包均无网络权限。
- 独立“2D 人脸 · 验证版”用于录入和主动测试，拥有独立数据，不接管 LSPosed 锁屏。它的录入不会自动迁入正式模块。
- 模型固定到上游 commit 和 SHA-256，保留上游 MIT 许可；仓库许可不等于单独确认训练数据权利或产品级身份认证能力。

验证结果：154 项 JVM 测试、326 项正式包真机断言通过；Release Lint 为 0 错误、7 条警告。Pixel 4 XL / Android 16 完成真人录入与主动识别，设备日志确认一次完整锁屏认证与退出；熄屏取消、相机不可用超时、失败后恢复采集以及非 SystemUI 调用拒绝均通过。30 次合成输入推理中位数/P95 均为 38 ms，该基准不代表真人识别率或整体解锁延迟。

下载 `Pixel2DFaceUnlock-coral-0.10.0-candidate.apk` 覆盖安装原模块，LSPosed 作用域只选系统界面，再重启 SystemUI 或手机。重启后的首次解锁使用 PIN/密码。Release 包沿用既有安装签名且不可调试；GitHub Actions 的 CI 包使用不同证书。分发 APK 与本次已安装实测文件完全相同；源码随后只增加发布文档、CI 验证包构建和 provenance 发布状态说明，未重打安装包。

若要用新算法，需在正式应用重新录入。新 v4 模板不兼容旧 APK，请在重新录入前保留旧 APK 和配置备份。维护设备的一次性迁移与恢复工具依赖私有备份和同签名维护 APK，Release 不包含人脸数据、备份或维护测试 APK。

| 安装包 | SHA-256 |
| --- | --- |
| 正式模块 | `e3f6fdbe02982831b35e679ce5b020d450daae044d3ffa21bf113bcc9bc29ec6` |
| 独立验证包 | `8e0c37e7d12041e1dfd730cdc46ab9b3561adee46d4dc5bf9017d1c8d2baed73` |

本次只验证一台 Pixel 4 XL / Android 16、单个录入用户。异人拒绝、低光/眼镜表现、照片/视频防伪、PIN 界面和跨 ROM 路径尚未完成新后端复测。普通 RGB 人脸只适合便利解锁，不用于支付或应用生物认证。Android 10–15 仍只有源码适配。

[中文安装说明](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.0-candidate/dist/INSTALL-ZH-0.10.0.txt) · [重构与实测记录](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.0-candidate/RECOGNITION-REFACTOR-0.10.0.md) · [模型来源](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/blob/v0.10.0-candidate/app/src/main/assets/models/provenance.json)
