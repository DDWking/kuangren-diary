# Keep serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.ddw.kuangren.**$$serializer { *; }
-keepclassmembers class com.ddw.kuangren.** {
    *** Companion;
}
