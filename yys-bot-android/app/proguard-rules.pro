# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Gson
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.AppGlideModule
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# TemplateConfig & Repository
-keep class com.yys.bot.service.TemplateConfig { *; }
-keep class com.yys.bot.service.TemplateRepository { *; }
-keep class com.yys.bot.service.TemplateMatcher { *; }
-keep class com.yys.bot.service.ConfigManager { *; }

# StateMachine & BotService
-keep class com.yys.bot.service.StateMachine { *; }
-keep class com.yys.bot.service.BotService { *; }
-keep class com.yys.bot.service.ScreenCapture { *; }
-keep class com.yys.bot.service.AccessibilityHelper { *; }

# Activities
-keep class com.yys.bot.TemplateManagerActivity { *; }
-keep class com.yys.bot.RecordPositionActivity { *; }
-keep class com.yys.bot.MainActivity { *; }