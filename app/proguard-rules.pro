# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/mat/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ServiceLoader support
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.android.HandlerContext {
    val handler;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# SimpleXML
-dontwarn org.simpleframework.xml.stream.**
-keep class org.simpleframework.xml.** { *; }
-keep interface org.simpleframework.xml.** { *; }
-keepattributes *Annotation*, EnclosingMethod, InnerClasses, Signature

# App Models (Keep all classes used for serialization/DB)
-keep class com.cixonline.cixreader.models.** { *; }
-keep class com.cixonline.cixreader.api.** { *; }

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# General Kotlin
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
