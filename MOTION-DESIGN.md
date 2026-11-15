# 四种锁屏人脸动效

四种样式共用圆角五官。识别中的动作集中在脸部周围，成功时嘴部线条连续变成勾，失败时面容轻微侧移并转为暖色。关闭系统动画时直接显示静态状态。

| 样式 | 识别过程 | 结果变化 |
| --- | --- | --- |
| 光幕 | 窄光带经过圆角方形中的面容 | 光带退去，面容连续变成勾 |
| 星轨 | 两段细弧围绕正脸行进 | 弧线合成圆环，中心出现勾 |
| 线条 | 面容轮廓有轻微明暗变化 | 轮廓淡出，嘴部线条转为勾 |
| 聚焦 | 四角框围绕五官轻微呼吸 | 四角框收拢，嘴部线条转为勾 |

光幕、星轨、线条、聚焦对应样式编号 2–5，原点阵选择沿用编号 5。预览和锁屏共用 `LockscreenStyleRenderer`。

## 参考与取舍

- 面容光环在小尺寸上靠明暗对比读出轮廓。灵动岛把状态变化留在同一位置。本轮对照深浅背景确定尺寸与笔重，把受光的轮廓作为主角。
- [Apple Local Authentication Embedded UI](https://developer.apple.com/documentation/localauthenticationembeddedui/) 强调使用容易识别的生物认证图形，并随认证状态改变图形。本次采用这一表达原则，自行绘制脸部曲线，没有使用 Apple 图标资产。
- [Google Pixel 人脸解锁说明](https://support.google.com/pixelphone/answer/9517039?hl=en) 以摄像头附近的动画或圆环表示正在扫描。本次借鉴小范围、围绕识别主体的反馈方式，让轨道退到人脸周围。
- [Material Icons 指南](https://developers.google.com/fonts/docs/material_icons) 强调小尺寸图形的简洁与可读性。本次检查了其开源 face 图标的轮廓和五官关系，没有引入图标文件或额外依赖。

## 实现

使用 Android Canvas、PathMeasure 和缓存的曲线与渐变。新样式在首帧显示面容，约 160 ms 完成入场。成功过渡 340 ms，失败 300 ms。识别流程与绘制分开运行，覆盖层移除时取消动画。

本轮参考 [Apple Motion](https://developer.apple.com/design/human-interface-guidelines/motion) 与 [Designing Fluid Interfaces](https://developer.apple.com/videos/play/wwdc2018/803/)，重点是简短反馈、形状连续和即时响应。

图标源文件位于 `assets/brand/`。图标由矢量曲线绘制，动效由原生代码绘制，均未使用 AI 生图。
