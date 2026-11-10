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

-keep class com.mossdial.BuildConfig { *; }
