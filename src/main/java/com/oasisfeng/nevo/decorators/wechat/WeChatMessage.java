package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.text.TextUtils;
import android.util.Log;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import com.oasisfeng.nevo.xposed.BuildConfig;

import static com.oasisfeng.nevo.decorators.wechat.WeChatDecorator.TAG;

/**
 * Parse various fields collected from notification to build a structural message.
 *
 * Known cases
 * -------------
 *  Direct message with 1 unread	Ticker: "Oasis: Hello",			Title: "Oasis",	Summary: "Hello"
 *  Direct message with >1 unread	Ticker: "Oasis: [Link] WTF",	Title: "Oasis",	Summary: "[2]Oasis: [Link] WTF"
 *  Service message with 1 unread	Ticker: "FedEx: [Link] Status",	Title: "FedEx",	Summary: "[Link] Status"			CarMessage: "[Link] Status"
 *  Service message with >1 unread	Ticker: "FedEx: Delivered",		Title: "FedEx",	Summary: "[2]FedEx: Delivered"		CarMessage: "[Link] Delivered"
 *  Group chat with 1 unread		Ticker: "GroupNick: Hello",		Title: "Group",	Summary: "GroupNick: Hello"			CarMessage: "GroupNick: Hello"
 *  Group chat with >1 unread		Ticker: "GroupNick: [Link] Mm",	Title: "Group",	Summary: "[2]GroupNick: [Link] Mm"	CarMessage: "GroupNick: [Link] Mm"
 *
 * Created by Oasis on 2019-4-19.
 */
class WeChatMessage {

	static final String SENDER_MESSAGE_SEPARATOR = ": ";
	private static final String SELF = "";

	static Message[] buildFromCarConversation(final Conversation conversation, final Notification.CarExtender.UnreadConversation convs, final List<Notification> archive) {
		final String[] car_messages = convs != null ? convs.getMessages() : null;
		if (car_messages == null || car_messages.length == 0) {
			Message basic = buildFromBasicFields(conversation);
			return basic != null ? new Message[] { basic } : new Message[0];
		}

		final CharSequence ticker = conversation.isRecall() || conversation.ticker == null
				? (conversation.summary != null ? conversation.summary : "") : conversation.ticker;
		final int pos = TextUtils.indexOf(ticker, SENDER_MESSAGE_SEPARATOR);
		CharSequence sender = null, text;
		if (pos > 0) {
			sender = ticker.subSequence(0, pos);
			text = ticker.subSequence(pos + SENDER_MESSAGE_SEPARATOR.length(), ticker.length());
		} else text = ticker;
		final java.util.List<Message> messageList = new java.util.ArrayList<>();
		final Notification[] notifications = new Notification[car_messages.length];
		// Log.d(TAG, "archive " + archive.size());
		// for (int i = 0, count = archive.size(); i < count; i++) {
		// 	android.app.Notification n = archive.get(i).getNotification();
		// 	Log.d(TAG, n.tickerText + ", " + n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)+ ", " + n.when);
		// }
		final int archiveSize = archive != null ? archive.size() : 0;
		for (int i = archiveSize - 1, diff = archiveSize - car_messages.length; i >= 0 && i >= diff; i--) {
			notifications[i - diff] =  archive.get(i);
		}
		int end_of_peers = -1;
		if (! conversation.isGroupChat()) for (end_of_peers = car_messages.length - 1; end_of_peers >= -1; end_of_peers --)
			if (end_of_peers >= 0 && TextUtils.equals(text, car_messages[end_of_peers])) break;	// Find the actual end line which matches basic fields, in case extra lines are sent by self
		for (int i = 0, count = car_messages.length; i < count; i ++) {
			Message msg = buildFromCarMessage(conversation, car_messages[i], notifications[i], end_of_peers >= 0 && i > end_of_peers);
			if (msg != null) {  // 过滤掉 null 消息（语音/视频通话）
				messageList.add(msg);
				if (BuildConfig.DEBUG) Log.d(TAG, "buildFromCarMessage " + msg.getDataUri());
			}
		}
		return messageList.toArray(new Message[0]);
	}

	private static Message buildFromBasicFields(final Conversation conversation) {
		// Trim the possible trailing white spaces in ticker.
		if (conversation.summary == null) return null;
		CharSequence ticker = conversation.isRecall() || conversation.ticker == null
				? conversation.summary : conversation.ticker;
		int ticker_length = ticker.length();
		int ticker_end = ticker_length;
		while (ticker_end > 0 && ticker.charAt(ticker_end - 1) == ' ') ticker_end--;
		if (ticker_end != ticker_length) {
			ticker = ticker.subSequence(0, ticker_end);
			ticker_length = ticker_end;
		}

		CharSequence sender = null, text;
		int pos = TextUtils.indexOf(ticker, SENDER_MESSAGE_SEPARATOR), unread_count = 0;
		if (pos > 0) {
			sender = ticker.subSequence(0, pos);
			text = ticker.subSequence(pos + SENDER_MESSAGE_SEPARATOR.length(), ticker_length);
		} else text = ticker;

		final CharSequence summary = conversation.summary;
		final int content_length = summary.length();
		CharSequence content_wo_prefix = summary;
		if (content_length > 3 && summary.charAt(0) == '[' && (pos = TextUtils.indexOf(summary, ']', 1)) > 0) {
			unread_count = parsePrefixAsUnreadCount(summary.subSequence(1, pos));
			if (unread_count > 0) {
				conversation.count = unread_count;
				content_wo_prefix = summary.subSequence(pos + 1, content_length);
			} else if (TextUtils.equals(summary.subSequence(pos + 1, content_length), text))
				conversation.setType(Conversation.TYPE_BOT_MESSAGE);	// Only bot message omits prefix (e.g. "[Link]")
		}

		if (sender == null) {	// No sender in ticker, blindly trust the sender in summary text.
			pos = TextUtils.indexOf(content_wo_prefix, SENDER_MESSAGE_SEPARATOR);
			if (pos > 0) {
				sender = content_wo_prefix.subSequence(0, pos);
				text = content_wo_prefix.subSequence(pos + SENDER_MESSAGE_SEPARATOR.length(), content_wo_prefix.length());
			} else text = content_wo_prefix;
		} else if (! startsWith(content_wo_prefix, sender, SENDER_MESSAGE_SEPARATOR)) {    // Ensure sender matches (in ticker and summary)
			if (unread_count > 0)	// When unread count prefix is present, sender should also be included in summary.
				Log.e(TAG, "Sender mismatch: \"" + sender + "\" in ticker, summary: " + summary.subSequence(0, Math.min(10, content_length)));
			if (startsWith(ticker, sender, SENDER_MESSAGE_SEPARATOR)) {	// Normal case for single unread message
				Message msg = toMessage(conversation, sender, content_wo_prefix, conversation.timestamp);
				return msg != null ? msg : new Message(content_wo_prefix, conversation.timestamp, new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build());
			}
		}
		Message result = toMessage(conversation, sender, text, conversation.timestamp);
		return result != null ? result : new Message(text, conversation.timestamp, new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build());
	}

	/**
	 * Parse unread count prefix in the form of "n" or "n条/則/…".
	 * @return unread count, or 0 if unrecognized as unread count
	 */
	private static int parsePrefixAsUnreadCount(final CharSequence prefix) {
		return UnreadCountPrefix.parseCount(prefix);
	}

	static int guessConversationType(final Conversation conversation) {
		final CharSequence content = conversation.summary;
		final String ticker = conversation.ticker != null ? conversation.ticker.toString().trim() : "";	// Ticker text (may contain trailing spaces) always starts with sender (same as title for direct message, but not for group chat).
		final CharSequence title = conversation.title;
		return guessConversationType(content, ticker, title);
	}

	static int guessConversationType(final CharSequence content, final String ticker, final CharSequence title) {
		if (content == null || ticker == null || ticker.isEmpty() || title == null)
			return Conversation.TYPE_UNKNOWN;
		// Content text includes sender for group and service messages, but not for direct messages.
		final int pos = TextUtils.indexOf(content, ticker.substring(0, Math.min(10, ticker.length())));    // Seek for the first 10 chars of ticker in content.
		if (pos >= 0 && pos <= 6) {        // Max length (up to 999 unread): [999t]
			// The content without unread count prefix, may or may not start with sender nick
			final CharSequence message = pos > 0 && content.charAt(0) == '[' ? content.subSequence(pos, content.length()) : content;
			// message.startsWith(title + SENDER_MESSAGE_SEPARATOR)
			if (startsWith(message, title, SENDER_MESSAGE_SEPARATOR))		// The title of group chat is group name, not the message sender
				return Conversation.TYPE_DIRECT_MESSAGE;	// Most probably a direct message with more than 1 unread
			return Conversation.TYPE_GROUP_CHAT;
		} else if (TextUtils.indexOf(ticker, content) >= 0) {
			return Conversation.TYPE_UNKNOWN;				// Indistinguishable (direct message with 1 unread, or a service text message without link)
		} else return Conversation.TYPE_BOT_MESSAGE;		// Most probably a service message with link
	}

	private static boolean startsWith(final CharSequence text, final CharSequence needle1, @SuppressWarnings("SameParameterValue") final String needle2) {
		if (text == null || needle1 == null) return false;
		final int needle1_length = needle1.length(), needle2_length = needle2.length();
		return text.length() > needle1_length + needle2_length && TextUtils.regionMatches(text, 0, needle1, 0, needle1_length)
				&& TextUtils.regionMatches(text, needle1_length, needle2, 0, needle2_length);
	}

	private static Message buildFromCarMessage(final Conversation conversation, final String message, @Nullable Notification notification, final boolean from_self) {
		if (message == null || message.isEmpty()) return null;
		String text = message, sender = null;
		CharSequence ticker = (notification != null) ? notification.tickerText : null;
		if (ticker == null) ticker = message;
		int pos;
		// parse text
		pos = from_self ? 0 : TextUtils.indexOf(message, SENDER_MESSAGE_SEPARATOR);
		if (pos > 0) {
			sender = message.substring(0, pos);
			final boolean title_as_sender = TextUtils.equals(sender, conversation.title);
			if (conversation.isGroupChat() || title_as_sender) {	// Verify the sender with title for non-group conversation
				text = message.substring(pos + SENDER_MESSAGE_SEPARATOR.length());
			} else sender = null;		// Not really the sender name, revert the parsing result.
		}
		// parse sender (from ticker)
		pos = from_self ? 0 : TextUtils.indexOf(ticker, SENDER_MESSAGE_SEPARATOR);
		if (pos > 0) {
			sender = ticker.toString().substring(0, pos);
			final boolean title_as_sender = TextUtils.equals(sender, conversation.title);
			if (conversation.isGroupChat() || title_as_sender) {	// Verify the sender with title for non-group conversation
			} else sender = null;		// Not really the sender name, revert the parsing result.
		}

		// 修正通话类型：微信 CarExtender 发送 [语音]，但 EXTRA_TEXT 是 [语音通话]
		if (notification != null && notification.extras != null && "[语音]".equals(text)) {
			final CharSequence extraText = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
			if (extraText != null) {
				final String extraTextStr = extraText.toString();
				// 提取 EXTRA_TEXT 中的实际通话类型
				final int start = extraTextStr.lastIndexOf("[");
				final int end = extraTextStr.lastIndexOf("]");
				if (start >= 0 && end > start) {
					final String callType = extraTextStr.substring(start, end + 1);
					if ("[语音通话]".equals(callType) || "[视频通话]".equals(callType)) {
						Log.d(TAG, "Corrected call type from [语音] to " + callType);
						text = callType;
					}
				}
			}
		}

		return toMessage(conversation, from_self ? SELF : sender, text,
				notification != null ? notification.when : conversation.timestamp);
	}


	/**
	 * 检查是否是需要跳过的消息类型
	 * 只跳过通话中的实时状态消息，不跳过语音消息和通话记录
	 *
	 * 区分：
	 * - [语音] = 语音消息（不跳过，正常显示）
	 * - [语音通话] = 语音通话记录（不跳过，正常显示）
	 * - [视频通话] = 视频通话记录（不跳过，正常显示）
	 * - 语音通话中/视频通话中 = 通话中的状态（跳过）
	 */
	private static boolean shouldSkipMessage(final CharSequence text) {
		if (text == null) return false;
		final String textStr = text.toString().trim();
		// 只跳过通话中的实时状态消息
		if (textStr.equals("语音通话中") || textStr.equals("视频通话中") ||
			textStr.equals("[语音通话中]") || textStr.equals("[视频通话中]")) {
			Log.d(TAG, "shouldSkipMessage: skipping in-call status: " + textStr);
			return true;
		}
		// 其他消息（包括 [语音]、[语音通话]、[视频通话]）都不跳过
		return false;
	}

	/** Only an explicit self marker identifies our own message.
	 * A group member may legitimately use the same nickname as the group title. */
	private static boolean isSelfMessage(final Conversation conversation, final @Nullable CharSequence sender) {
		return SELF.equals(sender);
	}

	private static Message toMessage(final Conversation conversation, final @Nullable CharSequence sender, final CharSequence text, final long time) {
		// Missing or malformed car messages must not abort the notification hook.
		if (text == null || text.length() == 0) return null;
		if (shouldSkipMessage(text)) {
			return null;  // 返回 null，由调用方过滤
		}

		final String s = (sender != null) ? sender.toString() : null;
		final Person person;

		// 第二层验证：双层验证判断是否是自己发的消息
		if (isSelfMessage(conversation, sender)) {
			person = null;  // 自己发的消息
		} else if (conversation.isGroupChat()) {
			person = s != null && !s.isEmpty() ? conversation.getGroupParticipant(s, s)
					: new Person.Builder().setName(" ").build();
		} else {
			// 朋友发的消息：用 conversation.title 作为发送者名称
			person = new Person.Builder().setName(conversation.title != null ? conversation.title.toString() : " ").build();
		}

		return new Message(EmojiTranslator.translate(text), time, person);
	}

}
