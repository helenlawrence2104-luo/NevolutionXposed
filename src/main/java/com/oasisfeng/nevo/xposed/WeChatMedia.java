package com.oasisfeng.nevo.xposed;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import de.robv.android.xposed.XposedHelpers;

/** Reads media through WeChat's own VFS and only for the notification's exact message. */
public final class WeChatMedia {
    public static final String SOURCE="nevo.source.media";
    private static volatile String state="not-installed";
    private static boolean installed;
    private WeChatMedia() {}

    public static synchronized void install(Context context,String version) {
        if(installed || !"8.0.79".equals(version))return;
        try {
            XposedHelpers.findClass("pv5.x",context.getClassLoader());
            XposedHelpers.findClass("com.tencent.mm.vfs.VFSFileProvider",context.getClassLoader());
            installed=true;WeChatBuiltinEmoji.install(version);WeChatVoice.install(context,version);state="installed:exact-publication";
        } catch(Throwable unavailable){state="install-unavailable:"+failure(unavailable);}
    }
    private static Object[] recent(Context context,String talker) {
        Object services=XposedHelpers.callStaticMethod(XposedHelpers.findClass("l41.h9",context.getClassLoader()),"b");
        return (Object[])XposedHelpers.callMethod(XposedHelpers.callMethod(services,"v"),"l8",talker,20);
    }
    static Uri uri(Context context,String path) {
        if(path==null || path.isEmpty())return null;
        ClassLoader loader=context.getClassLoader();
        Object file=XposedHelpers.newInstance(XposedHelpers.findClass("com.tencent.mm.vfs.u6",loader),path);
        Uri uri=(Uri)XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.tencent.mm.vfs.VFSFileProvider",loader),"getUriForFile","com.tencent.mm.external.fileprovider",file);
        return uri!=null && "content".equals(uri.getScheme()) && "com.tencent.mm.external.fileprovider".equals(uri.getAuthority()) ? uri : null;
    }
    static boolean readable(Context context,Uri uri,Bundle source) {
        if(uri==null)return false;
        try(ParcelFileDescriptor file=context.getContentResolver().openFileDescriptor(uri,"r")) {
            if(file==null || file.getStatSize()>32L*1024*1024)return false;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(),null,bounds);
            if(bounds.outWidth<1 || bounds.outHeight<1 || (long)bounds.outWidth*bounds.outHeight>80_000_000L)return false;
            source.putInt("width",bounds.outWidth);source.putInt("height",bounds.outHeight);
            source.putLong("bytes",file.getStatSize());source.putParcelable("uri",uri);source.putString("readState","readable");
            return true;
        } catch(Throwable unavailable){source.putString("readState",failure(unavailable));return false;}
    }
    static Bundle snapshot(Context context,Object msg) {
        int type=((Integer)XposedHelpers.callMethod(msg,"getType"))&0xffff;
        if((type!=3 && type!=47 && type!=34) || (Integer)XposedHelpers.callMethod(msg,"z0")!=0)return null;
        Bundle source=new Bundle();source.putString("talker",(String)XposedHelpers.callMethod(msg,"N0"));
        source.putLong("id",(Long)XposedHelpers.callMethod(msg,"getMsgId"));source.putInt("type",type);
        if(type==47){source.putString("kind","sticker");WeChatStickers.capture(context,msg,source);return source;}
        if(type==34){WeChatVoice.capture(context,msg,source);return source;}
        if(type!=3)return source;
        ClassLoader loader=context.getClassLoader();
        StringBuilder attempts=new StringBuilder();
        try {
            Object store=XposedHelpers.callStaticMethod(XposedHelpers.findClass("v51.b1",loader),"Bj");
            Object record=XposedHelpers.callMethod(store,"L3",msg);
            boolean complete=record!=null && (Boolean)XposedHelpers.callMethod(record,"l");
            source.putBoolean("complete",complete);
            if(complete) {
                String path=(String)XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.tencent.mm.ui.chatting.gallery.z2",loader),"x",msg,record,false);
                Class<?> vfs=XposedHelpers.findClass("com.tencent.mm.vfs.z6",loader);
                source.putBoolean("galleryVfs",path!=null && (Boolean)XposedHelpers.callStaticMethod(vfs,"j",path));
                if(path!=null)try(java.io.InputStream input=(java.io.InputStream)XposedHelpers.callStaticMethod(vfs,"E",path)) {
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeStream(input,null,bounds);
                    source.putString("galleryPixels",bounds.outWidth+"x"+bounds.outHeight);
                }
                if(readable(context,uri(context,path),source))source.putString("quality","GALLERY_IMAGE");
                else if(path!=null && nativePreview(context,path,source))source.putString("quality","GALLERY_IMAGE_NATIVE");
            }
        } catch(Throwable unavailable){attempts.append("GALLERY:").append(failure(unavailable)).append(';');}

        for(String kind:new String[]{"ORIGIN_IMAGE","MID_IMAGE"}) {
            if(source.containsKey("uri"))break;
            try {
                Object resource=XposedHelpers.callStaticMethod(XposedHelpers.findClass("pv5.e0",loader),"valueOf",kind);
                Object descriptor=XposedHelpers.callStaticMethod(XposedHelpers.findClass("pv5.x",loader),"a",msg,resource);
                String path=(String)XposedHelpers.callStaticMethod(XposedHelpers.findClass("pv5.y",loader),"g",descriptor,null,false,3,null);
                if(readable(context,uri(context,path),source)) {
                    source.putString("quality",kind);source.putString("readState","readable");break;
                }
                attempts.append(kind).append(":unreadable;");
            } catch(Throwable unavailable){attempts.append(kind).append(':').append(failure(unavailable)).append(';');}
        }
        source.putString("attempts",attempts.toString());
        if(!source.containsKey("uri")) {
            try {
                Object service=XposedHelpers.callStaticMethod(XposedHelpers.findClass("ni5.n0",loader),"c",XposedHelpers.findClass("no3.u0",loader));
                String thumb=(String)XposedHelpers.callMethod(service,"Pj",msg,XposedHelpers.callMethod(msg,"u0"),false,false);
                source.putString("thumb",thumb);source.putString("quality","thumbnail");
                // Legacy physical thumbnails may not be exportable; they remain a labelled diagnostic fallback.
                try{readable(context,uri(context,thumb),source);}catch(Throwable ignored){}
            } catch(Throwable unavailable){source.putString("readState",failure(unavailable));}
        }
        Uri selected=source.getParcelable("uri");
        if(selected!=null) {
            try{context.grantUriPermission("com.android.systemui",selected,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Throwable unavailable){source.remove("uri");source.putString("readState","grant:"+failure(unavailable));}
        }
        return source;
    }
    private static boolean nativePreview(Context context,String path,Bundle source) throws Exception {
        ClassLoader loader=context.getClassLoader();Class<?> vfs=XposedHelpers.findClass("com.tencent.mm.vfs.z6",loader);
        long bytes=(Long)XposedHelpers.callStaticMethod(vfs,"k",path);
        if(bytes<=0 || bytes>32L*1024*1024)return false;
        int slash=path.lastIndexOf('/');if(slash<1 || path.substring(slash+1).contains("&"))return false;
        String folder=path.substring(0,slash+1)+".nevo_notification_media/";
        XposedHelpers.callStaticMethod(vfs,"u",folder);
        String suffix="_"+source.getLong("id")+"_"+bytes+".png";
        Object directory=XposedHelpers.newInstance(XposedHelpers.findClass("com.tencent.mm.vfs.u6",loader),folder);
        Object[] existing=(Object[])XposedHelpers.callMethod(directory,"J");
        java.util.List<Object> retained=new java.util.ArrayList<>();long now=System.currentTimeMillis();
        if(existing!=null)for(Object entry:existing) {
            String name=(String)XposedHelpers.callMethod(entry,"getName");
            if(!name.matches("nevo_[0-9]+_[0-9]+_[0-9]+\\.png"))continue;
            long created=Long.parseLong(name.split("_")[1]);
            if(now-created>30*60_000L){XposedHelpers.callMethod(entry,"l");continue;}
            if(name.endsWith(suffix) && readable(context,uri(context,folder+name),source))return true;
            retained.add(entry);
        }
        retained.sort((left,right)->((String)XposedHelpers.callMethod(left,"getName")).compareTo((String)XposedHelpers.callMethod(right,"getName")));
        while(retained.size()>=16)XposedHelpers.callMethod(retained.remove(0),"l");
        Class<?> decoder=XposedHelpers.findClass("com.tencent.mm.graphics.e",loader);
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        Bitmap scratch=(Bitmap)XposedHelpers.callStaticMethod(decoder,"d",path,bounds);
        if(scratch!=null)scratch.recycle();
        int width=bounds.outWidth,height=bounds.outHeight;
        source.putString("nativePixels",width+"x"+height);
        if(width<1 || height<1 || (long)width*height>80_000_000L)return false;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;options.inScaled=false;
        while(width/options.inSampleSize>1600 || height/options.inSampleSize>8192 || (long)(width/options.inSampleSize)*(height/options.inSampleSize)>4_000_000L)options.inSampleSize*=2;
        Bitmap bitmap=(Bitmap)XposedHelpers.callStaticMethod(decoder,"d",path,options);
        if(bitmap==null)return false;
        String output=folder+"nevo_"+now+suffix;
        try {
            if(bitmap.getConfig()==Bitmap.Config.HARDWARE) {
                Bitmap software=bitmap.copy(Bitmap.Config.ARGB_8888,false);bitmap.recycle();bitmap=software;
            }
            if(bitmap==null)return false;
            try(java.io.OutputStream stream=(java.io.OutputStream)XposedHelpers.callStaticMethod(vfs,"J",output)) {
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))return false;
            }
        } finally {if(bitmap!=null)bitmap.recycle();}
        return readable(context,uri(context,output),source);
    }
    public static void tagPublished(Context context,Object item,Notification notification,String talker) {
        if(!installed || context==null || notification==null || talker==null)return;
        try {
            long id=XposedHelpers.getLongField(item,"i");if(id<=0)return;
            Object[] messages=recent(context,talker);if(messages==null)return;
            Object selected=null;
            for(Object msg:messages) {
                if(!talker.equals(XposedHelpers.callMethod(msg,"N0")))continue;
                if((Long)XposedHelpers.callMethod(msg,"getMsgId")!=id && (Long)XposedHelpers.callMethod(msg,"H0")!=id)continue;
                if(selected!=null){state="publication:ambiguous-id";return;} selected=msg;
            }
            if(selected==null){state="publication:message-not-found";return;}
            Bundle source=snapshot(context,selected);if(source==null)return;
            if(notification.extras==null)notification.extras=new Bundle();notification.extras.putBundle(SOURCE,source);
            state="publication:type="+source.getInt("type")+",source="+source.getString("quality")+",read="+source.getString("readState");
            if(source.getInt("type")==3 && "thumbnail".equals(source.getString("quality")))WeChatImageDownloads.request(context,selected);
            if(source.getInt("type")==47 && !source.containsKey("uri"))WeChatStickerDownloads.request(context,selected);
        } catch(Throwable unavailable){state="publication-unavailable:"+failure(unavailable);}
    }
    public static String requestRecentSticker(Context context,String talker) {
        try {
            Object[] list=recent(context,talker);
            if(list!=null)for(int i=list.length-1;i>=0;i--)if((((Integer)XposedHelpers.callMethod(list[i],"getType"))&0xffff)==47 && (Integer)XposedHelpers.callMethod(list[i],"z0")==0) {
                WeChatStickerDownloads.request(context,list[i]);return "queued-existing-sticker";
            }
            return "no-sticker";
        } catch(Throwable error){return failure(error);}
    }
    public static String requestRecent(Context context,String talker) {
        try {
            Object[] list=recent(context,talker);
            if(list!=null)for(int i=list.length-1;i>=0;i--)if((((Integer)XposedHelpers.callMethod(list[i],"getType"))&0xffff)==3 && (Integer)XposedHelpers.callMethod(list[i],"z0")==0) {
                WeChatImageDownloads.request(context,list[i]);return "queued-existing-image";
            }
            return "no-image";
        } catch(Throwable error){return failure(error);}
    }
    public static String inspect(Context context,String talker) {
        try {
            Object[] messages=recent(context,talker);StringBuilder result=new StringBuilder("recent:");
            if(messages!=null)for(Object msg:messages) {
                Bundle source=snapshot(context,msg);if(source==null)continue;
                result.append("type=").append(source.getInt("type")).append(",source=").append(source.getString("quality"))
                        .append(",pixels=").append(source.getInt("width")).append('x').append(source.getInt("height"))
                        .append(",bytes=").append(source.getLong("bytes")).append(",uri=").append(source.containsKey("uri"))
                        .append(",read=").append(source.getString("readState")).append(",complete=").append(source.getBoolean("complete")).append(",galleryVfs=").append(source.getBoolean("galleryVfs")).append(",galleryPixels=").append(source.getString("galleryPixels")).append(",nativePixels=").append(source.getString("nativePixels")).append(",attempts=").append(source.getString("attempts")).append(';');
            }
            return result.toString();
        } catch(Throwable unavailable){return "inspect-unavailable:"+failure(unavailable);}
    }
    public static Bitmap preview(Notification notification) {
        Bundle source=notification.extras.getBundle(SOURCE);if(source==null)return null;
        Uri uri=source.getParcelable("uri");
        Context context=com.oasisfeng.nevo.sdk.NevoDecoratorService.getAppContext();
        if(uri!=null && context!=null)try(ParcelFileDescriptor file=context.getContentResolver().openFileDescriptor(uri,"r")) {
            if(file!=null) {
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(Math.max(source.getInt("width"),source.getInt("height"))/options.inSampleSize>320)options.inSampleSize*=2;
                options.inPreferredConfig=Bitmap.Config.RGB_565;
                Bitmap bitmap=BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(),null,options);
                if(bitmap!=null){state="preview:ready:"+source.getString("quality");return bitmap;}
            }
        } catch(Throwable unavailable){state="preview-unavailable:"+failure(unavailable);}
        String thumb=source.getString("thumb");
        return thumb==null ? null : MainHook.loadImagePreview(thumb);
    }
    private static String failure(Throwable error) {
        for(int i=0;i<8 && error.getCause()!=null && error.getCause()!=error;i++)error=error.getCause();
        return error.getClass().getSimpleName();
    }
    public static String status(){return state+";builtinEmoji="+WeChatBuiltinEmoji.status()+";voice="+WeChatVoice.status();}
}
