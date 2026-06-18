# Root command classes — prevent R8 from stripping reflection access
-keep class com.adscreen.kiosk.manager.RootManager { *; }
-keep class com.topjohnwu.superuser.** { *; }

# WebView JS interface
-keepclassmembers class com.adscreen.kiosk.MainActivity$WebAppInterface {
    <methods>;
}

# Keep annotations for reflection
-keepattributes *Annotation*, InnerClasses
