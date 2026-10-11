# 0.10.1 锁屏启动补充

`UnlockFaceCaptureActivity` 在 Manifest 声明 `showWhenLocked`，并在主线程 `onCreate` 中先检查模式和 token 形状、准备 `1×1 / alpha=0`、不可触摸且不可聚焦的宿主窗口，防止异步模型初始化期间被 Keyguard 停止。该阶段不消费结果、不发就绪通知、不唤醒屏幕，也不开相机。分析线程消费私有一次性启动授权并加载身份，主线程复核实际锁屏和电源状态后，才调用 `UnlockCameraHost.attach()` 注册接收器、通知就绪并启动相机。授权失败、超时、停止和销毁仍统一关闭并释放宿主标志。详见 [修复与设备验证](LOCKSCREEN-FIX-0.10.1.md)。

Xposed 的 `xposed_init` 移到 `app/src/module/assets`，仅 debug/release 构建包含；validation Manifest 移除 `xposedmodule`、`xposeddescription`、`xposedminversion`。仅把 `xposedmodule` 设为 false 不足以阻止部分 LSPosed 版本发现模块。设备测试直接检查安装包元数据和资源，正式包必须存在、验证包必须缺失。

## 0.10.0 识别后端

新识别后端、v4 模板、LiteRT 生命周期及独立验证包见 [0.10.0 重构说明](RECOGNITION-REFACTOR-0.10.0.md)。下文为 0.9.3 基线说明；其中 LBP 门槛和 v3 编码约束只适用于旧模板后端。

# 0.9.3 重构维护说明

本文对应 `versionName=0.9.3-refactor`、`versionCode=23` 的源码实现，描述维护约束，不作为真机兼容性、识别率或防伪能力的验证报告。

## 模块职责

Java 源码位于 `app/src/main/java/com/yusd/pixel2dface/`。

| 模块 | 职责 |
| --- | --- |
| `FaceCaptureActivity` | 协调录入、测试、锁屏识别；加载配置快照、管理截止时间、提交唯一结果。 |
| `CameraCaptureSession` / `CaptureSessionGate` | 独占本次相机用例与检测器；串行处理帧；保证关闭与异步完成的先后关系。 |
| `FaceDetectorFactory` / `FaceFrameExtractor` | 配置 ML Kit；将 CameraX、眼部概率、轮廓和地标转换为标量质量信息与特征。 |
| `LbpDescriptor` / `LumaPlane` / `FaceGeometry` | 112×112 亮度采样、7×7×256 LBP 纹理与 7 维脸型比例；复用工作区并检查缓冲区边界。 |
| `FrameQualityPolicy` / `PassiveEyeGate` | 统一面部、眼睛、姿态、画面质量门禁及每帧阈值惩罚。 |
| `PassiveUnlockFailurePolicy` / `CameraHostTransitionPolicy` | 连续闭眼停止本轮识别；精确限定本宿主的锁屏遮挡与动画处理范围。 |
| `RecognitionEngine` / `RecognitionStabilizer` | 纯 Java 识别决策；绑定会话代次、采集时间与多帧证据；不持有图像或 ML Kit 对象。 |
| `EnrollmentSession` | 按采集时间选择 10 个样本，克隆纹理与几何数组，再构建身份模型。 |
| `IdentityModel` / `IdentityModelCodec` | 纹理、区域一致性及几何匹配；严格编码、校验 v3 模板。运行时匹配数组与序列化快照独立。 |
| `TemplateStore` / `AtomicStoreFiles` / `StorePolicy` | 配置与身份快照、原子事务、配置修订号、失败计数和锁定策略。 |
| `HookRuntimeStore` / `UnlockSessionPolicy` | 单独保存心跳、兼容性信息与一次性启动授权，不混入身份模板。 |
| `ConfigProvider` / `ProviderCallerPolicy` | SystemUI 的受限 IPC 接口；根据实际 Binder UID、系统包身份与平台签名检查调用者。 |
| `XposedEntry` / `SystemUiSessionCoordinator` | 安装能力检查后的钩子；协调亮屏、授权、结果复核与弱生物识别状态。 |
| `UnlockSessionState` / `UnlockResultGuard` | 绑定唤醒代次、Android 用户和一次性结果；旧回调不能释放新会话的准备槽或重复交付认证。 |
| `SystemUiCompat` | 封装 SystemUI 私有接口；未知契约拒绝授权；只提交 weak biometric。 |
| `CaptureContentView` / `UnlockCameraHost` / `SystemUiFeedback` | 取景交互、透明相机宿主与锁屏反馈；视觉代码不决定认证成功。 |

## 调用序列

锁屏路径：

1. SystemUI 亮屏事件进入 `SystemUiSessionCoordinator`，检查当前用户、锁屏与弱生物识别许可。
2. IPC 线程通过 `ConfigProvider.prepare_unlock` 发起一次调用；`TemplateStore.prepareUnlockSession()` 在同一文件事务中读取可用状态与 revision、验证模板并写入一次性授权。主线程复核实际锁屏、电源、用户和弱认证状态后立即启动 `UnlockFaceCaptureActivity`，不再等待固定的 620 ms。
3. 应用分析线程通过 `TemplateStore.consumeAuthorizedRecognitionSettings()` 在同一事务中持久删除启动授权、读取当前配置并解码一次模板；只返回仍启用且未锁定的 `RecognitionSettings`。随后开启 `RecognitionEngine` 会话，主线程附加透明宿主并绑定相机。
4. 相机帧进入 ML Kit；`FaceFrameExtractor` 提取质量、纹理与几何；Engine 复核时钟、质量、身份与多帧证据。
5. 达到确认条件后，`TemplateStore.validateAndRecordSuccess()` 在同一存储事务中检查 revision、身份指纹、启用状态与锁定时间。
6. 应用关闭采集，发送携带 token 与 revision 的结果。SystemUI 消费结果票，再读取当前 revision，并复核用户、锁屏与弱认证许可后交付一次认证。

录入和测试从未导出的 `FaceCaptureActivity` 进入。录入使用同一质量策略及更严格姿态门槛，由 `EnrollmentSession` 保存样本；10 帧完成后先争取唯一终态，再写入模型。识别超时为 12 秒，录入超时为 45 秒，初始化也计入截止时间。

## 线程、资源与时钟

- 应用主线程负责界面、相机绑定/解绑及宿主窗口。每个 `CameraCaptureSession` 使用一个串行分析执行器，涵盖配置加载、ML Kit 回调、特征计算与结果提交。其他配置写入通过 `StoreTasks` 的独立队列执行。
- SystemUI 主线程负责原生状态和反馈；配置读取、授权及结果复核在独立 IPC 执行器中完成。纯 `UnlockSessionState` 由协调器锁保护，异步回调均检查 generation。
- `close()` 先禁止新帧，仅解绑本对象持有的 `analysis`/`preview`，不调用 `unbindAll()`。旧会话关闭不会解绑另一个会话的用例。
- 检测器正在推理时不提前关闭；完成回调关闭 `ImageProxy`、释放帧槽，随后才能关闭检测器与执行器。`LumaPlane` 在 `finally` 中解绑，长期工作区不保留 CameraX 平面缓冲区。
- 提取出的 texture 借用工作区，下一次提取即覆盖。同步识别直接使用；录入必须通过 `EnrollmentSession.add()` 克隆。不要把借用数组排入延迟任务。
- 相机 sensor timestamp 的纪元不假定等于 `elapsedRealtime()`。`FrameTimestampNormalizer` 以首帧建立锚点，并根据每帧到达时间取最小映射 offset；首帧排队延迟下降时下调映射，避免后续帧被误判为未来证据。重复 sensor 时间、回退到达时间和重复毫秒证据仍被拒绝。
- detector 开始到回调、归一化采集时间到评估时间均限制 750 ms；提取后录入/识别再次检查。未来、重复、回退或会话开始前的证据不能计票。
- Engine `clear()` 清除连续证据并保留重放水位；`beginSession()` 明确建立新代次并重置时钟，`endSession()` 使迟到回调失效。Activity 停止或销毁时结束采集。

## 存储与兼容

应用私有 `files` 目录中，`face2d.properties` 保存配置与模板；`hook.heartbeat`、`hook.compatibility`、`unlock.session` 保存运行状态。所有读、迁移、读改写及授权消费共用进程锁和 `face2d.store.lock` 文件锁。**不能删除或替换锁文件**，否则跨进程可能锁住不同文件。

`AtomicFile` 配合 `fsync` 和写后回读确认发布；读取限制 128 KiB、256 个属性，拒绝重复键与损坏数据。不存在新文件时才尝试有界迁移 `shared_prefs/face2d.xml`，读取异常不能被当作空配置覆盖。清除模板同时删除遗留身份副本。

身份模板仍为 v3：向量采用小端 float32/Base64，维度、有限值、直方图质量和阈值必须合法。已有不含 checksum 的合法 v3 模板可继续解码；新写入模板附加 SHA-256 checksum。checksum 用于完整性检查，不提供对 root 修改者的身份认证。旧版身份模板需要重新录入；旧格式临时启动授权会过期，二者兼容策略独立。

配置变更、重新录入、清除及进入失败锁定会更新 `store_revision`。在途识别使用旧快照时，应用提交和 SystemUI 交付都会复核 revision；配置通知也会撤销待处理状态。普通失败计数和成功后的计数清零不单独改变身份修订号。禁止绕过事务直接修改属性文件。

0.9.3 保留原有 `state`、`authorize_unlock` 和授权消费方法，支持覆盖安装后应用与仍未重启的旧 SystemUI 混合运行。新准备接口不返回人脸模板，只返回限定的状态字段和授权结果；相机启动前 Provider 调用由两次减为一次，模板解码由四次减为两次。授权消费先持久删除再加载设置，配置读取或校验失败也不能重放已消费的授权。`HookRuntimeStore.writeAuthorization()` / `consumeAuthorization()` 只由持有存储事务锁的入口调用，不能从外部跳过锁或策略检查。

## 保留的识别门槛

| 检查 | 当前数值 |
| --- | --- |
| 双眼 | 两侧概率 ≥0.70，轮廓高度/宽度 ≥0.10；高置信路径要求概率 ≥0.85。 |
| 识别姿态 | yaw/pitch/roll 绝对值 ≤28°/22°/25°；录入 ≤18°/16°/18°。 |
| 取景 | 人脸宽度比例 ≥0.18；中心偏移 X/Y ≤0.34/0.36；裁剪至少 100×100。 |
| 图像 | 亮度 12–248，contrast ≥6，sharpness ≥1.5。 |
| 配置阈值 | `min(录入校准阈值, 档位上限)`；严格/均衡/便捷上限为 0.30/0.33/0.35。 |
| 每帧阈值 | 扣除姿态、质量、取景惩罚后，下限 0.24；三种惩罚上限 0.028/0.026/0.014。 |
| 身份区域 | 49 个纹理区域至少 40 个一致，25 个核心区域至少 21 个一致；几何均值受模型阈值约束，峰值 ≤3.2。 |
| 普通多帧 | 最多 6 帧、至少 5 个观察、至少 4 个匹配，最后至少连续 2 帧匹配。 |
| 高置信多帧 | 连续 4 帧，且额外纹理、几何、区域及质量余量全部通过。 |
| 时间与稳定性 | 证据至少跨 90 ms；窗口 ≤900 ms，帧间隔 ≤350 ms；普通分数跨度 ≤0.055，高置信 ≤0.035；均值保留 0.004 余量。 |
| 失败策略 | 5 次失败后锁定 30 秒。 |

这些核心数值延续原实现。新增合法性检查会拒绝 NaN、Infinity、损坏直方图及不合理轮廓等无效输入，不能将其作为普通身份未匹配票。

## 安全边界

- 睁眼门禁是被动质量判断，不是眨眼挑战或防照片/屏幕/面具攻击的证明。该实现不获得 Android 强生物识别等级。
- `SystemUiCompat` 调用明确 weak 的原生契约，检查 Android 生物识别锁定、StrongAuth、设备策略、SIM 与所选用户。不能通过修改 strong 标志、跳过原生许可或直接强制解锁扩大能力。
- 反射目标、字段布局或许可查询不兼容时保持锁定；源码启用条件为 Pixel、API 29–36，不能由此推断所有版本或 ROM 已通过验证。
- Provider 不返回模板；仅允许本应用 UID，或经过系统包与平台签名检查的 SystemUI UID。0.10.1 导出的锁屏 Activity 只提前准备透明窗口；必须先消费启动授权，才能附加受授权宿主、发就绪通知和开启相机。
- 启动授权绑定 boot count、墙钟与 elapsed 时间，最长 15 秒，成功消费前必须持久删除。结果票绑定 token、用户、generation，最长 18 秒，消费与交付各限一次。
- 熄屏、用户切换、配置变更、StrongAuth/设备策略事件会撤销会话；视觉反馈或宿主 ready 广播不能授予认证。清理本模块插入的状态时保留后来由 Android 写入的原生认证值。
- 待上滑时 `isDeviceLocked=false` 不代表锁屏已退出；同时确认 `isKeyguardLocked=false` 才因退出撤销认证。唤醒未就绪只在同代次的 1.5 秒窗口内重试，进入准备后仅允许一轮配置 IPC 和启动。
- 失败结束立即轮换代次、撤销结果和待恢复认证，并发送受 `STATUS_BAR_SERVICE` 及会话 token 约束的宿主取消信号。宿主成功、失败及取消退出均释放自身遮挡与常亮标志。
- 遮挡拦截要求当前有效会话且前台任务为精确的 `UnlockFaceCaptureActivity`；仅本宿主打开、允许其他目标关闭的动画可以跳过，未知及其他打开/变化目标保留原生行为。该视觉策略不参与身份认证。
- 被动识别中的持续闭眼达到 1.5 秒会停止本轮采集；帧间隔超过 350 ms、检测中断或其他质量结果重置连续时长。录入与主动测试仍按原流程处理。
- Manifest 移除网络权限、禁用应用备份；release 日志只记录流程、原因类别和耗时，不输出身份特征或逐眼概率。启动、首图、首次检测到脸、首次质量通过、首次身份匹配和最终确认分别记录，不能把总耗时全部归因于匹配算法。

## 构建与检查

在仓库目录的 PowerShell 中执行：

```powershell
./build.ps1
```

默认依次运行 `:app:testDebugUnitTest`、`:app:assembleRelease`、`:app:lintRelease`。脚本优先使用工作区 `.toolchain`，或已配置的 `JAVA_HOME`、`ANDROID_HOME`/`ANDROID_SDK_ROOT`；要求 JDK 17、Android SDK 36。

只运行本地单元测试：

```powershell
./build.ps1 -Tasks @(':app:testDebugUnitTest')
```

测试覆盖纯质量/身份/多帧策略、采样所有权、时钟、会话状态、Provider 调用策略、存储编解码及失败规则。存储并发与原子恢复另有 `app/src/androidTest` 中的设备测试。单元测试和 lint 不能替代真实锁屏、相机、升级及 ROM 反射路径验证。

release APK 输出到 `app/build/outputs/apk/release/app-release.apk`；当前 release 使用本地 debug signing 配置以维持既有安装证书，同时不启用 debuggable。维护升级时核对实际签名证书，不把本地构建等同于安装、分发或真机验收。
