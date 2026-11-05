package com.oasisfeng.nevo.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;
import de.robv.android.xposed.XposedHelpers;

/** Current-account avatar from verified WeChat 8.0.79 APIs, never a directory/history guess. */
public final class SelfAvatarProvider {
    private static String account;
    private static Person cached;
    private static long refreshed;
    private static String status = "not-loaded";
    private SelfAvatarProvider() {}

    public static synchronized Person current(Context context, String version) {
        if (context == null || !"8.0.79".equals(version)) return fallback();
        try {
            ClassLoader loader = context.getClassLoader();
            String current = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("l41.y1", loader), "u");
            if (current == null || current.isEmpty()) {
                account = null; cached = null; status = "account-not-ready";
                return fallback();
            }
            if (!current.equals(account)) {
                account = current; cached = null; refreshed = 0;
            }
            long now = SystemClock.elapsedRealtime();
            long ttl = cached == null ? 1000 : 60_000;
            if (refreshed != 0 && now - refreshed < ttl) return cached != null ? cached : fallback();
            refreshed = now;
            Class<?> registry = XposedHelpers.findClass("ni5.n0", loader);
            Class<?> feature = XposedHelpers.findClass("zv.c0", loader);
            // Follow the native notification avatar lookup, including feature registration.
            XposedHelpers.callStaticMethod(registry, "c", feature);
            Object result = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.modelavatar.g", loader), "a", current);
            if (!(result instanceof Bitmap) || ((Bitmap) result).isRecycled()) {
                status = "avatar-not-cached";
                return cached != null ? cached : fallback();
            }
            Bitmap source = (Bitmap) result;
            if (source.getConfig() == Bitmap.Config.HARDWARE) source = source.copy(Bitmap.Config.ARGB_8888, false);
            if (source == null) return fallback();
            int width = source.getWidth(), height = source.getHeight();
            if (width < 1 || height < 1) return fallback();
            float scale = Math.min(1f, 128f / Math.max(width, height));
            Bitmap owned = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            new Canvas(owned).drawBitmap(source, null, new Rect(0, 0, owned.getWidth(), owned.getHeight()),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            // The original is owned by WeChat's cache. Never recycle or mutate it.
            cached = new Person.Builder().setName("我").setKey(current)
                    .setIcon(IconCompat.createWithBitmap(owned)).build();
            status = "avatar-ready";
            return cached;
        } catch (Throwable unavailable) {
            // A failed identity read must not retain another account's photo.
            cached = null;
            status = "avatar-unavailable:" + unavailable.getClass().getSimpleName();
            return fallback();
        }
    }

    public static synchronized String status() { return status; }
    private static Person fallback() { return new Person.Builder().setName("我").setKey(account).build(); }
}
