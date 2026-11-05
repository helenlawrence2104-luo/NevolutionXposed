package com.oasisfeng.nevo.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.LruCache;
import de.robv.android.xposed.XposedHelpers;

/** Native spans stay in WeChat. Only a bounded transparent bitmap crosses into SystemUI. */
public final class WeChatBuiltinEmoji {
    private static boolean enabled;
    private static String state="not-installed";
    private static final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(2*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private WeChatBuiltinEmoji() {}
    static synchronized void install(String version){enabled="8.0.79".equals(version);}
    public static synchronized Bitmap render(Context context,CharSequence text) {
        if(!enabled || text==null || text.length()>1024 || TextUtils.indexOf(text,'[')<0 || TextUtils.indexOf(text,']')<0)return null;
        float density=context.getResources().getDisplayMetrics().density;
        float scaled=context.getResources().getDisplayMetrics().scaledDensity;
        String key=density+":"+scaled+":"+text;
        Bitmap cached=cache.get(key);if(cached!=null)return cached;
        try {
            Class<?> parser=XposedHelpers.findClass("com.tencent.mm.smiley.x1",context.getClassLoader());
            Object instance=XposedHelpers.callStaticMethod(parser,"Bj");
            CharSequence rich=(CharSequence)XposedHelpers.callMethod(instance,"Dj",text.toString(),true);
            if(!(rich instanceof Spanned) || ((Spanned)rich).getSpans(0,rich.length(),ReplacementSpan.class).length==0){state="no-native-span";return null;}
            Bitmap bitmap=rasterize(rich,density,scaled);
            if(bitmap!=null){cache.put(key,bitmap);state="native-ready:"+bitmap.getWidth()+"x"+bitmap.getHeight();}
            return bitmap;
        } catch(Throwable unavailable){state="unavailable:"+unavailable.getClass().getSimpleName();return null;}
    }
    static Bitmap rasterize(CharSequence rich,float density,float scaled) {
        if(rich==null || rich.length()==0 || rich.length()>1024)return null;
        TextPaint paint=new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(13*scaled);paint.setColor(0xff172026);
        int maximum=Math.max(1,Math.round(225*density));
        int width=Math.max(1,Math.min(maximum,(int)Math.ceil(Layout.getDesiredWidth(rich,paint))));
        StaticLayout layout=StaticLayout.Builder.obtain(rich,0,rich.length(),paint,width)
                .setIncludePad(true).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
        int height=Math.max(1,layout.getHeight());
        if((long)width*height>400_000)return null;
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        layout.draw(new Canvas(bitmap));return bitmap;
    }
    public static synchronized String status(){return state;}
}
