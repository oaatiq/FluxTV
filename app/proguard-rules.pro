# FluxTV ProGuard rules.
# Keep Media3 / ExoPlayer.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.fluxtv.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fluxtv.app.**$$serializer { *; }
-keepclassmembers class com.fluxtv.app.** {
    *** Companion;
}

# The Shizuku wake helper. Shizuku's own process loads it from this APK by name and creates it by
# reflection (see ShizukuWake.serviceArgs), so nothing in the app's code keeps the class, its
# constructor or its onTransact. Shizuku's library keeps its own BinderContainer.
-keep class com.fluxtv.app.WakeDaemon { *; }

# Class names stay as written, and line numbers in stack traces: this build is sideloaded, with no
# crash reporting to map renamed classes back, and logcat and the Diagnostics history should read
# as they always have. Shrinking and optimization, where the speed comes from, still run.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
