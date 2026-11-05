package com.oasisfeng.nevo.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.widget.RemoteViews;
import de.robv.android.xposed.XposedHelpers;

/** Resolve only this installed module's layouts when host package visibility hides it. */
public final class ModuleViews {
    static volatile String modulePath;
    public static volatile boolean freshApplied;
    private ModuleViews() {}
    public static RemoteViews create(Context context, int layout) {
        try { return fresh(new RemoteViews(BuildConfig.APPLICATION_ID, layout)); }
        catch (RuntimeException hidden) {
            String path = modulePath;
            if (path == null) throw hidden;
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(path, 0);
            if (info == null || info.applicationInfo == null || !BuildConfig.APPLICATION_ID.equals(info.packageName)) throw hidden;
            ApplicationInfo application = info.applicationInfo;
            application.sourceDir = path;
            application.publicSourceDir = path;
            return fresh((RemoteViews) XposedHelpers.newInstance(RemoteViews.class, application, layout));
        }
    }

    private static RemoteViews fresh(RemoteViews views) {
        // Replacing this module's small view tree avoids reapplying changed rows into a tree
        // that SystemUI may still be traversing for focus/accessibility during transitions.
        try { XposedHelpers.callMethod(views, "addFlags", 1); freshApplied = true; }
        catch (RuntimeException | LinkageError unavailable) { freshApplied = false; }
        return views;
    }
}
