package com.oasisfeng.nevo.xposed;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Keep;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.notxx.xposed.DeviceSharedPreferences;

import com.oasisfeng.nevo.sdk.HookSupport;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;
import com.oasisfeng.nevo.sdk.NevoDecoratorService.LocalDecorator;
import com.oasisfeng.nevo.sdk.NevoDecoratorService.SystemUIDecorator;
import com.oasisfeng.nevo.xposed.BuildConfig;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * hook and manupinate notifications.
 * 
 * @author notXX
 */
public class MainHook implements IXposedHookLoadPackage, de.robv.android.xposed.IXposedHookZygoteInit {
	@Override public void initZygote(StartupParam param) { ModuleViews.modulePath = param.modulePath; }
	private static final String TAG = "MainHook";

	private final XSharedPreferences pref = DeviceSharedPreferences.get(BuildConfig.APPLICATION_ID);
	private final NevoDecoratorService wechat = new com.oasisfeng.nevo.decorators.wechat.WeChatDecorator();
	private final NevoDecoratorService miui = new com.oasisfeng.nevo.decorators.MIUIDecorator();
	private final NevoDecoratorService media = new com.oasisfeng.nevo.decorators.media.MediaDecorator();


	private static void inspect(XC_LoadPackage.LoadPackageParam loadPackageParam, String className, String... methods) {
		try {
			final Class<?> clazz = XposedHelpers.findClass(className, loadPackageParam.classLoader);
			XposedBridge.log("inspect clazz: " + clazz + " " + loadPackageParam.packageName);
			Consumer<String> inspect = method -> {
				XposedBridge.hookAllMethods(clazz, method, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) {
						Log.d("inspect.method", loadPackageParam.packageName + " " + param.method.getName());
						for (Object arg : param.args) {
							Log.d("inspect.method", loadPackageParam.packageName + " arg " + arg);
						}
						if (BuildConfig.DEBUG) Log.d(TAG, loadPackageParam.packageName + " " + Log.getStackTraceString(new Exception()));
					}
				});
			};
			for (String method : methods) {
				inspect.accept(method);
			}
		} catch (XposedHelpers.ClassNotFoundError e) { /* XposedBridge.log("ContextImpl hook failed"); */ }
	}

	@Keep
	private static void inspectThen(XC_LoadPackage.LoadPackageParam loadPackageParam, String className, Consumer<Class<?>>... thens) {
		try {
			final Class<?> clazz = XposedHelpers.findClass(className, loadPackageParam.classLoader);
			XposedBridge.log("inspect clazz: " + clazz + " " + loadPackageParam.packageName);
			for (Consumer<Class<?>> then : thens) {
				then.accept(clazz);
			}
		} catch (XposedHelpers.ClassNotFoundError e) { /* XposedBridge.log("ContextImpl hook failed"); */ }
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		switch (loadPackageParam.packageName) {
			case "com.android.systemui":
			hookSystemUI(loadPackageParam);
			break;
			case "com.tencent.mm":
			hookWeChat(loadPackageParam);
			break;
			case "com.oasisfeng.nevo":
			hookEngine(loadPackageParam);
			break;
		}
		/* inspect(loadPackageParam,
				"com.android.server.notification.NotificationManagerService",
				"getNotificationChannel",
				"deleteNotificationChannel",
				"deleteNotificationChannelGroup",
				"createNotificationChannels"); */
	}

	private void hookSystemUI(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        InlineReplyVisibility.install(loadPackageParam.classLoader);
        if (BuildConfig.DEBUG) try {
            Class.forName("com.oasisfeng.nevo.xposed.DebugInlineProbe").getMethod("install", ClassLoader.class).invoke(null, loadPackageParam.classLoader);
        } catch (ReflectiveOperationException | LinkageError unavailable) { Log.w(TAG, "Inline diagnostics unavailable"); }

		
        AtomicReference<NotificationListenerService> nlsRef = new AtomicReference<>();
		final XC_MethodHook onNotificationPosted = new XC_MethodHook() { // 捕获通知到达
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				StatusBarNotification sbn = (StatusBarNotification)param.args[0];
				// RankingMap rankingMap = (RankingMap)param.args[1];
				Log.d(TAG, "onNotificationPosted");
				onNotificationPosted(sbn);
			}
		}, onNotificationRemoved = new XC_MethodHook() { // 捕获通知移除
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				StatusBarNotification sbn = (StatusBarNotification)param.args[0];
				// Adaptive reason extraction: handle both old (with NotificationStats) and new (without) signatures
				int reason;
				try {
					if (param.args.length >= 4 && param.args[3] instanceof Integer) {
						reason = (int) param.args[3]; // Old: (sbn, rankingMap, notificationStats, reason)
					} else if (param.args.length >= 3 && param.args[2] instanceof Integer) {
						reason = (int) param.args[2]; // New: (sbn, rankingMap, reason)
					} else {
						reason = 0;
					}
				} catch (Exception e) {
					reason = 0;
				}
				Log.d(TAG, "onNotificationRemoved");
				onNotificationRemoved(sbn, reason);
			}
		}, nls = new XC_MethodHook() { // 捕获NotificationListenerService的具体实现
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				// XposedBridge.log("nls constructor " + param.thisObject);
				NotificationListenerService nls = (NotificationListenerService)param.thisObject;
				if (nlsRef.compareAndSet(null, nls)) {
					SystemUIDecorator.setNLS(nls);
				}
				try {
					final Class<?> clazz = nls.getClass();
					XposedBridge.log("NL clazz: " + clazz + " " + loadPackageParam.packageName);
					Method method = XposedHelpers.findMethodExact(clazz, "onNotificationPosted", 
							StatusBarNotification.class, RankingMap.class);
					Log.d(TAG, "method " + method);
					XposedBridge.hookMethod(method, onNotificationPosted);
					// Try Android 16+ signature first, then fall back to older signature
					try {
						method = XposedHelpers.findMethodBestMatch(clazz, "onNotificationRemoved", StatusBarNotification.class, RankingMap.class,
								XposedHelpers.findClass("android.service.notification.NotificationStats", loadPackageParam.classLoader), int.class);
					} catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e2) {
						// Android 16 may have changed NotificationStats or its location
						XposedBridge.log("NotificationStats not found, trying without it");
						try {
							method = XposedHelpers.findMethodBestMatch(clazz, "onNotificationRemoved", StatusBarNotification.class, RankingMap.class, int.class);
						} catch (Throwable e3) {
							XposedBridge.log("onNotificationRemoved hook failed: " + e3.getMessage());
							method = null;
						}
					}
					if (method != null) {
						Log.d(TAG, "method " + method);
						XposedBridge.hookMethod(method, onNotificationRemoved);
					} else {
						XposedBridge.log("WARNING: onNotificationRemoved hook completely failed, notification removal tracking disabled");
					}
				} catch (Throwable e) { XposedBridge.log("NL hook failed: " + e.getMessage()); }
			}
		};
		try {
			XposedBridge.hookAllConstructors(NotificationListenerService.class, nls);
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log("NotificationListenerService hook failed "); }
		try {
			final Class<?> clazz = XposedHelpers.findClass("android.app.ContextImpl", loadPackageParam.classLoader);
			XposedBridge.log("CI clazz: " + clazz);
			AtomicReference<Context> ref = new AtomicReference<>();
			XposedBridge.hookAllMethods(clazz, "createAppContext", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					Context context = (Context)param.getResult();
					if (ref.compareAndSet(null, context)) {
						XposedBridge.log("onCreate " + context);
						onCreate(context);
					}
				}
			});
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log("ContextImpl hook failed"); }
		try {
			SystemUIDecorator media = this.media.getSystemUIDecorator();
			if ((media instanceof HookSupport)) { ((HookSupport)media).hook(loadPackageParam); }
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log(this.media + " hook failed"); }
	}
	
	private void onCreate(Context context) {
		NevoDecoratorService.setAppContext(context);

		SystemUIDecorator miui = this.miui.getSystemUIDecorator(),
				media = this.media.getSystemUIDecorator();
		miui.onCreate(pref);
		media.onCreate(pref);
	}

	private void onNotificationPosted(StatusBarNotification sbn) {
		if (XposedHelpers.getAdditionalInstanceField(sbn, "applied") != null) {
			Log.d(TAG, "skip " + sbn);
			return;
		}
		XposedHelpers.setAdditionalInstanceField(sbn, "applied", true);

		SystemUIDecorator miui = this.miui.getSystemUIDecorator(),
				media = this.media.getSystemUIDecorator();
		switch (sbn.getPackageName()) {
			case "com.xiaomi.xmsf":
			if (!miui.isDisabled()) miui.onNotificationPosted(sbn);
			break;
		}
		if (!media.isDisabled()) media.onNotificationPosted(sbn);
	}

	private void onNotificationRemoved(StatusBarNotification sbn, int reason) {
		SystemUIDecorator miui = this.miui.getSystemUIDecorator(),
				media = this.media.getSystemUIDecorator();
		switch (sbn.getPackageName()) {
			case "com.xiaomi.xmsf":
			if (!miui.isDisabled()) miui.onNotificationRemoved(sbn, reason);
			break;
		}
		if (!media.isDisabled()) media.onNotificationRemoved(sbn, reason);
	}


	private static final String EXTRA_USERNAME = "nevo.verified.username";
	private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
	private static volatile ClassLoader weChatClassLoader;
	private static volatile String weChatVersion;
	private static volatile int identityConstructors;
	private static final java.util.Map<PendingIntent, String> CONTENT_IDENTITIES =
			java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<PendingIntent, String>(16, .75f, true) {
				@Override protected boolean removeEldestEntry(java.util.Map.Entry<PendingIntent, String> entry) { return size() > 100; }
			});
	private static final ThreadLocal<String> PUBLISHING_USERNAME = new ThreadLocal<>();
	private static final ThreadLocal<Integer> PUBLISHING_ID = new ThreadLocal<>();
	private static volatile String lastNotificationState = "none";
	private static volatile String lastReplyState = "none";
	public static void recordReplyState(String state) { lastReplyState = state; }
	public static androidx.core.app.Person currentSelfPerson(Context context) {
		return SelfAvatarProvider.current(context, weChatVersion);
	}
	public static String diagnoseAdapter(Context context) {
		String stage = "resolve";
		try {
			ClassLoader runtime = context.getClassLoader();
			Class<?> locator = XposedHelpers.findClass("no3.w1", runtime);
			stage = "locator";
			Object messenger = XposedHelpers.callStaticMethod(locator, "a");
			return "service=" + (messenger != null) + ";runtimeLoader=" + runtime.getClass().getName()
					+ ";sameLoader=" + (runtime == weChatClassLoader);
		} catch (Throwable failure) {
			Throwable cause = failure;
			for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) cause = cause.getCause();
			StringBuilder result = new StringBuilder(stage).append(':').append(cause.getClass().getSimpleName());
			// No message text or recipient is passed to this diagnostic service lookup.
			String message = cause.getMessage();
			if (message != null) result.append(':').append(message.replaceAll("[\\r\\n]", " "), 0, Math.min(160, message.length()));
			for (int i = 0; i < Math.min(3, cause.getStackTrace().length); i++) {
				StackTraceElement frame = cause.getStackTrace()[i];
				result.append(';').append(frame.getClassName()).append('.').append(frame.getMethodName());
			}
			return result.toString();
		}
	}
	public static String describeRuntime() {
		return "module=" + BuildConfig.VERSION_NAME + ";wechat=" + weChatVersion
				+ ";identityConstructors=" + identityConstructors + ";contentIdentities=" + CONTENT_IDENTITIES.size()
				+ ";last=" + lastNotificationState + ";reply=" + lastReplyState
				+ ";selfAvatar=" + SelfAvatarProvider.status() + ";freshViews=" + ModuleViews.freshApplied;
	}

	private void hookWeChat(XC_LoadPackage.LoadPackageParam lpp) {
		if (!"com.tencent.mm".equals(lpp.processName)) return;
		XposedBridge.log("NevoStability: WeChat hook entry " + BuildConfig.VERSION_NAME);
		weChatClassLoader = lpp.classLoader;
		hookContentIntents();
		hookNotificationIdentity(lpp.classLoader);
		try {
			XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
				@Override protected void afterHookedMethod(MethodHookParam param) {
					Context context = ((Application) param.thisObject).getApplicationContext();
					NevoDecoratorService.setAppContext(context);
					ClassLoader runtimeLoader = context.getClassLoader();
					if (runtimeLoader != weChatClassLoader) {
						weChatClassLoader = runtimeLoader;
						hookNotificationIdentity(runtimeLoader);
					}
					try {
						weChatVersion = context.getPackageManager().getPackageInfo("com.tencent.mm", 0).versionName;
						WeChatMedia.install(context, weChatVersion);
						MainHook.this.wechat.getLocalDecorator("com.tencent.mm").onCreate(pref);
						Log.i(TAG, "NevoStability initialized version=" + weChatVersion);
						if (BuildConfig.DEBUG) {
							try {
								Class.forName("com.oasisfeng.nevo.xposed.DebugNotificationProbe")
										.getMethod("install", Context.class).invoke(null, context);
							} catch (ReflectiveOperationException ignored) { /* Debug helper optional. */ }
						}
					} catch (Throwable e) {
						Log.w(TAG, "Decorator initialization unavailable: " + e.getClass().getSimpleName());
					}
				}
			});
			XposedHelpers.findAndHookMethod(NotificationManager.class, "notify", String.class, int.class,
					Notification.class, new XC_MethodHook() {
				@Override protected void beforeHookedMethod(MethodHookParam param) {
					if (Boolean.TRUE.equals(APPLYING.get()) || param.args[2] == null) return;
					APPLYING.set(true);
					try {
						Notification original = (Notification) param.args[2];
						Notification candidate = original.clone();
						candidate.extras = original.extras == null ? new android.os.Bundle() : original.extras.deepCopy();
						candidate.extras.putString("nevo.notification.tag", param.args[0] == null ? "" : (String) param.args[0]);
						String username = candidate.contentIntent == null ? null : CONTENT_IDENTITIES.get(candidate.contentIntent);
						if (username == null && java.util.Objects.equals(PUBLISHING_ID.get(), (Integer) param.args[1]))
							username = PUBLISHING_USERNAME.get();
						if (username != null) candidate.extras.putString(EXTRA_USERNAME, username);
						NotificationManager nm = (NotificationManager) param.thisObject;
						Context context = NevoDecoratorService.getAppContext();
						if (context == null) {
							context = ((Context) XposedHelpers.getObjectField(nm, "mContext")).getApplicationContext();
							NevoDecoratorService.setAppContext(context);
						}
						LocalDecorator.setNM(nm);
						LocalDecorator local = MainHook.this.wechat.getLocalDecorator("com.tencent.mm");
						if (local.isDisabled()) return;
						com.oasisfeng.nevo.sdk.Decorating result = local.apply(nm, (String) param.args[0],
								(int) param.args[1], candidate);
						if (result == com.oasisfeng.nevo.sdk.Decorating.Processed) {

							Notification rebuilt = Notification.Builder.recoverBuilder(context, candidate).build();
							try {
                                Notification.Builder viewBuilder = Notification.Builder.recoverBuilder(context, rebuilt)
                                        .setStyle(new Notification.DecoratedCustomViewStyle())
                                        .setCustomBigContentView(com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.render(context, candidate))
                                        .setContentTitle(candidate.extras.getCharSequence("nevo.conversation.title"))
                                        // 装饰头部只显示微信图标 + 好友昵称，不显示好友头像。
                                        // 好友头像已由 render() 在聊天框内渲染，头部无需重复展示。
                                        .setLargeIcon((android.graphics.drawable.Icon) null);
                                if (InlineReplyVisibility.replyAction(candidate) != null) {
                                    viewBuilder.setCustomContentView(com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.compact(context, candidate))
                                            .setCustomHeadsUpContentView(com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.compact(context, candidate));
                                }
                                rebuilt = viewBuilder.build();
                                rebuilt.extras.putBoolean("nevo.inline.reply.entry", InlineReplyVisibility.replyAction(candidate) != null);
							} catch (RuntimeException unavailable) {
								Log.w(TAG, "Chat view unavailable: " + unavailable.getClass().getSimpleName());
							}
							param.args[2] = rebuilt;
						}
						if (BuildConfig.DEBUG) Log.i(TAG, "NevoStability notification id=" + param.args[1]
								+ " result=" + result + " identity=" + (getCapturedWeChatUsername(candidate) != null));
						lastNotificationState = "id:" + param.args[1] + ",result:" + result
								+ ",identity:" + (getCapturedWeChatUsername(candidate) != null)
								+ ",actions:" + (candidate.actions == null ? 0 : candidate.actions.length);
					} catch (Throwable e) {
						// Always retain the untouched native notification if enhancement fails.
						Log.w(TAG, "Notification enhancement skipped: " + e.getClass().getSimpleName());
					} finally { APPLYING.remove(); }
				}
			});
		} catch (Throwable e) {
			Log.w(TAG, "WeChat notification hooks unavailable: " + e.getClass().getSimpleName());
		}
	}

	private void hookNotificationIdentity(ClassLoader cl) {
		try {
			Class<?> item = XposedHelpers.findClass("com.tencent.mm.booter.notification.NotificationItem", cl);
			for (java.lang.reflect.Constructor<?> constructor : item.getDeclaredConstructors()) {
				Class<?>[] types = constructor.getParameterTypes();
				// Only accept the proven positional signature, never the first arbitrary String/int.
				if (types.length < 3 || types[0] != int.class || types[1] != String.class
						|| types[2] != Notification.class) continue;
				XposedBridge.hookMethod(constructor, new XC_MethodHook() {
					@Override protected void afterHookedMethod(MethodHookParam param) {
						String username = (String) param.args[1];
						Notification notification = (Notification) param.args[2];
						XposedHelpers.setAdditionalInstanceField(param.thisObject, "nevo-id", param.args[0]);
						if (username == null || username.isEmpty() || notification == null) return;
						if (notification.extras == null) notification.extras = new android.os.Bundle();
						notification.extras.putString(EXTRA_USERNAME, username);
						// Media is resolved later from NotificationItem's exact message ID.
					}
				});
				identityConstructors++;
			}
			XposedHelpers.findAndHookMethod(item, "a", Context.class, new XC_MethodHook() {
				@Override protected void beforeHookedMethod(MethodHookParam param) {
					String username = null;
					Notification notification = null;
					int stringFields = 0;
					try {
						for (Field field : item.getDeclaredFields()) {
							if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
							if (field.getType() == String.class) {
								stringFields++; field.setAccessible(true); username = (String) field.get(param.thisObject);
							} else if (field.getType() == Notification.class) {
								field.setAccessible(true); notification = (Notification) field.get(param.thisObject);
							}
						}
						Object id = XposedHelpers.getAdditionalInstanceField(param.thisObject, "nevo-id");
						if (stringFields == 1 && username != null && !username.isEmpty() && notification != null && id instanceof Integer) {
							PUBLISHING_ID.set((Integer) id); PUBLISHING_USERNAME.set(username);
							WeChatMedia.tagPublished((Context)param.args[0], param.thisObject, notification, username);
						}
					} catch (IllegalAccessException ignored) { /* No identity is safer than a guessed target. */ }
				}
				@Override protected void afterHookedMethod(MethodHookParam param) {
					PUBLISHING_ID.remove(); PUBLISHING_USERNAME.remove();
				}
			});
		} catch (Throwable e) {
			Log.w(TAG, "Notification identity capture unavailable: " + e.getClass().getSimpleName());
		}
	}

	/** Read a received image into a bounded thumbnail, never exceeding the notification budget. */
	static Bitmap loadImagePreview(String path) {
		try {
			File file = new File(path);
			if (!file.exists() || file.length() == 0 || file.length() > 16 * 1024 * 1024) return null;
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(path, options);
			int width = options.outWidth, height = options.outHeight;
			if (width <= 0 || height <= 0) return null;
			// 最大边 640px：卡片内一眼清晰，且单张位图经 Binder 传输仍在安全预算内。
			int sample = 1;
			while (Math.max(width, height) / sample > 640) sample <<= 1;
			options.inJustDecodeBounds = false;
			options.inSampleSize = sample;
			options.inPreferredConfig = Bitmap.Config.ARGB_8888;
			Bitmap decoded=BitmapFactory.decodeFile(path, options);
            if(decoded==null) return null;
            float scale=Math.min(1f,Math.min(640f/decoded.getWidth(),640f/decoded.getHeight()));
            if(scale>=1f)return decoded;
            Bitmap bounded=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*scale)),Math.max(1,Math.round(decoded.getHeight()*scale)),true);
            if(bounded!=decoded)decoded.recycle(); return bounded;
		} catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { return null; }
	}


	private void hookContentIntents() {
		XC_MethodHook hook = new XC_MethodHook() {
			@Override protected void afterHookedMethod(MethodHookParam param) {
				if (!(param.getResult() instanceof PendingIntent)) return;
				PendingIntent pending = (PendingIntent) param.getResult();
				if (!"com.tencent.mm".equals(pending.getCreatorPackage())) return;
				Intent target = null;
				for (Object argument : param.args) {
					if (argument instanceof Intent) target = (Intent) argument;
					else if (argument instanceof Intent[]) {
						Intent[] intents = (Intent[]) argument;
						if (intents.length > 0) target = intents[intents.length - 1];
					}
				}
				try {
					if (target == null || target.getComponent() == null
							|| !"com.tencent.mm".equals(target.getComponent().getPackageName())) return;
					String activity = target.getComponent().getClassName();
					if (!"com.tencent.mm.ui.LauncherUI".equals(activity)
							&& !"com.tencent.mm.ui.chatting.variants.ChattingMainUI".equals(activity)) return;
					String username = target.getStringExtra("Chat_User");
					String mainUser = target.getStringExtra("Main_User");
					if (username != null && !username.isEmpty() && username.equals(mainUser)
							&& !username.endsWith("@fakeuser")) CONTENT_IDENTITIES.put(pending, username);
					else CONTENT_IDENTITIES.remove(pending);
				} catch (RuntimeException ignored) { CONTENT_IDENTITIES.remove(pending); }
			}
		};
		XposedBridge.hookAllMethods(PendingIntent.class, "getActivity", hook);
		XposedBridge.hookAllMethods(PendingIntent.class, "getActivities", hook);
	}

	public static String getCapturedWeChatUsername(int id, Notification notification) {
		return getCapturedWeChatUsername(notification);
	}

	public static String getCapturedWeChatUsername(Notification notification) {
		return notification != null && notification.extras != null ? notification.extras.getString(EXTRA_USERNAME) : null;
	}

	/** Only adapters verified against a particular APK may invoke private messenger APIs. */
	public static boolean isInternalReplySupported() {
		return "8.0.76".equals(weChatVersion) || "8.0.79".equals(weChatVersion);
	}

	public static boolean invokeInternalWeChatSend(Context context, String username, String text) {
		lastReplyState = "adapter-entered";
		if (!isInternalReplySupported() || context == null || username == null || username.isEmpty()
				|| text == null || text.isEmpty() || weChatClassLoader == null) return false;
		try {
			// Tinker replaces the initial package loader. Service registries must come from the live app loader.
			ClassLoader runtimeLoader = context.getClassLoader();
			boolean current = "8.0.79".equals(weChatVersion);
			Class<?> locator = XposedHelpers.findClass(current ? "no3.w1" : "qh3.t1", runtimeLoader);
			lastReplyState = "locator-call";
			Object messenger = XposedHelpers.callStaticMethod(locator, "a");
			if (messenger == null) return false;
			lastReplyState = "messenger-found";
			Class<?> helper = XposedHelpers.findClass(current ? "l41.d2" : "e01.e2", runtimeLoader);
			lastReplyState = "type-helper-call";
			Object type = XposedHelpers.callStaticMethod(helper, "C", username);
			if (!(type instanceof Integer)) return false;
			Class<?> contract = current ? XposedHelpers.findClass("rt5.i5", runtimeLoader) : null;
			// 8.0.76 retains the previous adapter until its exact interface is resolved.
			if (current) {
				Method send = contract.getMethod("Jj", String.class, String.class, int.class, int.class);
				if (send.getReturnType() != void.class || !contract.isInstance(messenger)) return false;
				lastReplyState = "send-invoking";
				send.invoke(messenger, username, text, (Integer) type, 0);
			} else XposedHelpers.callMethod(messenger, "yj", username, text, type, 0);
			lastReplyState = "submitted";
			return true; // Submitted to WeChat, not a delivery receipt.
		} catch (Throwable e) {
			Throwable cause = e;
			for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) cause = cause.getCause();
			lastReplyState += ":failed:" + cause.getClass().getSimpleName();
			Log.w(TAG, "WeChat send not submitted: " + e.getClass().getSimpleName());
			return false;
		}
	}

	private void hookEngine(XC_LoadPackage.LoadPackageParam lpp) {
		// No synthetic actions without an in-process, validated receiver.
	}
}
