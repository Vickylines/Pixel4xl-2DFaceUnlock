# 参与贡献

欢迎提交 Issue 和 Pull Request。提交前请：

1. 写明 Pixel 型号、Android/API、ROM、Magisk 与 LSPosed 版本。
2. 区分“编译通过”“模块已加载”和“锁屏端到端验证”，不要把源码适配写成真机验证。
3. 不要提交人脸模板、设备备份、签名文件、访问令牌或包含私人信息的完整日志。
4. 保持认证失败关闭：关键用户、策略或 SystemUI 状态无法确认时，必须保持锁定。
5. 运行 `./gradlew :app:assembleDebug :app:lintDebug --no-daemon`，确认构建和 Lint 通过。

