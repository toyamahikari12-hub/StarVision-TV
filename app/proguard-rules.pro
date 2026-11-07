# ============================================================
# ProGuard/R8 Rules - StarVision TV (versi kecil & ringan)
# Library modern (androidx, media3, okhttp, glide, lottie) sudah membawa
# consumer rules sendiri, jadi TIDAK perlu di-keep semuanya lagi.
# ============================================================

# Kode aplikasi sendiri kecil -> aman di-keep penuh (model Gson, binding, dll.)
-keep class com.animatv.player.** { *; }

# Gson: butuh info generic & anotasi
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-dontwarn sun.misc.**
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Decoder FFmpeg (JNI) -> nama class/method native tidak boleh diubah
-keep class org.jellyfin.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Conscrypt / OkHttp: komponen opsional yang tidak ada di Android
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# Glide (modul hasil kapt)
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }

# FilePicker (library JitPack, pakai reflection untuk dialog)
-keep class com.developer.filepicker.** { *; }

# MultiDex - PENTING untuk Android 5
-keep class androidx.multidex.** { *; }

# Komponen Android yang dipanggil lewat manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.fragment.app.Fragment

# Resource id yang dicari lewat nama (AdminActivity pakai getIdentifier)
-keepclassmembers class **.R$* { public static <fields>; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn java.lang.invoke.**
-dontwarn **$$Lambda$*
-dontwarn kotlin.**
-dontwarn kotlinx.**
