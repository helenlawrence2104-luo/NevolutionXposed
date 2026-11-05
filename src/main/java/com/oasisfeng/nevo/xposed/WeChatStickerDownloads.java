package com.oasisfeng.nevo.xposed;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import de.robv.android.xposed.XposedHelpers;

/** Native prepareEmoji downloads only; the verified Ij path sets insert-message=false. */
public final class WeChatStickerDownloads {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String,Long> attempts=new LinkedHashMap<>();
    private static final java.util.HashSet<String> pending=new java.util.HashSet<>();
    private static volatile String state="idle";
    private WeChatStickerDownloads(){}
    public static void request(Context context,Object message) { MAIN.post(()->start(context,message)); }
    private static void start(Context context,Object message) {
        String key=null;
        try {
            String account=MainHook.currentSelfPerson(context).getKey();
            long id=(Long)XposedHelpers.callMethod(message,"getMsgId");
            String talker=(String)XposedHelpers.callMethod(message,"N0");
            if(account==null || id<=0 || (Integer)XposedHelpers.callMethod(message,"z0")!=0)return;
            key=account+":"+talker+":"+id;
            long now=android.os.SystemClock.elapsedRealtime();Long last=attempts.get(key);
            if(pending.contains(key) || last!=null && now-last<60_000 || pending.size()>=8)return;
            attempts.put(key,now);while(attempts.size()>100)attempts.remove(attempts.keySet().iterator().next());
            String digest=WeChatStickers.md5((String)XposedHelpers.callMethod(message,"u0"),(String)XposedHelpers.callMethod(message,"j"));
            if(digest==null)return;
            ClassLoader loader=context.getClassLoader();
            Object storage=XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.tencent.mm.storage.m5",loader),"f");
            Object emoji=XposedHelpers.callMethod(XposedHelpers.callMethod(storage,"c"),"k3",digest);
            if(emoji==null){state="record-unavailable";return;}
            Object feature=XposedHelpers.callStaticMethod(XposedHelpers.findClass("ni5.n0",loader),"c",XposedHelpers.findClass("com.tencent.mm.feature.emoji.b0",loader));
            XposedHelpers.callMethod(feature,"Ij",emoji);
            pending.add(key);state="requested:prepare-emoji";
            poll(context,message,emoji,account,talker,id,key,0);
        } catch(Throwable error){if(key!=null)pending.remove(key);state="request-unavailable:"+cause(error);}
    }
    private static void poll(Context context,Object message,Object emoji,String account,String talker,long id,String key,int count) {
        MAIN.postDelayed(()->{
            if(!pending.contains(key))return;
            try {
                if(!account.equals(MainHook.currentSelfPerson(context).getKey())){pending.remove(key);state="account-changed";return;}
                if((Boolean)XposedHelpers.callMethod(emoji,"P0")) {
                    Bundle source=WeChatMedia.snapshot(context,message);
                    if(source!=null && source.containsKey("uri")) {
                        pending.remove(key);state="downloaded:"+source.getInt("width")+"x"+source.getInt("height");
                        WeChatImageDownloads.refreshMessage(context,talker,id,source);return;
                    }
                }
                if(count>=29){pending.remove(key);state="download-timeout";return;}
                poll(context,message,emoji,account,talker,id,key,count+1);
            } catch(Throwable error){pending.remove(key);state="read-unavailable:"+cause(error);}
        },1000);
    }
    private static String cause(Throwable error){for(int i=0;i<8 && error.getCause()!=null;i++)error=error.getCause();return error.getClass().getSimpleName();}
    public static String status(){return state;}
}
