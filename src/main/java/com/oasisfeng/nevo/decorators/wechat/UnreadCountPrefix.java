package com.oasisfeng.nevo.decorators.wechat;

/** Strict, allocation-light parser for WeChat unread-count prefixes. */
final class UnreadCountPrefix {
    final int count;
    /** First character after the closing bracket. */
    final int end;

    private UnreadCountPrefix(int count, int end) {
        this.count = count;
        this.end = end;
    }

    /** Returns null for ordinary message text or any malformed/overflowing count. */
    static UnreadCountPrefix parse(CharSequence text) {
        if (text == null || text.length() < 3 || text.charAt(0) != '[') return null;
        int closing = -1;
        // A positive int has at most 10 digits, plus an optional count suffix.
        for (int i = 1, limit = Math.min(text.length(), 13); i < limit; i++) {
            if (text.charAt(i) == ']') { closing = i; break; }
        }
        if (closing < 0) return null;
        int count = parseCount(text.subSequence(1, closing));
        return count > 0 ? new UnreadCountPrefix(count, closing + 1) : null;
    }

    static int parseCount(CharSequence prefix) {
        if (prefix == null || prefix.length() == 0) return 0;
        int length = prefix.length();
        char last = prefix.charAt(length - 1);
        if (last == '条' || last == '條' || last == '則' || last == '则') length--;
        if (length == 0 || length > 10) return 0;
        int count = 0;
        for (int i = 0; i < length; i++) {
            char ch = prefix.charAt(i);
            if (ch < '0' || ch > '9') return 0;
            int digit = ch - '0';
            if (count > (Integer.MAX_VALUE - digit) / 10) return 0;
            count = count * 10 + digit;
        }
        return count;
    }
}
