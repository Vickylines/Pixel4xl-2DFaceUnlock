# 贡献指南

感谢关注并参与本项目的开发与改进！在提交 Issue 或 Pull Request 前，请阅读以下说明：

---

## 报告 Issue

提交 Issue 时，请尽量提供详细的环境信息以帮助定位问题：
- **设备型号**：如 Pixel 4 XL、Pixel 7 等
- **系统版本**：Android 版本及具体的 ROM 名称（如 LineageOS 21、AOSP 等）
- **框架版本**：LSPosed / Xposed 框架的具体版本
- **问题描述**：复现步骤、实际表现与预期结果
- **框架日志**：包含 `Pixel2DFaceUnlock` 或 `2D人脸解锁` 的相关日志片段（**注意：请在提交前隐去个人隐私、账户与敏感信息**）

---

## 提交 Pull Request

1. **保持核心逻辑安全**：人脸识别与认证涉及锁屏安全边界，任何修改不得破坏系统的默认保护（如开机首次解锁强制回退 PIN 等安全规则）。
2. **本地编译与测试**：在提交代码前，请确保本地通过编译与测试：
   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
   ```
   Windows 环境下亦可直接使用 `build.ps1`。
3. **隐私合规**：严禁在代码、资源或测试用例中提交任何真实的人脸照片、特征模板或私有签名证书。
