# App classes referenced only from AndroidManifest.xml are kept by AAPT's generated
# rules; OkHttp, coroutines and AndroidX ship their own consumer rules.

# OkHttp's optional TLS providers are not bundled.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
