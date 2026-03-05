# commons-compress has optional integrations (ASM/Brotli/Zstd) that are not
# required by this app's runtime path but are referenced from library classes.
-dontoptimize
-dontwarn org.objectweb.asm.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.apache.commons.compress.harmony.pack200.**

# Keep kotlinx.serialization generated serializers stable under shrinking.
-keep class **$$serializer { *; }
-keepclassmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
