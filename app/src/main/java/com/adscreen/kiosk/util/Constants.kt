package com.adscreen.kiosk.util

object Constants {
    const val PREFS_NAME = "kiosk_prefs"
    const val KEY_IS_FIRST_RUN = "is_first_run"
    const val DEFAULT_URL = "https://www.example.com"
    const val PASSWORD_LENGTH = 6

    // Settings keys
    const val KEY_ORIENTATION = "orientation"
    const val KEY_FREEZE_LAUNCHER = "freeze_launcher"

    const val ROOT_CMD_HIDE_NAV_BAR = "settings put global policy_control immersive.full=*"
    const val ROOT_CMD_HIDE_STATUS_BAR = "settings put global policy_control immersive.status=*"
    const val ROOT_CMD_CLEAR_QS_TILES = "settings put secure sysui_qs_tiles \"\""
    const val ROOT_CMD_DISABLE_HEADS_UP = "settings put global heads_up_notifications_enabled 0"
    const val ROOT_CMD_RESTORE_NAV_BAR = "settings put global policy_control immersive.preconfirms=*"
    const val ROOT_CMD_DISABLE_LAUNCHER = "pm disable-user --user 0 %s"
    const val ROOT_CMD_ENABLE_LAUNCHER = "pm enable %s"
    const val ROOT_CMD_RENICE = "renice -20 %d"
    const val ADMIN_COMPONENT = "com.adscreen.kiosk/.manager.DeviceAdminReceiverImpl"
    const val ROOT_CMD_REMOVE_ACTIVE_ADMIN = "dpm remove-active-admin $ADMIN_COMPONENT"
    const val ROOT_CMD_GRANT_OVERLAY = "appops set %s SYSTEM_ALERT_WINDOW allow"

    // Screen pinning via root (safe — no device-owner, no SystemUI kill)
    const val ROOT_CMD_ENABLE_PINNING = "settings put secure lock_to_app_enabled 1; settings put global lock_task_mode_enabled 1"
    const val ROOT_CMD_SET_ACTIVE_ADMIN = "dpm set-active-admin $ADMIN_COMPONENT"
    const val ROOT_CMD_LOCK_TASK_PACKAGES = "dpm set-lock-task-packages $ADMIN_COMPONENT %s"
    // Fallback: dismiss the "start pinning" dialog by tapping where the button appears
    const val ROOT_CMD_DISMISS_PINNING_DIALOG = "input tap %d %d"

    const val LAUNCHER_PACKAGES = "com.android.launcher3:com.google.android.apps.nexuslauncher:com.sec.android.app.launcher:com.oppo.launcher:com.miui.home:com.oneplus.launcher:com.huawei.android.launcher"
    const val ROOT_CMD_START_LAUNCHER = "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
}
