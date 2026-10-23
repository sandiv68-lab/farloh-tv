# Media3 / Coil ship their own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# FFmpeg renderer is created by reflection in DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# OpenVPN for Android remote-control interface (AIDL + parcelable).
-keep class de.blinkt.openvpn.api.** { *; }
