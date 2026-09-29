# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keep class com.baidu.** {*;}
-keep class vi.com.** {*;}
-keep class com.baidu.vi.** {*;}
-dontwarn com.baidu.**

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===========================================================================
# 移除 release 构建中的调试日志（零信任原则 8：数据最小化）
#
# 背景：代码中存在直接输出完整经纬度的 android.util.Log.d 调用。
# 这些调用虽然已被 BuildConfig.DEBUG 包裹，但 R8 默认不会消除它们
# （BuildConfig.DEBUG 只有在启用常量折叠且使用 --release 优化时才可能被
# 内联）。因此显式声明这些方法无副作用，使 R8 在 release 构建中删除调用。
#
# 注意：这些规则仅在 release 构建生效（minifyEnabled = true）。
# ===========================================================================
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}

# XLog 同理：避免日志内容（可能含坐标）进入 release 产物
-assumenosideeffects class com.elvishew.xlog.XLog {
    public static *** d(...);
    public static *** i(...);
    public static *** v(...);
    public static *** w(...);
    public static *** e(...);
}