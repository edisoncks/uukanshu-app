# Keep rules are minimal: text-only app, no reflection except Room + WorkManager.
-keep class cc.uukanshu.data.db.** { *; }
# WorkManager instantiates Workers via reflection (Class.forName on the
# enqueued name); R8 must not rename/strip them or release background
# checks silently never run while debug works.
-keep class cc.uukanshu.data.updatecheck.BookUpdateWorker { *; }
-dontwarn org.jsoup.**
# opencc4j loads dictionary data at runtime; never strip or obfuscate it.
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn com.github.houbb.opencc4j.**
