# EasyKiosk

原生 Android Kiosk 应用，基于 Kotlin 开发。将 Android 设备变成锁定式单应用广告播放器或数字标牌。

## 功能特性

- **全屏 WebView** — 以全屏 Kiosk 模式显示目标网页，无任何浏览器界面元素
- **Root 权限强制锁定** — 利用 Root 权限实现全局沉浸模式、系统 UI 强化和屏幕固定
- **开机自启动** — 设备开机后自动进入 Kiosk 模式
- **密码保护设置** — 通过密码验证进入设置面板，可配置 URL、管理员密码、屏幕方向和 Root 行为
- **密码保护退出** — 输入 6 位管理员密码方可退出 Kiosk 模式
- **网络错误恢复** — 网络断开时显示重试界面，网络恢复后自动重新加载
- **首次启动设置向导** — 引导式初始配置，设置管理员密码和目标网页地址
- **卸载保护** — 需通过密码验证后在设置中主动卸载，防止误操作

## 系统要求

- Android 7.0+ (API 24)
- **必须拥有 Root 权限** — 应用依赖 Root 实现以下功能：
  - 全局沉浸模式（`policy_control`）
  - 系统 UI 强化（状态栏、快捷设置、导航栏）
  - 无需用户确认的屏幕固定
  - 冻结/恢复系统启动器

## 编译

### 前置条件

- JDK 17
- Gradle（可使用项目自带的 `gradle-8.5/` 或系统安装的 Gradle 8.5+）
- Android SDK（参见 `android-sdk/` 目录或设置 `ANDROID_HOME` 环境变量）

### 编译步骤

```bash
# 使用自带的 Gradle
gradle-8.5/bin/gradle assembleRelease

# 或使用编译脚本
python build.py

# 或直接使用 Gradle Wrapper（需先设置）
./gradlew assembleRelease
```

输出文件：`app/build/outputs/apk/release/app-release.apk`

### 设置设备管理员（推荐）

实现无确认对话框的锁屏任务模式：

```bash
adb shell dpm set-device-owner com.adscreen.kiosk/.manager.DeviceAdminReceiverImpl
```

## 使用说明

1. **首次启动** — 设置向导引导您设置 6 位管理员密码和目标网页地址
2. **Kiosk 模式** — 应用锁定为全屏模式，显示已配置的网页
3. **设置页面** — 点击右上角的齿轮图标（半透明），输入管理员密码进入：
   - 修改目标网页地址
   - 修改管理员密码
   - 切换屏幕方向（横屏 / 竖屏 / 自动）
   - 冻结系统启动器开关（Root 权限：冻结系统桌面以防止从桌面打开其他应用）
   - 卸载保护
   - 退出 Kiosk 模式
4. **退出** — 进入设置 → "退出 Kiosk 模式" → 输入管理员密码 → 设备恢复正常模式

## 安全说明

- 管理员密码使用 AES-256 GCM 加密，密钥由 Android KeyStore 保护
- 应用窗口标记了 `FLAG_SECURE`，防止截屏和屏幕录制
- 物理按键（主页、最近任务、菜单）被拦截并屏蔽
- 返回键被禁用
- 系统每 2 秒强制检查一次，自动重新应用沉浸模式标记和锁屏任务模式
- 锁屏/解锁界面可能导致开机后应用无法立即显示，直到设备解锁 —— 这是 Android 平台的限制

## 开源协议

MIT
