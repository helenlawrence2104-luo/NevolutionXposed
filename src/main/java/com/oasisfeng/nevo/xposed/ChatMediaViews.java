package com.oasisfeng.nevo.xposed;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Only installed inside this module's WeChat expanded card. No window or separate reply panel. */
@androidx.annotation.RequiresApi(28)
public final class ChatMediaViews {
    private static final ThreadPoolExecutor READERS=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"NevoPhotoReader");t.setDaemon(true);return t;});
    private static volatile String readState="not-requested";
    public static String status(){return readState;}
    private ChatMediaViews() {}

    public static void bind(View root,Notification notification) {
        View first=root.findViewById(R.id.chat_row_0);
        if(first==null || !(first.getParent() instanceof LinearLayout))return;
        HistoryScroll scroll=root.findViewById(R.id.chat_history_scroll);
        if(scroll==null) {
            LinearLayout original=(LinearLayout)first.getParent();
            LinearLayout rows=new LinearLayout(root.getContext());rows.setOrientation(LinearLayout.VERTICAL);
            scroll=new HistoryScroll(root.getContext());scroll.setId(R.id.chat_history_scroll);
            for(int i=0;i<20;i++) {
                View row=original.findViewById(ChatNotificationRenderer.rowId(i));
                if(row!=null && row.getParent()==original) { original.removeView(row);rows.addView(row); }
            }
            scroll.addView(rows,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            original.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Parcelable[] messages=notification.extras.getParcelableArray("android.messages");
        List<Bundle> filtered=new ArrayList<>();
        if(messages!=null)for(Parcelable item:messages)if(item instanceof Bundle)filtered.add((Bundle)item);
        List<PhotoView> photos=new ArrayList<>();
        int start=Math.max(0,filtered.size()-20);
        for(int i=start;i<filtered.size();i++) {
            Bundle message=filtered.get(i), metadata=message.getBundle("extras");
            Bundle voice=metadata!=null ? metadata.getBundle("nevo.voice") : null;
            if(voice!=null) {
                android.app.PendingIntent action=voice.getParcelable("play");
                View text=root.findViewById(ChatNotificationRenderer.textId(i-start,ChatNotificationRenderer.isSelf(message)));
                if(text!=null && action!=null && "com.tencent.mm".equals(action.getCreatorPackage()))text.setOnClickListener(v->{
                    try{action.send();}catch(android.app.PendingIntent.CanceledException expired){android.widget.Toast.makeText(v.getContext(),"这条语音已失效，请等待新通知",android.widget.Toast.LENGTH_SHORT).show();}
                });
            }
            Uri uri=metadata==null ? null : metadata.getParcelable("nevo.attachment.uri");
            if(uri==null || !"content".equals(uri.getScheme()) || !"com.tencent.mm.external.fileprovider".equals(uri.getAuthority()))continue;
            int imageId=ChatNotificationRenderer.imageId(i-start,ChatNotificationRenderer.isSelf(message));
            View image=root.findViewById(imageId);
            if(!(image instanceof ImageView) || !(image.getParent() instanceof ViewGroup))continue;
            PhotoView photo;
            if(image instanceof PhotoView)photo=(PhotoView)image;
            else {
                ViewGroup parent=(ViewGroup)image.getParent();int index=parent.indexOfChild(image);
                ViewGroup.LayoutParams params=image.getLayoutParams();
                photo=new PhotoView(image.getContext());photo.setId(imageId);
                photo.fallback=((ImageView)image).getDrawable();photo.setImageDrawable(photo.fallback);
                parent.removeViewAt(index);parent.addView(photo,index,params);
            }
            photo.bindSource(uri,metadata.getBoolean("nevo.attachment.animated"));photos.add(photo);
        }
        HistoryScroll history=scroll;
        Runnable refresh=()->{for(PhotoView photo:photos)photo.readIfVisible();};
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->refresh.run());
        scroll.post(()->{history.fullScroll(View.FOCUS_DOWN);history.post(refresh);});
    }

    public static String describe(View root) {
        if(root==null)return "absent";
        StringBuilder result=new StringBuilder();int count=0;
        for(int i=0;i<20;i++)for(boolean self:new boolean[]{false,true}) {
            View view=root.findViewById(ChatNotificationRenderer.imageId(i,self));
            if(!(view instanceof PhotoView))continue;
            PhotoView photo=(PhotoView)view;
            android.graphics.drawable.Drawable drawable=photo.getDrawable();
            if(count++>=4)return result.toString();
            result.append(i).append(':').append(photo.isShown()).append(':').append(drawable==null ? "none" : drawable.getClass().getSimpleName())
                    .append(":running=").append(drawable instanceof android.graphics.drawable.Animatable && ((android.graphics.drawable.Animatable)drawable).isRunning()).append(';');
        }
        return result.toString();
    }
    public static final class HistoryScroll extends ScrollView {
        public HistoryScroll(Context context) { super(context);setFillViewport(false);setNestedScrollingEnabled(true); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int cap=Math.round(230*getResources().getDisplayMetrics().density);
            if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED)cap=Math.min(cap,MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));
        }
    }

    public static final class PhotoView extends ImageView {
        Uri uri;android.graphics.drawable.Drawable fallback;
        private boolean loading,ready,animated;private int generation;
        private float zoom=1,tx,ty,lastX,lastY;
        private final Matrix matrix=new Matrix();
        private final ScaleGestureDetector pinch;
        private final GestureDetector taps;
        public PhotoView(Context context) {
            super(context);setScaleType(ScaleType.MATRIX);setContentDescription("图片，可双击或双指放大，拖动查看");
            pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    changeZoom(zoom*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;
                }
            });
            taps=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent event){return true;}
                @Override public boolean onDoubleTap(MotionEvent event){changeZoom(zoom>1.1f ? 1 : 2,event.getX(),event.getY());return true;}
            });
        }
        void bindSource(Uri source,boolean animate) {
            if(!source.equals(uri) || animated!=animate) {
                generation++;loading=false;ready=false;stopAnimation();setImageDrawable(fallback);zoom=1;tx=ty=0;
            }
            uri=source;animated=animate;
        }
        private void stopAnimation() {
            if(getDrawable() instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)getDrawable()).stop();
        }
        private void startAnimation() {
            if(getDrawable() instanceof android.graphics.drawable.Animatable)((android.graphics.drawable.Animatable)getDrawable()).start();
        }
        void readIfVisible() {
            if(!isAttachedToWindow() || !getGlobalVisibleRect(new Rect())){stopAnimation();return;}
            if(ready){startAnimation();return;}
            if(uri==null || loading)return;
            loading=true;readState="loading";int task=++generation;Uri source=uri;boolean animate=animated;
            try { READERS.execute(()->{
                android.graphics.drawable.Drawable drawable=null;
                try {
                    ImageDecoder.OnHeaderDecodedListener config=(decoder,info,input)->{
                        int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                        if(w<1 || h<1 || (long)w*h>80_000_000L)throw new IllegalArgumentException("image dimensions");
                        double scale=Math.min(1d,Math.min((animate ? 256d : 1200d)/w,Math.sqrt((animate ? 65_536d : 4_000_000d)/((long)w*h))));
                        decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    };
                    ImageDecoder.Source input=ImageDecoder.createSource(getContext().getContentResolver(),source);
                    if(animate)drawable=ImageDecoder.decodeDrawable(input,config);
                    else drawable=new android.graphics.drawable.BitmapDrawable(getResources(),ImageDecoder.decodeBitmap(input,config));
                } catch(java.io.IOException | RuntimeException | OutOfMemoryError unavailable) {readState="read-failed:"+unavailable.getClass().getSimpleName();}
                android.graphics.drawable.Drawable decoded=drawable;
                post(()->{
                    if(task!=generation || !source.equals(uri) || !isAttachedToWindow())return;
                    loading=false;
                    if(decoded!=null) {
                        ready=true;setImageDrawable(decoded);zoom=1;tx=ty=0;position();
                        if(isShown() && getGlobalVisibleRect(new Rect()))startAnimation();
                        readState=(decoded instanceof android.graphics.drawable.Animatable ? "animated-ready:" : "ready:")+decoded.getIntrinsicWidth()+"x"+decoded.getIntrinsicHeight();
                    }
                });
            }); } catch(java.util.concurrent.RejectedExecutionException busy){loading=false;}
        }
        private void changeZoom(float value,float x,float y) {
            float next=Math.max(1,Math.min(4,value)),ratio=next/zoom;
            tx=x-(x-tx)*ratio;ty=y-(y-ty)*ratio;zoom=next;position();
        }
        private void position() {
            android.graphics.drawable.Drawable drawable=getDrawable();
            if(drawable==null || drawable.getIntrinsicWidth()<1 || getWidth()<1)return;
            float scale=(float)getWidth()/drawable.getIntrinsicWidth()*zoom;
            float w=drawable.getIntrinsicWidth()*scale,h=drawable.getIntrinsicHeight()*scale;
            tx=Math.max(Math.min(0,getWidth()-w),Math.min(0,tx));
            ty=Math.max(Math.min(0,getHeight()-h),Math.min(0,ty));
            matrix.setScale(scale,scale);matrix.postTranslate(tx,ty);setImageMatrix(matrix);
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);position();}
        @Override public void onVisibilityAggregated(boolean visible){
            super.onVisibilityAggregated(visible);
            if(visible){startAnimation();post(this::readIfVisible);}
            else {stopAnimation();if(ready){generation++;loading=false;ready=false;setImageDrawable(fallback);}}
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(this::readIfVisible);}
        @Override protected void onDetachedFromWindow(){generation++;loading=false;ready=false;stopAnimation();setImageDrawable(fallback);super.onDetachedFromWindow();}
        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);taps.onTouchEvent(event);
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);break;
                case MotionEvent.ACTION_MOVE:
                    if(!pinch.isInProgress() && event.getPointerCount()==1) {
                        float dx=event.getX()-lastX,dy=event.getY()-lastY,old=ty;
                        tx+=dx;ty+=dy;position();
                        if(Math.abs(ty-old)<0.5f && Math.abs(dy)>Math.abs(dx))getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    lastX=event.getX();lastY=event.getY();break;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);break;
            }
            return true;
        }
    }
}
