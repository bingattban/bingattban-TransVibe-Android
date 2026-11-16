# Keep Java/Kotlin native method names stable for RegisterNatives/JNI lookup.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep Room database metadata used through generated implementations.
-keep class * extends androidx.room.RoomDatabase { *; }

# Optional local FFmpegKit is invoked by reflection; R8 must retain its methods.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# sherpa-onnx loads native JNI functions by their Java/Kotlin binding names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Firebase ComponentDiscovery creates ML Kit registrars by reflection from
# AndroidManifest <meta-data>. R8 full mode may retain the class while removing
# its no-argument constructor, causing RemoteModelManager.getInstance() to
# crash with NullPointerException on Release startup (Debug remains unaffected).
# Keep only the necessary reflective constructors; preserve R8 elsewhere.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
