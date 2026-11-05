package com.oasisfeng.nevo.sdk;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.LruCache;
import android.widget.RemoteViews;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.oasisfeng.nevo.xposed.BuildConfig;
import com.oasisfeng.nevo.xposed.R;

public abstract class NevoDecoratorService {
	private static final int MAX_NUM_ARCHIVED = 20;

	public static final String EXTRAS_BIG_CONTENT_VIEW_OVERRIDE = "nevo.bigContentView";
	public static final String EXTRAS_CONTENT_VIEW_OVERRIDE = "nevo.contentView";
	/** Valid constant values for {@link android.app.Notification#EXTRA_TEMPLATE} */
	public static final String TEMPLATE_BIG_TEXT	= "android.app.Notification$BigTextStyle";
	public static final String TEMPLATE_BIG_PICTURE	= "android.app.Notification$BigPictureStyle";
	public static final String TEMPLATE_CUSTOM		= "android.app.Notification$DecoratedCustomViewStyle";
	public static final String TEMPLATE_INBOX		= "android.app.Notification$InboxStyle";
	public static final String TEMPLATE_MEDIA		= "android.app.Notification$MediaStyle";
	public static final String TEMPLATE_MESSAGING	= "android.app.Notification$MessagingStyle";

	private static final String TAG = "NevoDecoratorService";

	/** Detach mutable extras/history arrays from both producer and cache readers. */
	private static Notification copyNotification(Notification source) {
		final Notification copy = source.clone();
		copy.extras = copyExtras(source.extras);
		return copy;
	}

	private static Bundle copyExtras(@Nullable Bundle source) {
		if (source == null) return new Bundle();
		final Bundle copy = source.deepCopy();
		// Bundle.deepCopy() does not promise to clone arbitrary Parcelable contents.
		// MessagingStyle stores mutable Bundle objects inside Parcelable arrays.
		for (String key : source.keySet()) {
			final Object value = source.get(key);
			if (value instanceof Bundle) copy.putBundle(key, copyExtras((Bundle) value));
			else if (value instanceof android.os.Parcelable[]) {
				final android.os.Parcelable[] array = ((android.os.Parcelable[]) value).clone();
				for (int i = 0; i < array.length; i++)
					if (array[i] instanceof Bundle) array[i] = copyExtras((Bundle) array[i]);
				copy.putParcelableArray(key, array);
			}
		}
		return copy;
	}

	private static StatusBarNotification copyStatusBarNotification(StatusBarNotification source) {
		final StatusBarNotification copy = source.clone();
		final Notification original = source.getNotification();
		copy.getNotification().extras = copyExtras(original.extras);
		return copy;
	}

	private static volatile Context appContext, packageContext;

	public static Context getAppContext() {
		if (appContext == null) {
			try {
				final android.app.Application app = android.app.AndroidAppHelper.currentApplication();
				if (app != null) appContext = app.getApplicationContext();
			} catch (final Exception ignored) {}
		}
		return appContext;
	}

	public static void setAppContext(Context context) {
		appContext = context;
	}

	protected static Context getPackageContext() {
		if (packageContext == null)
			packageContext = getPackageContext(BuildConfig.APPLICATION_ID);
		// Android 11+ package visibility may prevent an injected module from
		// creating its own package context from the target application's UID.
		// A null context used to abort the whole WeChat decorator during startup.
		return packageContext != null ? packageContext : getAppContext();
	}

	protected static Context getPackageContext(String packageName) {
		final Context context = getAppContext();
		if (context == null) return null;
		try {
			return context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
		} catch (PackageManager.NameNotFoundException | SecurityException ignored) { return null; }
	}

	protected static PackageManager getPackageManager() {
		return getAppContext().getPackageManager();
	}

	protected static String getString(int key) {
		final Context module = packageContext != null ? packageContext : getPackageContext(BuildConfig.APPLICATION_ID);
		if (module != null) {
			try { return module.getString(key); }
			catch (RuntimeException ignored) { /* Use safe built-in defaults below. */ }
		}
		// These strings are needed inside WeChat before module resources are
		// necessarily visible. Keep notification enhancement functional even
		// when createPackageContext() is filtered by package visibility.
		if (key == R.string.channel_group_message) return "新群组消息通知";
		if (key == R.string.channel_message) return "新消息通知";
		if (key == R.string.channel_misc) return "其它通知";
		if (key == R.string.action_reply) return "回复";
		if (key == R.string.action_zoom) return "缩放";
		if (key == R.string.self_display_name) return "我";
		return "";
	}

	/**
	 * 在应用进程中执行的通知预处理，某些功能（NotificationChannel等）在此实现。
	 */
	public static class LocalDecorator {
		private static volatile NotificationManager mNM;

		public static void setNM(NotificationManager nm) {
			mNM = nm;
		}

		private static final int MAX_CACHE_ENTRIES = 50;
		private static final class Archive {
			final String tag;
			final LinkedList<Notification> notifications = new LinkedList<>();
			Archive(String tag) { this.tag = tag; }
		}
		// All list access is covered by the same lock, including compound get/put.
		private static final LruCache<Integer, Archive> cache = new LruCache<>(MAX_CACHE_ENTRIES);

		protected static void cache(final int id, final Notification n) { cache(id, null, n); }

		protected static void cache(final int id, @Nullable final String tag, final Notification n) {
			if (n == null) return;
			final Notification snapshot = copyNotification(n);
			synchronized (cache) {
				Archive archive = cache.get(id);
				// An ID reused under a different tag must not inherit another conversation's history.
				if (archive == null || !java.util.Objects.equals(archive.tag, tag)) {
					archive = new Archive(tag);
					cache.put(id, archive);
				}
				archive.notifications.add(snapshot);
				while (archive.notifications.size() > MAX_NUM_ARCHIVED) archive.notifications.removeFirst();
			}
		}

		/** Forget an ID reused for a different conversation before caching it. */
		protected static void removeArchivedNotification(final int id) {
			synchronized (cache) { cache.remove(id); }
		}

		protected static List<Notification> getArchivedNotifications(int key) {
			synchronized (cache) {
				final Archive archive = cache.get(key);
				final List<Notification> result = new ArrayList<>();
				if (archive != null) for (Notification n : archive.notifications) result.add(copyNotification(n));
				return result;
			}
		}

		@Nullable protected static Notification getArchivedNotification(int key) {
			synchronized (cache) {
				final Archive archive = cache.get(key);
				return archive == null || archive.notifications.isEmpty() ? null : copyNotification(archive.notifications.getLast());
			}
		}

		protected static boolean hasArchivedNotifications(int key) {
			synchronized (cache) {
				final Archive archive = cache.get(key);
				return archive != null && !archive.notifications.isEmpty();
			}
		}

		public static RemoteViews overrideBigContentView(Notification n, RemoteViews remoteViews) {
			n.extras.putParcelable(EXTRAS_BIG_CONTENT_VIEW_OVERRIDE, remoteViews);
			return remoteViews;
		}
	
		public static RemoteViews overridedBigContentView(Notification n) {
			return n.extras.getParcelable(EXTRAS_BIG_CONTENT_VIEW_OVERRIDE);
		}
	
		public static RemoteViews overrideContentView(Notification n, RemoteViews remoteViews) {
			n.extras.putParcelable(EXTRAS_CONTENT_VIEW_OVERRIDE, remoteViews);
			return remoteViews;
		}
	
		public static RemoteViews overridedContentView(Notification n) {
			return (RemoteViews)n.extras.getParcelable(EXTRAS_CONTENT_VIEW_OVERRIDE);
		}
	
		public static void setChannelId(Notification n, String channelId) {
			XposedHelpers.setObjectField(n, "mChannelId", channelId);
		}
	
		public static void setGroup(Notification n, String groupKey) {
			XposedHelpers.setObjectField(n, "mGroupKey", groupKey);
		}
	
		public static void setGroupAlertBehavior(Notification n, int behavior) {
			XposedHelpers.setIntField(n, "mGroupAlertBehavior", behavior);
		}
	
		public static void setSortKey(Notification n, String sortKey) {
			XposedHelpers.setObjectField(n, "mSortKey", sortKey);
		}
	
		public static void setActions(Notification n, Action... actions) {
			try {
				java.lang.reflect.Field field = Notification.class.getDeclaredField("actions");
				field.setAccessible(true);
				field.set(n, actions);
			} catch (Exception e) {
				XposedHelpers.setObjectField(n, "actions", actions);
			}
		}
	
		protected final String prefKey;
		private boolean disabled;

		protected LocalDecorator(String prefKey) { this.prefKey = prefKey; }

		public boolean isDisabled() { return disabled; }
		public void setDisabled(boolean disabled) { this.disabled = disabled; }
	
		@Keep public void onCreate(SharedPreferences pref) {
			this.disabled = !pref.getBoolean(prefKey  + ".enabled", true);
			if (BuildConfig.DEBUG) Log.d(TAG, prefKey + ".disabled " + this.disabled);
		}
	
		@Keep public void onDestroy() {}
	
		@Keep public Decorating apply(NotificationManager nm, String tag, int id, Notification n) {
			return Decorating.Unprocessed;
		}
	
		protected final void cancelNotification(int id) {
			final NotificationManager manager = mNM;
			final Archive archive;
			synchronized (cache) { archive = cache.remove(id); }
			if (manager != null && archive != null) manager.cancel(archive.tag, id);
		}

		@android.annotation.SuppressLint("NotificationPermission")
		protected final void recastNotification(final int id, final Notification n) {
			if (n == null) return;
			final NotificationManager manager = mNM;
			final String tag;
			synchronized (cache) {
				final Archive archive = cache.get(id);
				if (archive == null) return; // An evicted notification must not be revived with a guessed tag.
				tag = archive.tag;
			}
			if (manager != null) manager.notify(tag, id, copyNotification(n));
		}
	}

	/**
	 * 在系统UI（SystemUI）中执行的通知处理。
	 */
	public static class SystemUIDecorator {
		private static volatile NotificationListenerService mNLS;

		public static void setNLS(NotificationListenerService nls) {
			mNLS = nls;
		}

		public static NotificationListenerService getNLS() {
			return mNLS;
		}

		private static final int MAX_SBN_CACHE_ENTRIES = 50;
		private static final LruCache<String, LinkedList<StatusBarNotification>> cache = new LruCache<>(MAX_SBN_CACHE_ENTRIES);

		protected static void cache(StatusBarNotification sbn) {
			if (sbn == null) return;
			final StatusBarNotification snapshot = copyStatusBarNotification(sbn);
			final String key = sbn.getKey();
			synchronized (cache) {
				LinkedList<StatusBarNotification> queue = cache.get(key);
				if (queue == null) {
					queue = new LinkedList<>();
					cache.put(key, queue);
				}
				queue.add(snapshot);
				while (queue.size() > MAX_NUM_ARCHIVED) queue.removeFirst();
			}
		}

		protected static List<StatusBarNotification> getArchivedNotifications(String key) {
			synchronized (cache) {
				final LinkedList<StatusBarNotification> queue = cache.get(key);
				final List<StatusBarNotification> result = new ArrayList<>();
				if (queue != null) for (StatusBarNotification sbn : queue) result.add(copyStatusBarNotification(sbn));
				return result;
			}
		}

		@Nullable protected static StatusBarNotification getArchivedNotification(String key) {
			synchronized (cache) {
				final LinkedList<StatusBarNotification> queue = cache.get(key);
				return queue == null || queue.isEmpty() ? null : copyStatusBarNotification(queue.getLast());
			}
		}

		protected static boolean hasArchivedNotifications(String key) {
			synchronized (cache) {
				final LinkedList<StatusBarNotification> queue = cache.get(key);
				return queue != null && !queue.isEmpty();
			}
		}

		public static void setId(StatusBarNotification sbn, int id) {
			XposedHelpers.setIntField(sbn, "id", id);
		}
	
		public static void setNotification(StatusBarNotification sbn, Notification n) {
			XposedHelpers.setObjectField(sbn, "notification", n);
		}
	
		public static int getOriginalId(StatusBarNotification sbn) {
			return (Integer)XposedHelpers.getAdditionalInstanceField(sbn, "originalId");
		}
	
		public static void setOriginalId(StatusBarNotification sbn, int id) {
			XposedHelpers.setAdditionalInstanceField(sbn, "originalId", (Integer)id);
		}
	
		public static String getOriginalKey(StatusBarNotification sbn) {
			return (String)XposedHelpers.getAdditionalInstanceField(sbn, "originalKey");
		}
	
		public static void setOriginalKey(StatusBarNotification sbn, String key) {
			XposedHelpers.setAdditionalInstanceField(sbn, "originalKey", key);
		}
	
		public static void setOriginalTag(StatusBarNotification sbn, String tag) {
			XposedHelpers.setAdditionalInstanceField(sbn, "originalTag", tag);
		}
	
		protected final String prefKey;
		private boolean disabled;

		protected SystemUIDecorator(String prefKey) { this.prefKey = prefKey; }

		public boolean isDisabled() { return disabled; }
		public void setDisabled(boolean disabled) { this.disabled = disabled; }
	
		@Keep public void onCreate(SharedPreferences pref) {
			this.disabled = !pref.getBoolean(prefKey  + ".enabled", true);
			if (BuildConfig.DEBUG) Log.d(TAG, prefKey + ".disabled " + this.disabled);
		}
	
		@Keep public void onDestroy() {}
	
		@Keep public Decorating onNotificationPosted(final StatusBarNotification sbn) {
			Log.d(TAG, "onNotificationPosted(" + sbn + ")");
			return Decorating.Unprocessed;
		}
		@Keep public void onNotificationRemoved(final StatusBarNotification evolving, final int reason) {
			Log.d(TAG, "onNotificationRemoved(" + evolving + ", " + reason + ")");
		}

		protected final void cancelNotification(String key) {
			Log.d(TAG, "cancelNotification " + key);
			if (mNLS != null) mNLS.cancelNotification(key);
		}
	
		protected final void recastNotification(final StatusBarNotification sbn) {
			Log.d(TAG, "recastNotification " + sbn + " " + mNLS);
			if (mNLS != null) mNLS.onNotificationPosted(sbn, null);
		}
	}

	protected final String prefKey;
	// private boolean disabled;

	public NevoDecoratorService() {
		this.prefKey = getClass().getSimpleName();
	}

	private LocalDecorator localDecorator;
	@Keep public LocalDecorator createLocalDecorator(String packageName) { return null; }
	@Keep public final LocalDecorator getLocalDecorator(String packageName) {
		if (localDecorator == null) localDecorator = createLocalDecorator(packageName); // 不同package在不同进程，无需映射packageName
		return localDecorator;
	}
	private SystemUIDecorator systemUIDecorator;
	@Keep public SystemUIDecorator createSystemUIDecorator() { return null; }
	@Keep public final SystemUIDecorator getSystemUIDecorator() {
		if (systemUIDecorator == null) systemUIDecorator = createSystemUIDecorator();
		return systemUIDecorator;
	}

	// public boolean isDisabled() { return disabled; }
	// public void setDisabled(boolean disabled) { this.disabled = disabled; }

	// @Keep public void onCreate(SharedPreferences pref) {
	// 	this.disabled = !pref.getBoolean(prefKey  + ".enabled", true);
	// 	if (BuildConfig.DEBUG) Log.d(TAG, prefKey + ".disabled " + this.disabled);
	// }

	// @Keep public void onDestroy() {}

	// /**
	//  * 在应用进程中执行的通知预处理，某些功能（NotificationChannel等）在此实现。
	//  */
	// @Keep public void preApply(NotificationManager nm, String tag, int id, Notification n) {}
	// @Keep public Decorating onNotificationPosted(final StatusBarNotification sbn) {
	// 	apply(sbn);
	// 	return Decorating.Processed;
	// }
	// /**
	//  * 在系统UI（SystemUI）中执行的通知处理。
	//  */
	// @Deprecated
	// @Keep public void apply(final StatusBarNotification evolving) {}
	// @Keep public void onNotificationRemoved(final StatusBarNotification evolving, final int reason) {
	// 	Log.d(TAG, "onNotificationRemoved(" + evolving + ", " + reason + ")");
	// 	onNotificationRemoved(evolving.getKey(), reason);
	// }
	// @Keep public void onNotificationRemoved(final String key, final int reason) {}
}
