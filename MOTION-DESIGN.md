# 聚焦

识别时，四角的亮度依次变化，眼睛轻轻眨一下。成功时，笑线先收出勾的短边，长边随着四角合圆向上挑起。失败时，四角略微舒展，再和下落的嘴角一起收稳。关闭系统动画时直接显示静态画面和结果。

聚焦沿用编号 5。之前选择光幕、星轨或线条的用户会回到面容光环。预览和锁屏共用 `LockscreenStyleRenderer`。

## 参考与取舍

- 收尾保留面容与结果的共同笔画，参照 Apple 在 Magic Replace 中让相关符号接续变形的做法。 [SF Symbols 7](https://developer.apple.com/videos/play/wwdc2025/337/)
- 动效在原位完成，并在关闭系统动画时显示静态结果。 [Apple Motion](https://developer.apple.com/design/human-interface-guidelines/motion)

## 实现

外框合拢、嘴形和笔重共用同一段进度。四段弧对齐后由 Android Canvas 统一绘制。首帧显示面容，约 160 ms 完成入场。成功过渡 360 ms，失败 340 ms。覆盖层移除时取消动画。

普通锁屏收到本轮的识别结果时播放系统的确认或拒绝触感。密码界面的成功反馈交给系统，触感强度跟随设备设置。

图标源文件位于 `assets/brand/`。图标由矢量曲线绘制，动效由原生代码绘制，均未使用 AI 生图。
