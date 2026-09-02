# Keep data model fields for Gson reflection-based (de)serialization.
-keepclassmembers class com.dailytools.calculator.data.network.** { *; }
-keepclassmembers class com.dailytools.calculator.data.model.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
