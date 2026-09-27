# App classes referenced only from AndroidManifest.xml are kept by AAPT's generated
# rules; OkHttp, coroutines and AndroidX ship their own consumer rules.

# OkHttp's optional TLS providers are not bundled.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# sherpa-onnx (wake word): the native library finds these classes, their fields
# and constructors by name through JNI, so R8 must not rename or drop them.
-keep class com.k2fsa.sherpa.onnx.** { *; }
