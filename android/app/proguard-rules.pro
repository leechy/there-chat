# Ktor and OkHttp reference optional classes that are not on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Our API models are (de)serialized by kotlinx.serialization.
-keep,includedescriptorclasses class com.therechat.app.core.**$$serializer { *; }
-keepclassmembers class com.therechat.app.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
