package com.oasisfeng.nevo.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import de.robv.android.xposed.XposedHelpers;

/** Exact-message sticker lookup using WeChat's account-owned emoji database and decryptor. */
public final class WeChatStickers {
    private WeChatStickers() {}
    static String md5(String path,String content) {
        if(path!=null && path.matches("[0-9a-fA-F]{32}"))return path.toLowerCase(java.util.Locale.ROOT);
        if(content==null || content.length()>65536)return null;
        int start=content.indexOf('<');if(start<0)return null;
        try {
            org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
            parser.setInput(new java.io.StringReader(content.substring(start)));
            for(int event=parser.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=parser.next()) {
                if(event==org.xmlpull.v1.XmlPullParser.START_TAG && "emoji".equals(parser.getName())) {
                    String value=parser.getAttributeValue(null,"md5");
                    if(value==null || value.isEmpty())value=parser.getAttributeValue(null,"androidmd5");
                    return value!=null && value.matches("[0-9a-fA-F]{32}") ? value.toLowerCase(java.util.Locale.ROOT) : null;
                }
            }
        } catch(Exception ignored){}
        return null;
    }
    static void capture(Context context,Object message,Bundle source) {
        try {
            ClassLoader loader=context.getClassLoader();
            String id=md5((String)XposedHelpers.callMethod(message,"u0"),(String)XposedHelpers.callMethod(message,"j"));
            if(id==null){source.putString("readState","sticker-md5-unavailable");return;}
            Object storage=XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.tencent.mm.storage.m5",loader),"f");
            Object emoji=XposedHelpers.callMethod(XposedHelpers.callMethod(storage,"c"),"k3",id);
            if(emoji==null || !id.equals(XposedHelpers.callMethod(emoji,"getMd5"))){source.putString("readState","sticker-record-unavailable");return;}
            String path=(String)XposedHelpers.callMethod(emoji,"d1");
            Class<?> vfs=XposedHelpers.findClass("com.tencent.mm.vfs.z6",loader);
            long size=(Long)XposedHelpers.callStaticMethod(vfs,"k",path);
            if(size<=0 || size>16L*1024*1024){source.putString("readState","sticker-file-unavailable");return;}
            Object plugin=XposedHelpers.callStaticMethod(XposedHelpers.findClass("ni5.n0",loader),"c",XposedHelpers.findClass("c72.t",loader));
            Object manager=XposedHelpers.callMethod(plugin,"yj");
            byte[] data=(byte[])XposedHelpers.callMethod(manager,"E",emoji);
            if(data==null || data.length<10 || data.length>16*1024*1024){source.putString("readState","sticker-decode-unavailable");return;}
            boolean gif=data[0]=='G' && data[1]=='I' && data[2]=='F';
            boolean png=(data[0]&255)==137 && data[1]=='P' && data[2]=='N' && data[3]=='G';
            boolean webp=data[0]=='R' && data[1]=='I' && data[2]=='F' && data[3]=='F';
            boolean jpeg=(data[0]&255)==255 && (data[1]&255)==216;
            boolean standard=gif || png || webp || jpeg;
            String extension=gif ? ".gif" : webp ? ".webp" : jpeg ? ".jpg" : ".png";
            int slash=path.lastIndexOf('/');if(slash<1)return;
            String folder=path.substring(0,slash+1)+".nevo_notification_stickers/";
            XposedHelpers.callStaticMethod(vfs,"u",folder);
            Object directory=XposedHelpers.newInstance(XposedHelpers.findClass("com.tencent.mm.vfs.u6",loader),folder);
            Object[] existing=(Object[])XposedHelpers.callMethod(directory,"J");
            String suffix="_"+source.getLong("id")+"_"+data.length+extension;
            long now=System.currentTimeMillis();java.util.List<Object> retained=new java.util.ArrayList<>();
            if(existing!=null)for(Object file:existing) {
                String name=(String)XposedHelpers.callMethod(file,"getName");
                if(!name.matches("nevo_[0-9]+_[0-9]+_[0-9]+\\.(gif|webp|jpg|png)"))continue;
                long time=Long.parseLong(name.split("_")[1]);
                if(now-time>30*60_000L){XposedHelpers.callMethod(file,"l");continue;}
                if(name.endsWith(suffix) && finish(context,folder+name,source,gif || webp,standard))return;
                retained.add(file);
            }
            retained.sort((left,right)->((String)XposedHelpers.callMethod(left,"getName")).compareTo((String)XposedHelpers.callMethod(right,"getName")));
            while(retained.size()>=16)XposedHelpers.callMethod(retained.remove(0),"l");
            String output=folder+"nevo_"+now+suffix;
            try(java.io.OutputStream stream=(java.io.OutputStream)XposedHelpers.callStaticMethod(vfs,"J",output)) {
                if(standard)stream.write(data);
                else {
                    Bitmap frame=(Bitmap)XposedHelpers.callMethod(emoji,"e0",context,160);
                    if(frame==null){source.putString("readState","sticker-frame-unavailable");return;}
                    if(!frame.compress(Bitmap.CompressFormat.PNG,100,stream))return;
                }
            }
            if(!finish(context,output,source,gif || webp,standard))source.putString("readState","sticker-export-unavailable");
        } catch(Throwable failure){
            for(int i=0;i<8 && failure.getCause()!=null;i++)failure=failure.getCause();
            source.putString("readState","sticker-unavailable:"+failure.getClass().getSimpleName());
        }
    }
    private static boolean finish(Context context,String path,Bundle source,boolean animated,boolean standard) {
        Uri uri=WeChatMedia.uri(context,path);
        if(!WeChatMedia.readable(context,uri,source))return false;
        source.putString("quality",standard ? "STICKER_FILE" : "STICKER_NATIVE_FRAME");
        source.putString("kind","sticker");source.putBoolean("animated",animated);
        context.grantUriPermission("com.android.systemui",uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return true;
    }
}
