# Gson reflection targets in the shared bitchat sources (persisted state payloads).
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.services.SeenMessageStore$* { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin metadata needed by reflection-based serialization.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Tink references JSR-305 annotations not present on Android.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Strip debug/verbose/info logging from release builds so nicknames, peer IDs,
# fingerprints and file names never reach logcat or bug reports.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
