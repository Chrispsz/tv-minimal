# ============================================
# IPLINKS Player - ProGuard Rules (CORRIGIDO)
# ============================================

# ==================== MEDIA3 / EXOPLAYER ====================
# Keep all Media3 classes - essential for playback
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ExoPlayer specific - keep all native and reflection classes
-keep class com.google.android.exoplayer2.** { *; }
-keepclassmembers class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# DataSource factories - needed for HTTP requests
-keep class * extends androidx.media3.datasource.DataSource { *; }
-keep class * implements androidx.media3.datasource.DataSource { *; }
-keep class androidx.media3.datasource.** { *; }
-keepclassmembers class androidx.media3.datasource.** { *; }

# MediaSource factories
-keep class * extends androidx.media3.exoplayer.source.MediaSource { *; }
-keep class androidx.media3.exoplayer.source.** { *; }
-keepclassmembers class androidx.media3.exoplayer.source.** { *; }

# HLS specific
-keep class androidx.media3.exoplayer.hls.** { *; }
-keepclassmembers class androidx.media3.exoplayer.hls.** { *; }

# Decoder renderers
-keep class androidx.media3.exoplayer.** { *; }
-keepclassmembers class androidx.media3.exoplayer.** { *; }

# ==================== LIFECYCLE ====================
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }

# ==================== PLAYER ACTIVITY ====================
-keep public class com.iplinks.player.PlayerActivity { *; }
-keep public class com.iplinks.player.PlayerActivity$* { *; }

# Sealed interfaces e implementações
-keep class com.iplinks.player.PlayerActivity$PlayerState { *; }
-keep class com.iplinks.player.PlayerActivity$PlayerState$* { *; }

# Value classes (inline classes)
-keepclassmembers class com.iplinks.player.PlayerActivity$ValidatedUrl {
    public java.lang.String getUrl();
}
-keepclassmembers class com.iplinks.player.PlayerActivity$RetryCount {
    public int getValue();
}

# ==================== KOTLIN ====================
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }

# ==================== ANDROID COMPONENTS ====================
-keep class android.content.Intent { *; }
-keep class android.net.Uri { *; }
-keep class android.os.Bundle { *; }
-keep class android.view.SurfaceView { *; }

# ==================== NATIVE METHODS ====================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== ENUMS ====================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ==================== PARCELABLE ====================
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ==================== REMOVE LOGS ONLY ====================
# Remove only debug logs, keep everything else
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# ==================== OPTIMIZATIONS (LESS AGGRESSIVE) ====================
-optimizationpasses 3
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses

# Only safe optimizations
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# ==================== CRITICAL: DO NOT REMOVE ====================
# These are needed for ExoPlayer to work correctly
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
