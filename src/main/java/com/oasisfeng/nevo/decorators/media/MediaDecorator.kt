package com.oasisfeng.nevo.decorators.media

import android.app.Notification
import android.app.Notification.Action
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.widget.RemoteViews

import com.oasisfeng.nevo.sdk.Decorating
import com.oasisfeng.nevo.sdk.NevoDecoratorService
import com.oasisfeng.nevo.xposed.BuildConfig
import com.oasisfeng.nevo.xposed.R

/** Optional media appearance enhancement. Unsupported notifications retain their native views. */
class MediaDecorator : NevoDecoratorService() {
    override fun createSystemUIDecorator(): NevoDecoratorService.SystemUIDecorator {
        // No hooks into java.lang.reflect.Method: the views below use only public RemoteViews operations.
        return object : NevoDecoratorService.SystemUIDecorator(this.prefKey) {
            override fun onNotificationPosted(sbn: StatusBarNotification): Decorating {
                if (Build.VERSION.SDK_INT < 28) return Decorating.Unprocessed
                return try {
                    decorate(sbn)
                } catch (failure: Throwable) {
                    // SystemUI must keep delivering the original notification if any optional step fails.
                    Log.w(TAG, "Media enhancement skipped: " + failure.javaClass.simpleName)
                    Decorating.Unprocessed
                }
            }

            private fun decorate(sbn: StatusBarNotification): Decorating {
                val original = sbn.notification ?: return Decorating.Unprocessed
                val originalExtras = original.extras ?: return Decorating.Unprocessed
                if (!originalExtras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                    && originalExtras.getString(Notification.EXTRA_TEMPLATE) != TEMPLATE_MEDIA) {
                    return Decorating.Unprocessed
                }
                val smallIcon = original.smallIcon ?: return Decorating.Unprocessed
                val largeIcon = original.getLargeIcon() ?: return Decorating.Unprocessed
                // Never resolve module R IDs through another package's Resources fallback.
                val context = getPackageContext(BuildConfig.APPLICATION_ID) ?: return Decorating.Unprocessed
                if (context.resources.getResourcePackageName(R.layout.media_notifition_layout) != BuildConfig.APPLICATION_ID) {
                    return Decorating.Unprocessed
                }
                val colors = paletteForIcon(largeIcon, context) ?: return Decorating.Unprocessed
                val backgroundColor = colors[0]
                val textColor = colors[1]
                val pm = context.packageManager
                val appLabel = pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
                val styled = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                val selectableBackground = try { styled.getResourceId(0, 0) } finally { styled.recycle() }

                // Fresh RemoteViews on each event prevent accumulating stale reflection actions.
                val compact = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.media_notifition_layout)
                val expanded = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.media_notifition_layout_big)
                fun bindBase(view: RemoteViews) {
                    view.setTextViewText(R.id.appName, appLabel)
                    view.setTextViewText(R.id.title, originalExtras.getCharSequence(Notification.EXTRA_TITLE))
                    view.setTextViewText(R.id.subtitle, originalExtras.getCharSequence(Notification.EXTRA_TEXT))
                    view.setImageViewIcon(R.id.smallIcon, smallIcon)
                    view.setImageViewIcon(R.id.largeIcon, largeIcon)
                    for (id in intArrayOf(R.id.appName, R.id.title, R.id.subtitle)) view.setTextColor(id, textColor)
                    view.setInt(R.id.smallIcon, "setColorFilter", textColor)
                    view.setInt(R.id.foregroundImage, "setColorFilter", backgroundColor)
                    view.setInt(R.id.background, "setBackgroundColor", backgroundColor)
                }
                fun bindAction(view: RemoteViews, id: Int, action: Action?) {
                    view.setViewVisibility(id, View.GONE)
                    val icon = action?.getIcon() ?: return
                    val intent = action.actionIntent ?: return
                    // Preserve the Icon's actual kind/package instead of assuming every icon is a resource ID.
                    view.setImageViewIcon(id, icon)
                    view.setOnClickPendingIntent(id, intent)
                    view.setContentDescription(id, action.title)
                    view.setInt(id, "setColorFilter", textColor)
                    if (selectableBackground != 0) view.setInt(id, "setBackgroundResource", selectableBackground)
                    view.setViewVisibility(id, View.VISIBLE)
                }
                bindBase(compact)
                bindBase(expanded)
                val actions = original.actions ?: emptyArray()
                val compactIndices = originalExtras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS) ?: intArrayOf()
                for ((position, id) in intArrayOf(R.id.ic_0, R.id.ic_1, R.id.ic_2).withIndex()) {
                    val index = compactIndices.getOrNull(position)
                    bindAction(compact, id, index?.let { actions.getOrNull(it) })
                }
                for ((position, id) in intArrayOf(R.id.ic_0, R.id.ic_1, R.id.ic_2, R.id.ic_3, R.id.ic_4).withIndex()) {
                    bindAction(expanded, id, actions.getOrNull(position))
                }
                // Commit only after both complete view trees were prepared successfully.
                val updated = original.clone()
                updated.extras = originalExtras.deepCopy()
                updated.contentView = NevoDecoratorService.LocalDecorator.overrideContentView(updated, compact)
                updated.bigContentView = NevoDecoratorService.LocalDecorator.overrideBigContentView(updated, expanded)
                NevoDecoratorService.SystemUIDecorator.setNotification(sbn, updated)
                return Decorating.Processed
            }
        }
    }

    companion object {
        private const val TAG = "MediaDecorator"

        private fun paletteForIcon(icon: Icon, context: android.content.Context): IntArray? {
            // Bounded software rendering also handles vectors and avoids Palette reading a hardware bitmap.
            val drawable = icon.loadDrawable(context) ?: return null
            val sample = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            return try {
                drawable.setBounds(0, 0, sample.width, sample.height)
                drawable.draw(Canvas(sample))
                ColorUtil.getColor(sample)
            } finally {
                sample.recycle()
            }
        }
    }
}
