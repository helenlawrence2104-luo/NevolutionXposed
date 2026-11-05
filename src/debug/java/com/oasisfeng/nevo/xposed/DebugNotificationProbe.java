package com.oasisfeng.nevo.xposed;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/** Debug-only, shell-triggered local notifications. Never routes or sends a chat message. */
public final class DebugNotificationProbe {
    private static final String LOG_TAG = "NevoProbe";
    private static final String ACTION = "com.oasisfeng.nevo.xposed.DEBUG_PROBE";
    private static final String CHANNEL = "nevo_debug_probe";
    private static final String NOTIFICATION_TAG = "nevo-probe";
    private static final int NOTIFICATION_ID = 190001;
    private static final String[] CASES = { "counter", "malformed", "text", "image", "call", "overwrite" };
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Context sContext;
    private static BroadcastReceiver sReceiver;
    // Accessed only on the main thread, including receiver delivery and delayed callbacks.
    private static boolean sRunning;

    private DebugNotificationProbe() {}

    public static synchronized void install(Context context) {
        if (!BuildConfig.DEBUG || context == null || sReceiver != null) return;
        final Context application = context.getApplicationContext();
        final Context owner = application != null ? application : context;
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context unused, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction())) return;
                try {
                    final String selected = intent.getStringExtra("case");
                    if ("avatar".equals(selected)) {
                        setResultCode(1);
                        setResultData(inspectAvatar(intent.getIntExtra("id", 190002)));
                        return;
                    }
                    if ("adapter".equals(selected)) {
                        setResultCode(1);
                        setResultData(MainHook.diagnoseAdapter(owner));
                        return;
                    }
                    if ("fixture".equals(selected)) {
                        createReplyFixture(intent);
                        setResultCode(1);
                        setResultData("local reply fixture created; no chat sent");
                        return;
                    }
                    if ("clear_fixture".equals(selected)) {
                        owner.getSystemService(NotificationManager.class).cancel("nevo-reply-fixture", 190002);
                        return;
                    }
                    if ("status".equals(selected)) {
                        setResultCode(1);
                        setResultData(MainHook.describeRuntime()+";read="+WeChatReadState.status()+";media="+WeChatMedia.status()+";imageDownload="+WeChatImageDownloads.status()+";stickerDownload="+WeChatStickerDownloads.status());
                        return;
                    }
                    if ("voice".equals(selected)) {setResultCode(1);setResultData(WeChatVoice.inspectRecent(owner,intent.getStringExtra("username"),intent.getBooleanExtra("play",false)));return;}
                    if ("download_sticker".equals(selected)) { setResultCode(1);setResultData(WeChatMedia.requestRecentSticker(owner,intent.getStringExtra("username")));return; }
                    if ("download_media".equals(selected)) { setResultCode(1);setResultData(WeChatMedia.requestRecent(owner,intent.getStringExtra("username")));return; }
                    if ("media".equals(selected)) {
                        setResultCode(1); setResultData(WeChatMedia.inspect(owner,intent.getStringExtra("username"))); return;
                    }
                    if ("unread".equals(selected)) {
                        WeChatReadState.capture(owner,intent.getStringExtra("username"));
                        setResultCode(1);
                        setResultData(WeChatReadState.status());
                        return;
                    }
                    if ("clear".equals(selected)) {
                        if (!sRunning) cancelProbe();
                        return;
                    }
                    if (sRunning) {
                        Log.i(LOG_TAG, "event=busy");
                        return;
                    }
                    if (selected == null || "all".equals(selected)) {
                        sRunning = true;
                        runPhase(CASES, 0, true);
                    } else {
                        for (String allowed : CASES) {
                            if (allowed.equals(selected)) {
                                sRunning = true;
                                // A selected single case remains visible for manual UI inspection.
                                runPhase(new String[] { allowed }, 0, false);
                                return;
                            }
                        }
                        Log.i(LOG_TAG, "event=unknown_case");
                    }
                } catch (RuntimeException | LinkageError error) {
                    sRunning = false;
                    logFailure("receive", error);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ACTION);
            // Exported delivery is restricted to a caller holding the privileged DUMP permission.
            androidx.core.content.ContextCompat.registerReceiver(owner, receiver, filter,
                    Manifest.permission.DUMP, HANDLER, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
            sContext = owner;
            sReceiver = receiver;
            Log.i(LOG_TAG, "event=installed permission=android.permission.DUMP");
        } catch (RuntimeException | LinkageError error) {
            logFailure("install", error);
        }
    }

    private static String inspectAvatar(int id) {
        if (Build.VERSION.SDK_INT < 28) return "avatar diagnostics require API 28";
        androidx.core.app.Person profile = MainHook.currentSelfPerson(sContext);
        StringBuilder result = new StringBuilder(SelfAvatarProvider.status()).append(";profileIcon=").append(profile.getIcon() != null);
        if (profile.getIcon() != null) {
            android.graphics.drawable.Drawable drawable = profile.getIcon().toIcon(sContext).loadDrawable(sContext);
            if (drawable != null) {
                android.graphics.Bitmap preview = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, 128, 128);
                drawable.draw(new android.graphics.Canvas(preview));
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(sContext.getCacheDir(), "nevo-current-self.png"))) {
                    preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException ignored) { result.append(";preview-write-failed"); }
            }
        }
        for (StatusBarNotification active : sContext.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (active.getId() != id) continue;
            android.os.Bundle extras = active.getNotification().extras;
            android.app.Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
            result.append(";notificationUserIcon=").append(user != null && user.getIcon() != null);
            android.os.Parcelable[] messages = extras.getParcelableArray("android.messages");
            if (messages != null) for (android.os.Parcelable value : messages) {
                if (!(value instanceof android.os.Bundle)) continue;
                android.os.Bundle message = (android.os.Bundle) value;
                CharSequence sender = message.getCharSequence("sender");
                if (sender == null || sender.length() != 0) continue;
                android.app.Person person = message.getParcelable("sender_person");
                result.append(";selfMessageIcon=").append(person != null && person.getIcon() != null);
            }
        }
        return result.toString();
    }

    @android.annotation.SuppressLint("NotificationPermission")
    private static void createReplyFixture(Intent command) {
        String username = command.getStringExtra("username");
        String title = command.getStringExtra("title");
        if (username == null || !username.startsWith("wxid_") || title == null || title.isEmpty()) return;
        Context context = sContext;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Nevo local diagnostics", NotificationManager.IMPORTANCE_LOW));
        Intent chat = new Intent().setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                .putExtra("Chat_User", username).putExtra("Main_User", username);
        android.app.PendingIntent content = android.app.PendingIntent.getActivity(context, 190002, chat,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification fixture = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title)
                .setContentText("插件回复测试（本地通知）").setContentIntent(content)
                .setWhen(System.currentTimeMillis()).setOnlyAlertOnce(true).build();
        if (command.getBooleanExtra("self_preview", false)) fixture.extras.putCharSequenceArray(
                Notification.EXTRA_REMOTE_INPUT_HISTORY, new CharSequence[] { "自己的头像显示测试（未发送）" });
        manager.notify("nevo-reply-fixture", 190002, fixture);
    }

    @android.annotation.SuppressLint("NotificationPermission") // Uses WeChat's already granted posting permission.
    private static void runPhase(final String[] phases, final int index, final boolean cleanup) {
        if (index >= phases.length) {
            if (cleanup) cancelProbe();
            sRunning = false;
            Log.i(LOG_TAG, "event=complete");
            return;
        }
        final String phase = phases[index];
        try {
            final NotificationManager manager = sContext.getSystemService(NotificationManager.class);
            if (manager == null) throw new IllegalStateException();
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Nevo local diagnostics",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            final String text;
            switch (phase) {
                case "counter": text = "[2条]local notification smoke test"; break;
                case "malformed": text = "[broken"; break;
                case "image": text = "[图片]"; break;
                case "overwrite": text = "local notification overwrite test"; break;
                default: text = "local notification smoke test";
            }
            Notification.Builder builder = new Notification.Builder(sContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("Nevo local smoke test")
                    .setContentText(text)
                    .setWhen(System.currentTimeMillis())
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true);
            if ("call".equals(phase)) builder.setCategory(Notification.CATEGORY_CALL);
            // No captured username, reply PendingIntent, person identity, or network operation.
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
            HANDLER.postDelayed(() -> observeProbe(phase), 500L);
            HANDLER.postDelayed(() -> runPhase(phases, index + 1, cleanup), 700L);
        } catch (RuntimeException | LinkageError error) {
            logFailure("post", error);
            if (cleanup) cancelProbe();
            sRunning = false;
        }
    }

    private static void observeProbe(String phase) {
        try {
            NotificationManager manager = sContext.getSystemService(NotificationManager.class);
            if (manager == null) throw new IllegalStateException();
            for (StatusBarNotification item : manager.getActiveNotifications()) {
                // No contents or metadata from another notification are inspected.
                if (item.getId() != NOTIFICATION_ID || !NOTIFICATION_TAG.equals(item.getTag())) continue;
                Notification notification = item.getNotification();
                String template = notification.extras != null
                        ? notification.extras.getString(Notification.EXTRA_TEMPLATE) : null;
                int actions = notification.actions != null ? notification.actions.length : 0;
                Log.i(LOG_TAG, "event=observed case=" + phase + " id=" + NOTIFICATION_ID
                        + " tag=" + NOTIFICATION_TAG + " template=" + safeTemplate(template) + " actions=" + actions);
                return;
            }
            Log.i(LOG_TAG, "event=absent case=" + phase + " id=" + NOTIFICATION_ID + " tag=" + NOTIFICATION_TAG);
        } catch (RuntimeException | LinkageError error) {
            logFailure("observe", error);
        }
    }

    private static String safeTemplate(String template) {
        if (template == null) return "none";
        // Avoid logging arbitrary extras if a platform or another module changes this field.
        if (template.startsWith("android.app.Notification$") && template.matches("[a-zA-Z0-9.$]+")) return template;
        return "other";
    }

    private static void cancelProbe() {
        try {
            NotificationManager manager = sContext.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
            Log.i(LOG_TAG, "event=cleared id=" + NOTIFICATION_ID + " tag=" + NOTIFICATION_TAG);
        } catch (RuntimeException | LinkageError error) {
            logFailure("clear", error);
        }
    }

    private static void logFailure(String stage, Throwable error) {
        Log.i(LOG_TAG, "event=error stage=" + stage + " type=" + error.getClass().getSimpleName());
    }
}
