# Ponte gomobile (JNI) — não pode ser renomeada/removida
-keep class go.** { *; }
-keep class com.zapfig.engine.** { *; }
-keep interface com.zapfig.engine.** { *; }
# Métodos nativos do encoder WebP
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.zapfig.app.media.WebPAnim { *; }

# yt-dlp para Android (usa reflexão/Jackson internamente)
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
