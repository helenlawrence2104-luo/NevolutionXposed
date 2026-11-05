package com.oasisfeng.nevo.xposed;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Privileged, read-only diagnostics; the optional click only activates the original reply button. */
public final class DebugInlineProbe {
    private static final List<WeakReference<View>> layouts = new ArrayList<>();
    private static boolean receiverReady;
    private static String bind = "none", activation = "none";
    public static void install(ClassLoader loader) {
        Class<?> content = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.NotificationContentView", loader);
        XC_MethodHook remember = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                View layout = (View) param.thisObject;
                StatusBarNotification sbn = notification(layout);
                if (sbn == null || !"com.tencent.mm".equals(sbn.getPackageName())) return;
                remember(layout);
                if (param.method.getName().equals("applyRemoteInput")) {
                    View root = (View) param.args[0];
                    bind = "enabled:" + param.args[1] + ",wrapper:" + param.args[5].getClass().getSimpleName()
                            + ",androidContainer:" + container(root, "android")
                            + ",miuiContainer:" + container(root, "com.android.systemui");
                }
            }
        };
        for (String method : new String[]{"setExpandedChild", "setHeadsUpChild", "applyRemoteInput"}) XposedBridge.hookAllMethods(content, method, remember);
        Class<?> manager = XposedHelpers.findClass("com.android.systemui.statusbar.NotificationRemoteInputManager", loader);
        XposedBridge.hookAllMethods(manager, "activateRemoteInputOnExpanded", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                View button = (View) param.args[0];
                StringBuilder path = new StringBuilder();
                int rowTag = button.getResources().getIdentifier("row_tag_for_content_view", "id", "com.android.systemui");
                for (View current = button; current != null;) {
                    if (idName(current).contains("status_bar_latest_event_content"))
                        path.append(idName(current)).append("=tag:").append(rowTag != 0 && current.getTag(rowTag) != null).append(';');
                    current = current.getParent() instanceof View ? (View) current.getParent() : null;
                }
                activation = "result:" + param.getResult() + ",error:" + (param.hasThrowable() ? param.getThrowable().getClass().getSimpleName() : "none") + ",path:" + path;
            }
        });
    }

    private static String container(View root, String pkg) {
        int id = root.getResources().getIdentifier("actions_container", "id", pkg);
        View view = id == 0 ? null : root.findViewById(id);
        return view == null ? "absent" : view.getClass().getSimpleName() + ":" + view.getVisibility();
    }
    private static String idName(View view) {
        try { return view.getResources().getResourceName(view.getId()); }
        catch (RuntimeException absent) { return Integer.toString(view.getId()); }
    }
    private static StatusBarNotification notification(View layout) {
        try {
            Object row = XposedHelpers.getObjectField(layout, "mContainingNotification");
            Object adapter = XposedHelpers.callMethod(row, "getEntryAdapter");
            return (StatusBarNotification) XposedHelpers.callMethod(adapter, "getSbn");
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }
    private static void remember(View layout) {
        layouts.removeIf(reference -> reference.get() == null || reference.get() == layout);
        layouts.add(new WeakReference<>(layout));
        if (layouts.size() > 30) layouts.remove(0);
        if (receiverReady) return;
        Context context = layout.getContext().getApplicationContext();
        androidx.core.content.ContextCompat.registerReceiver(context, new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent request) {
                try {
                    int id = request.getIntExtra("id", Integer.MIN_VALUE);
                    String tag = request.getStringExtra("tag");
                    StringBuilder report = new StringBuilder("bind=").append(bind).append(";activation=").append(activation);
                    int clicks = 0;
                    for (WeakReference<View> reference : layouts) {
                        View layout = reference.get();
                        if (layout == null) continue;
                        StatusBarNotification sbn = notification(layout);
                        if (sbn == null || sbn.getId() != id || !java.util.Objects.equals(tag, sbn.getTag())) continue;
                        report.append(";layout=").append(layout.isAttachedToWindow()).append(':').append(layout.isShown());
                        View expanded = (View) XposedHelpers.getObjectField(layout, "mExpandedChild");
                        View heads = (View) XposedHelpers.getObjectField(layout, "mHeadsUpChild");
                        View input = (View) XposedHelpers.getObjectField(layout, "mExpandedRemoteInput");
                        report.append(",expanded=").append(state(expanded)).append(",heads=").append(state(heads)).append(",input=").append(state(input));
                        if(expanded!=null)for(int slot=0;slot<20;slot++) {
                            View voice=expanded.findViewById(com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.textId(slot,false));
                            if(voice instanceof TextView && ((TextView)voice).getText().toString().contains("语音 · ")) {
                                report.append(",voice").append(slot).append('=').append(state(voice)).append(":click=").append(voice.hasOnClickListeners());
                                if(request.getIntExtra("voice_click",-1)==slot && voice.isShown())report.append(":performed=").append(voice.performClick());
                            }
                        }
                        report.append(",headReplies=").append(replyViews(heads, 0));
                        if(android.os.Build.VERSION.SDK_INT>=28)report.append(",mediaViews=").append(ChatMediaViews.describe(expanded));
                        if (layout.isAttachedToWindow()) report.append(",spacing=").append(spacing(layout, expanded));
                        if (request.getBooleanExtra("click", false) && layout.isAttachedToWindow() && clicks == 0) {
                            List<View> buttons = new ArrayList<>(); findReplies(expanded, buttons, 0);
                            report.append(",buttons=").append(buttons.size());
                            if (buttons.size() == 1) { buttons.get(0).performClick(); clicks++; }
                        }
                    }
                    if(android.os.Build.VERSION.SDK_INT>=28)report.append(";photo=").append(ChatMediaViews.status());
                    setResultCode(1); setResultData(report.toString());
                } catch (RuntimeException | LinkageError unavailable) { setResultCode(-1); setResultData(unavailable.getClass().getSimpleName()); }
            }
        }, new IntentFilter("com.oasisfeng.nevo.xposed.INLINE_PROBE"), Manifest.permission.DUMP,
                new android.os.Handler(android.os.Looper.getMainLooper()), androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        receiverReady = true;
    }
    private static String spacing(View layout, View expanded) {
        try {
            Object wrapper = XposedHelpers.getObjectField(layout, "mExpandedWrapper");
            String result = "custom:" + XposedHelpers.callMethod(wrapper, "getCustomViewMargin")
                    + ",extra:" + XposedHelpers.callMethod(wrapper, "getExtraMeasureHeight");
            if (expanded != null && expanded.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) expanded.getLayoutParams();
                result += ",top:" + margins.topMargin + ",bottom:" + margins.bottomMargin + ",paddingBottom:" + expanded.getPaddingBottom();
            }
            Object row = XposedHelpers.getObjectField(layout, "mContainingNotification");
            Object injector = XposedHelpers.callMethod(row, "getInjector");
            View bar = (View) XposedHelpers.callMethod(injector, "getMiniBar");
            return result + ",bar:" + state(bar) + ",barBottom:" + XposedHelpers.getFloatField(injector, "miniBarMarginBottom");
        } catch (RuntimeException | LinkageError unavailable) { return unavailable.getClass().getSimpleName(); }
    }
    private static String replyViews(View root, int depth) {
        if (root == null || depth > 12) return "";
        StringBuilder text = new StringBuilder();
        if (root instanceof TextView && "回复".contentEquals(((TextView) root).getText()))
            text.append(root.getClass().getSimpleName()).append(':').append(root.getVisibility()).append(':').append(idName(root)).append(';');
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < Math.min(50, group.getChildCount()); i++) text.append(replyViews(group.getChildAt(i), depth + 1));
        }
        return text.toString();
    }
    private static String state(View view) {
        if (view == null) return "absent";
        Rect bounds = new Rect(); boolean visible = view.getGlobalVisibleRect(bounds);
        return view.getVisibility() + ":attached:" + view.isAttachedToWindow() + ":shown:" + view.isShown() + ":visible:" + visible + ":" + bounds.toShortString();
    }
    private static void findReplies(View root, List<View> results, int depth) {
        if (root == null || depth > 12 || results.size() > 3) return;
        if (root instanceof TextView && "回复".contentEquals(((TextView) root).getText()) && root.hasOnClickListeners()) results.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < Math.min(50, group.getChildCount()); i++) findReplies(group.getChildAt(i), results, depth + 1);
        }
    }
}
