# Vosk - keep JNI classes
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }

# ONNX Runtime - keep JNI classes
-keep class ai.onnxruntime.** { *; }

# Nearby Connections
-keep class com.google.android.gms.nearby.** { *; }
