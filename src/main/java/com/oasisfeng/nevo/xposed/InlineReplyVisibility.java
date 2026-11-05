package com.oasisfeng.nevo.xposed;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Expose the existing notification reply action without replacing its editor or click handler. */
public final class InlineReplyVisibility {
    private InlineReplyVisibility() {}
    public static void install(ClassLoader loader) {
        installFullscreenTemplate(loader);
        installEnterToSend(loader);
        installCardSpacing(loader);
        try {
            Class<?> content = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.NotificationContentView", loader);
            XposedBridge.hookAllMethods(content, "getExtraRemoteInputHeight", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof Integer) || (Integer) param.getResult() <= 0) return;
                    try {
                        View layout = (View) param.thisObject;
                        StatusBarNotification owner = notification(layout);
                        if (owner == null || !"com.tencent.mm".equals(owner.getPackageName())) return;
                        View expanded = (View) XposedHelpers.getObjectField(layout, "mExpandedChild");
                        if (expanded == null || expanded.findViewById(R.id.chat_row_0) == null) return;
                        Object row = XposedHelpers.getObjectField(layout, "mContainingNotification");
                        Object injector = XposedHelpers.callMethod(row, "getInjector");
                        View bar = (View) XposedHelpers.callMethod(injector, "getMiniBar");
                        boolean visible = bar != null && bar.getVisibility() == View.VISIBLE;
                        float bottom = XposedHelpers.getFloatField(injector, "miniBarMarginBottom");
                        param.setResult(ChatCardSpacing.inputReserve((Integer) param.getResult(), visible,
                                bar == null ? 0 : bar.getMeasuredHeight(), bottom));
                    } catch (RuntimeException | LinkageError unavailable) {
                        android.util.Log.w("NevoInline", "Inline input spacing unchanged");
                    }
                }
            });
            XC_MethodHook bind = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length == 0 || !(param.args[0] instanceof View)) return;
                    View root = (View) param.args[0];
                    View layout = (View) param.thisObject;
                    StatusBarNotification owner = notification(layout);
                    if (owner == null || !"com.tencent.mm".equals(owner.getPackageName())) return;
                    if (param.method.getName().equals("setExpandedChild")) {
                        alignChat(root);
                        ChatCardHeader.bind(root, owner.getNotification().extras.getCharSequence("nevo.conversation.title"));
                        try { if(android.os.Build.VERSION.SDK_INT>=28)ChatMediaViews.bind(root,owner.getNotification()); }
                        catch(RuntimeException | LinkageError unavailable) { android.util.Log.w("NevoInline","Media view retained native preview"); }
                        return;
                    }
                    // Heads-up banners are the surface that leaks the duplicate reply action;
                    // hide it before and independently of the inline entry, which only exists in
                    // the expanded child. Never return early here.
                    if (param.method.getName().equals("setHeadsUpChild")) {
                        Notification.Action action = replyAction(owner.getNotification());
                        if (action != null) hideDuplicateActions(root, action);
                    }
                    View entry = root.findViewById(R.id.inline_reply_entry);
                    if (entry == null) return;
                    final String key = owner.getKey();
                    entry.setOnClickListener(clicked -> {
                        try {
                            StatusBarNotification current = notification(layout);
                            if (!layout.isAttachedToWindow() || current == null || !key.equals(current.getKey())) return;
                            Notification.Action reply = replyAction(current.getNotification());
                            View expanded = (View) XposedHelpers.getObjectField(layout, "mExpandedChild");
                            List<View> originals = new ArrayList<>();
                            if (reply != null) findOriginal(expanded, reply.title, originals, 0);
                            // Resolve the currently bound original each time; never retain a stale view.
                            if (originals.size() == 1) originals.get(0).callOnClick();
                            else android.widget.Toast.makeText(clicked.getContext(), "通知回复尚未加载，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (RuntimeException | LinkageError unavailable) {
                            android.util.Log.w("NevoInline", "Original reply activation unavailable");
                        }
                    });
                }
            };
            for (String name : new String[]{"setContractedChild", "setHeadsUpChild", "setExpandedChild"}) XposedBridge.hookAllMethods(content, name, bind);
        } catch (RuntimeException | LinkageError unavailable) {
            android.util.Log.w("NevoInline", "Reply visibility hook unavailable");
        }
    }

    private static void installCardSpacing(ClassLoader loader) {
        try {
            Class<?> custom = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationCustomViewWrapper", loader);
            Class<?> decorated = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationDecoratedCustomViewWrapper", loader);
            XC_MethodHook trim = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        View root = (View) XposedHelpers.getObjectField(param.thisObject, "mView");
                        if (root.findViewById(R.id.chat_row_0) == null) return;
                        Object row = XposedHelpers.getObjectField(param.thisObject, "mRow");
                        Object adapter = XposedHelpers.callMethod(row, "getEntryAdapter");
                        StatusBarNotification owner = (StatusBarNotification) XposedHelpers.callMethod(adapter, "getSbn");
                        if (!"com.tencent.mm".equals(owner.getPackageName())) return;
                        int removed = ChatCardSpacing.trimBottom(root);
                        if (param.method.getName().equals("getExtraMeasureHeight") && param.getResult() instanceof Integer)
                            param.setResult(ChatCardSpacing.extraHeight((Integer) param.getResult(), removed));
                    } catch (RuntimeException | LinkageError unavailable) {
                        android.util.Log.w("NevoInline", "Chat card spacing unavailable");
                    }
                }
            };
            XposedBridge.hookAllMethods(custom, "getExtraMeasureHeight", trim);
            XposedBridge.hookAllMethods(decorated, "setCustomViewMargin", trim);
        } catch (RuntimeException | LinkageError unavailable) {
            android.util.Log.w("NevoInline", "Chat card spacing hook unavailable");
        }
    }

    static void hideDuplicateActions(View root, Notification.Action reply) {
        if (root == null || reply == null) return;
        CharSequence title = reply.title;
        // MIUI heads-up often labels the action from its RemoteInput, not action.title.
        CharSequence label = null;
        android.app.RemoteInput[] inputs = reply.getRemoteInputs();
        if (inputs != null) for (android.app.RemoteInput input : inputs)
            if (input.getAllowFreeFormInput() && input.getLabel() != null) { label = input.getLabel(); break; }
        hideDuplicateChildren(root, title, label, 0);
    }

    private static void hideDuplicateChildren(View view, CharSequence title, CharSequence label, int depth) {
        if (view == null || depth > 12) return;
        // Only hide the framework's own reply action, never our inline_reply_entry.
        if (view.getId() != R.id.inline_reply_entry && view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            boolean match = (title != null && android.text.TextUtils.equals(title, text))
                    || (label != null && android.text.TextUtils.equals(label, text));
            // Fall back to the literal remote-input label when it is not exposed as action title.
            boolean actionView = view instanceof android.widget.Button || view.hasOnClickListeners()
                    || view.getParent() instanceof View && ((View) view.getParent()).hasOnClickListeners();
            int id = view.getId();
            boolean messageView = id == R.id.inline_reply_text || id == R.id.inline_reply_title
                    || id == R.id.chat_left_text_0 || id == R.id.chat_left_text_1
                    || id == R.id.chat_left_text_2 || id == R.id.chat_left_text_3
                    || id == R.id.chat_right_text_0 || id == R.id.chat_right_text_1
                    || id == R.id.chat_right_text_2 || id == R.id.chat_right_text_3;
            if (match && actionView && !messageView && !(view instanceof android.widget.EditText)) view.setVisibility(View.GONE);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < Math.min(50, group.getChildCount()); i++)
                hideDuplicateChildren(group.getChildAt(i), title, label, depth + 1);
        }
    }

    private static boolean enhanced(Notification notification) {
        return notification != null && notification.extras != null
                && notification.extras.getBoolean("nevo.inline.reply.entry", false)
                && notification.headsUpContentView != null && replyAction(notification) != null;
    }

    private static void installFullscreenTemplate(ClassLoader loader) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            // Android 15 (API 35) renamed the content-binder injector; hook it only when present.
            try {
                Class<?> injector = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjectorImpl", loader);
                XposedBridge.hookAllMethods(injector, "useOneLine", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        for (Object arg : param.args) if (arg instanceof Notification && enhanced((Notification) arg)) { param.setResult(false); return; }
                    }
                });
            } catch (RuntimeException | LinkageError unavailable) {
                android.util.Log.w("NevoInline", "Content-binder injector hook unavailable");
            }
            final java.lang.reflect.Field field = XposedHelpers.findFirstFieldByExactType(Notification.Builder.class, Notification.class);
            // Heads-up templates are built through this method on every supported API level.
            XposedBridge.hookAllMethods(Notification.Builder.class, "createHeadsUpContentView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof android.widget.RemoteViews)) return;
                    try {
                        if (enhanced((Notification) field.get(param.thisObject)))
                            hideHeadsUpActions((android.widget.RemoteViews) param.getResult());
                    } catch (IllegalAccessException | RuntimeException | LinkageError unavailable) {
                        android.util.Log.w("NevoInline", "Heads-up action row unavailable");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Notification.Builder.class, "createCompactHeadsUpContentView", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Notification notification = (Notification) field.get(param.thisObject);
                        if (!enhanced(notification)) return;
                        Object standard = XposedHelpers.callMethod(param.thisObject, "createHeadsUpContentView");
                        if (standard instanceof android.widget.RemoteViews) param.setResult(standard);
                    } catch (IllegalAccessException | RuntimeException | LinkageError unavailable) {
                        android.util.Log.w("NevoInline", "Standard heads-up template unavailable");
                    }
                }
            });
        } catch (RuntimeException | LinkageError unavailable) {
            android.util.Log.w("NevoInline", "Fullscreen reply template hook unavailable");
        }
    }

    static void hideHeadsUpActions(android.widget.RemoteViews views) {
        if (views == null) return;
        // AOSP action row id; MIUI may use its own, which the View-tree path covers instead.
        int id = android.content.res.Resources.getSystem().getIdentifier("actions", "id", "android");
        if (id != 0) views.setViewVisibility(id, View.GONE);
    }

    /** Force the notification reply editor to treat Enter as "send" instead of a newline. */
    private static void installEnterToSend(ClassLoader loader) {
        try {
            Class<?> editText = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.policy.RemoteInputView$RemoteEditText", loader);
            XposedBridge.hookAllMethods(editText, "onCreateInputConnection", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length == 0
                            || !(param.args[0] instanceof android.view.inputmethod.EditorInfo)) return;
                    android.view.inputmethod.EditorInfo info = (android.view.inputmethod.EditorInfo) param.args[0];
                    // SEND turns the IME enter key into a "send" action; clearing NO_ENTER_ACTION
                    // prevents some IMEs from hiding that key when it is set by the layout.
                    info.imeOptions = (info.imeOptions
                            & ~android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                            & ~android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                            | android.view.inputmethod.EditorInfo.IME_ACTION_SEND;
                }
            });
        } catch (RuntimeException | LinkageError unavailable) {
            android.util.Log.w("NevoInline", "Enter-to-send hook unavailable");
        }
    }

    private static void alignChat(View root) {
        if (root.findViewById(R.id.chat_row_0) == null) return;
        int id = root.getResources().getIdentifier("notification_main_column", "id", "android");
        View column = id == 0 ? null : root.findViewById(id);
        if (column == null || !(column.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) column.getLayoutParams();
        int inset = Math.round(16 * root.getResources().getDisplayMetrics().density);
        margins.setMarginStart(inset); margins.setMarginEnd(inset);
        column.setLayoutParams(margins);
    }

    private static StatusBarNotification notification(View layout) {
        try {
            Object row = XposedHelpers.getObjectField(layout, "mContainingNotification");
            Object adapter = XposedHelpers.callMethod(row, "getEntryAdapter");
            return (StatusBarNotification) XposedHelpers.callMethod(adapter, "getSbn");
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    public static Notification.Action replyAction(Notification notification) {
        if (notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.actionIntent == null || !action.getExtras().getBoolean("nevo.reply.owned_action")) continue;
            android.app.RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs != null) for (android.app.RemoteInput input : inputs) if (input.getAllowFreeFormInput()) return action;
        }
        return null;
    }

    static void findOriginal(View root, CharSequence title, List<View> results, int depth) {
        if (root == null || depth > 12 || results.size() > 2) return;
        if (root.getId() != R.id.inline_reply_entry && root instanceof TextView && root.hasOnClickListeners()
                && android.text.TextUtils.equals(title, ((TextView) root).getText())) results.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < Math.min(50, group.getChildCount()); i++) findOriginal(group.getChildAt(i), title, results, depth + 1);
        }
    }
}
