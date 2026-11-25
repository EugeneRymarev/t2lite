# Keep Gson model fields
-keepclassmembers class ru.myt2lite.data.** { <fields>; }

# --- t2 Lite (R8) ---
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions
# Свой код маленький — держим целиком (Gson-модели, Retrofit-интерфейсы, воркеры WorkManager, Glance).
-keep class ru.myt2lite.** { *; }
# Retrofit + suspend
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Gson
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
# Webim SDK (свои Gson-модели и рефлексия) + SQLCipher (JNI)
-keep class ru.webim.** { *; }
-keep interface ru.webim.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn ru.webim.**
-dontwarn net.sqlcipher.**
# Tink (security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
