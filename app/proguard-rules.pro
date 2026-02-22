# ============================================
# IPLINKS Player - ProGuard Rules
# ============================================

# ExoPlayer/Media3 - Keep all native and reflection
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep PlayerActivity for AndroidManifest
-keep public class com.iplinks.player.PlayerActivity { *; }

# Optimization settings
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Aggressive optimizations
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# Remove ALL debug logs in release (shrinks APK significantly)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
