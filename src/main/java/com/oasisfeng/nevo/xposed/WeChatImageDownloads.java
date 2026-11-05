package com.oasisfeng.nevo.xposed;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import de.robv.android.xposed.XposedHelpers;

/** Requests the same normal (compressType=0) image download as WeChat's gallery. */
public final class WeChatImageDownloads {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String,Task> tasks=new LinkedHashMap<>();
    private static final LinkedHashMap<String,Long> attempts=new LinkedHashMap<>();
    private static volatile String state="idle";
    private WeChatImageDownloads() {}
    private static final class Task {
        Context context;Object message,manager,listener;String key,account,talker;long id;
    }
    public static void request(Context context,Object message) {
        if(context==null || !"com.tencent.mm".equals(context.getPackageName()))return;
        MAIN.post(()->start(context,message));
    }
    private static void start(Context context,Object message) {
        Task task=new Task();
        try {
            task.context=context;task.message=message;
            task.account=MainHook.currentSelfPerson(context).getKey();
            task.talker=(String)XposedHelpers.callMethod(message,"N0");task.id=(Long)XposedHelpers.callMethod(message,"getMsgId");
            if(task.account==null || task.id<=0 || (Integer)XposedHelpers.callMethod(message,"z0")!=0)return;
            task.key=task.account+":"+task.talker+":"+task.id;
            if(tasks.containsKey(task.key))return;
            long now=android.os.SystemClock.elapsedRealtime();
            Long last=attempts.get(task.key);if(last!=null && now-last<60_000)return;
            attempts.put(task.key,now);while(attempts.size()>100)attempts.remove(attempts.keySet().iterator().next());
            if(tasks.size()>=8){state="capacity";return;}
            ClassLoader loader=context.getClassLoader();
            Object store=XposedHelpers.callStaticMethod(XposedHelpers.findClass("v51.b1",loader),"Bj");
            Object image=XposedHelpers.callMethod(store,"L3",message);
            if(image==null){state="image-record-unavailable";return;}
            long imageId=XposedHelpers.getLongField(image,"a");if(imageId<=0){state="image-id-unavailable";return;}
            Object service=XposedHelpers.callStaticMethod(XposedHelpers.findClass("ni5.n0",loader),"c",XposedHelpers.findClass("ea0.y",loader));
            task.manager=XposedHelpers.callMethod(service,"Bj");
            Class<?> listenerType=XposedHelpers.findClass("ea0.w",loader);
            task.listener=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class<?>[]{listenerType},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class) {
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return "NevoImageListener";
                }
                if("c".equals(method.getName()) && args!=null && args.length==10) {
                    long id=((Number)args[1]).longValue();String talker=(String)args[2];
                    int type=(Integer)args[6],code=(Integer)args[7];
                    if(id==task.id && task.talker.equals(talker))MAIN.post(()->finish(task,type,code));
                } else if("o".equals(method.getName()))MAIN.post(()->finish(task,-1,-1));
                return null;
            });
            Object identity=XposedHelpers.callStaticMethod(XposedHelpers.findClass("no3.o1",loader),"a",message);
            tasks.put(task.key,task);
            int result=(Integer)XposedHelpers.callMethod(task.manager,"b",imageId,identity,0,task.key,2131231865,task.listener,0,false);
            if(result<0){tasks.remove(task.key);state="request-rejected:"+result;return;}
            state="requested:normal-image";
            MAIN.postDelayed(()->{if(tasks.get(task.key)==task){tasks.remove(task.key);cancel(task);state="download-timeout";}},60_000);
        } catch(Throwable unavailable){if(task.key!=null)tasks.remove(task.key);state="request-unavailable:"+cause(unavailable);}
    }
    private static void finish(Task task,int type,int code) {
        if(tasks.get(task.key)!=task)return;
        tasks.remove(task.key);
        if(type!=0 || code!=0){state="download-failed:"+type+":"+code;return;}
        if(!task.account.equals(MainHook.currentSelfPerson(task.context).getKey())){state="account-changed";return;}
        try {
            Bundle source=WeChatMedia.snapshot(task.context,task.message);
            if(source==null || !source.containsKey("uri") || "thumbnail".equals(source.getString("quality"))){state="downloaded-file-unresolved";return;}
            state="downloaded:"+source.getInt("width")+"x"+source.getInt("height");
            refresh(task,source);
        } catch(Throwable unavailable){state="refresh-unavailable:"+cause(unavailable);}
    }
    @android.annotation.SuppressLint("NotificationPermission") // Runs in WeChat; explicitly checks its posting permission below.
    private static void refresh(Task task,Bundle source) {
        if(android.os.Build.VERSION.SDK_INT>=33 && task.context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        NotificationManager manager=task.context.getSystemService(NotificationManager.class);
        for(StatusBarNotification active:manager.getActiveNotifications()) {
            Notification original=active.getNotification();
            if(!task.talker.equals(MainHook.getCapturedWeChatUsername(original)))continue;
            Notification copy=original.clone();copy.extras=original.extras.deepCopy();
            Notification media=new Notification();media.extras=new Bundle();media.extras.putBundle(WeChatMedia.SOURCE,source);
            boolean found=updateMessages(copy,task.id,source,WeChatMedia.preview(media));
            if(!found)continue;
            Bundle current=copy.extras.getBundle(WeChatMedia.SOURCE);
            if(current!=null && current.getLong("id")==task.id)copy.extras.putBundle(WeChatMedia.SOURCE,new Bundle(source));
            copy.flags|=Notification.FLAG_ONLY_ALERT_ONCE;
            manager.notify(active.getTag(),active.getId(),copy);
            state=state.split(";",2)[0]+";notification-updated";
        }
    }
    static void refreshMessage(Context context,String talker,long id,Bundle source) {
        Task task=new Task();task.context=context;task.talker=talker;task.id=id;refresh(task,source);
    }
    static boolean updateMessages(Notification notification,long id,Bundle source,Bitmap preview) {
        if(id<=0 || source==null || (source.getParcelable("uri")==null && source.getBundle("voice")==null))return false;
        Parcelable[] messages=notification.extras.getParcelableArray("android.messages");boolean found=false;
        if(messages!=null)for(Parcelable item:messages)if(item instanceof Bundle) {
            Bundle message=(Bundle)item,metadata=message.getBundle("extras");
            if(metadata==null || metadata.getLong("nevo.attachment.message_id")!=id || ChatNotificationRenderer.isSelf(message))continue;
            if(source.getBundle("voice")!=null){metadata.putBundle("nevo.voice",new Bundle(source.getBundle("voice")));found=true;continue;}
            if(preview!=null)metadata.putParcelable("nevo.attachment.preview",preview);
            metadata.putParcelable("nevo.attachment.uri",source.getParcelable("uri"));
            metadata.putString("nevo.attachment.quality",source.getString("quality"));
            metadata.putString("nevo.attachment.kind",source.getString("kind"));
            metadata.putBoolean("nevo.attachment.animated",source.getBoolean("animated"));found=true;
        }
        return found;
    }
    private static void cancel(Task task){try{XposedHelpers.callMethod(task.manager,"d",task.listener);}catch(Throwable ignored){}}
    private static String cause(Throwable error){for(int i=0;i<8 && error.getCause()!=null;i++)error=error.getCause();return error.getClass().getSimpleName();}
    public static String status(){return state;}
}
