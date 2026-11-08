# 0.13.2 全量代码检查

2026-11-08。基于 0.13.1，逐文件检查全部 54 个生产 Java 源文件，同时检查清单、资源、构建配置、工作流、维护脚本和 5 个设备测试辅助类。此次确认并修复两个问题。

## 修复与复现

### 损坏数据页无法完成重新录入

损坏的属性文件会使 storageAvailable 为 false。首页原先显示“重新录入”，面容区的录入按钮也仍可点击；采集完成后，保存操作再次读取同一个损坏文件并失败。

首页现在进入现有的删除确认流程，仍须通过系统锁屏密码确认。数据不可读时禁用面容区的录入按钮。明确删除成功后，首页才恢复录入入口。取消删除或取消密码确认均保留原文件；磁盘访问失败不会被当成可以覆盖的空数据。

MainActivityRecoveryTest 在 API 29、35 上实际启动设置页，使用损坏文件检查取消、密码确认、删除后的状态和重新进入录入的完整路径。

### 歪头放大眼部开合测量

眼睛轮廓原先按屏幕坐标计算包围框宽高。轮廓随头部倾斜时，即使眼睑几乎闭合，斜线在屏幕上的高度也会被算成开合高度，影响辅助轮廓门槛。

现在沿双眼连线及其垂直方向测量轮廓。睁眼概率仍为独立的主要门槛，轮廓比例门槛、身份距离、连续帧要求均保持原值。缺失或退化的眼位不能提供有效轮廓证据。

EyeContourTest 检查 -25° 至 25° 的近闭合轮廓、正常睁眼轮廓、左右眼顺序反转和无效眼位。原实现的两项角度测试失败，修复后通过。

## 逐文件检查范围

| 范围 | 文件 | 检查重点 |
|---|---|---|
| SystemUI 接入 | XposedEntry、SystemUiCompat、SystemUiSessionCoordinator、SystemUiFeedback、SystemUiLog | 钩子能力探测、系统弱认证策略、用户切换、认证缓存归属、异步回调与撤销、动画清理、日志内容 |
| 会话与跨进程入口 | UnlockSessionState、UnlockResultGuard、UnlockSessionPolicy、HookRuntimeStore、ConfigProvider、ProviderCallerPolicy、CameraHostTransitionPolicy | 调用方 UID、广播权限、一次性令牌、代次与有效期、配置复核、启动授权与输入层适配 |
| 相机与采集生命周期 | FaceCaptureActivity、UnlockFaceCaptureActivity、UnlockCameraHost、CameraCaptureSession、CaptureSessionGate、FaceDetectorFactory、SingleFaceDetector、FrameTimestampNormalizer、FaceFrameExtractor | 关屏取消、初始化与销毁顺序、帧关闭、检测器和模型释放、超时、失败记录、双阶段人数检测 |
| 判定与连续证据 | RecognitionEngine、RecognitionStabilizer、FrameQualityPolicy、PassiveEyeGate、PassiveUnlockFailurePolicy | 过期帧、重放、断续证据、闭眼中止、姿态和图像质量、动态收紧门槛 |
| 图像与身份模型 | FaceAlignment、AlignedRgbSampler、LumaPlane、LbpDescriptor、FaceGeometry、MobileFaceEmbedder、IdentityModel、EmbeddingIdentity、IdentityTemplate、IdentityEvidence、EmbeddingEnrollment、EnrollmentSession | 旋转、步长、取景边界、有限值、归一化、模型摘要与输入形状、录入一致性、v3/v4 后端隔离 |
| 持久化 | TemplateStore、TemplateCodec、IdentityModelCodec、AtomicStoreFiles、LegacyStoreMigration、StorePolicy、StoreTasks | 原子写入、进程间锁、格式与大小边界、损坏数据保留、历史数据清理、修订号和失败锁定 |
| 设置与界面 | MainActivity、DeviceCredentialConfirmation、CaptureContentView、AnimationGallery、AnimationStyle、UnlockFaceAnimationView、LockscreenStyleRenderer、Ui、Constants | 密码确认、恢复入口、生命周期、异步设置保存、结果重试、预览及动画回调释放 |

其他文件：

- 主包与验证包 Manifest、全部资源 XML、LSPosed 入口与作用域、模型来源及许可证。
- 根目录与应用 Gradle 配置、Wrapper 配置、build.ps1、Android CI 工作流。
- tools/restore_legacy_enrollment.py：原设备限制、原文件摘要、事务导入、暂存清理。
- StorageInstrumentation、NeuralInstrumentationChecks、EnrollmentTransferInstrumentation、UiReviewInstrumentation、MotionArtworkReview：合成数据隔离、导入授权、模拟器限制及测试工具不进入正式 APK。

上述范围是本仓库自有代码；第三方库和模型文件检查依赖版本、打包边界及固定摘要，没有将其全部上游源码纳入本轮逐行检查。

## 验证结果

- 修复前，新增的 6 个复现场景全部失败；修复后，新增 7 项检查及原有回归合计 238 项通过，0 失败、0 错误。
- Release、Validation、Debug AndroidTest、Validation AndroidTest 构建通过。
- Release Lint：0 错误，3 条已有警告，分别为旧 API 广播注册、显式导出的受调用方校验保护的 Provider，以及最低版本冗余判断。
- 正式 APK 不可调试，只有相机及应用自身的广播权限，未包含网络权限；验证包没有 Xposed 入口。
- 四种 ABI 均保留，正式 APK 的 16 KB 对齐和原签名校验通过，模型摘要未改变。

2026-11-09 补充完成 Pixel 4 XL / Android 17 同签名安装和模块加载验证，连续亮灭屏、冷启动关屏及自动息屏后相机释放通过。用户确认正脸和轻微歪头均可识别并上滑进入桌面。全部设备场景和按键注入异常见 [真机验证记录](dist/DEVICE-VERIFICATION-0.13.2-20261109.md)；公开安装包未改变。
