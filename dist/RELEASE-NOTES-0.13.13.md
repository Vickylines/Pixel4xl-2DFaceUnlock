# 2D人脸解锁 0.13.13

相机开始检测下一帧时，上一帧可以继续进行身份比对。验证按采集顺序完成，沿用已有的质量、身份与多帧确认规则。

锁屏的光幕、星轨、线条和点阵已更新。点阵用细线画出五官，光点沿两侧亮起，成功时向中间聚拢，失败时向两侧散开。

发布包与 Pixel 4 XL 上覆盖安装的 APK 字节一致。应用内版本为 `0.13.13-motion`，版本号 43。覆盖安装保留已有面容与设置，重启后可在「连接与关于」确认锁屏运行版本。

267 项 JVM 回归通过，Release 构建通过，Lint 无错误。Android 15 模拟器已检查样式保存、结果反馈和关闭动画后的静态显示。Pixel 4 XL / Android 17 真机已确认覆盖升级后保留录入，并由 SystemUI 收到识别成功结果。

[下载正式 APK](https://github.com/Vickylines/Pixel4xl-2DFaceUnlock/releases/download/v0.13.13/Pixel2DFaceUnlock-universal-0.13.13-motion.apk) · [安装说明](INSTALL-ZH-0.13.13.txt) · [SHA-256](SHA256SUMS-0.13.13.txt)
