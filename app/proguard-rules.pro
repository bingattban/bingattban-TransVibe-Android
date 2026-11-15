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
