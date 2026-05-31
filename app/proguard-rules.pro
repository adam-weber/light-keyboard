# InputMethodService is referenced from the manifest; keep it.
-keep class app.lightphonekeyboard.** { *; }

# Vosk (voice dictation) calls its native library through JNA, which the native code resolves by
# class/field NAME (e.g. com.sun.jna.Pointer.peer). R8 must not strip or rename JNA or Vosk, or the
# release build throws "UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer".
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class org.vosk.** { *; }
# JNA references java.awt (desktop-only, absent on Android); silence the resulting R8 warnings.
-dontwarn java.awt.**
