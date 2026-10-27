# 墨水屏 NFC 刷新工具

Android 应用，通过 Shizuku 或 Root 调用兼容系统的 NXP Q-Poll，再经 Android ISO-DEP 向 **552×768 四色墨水屏**（黑 / 白 / 黄 / 红）推送画面。当前版本 **v3.0**。

## 功能结构

界面顶部固定显示 Shizuku 连接状态、墨水屏检测状态、进度与停止按钮，下方为三个页面：

| 页面 | 内容 |
|---|---|
| **图片** | 选择手机图片或内置测试图，实时四色预览；适配方式（留白 / 填满）、旋转、裁切、黑白二值化与阈值、色块简化、抖动 |
| **日程** | 读取手机日历当月日程，可选择日历来源、月份切换；显示方向（0°/90°/180°/270°，横向自动改用 768×552 版面）、上下左右四边留白、标题/星期/日期/日程四组独立字号、当日样式（红底白字 / 红字加粗 / 不标注 / 黑底白字 / 黄底黑字 / 红框）、周末标注样式、日程标记样式（圆点/方块/横条/数字，居中显示在日期正下方）、日历边框开关、日程条数上限、是否显示时间、是否显示节假日与调休角标；配置改动即时反映在上方预览 |
| **配置** | 授权方式（Shizuku / Root）、Shizuku 授权连接、芯片检查、Q-Poll 能力测试、普通 NFC-A 对照、上电等待、状态查询间隔、运行日志 |

所有预览都由最终发送的 2-bit 位图反解而来，颜色严格限定为四种，屏幕与预览一致。全部配置（图片参数、日程版式、传输参数）保存在本地，重启应用后保持上次设置。

### 日程版式说明

- **显示方向**：0°/90°/180°/270°。选择 90° 或 270° 时版面按 768×552 横向排版后再旋转到 552×768；左右两侧斜装可选择同一横向方向，上下方向同理。
- **边缘留白**：上下左右分别设置，单位为屏幕像素（0–60 px）。
- **字号**：标题、星期、日期、日程文字各自独立，五档（很小/小/标准/大/很大）；全部文字使用粗体，便于墨水屏远距离辨认。
- **日程标记**：圆点、方块、横条、数字（显示当天条数），统一居中显示在日期正下方，可整体关闭。
- **当日样式**：红底白字、红字加粗、黑底白字、黄底黑字、红框、不标注。
- **日历边框**：可在日格之间绘制 2px 分隔线，默认关闭。
- **节假日 / 调休角标**：日格右下角三角标，红色为法定放假，黑色为调休上班日，底部带图例；角标尺寸随日格宽度自适应。数据内置国务院公布的 2025 与 2026 年安排（2026 年依据国办发明电〔2025〕7号），未收录年份不显示角标。

## 使用

1. 开启 NFC，保持手机解锁。启动 Shizuku 后，在“配置”页点击“Shizuku 授权 / 连接”；已 Root 的设备可直接选择 Root。
2. 顶部状态芯片显示 `Shizuku · 已连接` 或 `Root · 已选择` 后即可推送。
3. 图片页选择图片并调整；日程页首次使用需允许“读取日历”，再点“导入 / 刷新当月日程”。
4. 点击推送按钮，核对参数后确认。持续贴合屏幕并保持应用在前台，直到日志提示结束。

仅适用于兼容 Q-Poll 的手机系统及相同原厂配置的目标屏；不能仅凭接口存在或型号名称判定兼容。刷新结果取决于实际供能与屏幕状态。中断可能留下不完整画面，程序不会自动重发刷新指令。

## 代码结构

```
src/org/nfcresearch/qpolllab/
  QPollWorker.java, ShellBridge.java    特权后端（app_process 入口 / Shizuku UserService）
  core/     PanelProtocol, PanelSession, ConnectionRetry, NciDiscovery, ChipInfo, SessionFailure
  image/    ImageRaster, ImageBlocks, PanelImage, CropWindow
  calendar/ CalendarEvent, CalendarMonth, CalendarRepository, CalendarStyle, CalendarRenderer, HolidayTable
  link/     PanelLink（后端绑定、进程生命周期、日志解析、取消）
  settings/ AppSettings（全部持久化项的类型化入口）
  ui/       MainActivity, ImagePage, CalendarPage, SettingsPage, LogPanel, 预览控件
res/layout/ activity_main, page_image, page_calendar, page_settings
```

`core` 与 `image` 不依赖任何界面代码，`calendar` 的月历运算不依赖 Android，可直接离机测试。

## 构建与验证

需要 JDK 17 与 Android SDK（`platforms/android-34` 或更高、`build-tools` 34 或更高）；脚本会自动探测版本，不需要 Gradle。

```sh
# 单元测试（协议、图像量化、裁剪、日历版面）
JAVA_HOME=/path/to/jdk-17 python3 tests/run_java.py

# 构建 APK（Windows / Linux / WSL 均可）
python3 build.py
```

WSL 中若 SDK 位于 Windows 盘（`/mnt/c/...`），脚本会自动使用 Windows 版 `aapt` / `zipalign` 互操作，`d8`、`apksigner` 与 `javac` 仍使用 Linux JDK，无需下载额外组件。SDK 路径可通过 `ANDROID_HOME` 指定。

APK 与 SHA-256 输出在 `dist/`。首次构建会在 `build/` 生成本地调试签名密钥；密钥、构建缓存和 APK 不纳入 Git。自行构建时需要保留自己的密钥，以支持后续覆盖安装。

```sh
adb install -r dist/QPollLab-3.0.apk
```

## 隐私

应用在运行时读取当前设备、标签信息与本地日历（仅读取，不写入），并在应用私有目录保存运行日志。日志可能包含设备型号、标签 UID 和本轮操作参数；分享前请自行检查。应用未申请网络权限。

## 参考逆向项目

- [kur0neko/huawei-epd-pn532](https://gitee.com/kur0neko/huawei-epd-pn532)：原厂图像扫描布局及 D3 传图、D4 刷新流程；PolyForm Noncommercial 1.0.0。
- [solar2ain/ink-nfc-fmsc-by-pn532](https://github.com/solar2ain/ink-nfc-fmsc-by-pn532)：D8/APDU 定义及 LZO1X literal-stream 格式；GPL-3.0-or-later。

具体参考提交、版权声明和许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 协议与许可

保留原厂驱动与扫描配置，无 DB/DA 配置写入。图像为 105984 字节，分 53 个 2000 字节块，经 literal-only LZO 后按 250 字节分包发送 D3（共 476 包），单次 D4 启动刷新，通过 DE 查询状态。

协议与扫描布局所参考代码使用 PolyForm Noncommercial 1.0.0，完整许可证及 Required Notice 保留在 `third-party/` 和 APK assets 中；请遵守其非商业条款。Shizuku 依赖遵循 MIT License。

