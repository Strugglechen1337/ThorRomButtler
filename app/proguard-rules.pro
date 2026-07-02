# junrar uses reflection internally for unpack filters
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# Commons Compress: optional compressors we don't ship (brotli, zstd, ...)
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
