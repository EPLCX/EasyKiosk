## v1.2.1

### 修复
- **修复 Android 11 定制 ROM 启动崩溃** — `getLaunchedFromUid()` 在某些国产系统框架中被移除导致 `NoSuchMethodError`，已加 try-catch 保护
- **修复 URL 解密失败导致黑屏** — 部分设备 Android Keystore 解密失败时返回乱码，WebView 加载无效地址静默失败，现增加了明文备用存储
- **修复 FLAG_SECURE 导致 WebView 黑屏** — 延迟到页面加载完成后再设置防截屏标志，避免干扰 WebView 表面初始化
- **修复 WebView 布局约束** — ConstraintLayout 中 WebView 缺少完整约束可能导致尺寸为 0
- **添加 SSL/HTTP 错误回调** — 缺失的 `onReceivedSslError` 和 `onReceivedHttpError` 导致错误时页面空白
- **修复构建脚本 APK 路径** — `build.py` 硬编码 `app-debug.apk`，但 ABI 拆分后实际文件名不同，安装的可能是旧版 APK

### 新增
- **注册为系统桌面（启动器）** — 按 Home 键弹出选择提示，选中后开机自动进入 Kiosk 模式（不需要 root）
- **设置页新增卸载按钮** — 自动调用 `clearDeviceOwnerApp()` 清除设备所有者身份，然后打开系统卸载页面（不需要 root）
- **设置按钮移至左下角** — 避免遮挡页面内容

### 其他
- 移除冗余的 WebView stop/blank 流程
- 清理残留的 `policy_control` 系统设置
