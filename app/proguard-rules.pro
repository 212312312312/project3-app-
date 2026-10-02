# ===================================================================
# 1. СЕТЕВОЙ СЛОЙ И МОДЕЛИ ДАННЫХ (DTO / GSON / RETROFIT)
# ===================================================================

# Сохраняем все DTO, ответы сервера и сокет-сообщения
-keep class com.taxiapp.client.network.** { *; }
-keep class com.taxiapp.client.network.dto.** { *; }
-keep class com.taxiapp.client.data.model.** { *; }

# Правила для Gson (сериализация/десериализация полей и аннотаций)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepclassmembers enum * { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}
-keep class com.google.gson.** { *; }

# Retrofit & OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ===================================================================
# 2. WEBSOCKET (STOMP) & RXJAVA
# ===================================================================

-keep class ua.naiksoftware.stomp.** { *; }
-dontwarn ua.naiksoftware.stomp.**

-dontwarn io.reactivex.**
-keep class io.reactivex.** { *; }

# ===================================================================
# 3. GOOGLE SERVICES (MAPS, PLACES, LOCATION, FIREBASE)
# ===================================================================

-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }
-keep class com.google.android.libraries.places.** { *; }
-keep class com.google.maps.android.** { *; }
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.firebase.messaging.** { *; }

# ===================================================================
# 4. GLIDE & UI LIBRARIES
# ===================================================================

-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

-keep class com.facebook.shimmer.** { *; }

# ===================================================================
# 5. ДИАГНОСТИКА И СТЕКТРЕЙСЫ В GOOGLE PLAY CONSOLE
# ===================================================================

# Сохраняем номера строк в краш-логах Google Play Console
-keepattributes SourceFile,LineNumberTable