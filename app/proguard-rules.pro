# Media3 / Coil ship their own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# FFmpeg renderer is created by reflection in DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
