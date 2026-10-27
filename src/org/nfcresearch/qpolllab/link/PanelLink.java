package org.nfcresearch.qpolllab.link;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.nfcresearch.qpolllab.core.PanelProtocol;
import org.nfcresearch.qpolllab.core.SessionFailure;
import org.nfcresearch.qpolllab.settings.AppSettings;
import rikka.shizuku.Shizuku;

/**
 * Owns the privileged NFC session: backend binding, worker process lifetime, log parsing
 * and cancellation. UI pages only ask it to push an already encoded image.
 */
public final class PanelLink {
    public static final int MODE_INSPECT_SECONDS = 5;
    public static final int MODE_PANEL_SECONDS = 10;
    private static final int REQUEST_CODE = 28;

    public interface Listener {
        void onBackendChanged(boolean connected, String label);

        void onPanelChanged(String label);

        void onDetail(String text);

        void onProgress(int done, int total);

        void onLog(String line);

        void onSessionStart();

        void onSessionEnd();
    }

    private final Activity activity;
    private final AppSettings settings;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionFailure failure = new SessionFailure();

    private volatile boolean busy;
    private volatile File stopFile;
    private volatile IBinder bridgeBinder;
    private volatile boolean bound;
    private volatile int sessionSeconds;
    private boolean attached = true;

    private final Shizuku.UserServiceArgs bridgeArgs = new Shizuku.UserServiceArgs(
            new ComponentName("org.nfcresearch.qpolllab", "org.nfcresearch.qpolllab.ShellBridge"))
            .tag("nfc-shell").version(19).daemon(false).processNameSuffix("nfc_shell");

    private final ServiceConnection bridgeConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bridgeBinder = binder;
            bound = true;
            post(() -> {
                listener.onBackendChanged(true, "Shizuku · 已连接");
                listener.onLog("Shizuku 后端已连接，可开始检查 NFC。");
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridgeBinder = null;
            bound = false;
            post(() -> {
                listener.onBackendChanged(false, "Shizuku · 已断开");
                listener.onLog("Shizuku 后端已断开。");
            });
            if (busy) cancel("授权服务断开");
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> {
        if (code != REQUEST_CODE) return;
        if (result == PackageManager.PERMISSION_GRANTED) connect();
        else post(() -> listener.onDetail("Shizuku 授权未允许，可在 Shizuku 中重新授权。"));
    };

    public PanelLink(Activity activity, AppSettings settings, Listener listener) {
        this.activity = activity;
        this.settings = settings;
        this.listener = listener;
        try {
            Shizuku.addRequestPermissionResultListener(permissionResult);
        } catch (Throwable ignored) {
            // Shizuku is optional; a missing provider must not crash the app.
        }
    }

    // ---- backend ----------------------------------------------------------

    public void connect() {
        if (settings.useRoot()) {
            post(() -> {
                listener.onBackendChanged(true, "Root · 已选择");
                listener.onDetail("Root 模式：执行时会请求 su 授权。");
            });
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                post(() -> {
                    listener.onBackendChanged(false, "Shizuku · 未运行");
                    listener.onDetail("请先在 Shizuku 中启动服务，再点击“Shizuku 授权 / 连接”。");
                });
                return;
            }
            if (Shizuku.isPreV11()) {
                post(() -> listener.onDetail("Shizuku 版本过低，请升级。"));
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE);
                post(() -> {
                    listener.onBackendChanged(false, "Shizuku · 等待授权");
                    listener.onDetail("请在 Shizuku 弹窗中允许本应用。");
                });
                return;
            }
            bind();
        } catch (Throwable t) {
            post(() -> {
                listener.onBackendChanged(false, "Shizuku · 连接失败");
                listener.onDetail("Shizuku 连接失败：" + t);
            });
        }
    }

    private void bind() {
        try {
            Shizuku.bindUserService(bridgeArgs, bridgeConnection);
        } catch (Throwable t) {
            if (!bound) post(() -> listener.onDetail("Shizuku 绑定失败：" + t));
        }
    }

    /** Auto-connect only when the user already granted Shizuku; never pop a dialog on launch. */
    public void connectIfPermitted() {
        if (settings.useRoot()) {
            post(() -> listener.onBackendChanged(true, "Root · 已选择"));
            return;
        }
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                post(() -> listener.onBackendChanged(false, pingOk() ? "Shizuku · 待授权" : "Shizuku · 未运行"));
                return;
            }
            bind();
        } catch (Throwable t) {
            post(() -> listener.onBackendChanged(false, "Shizuku · 未连接"));
        }
    }

    private static boolean pingOk() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean ready() {
        if (settings.useRoot()) return true;
        IBinder binder = bridgeBinder;
        return binder != null && binder.isBinderAlive();
    }

    public boolean busy() {
        return busy;
    }

    public void useRoot(boolean root) {
        if (root) {
            post(() -> listener.onBackendChanged(true, "Root · 已选择"));
        } else {
            bridgeBinder = null;
            bound = false;
            post(() -> listener.onBackendChanged(false, "Shizuku · 未连接"));
        }
    }

    public void detach() {
        attached = false;
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResult);
        } catch (Throwable ignored) {
        }
        if (bound) {
            try {
                Shizuku.unbindUserService(bridgeArgs, bridgeConnection, false);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
    }

    // ---- public sessions --------------------------------------------------

    public boolean inspect() {
        return begin("inspect", MODE_INSPECT_SECONDS, "{}", null, false);
    }

    public boolean testQPoll(int seconds) {
        return begin("qtag", seconds, "{}", null, false);
    }

    public boolean testStandard(int seconds) {
        return begin("standard", seconds, "{}", null, false);
    }

    public boolean identify() {
        return begin("identity", MODE_PANEL_SECONDS, "{}", null, true);
    }

    public boolean refresh(byte[] packed, int powerMs, int pollMs) {
        return refresh(packed, powerMs, pollMs, settings.panelType());
    }

    public boolean refresh(byte[] packed, int powerMs, int pollMs, String panelType) {
        try {
            if (!org.nfcresearch.qpolllab.core.PanelType.isValid(panelType)) {
                post(() -> listener.onDetail("请先在配置页选择屏幕类型 A0 或 A1。"));
                return false;
            }
            JSONObject job = new JSONObject();
            job.put("panelType", panelType);
            job.put("sha256", sha256(packed));
            job.put("powerMs", powerMs);
            job.put("pollMs", pollMs);
            return begin("refresh", MODE_PANEL_SECONDS, job.toString(), packed, true);
        } catch (Exception e) {
            post(() -> listener.onDetail("准备发送失败：" + e));
            return false;
        }
    }

    private boolean begin(String mode, int seconds, String jobJson, byte[] image, boolean panelOperation) {
        if (busy) {
            post(() -> listener.onDetail("上一轮操作尚未结束。"));
            return false;
        }
        if (!ready()) {
            post(() -> listener.onDetail("请先在“配置”页完成授权 / 连接。"));
            return false;
        }
        final String id = Long.toString(SystemClock.elapsedRealtime());
        final File dir = activity.getFilesDir();
        final File stop = new File(dir, "stop-" + id);
        final File lock = new File(dir, "session.lock");
        final File jobFile = new File(dir, "job-" + id + ".json");
        final File imageFile = new File(dir, "image-" + id + ".bin");
        busy = true;
        stopFile = stop;
        sessionSeconds = seconds;
        failure.clear();
        try {
            new FileOutputStream(lock, true).close();
        } catch (Exception ignored) {
        }
        post(() -> {
            listener.onPanelChanged("未检测");
            listener.onSessionStart();
        });
        new Thread(() -> {
            boolean ok = false;
            try {
                if (settings.useRoot()) {
                    runRoot(mode, seconds, jobJson, image, stop, lock, jobFile, imageFile);
                } else {
                    runBridge(mode, seconds, jobJson, image, stop);
                }
                ok = true;
            } catch (Exception e) {
                post(() -> {
                    listener.onLog("ERROR · " + e.getMessage());
                    listener.onDetail("本轮未完成：" + e.getMessage());
                });
            } finally {
                stop.delete();
                jobFile.delete();
                imageFile.delete();
                busy = false;
                stopFile = null;
                post(listener::onSessionEnd);
            }
        }, "panel-session").start();
        return true;
    }

    public void cancel(String reason) {
        if (!busy) return;
        try {
            File file = stopFile;
            if (file != null) file.createNewFile();
        } catch (Exception e) {
            post(() -> listener.onLog("停止标记失败 · " + e));
        }
        post(() -> listener.onDetail(reason + "；等当前通信返回后恢复 NFC，不重发写入。"));
        if (!settings.useRoot()) {
            try {
                cancelBridge();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- backends ---------------------------------------------------------

    private void runRoot(String mode, int seconds, String jobJson, byte[] image, File stop, File lock,
                         File jobFile, File imageFile) throws Exception {
        boolean panel = mode.equals("identity") || mode.equals("refresh");
        if (panel) {
            JSONObject job = new JSONObject(jobJson);
            if (image != null) {
                try (FileOutputStream out = new FileOutputStream(imageFile)) {
                    out.write(image);
                }
                job.put("imageFile", imageFile.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(jobFile)) {
                out.write(job.toString().getBytes("UTF-8"));
            }
        }
        String apk = activity.getApplicationInfo().sourceDir;
        String command = "CLASSPATH=" + quote(apk) + " /system/bin/app_process /system/bin org.nfcresearch.qpolllab.QPollWorker "
                + mode + " " + seconds + " " + quote(stop.getAbsolutePath()) + " " + quote(lock.getAbsolutePath())
                + (panel ? " " + quote(jobFile.getAbsolutePath()) : "");
        Process child = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        Thread watchdog = new Thread(() -> {
            try {
                if (!child.waitFor(mode.equals("refresh") ? 320 : 140, TimeUnit.SECONDS)) {
                    stop.createNewFile();
                    if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroy();
                }
            } catch (Exception ignored) {
            }
        }, "root-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = in.readLine()) != null) handle(line);
        }
        int code = child.waitFor();
        if (code != 0) handle("QP|PROCESS_EXIT|" + code);
    }

    private void runBridge(String mode, int seconds, String jobJson, byte[] image, File stop) throws Exception {
        IBinder binder = bridgeBinder;
        if (binder == null || !binder.isBinderAlive()) throw new IOException("Shizuku 后端未连接");
        boolean cancelSent = false;
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        final android.os.Binder owner = new android.os.Binder();
        try {
            request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");
            request.writeString(mode);
            request.writeInt(seconds);
            request.writeString(jobJson);
            request.writeByteArray(image == null ? new byte[0] : image);
            request.writeStrongBinder(owner);
            if (!binder.transact(1, request, reply, 0)) throw new IOException("后端不兼容");
            reply.readException();
        } finally {
            request.recycle();
            reply.recycle();
        }
        boolean running = true;
        long deadline = SystemClock.elapsedRealtime() + 335000;
        while (running) {
            if (stop.exists() && !cancelSent) {
                cancelBridge();
                cancelSent = true;
            }
            request = Parcel.obtain();
            reply = Parcel.obtain();
            try {
                request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");
                binder.transact(2, request, reply, 0);
                reply.readException();
                String logs = reply.readString();
                running = reply.readInt() != 0;
                if (logs != null)
                    for (String line : logs.split("\n")) {
                        if (!line.isEmpty()) handle(line);
                    }
            } finally {
                request.recycle();
                reply.recycle();
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                cancelBridge();
                throw new IOException("后端超时，已请求停止。");
            }
            if (running) SystemClock.sleep(100);
        }
    }

    private void cancelBridge() throws Exception {
        IBinder binder = bridgeBinder;
        if (binder == null) return;
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");
            binder.transact(3, request, reply, 0);
            reply.readException();
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    // ---- log handling -----------------------------------------------------

    private void handle(String line) {
        if (!line.startsWith("QP|")) {
            if (!line.trim().isEmpty()) post(() -> listener.onLog(line));
            return;
        }
        String[] p = line.split("\\|", 3);
        if (p.length != 3) return;
        String kind = p[1], msg = p[2];
        post(() -> listener.onLog(kind + " · " + msg));
        if (failure.accept(kind, msg)) {
            post(() -> {
                listener.onPanelChanged("需要检查");
                listener.onDetail(failure.message());
            });
            return;
        }
        if (failure.present()) return;
        switch (kind) {
            case "CHIP":
                post(() -> listener.onDetail("芯片：" + msg));
                break;
            case "CAPABILITY":
            case "QTAG_REJECTED":
                post(() -> listener.onDetail("Q-Poll：" + msg));
                break;
            case "START":
                post(() -> {
                    listener.onPanelChanged(msg.startsWith("standard") ? "普通 NFC-A 寻卡中" : "Q-Poll 寻卡中");
                    listener.onDetail("保持贴合，最长 " + sessionSeconds + " 秒。");
                });
                break;
            case "TAG":
                post(() -> {
                    listener.onPanelChanged("已发现 " + extractUid(msg));
                    listener.onDetail(msg);
                });
                break;
            case "CONNECT_ATTEMPT":
                post(() -> listener.onDetail("连接尝试 " + msg));
                break;
            case "CONNECTED":
                post(() -> {
                    listener.onPanelChanged("ISO-DEP 已连接");
                    listener.onDetail(msg);
                });
                break;
            case "CONNECT_FAILED":
                post(() -> listener.onDetail(msg));
                break;
            case "RECONNECT":
                post(() -> {
                    listener.onPanelChanged("重新寻卡");
                    listener.onDetail(msg);
                });
                break;
            case "STAGE": {
                int bar = msg.indexOf('|');
                String text = bar >= 0 ? msg.substring(bar + 1) : msg;
                post(() -> {
                    listener.onPanelChanged(text);
                    listener.onDetail("保持贴合，不要切换应用。");
                });
                break;
            }
            case "PROGRESS": {
                String[] f = msg.split("\\|", 3);
                if (f.length >= 2) {
                    try {
                        int done = Integer.parseInt(f[0]), total = Integer.parseInt(f[1]);
                        post(() -> {
                            listener.onProgress(done, total);
                            listener.onPanelChanged("传输中 " + (total == 0 ? 0 : done * 100 / total) + "%");
                        });
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;
            }
            case "BUSY":
                post(() -> {
                    listener.onDetail(msg);
                    listener.onPanelChanged(msg.contains("忙") ? "刷新中…" : "刷新收尾");
                });
                break;
            case "IDENTITY":
                post(() -> {
                    listener.onPanelChanged("规格校验通过");
                    listener.onDetail(msg);
                });
                break;
            case "REFRESH_DONE":
                post(() -> {
                    listener.onPanelChanged("刷新完成");
                    listener.onDetail(msg);
                });
                break;
            case "REFRESH_UNCONFIRMED":
                post(() -> {
                    listener.onPanelChanged("刷新待确认");
                    listener.onDetail(msg);
                });
                break;
            case "RESULT":
                post(() -> {
                    if (msg.startsWith("D1")) listener.onPanelChanged("规格校验通过");
                    else if (msg.startsWith("已收到")) listener.onPanelChanged("已发现标签");
                    else if (msg.startsWith("用户")) listener.onPanelChanged("已停止");
                    else listener.onPanelChanged("未发现标签");
                    listener.onDetail(msg);
                });
                break;
            case "CANCELLED":
                post(() -> {
                    listener.onPanelChanged("已停止");
                    listener.onDetail(msg);
                });
                break;
            case "READY":
                post(() -> {
                    listener.onPanelChanged("检查通过");
                    listener.onDetail(msg);
                });
                break;
            case "RESTORED":
                post(() -> listener.onDetail(msg));
                break;
            default:
                break;
        }
    }

    private static String extractUid(String message) {
        int at = message.indexOf("UID=");
        if (at < 0) return "标签";
        int end = message.indexOf('；', at);
        return message.substring(at + 4, end < 0 ? message.length() : end);
    }

    private void post(Runnable action) {
        if (!attached) return;
        main.post(action);
    }

    private static String sha256(byte[] data) throws Exception {
        return PanelProtocol.toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
