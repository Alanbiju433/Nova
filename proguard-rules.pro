-keep class net.zetetic.database.** { *; }
# MediaPipe uses JNI + protobuf reflection
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
-dontwarn org.checkerframework.**
# MediaPipe's logger (Flogger) and usage reporting look classes up by name at runtime.
# Without these, R8 renames them and the app crashes the moment gestures start (v0.2 bug).
-keep class com.google.common.flogger.** { *; }
-keep class com.google.android.datatransport.** { *; }
-keepnames class com.google.common.** { *; }
-dontwarn com.google.common.flogger.**
-dontwarn com.google.android.datatransport.**
# Porcupine wake word (JNI)
-keep class ai.picovoice.** { *; }
