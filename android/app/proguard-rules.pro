# Native code entry points (SDL3 JNI) are reflected by name; keep them.
-keepclasseswithmembernames class org.libsdl.app.** { native <methods>; }
-keepclasseswithmembernames class dev.pinyon.shift.** { native <methods>; }
