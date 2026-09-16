# zstd-jni resolves Java classes and fields (including srcPos/dstPos) by name
# from native code. Keeping native method names alone is insufficient: R8 can
# otherwise strip/rename those fields and ART aborts during TimeShape import.
-keep class com.github.luben.zstd.** { *; }

# ESRI loads Wkid tolerance tables relative to its Java package. Relocation
# makes those resources invisible and crashes TimeShape's static initializer.
-keep class com.esri.core.geometry.** { *; }
