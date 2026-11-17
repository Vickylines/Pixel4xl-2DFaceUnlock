# 线条与聚焦

线条与聚焦共用圆角五官。识别完成后，外围线条合成圆环。成功时嘴部线条变成勾，失败时面容轻微侧移、嘴角落下，线条转为暖色。关闭系统动画时直接显示静态状态。

| 样式 | 识别过程 | 结果变化 |
| --- | --- | --- |
| 线条 | 额头和下颌外扩，带高斯柔边的高光沿脸缘转动 | 额头与下颌从两侧接成细圆环 |
| 聚焦 | 外框轻转，高光依次经过四个角 | 对角先后移动，四角拉成弧并合成圆环 |

线条和聚焦沿用编号 4、5。之前选择光幕或星轨的用户会回到面容光环。预览和锁屏共用 `LockscreenStyleRenderer`。

## 参考与取舍

- 面容光环在小尺寸上靠明暗对比读出轮廓。灵动岛把状态变化留在同一位置。本轮对照深浅背景确定尺寸与笔重，把受光的轮廓作为主角。
- [Apple Local Authentication Embedded UI](https://developer.apple.com/documentation/localauthenticationembeddedui/) 强调使用容易识别的生物认证图形，并随认证状态改变图形。本次采用这一表达原则，自行绘制脸部曲线，没有使用 Apple 图标资产。
- [Google Pixel 人脸解锁说明](https://support.google.com/pixelphone/answer/9517039?hl=en) 以摄像头附近的动画或圆环表示正在扫描。本次借鉴小范围、围绕识别主体的反馈方式，让轨道退到人脸周围。
- [Material Icons 指南](https://developers.google.com/fonts/docs/material_icons) 强调小尺寸图形的简洁与可读性。本次检查了其开源 face 图标的轮廓和五官关系，没有引入图标文件或额外依赖。

## 实现

使用 Android Canvas 绘制，柔光纹理一次生成。首帧显示面容，约 160 ms 完成入场。成功过渡 340 ms，失败 300 ms，从当时的扫光位置自然减速。覆盖层移除时取消动画。

普通锁屏收到本轮的识别结果时播放系统的确认或拒绝触感。密码界面的成功反馈交给系统，触感强度跟随设备设置。

扫光从当前速度接到结果过渡，五官、外圈和颜色先后收稳。参考 Apple 的 [Animate with springs](https://developer.apple.com/videos/play/wwdc2023/10158/) 和 [SF Symbols 7 的路径动画](https://developer.apple.com/videos/play/wwdc2025/337/?time=421)。

图标源文件位于 `assets/brand/`。图标由矢量曲线绘制，动效由原生代码绘制，均未使用 AI 生图。
