# Xposed
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keepattributes RuntimeVisibleAnnotations
-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onPackageLoaded(...);
    public void onSystemServerLoaded(...);
}
-keep,allowoptimization,allowobfuscation @io.github.libxposed.api.annotations.* class * {
    @io.github.libxposed.api.annotations.BeforeInvocation <methods>;
    @io.github.libxposed.api.annotations.AfterInvocation <methods>;
}

# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}

# Strip debug log
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Obfuscation
-repackageclasses
-allowaccessmodification

# AMap SDK 可选依赖缺失（运行时按需加载，R8 仅告警）
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath

# AMap SDK 必须整体保留原名：libAMapSDK_MAP_v11_2_100.so 在 GL 线程通过 JNI
# FindClass/GetStaticMethodID 按原始类名反查 Java 类，混淆/裁剪会导致
# "JNI DETECTED ERROR IN APPLICATION: java_class == null" -> SIGABRT（位置/路线页闪退）。
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
