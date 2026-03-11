# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in build.gradle.kts. You can edit the include path and order by changing the
# proguardFiles directive in build.gradle.kts.

# Keep MNN native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep SDK public API
-keep public class com.mnn.sdk.** {
    public *;
}

# Keep model-related classes
-keep class com.mnn.sdk.model.** { *; }

# Preserve annotation
-keepattributes *Annotation*

# Preserve line number information for debugging
-keepattributes SourceFile,LineNumberTable

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
