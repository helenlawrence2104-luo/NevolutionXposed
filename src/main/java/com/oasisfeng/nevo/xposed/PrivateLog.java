package com.oasisfeng.nevo.xposed;

/** Never emit chat payloads or exception messages containing them, even in debug builds. */
public final class PrivateLog {
    private PrivateLog() {}
    public static int d(String tag, String ignored) { return 0; }
    public static int d(String tag, String ignored, Throwable error) { return 0; }
    public static int w(String tag, String ignored) { return android.util.Log.w(tag, "Notification reply warning; payload omitted"); }
    public static int e(String tag, String ignored) { return android.util.Log.e(tag, "Notification reply error; payload omitted"); }
    public static int e(String tag, String ignored, Throwable error) { return e(tag, ignored); }
}
