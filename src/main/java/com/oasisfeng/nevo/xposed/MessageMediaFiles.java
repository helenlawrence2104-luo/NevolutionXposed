package com.oasisfeng.nevo.xposed;

import android.os.Bundle;
import java.io.File;
import java.io.IOException;

/** Resolves only an exact message path within the live account roots. No directory scans. */
public final class MessageMediaFiles {
    private MessageMediaFiles() {}
    public static String resolveImage(Bundle source) {
        String path=source.getString("path");
        if (path==null || path.isEmpty() || source.getLong("id")<=0 || source.getInt("type")!=3) return null;
        for (String key:new String[]{"accountRoot","mediaRoot"}) {
            String root=source.getString(key);
            if (root==null || root.isEmpty()) continue;
            try {
                File account=new File(root).getCanonicalFile();
                File candidate=new File(path);
                if (candidate.isAbsolute()) {
                    if (contained(account,candidate)) return candidate.getCanonicalPath();
                } else if (path.matches("reader_[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9]+)?")) {
                    for (String suffix:new String[]{"",".jpg",".png"}) {
                        candidate=new File(new File(account,"image"),path+suffix);
                        if (contained(account,candidate)) return candidate.getCanonicalPath();
                    }
                }
            } catch (IOException | SecurityException ignored) { /* Keep native placeholder. */ }
        }
        return null;
    }
    private static boolean contained(File root,File candidate) throws IOException {
        File resolved=candidate.getCanonicalFile();
        return resolved.getPath().startsWith(root.getPath()+File.separator)
                && resolved.isFile() && resolved.length()>0 && resolved.length()<=16L*1024*1024;
    }
}
