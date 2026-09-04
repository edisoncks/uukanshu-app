# Keep rules are minimal: text-only app, no reflection except Room.
-keep class cc.uukanshu.data.db.** { *; }
-dontwarn org.jsoup.**
# opencc4j loads dictionary data at runtime; never strip or obfuscate it.
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn com.github.houbb.opencc4j.**
