package org.nfcresearch.qpolllab.ui;

import android.app.Activity;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Timestamped in-memory transcript mirrored to a private log file. */
public final class LogPanel {
    private final TextView view;
    private final File file;
    private final StringBuilder buffer = new StringBuilder();
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    public LogPanel(Activity activity, TextView view) {
        this.view = view;
        this.file = new File(activity.getFilesDir(), "latest.log");
    }

    public void append(String line) {
        String stamped = stamp.format(new Date()) + "  " + line + "\n";
        buffer.append(stamped);
        if (buffer.length() > 60000) {
            int cut = buffer.indexOf("\n", buffer.length() - 50000);
            if (cut > 0) buffer.delete(0, cut + 1);
        }
        view.setText(buffer.toString());
        try (FileWriter out = new FileWriter(file, true)) {
            out.write(stamped);
        } catch (Exception ignored) {
        }
    }

    public void clear() {
        buffer.setLength(0);
        view.setText("");
        if (file.exists() && !file.delete()) {
            try (FileWriter out = new FileWriter(file, false)) {
                out.write("");
            } catch (Exception ignored) {
            }
        }
    }

    public String text() {
        return buffer.toString();
    }
}
