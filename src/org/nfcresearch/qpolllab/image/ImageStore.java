package org.nfcresearch.qpolllab.image;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Persists the multi-image library and each image's independent settings. */
public final class ImageStore {
    public static final class Entry {
        public final String id;
        public String path;
        public int rotation;
        public boolean cover;
        public boolean dither;
        public int blockSize;
        public boolean binary;
        public int threshold;
        public boolean cropped;
        public String cropPath;

        Entry(String id) {
            this.id = id;
            rotation = 0;
            cover = false;
            dither = false;
            blockSize = 1;
            binary = false;
            threshold = 128;
            cropped = false;
            cropPath = "";
        }
    }

    private final Context context;
    private final AppSettings settings;
    private final File root;
    private final File metaFile;
    private final List<Entry> entries = new ArrayList<>();

    public ImageStore(Context context, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        root = new File(context.getFilesDir(), "images");
        if (!root.exists()) root.mkdirs();
        metaFile = new File(root, "index.json");
        load();
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Entry get(int index) {
        if (index < 0 || index >= entries.size()) return null;
        return entries.get(index);
    }

    public synchronized int selectedIndex() {
        if (entries.isEmpty()) return 0;
        return Math.min(settings.imageIndex(), entries.size() - 1);
    }

    public synchronized Entry selected() {
        return get(selectedIndex());
    }

    public synchronized void select(int index) {
        if (entries.isEmpty()) {
            settings.setImageIndex(0);
            return;
        }
        settings.setImageIndex(Math.max(0, Math.min(entries.size() - 1, index)));
    }

    public synchronized Entry addFromUri(Uri uri) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        File dest = new File(root, id + ".jpg");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IllegalArgumentException("无法读取图片");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        Entry entry = new Entry(id);
        entry.path = dest.getAbsolutePath();
        entries.add(entry);
        select(entries.size() - 1);
        save();
        return entry;
    }

    public synchronized Entry addTestPattern(Bitmap bitmap) throws Exception {
        String id = "test-" + System.currentTimeMillis();
        File dest = new File(root, id + ".png");
        try (FileOutputStream out = new FileOutputStream(dest)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        Entry entry = new Entry(id);
        entry.path = dest.getAbsolutePath();
        entries.add(entry);
        select(entries.size() - 1);
        save();
        return entry;
    }

    public synchronized void remove(int index) {
        if (index < 0 || index >= entries.size()) return;
        Entry entry = entries.remove(index);
        deleteQuiet(entry.path);
        deleteQuiet(entry.cropPath);
        select(Math.min(index, Math.max(0, entries.size() - 1)));
        save();
    }

    public synchronized void saveEntry(Entry entry) {
        save();
    }

    public synchronized void setCropped(Entry entry, Bitmap croppedBitmap) throws Exception {
        File dest = new File(root, entry.id + "-crop.png");
        try (FileOutputStream out = new FileOutputStream(dest)) {
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        deleteQuiet(entry.cropPath);
        entry.cropPath = dest.getAbsolutePath();
        entry.cropped = true;
        save();
    }

    public synchronized void clearCrop(Entry entry) {
        deleteQuiet(entry.cropPath);
        entry.cropPath = "";
        entry.cropped = false;
        entry.rotation = 0;
        entry.cover = true;
        save();
    }

    public Bitmap loadBitmap(Entry entry) throws Exception {
        String path = entry.cropped && entry.cropPath != null && !entry.cropPath.isEmpty()
                ? entry.cropPath : entry.path;
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) throw new IllegalArgumentException("图片文件损坏");
        return bitmap;
    }

    public Bitmap loadThumb(Entry entry, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(entry.path, bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(entry.path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private void load() {
        entries.clear();
        if (!metaFile.exists()) return;
        try (FileInputStream in = new FileInputStream(metaFile)) {
            byte[] raw = new byte[(int) metaFile.length()];
            int n = in.read(raw);
            JSONArray array = new JSONArray(new String(raw, 0, Math.max(0, n), "UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Entry e = new Entry(o.getString("id"));
                e.path = o.optString("path", "");
                e.rotation = o.optInt("rotation", 0);
                e.cover = o.optBoolean("cover", false);
                e.dither = o.optBoolean("dither", false);
                e.blockSize = o.optInt("blockSize", 1);
                e.binary = o.optBoolean("binary", false);
                e.threshold = o.optInt("threshold", 128);
                e.cropped = o.optBoolean("cropped", false);
                e.cropPath = o.optString("cropPath", "");
                if (e.path.isEmpty() || !new File(e.path).exists()) continue;
                entries.add(e);
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("path", e.path);
                o.put("rotation", e.rotation);
                o.put("cover", e.cover);
                o.put("dither", e.dither);
                o.put("blockSize", e.blockSize);
                o.put("binary", e.binary);
                o.put("threshold", e.threshold);
                o.put("cropped", e.cropped);
                o.put("cropPath", e.cropPath == null ? "" : e.cropPath);
                array.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(metaFile)) {
                out.write(array.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuiet(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            new File(path).delete();
        } catch (Exception ignored) {
        }
    }
}
