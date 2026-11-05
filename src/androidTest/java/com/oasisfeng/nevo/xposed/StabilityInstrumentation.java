package com.oasisfeng.nevo.xposed;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.LruCache;

import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs real Android Bundle/Notification cache tests without posting notifications or sending chats. */
public final class StabilityInstrumentation extends Instrumentation {
    private int assertions;
    private final List<String> passed = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            // Exercise only isolated module-process state; never call a real NotificationManager.
            NevoDecoratorService.LocalDecorator.setNM(null);
            clearCache();
            parcelableBundleSnapshots();
            passed.add("Parcelable[] nested Bundle snapshots isolate producer and reader mutations");
            historyAndEntryBounds();
            passed.add("Notification history bounded at 20; cache bounded at 50 with LRU eviction");
            cacheMissAndTagReuse();
            passed.add("Cache miss and explicit removal safe; reused ID with new tag resets history");
            conversationResetAndBounds();
            passed.add("Conversation reset clears identity/type/history stub; 50 conversation and 100 participant bounds");
            messageSerializationAndParcelableFiltering();
            passed.add("Explicit self sender survives serialization; friend identity and mixed Parcelable[] filtering preserved");
            selfAvatarSerialization();
            passed.add("Current-user avatar, name and account key survive MessagingStyle and framework notification serialization");
            twoSidedChatLayout();
            passed.add("Two-sided chat layout renders friend left, self right, correct avatars and message-linked image");
            originalReplySelection();
            passed.add("Visible reply entry selects only the original reply action and excludes itself and unrelated text");
            headsUpActionRow();
            passed.add("Heads-up native action row is hidden at RemoteViews creation while the visible entry and expanded reply remain");
            chatCardHeader();
            passed.add("Conversation header formats the current nickname, updates without duplication, and leaves unrelated notifications untouched");
            chatCardSpacing();
            passed.add("Chat spacing removes only lower margin and its height reservation, preserving header and unrelated cards");
            readStateGuard();
            passed.add("Read acknowledgement rejects changed recipient/account/new unread/sequence/expired snapshots");
            if(android.os.Build.VERSION.SDK_INT>=28)inlinePhotoInteraction();
            passed.add("Twenty history rows stay inside a bounded viewport and photo double-tap zooms in place");
            voiceIdentityAndRendering();
            passed.add("Voice controls parcel safely and update only the exact incoming voice, retaining newer messages and self replies");
            builtinEmojiRendering();
            passed.add("Built-in markers translate on archived rows; rich mixed text rasterizes within three lines and parcels safely");
            stickerIdentity();
            passed.add("Sticker identity comes only from the exact message path or emoji XML and rejects malformed values");
            downloadMessageGuard();
            passed.add("Downloaded image updates only its exact incoming message, preserving newer text and self replies");
            mediaHistoryRetention();
            passed.add("Image metadata survives duplicate archive rebuild and later text messages");
            mediaFileBoundaries();
            passed.add("Media files require exact message identity and stay within the live account root");
            result.putInt("assertions", assertions);
            result.putInt("tests", passed.size());
            result.putString("stream", "\nPASS: " + passed.size() + " Android stability tests, "
                    + assertions + " assertions\n" + String.join("\n", passed) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            result.putInt("assertions", assertions);
            result.putString("stream", "\nFAIL after " + assertions + " assertions: "
                    + android.util.Log.getStackTraceString(cause) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void voiceIdentityAndRendering() {
        android.content.Context context=getTargetContext();
        android.app.PendingIntent play=android.app.PendingIntent.getBroadcast(context,42,new android.content.Intent("isolated.voice.test").setPackage(context.getPackageName()),android.app.PendingIntent.FLAG_IMMUTABLE);
        Bundle voice=new Bundle();voice.putParcelable("play",play);voice.putInt("seconds",8);voice.putString("transState","transcribing");
        check(WeChatVoice.label(voice).contains("8秒") && WeChatVoice.label(voice).contains("正在转文字"),"voice progress label missing");
        voice.putString("transcript","测试转写");voice.putString("playState","playing");
        check(WeChatVoice.label(voice).contains("正在播放") && WeChatVoice.label(voice).contains("测试转写"),"voice transcript or playback state missing");
        Bundle source=new Bundle();source.putBundle("voice",voice);
        Bundle received=new Bundle();received.putCharSequence("text","[语音]");received.putCharSequence("sender","friend");
        Bundle metadata=new Bundle();metadata.putLong("nevo.attachment.message_id",93);received.putBundle("extras",metadata);
        Bundle newer=new Bundle();newer.putCharSequence("text","newer");newer.putCharSequence("sender","friend");
        Bundle self=new Bundle(received);self.putCharSequence("sender","");self.putBundle("extras",new Bundle(metadata));
        Notification notification=new Notification();notification.extras=new Bundle();notification.extras.putParcelableArray("android.messages",new Parcelable[]{received,newer,self});
        check(!WeChatImageDownloads.updateMessages(notification,92,source,null),"unrelated voice identity matched");
        check(WeChatImageDownloads.updateMessages(notification,93,source,null),"voice metadata was not attached");
        check(received.getBundle("extras").getBundle("nevo.voice")!=null,"voice state missing");
        check(self.getBundle("extras").getBundle("nevo.voice")==null,"incoming voice state applied to self");
        check("newer".contentEquals(newer.getCharSequence("text")),"voice refresh changed later text");
        android.graphics.Bitmap avatar=android.graphics.Bitmap.createBitmap(28,28,android.graphics.Bitmap.Config.ARGB_8888);
        avatar.eraseColor(android.graphics.Color.BLUE);
        notification=new Notification.Builder(context,"isolated").setLargeIcon(avatar).setExtras(notification.extras).build();
        android.widget.RemoteViews views=com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.render(context,notification);
        Notification refreshed=Notification.Builder.recoverBuilder(context,notification).setLargeIcon((android.graphics.drawable.Icon)null).build();
        android.view.View refreshedView=com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.render(context,refreshed).apply(context,null);
        check(((android.widget.ImageView)refreshedView.findViewById(R.id.chat_left_avatar_0)).getDrawable()!=null,"friend avatar lost after async refresh clears header icon");
        android.os.Parcel parcel=android.os.Parcel.obtain();
        try {
            views.writeToParcel(parcel,0);parcel.setDataPosition(0);
            android.view.View root=android.widget.RemoteViews.CREATOR.createFromParcel(parcel).apply(context,null);
            android.widget.TextView text=root.findViewById(R.id.chat_left_text_0);
            check(text.getText().toString().contains("测试转写") && text.hasOnClickListeners(),"voice text or click lost across SystemUI parcel");
            check(root.findViewById(R.id.chat_row_1).getVisibility()==android.view.View.VISIBLE,"newer message hidden by voice");
        } finally {parcel.recycle();play.cancel();}
    }

    private void builtinEmojiRendering() throws Exception {
        Class<?> translator=Class.forName("com.oasisfeng.nevo.decorators.wechat.EmojiTranslator");
        Method translate=translator.getDeclaredMethod("translate",CharSequence.class);translate.setAccessible(true);
        check(translate.invoke(null,new Object[]{null})==null,"null emoji text failed");
        check("😖".contentEquals((CharSequence)translate.invoke(null,"[撇嘴]")),"built-in marker missing");
        check("前😖😃后[未知]".contentEquals((CharSequence)translate.invoke(null,"前[撇嘴][微笑]后[未知]")),"mixed adjacent markers failed");
        android.text.SpannableString rich=new android.text.SpannableString("前[撇嘴]后[微笑]");
        android.graphics.drawable.ColorDrawable drawable=new android.graphics.drawable.ColorDrawable(0xffffcc00);
        drawable.setBounds(0,0,20,20);
        rich.setSpan(new android.text.style.ImageSpan(drawable),1,5,33);
        BitmapCheck(rich);
        Notification notification=new Notification();notification.extras=new Bundle();
        Bundle message=new Bundle();message.putCharSequence("text","前[撇嘴][微笑]后");message.putCharSequence("sender","friend");
        notification.extras.putParcelableArray("android.messages",new Parcelable[]{message});
        android.view.View view=com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.render(getTargetContext(),notification).apply(getTargetContext(),null);
        check("前😖😃后".contentEquals(((android.widget.TextView)view.findViewById(R.id.chat_left_text_0)).getText()),"archived row bypassed emoji rendering");
        check("前[撇嘴][微笑]后".contentEquals(message.getCharSequence("text")),"rendering changed original history");
    }
    private void BitmapCheck(CharSequence rich) {
        android.graphics.Bitmap bitmap=WeChatBuiltinEmoji.rasterize(rich,2,2);
        check(bitmap!=null && bitmap.getWidth()<=450 && bitmap.getHeight()<160,"rich emoji dimensions overflowed");
        android.os.Parcel parcel=android.os.Parcel.obtain();
        try {
            Bundle data=new Bundle();data.putParcelable("rich",bitmap);parcel.writeBundle(data);parcel.setDataPosition(0);
            android.graphics.Bitmap restored=parcel.readBundle(getClass().getClassLoader()).getParcelable("rich");
            check(restored!=null && restored.sameAs(bitmap),"rich emoji pixels lost across parcel");
        } finally {parcel.recycle();}
        check(WeChatBuiltinEmoji.rasterize("",2,2)==null,"empty raster should be skipped");
    }

    private void headsUpActionRow() {
        android.content.Context context = getTargetContext();
        android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(context, 7234,
                new android.content.Intent("nevo.test.never-send").setPackage(context.getPackageName()),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
        Notification.Action action = new Notification.Action.Builder(null, "回复", pending)
                .addRemoteInput(new android.app.RemoteInput.Builder("test").build()).build();
        Notification.Builder builder = new Notification.Builder(context,"local-test-only").setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("test").setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomHeadsUpContentView(new android.widget.RemoteViews(context.getPackageName(),R.layout.inline_reply_compact))
                .setCustomBigContentView(new android.widget.RemoteViews(context.getPackageName(),R.layout.inline_reply_compact))
                .addAction(action);
        android.widget.RemoteViews heads = builder.createHeadsUpContentView();
        check(heads != null,"heads-up wrapper missing");
        int actions = context.getResources().getIdentifier("actions","id","android");
        check(actions != 0,"native actions resource unavailable");
        InlineReplyVisibility.hideHeadsUpActions(heads);
        android.view.View view = heads.apply(context,new android.widget.FrameLayout(context));
        check(view.findViewById(actions) != null && view.findViewById(actions).getVisibility()==android.view.View.GONE,"heads-up native action row remains visible");
        check(view.findViewById(R.id.inline_reply_entry).getVisibility()==android.view.View.VISIBLE,"right entry hidden with native actions");
        android.view.View expanded=builder.createBigContentView().apply(context,new android.widget.FrameLayout(context));
        check(expanded.findViewById(actions).getVisibility()==android.view.View.VISIBLE,"expanded reply action row changed");
        pending.cancel();
    }

    @android.annotation.SuppressLint("UseSdkSuppress") // Custom Instrumentation runner guards the SDK explicitly.
    @androidx.annotation.RequiresApi(28)
    private void inlinePhotoInteraction() {
        final Throwable[] error={null};
        runOnMainSync(()->{try {
            android.content.Context context=getTargetContext();
            Notification notification=new Notification();notification.extras=new Bundle();
            Bundle[] messages=new Bundle[20];
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(100,300,android.graphics.Bitmap.Config.ARGB_8888);
            for(int i=0;i<20;i++) {
                messages[i]=new Bundle();messages[i].putCharSequence("text",i==0 ? "[图片]" : "history "+i);messages[i].putCharSequence("sender","friend");
                if(i==0) {
                    Bundle metadata=new Bundle();metadata.putParcelable("nevo.attachment.preview",bitmap);
                    metadata.putParcelable("nevo.attachment.uri",android.net.Uri.parse("content://com.tencent.mm.external.fileprovider/nevo-test-never-open"));
                    messages[i].putBundle("extras",metadata);
                }
            }
            notification.extras.putParcelableArray("android.messages",messages);
            android.widget.FrameLayout holder=new android.widget.FrameLayout(context);
            android.view.View root=com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.render(context,notification).apply(context,holder);
            ChatMediaViews.bind(root,notification);
            float density=context.getResources().getDisplayMetrics().density;
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(Math.round(360*density),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(Math.round(900*density),android.view.View.MeasureSpec.AT_MOST));
            root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
            android.view.View history=root.findViewById(R.id.chat_history_scroll);
            check(history!=null && history.getMeasuredHeight()<=Math.round(230*density),"history overflowed its viewport");
            check(root.findViewById(R.id.chat_row_19)!=null,"older message slots missing");
            android.widget.ImageView photo=root.findViewById(R.id.chat_left_image_0);
            check(photo instanceof ChatMediaViews.PhotoView,"inline photo interaction not installed");
            float[] before=new float[9],after=new float[9];photo.getImageMatrix().getValues(before);
            long now=android.os.SystemClock.uptimeMillis();
            for(int tap=0;tap<2;tap++) {
                long time=now+tap*100;
                android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,20,20,0);
                android.view.MotionEvent up=android.view.MotionEvent.obtain(time,time+20,android.view.MotionEvent.ACTION_UP,20,20,0);
                photo.onTouchEvent(down);photo.onTouchEvent(up);down.recycle();up.recycle();
            }
            photo.getImageMatrix().getValues(after);
            check(after[android.graphics.Matrix.MSCALE_X]>before[android.graphics.Matrix.MSCALE_X]*1.5f,"double tap did not enlarge photo");
            TestAnimation animation=new TestAnimation();photo.setImageDrawable(animation);
            photo.onVisibilityAggregated(true);check(animation.isRunning(),"visible animation was not started");
            photo.onVisibilityAggregated(false);check(!animation.isRunning(),"hidden animation kept running");
        } catch(Throwable failure){error[0]=failure;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }

    private static final class TestAnimation extends android.graphics.drawable.Drawable implements android.graphics.drawable.Animatable {
        boolean running;
        public void start(){running=true;}
        public void stop(){running=false;}
        public boolean isRunning(){return running;}
        public void draw(android.graphics.Canvas canvas){}
        public void setAlpha(int alpha){}
        public void setColorFilter(android.graphics.ColorFilter filter){}
        public int getOpacity(){return android.graphics.PixelFormat.TRANSPARENT;}
        public int getIntrinsicWidth(){return 24;}
        public int getIntrinsicHeight(){return 24;}
    }

    private void stickerIdentity() {
        String digest="0123456789abcdef0123456789abcdef";
        equal(digest,WeChatStickers.md5(digest.toUpperCase(java.util.Locale.ROOT),null),"message sticker identity not normalized");
        equal(digest,WeChatStickers.md5(null,"<msg><emoji md5=\""+digest+"\"/></msg>"),"emoji XML identity missing");
        equal(digest,WeChatStickers.md5(null,"<msg><emoji androidmd5=\""+digest+"\"/></msg>"),"android emoji identity missing");
        check(WeChatStickers.md5("../other-file","<msg><emoji md5=\"invalid\"/></msg>")==null,"malformed sticker identity accepted");
        check(WeChatStickers.md5(null,"<msg><unrelated md5=\""+digest+"\"/></msg>")==null,"unrelated XML node used as sticker");
    }

    private void downloadMessageGuard() {
        Notification notification=new Notification(); notification.extras=new Bundle();
        Bundle target=new Bundle(),other=new Bundle(),self=new Bundle(),latest=new Bundle();
        target.putCharSequence("text","[图片]");target.putCharSequence("sender","friend");
        Bundle metadata=new Bundle();metadata.putLong("nevo.attachment.message_id",111);target.putBundle("extras",metadata);
        other.putCharSequence("text","[图片]");other.putCharSequence("sender","friend");
        Bundle different=new Bundle();different.putLong("nevo.attachment.message_id",222);other.putBundle("extras",different);
        self.putCharSequence("text","already sent reply");self.putCharSequence("sender","");
        Bundle own=new Bundle();own.putLong("nevo.attachment.message_id",111);own.putBoolean("nevo.self",true);self.putBundle("extras",own);
        latest.putCharSequence("text","new incoming text");latest.putCharSequence("sender","friend");
        notification.extras.putParcelableArray("android.messages",new Bundle[]{target,other,self,latest});
        notification.extras.putCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY,new CharSequence[]{"already sent reply"});
        Bundle source=new Bundle();source.putParcelable("uri",android.net.Uri.parse("content://com.tencent.mm.external.fileprovider/local-only-test"));source.putString("quality","MID_IMAGE");
        check(!WeChatImageDownloads.updateMessages(notification,999,source,null),"unrelated message ID accepted");
        check(WeChatImageDownloads.updateMessages(notification,111,source,null),"matching image not updated");
        check(metadata.getParcelable("nevo.attachment.uri")!=null,"matching image URI lost");
        check(!different.containsKey("nevo.attachment.uri"),"download overwrote another image");
        check(!own.containsKey("nevo.attachment.uri"),"download overwrote own reply");
        equal("new incoming text",latest.getCharSequence("text").toString(),"new incoming text overwritten");
        check(notification.extras.getParcelableArray("android.messages").length==4,"download replaced the conversation");
        equal("already sent reply",notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)[0].toString(),"reply history overwritten");
    }

    private void mediaHistoryRetention() throws Exception {
        Class<?> type=Class.forName("com.oasisfeng.nevo.decorators.wechat.MessagingBuilder");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object builder=constructor.newInstance(getTargetContext(),getTargetContext(),null);
        try {
            Method merge=type.getDeclaredMethod("mergeTimeline",int.class,androidx.core.app.NotificationCompat.MessagingStyle.class); merge.setAccessible(true);
            Person user=new Person.Builder().setName("self").setKey("test-account").build();
            Person friend=new Person.Builder().setName("friend").build();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);
            Message photo=new Message("[图片]",1,friend); photo.getExtras().putParcelable("nevo.attachment.preview",bitmap);
            androidx.core.app.NotificationCompat.MessagingStyle first=new androidx.core.app.NotificationCompat.MessagingStyle(user).addMessage(photo);
            merge.invoke(builder,72345,first);
            androidx.core.app.NotificationCompat.MessagingStyle next=new androidx.core.app.NotificationCompat.MessagingStyle(user)
                    .addMessage(new Message("[图片]",1,friend)).addMessage(new Message("later text",2,friend));
            androidx.core.app.NotificationCompat.MessagingStyle restored=(androidx.core.app.NotificationCompat.MessagingStyle)merge.invoke(builder,72345,next);
            check(restored.getMessages().size()==2,"history rebuild duplicated media message");
            check(restored.getMessages().get(0).getExtras().getParcelable("nevo.attachment.preview")!=null,"later notification erased image metadata");
            Method flatten=type.getDeclaredMethod("flatIntoExtras",androidx.core.app.NotificationCompat.MessagingStyle.class,Bundle.class); flatten.setAccessible(true);
            Bundle extras=new Bundle(); flatten.invoke(null,restored,extras);
            Bundle image=(Bundle)extras.getParcelableArray("android.messages")[0];
            check(image.getBundle("extras").getParcelable("nevo.attachment.preview")!=null,"notification serialization dropped image metadata");
        } finally {
            for(String name:new String[]{"mReplyReceiver","mSyntheticReplyReceiver","mZoomReceiver"}) {
                Field receiver=type.getDeclaredField(name);receiver.setAccessible(true);
                getTargetContext().unregisterReceiver((android.content.BroadcastReceiver)receiver.get(builder));
            }
        }
    }

    private void mediaFileBoundaries() throws Exception {
        java.io.File root=new java.io.File(getTargetContext().getCacheDir(),"nevo-media-test");
        java.io.File images=new java.io.File(root,"image"); images.mkdirs();
        java.io.File image=new java.io.File(images,"reader_-255_test.jpg");
        java.io.File outside=new java.io.File(getTargetContext().getCacheDir(),"nevo-outside.jpg");
        try {
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)) { out.write(1); }
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(outside)) { out.write(1); }
            Bundle source=new Bundle(); source.putLong("id",123); source.putInt("type",3);
            source.putString("accountRoot",root.getAbsolutePath()); source.putString("path","reader_-255_test");
            check(image.getCanonicalPath().equals(MessageMediaFiles.resolveImage(source)),"exact reader path not resolved");
            source.putString("path",outside.getAbsolutePath());
            check(MessageMediaFiles.resolveImage(source)==null,"attachment outside account accepted");
            source.putString("path","../nevo-outside.jpg");
            check(MessageMediaFiles.resolveImage(source)==null,"relative traversal accepted");
            source.putString("path",image.getAbsolutePath()); source.putLong("id",0);
            check(MessageMediaFiles.resolveImage(source)==null,"attachment without message identity accepted");
            source.putLong("id",123); source.putInt("type",1);
            check(MessageMediaFiles.resolveImage(source)==null,"text message inherited stale image");
            source.putInt("type",3); source.putString("path","reader_-255_missing");
            check(MessageMediaFiles.resolveImage(source)==null,"missing attachment guessed");
        } finally { image.delete(); outside.delete(); images.delete(); root.delete(); }
    }

    private void readStateGuard() {
        WeChatReadState.Snapshot before=new WeChatReadState.Snapshot("account","friend",2,0,10,1000);
        check(WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("account","friend",2,0,10,1001)),"unchanged conversation rejected");
        check(!WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("other","friend",2,0,10,1001)),"changed account accepted");
        check(!WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("account","other",2,0,10,1001)),"changed recipient accepted");
        check(!WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("account","friend",3,0,10,1001)),"new unread message accepted");
        check(!WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("account","friend",2,1,10,1001)),"new muted unread accepted");
        check(!WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("account","friend",2,0,11,1001)),"new message sequence accepted");
        check(!WeChatReadState.unchanged(before,new WeChatReadState.Snapshot("account","friend",2,0,10,7000)),"expired read snapshot accepted");
        check(!WeChatReadState.unchanged(null,before),"missing snapshot accepted");
    }

    private void chatCardSpacing() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(getTargetContext());
        android.widget.FrameLayout.LayoutParams margins = new android.widget.FrameLayout.LayoutParams(300,400);
        margins.topMargin=32; margins.bottomMargin=32; margins.leftMargin=16; margins.rightMargin=16; root.setLayoutParams(margins);
        android.view.View chat = new android.view.View(getTargetContext()); chat.setId(R.id.chat_row_0); root.addView(chat);
        check(ChatCardSpacing.trimBottom(root)==32,"removed lower margin mismatch");
        check(margins.bottomMargin==0 && margins.topMargin==32 && margins.leftMargin==16 && margins.rightMargin==16,"spacing changed other edges");
        check(ChatCardSpacing.extraHeight(64,32)==32,"height reservation did not match remaining top margin");
        check(ChatCardSpacing.trimBottom(root)==32,"repeated layout lost removed margin");
        check(ChatCardSpacing.extraHeight(4,32)==0,"spacing produced negative height");
        check(ChatCardSpacing.inputReserve(72,false,12,24)==0,"hidden drag bar reserves duplicate input space");
        check(ChatCardSpacing.inputReserve(72,true,12,24)==36,"visible drag bar clearance lost");
        check(ChatCardSpacing.inputReserve(20,true,12,24)==20,"spacing increased native reservation");
        android.view.View unrelated = new android.view.View(getTargetContext());
        android.widget.FrameLayout.LayoutParams other = new android.widget.FrameLayout.LayoutParams(100,100); other.bottomMargin=32; unrelated.setLayoutParams(other);
        check(ChatCardSpacing.trimBottom(unrelated)==0 && other.bottomMargin==32,"unrelated card changed");
    }

    private void chatCardHeader() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(getTargetContext());
        android.widget.LinearLayout chat = new android.widget.LinearLayout(getTargetContext()); chat.setId(R.id.chat_row_0); root.addView(chat);
        ChatCardHeader.bind(root, "辅导员");
        android.widget.TextView header = root.findViewById(R.id.chat_card_header);
        check(header != null, "chat header missing");
        equal("辅导员聊天框", header.getText().toString(), "chat header label mismatch");
        check(header.getMaxLines() == 1, "long nickname may occupy multiple lines");
        ChatCardHeader.bind(root, "好友B");
        check(root.getChildCount() == 2, "header duplicated on update");
        equal("好友B聊天框", header.getText().toString(), "previous nickname leaked");
        ChatCardHeader.bind(root, " ");
        check(header.getVisibility() == android.view.View.GONE, "empty nickname produces misleading header");
        android.widget.FrameLayout unrelated = new android.widget.FrameLayout(getTargetContext());
        ChatCardHeader.bind(unrelated, "Other");
        check(unrelated.getChildCount() == 0, "unrelated notification changed");
    }

    private void originalReplySelection() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(getTargetContext());
        android.widget.TextView entry = new android.widget.TextView(getTargetContext());
        entry.setId(R.id.inline_reply_entry); entry.setText("回复"); entry.setOnClickListener(v -> {}); root.addView(entry);
        android.widget.Button nativeButton = new android.widget.Button(getTargetContext());
        nativeButton.setText("回复"); nativeButton.setOnClickListener(v -> {}); root.addView(nativeButton);
        android.widget.TextView message = new android.widget.TextView(getTargetContext()); message.setText("回复"); root.addView(message);
        List<android.view.View> found = new ArrayList<>();
        InlineReplyVisibility.findOriginal(root, "回复", found, 0);
        check(found.size() == 1 && found.get(0) == nativeButton, "must select only the bound native reply button");
        found.clear(); InlineReplyVisibility.findOriginal(root, "other", found, 0);
        check(found.isEmpty(), "unrelated action selected");
        found.clear(); InlineReplyVisibility.findOriginal(null, "回复", found, 0);
        check(found.isEmpty(), "missing tree must be safe");
        Notification.Action replyAction = new Notification.Action.Builder(null, "回复", null)
                .addRemoteInput(new android.app.RemoteInput.Builder("test").setLabel("回复").build()).build();
        InlineReplyVisibility.hideDuplicateActions(root, replyAction);
        check(nativeButton.getVisibility() == android.view.View.GONE, "duplicate native button must be hidden");
        check(entry.getVisibility() == android.view.View.VISIBLE, "visible entry must stay visible");
        check(message.getVisibility() == android.view.View.VISIBLE, "message text must not be hidden");
        // The expanded child is intentionally left untouched: hideDuplicateActions is only
        // invoked from the setHeadsUpChild path, so expanded native replies stay visible.
        android.widget.LinearLayout expanded = new android.widget.LinearLayout(getTargetContext());
        android.widget.Button expandedReply = new android.widget.Button(getTargetContext());
        expandedReply.setText("回复"); expanded.addView(expandedReply);
        InlineReplyVisibility.hideDuplicateActions(expanded, replyAction);
        check(expandedReply.getVisibility() == android.view.View.GONE, "expanded native reply hidden by direct call");
        Notification notification = new Notification.Builder(getTargetContext(),"local-test-only").setSmallIcon(android.R.drawable.stat_notify_chat).build();
        check(InlineReplyVisibility.replyAction(notification) == null, "notification without reply must not gain an entry");
    }

    private void twoSidedChatLayout() {
        android.graphics.Bitmap mine = android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Bitmap friend = android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888);
        mine.eraseColor(android.graphics.Color.BLUE); friend.eraseColor(android.graphics.Color.RED);
        android.app.Person self = new android.app.Person.Builder().setName("我").setKey("test-account")
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(mine)).build();
        Bundle incoming = new Bundle(); incoming.putCharSequence("text","friend message"); incoming.putCharSequence("sender","friend");
        Bundle outgoing = new Bundle(); outgoing.putCharSequence("text","self message"); outgoing.putCharSequence("sender","");
        Bundle metadata = new Bundle(); metadata.putBoolean("nevo.self",true); outgoing.putBundle("extras",metadata);
        Bundle attachment = new Bundle(); attachment.putParcelable("nevo.attachment.preview",friend); incoming.putBundle("extras",attachment);
        Notification notification = new Notification.Builder(getTargetContext(),"local-test-only").setSmallIcon(android.R.drawable.stat_notify_more)
                .setLargeIcon(friend).build();
        notification.extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON,self);
        notification.extras.putParcelableArray("android.messages",new Bundle[]{incoming,outgoing});
        android.widget.RemoteViews remote = com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.render(getTargetContext(),notification);
        android.view.View layout = remote.apply(getTargetContext(), new android.widget.FrameLayout(getTargetContext()));
        check(layout.findViewById(R.id.chat_left_0).getVisibility()==android.view.View.VISIBLE,"friend must be left");
        check(layout.findViewById(R.id.chat_right_0).getVisibility()==android.view.View.GONE,"friend rendered on right");
        check(layout.findViewById(R.id.chat_right_1).getVisibility()==android.view.View.VISIBLE,"self must be right");
        check(layout.findViewById(R.id.chat_left_1).getVisibility()==android.view.View.GONE,"self rendered on left");
        equal("friend message",((android.widget.TextView)layout.findViewById(R.id.chat_left_text_0)).getText().toString(),"friend text lost");
        equal("self message",((android.widget.TextView)layout.findViewById(R.id.chat_right_text_1)).getText().toString(),"self text lost");
        android.graphics.drawable.BitmapDrawable selfAvatar=(android.graphics.drawable.BitmapDrawable)((android.widget.ImageView)layout.findViewById(R.id.chat_right_avatar_1)).getDrawable();
        android.graphics.drawable.BitmapDrawable friendAvatar=(android.graphics.drawable.BitmapDrawable)((android.widget.ImageView)layout.findViewById(R.id.chat_left_avatar_0)).getDrawable();
        check(selfAvatar.getBitmap().getPixel(0,0)==android.graphics.Color.BLUE,"self avatar mixed with friend");
        check(friendAvatar.getBitmap().getPixel(0,0)==android.graphics.Color.RED,"friend avatar mixed with self");
        check(layout.findViewById(R.id.chat_left_image_0).getVisibility()==android.view.View.VISIBLE,"message image missing");
        android.view.View bubble=layout.findViewById(R.id.chat_left_bubble_0);
        check(bubble.getPaddingLeft()==0 && bubble.getPaddingRight()==0,"media retained text bubble padding");
        check(((android.graphics.drawable.ColorDrawable)bubble.getBackground()).getColor()==android.graphics.Color.TRANSPARENT,"media retained white background");
        int[] portrait=com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.mediaSize(100,300);
        check(portrait[0]==53 && portrait[1]==160,"portrait preview stretched or not enlarged");
        int[] landscape=com.oasisfeng.nevo.decorators.wechat.ChatNotificationRenderer.mediaSize(300,100);
        check(landscape[0]==210 && landscape[1]==70,"landscape preview cropped or stretched");
        check(layout.findViewById(R.id.chat_right_image_1).getVisibility()==android.view.View.GONE,"image leaked into another message");
    }

    private void selfAvatarSerialization() throws Exception {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.BLUE);
        Person self = new Person.Builder().setName("我").setKey("local-account-test")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap)).build();
        androidx.core.app.NotificationCompat.MessagingStyle style = new androidx.core.app.NotificationCompat.MessagingStyle(self);
        style.addMessage(new Message("local self preview", 1, (Person) null));
        Bundle extras = new Bundle();
        Class<?> builder = Class.forName("com.oasisfeng.nevo.decorators.wechat.MessagingBuilder");
        Method flatten = builder.getDeclaredMethod("flatIntoExtras", androidx.core.app.NotificationCompat.MessagingStyle.class, Bundle.class);
        flatten.setAccessible(true);
        flatten.invoke(null, style, extras);
        android.app.Person nativeSelf = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        check(nativeSelf != null, "framework self Person missing");
        check(nativeSelf.getIcon() != null, "framework self avatar missing");
        equal("我", nativeSelf.getName().toString(), "self display name lost");
        equal("local-account-test", nativeSelf.getKey(), "self account key lost");
        Notification notification = new Notification.Builder(getTargetContext(), "local-test-only")
                .setSmallIcon(android.R.drawable.stat_notify_more).addExtras(extras).build();
        android.app.Person retained = notification.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        check(retained != null && retained.getIcon() != null, "notification build lost self avatar");
        check(!bitmap.isRecycled(), "serialization recycled shared source bitmap");
        Message explicitSelf = new Message("local explicit self preview", 2, self);
        explicitSelf.getExtras().putBoolean("nevo.self", true);
        Method serialize = builder.getDeclaredMethod("toBundle", Message.class);
        serialize.setAccessible(true);
        Bundle explicit = (Bundle) serialize.invoke(null, explicitSelf);
        equal("", explicit.getCharSequence("sender").toString(), "explicit self avatar lost self marker");
        android.app.Person explicitPerson = explicit.getParcelable("sender_person");
        check(explicitPerson != null && explicitPerson.getIcon() != null, "self message must carry its own avatar");
        equal("local-account-test", explicitPerson.getKey(), "explicit message avatar belongs to wrong account");

    }

    private void parcelableBundleSnapshots() {
        final int id = 1;
        Notification source = notification(7);
        Bundle nested = new Bundle();
        nested.putString("value", "original-nested");
        Bundle message = new Bundle();
        message.putString("text", "original-message");
        message.putBundle("nested", nested);
        source.extras.putParcelableArray("android.messages", new Parcelable[] { message });
        source.extras.putBundle("direct", nested);
        source.extras.putCharSequenceArray("android.remoteInputHistory", new CharSequence[] { "original-history" });
        Access.store(id, "chat-A", source);

        // Producer mutations after cache write must not change the archived notification.
        source.when = 99;
        message.putString("text", "producer-mutated");
        nested.putString("value", "producer-mutated");
        source.extras.getCharSequenceArray("android.remoteInputHistory")[0] = "producer-mutated";
        Notification first = Access.latest(id);
        check(first != source, "cache must detach Notification object");
        check(first.when == 7, "Notification scalar snapshot changed with producer");
        Bundle cachedMessage = (Bundle) first.extras.getParcelableArray("android.messages")[0];
        equal("original-message", cachedMessage.getString("text"), "Parcelable Bundle must detach on write");
        equal("original-nested", cachedMessage.getBundle("nested").getString("value"), "nested Bundle must detach on write");
        equal("original-nested", first.extras.getBundle("direct").getString("value"), "direct Bundle must detach on write");
        equal("original-history", first.extras.getCharSequenceArray("android.remoteInputHistory")[0], "history array must detach on write");

        // Reader mutations must not change future readers either.
        first.when = 123;
        cachedMessage.putString("text", "reader-mutated");
        cachedMessage.getBundle("nested").putString("value", "reader-mutated");
        first.extras.getCharSequenceArray("android.remoteInputHistory")[0] = "reader-mutated";
        Notification second = Access.latest(id);
        check(second.when == 7, "cache must detach scalar fields on read");
        Bundle secondMessage = (Bundle) second.extras.getParcelableArray("android.messages")[0];
        equal("original-message", secondMessage.getString("text"), "Parcelable Bundle must detach on read");
        equal("original-nested", secondMessage.getBundle("nested").getString("value"), "nested Bundle must detach on read");
        equal("original-history", second.extras.getCharSequenceArray("android.remoteInputHistory")[0], "history must detach on read");
        List<Notification> list = Access.history(id);
        list.get(0).extras.putString("text", "list-mutated");
        list.clear();
        check(Access.history(id).size() == 1, "returned history list must be isolated");
        equal("row-7", Access.latest(id).extras.getString("text"), "history item must be isolated");
    }

    private void historyAndEntryBounds() throws Exception {
        clearCache();
        for (int i = 0; i < 25; i++) Access.store(2, "same-chat", notification(i));
        List<Notification> history = Access.history(2);
        check(history.size() == 20, "history should retain only 20 entries");
        check(history.get(0).when == 5, "oldest excess entries must be discarded");
        check(history.get(19).when == 24, "newest entry must be retained");
        clearCache();
        for (int i = 0; i < 60; i++) Access.store(1000 + i, "tag-" + i, notification(i));
        check(localCache().size() == 50, "notification cache must be capped at 50 IDs");
        check(Access.latest(1000) == null, "least-recently-used ID must be evicted");
        check(Access.latest(1059) != null, "most-recently-used ID must remain");
        // A read must influence LRU order, protecting an actively used conversation.
        check(Access.latest(1010) != null, "first retained ID should exist");
        Access.store(1060, "tag-new", notification(60));
        check(Access.latest(1010) != null, "read should refresh LRU recency");
        check(Access.latest(1011) == null, "next least-recently-used ID should be evicted");
    }

    private void cacheMissAndTagReuse() throws Exception {
        clearCache();
        check(Access.latest(999) == null, "cache miss must return null");
        check(Access.history(999).isEmpty(), "cache miss history must be empty");
        check(!Access.has(999), "cache miss must not report available history");
        Access.remove(999); // No exception or side effect for an unknown key.
        Access.store(3, "old-tag", notification(1));
        Access.store(3, "old-tag", notification(2));
        check(Access.history(3).size() == 2, "same tag should preserve history");
        Access.store(3, "new-tag", notification(3));
        check(Access.history(3).size() == 1, "new tag must reset reused-ID history");
        check(Access.latest(3).when == 3, "new tag must own the cached notification");
        Access.remove(3);
        check(Access.latest(3) == null, "explicit removal must forget archive");
        check(!Access.has(3), "removed archive must not be advertised");
        Access.store(4, null, notification(4));
        Access.store(4, "named-tag", notification(5));
        check(Access.history(4).size() == 1, "null-to-named tag transition must reset history");
        Access.store(4, null, notification(6));
        check(Access.history(4).size() == 1, "named-to-null tag transition must reset history");
    }

    private void conversationResetAndBounds() throws Exception {
        Class<?> type = Class.forName("com.oasisfeng.nevo.decorators.wechat.ConversationManager");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object manager = constructor.newInstance();
        Method get = type.getDeclaredMethod("getConversation", int.class);
        Method remove = type.getDeclaredMethod("removeConversation", int.class);
        get.setAccessible(true);
        remove.setAccessible(true);
        Object original = get.invoke(manager, 8);
        Field key = field(original.getClass(), "key");
        Field title = field(original.getClass(), "title");
        key.set(original, "old-recipient");
        title.set(original, "old-title");
        Method originalSetType = original.getClass().getDeclaredMethod("setType", int.class);
        originalSetType.setAccessible(true);
        originalSetType.invoke(original, 2);
        remove.invoke(manager, 8);
        Object replacement = get.invoke(manager, 8);
        check(replacement != original, "reset must replace the conversation object");
        check(key.get(replacement) == null, "reset must forget old recipient identity");
        check(title.get(replacement) == null, "reset must forget old title");
        Method getType = replacement.getClass().getDeclaredMethod("getType");
        getType.setAccessible(true);
        check(((Integer) getType.invoke(replacement)) == 0, "reset type must be unknown");
        for (int i = 0; i < 60; i++) get.invoke(manager, 2000 + i);
        LruCache<?, ?> conversations = (LruCache<?, ?>) field(type, "mConversations").get(manager);
        check(conversations.size() == 50, "conversation cache must be bounded at 50");
        check(!conversations.snapshot().containsKey(2000), "old conversation state must be evicted");
        Object group = get.invoke(manager, 3000);
        Method setType = group.getClass().getDeclaredMethod("setType", int.class);
        Method participant = group.getClass().getDeclaredMethod("getGroupParticipant", String.class, String.class);
        setType.setAccessible(true);
        participant.setAccessible(true);
        setType.invoke(group, 2);
        for (int i = 0; i < 110; i++) participant.invoke(group, "participant-" + i, "Name-" + i);
        Map<?, ?> participants = (Map<?, ?>) field(group.getClass(), "mParticipants").get(group);
        check(participants.size() == 100, "group participants must be bounded at 100");
        check(!participants.containsKey("participant-0"), "oldest inactive participant must be evicted");
        check(participants.containsKey("participant-109"), "newest participant must remain");
    }

    private void messageSerializationAndParcelableFiltering() throws Exception {
        Class<?> builder = Class.forName("com.oasisfeng.nevo.decorators.wechat.MessagingBuilder");
        Method serialize = builder.getDeclaredMethod("toBundle", Message.class);
        Method filter = builder.getDeclaredMethod("messageBundles", Bundle.class);
        serialize.setAccessible(true);
        filter.setAccessible(true);

        Message self = new Message("local-self", 11L, (Person) null);
        Bundle selfBundle = (Bundle) serialize.invoke(null, self);
        check(selfBundle.containsKey("sender"), "self serialization must retain an explicit sender key");
        equal("", selfBundle.getCharSequence("sender"), "self sender must be the empty marker, not absent");
        check(!selfBundle.containsKey("sender_person"), "self serialization must not invent a friend Person");
        Person friend = new Person.Builder().setName("Local friend").build();
        Message peer = new Message("local-peer", 12L, friend);
        Bundle peerBundle = (Bundle) serialize.invoke(null, peer);
        equal("Local friend", peerBundle.getCharSequence("sender"), "friend sender must survive serialization");

        Bundle extras = new Bundle();
        Parcelable[] values = new Parcelable[] { selfBundle, new Notification(), null, peerBundle };
        extras.putParcelableArray("android.messages", values);
        check(extras.getParcelableArray("android.messages").getClass() == Parcelable[].class,
                "regression input must be a Parcelable[] runtime type, not Bundle[]");
        Bundle[] messages = (Bundle[]) filter.invoke(null, extras);
        check(messages.length == 2, "mixed Parcelable[] should retain only valid Bundle messages");
        equal("", messages[0].getCharSequence("sender"), "filtered self message must remain first");
        equal("Local friend", messages[1].getCharSequence("sender"), "filtered friend message must remain second");
        check(((Bundle[]) filter.invoke(null, new Bundle())).length == 0,
                "missing Parcelable[] history must produce an empty Bundle[]");
        extras.putParcelableArray("android.messages", new Parcelable[] { new Notification(), null });
        check(((Bundle[]) filter.invoke(null, extras)).length == 0,
                "all-invalid Parcelable[] history must produce an empty Bundle[]");
    }

    private static Notification notification(int sequence) {
        Notification n = new Notification();
        n.when = sequence;
        n.extras = new Bundle();
        n.extras.putString("text", "row-" + sequence);
        return n;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static LruCache<?, ?> localCache() throws Exception {
        return (LruCache<?, ?>) field(NevoDecoratorService.LocalDecorator.class, "cache").get(null);
    }

    private static void clearCache() throws Exception { localCache().evictAll(); }

    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private static final class Access extends NevoDecoratorService.LocalDecorator {
        Access() { super("stability-test"); }
        static void store(int id, String tag, Notification n) { cache(id, tag, n); }
        static Notification latest(int id) { return getArchivedNotification(id); }
        static List<Notification> history(int id) { return getArchivedNotifications(id); }
        static boolean has(int id) { return hasArchivedNotifications(id); }
        static void remove(int id) { removeArchivedNotification(id); }
    }
}
