package com.oasisfeng.nevo.xposed;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A small per-conversation label in the expanded notification's unused header space. */
public final class ChatCardHeader {
    private ChatCardHeader() {}
    public static String title(CharSequence nickname) {
        String name = nickname == null ? "" : nickname.toString().trim();
        return name.isEmpty() ? "" : name + "聊天框";
    }

    public static void bind(View root, CharSequence nickname) {
        if (!(root instanceof FrameLayout) || root.findViewById(R.id.chat_row_0) == null) return;
        String label = title(nickname);
        TextView header = root.findViewById(R.id.chat_card_header);
        if (label.isEmpty()) { if (header != null) header.setVisibility(View.GONE); return; }
        float density = root.getResources().getDisplayMetrics().density;
        if (header == null) {
            header = new TextView(root.getContext());
            header.setId(R.id.chat_card_header);
            header.setTextSize(13);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            header.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            int width = Math.min(root.getResources().getDisplayMetrics().widthPixels, Math.round(394 * density));
            header.setMaxWidth(Math.max(Math.round(64 * density), Math.min(Math.round(150 * density), width - Math.round(190 * density))));
            int color = Color.WHITE;
            int appName = root.getResources().getIdentifier("app_name_text", "id", "android");
            View nativeLabel = appName == 0 ? null : root.findViewById(appName);
            if (nativeLabel instanceof TextView) color = ((TextView) nativeLabel).getCurrentTextColor();
            else {
                android.content.res.TypedArray colors = root.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
                color = colors.getColor(0, color); colors.recycle();
            }
            header.setTextColor(color);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            params.topMargin = Math.round(18 * density);
            params.setMarginEnd(Math.round(16 * density));
            ((FrameLayout) root).addView(header, params);
        }
        header.setText(label);
        header.setVisibility(View.VISIBLE);
    }
}
