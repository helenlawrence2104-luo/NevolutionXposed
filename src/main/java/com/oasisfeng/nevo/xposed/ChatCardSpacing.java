package com.oasisfeng.nevo.xposed;

import android.view.View;
import android.view.ViewGroup;

/** Remove only this chat card's redundant lower custom-view margin. */
public final class ChatCardSpacing {
    private ChatCardSpacing() {}
    public static int trimBottom(View root) {
        if (root == null || root.findViewById(R.id.chat_row_0) == null
                || !(root.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return 0;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
        if (margins.bottomMargin > 0) {
            root.setTag(R.id.chat_card_removed_bottom, margins.bottomMargin);
            margins.bottomMargin = 0;
            root.setLayoutParams(margins);
        }
        Object removed = root.getTag(R.id.chat_card_removed_bottom);
        return removed instanceof Integer ? (Integer) removed : 0;
    }

    public static int extraHeight(int original, int removedBottom) {
        return Math.max(0, original - Math.max(0, removedBottom));
    }

    public static int inputReserve(int original, boolean barVisible, int barHeight, float barBottom) {
        int reserve = barVisible ? Math.max(0, Math.round(Math.max(0, barHeight) + Math.max(0, barBottom))) : 0;
        return Math.min(Math.max(0, original), reserve);
    }
}
