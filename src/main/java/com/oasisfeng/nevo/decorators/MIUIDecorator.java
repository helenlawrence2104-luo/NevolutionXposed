package com.oasisfeng.nevo.decorators;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import de.robv.android.xposed.XposedHelpers;

import com.oasisfeng.nevo.sdk.Decorating;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;
import com.oasisfeng.nevo.xposed.BuildConfig;
import com.oasisfeng.nevo.xposed.R;
import com.oasisfeng.nevo.decorators.media.ColorUtil;

import top.trumeet.common.cache.IconCache;

public class MIUIDecorator extends NevoDecoratorService {
    private static final String NOTIFICATION_ICON = "mipush_notification";
    private static final String NOTIFICATION_SMALL_ICON = "mipush_small_notification";
    private static final String TAG = "MIUIDecorator";

    @Override public SystemUIDecorator createSystemUIDecorator() {
        return new SystemUIDecorator(this.prefKey) {
            @Override public Decorating onNotificationPosted(final StatusBarNotification sbn) {
                try {
                    return decorate(sbn);
                } catch (Throwable failure) {
                    Log.w(TAG, "MIUI enhancement skipped: " + failure.getClass().getSimpleName());
                    return Decorating.Unprocessed;
                }
            }

            private Decorating decorate(final StatusBarNotification sbn) {
                if (sbn == null || !"com.xiaomi.xmsf".equals(sbn.getPackageName())) return Decorating.Unprocessed;
                final Notification original = sbn.getNotification();
                if (original == null || original.extras == null) return Decorating.Unprocessed;
                final String packageName = original.extras.getString("target_package");
                if (packageName == null || packageName.isEmpty()) return Decorating.Unprocessed;
                final Context target = getPackageContext(packageName);
                if (target == null) return Decorating.Unprocessed;

                final Notification updated = original.clone();
                final Bundle extras = updated.extras = original.extras.deepCopy();
                Icon replacement = null;
                int iconId = target.getResources().getIdentifier(NOTIFICATION_SMALL_ICON, "drawable", packageName);
                if (iconId == 0) iconId = target.getResources().getIdentifier(NOTIFICATION_ICON, "drawable", packageName);
                if (iconId != 0) replacement = Icon.createWithResource(packageName, iconId);

                final IconCache cache = IconCache.getInstance();
                final Bitmap raw = cache.getRawIconBitmap(target, packageName);
                if (replacement == null && raw != null && !raw.isRecycled()) {
                    replacement = cache.getIconCache(target, packageName,
                            (ctx, bitmap) -> bitmap != null && !bitmap.isRecycled() ? Icon.createWithBitmap(bitmap) : null);
                }
                if (replacement == null) {
                    final Context module = getPackageContext(BuildConfig.APPLICATION_ID);
                    if (module != null && BuildConfig.APPLICATION_ID.equals(module.getResources().getResourcePackageName(R.drawable.mipush_small_notification)))
                        replacement = Icon.createWithResource(module, R.drawable.mipush_small_notification);
                }
                if (replacement == null) return Decorating.Unprocessed;
                // Reflection affects a detached notification; a changed framework field leaves the original intact.
                XposedHelpers.setObjectField(updated, "mSmallIcon", replacement);
                extras.putBoolean("miui.isGrayscaleIcon", true);
                if (raw != null && !raw.isRecycled())
                    updated.color = cache.getAppColor(target, packageName, (ctx, bitmap) -> ColorUtil.getColor(bitmap)[0]);

                final CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                // Preserve richer native styles and RemoteInput actions when present.
                final String template = extras.getString(Notification.EXTRA_TEMPLATE);
                if (text != null && (template == null || TEMPLATE_BIG_TEXT.equals(template))) {
                    extras.putCharSequence(Notification.EXTRA_TITLE_BIG, extras.getCharSequence(Notification.EXTRA_TITLE));
                    extras.putCharSequence(Notification.EXTRA_BIG_TEXT, text);
                    extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_TEXT);
                }
                setNotification(sbn, updated);
                return Decorating.Processed;
            }
        };
    }
}
