# 0.10.1 锁屏修复与实测记录

日期：2026-10-11。设备：Pixel 4 XL（coral）、LineageOS 23.2、Android 16 / API 36、LSPosed 2.0.1（7639）。正式包 `com.yusd.pixel2dface`，versionCode 25，versionName 0.10.1。

## 原因与修复

原 0.10.0 候选包在应用内可以识别，锁屏冷启动却可能在约 80 ms 内以 `activity_stopped` 退出；首帧、人脸和匹配时间均为 -1。模型异步初始化结束之后才设置锁屏宿主窗口，导致普通 Activity 先被 Keyguard 停止。热启动偶尔成功，不能证明该竞态不存在。

0.10.1 在 Manifest 声明 `showWhenLocked`，在异步初始化前准备透明的 `1×1` 窗口。相机、宿主就绪通知和屏幕唤醒仍须先消费私有启动授权，复核配置、锁屏和电源状态；无授权启动不能取得相机。生命周期退出继续清除遮挡和常亮标志。[Android Activity 文档](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean))说明了该标记对锁屏时 Activity 保持 resumed 的作用。

现场还发现 LSPosed 中正式包关闭、验证包启用。验证包原本不执行锁屏钩子，却残留模块发现元数据和入口资源；此前已加载的 SystemUI 进程可能继续保留旧钩子，重启后就不再加载。维护前备份 LSPosed 数据库，恢复正式包启用及其 SystemUI 作用域，关闭验证包；其他模块与作用域保持原值。重新安装刷新模块 APK 路径，再重启 SystemUI，确认正式钩子加载。

新版验证包移除全部 Xposed 模块元数据及 `xposed_init` 资源。Xposed 入口仅供正式 debug/release 变体，验证包仍可独立录入和测试，不再作为 LSPosed 模块被发现。

## 数据与升级

- 安装前保留正式/验证 APK、私有配置和数据库快照；备份只存本地，不纳入源码或 Release。
- 同签名覆盖安装两个包，已有正式包 v4 模板与设置在安装前后逐字节一致，无需重新录入。验证包数据独立保留。
- v3 继续走旧后端，v4 继续走 MobileFaceNet；本次未修改模型、身份阈值、睁眼/多帧门槛或系统弱认证策略。
- 模型 SHA-256：`d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b`。
- 正式包证书 SHA-256：`769bae7364e73ad5c73835c7fbce30994ea4e9ff9efd32fde51404886d5ad7da`。GitHub Actions 构建证书不同，覆盖安装应使用 Release 资产。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| JVM 测试 | 154 项通过，0 失败、错误、跳过 |
| Release 构建与 Lint | 通过；0 错误、7 警告 |
| 正式包设备回归 | 329 次断言通过，含模块元数据/入口检查、隔离存储、原子恢复及原生模型推理 |
| 验证包设备回归 | 329 次断言通过，确认其不含模块发现元数据和入口 |
| 三轮强停后冷启动 | 每轮均先准备窗口、完成受授权附加、取得首帧；无提前 `activity_stopped` |
| 熄屏取消 | 三轮均结束采集，相机客户端清空 |
| 无脸超时与恢复 | 12 秒截止后保持锁定，退出遮挡；下一次亮屏重新取得首帧 |
| 相机权限撤销与恢复 | 实际撤销 CAMERA 后立即返回未授予权限，无相机帧且锁屏无遮挡；恢复授权后再次识别成功并确认退出锁屏 |
| 非 SystemUI Provider 调用 | shell 调用被拒绝：`Caller is not SystemUI` |
| 伪造未知授权 | 返回未获授权，未附加受授权宿主，未启动相机 |
| 真人锁屏与上滑 | 用户确认能进入桌面；新版本 SystemUI 日志确认完整认证和退出 |

三轮冷启动的相机启动至首帧分别为 459 / 370 / 385 ms；这是相机首帧时间，不是完整解锁延迟。每个包 30 次合成输入推理中位数 38 ms、P95 39 ms；该基准不能推断真人识别率。

12:35:56–12:35:59 的新版本现场成功链路为：

```text
Capture completed mode=unlock path=normal totalMs=1091
result_received generation=32 success=true
result_recheck_passed generation=32
delivery_completed generation=32 nativeCanSkip=true
keyguard_exit_check generation=32 deviceLocked=false, keyguardLocked=false, confirmed=true
```

该轮模型初始化 44 ms、相机首帧 300 ms，页面启动至识别成功 1091 ms。用户随后确认上滑可进入桌面；退出后相机无活动客户端。日志不含原始图像或人脸特征，完整设备日志仅留本地。

相机权限回归在成功测试后独立进行：撤销权限后的两次宿主分别在 6 / 2 ms 返回“未授予相机权限”，均无受授权附加和首帧；恢复权限后记录另一轮识别成功（1032 ms）及完整 SystemUI 退出。最终检查确认 CAMERA 已恢复授权、正式模块启用、验证模块关闭且相机客户端清空。最初的 app-op ignore 检查仍可收到无脸帧，仅计作超时回归，不作为实际权限拒绝的证据。

## 发行文件与边界

本次发行的安装包与已覆盖安装、执行设备回归的 APK 为同一文件；源码中后续仅补充文档。正式模块与验证包的 SHA-256 见 [校验文件](dist/SHA256SUMS-0.10.1.txt)，使用步骤见 [中文安装说明](dist/INSTALL-ZH-0.10.1.txt)。

以上为单机、单录入用户结果。本次没有完成新后端的异人拒绝统计、低光/眼镜、照片/视频防伪、PIN 界面或跨 ROM 复测。RGB 2D 人脸仅适合便利解锁；不获得 Android 强生物识别等级，不用于支付或应用生物认证。Android 10–15 仍只有源码适配。
