/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.text.TextUtils;
import android.widget.RemoteViews;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.graphics.drawable.IconCompat;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.Decorating;
import com.oasisfeng.nevo.sdk.Decorator;
import com.oasisfeng.nevo.sdk.HookSupport;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;
import com.oasisfeng.nevo.xposed.BuildConfig;
import com.oasisfeng.nevo.xposed.MainHook;
import com.oasisfeng.nevo.xposed.R;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 *
 * @class WeChatImageDecorator.
 * 
 */
@Decorator(title = R.string.decorator_wechat_title, description = R.string.decorator_wechat_description, priority = -20)
public class WeChatDecorator extends NevoDecoratorService {
	
	public static final String WECHAT_PACKAGE = "com.tencent.mm";
	private static final long GROUP_CHAT_SORT_KEY_SHIFT = 24 * 60 * 60 * 1000L;			// Sort group chat like one day older message.
	private static final String CHANNEL_MESSAGE = "message_channel_new_id";				// Channel ID used by WeChat for all message notifications
	private static final String OLD_CHANNEL_MESSAGE = "message";						//   old name for migration
	private static final String CHANNEL_MISC = "reminder_channel_id";					// Channel ID used by WeChat for misc. notifications
	private static final String OLD_CHANNEL_MISC = "misc";								//   old name for migration
	private static final String CHANNEL_DND = "message_dnd_mode_channel_id";			// Channel ID used by WeChat for its own DND mode
	private static final String CHANNEL_GROUP_CONVERSATION = "group";					// WeChat has no separate group for group conversation
	private static final String RECALL_PATTERN = "(\"(?<recaller>[^\"]+)\" )?撤回了?一条消息";
	private static final Pattern pattern = Pattern.compile(RECALL_PATTERN);				// [2条]"🦉 " 撤回了一条消息 / [2条]撤回一条消息

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;

	static final String ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED";
	static final String ACTION_DEBUG_NOTIFICATION = "DEBUG";
	private static final String KEY_SILENT_REVIVAL = "nevo.wechat.revival";
	private static final String EXTRA_RECALL = "nevo.wechat.recall";
	private static final String EXTRA_RECALLER = "nevo.wechat.recaller";
	public static final String EXTRA_PICTURE_PATH = "nevo.wechat.picturePath";
	private static final String EXTRA_PICTURE = "nevo.wechat.picture";
	private static final String STORAGE_PREFIX = "/storage/emulated/0/";

	static final String TAG = "WeChatDecorator";

	public static interface ModifyNotification { void modify(Notification n); }

	// 诊断回调
	private static interface Diagnose { void diagnose(final XC_LoadPackage.LoadPackageParam loadPackageParam, final ClassLoader loader, final Class target); }

	// 诊断钩子
	private static void hookForDiagnose(final XC_LoadPackage.LoadPackageParam loadPackageParam, final String targetClassName, final Diagnose diagnose) {
		final List<ClassLoader> loaders = new ArrayList<>();
		final AtomicReference<Class> targetRef = new AtomicReference<>();
		Class clazz = android.app.Notification.Builder.class;
		Log.d(TAG, "hookForDiagnose clazz " + clazz);
		XposedHelpers.findAndHookMethod(clazz, "setLargeIcon", Icon.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				if (targetRef.get() != null) return;
				// Log.d(TAG, "method " + param.method);
				for (ClassLoader loader : loaders) {
					// Log.d(TAG, "loader " + loader);
					try {
						Class target = XposedHelpers.findClass(targetClassName, loader);
						if (target == null) {
							Log.d(TAG, "cannot find " + targetClassName + " with " + loader);
							continue;
						}
						Log.d(TAG, "target " + target);
						// Class ni = XposedHelpers.findClass("com.tencent.mm.booter.notification.NotificationItem", loader);
						targetRef.set(target);
						if (diagnose != null) diagnose.diagnose(loadPackageParam, loader, target);
					} catch (Exception ex) { Log.d(TAG, "find " + targetClassName + " failed with " + loader); }
				}
			}
		});
		clazz = ClassLoader.class; // 
		// Log.d(TAG, "clazz " + clazz);
		XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				ClassLoader cl = (ClassLoader)param.thisObject;
				// Log.d(TAG, "ClassLoader " + cl);
				loaders.add(cl);
			}
		});
	}

	@Override public LocalDecorator createLocalDecorator(String packageName) {
		return new Local(this.prefKey);
	}

	public static class Local extends LocalDecorator implements HookSupport {
		public Local(String prefKey) {
			super(prefKey);
		}
		
		@Override public void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
			// File write times do not identify a message or conversation. Do not hook
			// FileOutputStream or attach the most recently written private image.
		}

		// 抓android.support.v4.app.s$c::c(android.graphics.Bitmap) <- android.support.v4.app.NotificationCompat$Builder::setLargeIcon
		private void hookSetLargeIcon(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
			for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
				Class[] types = method.getParameterTypes();
				if (types.length != 1 || !android.graphics.Bitmap.class.equals(types[0])) continue;
				XposedBridge.hookMethod(method, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) {
						Log.d(TAG, param.method + " " + param.args[0]);
						Object stack = XposedHelpers.getAdditionalInstanceField(param.args[0], "stack");
						if (stack instanceof Exception) Log.d(TAG, "stack", (Exception)stack);
					}
				});
				XposedBridge.hookAllConstructors(android.graphics.Bitmap.class, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) {
						XposedHelpers.setAdditionalInstanceField(param.thisObject, "stack", new Exception());
					}
				});
			}
		}

		// 抓com.tencent.mm.booter.notification.c::a(NotificationItem)
		private void hookNotificationItem(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
			for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
				Class[] types = method.getParameterTypes();
				if (types.length == 0/* || !android.graphics.Bitmap.class.equals(types[0])*/) continue;
				Log.d(TAG, "method " + method);
				XposedBridge.hookMethod(method, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) {
						for (Object arg : param.args) Log.d(TAG, param.method + " " + arg);
					}
				});
			}
		}

		// 抓com.tencent.mm.sdk.platformtools.am::dispatchMessage(android.os.Message)
		private void hookDispatchMessage(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
			XposedHelpers.findAndHookMethod(target, "dispatchMessage", Message.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					Message msg = (Message)param.args[0];
					Bundle data = msg.getData();
					// Log.d(TAG, "data " + data);
					int msgType = data.getInt("notification.show.message.type", -1);
					// Log.d(TAG, "msgType " + msgType);
					if (msgType == -1) return;
					Log.d(TAG, param.method.getName() + " " + data);
					// for (java.lang.reflect.Field field : ni.getDeclaredFields()) {
					// 	try {
					// 		field.setAccessible(true);
					// 		Log.d(TAG, "ni " + field.getName() + " " + field.getType() + " " + field.get(param.args[0]));
					// 	} catch (IllegalAccessException ex0) {
					// 		Log.d(TAG, "ni error " + field.getName() + " " + field.getType() + " ");
					// 	}
					// }
				}
			});
		}

		private MessagingBuilder mMessagingBuilder;
		private String channelGroupMessage, channelMessage, channelMisc;
		private boolean mWeChatTargetingO;
		private final ConversationManager mConversationManager = new ConversationManager();

		@Override public void onCreate(SharedPreferences pref) {
			super.onCreate(pref);

			mMessagingBuilder = new MessagingBuilder(getAppContext(), getPackageContext(), this::modifyNotification);		// Must be called after loadPreferences().
			channelGroupMessage = getString(R.string.channel_group_message);
			channelMessage = getString(R.string.channel_message);
			channelMisc = getString(R.string.channel_misc);
		}

		@Override public Decorating apply(NotificationManager nm, String tag, int id, Notification n) {
			if (nm == null || n == null || n.extras == null || getAppContext() == null)
				return Decorating.Unprocessed;
			// This module currently has no verified message-to-file association.
			// Remove old module metadata while retaining WeChat's native image extras.
			n.extras.remove(EXTRA_PICTURE_PATH);
			mWeChatTargetingO = isWeChatTargeting26OrAbove();
			if (BuildConfig.DEBUG) Log.d(TAG, "apply tag " + tag + " id " + id);

			// Preserve ongoing calls. Never infer another conversation's call type
			// from the most recently seen global call notification.
			if (Notification.CATEGORY_CALL.equals(n.category)) return Decorating.Unprocessed;
			if (id == 40 || id == 41) {
				final CharSequence textCheck = n.extras.getCharSequence(Notification.EXTRA_TEXT);
				if (textCheck != null) {
					final String textStr = textCheck.toString();
					if (textStr.contains("邀请你") || textStr.contains("通话中")
							|| textStr.contains("calling") || textStr.contains("Voice call")
							|| textStr.contains("Video call")) return Decorating.Unprocessed;
				}
			}

			// Log.d(TAG, "deleteIntent " + n.deleteIntent);
			final Bundle extras = n.extras,
				extensions = extras.getBundle("android.car.EXTENSIONS");
			if (extensions != null) {
				Bundle conversation = extensions.getBundle("car_conversation");
				if (conversation != null) {
					Object onRead = conversation.get("on_read");
					if (onRead instanceof PendingIntent) n.deleteIntent = (PendingIntent) onRead;
				}
			}
			// Log.d(TAG, "deleteIntent " + n.deleteIntent);
			CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
			if (title == null || title.length() == 0) {
				Log.e(TAG, "Title is missing: " + n);
				return Decorating.Unprocessed;
			}
			if (title != (title = EmojiTranslator.translate(title))) extras.putCharSequence(Notification.EXTRA_TITLE, title);
			final Conversation previousConversation = mConversationManager.getConversation(id);
			final String currentUsername = MainHook.getCapturedWeChatUsername(id, n);
			if ((previousConversation.title != null && !TextUtils.equals(previousConversation.title, title))
					|| (previousConversation.key != null && currentUsername != null
							&& !previousConversation.key.equals(currentUsername))) {
				// WeChat reuses notification IDs. Do not carry another recipient's
				// identity, group participants, or historical messages into this one.
				mConversationManager.removeConversation(id);
				if (mMessagingBuilder != null) mMessagingBuilder.forgetConversation(id);
				removeArchivedNotification(id);
			}
			cache(id, tag, n);
			n.color = PRIMARY_COLOR;        // Tint the small icon

			String channel_id = SDK_INT >= O ? n.getChannelId() : null;
			if (CHANNEL_MISC.equals(channel_id)) return Decorating.Unprocessed;	// Misc. notifications on Android 8+.
			
			final CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
			String content = text != null ? text.toString() : null;
			final UnreadCountPrefix prefix = UnreadCountPrefix.parse(content);
			if (prefix != null) {
				n.number = prefix.count;
				content = content.substring(prefix.end);
			}
			// Keep the native image notification until attachment ownership can be
			// established by message identity. Timestamp proximity is insufficient.
			// 表情包/视频/文件等消息不做处理，保留微信原始通知内容
			if (content != null) {
				if (content.contains("[视频]") || content.contains("[文件]") || content.contains("[链接]") ||
					content.contains("[音乐]") || content.contains("[位置]") || content.contains("[红包]") ||
					content.contains("[转账]") || content.contains("[小程序]")) {
					if (BuildConfig.DEBUG) Log.d(TAG, "Preserving media/sticker notification");
					return Decorating.Unprocessed;
				}
				// Keep raw markers for native WeChat face rendering; translate only at display.

			}
			// 撤回...
			int type = Conversation.TYPE_UNKNOWN;
			String recaller = null;
			boolean is_recall = false;
			if (content != null && content.contains("撤回")) {
				if (CHANNEL_MISC.equals(channel_id)) {	// Misc. notifications on Android 8+.
					return Decorating.Unprocessed;
				} else if (n.tickerText == null) {		// Legacy misc. notifications.
					if (SDK_INT >= O && channel_id == null) setChannelId(n, CHANNEL_MISC);
					Matcher matcher = pattern.matcher(content);
					if (BuildConfig.DEBUG) Log.d(TAG, "matcher " + matcher.matches());
					if (matcher.matches()) {
						// 撤回
						// Log.d(TAG, matcher.group(0) + ", " + matcher.group(1) + ", " + matcher.group(2) + ", " + matcher.group(3));
						is_recall = true;
						recaller = matcher.group("recaller");
						extras.putBoolean(EXTRA_RECALL, true);
						extras.putString(EXTRA_RECALLER, recaller);
					} else {
						return Decorating.Unprocessed;
					}
				}
				if (is_recall) type = (recaller == null) ? Conversation.TYPE_DM_RECALL : Conversation.TYPE_GC_RECALL;
			}
			if (content == null || content.isEmpty()) return Decorating.Unprocessed;

			extras.putCharSequence(Notification.EXTRA_TEXT, content);

			if (type == Conversation.TYPE_UNKNOWN) type = WeChatMessage.guessConversationType(content, n.tickerText != null ? n.tickerText.toString().trim() : "", title);
			if (SDK_INT >= O) {
				if (extras.containsKey(KEY_SILENT_REVIVAL)) {
					setGroup(n, "nevo.group.auto");	// Special group name to let Nevolution auto-group it as if not yet grouped. (To be standardized in SDK)
					setGroupAlertBehavior(n, Notification.GROUP_ALERT_SUMMARY);		// This trick makes notification silent
				}
			}

			// WeChat previously uses dynamic counter starting from 4097 as notification ID, which is reused after cancelled by WeChat itself,
			//   causing conversation duplicate or overwritten notifications.
			final Conversation conversation = mConversationManager.getConversation(id);

			final Icon icon = n.getLargeIcon();
			conversation.icon = icon != null ? IconCompat.createFromIcon(getAppContext(), icon) : null;
			conversation.title = title;
			conversation.summary = content;
			conversation.ticker = n.tickerText;
			conversation.timestamp = n.when;
			if (is_recall)
				conversation.setType((recaller == null) ? Conversation.TYPE_DM_RECALL : Conversation.TYPE_GC_RECALL);
			else if (conversation.getType() == Conversation.TYPE_UNKNOWN)
				conversation.setType(WeChatMessage.guessConversationType(conversation));

			extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
			// if (mPreferences.getBoolean(mPrefKeyWear, false)) n.flags &= ~ Notification.FLAG_LOCAL_ONLY; // TODO


			if (mMessagingBuilder == null) {
				try {
					Log.d(TAG, "mMessagingBuilder is null, initializing...");
					mMessagingBuilder = new MessagingBuilder(getAppContext(), getPackageContext(), this::modifyNotification);
				} catch (final Exception e) {
					Log.w(TAG, "Failed to init mMessagingBuilder: " + e.getMessage());
					return Decorating.Unprocessed;
				}
			}
			Log.d(TAG, "Calling buildFromExtender...");
			MessagingStyle messaging = mMessagingBuilder.buildFromExtender(conversation, id, n, title, getArchivedNotifications(id));
			Log.d(TAG, "buildFromExtender returned: " + (messaging != null ? "non-null" : "null")); // build message from android auto
			if (messaging == null)	// EXTRA_TEXT will be written in buildFromArchive()
				messaging = mMessagingBuilder.buildFromArchive(conversation, n, title, getArchivedNotifications(id));
			if (messaging == null) return Decorating.Unprocessed;
			if (content.contains("[图片]") || content.contains("[表情]") || content.contains("[动画表情]")) {
				Bitmap preview = com.oasisfeng.nevo.xposed.WeChatMedia.preview(n);
                if (preview == null) preview = ChatNotificationRenderer.nativePicture(n);
				if (preview != null || n.extras.getBundle(com.oasisfeng.nevo.xposed.WeChatMedia.SOURCE)!=null) {
					List<MessagingStyle.Message> current = messaging.getMessages();
					for (int index = current.size() - 1; index >= 0; index--) {
						MessagingStyle.Message message = current.get(index);
						if (!message.getExtras().getBoolean("nevo.self", false)) {
							if(preview!=null)message.getExtras().putParcelable("nevo.attachment.preview", preview);
                            Bundle source=n.extras.getBundle(com.oasisfeng.nevo.xposed.WeChatMedia.SOURCE);
                            if(source!=null) message.getExtras().putLong("nevo.attachment.message_id",source.getLong("id"));
                            if(source!=null && source.getParcelable("uri")!=null) {
                                message.getExtras().putParcelable("nevo.attachment.uri",source.getParcelable("uri"));
                                message.getExtras().putString("nevo.attachment.quality",source.getString("quality"));
                                message.getExtras().putString("nevo.attachment.kind",source.getString("kind"));
                                message.getExtras().putBoolean("nevo.attachment.animated",source.getBoolean("animated"));
                            }
							break;
						}
					}
				}
			}
            Bundle voiceSource=n.extras.getBundle(com.oasisfeng.nevo.xposed.WeChatMedia.SOURCE);
            if(voiceSource!=null && voiceSource.getInt("type")==34 && content!=null && content.contains("[语音]")) {
                List<MessagingStyle.Message> voiceMessages=messaging.getMessages();
                for(int index=voiceMessages.size()-1;index>=0;index--) {
                    MessagingStyle.Message message=voiceMessages.get(index);
                    if(message.getExtras().getBoolean("nevo.self",false))continue;
                    message.getExtras().putLong("nevo.attachment.message_id",voiceSource.getLong("id"));
                    message.getExtras().putBundle("nevo.voice",voiceSource.getBundle("voice"));break;
                }
            }
			messaging = mMessagingBuilder.mergeTimeline(id, messaging);
			final List<MessagingStyle.Message> messages = messaging.getMessages();
			if (messages.isEmpty()) return Decorating.Unprocessed;

			// The builder may resolve a previously unknown conversation from its
			// captured WeChat identity. Use that resolved type for the final style.
			final boolean isGroupChat = conversation.isGroupChat();
			messaging.setGroupConversation(isGroupChat).setConversationTitle(title);
			extras.putCharSequence("nevo.conversation.title", title);
			if (isGroupChat && !CHANNEL_DND.equals(channel_id)) setChannelId(n, CHANNEL_GROUP_CONVERSATION);
			else if (channel_id == null) setChannelId(n, CHANNEL_MESSAGE);
			setSortKey(n, String.valueOf(Long.MAX_VALUE - n.when
					+ (isGroupChat ? GROUP_CHAT_SORT_KEY_SHIFT : 0)));
			MessagingBuilder.flatIntoExtras(messaging, extras);
			extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING);

			if (SDK_INT >= N && extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null)
				n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;		// No more alert for direct-replied notification.

			// 维护NotificationChannel
			channel_id = n.getChannelId();
			if (channel_id != null) { // 确保NotificationChannel存在
				NotificationChannel channel = nm.getNotificationChannel(channel_id);
				if (BuildConfig.DEBUG) Log.d(TAG, channel_id + " " + channel);
				if (channel != null) return Decorating.Processed;
				switch (channel_id) {
					case CHANNEL_GROUP_CONVERSATION:
					channel = makeChannel(CHANNEL_GROUP_CONVERSATION, channelGroupMessage, false);
					break;
					case CHANNEL_MESSAGE:
					channel = migrate(nm, OLD_CHANNEL_MESSAGE,	CHANNEL_MESSAGE,	channelMessage, false);
					break;
					case CHANNEL_MISC:
					channel = migrate(nm, OLD_CHANNEL_MISC,		CHANNEL_MISC,		channelMisc, true);
					break;
				}
				if (BuildConfig.DEBUG) Log.d(TAG, channel_id + " " + channel);
				if (channel != null) {
					try {
						nm.createNotificationChannel(channel);
					} catch (Exception e) {
						Log.w(TAG, "Failed to createNotificationChannel: " + channel_id + " " + e.getMessage());
					}
				}
				channel = nm.getNotificationChannel(channel_id);
				if (BuildConfig.DEBUG) Log.d(TAG, channel_id + " " + channel);
			}

			VoiceCall.tweakIfNeeded(getAppContext(), n);
			return Decorating.Processed;
		}

		private void reviveNotificationAfterChannelDeletion(final int id) {
			Log.d(TAG, ("Revive silently: ") + id);
			modifyNotification(id, n -> {
				n.extras.putBoolean(KEY_SILENT_REVIVAL, true);
			});
		}

		@RequiresApi(O) private NotificationChannel migrate(NotificationManager nm, final String old_id, final String new_id, final String new_name, final boolean silent) {
			final NotificationChannel channel_message = nm.getNotificationChannel(old_id);
			nm.deleteNotificationChannel(old_id);
			if (channel_message != null) return cloneChannel(channel_message, new_id, new_name);
			else return makeChannel(new_id, new_name, silent);
		}

		@RequiresApi(O) private NotificationChannel makeChannel(final String channel_id, final String name, final boolean silent) {
			final NotificationChannel channel = new NotificationChannel(channel_id, name, NotificationManager.IMPORTANCE_HIGH/* Allow heads-up (by default) */);
			if (silent) channel.setSound(null, null);
			else channel.setSound(getDefaultSound(), new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
					.setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
			channel.enableLights(true);
			channel.setLightColor(LIGHT_COLOR);
			return channel;
		}

		@RequiresApi(O) private NotificationChannel cloneChannel(final NotificationChannel channel, final String id, final String new_name) {
			final NotificationChannel clone = new NotificationChannel(id, new_name, channel.getImportance());
			clone.setGroup(channel.getGroup());
			clone.setDescription(channel.getDescription());
			clone.setLockscreenVisibility(channel.getLockscreenVisibility());
			clone.setSound(Optional.ofNullable(channel.getSound()).orElse(getDefaultSound()), channel.getAudioAttributes());
			clone.setBypassDnd(channel.canBypassDnd());
			clone.setLightColor(channel.getLightColor());
			clone.setShowBadge(channel.canShowBadge());
			clone.setVibrationPattern(channel.getVibrationPattern());
			return clone;
		}

		@Nullable private Uri getDefaultSound() {	// Before targeting O, WeChat actually plays sound by itself (not via Notification).
			return mWeChatTargetingO ? Settings.System.DEFAULT_NOTIFICATION_URI : null;
		}

		private boolean isWeChatTargeting26OrAbove() {
			try {
				return getPackageManager().getApplicationInfo(WECHAT_PACKAGE, PackageManager.GET_UNINSTALLED_PACKAGES).targetSdkVersion >= O;
			} catch (final PackageManager.NameNotFoundException e) {
				return false;
			}
		}

		private void modifyNotification(final int id, final ModifyNotification... modifies) {
			final Notification n = getArchivedNotification(id);
			if (n == null || n.extras == null) {
				if (BuildConfig.DEBUG) Log.d(TAG, "No archived notification to update: " + id);
				return;
			}
			for (ModifyNotification modify : modifies) modify.modify(n);
			recastNotification(id, n);
		}

	}
}
