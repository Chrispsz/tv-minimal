# ============================================
# IPLINKS Player - ProGuard Rules (AGRESSIVO)
# ============================================

# ==================== MEDIA3 / EXOPLAYER ====================
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ==================== LIFECYCLE ====================
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class androidx.lifecycle.** { *; }

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

# ==================== KOTLIN OPTIMIZATIONS ====================
# Keep Kotlin Metadata for reflection-free operations
-keep class kotlin.Metadata { *; }

# Kotlin inline functions
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.Dispatchers { *; }

# Value classes / Inline classes
-keepclassmembers class **.**$** {
    public <methods>;
}

# ==================== AGRESSIVE OPTIMIZATIONS ====================
-optimizationpasses 7
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Aggressive optimizations - remove dead code
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# ==================== REMOVE DEBUG CODE ====================
# Remove ALL logging in release (zero overhead)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# Remove System.out and System.err
-assumenosideeffects class java.io.PrintStream {
    public *** println(...);
    public *** print(...);
    public *** printf(...);
}

# Remove StringBuilder debug
-assumenosideeffects class java.lang.StringBuilder {
    public *** append(java.lang.String);
    public *** toString();
}

# ==================== REMOVE ASSERTIONS ====================
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static *** checkNotNull(...);
    public static *** checkNotNullParameter(...);
    public static *** checkExpressionValueIsNotNull(...);
    public static *** checkNotNullExpressionValue(...);
    public static *** checkReturnedValueIsNotNull(...);
    public static *** checkFieldIsNotNull(...);
    public static *** checkParameterIsNotNull(...);
}

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
