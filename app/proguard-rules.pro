# ============================================
# IPLINKS Player - ProGuard Rules
# ============================================

# ExoPlayer/Media3 - Keep all native and reflection
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Lifecycle - Keep for lifecycleScope
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class androidx.lifecycle.** { *; }

# LeakCanary - Keep for debug builds (will be stripped in release)
-keep class leakcanary.** { *; }
-dontwarn leakcanary.**

# Keep PlayerActivity for AndroidManifest
-keep public class com.iplinks.player.PlayerActivity { *; }
-keep public class com.iplinks.player.PlayerActivity$* { *; }

# Keep sealed classes and their subclasses
-keep class com.iplinks.player.PlayerActivity$PlayerState { *; }
-keep class com.iplinks.player.PlayerActivity$PlayerState$* { *; }

# Optimization settings
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Aggressive optimizations
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# Remove debug logs in release (shrinks APK)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
