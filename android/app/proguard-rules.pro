# Unscroll release shrinker rules. Keep names that reflection, manifests, or
# on-device interchange still need. Do not over-keep.

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# WorkManager workers are constructed by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Glance / app widgets
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# Accessibility, notification listener, tiles, and receivers are referenced
# from the manifest; keep them and their no-arg constructors explicitly.
-keep class app.unscroll.service.** { *; }
-keep class app.unscroll.widget.** { *; }
-keep class app.unscroll.UnscrollApp { *; }
-keep class app.unscroll.MainActivity { *; }
-keep class app.unscroll.ui.block.BlockActivity { *; }

# ZXing barcode decoder (QR pairing, on device)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exception
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
