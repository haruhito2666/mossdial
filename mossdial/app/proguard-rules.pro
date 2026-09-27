-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Components the platform instantiates by name: the foreground service, the boot receiver and the
# two widget receivers. AGP already keeps manifest-declared components; these rules keep the
# requirement explicit and survive a manifest that is generated or merged differently.
-keep class com.mossdial.service.ServerService { *; }
-keep class com.mossdial.service.BootReceiver { *; }
-keep class com.mossdial.widget.ServerWidgetActions { *; }

# The widget provider is driven by the system through these callbacks, which are called by name
# rather than from a call site, so they are kept individually.
-keep class com.mossdial.widget.ServerWidget {
    public void onUpdate(android.content.Context, android.appwidget.AppWidgetManager, int[]);
    public void onEnabled(android.content.Context);
    public void onReceive(android.content.Context, android.content.Intent);
}

# The widget fills a RemoteViews layout, so the layout and the string resources it looks up have to
# survive resource shrinking together with the ids they are referenced by.
-keep class com.mossdial.widget.ServerWidget$Companion { *; }

# ONNX Runtime. The AAR ships its own consumer rules for most of this, but the app calls the
# runtime reflectively in a few places the shipped rules do not cover, and the native library is
# reached by name. Being explicit here means a release build cannot lose a class the debug build
# happened to keep, which is the failure mode that only shows up in a minified APK on a device.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keepclassmembers class ai.onnxruntime.** { native <methods>; }

# llama.cpp. The native library looks the Kotlin object up by the JNI name
# com.mossdial.ai.LlamaBridge, so both the class and its external methods have to survive, and the
# external methods must keep their names: renaming one of them breaks the JNI lookup at runtime with
# an UnsatisfiedLinkError that no build step reports.
-keep class com.mossdial.ai.LlamaBridge {
    public *;
    native <methods>;
}
-keepclasseswithmembernames class com.mossdial.ai.LlamaBridge {
    native <methods>;
}

# The native libraries themselves are loaded by name from the APK, which shrinking cannot affect but
# which is worth stating next to the rules above.
-keep class com.mossdial.BuildConfig { *; }
