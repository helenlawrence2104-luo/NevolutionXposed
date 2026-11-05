package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.widget.RemoteViews;
import com.oasisfeng.nevo.xposed.ModuleViews;
import com.oasisfeng.nevo.xposed.R;
import java.util.ArrayList;
import java.util.List;

/** Bounded two-sided notification view. Only exact message metadata may supply media. */
public final class ChatNotificationRenderer {
    private static final int[] ROWS = {R.id.chat_row_0,R.id.chat_row_1,R.id.chat_row_2,R.id.chat_row_3,R.id.chat_row_4,R.id.chat_row_5,R.id.chat_row_6,R.id.chat_row_7,R.id.chat_row_8,R.id.chat_row_9,R.id.chat_row_10,R.id.chat_row_11,R.id.chat_row_12,R.id.chat_row_13,R.id.chat_row_14,R.id.chat_row_15,R.id.chat_row_16,R.id.chat_row_17,R.id.chat_row_18,R.id.chat_row_19};
    private static final int[] LEFT = {R.id.chat_left_0,R.id.chat_left_1,R.id.chat_left_2,R.id.chat_left_3,R.id.chat_left_4,R.id.chat_left_5,R.id.chat_left_6,R.id.chat_left_7,R.id.chat_left_8,R.id.chat_left_9,R.id.chat_left_10,R.id.chat_left_11,R.id.chat_left_12,R.id.chat_left_13,R.id.chat_left_14,R.id.chat_left_15,R.id.chat_left_16,R.id.chat_left_17,R.id.chat_left_18,R.id.chat_left_19};
    private static final int[] RIGHT = {R.id.chat_right_0,R.id.chat_right_1,R.id.chat_right_2,R.id.chat_right_3,R.id.chat_right_4,R.id.chat_right_5,R.id.chat_right_6,R.id.chat_right_7,R.id.chat_right_8,R.id.chat_right_9,R.id.chat_right_10,R.id.chat_right_11,R.id.chat_right_12,R.id.chat_right_13,R.id.chat_right_14,R.id.chat_right_15,R.id.chat_right_16,R.id.chat_right_17,R.id.chat_right_18,R.id.chat_right_19};
    private static final int[] LT = {R.id.chat_left_text_0,R.id.chat_left_text_1,R.id.chat_left_text_2,R.id.chat_left_text_3,R.id.chat_left_text_4,R.id.chat_left_text_5,R.id.chat_left_text_6,R.id.chat_left_text_7,R.id.chat_left_text_8,R.id.chat_left_text_9,R.id.chat_left_text_10,R.id.chat_left_text_11,R.id.chat_left_text_12,R.id.chat_left_text_13,R.id.chat_left_text_14,R.id.chat_left_text_15,R.id.chat_left_text_16,R.id.chat_left_text_17,R.id.chat_left_text_18,R.id.chat_left_text_19};
    private static final int[] RT = {R.id.chat_right_text_0,R.id.chat_right_text_1,R.id.chat_right_text_2,R.id.chat_right_text_3,R.id.chat_right_text_4,R.id.chat_right_text_5,R.id.chat_right_text_6,R.id.chat_right_text_7,R.id.chat_right_text_8,R.id.chat_right_text_9,R.id.chat_right_text_10,R.id.chat_right_text_11,R.id.chat_right_text_12,R.id.chat_right_text_13,R.id.chat_right_text_14,R.id.chat_right_text_15,R.id.chat_right_text_16,R.id.chat_right_text_17,R.id.chat_right_text_18,R.id.chat_right_text_19};
    private static final int[] LA = {R.id.chat_left_avatar_0,R.id.chat_left_avatar_1,R.id.chat_left_avatar_2,R.id.chat_left_avatar_3,R.id.chat_left_avatar_4,R.id.chat_left_avatar_5,R.id.chat_left_avatar_6,R.id.chat_left_avatar_7,R.id.chat_left_avatar_8,R.id.chat_left_avatar_9,R.id.chat_left_avatar_10,R.id.chat_left_avatar_11,R.id.chat_left_avatar_12,R.id.chat_left_avatar_13,R.id.chat_left_avatar_14,R.id.chat_left_avatar_15,R.id.chat_left_avatar_16,R.id.chat_left_avatar_17,R.id.chat_left_avatar_18,R.id.chat_left_avatar_19};
    private static final int[] RA = {R.id.chat_right_avatar_0,R.id.chat_right_avatar_1,R.id.chat_right_avatar_2,R.id.chat_right_avatar_3,R.id.chat_right_avatar_4,R.id.chat_right_avatar_5,R.id.chat_right_avatar_6,R.id.chat_right_avatar_7,R.id.chat_right_avatar_8,R.id.chat_right_avatar_9,R.id.chat_right_avatar_10,R.id.chat_right_avatar_11,R.id.chat_right_avatar_12,R.id.chat_right_avatar_13,R.id.chat_right_avatar_14,R.id.chat_right_avatar_15,R.id.chat_right_avatar_16,R.id.chat_right_avatar_17,R.id.chat_right_avatar_18,R.id.chat_right_avatar_19};
    private static final int[] LI = {R.id.chat_left_image_0,R.id.chat_left_image_1,R.id.chat_left_image_2,R.id.chat_left_image_3,R.id.chat_left_image_4,R.id.chat_left_image_5,R.id.chat_left_image_6,R.id.chat_left_image_7,R.id.chat_left_image_8,R.id.chat_left_image_9,R.id.chat_left_image_10,R.id.chat_left_image_11,R.id.chat_left_image_12,R.id.chat_left_image_13,R.id.chat_left_image_14,R.id.chat_left_image_15,R.id.chat_left_image_16,R.id.chat_left_image_17,R.id.chat_left_image_18,R.id.chat_left_image_19};
    private static final int[] RI = {R.id.chat_right_image_0,R.id.chat_right_image_1,R.id.chat_right_image_2,R.id.chat_right_image_3,R.id.chat_right_image_4,R.id.chat_right_image_5,R.id.chat_right_image_6,R.id.chat_right_image_7,R.id.chat_right_image_8,R.id.chat_right_image_9,R.id.chat_right_image_10,R.id.chat_right_image_11,R.id.chat_right_image_12,R.id.chat_right_image_13,R.id.chat_right_image_14,R.id.chat_right_image_15,R.id.chat_right_image_16,R.id.chat_right_image_17,R.id.chat_right_image_18,R.id.chat_right_image_19};
    private static final int[] LB={R.id.chat_left_bubble_0,R.id.chat_left_bubble_1,R.id.chat_left_bubble_2,R.id.chat_left_bubble_3,R.id.chat_left_bubble_4,R.id.chat_left_bubble_5,R.id.chat_left_bubble_6,R.id.chat_left_bubble_7,R.id.chat_left_bubble_8,R.id.chat_left_bubble_9,R.id.chat_left_bubble_10,R.id.chat_left_bubble_11,R.id.chat_left_bubble_12,R.id.chat_left_bubble_13,R.id.chat_left_bubble_14,R.id.chat_left_bubble_15,R.id.chat_left_bubble_16,R.id.chat_left_bubble_17,R.id.chat_left_bubble_18,R.id.chat_left_bubble_19};
    private static final int[] RB={R.id.chat_right_bubble_0,R.id.chat_right_bubble_1,R.id.chat_right_bubble_2,R.id.chat_right_bubble_3,R.id.chat_right_bubble_4,R.id.chat_right_bubble_5,R.id.chat_right_bubble_6,R.id.chat_right_bubble_7,R.id.chat_right_bubble_8,R.id.chat_right_bubble_9,R.id.chat_right_bubble_10,R.id.chat_right_bubble_11,R.id.chat_right_bubble_12,R.id.chat_right_bubble_13,R.id.chat_right_bubble_14,R.id.chat_right_bubble_15,R.id.chat_right_bubble_16,R.id.chat_right_bubble_17,R.id.chat_right_bubble_18,R.id.chat_right_bubble_19};
    public static int textId(int i,boolean self){return self ? RT[i] : LT[i];}
    public static int rowId(int i) { return ROWS[i]; }
    public static int imageId(int i,boolean self) { return self ? RI[i] : LI[i]; }
    private ChatNotificationRenderer() {}

    public static boolean isSelf(Bundle message) {
        Bundle metadata = message.getBundle("extras");
        CharSequence sender = message.getCharSequence("sender");
        return (metadata != null && metadata.getBoolean("nevo.self", false)) || (sender != null && sender.length() == 0);
    }

    public static RemoteViews compact(Context context, Notification notification) {
        RemoteViews views = ModuleViews.create(context, R.layout.inline_reply_compact);
        views.setTextViewText(R.id.inline_reply_title, notification.extras.getCharSequence("nevo.conversation.title"));
        views.setTextViewText(R.id.inline_reply_text, EmojiTranslator.translate(notification.extras.getCharSequence(Notification.EXTRA_TEXT)));
        return views;
    }

    public static RemoteViews render(Context context, Notification notification) {
        RemoteViews views = ModuleViews.create(context, R.layout.chat_notification);
        List<Bundle> messages = new ArrayList<>();
        Parcelable[] values = notification.extras.getParcelableArray("android.messages");
        if (values != null) for (Parcelable value : values) if (value instanceof Bundle) messages.add((Bundle) value);
        Icon friendIcon=notification.getLargeIcon();
        if(friendIcon!=null)notification.extras.putParcelable("nevo.chat.friend_icon",friendIcon);
        else friendIcon=notification.extras.getParcelable("nevo.chat.friend_icon");
        Bitmap friend = icon(context, friendIcon, 64);
        Bitmap self = null;
        if (Build.VERSION.SDK_INT >= 28) {
            android.app.Person user = notification.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
            if (user != null) self = icon(context, user.getIcon(), 64);
        }
        int start = Math.max(0, messages.size() - ROWS.length);
        int mediaHeight=140;
        for (int row = 0; row < ROWS.length; row++) {
            int index = start + row;
            boolean visible = index < messages.size();
            views.setViewVisibility(ROWS[row], visible ? View.VISIBLE : View.GONE);
            if (!visible) continue;
            Bundle message = messages.get(index);
            boolean mine = isSelf(message);
            views.setViewVisibility(LEFT[row], mine ? View.GONE : View.VISIBLE);
            views.setViewVisibility(RIGHT[row], mine ? View.VISIBLE : View.GONE);
            views.setTextViewText(mine ? RT[row] : LT[row], EmojiTranslator.translate(message.getCharSequence("text")));
            Bitmap avatar = mine ? self : friend;
            if (Build.VERSION.SDK_INT >= 28) {
                android.app.Person person = message.getParcelable("sender_person");
                if (person != null && person.getIcon() != null) avatar = icon(context, person.getIcon(), 64);
            }
            if (avatar != null) views.setImageViewBitmap(mine ? RA[row] : LA[row], avatar);
            Bundle metadata = message.getBundle("extras");
            Bundle voice=metadata!=null ? metadata.getBundle("nevo.voice") : null;
            if(voice!=null) {
                int textId=mine ? RT[row] : LT[row];
                views.setTextViewText(textId,com.oasisfeng.nevo.xposed.WeChatVoice.label(voice));
                views.setInt(textId,"setMaxLines",8);
                views.setContentDescription(textId,"语音，点击播放或停止");
                android.app.PendingIntent play=voice.getParcelable("play");
                if(play!=null)views.setOnClickPendingIntent(textId,play);
            }
            Bitmap media = metadata != null ? metadata.getParcelable("nevo.attachment.preview") : null;
            boolean hasFile=metadata!=null && metadata.getParcelable("nevo.attachment.uri")!=null;
            views.setViewVisibility(mine ? RI[row] : LI[row], media == null && !hasFile ? View.GONE : View.VISIBLE);
            if (media != null && !media.isRecycled()) views.setImageViewBitmap(mine ? RI[row] : LI[row], media);
            CharSequence text=message.getCharSequence("text");
            boolean placeholder=text!=null && (text.toString().contains("[图片]") || text.toString().contains("[动画表情]") || text.toString().contains("[表情]"));
            views.setViewVisibility(mine ? RT[row] : LT[row],(media!=null || hasFile) && placeholder ? View.GONE : View.VISIBLE);
            if(media==null && !hasFile) {
                Bitmap rich=com.oasisfeng.nevo.xposed.WeChatBuiltinEmoji.render(context,text);
                if(rich!=null) {
                    int image=mine ? RI[row] : LI[row];
                    views.setImageViewBitmap(image,rich);
                    views.setContentDescription(image,text);
                    views.setViewVisibility(image,View.VISIBLE);
                    views.setViewVisibility(mine ? RT[row] : LT[row],View.GONE);
                    if(Build.VERSION.SDK_INT>=31) {
                        views.setViewLayoutWidth(image,rich.getWidth(),android.util.TypedValue.COMPLEX_UNIT_PX);
                        views.setViewLayoutHeight(image,rich.getHeight(),android.util.TypedValue.COMPLEX_UNIT_PX);
                    }
                }
            }
            if(hasFile || (media!=null && !media.isRecycled())) {
                int bubble=mine ? RB[row] : LB[row];
                views.setInt(bubble,"setBackgroundColor",android.graphics.Color.TRANSPARENT);
                views.setViewPadding(bubble,0,0,0,0);
                if(Build.VERSION.SDK_INT>=31) {
                    boolean sticker=metadata!=null && "sticker".equals(metadata.getString("nevo.attachment.kind"));
                    int[] size=hasFile ? (sticker ? new int[]{112,112} : new int[]{210,mediaHeight}) : mediaSize(media.getWidth(),media.getHeight(),mediaHeight);
                    views.setViewLayoutWidth(mine ? RI[row] : LI[row],size[0],android.util.TypedValue.COMPLEX_UNIT_DIP);
                    views.setViewLayoutHeight(mine ? RI[row] : LI[row],size[1],android.util.TypedValue.COMPLEX_UNIT_DIP);
                }
            }
        }
        return views;
    }

    /** Expand a readable preview without stretching or cropping long screenshots. */
    public static int[] mediaSize(int width,int height) {
        return mediaSize(width,height,160);
    }
    public static int[] mediaSize(int width,int height,int maxHeight) {
        if(width<1 || height<1)return new int[]{1,1};
        float scale=Math.min(210f/width,Math.max(1,maxHeight)/(float)height);
        return new int[]{Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale))};
    }

    public static Bitmap nativePicture(Notification notification) {
        Object picture = notification.extras.getParcelable(Notification.EXTRA_PICTURE);
        return picture instanceof Bitmap && !((Bitmap) picture).isRecycled() ? bounded((Bitmap) picture, 192, 128) : null;
    }

    private static Bitmap icon(Context context, Icon icon, int size) {
        if (icon == null) return null;
        try {
            Drawable drawable = icon.loadDrawable(context);
            if (drawable == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0,0,size,size);
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        } catch (RuntimeException unavailable) { return null; }
    }

    private static Bitmap bounded(Bitmap source, int maxWidth, int maxHeight) {
        float scale = Math.min(1f, Math.min((float)maxWidth/source.getWidth(), (float)maxHeight/source.getHeight()));
        Bitmap result = Bitmap.createBitmap(Math.max(1,Math.round(source.getWidth()*scale)),Math.max(1,Math.round(source.getHeight()*scale)),Bitmap.Config.ARGB_8888);
        if (source.getConfig() == Bitmap.Config.HARDWARE) source = source.copy(Bitmap.Config.ARGB_8888,false);
        new Canvas(result).drawBitmap(source,null,new Rect(0,0,result.getWidth(),result.getHeight()),new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }
}
