# Kotlinx serialization keeps generated serializers reachable through reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *** descriptor; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Room entities are constructed reflectively by generated code.
-keep class com.expensesplit.app.data.local.entity.** { *; }
-keep class com.expensesplit.app.data.remote.dto.** { *; }

# ML Kit text recognition ships optional model backends.
-dontwarn com.google.mlkit.**
