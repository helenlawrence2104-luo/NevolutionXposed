package com.oasisfeng.nevo.decorators.wechat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.Notification.CarExtender;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import com.oasisfeng.nevo.xposed.PrivateLog;
import java.util.Map;
import android.util.LongSparseArray;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;
import com.oasisfeng.nevo.xposed.MainHook;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import static android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.S;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import static androidx.core.app.NotificationCompat.EXTRA_CONVERSATION_TITLE;
import static androidx.core.app.NotificationCompat.EXTRA_IS_GROUP_CONVERSATION;
import static androidx.core.app.NotificationCompat.EXTRA_MESSAGES;
import static androidx.core.app.NotificationCompat.EXTRA_SELF_DISPLAY_NAME;

import com.oasisfeng.nevo.xposed.BuildConfig;
import com.oasisfeng.nevo.xposed.R;

import static com.oasisfeng.nevo.decorators.wechat.WeChatMessage.SENDER_MESSAGE_SEPARATOR;
import static com.oasisfeng.nevo.sdk.NevoDecoratorService.TEMPLATE_BIG_PICTURE;
import static com.oasisfeng.nevo.sdk.NevoDecoratorService.TEMPLATE_MESSAGING;
import static com.oasisfeng.nevo.sdk.NevoDecoratorService.LocalDecorator.setActions;


/**
 * Build the modernized {@link MessagingStyle} for WeChat conversation.
 *
 * Refactored by Oasis on 2018-8-9.
 */
class MessagingBuilder {

	private static final int MAX_NUM_HISTORICAL_LINES = 10;

	private static final String ACTION_REPLY = "REPLY";
	private static final String ACTION_MENTION = "MENTION";
	private static final String ACTION_ZOOM = "ZOOM";
	private static final String ACTION_SYNTHETIC_REPLY = "SYNTHETIC_REPLY";
	private static final String SCHEME_ID = "id";
	private static final String EXTRA_REPLY_ACTION = "pending_intent";
	private static final String EXTRA_RESULT_KEY = "result_key";
	private static final String EXTRA_REPLY_PREFIX = "reply_prefix";
	private static final String EXTRA_REPLY_TOKEN = "nevo.reply.token";
	private static final String EXTRA_OWN_ACTION = "nevo.reply.owned_action";
	private final ReplySessions mReplySessions = new ReplySessions(android.os.SystemClock::elapsedRealtime);
	private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

	private String replyToken(int id, Notification n) {
		String identity = n.extras.getString("nevo.notification.tag", "") + "\n"
				+ MainHook.getCapturedWeChatUsername(id, n) + "\n"
				+ n.extras.getCharSequence(Notification.EXTRA_TITLE);
		return mReplySessions.issue(id, identity);
	}

	private boolean acceptsReply(Intent intent, int id) {
		return !mClosed && mReplySessions.accepts(id, intent.getStringExtra(EXTRA_REPLY_TOKEN));
	}

	private void replyFailed() {
		mMainHandler.post(() -> {
			try { android.widget.Toast.makeText(mContext, "回复未提交，请打开微信重试", android.widget.Toast.LENGTH_LONG).show(); }
			catch (RuntimeException ignored) { /* Feedback must not crash the host process. */ }
		});
	}

	private void resetReplySpinner(int id) {
		mController.recastNotification(id, notification -> {
			notification.extras.putBoolean("android.remoteInputSpinner", false);
			notification.extras.putCharSequence(Notification.EXTRA_SUB_TEXT, "回复未提交，请打开微信重试");
		});
	}

	private static Bundle ownedActionExtras() {
		Bundle extras = new Bundle();
		extras.putBoolean(EXTRA_OWN_ACTION, true);
		return extras;
	}

	private boolean isOwnReplyAction(Action action) {
		return action.getExtras().getBoolean(EXTRA_OWN_ACTION, false)
				|| mProxyIntents.containsValue(action.actionIntent);
	}

	@Nullable private Integer validatedReplyId(Intent intent) {
		if (intent == null || intent.getData() == null || !SCHEME_ID.equals(intent.getData().getScheme())) return null;
		final int id;
		try { id = Integer.parseInt(intent.getData().getSchemeSpecificPart()); }
		catch (NumberFormatException invalid) { return null; }
		return acceptsReply(intent, id) ? id : null;
	}

	private String pendingReplyKey(Intent intent, CharSequence text) {
		return intent.getStringExtra(EXTRA_REPLY_TOKEN) + "\n" + text;
	}

	private void releaseReplyLater(String key) {
		mMainHandler.postDelayed(() -> mPendingReplies.remove(key), 1000);
	}

	private static final String KEY_TEXT = "text";
	private static final String KEY_TIMESTAMP = "time";
	private static final String KEY_SENDER = "sender";
	@RequiresApi(P) private static final String KEY_SENDER_PERSON = "sender_person";
	private static final String KEY_DATA_MIME_TYPE = "type";
	private static final String KEY_DATA_URI= "uri";
	private static final String KEY_EXTRAS_BUNDLE = "extras";

	private static final String KEY_USERNAME = "key_username";
	private static final String MENTION_SEPARATOR = " ";			// Separator between @nick and text. It's not a regular white space, but U+2005.

	public static int guessType(String key) {
		if (key.endsWith("@chatroom") || key.endsWith("@im.chatroom"/* WeWork */))
			return Conversation.TYPE_GROUP_CHAT;
		if (key.startsWith("gh_"))
			return Conversation.TYPE_BOT_MESSAGE;
		return Conversation.TYPE_DIRECT_MESSAGE;
	}

	/**
	 * 从已存档消息重建会话
	 * 
	 * @param conversation 会话的内存存根
	 * @param n 当前消息
	 * @param title 标题
	 * @param archieve 消息存档
	 * @return 会话的图形化
	 */
	@Nullable MessagingStyle buildFromArchive(final Conversation conversation, final Notification n, final CharSequence title, final List<Notification> archive) {
		// Chat history in big content view
		if (archive.isEmpty()) {
			PrivateLog.d(TAG, "No history");
			return null;
		}

		final LongSparseArray<CharSequence> tickerArray = new LongSparseArray<>(MAX_NUM_HISTORICAL_LINES);
		final LongSparseArray<CharSequence> textArray = new LongSparseArray<>(MAX_NUM_HISTORICAL_LINES);
		CharSequence text;
		int count = 0, num_lines_with_colon = 0;
		final String redundant_prefix = title.toString() + SENDER_MESSAGE_SEPARATOR;
		for (final Notification notification : archive) {
			tickerArray.put(notification.when, notification.tickerText);
			final Bundle its_extras = notification.extras;
			final CharSequence its_title = EmojiTranslator.translate(its_extras.getCharSequence(Notification.EXTRA_TITLE));
			if (! title.equals(its_title)) {
				PrivateLog.d(TAG, "Skip other conversation with the same key in archive: " + its_title);	// ID reset by WeChat due to notification removal in previous evolving
				continue;
			}
			final CharSequence its_text = its_extras.getCharSequence(EXTRA_TEXT);
			if (its_text == null) {
				PrivateLog.w(TAG, "No text in archived notification.");
				continue;
			}
			final int result = trimAndExtractLeadingCounter(its_text);
			if (result >= 0) {
				count = result & 0xFFFF;
				CharSequence trimmed_text = its_text.subSequence(result >> 16, its_text.length());
				if (trimmed_text.toString().startsWith(redundant_prefix))	// Remove redundant prefix
					trimmed_text = trimmed_text.subSequence(redundant_prefix.length(), trimmed_text.length());
				else if (trimmed_text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon ++;
				textArray.put(notification.when, trimmed_text);
			} else {
				count = 1;
				textArray.put(notification.when, text = its_text);
				if (text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon ++;
			}
		}
		n.number = count;
		if (textArray.size() == 0) {
			PrivateLog.w(TAG, "No lines extracted, expected " + count);
			return null;
		}

		final MessagingStyle messaging = new MessagingStyle(MainHook.currentSelfPerson(mContext));
		final boolean sender_inline = num_lines_with_colon == textArray.size();
		for (int i = 0, size = textArray.size(); i < size; i++)	{		// All lines have colon in text
			messaging.addMessage(buildMessage(conversation, textArray.keyAt(i), tickerArray.valueAt(i), textArray.valueAt(i), sender_inline ? null : title.toString()));
		}
		return messaging;
	}

	/**
	 * 从车载扩展信息重建会话
	 */
	@Nullable MessagingStyle buildFromExtender(final Conversation conversation, final int id, final Notification n, final CharSequence title, final List<Notification> archive) {
		if (MainHook.currentSelfPerson(mContext).getIcon() == null && mAvatarRefreshPending.add(id))
			refreshMissingAvatar(id, n, 0);
		final String capturedUsername = MainHook.getCapturedWeChatUsername(id, n);
		if (capturedUsername != null && !capturedUsername.isEmpty()) {
			conversation.key = capturedUsername;
			mConversationUsernames.put(id, capturedUsername);
			conversation.setType(guessType(capturedUsername));
		}
		if (capturedUsername == null || capturedUsername.isEmpty()) {
			mConversationUsernames.remove(id);
			conversation.key = null;
		}
		final Notification.CarExtender extender = new Notification.CarExtender(n);
		final CarExtender.UnreadConversation convs = extender.getUnreadConversation();
		if (convs == null) {
			final MessagingStyle fromActions = buildFromActions(conversation, id, n, title, archive);
			if (fromActions != null) return fromActions;
			return buildWithSyntheticReply(conversation, id, n, title, archive);
		}
		final long latest_timestamp = convs.getLatestTimestamp();
		if (latest_timestamp > 0) n.when = conversation.timestamp = latest_timestamp;

		final PendingIntent on_reply = convs.getReplyPendingIntent();

		final MessagingStyle messaging = new MessagingStyle(MainHook.currentSelfPerson(mContext));
		final Message[] messages = WeChatMessage.buildFromCarConversation(conversation, convs, archive);
		for (final Message message : messages) messaging.addMessage(message.getPerson() == null
				? selfMessage(message.getText(), message.getTimestamp()) : message);

		// 从 EXTRA_MESSAGES 保留之前的用户回复（关键！）
		final Bundle[] existingMessages = n.extras.getParcelableArray(EXTRA_MESSAGES) != null ?
			messageBundles(n.extras) : null;
		boolean hasUserReplyInMessages = false;
		if (existingMessages != null) {
			for (final Bundle msgBundle : existingMessages) {
				final CharSequence msgText = msgBundle.getCharSequence(KEY_TEXT);
				final long msgTimestamp = msgBundle.getLong(KEY_TIMESTAMP, 0);
				final CharSequence msgSender = msgBundle.getCharSequence(KEY_SENDER);
				// 只保留用户自己的回复（sender 为空字符串或 null 且时间戳较新）
				if (msgText != null && isSelfBundle(msgBundle)) {
					PrivateLog.d(TAG, "Preserving user reply from EXTRA_MESSAGES: " + msgText);
					messaging.addMessage(selfMessage(msgText, msgTimestamp));
					hasUserReplyInMessages = true;
				}
			}
		}

		final PendingIntent on_read = convs.getReadPendingIntent();
		if (on_read != null) mMarkReadPendingIntents.put(id, on_read);	// Mapped by evolved key,

		final List<Action> actions = new ArrayList<>();
		// 回复：使用 RemoteInput 内联回复（修改版 HyperIsland 已保留 RemoteInput）
		final RemoteInput remote_input;
		if (SDK_INT >= N && on_reply != null && (remote_input = convs.getRemoteInput()) != null) {
			final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			final PendingIntent proxy = proxyDirectReply(id, n, on_reply, remote_input, input_history, null);
			final RemoteInput.Builder reply_remote_input = new RemoteInput.Builder(remote_input.getResultKey()).addExtras(remote_input.getExtras())
					.setAllowFreeFormInput(true);
			final String participant = convs.getParticipant();
			if (participant != null) reply_remote_input.setLabel(participant);

			final Action.Builder reply_action = new Action.Builder(null, actionReply, proxy).addExtras(ownedActionExtras())
					.addRemoteInput(reply_remote_input.build()).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) reply_action.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			actions.add(reply_action.build());
		} else if (SDK_INT >= N && on_reply != null && MainHook.isInternalReplySupported()
				&& conversation.key != null) {
			final Intent replyIntent = new Intent(ACTION_SYNTHETIC_REPLY).putExtra(EXTRA_REPLY_TOKEN, replyToken(id, n))
					.setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), replyToken(id, n)))
					.setPackage(mContext.getPackageName());
			final PendingIntent replyPendingIntent = rememberReplyIntent(id, PendingIntent.getBroadcast(mContext, id, replyIntent, pendingIntentFlags()));
			final RemoteInput remoteInput = new RemoteInput.Builder("internal_reply_text")
					.setAllowFreeFormInput(true).setLabel(actionReply).build();
			final Action.Builder replyAction = new Action.Builder(null, actionReply, replyPendingIntent).addExtras(ownedActionExtras())
					.addRemoteInput(remoteInput).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) replyAction.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			actions.add(replyAction.build());
		}
		// 放大
		if (n.extras.containsKey(WeChatDecorator.EXTRA_PICTURE_PATH)) {
			final Intent intent = new Intent(ACTION_ZOOM).putExtra(EXTRA_REPLY_TOKEN, replyToken(id, n)).setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), replyToken(id, n)));
			final Action.Builder zoom_action = new Action.Builder(null, actionZoom, PendingIntent.getBroadcast(mContext, 0, intent.setPackage(mContext.getPackageName()), pendingIntentFlags()));
			actions.add(zoom_action.build());
		}
		// 从 EXTRA_REMOTE_INPUT_HISTORY 补充用户回复（仅当 EXTRA_MESSAGES 中没有时）
		if (!hasUserReplyInMessages) {
			final CharSequence[] carInputHistory = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			PrivateLog.d(TAG, "buildFromExtender: EXTRA_REMOTE_INPUT_HISTORY=" + (carInputHistory != null ? carInputHistory.length + " items" : "null"));
			if (carInputHistory != null && carInputHistory.length > 0) {
				for (final CharSequence reply : carInputHistory) {
					PrivateLog.d(TAG, "buildFromExtender: adding user reply: " + reply);
					if (reply != null && reply.length() > 0) {
						messaging.addMessage(selfMessage(reply, System.currentTimeMillis()));
					}
				}
			}
		} else {
			PrivateLog.d(TAG, "buildFromExtender: skipping EXTRA_REMOTE_INPUT_HISTORY, already have user reply from EXTRA_MESSAGES");
		}

		setActions(n, actions.toArray(new Action[actions.size()]));
		return messaging;
	}

	private Message buildMessage(final Conversation conversation, final long when, final @Nullable CharSequence ticker,
										final CharSequence text, @Nullable String sender) {
		CharSequence actual_text = text;
		if (sender == null) {
			sender = extractSenderFromText(text);
			if (sender != null) {
				actual_text = text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length());
				if (TextUtils.equals(conversation.title, sender)) sender = null;		// In this case, the actual sender is user itself.
			}
		}
		// Preserve raw native emoji markers across history and attachment refreshes.

		final Person person;
		if (sender != null && sender.isEmpty()) person = null;		// Empty string as a special mark for "self"
		else if (conversation.isGroupChat()) {
			final String ticker_sender = ticker != null ? extractSenderFromText(ticker) : null;	// Group nick is used in ticker and content text, while original nick in sender.
			person = sender == null ? null : conversation.getGroupParticipant(sender, ticker_sender != null ? ticker_sender : sender);
		} else person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
		return person == null ? selfMessage(actual_text, when) : new Message(actual_text, when, person);
	}

	private static @Nullable String extractSenderFromText(final CharSequence text) {
		final int pos_colon = TextUtils.indexOf(text, SENDER_MESSAGE_SEPARATOR);
		return pos_colon > 0 ? text.toString().substring(0, pos_colon) : null;
	}

	/** @return the extracted count in 0xFF range and start position in 0xFF00 range */
	private static int trimAndExtractLeadingCounter(final CharSequence text) {
		UnreadCountPrefix parsed = UnreadCountPrefix.parse(text);
		if (parsed == null || parsed.count < 2) return -1;
		return (Math.min(parsed.count, 0xFFFF) & 0xFFFF) | (parsed.end << 16);
	}

	/** @return appropriate PendingIntent flags, including FLAG_MUTABLE on Android 12+ for RemoteInput. */
	private static int pendingIntentFlags() {
		return SDK_INT >= S ? (FLAG_UPDATE_CURRENT | FLAG_MUTABLE) : FLAG_UPDATE_CURRENT;
	}

	/** Build reply action directly from notification actions when CarExtender is absent (new WeChat versions). */
	@Nullable private MessagingStyle buildFromActions(final Conversation conversation, final int id, final Notification n, final CharSequence title, final List<Notification> archive) {
		final Action[] actions = n.actions;
		if (actions == null) return null;

		Action replyAction = null;
		RemoteInput replyRemoteInput = null;
		PendingIntent onReply = null;

		for (final Action action : actions) {
			if (action == null || action.actionIntent == null || isOwnReplyAction(action)) continue;
			final RemoteInput[] remoteInputs = action.getRemoteInputs();
			if (remoteInputs != null) {
				for (final RemoteInput ri : remoteInputs) {
					if (ri != null && ri.getAllowFreeFormInput()) {
						replyAction = action;
						replyRemoteInput = ri;
						onReply = action.actionIntent;
						break;
					}
				}
				if (replyAction != null) break;
			}
		}

		if (onReply == null || replyRemoteInput == null) {
			PrivateLog.d(TAG, "No reply action found in notification actions");
			return null;
		}

		PrivateLog.d(TAG, "Found reply action via notification actions fallback");

		final PendingIntent onRead = n.deleteIntent;
		if (onRead != null) mMarkReadPendingIntents.put(id, onRead);

		final MessagingStyle messaging = new MessagingStyle(MainHook.currentSelfPerson(mContext));

		// 检查通知是否已有 EXTRA_MESSAGES（用户回复后重建时）
		final Bundle[] existingMessages = n.extras.getParcelableArray(EXTRA_MESSAGES) != null ?
			messageBundles(n.extras) : null;
		boolean hasUserReplyInMessages = false;

		if (existingMessages != null && existingMessages.length > 0) {
			// 使用已有的消息（包括用户回复）
			for (final Bundle msgBundle : existingMessages) {
				final CharSequence text = msgBundle.getCharSequence(KEY_TEXT);
				final long timestamp = msgBundle.getLong(KEY_TIMESTAMP, 0);
				final CharSequence sender = msgBundle.getCharSequence(KEY_SENDER);
				if (text != null) {
					final Person person;
					if (isSelfBundle(msgBundle)) {
						person = null;  // 自己发的消息（KEY_SENDER 为空字符串）
						hasUserReplyInMessages = true;
					} else if (sender != null && sender.length() > 0) {
						// 有明确的发送者名称
						if (conversation.isGroupChat()) {
							person = conversation.getGroupParticipant(sender.toString(), sender.toString());
						} else {
							person = new Person.Builder().setName(sender.toString()).build();
						}
					} else {
						// KEY_SENDER 为 null，判断是否是朋友发的
						person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
					}
					Message restored=person == null ? selfMessage(text, timestamp) : new Message(text, timestamp, person);
                    Bundle metadata=msgBundle.getBundle(KEY_EXTRAS_BUNDLE);
                    if(metadata!=null)restored.getExtras().putAll(metadata);
                    messaging.addMessage(restored);
				}
			}
		} else if (archive != null && ! archive.isEmpty()) {
			for (final Notification archived : archive) {
				final CharSequence text = archived.extras.getCharSequence(Notification.EXTRA_TEXT);
				if (text != null) {
					final String sender = extractSenderFromText(text);
					final CharSequence msgText = sender != null ? text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length()) : text;
					// 始终用 conversation.title 作为发送者（朋友名字），不返回 null
				final Person person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
					messaging.addMessage(new Message(msgText, archived.when, person));
				}
			}
		} else {
			final CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
			if (text != null) {
				final String sender = extractSenderFromText(text);
				final CharSequence msgText = sender != null ? text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length()) : text;
				// 始终用 conversation.title 作为发送者（朋友名字），不返回 null
				final Person person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
				messaging.addMessage(new Message(msgText, n.when, person));
			}
		}

		final List<Action> newActions = new ArrayList<>();
		final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);

		if (n.extras.containsKey(WeChatDecorator.EXTRA_PICTURE_PATH)) {
			final Intent intent = new Intent(ACTION_ZOOM).putExtra(EXTRA_REPLY_TOKEN, replyToken(id, n)).setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), replyToken(id, n)));
			final Action.Builder zoom_action = new Action.Builder(null, actionZoom,
					PendingIntent.getBroadcast(mContext, 0, intent.setPackage(mContext.getPackageName()), pendingIntentFlags()));
			newActions.add(zoom_action.build());
		}
		// 从 EXTRA_REMOTE_INPUT_HISTORY 获取用户回复并添加到 MessagingStyle（仅当 EXTRA_MESSAGES 中没有时）
		if (!hasUserReplyInMessages && input_history != null && input_history.length > 0) {
			for (final CharSequence reply : input_history) {
				if (reply != null && reply.length() > 0) {
					messaging.addMessage(selfMessage(reply, System.currentTimeMillis()));  // null person = 自己发的
				}
			}
		} else if (hasUserReplyInMessages) {
			PrivateLog.d(TAG, "buildFromActions: skipping EXTRA_REMOTE_INPUT_HISTORY, already have user reply from EXTRA_MESSAGES");
		}

		// 回复：使用 RemoteInput 内联回复（修改版 HyperIsland 已保留 RemoteInput）
		if (onReply != null && replyRemoteInput != null) {
			final PendingIntent proxy = proxyDirectReply(id, n, onReply, replyRemoteInput, input_history, null);
			final RemoteInput.Builder reply_remote_input = new RemoteInput.Builder(replyRemoteInput.getResultKey())
					.addExtras(replyRemoteInput.getExtras()).setAllowFreeFormInput(true);
			final Action.Builder reply_action_builder = new Action.Builder(null, actionReply, proxy).addExtras(ownedActionExtras())
					.addRemoteInput(reply_remote_input.build()).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) reply_action_builder.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			newActions.add(reply_action_builder.build());
		}

		setActions(n, newActions.toArray(new Action[0]));
		return messaging;
	}

	/** Intercept the PendingIntent in RemoteInput to update the notification with replied message upon success. */
	private PendingIntent proxyDirectReply(final int id, final Notification notification, final PendingIntent on_reply, final RemoteInput remote_input,
										   final @Nullable CharSequence[] input_history, final @Nullable String mention_prefix) {
		final Intent proxy = new Intent(mention_prefix != null ? ACTION_MENTION : ACTION_REPLY)		// Separate action to avoid PendingIntent overwrite.
				.putExtra(EXTRA_REPLY_ACTION, on_reply).putExtra(EXTRA_RESULT_KEY, remote_input.getResultKey())
				.putExtra(EXTRA_REPLY_TOKEN, replyToken(id, notification))
				.setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), replyToken(id, notification)));
		if (mention_prefix != null) proxy.putExtra(EXTRA_REPLY_PREFIX, mention_prefix);
		if (SDK_INT >= N && input_history != null)
			proxy.putCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY, new ArrayList<>(Arrays.asList(input_history)));
		return rememberReplyIntent(id, PendingIntent.getBroadcast(mContext, 0, proxy.setPackage(mContext.getPackageName()), pendingIntentFlags()));
	}

	private final Map<Integer, PendingIntent> mProxyIntents = new java.util.concurrent.ConcurrentHashMap<>();

	private PendingIntent rememberReplyIntent(int id, PendingIntent intent) {
		PendingIntent previous = mProxyIntents.put(id, intent);
		if (previous != null && !previous.equals(intent)) previous.cancel();
		if (mProxyIntents.size() > 50) {
			Integer evicted = mProxyIntents.keySet().iterator().next();
			if (evicted != id) forgetConversation(evicted);
		}
		return intent;
	}

	synchronized void forgetConversation(int id) {
		timelines.remove(id);
		timelineAccounts.remove(id);
		mConversationUsernames.remove(id);
		mMarkReadPendingIntents.remove(id);
		mReplySessions.remove(id);
		PendingIntent previous = mProxyIntents.remove(id);
		if (previous != null) previous.cancel();
	}

	private final Set<String> mPendingReplies = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final BroadcastReceiver mReplyReceiver = new BroadcastReceiver() {
		@Override public void onReceive(final Context context, final Intent proxyIntent) {
			try { receiveDirectReply(proxyIntent); }
			catch (RuntimeException malformed) {
				PrivateLog.w(TAG, "Reply request rejected: " + malformed.getClass().getSimpleName());
			}
		}
	};

	private void receiveDirectReply(final Intent proxy_intent) {
		// Validate the capability before decoding an externally supplied Parcelable or RemoteInput payload.
		final Integer validId = validatedReplyId(proxy_intent);
		if (validId == null) return;
		final int replyId = validId;
		final String part = Integer.toString(replyId);
		final PendingIntent reply_action = proxy_intent.getParcelableExtra(EXTRA_REPLY_ACTION);
		final String result_key = proxy_intent.getStringExtra(EXTRA_RESULT_KEY), reply_prefix = proxy_intent.getStringExtra(EXTRA_REPLY_PREFIX);
		if (reply_action == null || result_key == null || !"com.tencent.mm".equals(reply_action.getCreatorPackage())) return;
		// A module proxy is never a valid native destination, even if it has WeChat's creator UID.
		if (mProxyIntents.containsValue(reply_action)) return;
		final Bundle results = RemoteInput.getResultsFromIntent(proxy_intent);
		final CharSequence input = results != null ? results.getCharSequence(result_key) : null;
		if (input == null || input.length() == 0) return;
		final CharSequence text;
		if (reply_prefix != null) {
			text = reply_prefix + input;
			results.putCharSequence(result_key, text);
			RemoteInput.addResultsToIntent(new RemoteInput[]{ new RemoteInput.Builder(result_key).build() }, proxy_intent, results);
		} else text = input;
		final ArrayList<CharSequence> input_history = proxy_intent.getCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY);
		final String replyKey = pendingReplyKey(proxy_intent, text);
		if (!mPendingReplies.add(replyKey)) return;
		boolean dispatched = false;
		try {
			final Intent input_data = addTargetPackageAndWakeUp(reply_action);
			input_data.setClipData(proxy_intent.getClipData());
			RemoteInput.addResultsToIntent(new RemoteInput[]{ new RemoteInput.Builder(result_key).build() }, input_data, results);
			reply_action.send(mContext, 0, input_data, (pendingIntent, intent, _result_code, _result_data, _result_extras) -> {
				try {
				if (BuildConfig.DEBUG) PrivateLog.d(TAG, "Reply PendingIntent dispatched for id=" + part);
				if (SDK_INT >= N && acceptsReply(proxy_intent, replyId)) {
					final CharSequence[] inputs;
					if (input_history != null) {
						input_history.add(0, text);
						inputs = input_history.subList(0, Math.min(5, input_history.size())).toArray(new CharSequence[0]);
					} else inputs = new CharSequence[] { text };
					final int id = Integer.parseInt(part);
					mController.recastNotification(id, n -> {
						// 清除 pre-applied 标记，允许重新处理
						de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField(n, "pre-applied", null);
						de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField(n, "applied", null);
						final Bundle extras = n.extras;
						// 按女娲石源码逻辑添加用户回复
						Bundle[] messages;
						if (extras.getParcelableArray(EXTRA_MESSAGES) != null) {
							messages = messageBundles(extras);
						} else {
							messages = new Bundle[0];
						}
						Bundle[] new_messages = new Bundle[messages.length + 1];
						System.arraycopy(messages, 0, new_messages, 0, messages.length);
						Bundle userMessage = new Bundle();
						userMessage.putCharSequence(KEY_TEXT, text);
						userMessage.putLong(KEY_TIMESTAMP, System.currentTimeMillis());
						// Empty sender is the explicit self-message marker used by the parser.
						userMessage.putCharSequence(KEY_SENDER, "");
						new_messages[messages.length] = userMessage;
						extras.putParcelableArray(EXTRA_MESSAGES, new_messages.length <= 20 ? new_messages : Arrays.copyOfRange(new_messages, new_messages.length - 20, new_messages.length));
						extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, inputs);
					});
					markRead(id);
				}
				} catch (RuntimeException failure) {
					// Dispatch already happened; a failed local echo must not cause a resend or crash.
					PrivateLog.w(TAG, "Reply display update skipped: " + failure.getClass().getSimpleName());
				}
			}, null);
			dispatched = true;
		} catch (PendingIntent.CanceledException | RuntimeException failure) {
			PrivateLog.w(TAG, "Reply not submitted: " + failure.getClass().getSimpleName());
			replyFailed();
		} finally {
			if (dispatched) releaseReplyLater(replyKey);
			else mPendingReplies.remove(replyKey);
		}
	}

	private final BroadcastReceiver mZoomReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent proxy_intent) {
		final String action = proxy_intent.getAction();
		final Uri data = proxy_intent.getData(); final Bundle results = RemoteInput.getResultsFromIntent(proxy_intent);
		if (data == null) return;
		final String key = data.getSchemeSpecificPart();
		try { if (!acceptsReply(proxy_intent, Integer.parseInt(key))) return; }
		catch (NumberFormatException invalid) { return; }
		// final Bundle addition = new Bundle();
		mController.recastNotification(Integer.parseInt(key), n -> {
			final Bundle extras = n.extras;
			if (BuildConfig.DEBUG) PrivateLog.d(TAG, "bitmap " + extras.getParcelable(Notification.EXTRA_PICTURE));
			if (TEMPLATE_MESSAGING.equals(extras.getString(Notification.EXTRA_TEMPLATE))) {
				final String path = extras.getString(WeChatDecorator.EXTRA_PICTURE_PATH);
				final BitmapFactory.Options options = new BitmapFactory.Options();
				options.inPreferredConfig = SDK_INT >= O ? Bitmap.Config.HARDWARE : Bitmap.Config.ARGB_8888;
				// extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_PICTURE);
				extras.putParcelable(Notification.EXTRA_PICTURE, BitmapFactory.decodeFile(path, options));
				// extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, text);
				extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_PICTURE);
			} else {
				extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING); // TODO
			}
			if (BuildConfig.DEBUG) PrivateLog.d(TAG, "bitmap " + extras.getParcelable(Notification.EXTRA_PICTURE));
		});
	} };

	/** @param id the notification id */
	void markRead(final int id) {
		final PendingIntent action = mMarkReadPendingIntents.remove(id);
		if (action == null) return;
		try {
			action.send(mContext, 0, addTargetPackageAndWakeUp(action));
		} catch (final PendingIntent.CanceledException e) {
			PrivateLog.w(TAG, "Mark-read action is already cancelled: " + id);
		}
	}

	/** Ensure the PendingIntent works even if WeChat is stopped or background-restricted. */
	@NonNull private static Intent addTargetPackageAndWakeUp(final PendingIntent action) {
		return new Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setPackage(action.getCreatorPackage());
	}

	static void flatIntoExtras(final MessagingStyle messaging, final Bundle extras) {
		final Person user = messaging.getUser();
		if (user != null) {
			extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, user.getName());
			if (SDK_INT >= P) extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON, toAndroidPerson(user));	// Not included in NotificationCompat
		}
		if (messaging.getConversationTitle() != null) extras.putCharSequence(EXTRA_CONVERSATION_TITLE, messaging.getConversationTitle());
		final List<Message> messages = messaging.getMessages();
		// PrivateLog.d(TAG, "messages " + messages.size());
		if (! messages.isEmpty()) extras.putParcelableArray(EXTRA_MESSAGES, getBundleArrayForMessages(messages));
		//if (! mHistoricMessages.isEmpty()) extras.putParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, MessagingBuilder.getBundleArrayForMessages(mHistoricMessages));
		extras.putBoolean(EXTRA_IS_GROUP_CONVERSATION, messaging.isGroupConversation());
	}

	private static boolean isSelfBundle(Bundle message) {
		Bundle extras = message.getBundle(KEY_EXTRAS_BUNDLE);
		CharSequence sender = message.getCharSequence(KEY_SENDER);
		return (extras != null && extras.getBoolean("nevo.self", false)) || (sender != null && sender.length() == 0);
	}

	private final java.util.Map<Integer, String> timelineAccounts = new java.util.HashMap<>();
	private final java.util.Map<Integer, java.util.LinkedHashMap<String, Message>> timelines = new java.util.LinkedHashMap<Integer, java.util.LinkedHashMap<String, Message>>(16, .75f, true) {
		@Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, java.util.LinkedHashMap<String, Message>> entry) {
			if (size() <= 50) return false;
			timelineAccounts.remove(entry.getKey()); return true;
		}
	};

	synchronized MessagingStyle mergeTimeline(int id, MessagingStyle current) {
		String currentAccount = current.getUser().getKey();
		if (!java.util.Objects.equals(timelineAccounts.get(id), currentAccount)) timelines.remove(id);
		timelineAccounts.put(id, currentAccount);
		java.util.LinkedHashMap<String, Message> timeline = timelines.get(id);
		if (timeline == null) { timeline = new java.util.LinkedHashMap<>(); timelines.put(id, timeline); }
		for (Message message : current.getMessages()) {
			boolean self = message.getPerson() == null || message.getExtras().getBoolean("nevo.self", false);
			String key = self + ":" + message.getTimestamp() + ":" + message.getText();
            Message previous=timeline.get(key);
            if(previous!=null && !message.getExtras().containsKey("nevo.voice") && previous.getExtras().containsKey("nevo.voice"))
                message.getExtras().putBundle("nevo.voice",previous.getExtras().getBundle("nevo.voice"));
            if(previous!=null && !message.getExtras().containsKey("nevo.attachment.message_id") && previous.getExtras().containsKey("nevo.attachment.message_id"))message.getExtras().putLong("nevo.attachment.message_id",previous.getExtras().getLong("nevo.attachment.message_id"));
            if(previous!=null && !message.getExtras().containsKey("nevo.attachment.preview")
                    && previous.getExtras().containsKey("nevo.attachment.preview"))
                message.getExtras().putParcelable("nevo.attachment.preview",previous.getExtras().getParcelable("nevo.attachment.preview"));
            if(previous!=null && !message.getExtras().containsKey("nevo.attachment.uri") && previous.getExtras().containsKey("nevo.attachment.uri")) {
                message.getExtras().putParcelable("nevo.attachment.uri",previous.getExtras().getParcelable("nevo.attachment.uri"));
                message.getExtras().putString("nevo.attachment.quality",previous.getExtras().getString("nevo.attachment.quality"));
                message.getExtras().putString("nevo.attachment.kind",previous.getExtras().getString("nevo.attachment.kind"));
                message.getExtras().putBoolean("nevo.attachment.animated",previous.getExtras().getBoolean("nevo.attachment.animated"));
            }
			timeline.put(key, message);
		}
		java.util.List<Message> ordered = new java.util.ArrayList<>(timeline.values());
		ordered.sort(java.util.Comparator.comparingLong(Message::getTimestamp));
		if (ordered.size() > 20) ordered = new java.util.ArrayList<>(ordered.subList(ordered.size() - 20, ordered.size()));
		for (int i=0; i<ordered.size()-4; i++) ordered.get(i).getExtras().remove("nevo.attachment.preview");
		timeline.clear();
		MessagingStyle result = new MessagingStyle(MainHook.currentSelfPerson(mContext));
		for (Message message : ordered) {
			boolean self = message.getPerson() == null || message.getExtras().getBoolean("nevo.self", false);
			Message rendered = self ? selfMessage(message.getText(), message.getTimestamp()) : message;
			timeline.put(self + ":" + message.getTimestamp() + ":" + message.getText(), rendered);
			result.addMessage(rendered);
		}
		return result;
	}

	private Message selfMessage(CharSequence text, long timestamp) {
		Message message = new Message(text, timestamp, MainHook.currentSelfPerson(mContext).toBuilder().setName("").build());
		message.getExtras().putBoolean("nevo.self", true);
		return message;
	}

	private final Set<Integer> mAvatarRefreshPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private void refreshMissingAvatar(final int id, final Notification source, final int attempt) {
		if (mClosed || attempt >= 5) { mAvatarRefreshPending.remove(id); return; }
		final String expectedUser = MainHook.getCapturedWeChatUsername(id, source);
		final String expectedTag = source.extras.getString("nevo.notification.tag", "");
		mMainHandler.postDelayed(() -> {
			if (mClosed) { mAvatarRefreshPending.remove(id); return; }
			try {
				NotificationManager manager = mContext.getSystemService(NotificationManager.class);
				boolean active = false;
				for (StatusBarNotification notification : manager.getActiveNotifications()) {
					String tag = notification.getTag() == null ? "" : notification.getTag();
					if (notification.getId() == id && expectedTag.equals(tag)
							&& TextUtils.equals(expectedUser, MainHook.getCapturedWeChatUsername(id, notification.getNotification()))) {
						active = true; break;
					}
				}
				if (!active) { mAvatarRefreshPending.remove(id); return; }
				if (MainHook.currentSelfPerson(mContext).getIcon() != null) {
					mAvatarRefreshPending.remove(id);
					mController.recastNotification(id);
				} else refreshMissingAvatar(id, source, attempt + 1);
			} catch (RuntimeException unavailable) { mAvatarRefreshPending.remove(id); }
		}, 1000L + attempt * 500L);
	}

	private static Bundle[] messageBundles(Bundle extras) {
		android.os.Parcelable[] values = extras.getParcelableArray(EXTRA_MESSAGES);
		if (values == null) return new Bundle[0];
		List<Bundle> bundles = new ArrayList<>();
		for (android.os.Parcelable value : values) if (value instanceof Bundle) bundles.add((Bundle) value);
		return bundles.toArray(new Bundle[0]);
	}

	private static Bundle[] getBundleArrayForMessages(final List<Message> messages) {
		final int N = messages.size();
		final Bundle[] bundles = new Bundle[N];
		for (int i = 0; i < N; i ++) bundles[i] = toBundle(messages.get(i));
		return bundles;
	}

	private static Bundle toBundle(final Message message) {
		final Bundle bundle = new Bundle();
		bundle.putCharSequence(KEY_TEXT, message.getText());
		// PrivateLog.d(TAG, "message.text " + message.getText());
		bundle.putLong(KEY_TIMESTAMP, message.getTimestamp());		// Must be included even for 0
		final Person sender = message.getPerson();
		if (sender != null) {
			bundle.putCharSequence(KEY_SENDER, message.getExtras().getBoolean("nevo.self", false) ? "" : sender.getName());	// Legacy listeners need this
			if (SDK_INT >= P) bundle.putParcelable(KEY_SENDER_PERSON, toAndroidPerson(sender));
		} else bundle.putCharSequence(KEY_SENDER, "");
		if (message.getDataMimeType() != null) bundle.putString(KEY_DATA_MIME_TYPE, message.getDataMimeType());
		if (message.getDataUri() != null) bundle.putParcelable(KEY_DATA_URI, message.getDataUri());
		if (SDK_INT >= O && ! message.getExtras().isEmpty()) bundle.putBundle(KEY_EXTRAS_BUNDLE, message.getExtras());
		//if (message.isRemoteInputHistory()) bundle.putBoolean(KEY_REMOTE_INPUT_HISTORY, message.isRemoteInputHistory());
		return bundle;
	}

	@RequiresApi(P) @SuppressLint("RestrictedApi") private static android.app.Person toAndroidPerson(final Person user) {
		return user.toAndroidPerson();
	}

	interface Controller { void recastNotification(int id, WeChatDecorator.ModifyNotification... modifies); }

	MessagingBuilder(final Context context, final Context packageContext, /* final SharedPreferences preferences,  */final Controller controller) {
		mContext = context;
		// Try to load resources from module APK
		Context moduleContext = null;
		try {
			moduleContext = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
		} catch (final Exception e) {
			PrivateLog.w(TAG, "Failed to create module context: " + e.getMessage());
		}
		final Context pkgCtx = moduleContext != null ? moduleContext : context;
		PrivateLog.d(TAG, "pkgCtx=" + pkgCtx + " moduleContext=" + moduleContext);
		actionReply = "回复";
		actionZoom = "缩放";
		mController = controller;
		// Module resources can be hidden from the injected WeChat process by
		// Android package visibility. This label has no reason to depend on a
		// foreign package Context and must never abort decorator initialization.
		String selfName = "我";
		mUserSelf = new Person.Builder().setName(selfName).build();

		{
			final IntentFilter filter = new IntentFilter(ACTION_REPLY); filter.addAction(ACTION_MENTION); filter.addDataScheme(SCHEME_ID);
			// The PendingIntent is fired across the SystemUI process boundary. HyperOS 3 does not
			// deliver it to a NOT_EXPORTED dynamic receiver. The receiver still validates the
			// embedded WeChat-owned reply PendingIntent before doing any work.
			androidx.core.content.ContextCompat.registerReceiver(context, mReplyReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
		}
		{
			final IntentFilter filter = new IntentFilter(ACTION_SYNTHETIC_REPLY); filter.addDataScheme(SCHEME_ID);
			androidx.core.content.ContextCompat.registerReceiver(context, mSyntheticReplyReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
		}
		{
			final IntentFilter filter = new IntentFilter(ACTION_ZOOM); filter.addDataScheme(SCHEME_ID);
			androidx.core.content.ContextCompat.registerReceiver(context, mZoomReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
		}
	}

	@Nullable private MessagingStyle buildWithSyntheticReply(final Conversation conversation, final int id, final Notification n, final CharSequence title, final List<Notification> archive) {
		final PendingIntent contentIntent = n.contentIntent;
		if (contentIntent == null) {
			PrivateLog.d(TAG, "No contentIntent for synthetic reply");
			return null;
		}
		PrivateLog.d(TAG, "Building synthetic reply action for notification " + id);
		final MessagingStyle messaging = new MessagingStyle(MainHook.currentSelfPerson(mContext));

		// 检查通知是否已有 EXTRA_MESSAGES（用户回复后重建时）
		final Bundle[] existingMessages = n.extras.getParcelableArray(EXTRA_MESSAGES) != null ?
			messageBundles(n.extras) : null;
		boolean hasUserReplyInMessages = false;

		if (existingMessages != null && existingMessages.length > 0) {
			// 使用已有的消息（包括用户回复）
			for (final Bundle msgBundle : existingMessages) {
				final CharSequence text = msgBundle.getCharSequence(KEY_TEXT);
				final long timestamp = msgBundle.getLong(KEY_TIMESTAMP, 0);
				final CharSequence sender = msgBundle.getCharSequence(KEY_SENDER);
				if (text != null) {
					final Person person;
					if (isSelfBundle(msgBundle)) {
						person = null;  // 自己发的消息（KEY_SENDER 为空字符串）
						hasUserReplyInMessages = true;
					} else if (sender != null && sender.length() > 0) {
						// 有明确的发送者名称
						if (conversation.isGroupChat()) {
							person = conversation.getGroupParticipant(sender.toString(), sender.toString());
						} else {
							person = new Person.Builder().setName(sender.toString()).build();
						}
					} else {
						// KEY_SENDER 为 null，判断是否是朋友发的
						person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
					}
					Message restored=person == null ? selfMessage(text, timestamp) : new Message(text, timestamp, person);
                    Bundle metadata=msgBundle.getBundle(KEY_EXTRAS_BUNDLE);
                    if(metadata!=null)restored.getExtras().putAll(metadata);
                    messaging.addMessage(restored);
				}
			}
		} else if (archive != null && ! archive.isEmpty()) {
			for (final Notification archived : archive) {
				final CharSequence text = archived.extras.getCharSequence(Notification.EXTRA_TEXT);
				if (text != null) {
					final String sender = extractSenderFromText(text);
					final CharSequence msgText = sender != null ? text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length()) : text;
					// 始终用 conversation.title 作为发送者（朋友名字），不返回 null
				final Person person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
					messaging.addMessage(new Message(msgText, archived.when, person));
				}
			}
		} else {
			final CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
			if (text != null) {
				final String sender = extractSenderFromText(text);
				final CharSequence msgText = sender != null ? text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length()) : text;
				// 始终用 conversation.title 作为发送者（朋友名字），不返回 null
				final Person person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
				messaging.addMessage(new Message(msgText, n.when, person));
			}
		}
		// 从 EXTRA_REMOTE_INPUT_HISTORY 获取用户回复并添加到 MessagingStyle（仅当 EXTRA_MESSAGES 中没有时）
		if (!hasUserReplyInMessages) {
			final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			if (input_history != null && input_history.length > 0) {
				for (final CharSequence reply : input_history) {
					if (reply != null && reply.length() > 0) {
						messaging.addMessage(selfMessage(reply, System.currentTimeMillis()));  // null person = 自己发的
					}
				}
			}
		} else {
			PrivateLog.d(TAG, "buildWithSyntheticReply: skipping EXTRA_REMOTE_INPUT_HISTORY, already have user reply from EXTRA_MESSAGES");
		}

		final String username = MainHook.getCapturedWeChatUsername(id, n);
		if (username != null && !username.isEmpty() && MainHook.isInternalReplySupported()) {
			conversation.key = username;
			mConversationUsernames.put(id, username);
			final Intent replyIntent = new Intent(ACTION_SYNTHETIC_REPLY).putExtra(EXTRA_REPLY_TOKEN, replyToken(id, n))
					.setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), replyToken(id, n))).setPackage(mContext.getPackageName());
			final PendingIntent replyPendingIntent = rememberReplyIntent(id, PendingIntent.getBroadcast(mContext, id, replyIntent, pendingIntentFlags()));
			final RemoteInput remoteInput = new RemoteInput.Builder("internal_reply_text")
					.setAllowFreeFormInput(true).setLabel(actionReply).build();
			final Action.Builder replyAction = new Action.Builder(null, actionReply, replyPendingIntent).addExtras(ownedActionExtras())
					.addRemoteInput(remoteInput).setAllowGeneratedReplies(true);
			if (SDK_INT >= P) replyAction.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			setActions(n, new Action[] { replyAction.build() });
		} else {
			setActions(n, new Action[0]);
			PrivateLog.w(TAG, "Internal reply omitted: notification username unavailable");
		}
		return messaging;
	}

	private final BroadcastReceiver mSyntheticReplyReceiver = new BroadcastReceiver() {
		@Override public void onReceive(final Context context, final Intent proxyIntent) {
			MainHook.recordReplyState("synthetic-received");
			try { receiveSyntheticReply(context, proxyIntent); }
			catch (RuntimeException malformed) {
				MainHook.recordReplyState("synthetic-error:" + malformed.getClass().getSimpleName());
				PrivateLog.w(TAG, "Synthetic request rejected: " + malformed.getClass().getSimpleName());
			}
		}
	};

	private void receiveSyntheticReply(final Context context, final Intent proxy_intent) {
		final Integer validId = validatedReplyId(proxy_intent);
		if (validId == null) { MainHook.recordReplyState("token-rejected"); return; }
		MainHook.recordReplyState("token-accepted");
		final int notif_id = validId;
		final Bundle results = RemoteInput.getResultsFromIntent(proxy_intent);
		if (results == null) { MainHook.recordReplyState("remote-input-missing"); return; }
		final CharSequence input = results.getCharSequence("internal_reply_text");
		if (input == null || input.length() == 0) { MainHook.recordReplyState("reply-key-missing"); return; }
		final String reply_text = input.toString();
		final String username = mConversationUsernames.get(notif_id);
		if (username == null || username.isEmpty()) { MainHook.recordReplyState("recipient-missing"); replyFailed(); return; }
		final String replyKey = pendingReplyKey(proxy_intent, reply_text);
		if (!mPendingReplies.add(replyKey)) return;
		boolean submitted = false;
		try {
			com.oasisfeng.nevo.xposed.WeChatReadState.Snapshot readBefore = com.oasisfeng.nevo.xposed.WeChatReadState.capture(context, username);
			if (!MainHook.invokeInternalWeChatSend(context, username, reply_text)) { replyFailed(); return; }
			submitted = true;
			com.oasisfeng.nevo.xposed.WeChatReadState.markAfterSubmission(context, username, readBefore);
		final String finalReplyText = reply_text;
		mController.recastNotification(notif_id, n -> {
			// 清除 pre-applied 标记，允许重新处理
			de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField(n, "pre-applied", null);
			de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField(n, "applied", null);
			final Bundle extras = n.extras;
			// 按女娲石源码逻辑添加用户回复
			Bundle[] messages;
			if (extras.getParcelableArray(EXTRA_MESSAGES) != null) {
				messages = messageBundles(extras);
			} else {
				messages = new Bundle[0];
			}
			Bundle[] new_messages = new Bundle[messages.length + 1];
			System.arraycopy(messages, 0, new_messages, 0, messages.length);
			Bundle userMessage = new Bundle();
			userMessage.putCharSequence(KEY_TEXT, finalReplyText);
			userMessage.putLong(KEY_TIMESTAMP, System.currentTimeMillis());
			// Empty sender is the explicit self-message marker used by the parser.
			userMessage.putCharSequence(KEY_SENDER, "");
			new_messages[messages.length] = userMessage;
			extras.putParcelableArray(EXTRA_MESSAGES, new_messages.length <= 20 ? new_messages : Arrays.copyOfRange(new_messages, new_messages.length - 20, new_messages.length));
			// 添加到输入历史
			final CharSequence[] history = extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			final CharSequence[] new_history;
			if (history != null) {
				new_history = new CharSequence[history.length + 1];
				System.arraycopy(history, 0, new_history, 0, history.length);
				new_history[history.length] = finalReplyText;
			} else new_history = new CharSequence[] { finalReplyText };
			extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, new_history.length <= 5 ? new_history : Arrays.copyOfRange(new_history, new_history.length - 5, new_history.length));
		});
		} catch (RuntimeException failure) {
			PrivateLog.w(TAG, submitted ? "Reply display update skipped" : "Reply not submitted");
			if (!submitted) replyFailed();
		} finally {
			if (submitted) releaseReplyLater(replyKey);
			else { mPendingReplies.remove(replyKey); resetReplySpinner(notif_id); }
		}
	}

	// M4: 跟踪注册状态，防止重复注销或泄漏
	private volatile boolean mClosed = false;

	void close() {
		if (mClosed) return;
		mClosed = true;
		try { mContext.unregisterReceiver(mReplyReceiver); } catch (final RuntimeException ignored) {}
		try { mContext.unregisterReceiver(mSyntheticReplyReceiver); } catch (final RuntimeException ignored) {}
		try { mContext.unregisterReceiver(mZoomReceiver); } catch (final RuntimeException ignored) {}
		mMarkReadPendingIntents.clear();
		mPendingReplies.clear();
		mAvatarRefreshPending.clear();
		timelines.clear();
		timelineAccounts.clear();
		mReplySessions.clear();
		mMainHandler.removeCallbacksAndMessages(null);
		mConversationUsernames.clear();
		for (PendingIntent intent : mProxyIntents.values()) intent.cancel();
		mProxyIntents.clear();
	}

	private final Context mContext;
	private final String actionReply, actionZoom;
	// private final SharedPreferences mPreferences;
	private final Controller mController;
	private final Person mUserSelf;
	private final Map<Integer, PendingIntent> mMarkReadPendingIntents = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<Integer, String> mConversationUsernames = new java.util.concurrent.ConcurrentHashMap<>();
	private static final String TAG = WeChatDecorator.TAG;
}
