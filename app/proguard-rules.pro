# Keep serialization metadata for API models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.projectlist.mobile.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.projectlist.mobile.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
