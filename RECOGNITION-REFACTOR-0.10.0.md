# 0.10.0 识别后端重构（预发布候选版）

实现与实测日期：2026-10-08–09。基线：`ad8dd75` / `0.9.3-refactor`。经用户授权，手机已覆盖安装 0.10.0 候选正式模块；随后按用户“发布”指令，以 `v0.10.0-candidate` 预发布分发同一实测 APK。0.9.3 保留为稳定版。

## 本次解决的问题

0.9.3 已经拆开相机、存储和 SystemUI 会话，但识别接口仍硬编码 12,544 维 LBP 纹理、7 维脸型比例和对应阈值。增加其他识别模型需要同时修改存储、会话和取景逻辑。

本次加入 `IdentityTemplate` / `IdentityEvidence`，将后端特有的距离和判定与公共质量门禁、时间水位、多帧投票、一次性授权及 SystemUI 接口分开。`IdentityModel` 继续执行原 v3 比对，`EmbeddingIdentity` 使用 192 维 MobileFaceNet 特征。新录入写入 v4；现有 v3 数据保持原算法，不能通过格式转换得到神经网络特征。

## 参考项目与采用范围

| 来源 | 实际采用或判断 |
| --- | --- |
| [UniversalAuth](https://github.com/null-dev/UniversalAuth) | 核对其模块式认证方案。其人脸模块依赖 Megvii 厂商闭源库，本次不提取或引入。 |
| [Android-MobileFaceNet-MTCNN-FaceAntiSpoofing](https://github.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing) | 使用固定 commit 的 `MobileFaceNet.tflite`；核对 RGB 输入归一化及 192 维输出。保留上游 MIT 许可。未引入其 MTCNN、反欺骗模型或示例认证阈值。 |
| [OnDevice-Face-Recognition-Android](https://github.com/shubham0204/OnDevice-Face-Recognition-Android) | 参考端侧 embedding 比对的职责划分；单用户锁屏无需向量数据库，未添加。 |

模型来源、版本、SHA-256、原始尺寸和限制记录在 `app/src/main/assets/models/provenance.json`。上游仓库声明 MIT，但这不等于独立确认了训练数据权利或产品级身份认证能力。候选包保留上游许可；发布没有改变模型、安装包签名或之前记录的 APK 哈希。已实测 APK 内的 provenance 文本仍含发布前的本地候选标签，源文件已补充本次公开预发布状态。

## 新识别路径

1. CameraX 输出 YUV420，ML Kit 继续负责单脸、眼睛、姿态和地标检测。
2. 五点相似变换对齐到 112×112；按旋转角映射回原始 Y/U/V 平面，尊重各平面的 row stride、pixel stride 与 ByteBuffer position。采用 BT.601 limited-range 转换为 RGB，按 `(channel - 127.5) / 128` 归一化。真实设备色彩范围与跨相机泛化仍需样本验证。
3. 直接采样对齐区域，不生成整帧 Bitmap/JPEG。计算亮度、对比度、清晰度，通过质量门禁后才运行 LiteRT 1.4.0 CPU 推理。
4. 固定图含两图 batch 的内部 reshape。真机证实强制 batch=1 会失败，因此输入同一帧的两份副本，使用第一份 192 维结果；不能把双 batch 当成两张独立观察票。
5. 每份输出归一化，使用余弦距离。模型绑定、维度、有限值、单位范数、checksum 和阈值验证失败时拒绝，不降级为 LBP。
6. 十份录入样本间隔至少 280 ms；任意两份距离超过 0.22 时拒绝混入。自动阈值限制在 0.18–0.28，严格/均衡/便捷上限分别为 0.22/0.26/0.28。它们是候选参数，没有经过真实误接受率标定。
7. 公共引擎保留睁眼、质量、时钟、会话代次及多帧要求。新后端禁用旧四帧快速路径，使用至少五次观察、四次匹配和末尾连续两次匹配；旧后端阈值与快速路径保留。
8. 原有配置 revision、身份指纹复核、单次启动授权和 weak biometric 提交链继续生效。模型和 LiteRT 在分析线程运行，关闭时在所有帧处理结束后释放。

## 模板与验证包

- v3 文件不自动重写；完成新录入后才通过原子事务替换为 v4，并清除旧模板字段。
- v4 checksum 必须存在，绑定模型 ID、特征和阈值。未知版本/未知模型/损坏模型不能回退。
- 独立包 `com.yusd.pixel2dface.validation` 使用自己的 Provider authority、广播、任务栈和私有目录，关闭 Xposed 模块声明及入口钩子。名称为“2D 人脸 · 验证版”，用于录入和主动测试，不接管锁屏。
- 原 APK 与配置备份在忽略目录 `device-backups/20261008-before-neural-refactor/`，不纳入 Git。常规回归测试使用合成身份和隔离目录，不读取或写入用户真实模板；另一个维护入口仅在明确指定迁移参数、校验源与目标哈希后处理真实配置。
- 完整候选包沿用原包名和本机签名，已覆盖安装；经一次性签名维护工具，将用户已确认成功的验证版 v4 身份迁入正式包，原严格档位、动画、启用状态和失败计数保持不变。迁移持有原存储锁，递增 revision 并撤销旧授权；辅助 APK 与临时文件随后移除。
- 新模板不兼容旧版 APK。提供 `tools/restore_legacy_enrollment.py --apply`，通过相同事务入口恢复原 v3 身份与旧识别后端，保留现有偏好且不降级 APK。默认不带参数只核对本地回退材料；回退逻辑已通过合成数据真机测试，未对实际手机执行回退。

## 验证

最终本地结果：154 项 JVM 测试通过（0 失败、0 错误、0 跳过）；Release、Validation 和 Validation AndroidTest APK 构建成功；Release Lint 0 错误、7 条警告。合并后的两个 APK 均无网络权限，模型和许可随包离线提供。完整候选 APK 的签名与设备原 0.9.3 一致；独立验证安装后，原配置与备份字节完全相同。

交付目录 `dist/`：`Pixel2DFaceUnlock-coral-0.10.0-candidate.apk`（完整模块，已安装）、`Pixel2DFaceUnlock-0.10.0-validation.apk`（独立验证包）、`SHA256SUMS-0.10.0.txt` 和 `INSTALL-ZH-0.10.0.txt`。

构建命令：

```powershell
./build.ps1 -Tasks ':app:testDebugUnitTest',':app:assembleRelease',':app:lintRelease',':app:assembleValidation',':app:assembleValidationAndroidTest','-Pvalidation'
```

设备测试：

```powershell
../adb/adb.exe install -r app/build/outputs/apk/validation/app-validation.apk
../adb/adb.exe install -r app/build/outputs/apk/androidTest/validation/app-validation-androidTest.apk
../adb/adb.exe shell am instrument -w com.yusd.pixel2dface.validation.test/com.yusd.pixel2dface.StorageInstrumentation
```

已在 Pixel 4 XL / Android 16 上通过 268 项断言，覆盖原存储回归、v3→v4 迁移、模板变更撤销、授权单次消费、未知模型拒绝、原生推理、输入敏感性、重复推理及关闭/重建。30 次合成输入基准：初始化 50 ms、首次推理 44 ms、推理中位数 39 ms、P95 40 ms。该数据不代表真人识别率或整体解锁延迟。

2026-10-08，用户在已安装的独立验证版中完成真人录入与主动识别，并明确反馈“录入和识别都成功”。这是单用户手动验证，不等同于误接受率或完整锁屏流程验证。

尚未验证新后端的统计识别率、异人拒绝、低光/眼镜表现、照片/视频攻击和跨 ROM 兼容性。保持候选版及普通 RGB 弱生物识别定位；不能由一次成功解锁推断上述能力。

## 正式安装与夜间真机回归

用户确认主动识别成功后，明确授权“接着做完”。以下操作已完成：

- 新备份目录 `device-backups/20261008-before-full-install/` 保存两个应用的原 APK、原 v3 配置、已验证的 v4 配置与哈希。所有特征文件均在忽略目录，未进入 Git 或日志。
- 覆盖安装正式包 `versionCode=24` / `0.10.0-candidate`，已安装 APK 的 SHA-256 与 `dist/` 交付文件完全相同。
- 迁入的 v4 模型字段与验证版逐项一致，原严格档位和动画设置保留；文件 checksum、模型 ID、revision、源/目标文件哈希和临时文件清理均校验成功。
- 正式包上最终通过 **326 项设备断言**（加入迁移失败拒绝、设置保留和 v4→v3 事务回退测试）。此前 154 项 JVM 测试仍通过，主应用代码未因后续部署修改。
- 仅重启 SystemUI，使同包名 LSPosed 钩子重新加载。新进程安装钩子并收到心跳，之后保持存活；没有重启手机。
- 实机日志出现一次完整成功链路：generation 6，`result_received success=true` → `result_recheck_passed` → `delivery_completed` → `keyguard_exit_check deviceLocked=false, keyguardLocked=false, confirmed=true`。相机随后释放。此处是设备日志确认，用户未另行反馈完整锁屏体验。
- 本次成功捕获总计 3981 ms，其中约 2849 ms 后首次检测到脸；首个质量合格/匹配帧到确认约 515 ms。不能将等待出现人脸的时间归为模型推理耗时。
- 普通 shell 访问 Provider 被 `Caller is not SystemUI` 拒绝。
- 熄屏中断测试释放相机并撤销会话。临时将本应用 Camera AppOps 设为 ignore 后，约 12 秒超时返回失败，保持锁屏并解除宿主遮挡；随后恢复原 allow 模式与原 UID foreground 限制。重新亮屏已看到新的相机 client/首帧，证明失败后可重新采集。一次过短的 2 秒探针提前取消了启动，已改为等待实际相机证据后验证。
- 两个测试 APK 已卸载，验证版应用及其数据保留备用。最终状态：手机熄屏、锁屏仍显示、无活跃相机 client、正式模块启用。

本地证据：`test-artifacts/device-production-regression.txt`、`enrollment-transfer-qa.json`、`production-systemui-final.log`、`production-failure-smoke.json`、`qa-0.10.0.json`。证据目录、设备 APK 备份和人脸配置留在本地，未进入 Git 或 Release。公开说明见 `dist/RELEASE-NOTES-0.10.0.md`。
