package com.oasisfeng.nevo.xposed;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.UUID;
import de.robv.android.xposed.XposedHelpers;

/** Exact incoming voice identity. Audio and transcription stay in WeChat's native services. */
public final class WeChatVoice {
    private static final String ACTION="com.oasisfeng.nevo.xposed.PLAY_VOICE";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String,Voice> records=new LinkedHashMap<>();
    private static Context owner;
    private static Voice playing,transcribing;
    private static Object player;
    private static volatile String state="not-installed";
    private static final long TTL=30*60_000L;
    private WeChatVoice() {}
    private static final class Voice {
        String account,talker,token,file,transcript="",transState="waiting",playState="idle";
        long id,created;int seconds,waits;boolean ready,requested,heard;
        PendingIntent action;
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API <33 branch is protected by signature DUMP; 33+ is explicitly not exported.
    static synchronized void install(Context context,String version) {
        if(owner!=null || !"8.0.79".equals(version))return;
        if(!"com.tencent.mm".equals(context.getPackageName()))return;
        owner=context;
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            String token=i.getData()!=null ? i.getData().getLastPathSegment() : null;
            MAIN.post(()->toggle(token));
        }};
        if(android.os.Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,new IntentFilter(ACTION),Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver,new IntentFilter(ACTION),"android.permission.DUMP",null);
        state="installed";
    }
    private static Object call(String type,String method,Object... args) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(type,owner.getClassLoader()),method,args);
    }
    private static Object[] recent(String talker) {
        return (Object[])XposedHelpers.callMethod(XposedHelpers.callMethod(call("l41.h9","b"),"v"),"l8",talker,20);
    }
    private static Object message(Voice v) {
        if(!valid(v))return null;
        Object[] list=recent(v.talker);if(list==null)return null;
        for(Object m:list)if(matches(v,m))return m;
        return null;
    }
    private static boolean matches(Voice v,Object m) {
        return (Long)XposedHelpers.callMethod(m,"getMsgId")==v.id && v.talker.equals(XposedHelpers.callMethod(m,"N0"))
                && (((Integer)XposedHelpers.callMethod(m,"getType"))&0xffff)==34 && (Integer)XposedHelpers.callMethod(m,"z0")==0;
    }
    private static boolean valid(Voice v) {
        return v!=null && v.id>0 && SystemClock.elapsedRealtime()-v.created<TTL
                && v.account!=null && v.account.equals(MainHook.currentSelfPerson(owner).getKey());
    }
    static synchronized void capture(Context context,Object message,Bundle source) {
        if(owner==null)return;
        try {
            String account=MainHook.currentSelfPerson(context).getKey();
            if(account==null)return;
            String talker=(String)XposedHelpers.callMethod(message,"N0");long id=(Long)XposedHelpers.callMethod(message,"getMsgId");
            Voice voice=null;
            for(Voice v:records.values())if(v.id==id && v.talker.equals(talker) && account.equals(v.account) && valid(v)){voice=v;break;}
            if(voice==null) {
                voice=new Voice();voice.account=account;voice.talker=talker;voice.id=id;voice.token=UUID.randomUUID().toString();voice.created=SystemClock.elapsedRealtime();
                voice.file=(String)XposedHelpers.callMethod(message,"u0");
                Object content=XposedHelpers.newInstance(XposedHelpers.findClass("f71.a1",context.getClassLoader()),XposedHelpers.callMethod(message,"j"));
                voice.seconds=(int)Math.max(1,Math.min(60,Math.round(XposedHelpers.getLongField(content,"b")/1000f)));
                Intent intent=new Intent(ACTION).setPackage("com.tencent.mm").setData(Uri.parse("nevo-voice://play/"+voice.token));
                voice.action=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                records.put(voice.token,voice);
                while(records.size()>64){Voice removed=records.remove(records.keySet().iterator().next());removed.action.cancel();}
                Voice added=voice;MAIN.post(()->prepare(added));
            }
            source.putString("kind","voice");source.putBundle("voice",metadata(voice));
        } catch(Throwable unavailable){state="capture-unavailable:"+cause(unavailable);}
    }
    private static Bundle metadata(Voice v) {
        Bundle b=new Bundle();b.putParcelable("play",v.action);b.putInt("seconds",v.seconds);
        b.putString("playState",v.playState);b.putString("transState",v.transState);b.putString("transcript",v.transcript);return b;
    }
    private static void refresh(Voice v) {
        if(!valid(v))return;
        Bundle source=new Bundle();source.putInt("type",34);source.putString("kind","voice");source.putLong("id",v.id);source.putString("talker",v.talker);source.putBundle("voice",metadata(v));
        WeChatImageDownloads.refreshMessage(owner,v.talker,v.id,source);
    }
    private static String path(Voice v,Object m) {
        Object resource=call("pv5.e0","valueOf","AUDIO");Object descriptor=call("pv5.x","a",m,resource);
        Object service=call("ni5.n0","c",XposedHelpers.findClass("no3.u0",owner.getClassLoader()));
        String path=(String)XposedHelpers.callMethod(service,"bk",descriptor,v.file,false);
        if(path==null || !(Boolean)call("com.tencent.mm.vfs.z6","j",path) || ((Number)call("com.tencent.mm.vfs.z6","k",path)).longValue()<8)return null;
        return path;
    }
    private static void prepare(Voice v) {
        try {
            Object m=message(v);if(m==null)return;
            v.ready=path(v,m)!=null;
            if(!v.ready) {
                v.transState="downloading";
                if(++v.waits<20){MAIN.postDelayed(()->prepare(v),1000);return;}
                v.transState="unavailable";refresh(v);state="voice-file-unavailable";return;
            }
            Object store=call("f71.v0","zj");Object saved=XposedHelpers.callMethod(store,"P1",v.file);
            if(saved!=null) {
                String text=(String)XposedHelpers.getObjectField(saved,"field_content");
                if(text!=null && !text.isEmpty()){v.transcript=bounded(text);v.transState="done";refresh(v);state="transcript-cached";return;}
            }
            v.transState="waiting";refresh(v);queueTranscription();
        } catch(Throwable unavailable){v.transState="unavailable";state="prepare-unavailable:"+cause(unavailable);refresh(v);}
    }
    private static synchronized void queueTranscription() {
        if(transcribing!=null)return;
        for(Voice v:records.values())if(valid(v) && v.ready && !v.requested && v.transcript.isEmpty()) {
            v.requested=true;transcribing=v;transcribe(v);return;
        }
    }
    private static void transcribe(Voice v) {
        try {
            Object event=XposedHelpers.newInstance(XposedHelpers.findClass("com.tencent.mm.autogen.events.ExtTranslateVoiceEvent",owner.getClassLoader()));
            Object input=XposedHelpers.getObjectField(event,"g");
            XposedHelpers.setObjectField(input,"a",v.file);XposedHelpers.setObjectField(input,"b",String.valueOf(v.id));
            XposedHelpers.setObjectField(input,"g",v.talker);XposedHelpers.setIntField(input,"c",0);XposedHelpers.setIntField(input,"d",1);
            XposedHelpers.setIntField(input,"f",v.talker.endsWith("@chatroom") ? 2 : 1);
            XposedHelpers.setObjectField(input,"e",(Runnable)()->MAIN.post(()->{
                if(transcribing!=v || !valid(v))return;
                try {
                    Object output=XposedHelpers.getObjectField(event,"h");
                    String text=(String)XposedHelpers.getObjectField(output,"a");
                    v.transcript=bounded(text);v.transState=v.transcript.isEmpty() ? "unavailable" : "done";
                    state="transcript:"+v.transState;
                } catch(Throwable unavailable){v.transState="unavailable";state="transcript-unavailable:"+cause(unavailable);}
                transcribing=null;refresh(v);queueTranscription();
            }));
            v.transState="transcribing";refresh(v);
            boolean published=(Boolean)XposedHelpers.callMethod(event,"e");
            state=published ? "transcription-requested" : "transcription-busy";
            if(!published){v.transState="unavailable";transcribing=null;refresh(v);MAIN.postDelayed(WeChatVoice::queueTranscription,1000);return;}
            MAIN.postDelayed(()->{
                if(transcribing!=v)return;transcribing=null;v.transState="unavailable";state="transcription-timeout";refresh(v);queueTranscription();
            },60_000);
        } catch(Throwable unavailable){transcribing=null;v.transState="unavailable";state="transcription-unavailable:"+cause(unavailable);refresh(v);MAIN.postDelayed(WeChatVoice::queueTranscription,1000);}
    }
    private static String bounded(String text){return text==null ? "" : text.substring(0,Math.min(text.length(),4000));}
    private static void toggle(String token) {
        Voice v; synchronized(WeChatVoice.class){v=records.get(token);}
        try {
            if(!valid(v)){stop();state="stale-action";return;}
            if(playing==v){stop();return;}
            stop();Object m=message(v);if(m==null){state="message-unavailable";return;}
            AudioManager audio=owner.getSystemService(AudioManager.class);
            if(audio!=null && audio.getMode()!=AudioManager.MODE_NORMAL){state="audio-busy";return;}
            String file=path(v,m);if(file==null){v.playState="downloading";refresh(v);prepare(v);return;}
            player=XposedHelpers.newInstance(XposedHelpers.findClass("xl.x",owner.getClassLoader()),owner,0,1.0f);
            playing=v;
            boolean started=(Boolean)XposedHelpers.callMethod(player,"x",file,true,true,-1);
            if(!started){stop();v.playState="failed";refresh(v);state="play-rejected";return;}
            v.playState="playing";refresh(v);state="play-requested";
            poll(v,SystemClock.elapsedRealtime(),m);
        } catch(Throwable unavailable){stop();if(v!=null){v.playState="failed";refresh(v);}state="play-unavailable:"+cause(unavailable);}
    }
    private static void poll(Voice v,long started,Object message) {
        MAIN.postDelayed(()->{
            if(playing!=v)return;
            try {
                boolean active=player!=null && (Boolean)XposedHelpers.callMethod(player,"isPlaying");
                if(active){state="playing";if(!v.heard){v.heard=true;call("f71.d1","r",message);}}
                if(!valid(v) || SystemClock.elapsedRealtime()-started>70_000 || (!active && SystemClock.elapsedRealtime()-started>2000)){stop();return;}
                poll(v,started,message);
            } catch(Throwable unavailable){stop();state="playback-unavailable:"+cause(unavailable);}
        },300);
    }
    private static void stop() {
        Voice old=playing;Object previous=player;playing=null;player=null;
        if(previous!=null)try{XposedHelpers.callMethod(previous,"stop",true);}catch(Throwable ignored){}
        if(old!=null){old.playState="idle";refresh(old);state="stopped";}
    }
    public static String label(Bundle voice) {
        String playback=voice.getString("playState","");
        String label=("playing".equals(playback) ? "■ 正在播放 · " : "▶ 语音 · ")+voice.getInt("seconds",1)+"秒";
        String text=voice.getString("transcript","");
        if(!text.isEmpty())return label+"\n"+text;
        String trans=voice.getString("transState","");
        if("transcribing".equals(trans))return label+"\n正在转文字…";
        if("downloading".equals(trans)||"downloading".equals(playback))return label+"\n语音下载中…";
        if("failed".equals(playback))return label+"\n播放失败，点此重试";
        return label;
    }
    public static synchronized String inspectRecent(Context context,String talker,boolean play) {
        try {
            Object[] list=recent(talker);if(list==null)return "no-voice";
            for(int i=list.length-1;i>=0;i--) {
                Object m=list[i];if((((Integer)XposedHelpers.callMethod(m,"getType"))&0xffff)!=34 || (Integer)XposedHelpers.callMethod(m,"z0")!=0)continue;
                Bundle source=new Bundle();source.putLong("id",(Long)XposedHelpers.callMethod(m,"getMsgId"));capture(context,m,source);
                Voice v=null;for(Voice candidate:records.values())if(matches(candidate,m)){v=candidate;break;}
                if(v==null)return "capture-unavailable";
                Voice selected=v;
                if(play)MAIN.post(()->toggle(selected.token));
                return "voice:"+v.seconds+"s;file="+(path(v,m)!=null)+";transcript="+(!v.transcript.isEmpty())+";play="+v.playState;
            }
            return "no-voice";
        } catch(Throwable unavailable){return "voice-inspect-unavailable:"+cause(unavailable);}
    }
    private static String cause(Throwable e){for(int i=0;i<8 && e.getCause()!=null;i++)e=e.getCause();return e.getClass().getSimpleName();}
    public static String status(){return state;}
}
