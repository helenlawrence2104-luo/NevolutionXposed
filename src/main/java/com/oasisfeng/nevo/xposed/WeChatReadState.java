package com.oasisfeng.nevo.xposed;

import android.content.Context;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.Objects;

import de.robv.android.xposed.XposedHelpers;

/** Version-gated adapter to WeChat's own conversation-read operation; never writes SQL. */
public final class WeChatReadState {
    private static volatile String state = "not-requested";
    private WeChatReadState() {}

    public static final class Snapshot {
        final String account, username;
        final int unread, muted;
        final long sequence, captured;
        Snapshot(String account, String username, int unread, int muted, long sequence, long captured) {
            this.account=account; this.username=username; this.unread=unread; this.muted=muted;
            this.sequence=sequence; this.captured=captured;
        }
    }

    public static boolean unchanged(Snapshot before, Snapshot after) {
        return before != null && after != null && Objects.equals(before.account,after.account)
                && Objects.equals(before.username,after.username) && before.unread==after.unread
                && before.muted==after.muted && before.sequence==after.sequence
                && after.captured>=before.captured && after.captured-before.captured<=5000;
    }

    private static Object storage(Context context) throws ReflectiveOperationException {
        try {
            if (!"8.0.79".equals(context.getPackageManager().getPackageInfo("com.tencent.mm",0).versionName)) return null;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return null; }
        ClassLoader loader=context.getClassLoader();
        Class<?> kernel=XposedHelpers.findClass("pp0.i1",loader);
        Class<?> feature=XposedHelpers.findClass("po3.c4",loader);
        Object service=kernel.getMethod("v",Class.class).invoke(null,feature);
        if (service==null) return null;
        Class<?> storageType=XposedHelpers.findClass("com.tencent.mm.storage.l4",loader);
        Method getter=service.getClass().getMethod("Bj");
        if (getter.getReturnType()!=storageType) return null;
        Object storage=getter.invoke(service);
        if (storage==null || !storageType.isInstance(storage)) return null;
        if (storageType.getMethod("b0",String.class).getReturnType()!=boolean.class) return null;
        return storage;
    }

    private static Snapshot read(Context context, String username, Object storage) throws ReflectiveOperationException {
        String account=MainHook.currentSelfPerson(context).getKey();
        if (account==null || account.isEmpty() || username==null || username.isEmpty() || storage==null) return null;
        Object conversation=storage.getClass().getMethod("p",String.class).invoke(storage,username);
        if (conversation==null || !username.equals(XposedHelpers.callMethod(conversation,"f1"))) return null;
        return new Snapshot(account,username,(Integer)XposedHelpers.callMethod(conversation,"Z0"),
                (Integer)XposedHelpers.callMethod(conversation,"c1"),(Long)XposedHelpers.callMethod(conversation,"O0"),SystemClock.elapsedRealtime());
    }

    public static Snapshot capture(Context context, String username) {
        try {
            Snapshot result=read(context,username,storage(context));
            state=result==null ? "unavailable" : "captured:"+result.unread+":"+result.muted;
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            state="unavailable:"+unavailable.getClass().getSimpleName(); return null;
        }
    }

    public static void markAfterSubmission(Context context, String username, Snapshot before) {
        if (before==null) return;
        try {
            Object storage=storage(context);
            Snapshot current=read(context,username,storage);
            if (!unchanged(before,current)) { state="skipped:conversation-changed"; return; }
            if (current.unread==0 && current.muted==0) { state="already-read"; return; }
            boolean updated=(Boolean)storage.getClass().getMethod("b0",String.class).invoke(storage,username);
            Snapshot after=read(context,username,storage);
            state=updated && after!=null && after.unread==0 && after.muted==0 ? "marked-read" : "read-not-confirmed";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            state="read-failed:"+unavailable.getClass().getSimpleName();
        }
    }

    public static String status() { return state; }
}
