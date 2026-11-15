# 校园网助手 1.2.0：界面与交互设计说明

界面以 Google Material 3 的色彩角色、字体层级、布局、圆角与交互反馈为依据，用 Android 原生控件和本地 Drawable 实现。没有引入 Material Components 或 Compose 依赖；这是一套按规范定制的原生样式，并非 Google 官方应用。

## 页面与交互

- 首页：三步准备、近期检测结果、当前任务、主要操作、自动计划与网络详情。成功结果标为“上次联网验证成功”，检测时间与当前任务分开；失败提供与原因相应的处理入口。
- 设置：宿舍 Wi-Fi、校园网账号、计划、运行准备和高级配置。“保存设置”和“保存并测试”固定在底部。状态刷新保留正在编辑的输入；验证失败保留输入并显示持续提示；保存成功后清空并隐藏密码。
- 记录：统一的时间标签和记录卡片、导出/清除入口、无记录时的空状态。
- 导航：底部三个目的地，图标与文字并列成组，选中项使用胶囊指示器和无障碍状态说明。
- 表单：描边输入框、下拉菜单、时间选择、星期圆形选择器；密码显隐按钮嵌入输入框右侧。
- 运行状态：文字与颜色共同表达状态，任务运行时显示进度条，可停止当前任务。

1.2.0 保留原生控件、主题、图标与导航，重点调整任务信息和反馈。新增的 07:00—07:05 快速填写需要手动保存，不改变升级前的设置。已配置且有成功记录后收起首次引导。没有添加持续后台监控。

## 视觉参数

| 角色 | 浅色 | 深色 |
| --- | --- | --- |
| Surface | `#F8FAFD` | `#111318` |
| Surface container low | `#F0F4F9` | `#191C20` |
| Surface container | `#E9EEF6` | `#20242A` |
| Primary | `#0B57D0` | `#A8C7FA` |
| Primary container | `#D3E3FD` | `#0842A0` |
| On surface | `#1F1F1F` | `#E3E3E3` |

使用固定的蓝色主题并跟随系统日夜模式；没有声称实现基于壁纸取色的 Dynamic Color。文字使用系统 sans-serif/sans-serif-medium，中文使用系统字体回退。页面标题 28 sp、状态标题 26 sp、主时段 32 sp、正文 14–16 sp、辅助文字与导航标签 12 sp。

卡片圆角 24 dp，按钮与导航指示器为胶囊形，描边输入框圆角 4 dp。页面边距 20 dp，卡片内边距 20 dp；主操作、星期与图标按钮触摸高度/宽度至少 48 dp。窄屏的七个星期选项可横向滚动。字体比例达到 1.3 时，并排的操作按钮改为纵向排列，避免文字挤压；时间按钮使用单行自适应字号。宽屏正文居中，最大宽度 640 dp。按钮支持原生 ripple 状态层，导航使用短淡入过渡。

## 图标与资产

图标通过内置图像生成工具制作。源图保持透明，使用蓝、绿、黄、红构成原创 Wi-Fi 图形。项目路径：`assets/design/campus-icon.png`。APK 引用 `res/drawable-nodpi/brand_mark.png` 与 `ic_launcher_foreground.png`；前景采用等比例缩放与透明留白，不改变图形设计。自适应图标背景为白色，Android 13 及以上提供独立单色图层。通知仍使用适合系统状态栏的单色图标。

最终生成提示词：

> Use case: logo-brand. Asset type: production Android adaptive launcher icon foreground for a Chinese campus Wi-Fi sign-in app. Create ONE original, beautifully balanced Google Workspace / Material Design inspired colorful geometric Wi-Fi mark. The subject is a clear Wi-Fi signal made of two broad concentric rounded arches and a circular dot below. Use Google-like blue #4285F4 as the main arch, green #34A853 and yellow #FBBC05 as clean joined segments of the inner arch, and red #EA4335 as the circular signal dot. Precise concentric geometry, generous clear spaces, consistent broad ribbon thickness, gently rounded ends, flat vector-like graphic, optical balance, approachable and extremely recognizable at small sizes. Mark centered and occupies only the central 58 percent of the square canvas so it stays inside Android adaptive icon safe zone. Genuinely transparent background; no white square, no badge, no pre-rounded outer container. No text, no letters, no Google G logo, no official Google logos, no watermark, no school crest, no 3D, no shadows, no gradients, no texture, no mockup. Only one icon. This image will be used in a real Android app.

## 参考规范

- [Google Material Design 3](https://m3.material.io/)
- [Color roles](https://m3.material.io/styles/color/roles)
- [Navigation bar](https://m3.material.io/components/navigation-bar/overview)
- [Cards](https://m3.material.io/components/cards/specs)
- [Android 触摸目标与无障碍](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)

Android 原生界面渲染预览与验证结果以 `docs/VALIDATION.md` 为准。宿舍网络、VPN 和隔夜运行需要手机实测。
