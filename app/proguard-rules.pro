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
