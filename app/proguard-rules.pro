# Componentele instanțiate de sistem după nume (manifest).
-keep class ro.e92.launcher.E92Application { *; }
-keep class ro.e92.launcher.media.E92NotificationListener { *; }
-keep class ro.e92.launcher.boot.BootReceiver { *; }

# Custom Views inflate din XML.
-keep class ro.e92.launcher.ui.widget.** { *; }

-dontwarn kotlinx.coroutines.**
