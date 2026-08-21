# 真机测试报告

测试设备：Google Pixel 4 XL（coral），LineageOS 23.2-20260320-NIGHTLY，Android 16 / API 36。

说明：0.6.0 已在本机完成构建、覆盖安装、SystemUI 注入及端到端锁屏回归；Android 10–15 仍属于源码适配，未逐版本真机验证。

## 0.5.1 及之前真机已验证

- Gradle 构建成功，APK 通过 v2 签名校验。
- APK 仅声明摄像头权限和 Android 自动生成的应用内动态广播权限；未声明网络权限。
- LSPosed 作用域仅为 `com.android.systemui`，SystemUI 注入与唤醒钩子加载成功。
- 原有 3 组人脸模板从旧存储无损迁移到原子文件。
- 应用内识别测试通过，匹配距离 `0.28401855`。
- 三次独立“锁屏 → 亮屏 → 前摄识别 → 解锁”测试均通过，匹配距离分别为 `0.371466`、`0.34931213`、`0.36438417`。
- 0.3.0 原生锁屏叠加：动画直接添加到 SystemUI 的 `NotificationShadeWindowView`，运行日志确认 `Animation attached to native time-lockscreen root`；识别 Activity 只保留 1×1 不可触摸的相机分析宿主，不承担动画界面。
- 0.4.2 将动画精简为 56dp 原生锁屏图标，并在相机宿主启动前保留 620ms 锁屏稳定时间；配置查询与 ML 引擎初始化均不再阻塞界面线程。
- 已定位并拦截 coral 的 `OccludeActivityLaunchRemoteAnimationRunner`：日志确认 `Suppressed camera-host keyguard occlude animation`，相机任务不再让 SystemUI 从原生锁屏切到 `OCCLUDED`。
- 以白色模块主页作为后台应用回归，亮屏后 0.35、1、2、4 秒四次截屏均持续显示日期、大时钟和人脸图标，没有再露出后台白色界面；12 秒识别超时退出后仍为 `NotificationShade` / `mDreamingLockscreen=true`。
- 无截屏干扰的 SystemUI 帧统计：290 帧中 6 个 jank 帧（`2.07%`），P50 `17ms`、P90 `27ms`、P95 `28ms`、P99 `40ms`，Missed Vsync 为 1。
- 动画胶囊周围的光效/光晕已移除；成功文案改为“验证成功 / 向上滑动打开”。
- 被动认证流程验证通过：识别成功后 Keyguard 保持显示，不直接进入桌面；随后向上滑动无需密码，桌面成为前台。
- 0.4.x 闭眼回归曾验证：持续闭眼 12 秒时识别超时且没有进入桌面。0.5.0 调整了自然睁眼容差，仍保留双眼概率与眼睑轮廓双重检查，但本轮没有要求用户再次执行闭眼动作。
- 0.5.0 自然注视真机测试通过：连续 3 帧匹配距离约 `0.337–0.411`，均低于收紧后的默认阈值 `0.42`；最快一轮会话启动到成功约 `0.94` 秒，相机首帧到成功约 `0.46` 秒。
- 成功后 Keyguard 状态实测仍为 `NotificationShade` / `mDreamingLockscreen=true`；执行上滑后切换为 Launcher / `mDreamingLockscreen=false`，没有出现密码界面。
- “成功后不滑、直接关屏再亮”回归通过：第一次成功后第二次亮屏创建了新的 FaceCaptureActivity 并重新挂载原生锁屏动画，没有继承旧认证。
- 检测器使用快速模式且每个识别会话独立持有；人脸模板和阈值只在会话初始化时读取一次。当前帧回调完成后再关闭检测器和分析线程，避免跨 Activity 复用。
- 0.5.0 快速重锁压力测试最初复现了 ML Kit `DuplicateTaskCompletionException`；改为独立检测器和延迟释放后，连续“识别中熄屏/亮屏”回归未再崩溃，应用进程保持存活。
- 解锁模式未创建 CameraX Preview，只绑定 ImageAnalysis；录入/测试模式仍保留预览。
- 成功结果加入重新触发冷却，验证后未再次弹出识别窗口。
- 0.4.5 快速重锁回归：连续两轮 0.7 秒间隔的熄屏/亮屏均先清除上一轮弱认证，再重新触发人脸会话；日志中旧认证恢复次数为 0。
- 0.4.6 增加凭据界面分流：普通锁屏识别成功只保存一次性弱认证并等待上滑；`StatusBarKeyguardViewManager.isBouncerShowing()` 为真时改走系统人脸认证回调，以便从 PIN/密码界面直接完成解锁。普通锁屏分支已在真机确认 `Credential bouncer showing=false` 且 Keyguard 保持显示。
- 0.4.7 接入 Android 16 原生 `DeviceEntryIconView`：普通锁屏人脸成功后驱动系统自带 `LOCK → UNLOCK` 矢量动画，息屏或重新锁定时恢复 `LOCK`。真机日志已确认原生控件钩子加载以及 `Native lock icon state=locked` 复位调用成功。
- 0.4.8 将圆形动画升级为带自然眨眼、呼吸光环、巡游光点和柔和扫描线的面容球；成功态以 610 ms 收束成绿色勾。解锁动画仍会在 Keyguard 隐藏前同步移除，因此不会残留到桌面。
- 0.4.9 在同一个 APK 中增加“面容光环 / 灵动岛”双风格持久化切换。灵动岛真机截帧已确认胶囊展开、“正在识别”、流动微光和“已识别”收束；SystemUI 日志确认 `style=1`。识别后仍记录 `Credential bouncer showing=false`、`waiting for upward swipe`，且 `isKeyguardShowing=true`，解锁逻辑未改变。
- 0.5.0 移除解锁时的眨眼/转头模式和相关冗余状态；增加正脸、居中、脸部大小、亮度、对比度、清晰度、正面模板以及连续三帧分数稳定性检查。无脸状态持续 12 秒后保持 Keyguard 锁定并正常超时，没有复用上一轮认证。
- 0.5.0 将整帧 Bitmap 转换链改为直接采样 Y 平面并复用灰度、直方图、均衡映射和描述子缓冲区；现有 3 份 LBP 模板保持兼容，无需重新录入。
- 0.5.1 统一升级“面容光环 / 灵动岛”视觉：锁屏动画容器由 132×56dp 扩大到 176×76dp；灵动岛目标尺寸约 150×48dp，并增加深色玻璃层次、细描边和顶部高光；面容光环扩大主体、双层轨道、扫描线与成功/失败图形。两套样式均已在真机锁屏截帧确认位置正确，未遮挡日期或时钟，解锁逻辑与识别参数未改动。

## 0.6.0 Android 16 真机验证

- 最低版本降至 API 29，并增加 Android 10 布尔认证缓存、Android 11–16 类型化认证缓存、单/双参数人脸回调、AOSP 唤醒方法与亮屏广播后备、旧版 `StatusBarWindowView` 动画根、动态远程动画完成回调探测。普通锁屏认证缓存写入后必须经 `getUserCanSkipBouncer` 反查；PIN 页在策略与用户检查通过后调用 SystemUI 官方人脸回调，由 SystemUI 异步完成解锁。
- 锁屏隐藏清理增加 `KeyguardViewMediator.setShowingLocked`、`KeyguardStateControllerImpl.notifyKeyguardState` 与相机宿主结束通知后备，降低不同 SystemUI 版本切到桌面后动画残留的风险。
- 对非 Pixel、API 36 以上、缺失 `KeyguardUpdateMonitor`、策略状态不完整或未知认证缓存的环境采用安全停用；可选锁图标或动画根缺失不会中断其余钩子。
- 当前只允许 SystemUI 进程所属主用户；访客或辅助用户不会复用主用户模板。
- 开机首次 PIN 前不访问凭据加密的配置 Provider；收到 `ACTION_USER_UNLOCKED` 后再发布适配心跳，避免把正常的 Direct Boot 限制记录成模块异常。
- 录入／测试 Activity 已改为不导出；导出的 SystemUI 解锁别名必须消费由受限 Provider 签发的一次性 15 秒会话授权，验证失败会在显示锁屏窗口和打开相机前退出。
- `assembleDebug` 与 `lintDebug` 均通过；Lint 为 0 error。剩余警告主要是简体中文硬编码、Pixel 专用 arm64、受调用 UID 检查保护的导出配置 Provider，以及刻意保留的已验证依赖版本。
- 已以相同签名从 versionCode 15 覆盖安装到 16；安装前后 `face2d.properties` 均为 200887 字节，SHA-256 均为 `32eecc7469b498edf6c5efd9ff4f39d08c03264b3e370224ef3c92ad729c0f65`。
- 重启及单独重启 SystemUI 后，LSPosed 日志确认凭据页、原生锁图标、原生动画根、遮挡保护、跨版本退出清理、coral Android 16 唤醒适配和屏幕广播后备均已加载；SystemUI PID 保持稳定，未见相关 FATAL/ANR。
- 开机强认证未完成时，人脸会话被正确跳过；配置心跳被延后而非访问未解密 Provider。
- 普通锁屏识别成功后仍停留锁屏并等待上滑；原生小锁切换为打开状态，上滑后无需 PIN。快速熄屏再亮会清除上一轮认证并建立新会话，未复用旧结果；动画在进入桌面前清理。
- 最终修正版在 PIN 页取得连续 3 帧匹配（距离 `0.40542898`）后，经双参数 SystemUI 回调直接解锁；随后 `Keyguard showing=false`，未再出现同步状态查询导致的误拒绝。
- 最终 APK SHA-256：`8AED7C99ED4014BF02E5E90B6EAB68CED931E33122C99867D3B982EB1F19E59D`；v2 签名证书 SHA-256 与上一版一致：`769bae7364e73ad5c73835c7fbce30994ea4e9ff9efd32fde51404886d5ad7da`。

## 已知现象

- 相机启动时出现一次对只读相机属性的 SELinux 拒绝日志，但 CameraService 随后正常开始视频流，不影响识别和解锁。
- 个别相机关闭过程出现约 200 ms 的线程等待警告，没有造成崩溃。
- 已多次重启真机验证：开机后系统先要求 PIN，首次强认证完成后模块才参与后续锁屏识别。
