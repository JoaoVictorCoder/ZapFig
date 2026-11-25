# Ponte gomobile (JNI) — não pode ser renomeada/removida
-keep class go.** { *; }
-keep class com.zapfig.engine.** { *; }
-keep interface com.zapfig.engine.** { *; }
# Métodos nativos do encoder WebP
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.zapfig.app.media.WebPAnim { *; }
