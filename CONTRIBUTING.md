# 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请：

1. 写明 Pixel 型号、Android/API、ROM、Magisk 与 LSPosed 版本。
2. 区分“编译通过”“模块已加载”和“锁屏端到端验证”，不要把源码适配写成真机验证。
3. 不要提交人脸模板、设备备份、签名文件、访问令牌或包含私人信息的完整日志。
4. 保持认证失败关闭：关键用户、策略或 SystemUI 状态无法确认时，必须保持锁定。
5. 运行 `./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease :app:assembleDebugAndroidTest --no-daemon`，确认单元测试、Release 构建、Lint 和设备测试 APK 编译通过。Windows 可运行 `build.ps1`，额外传入设备测试构建任务。

修改存储、迁移、授权或 Provider 时，连接测试设备并运行 `adb shell am instrument -w com.yusd.pixel2dface.test/com.yusd.pixel2dface.StorageInstrumentation`；先安装应用和设备测试 APK。测试使用合成数据与隔离缓存目录，不使用真实录入。实际相机、锁屏和跨 ROM 路径另需人工复测，不能用设备测试 APK 编译成功代替。

GitHub CI 的 APK 供构建检查使用，签名取决于 CI 环境。公开升级包应核对既有签名、版本、SHA-256 和实际覆盖安装结果；不要将 CI 产物直接当作已验收的升级包。实现边界见 [架构说明](ARCHITECTURE.md)，已有设备证据见 [重构报告](REFACTOR-REPORT.md)。

