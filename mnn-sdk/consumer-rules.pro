# Consumer ProGuard rules for library users
-keep class com.mnn.sdk.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
