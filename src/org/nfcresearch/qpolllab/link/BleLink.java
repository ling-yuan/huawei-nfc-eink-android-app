package org.nfcresearch.qpolllab.link;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.nfcresearch.qpolllab.image.BleRaster;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** GATT client for InkSight398 (ESP32-C3). Requires bonding before encrypted writes. */
@SuppressLint("MissingPermission")
public final class BleLink {
    public static final String DEVICE_NAME = "InkSight398";
    public static final UUID SVC = UUID.fromString("6f4c0001-8a2e-4c91-b7d4-1e5a9c30f001");
    public static final UUID CTRL = UUID.fromString("6f4c0002-8a2e-4c91-b7d4-1e5a9c30f001");
    public static final UUID IMG = UUID.fromString("6f4c0003-8a2e-4c91-b7d4-1e5a9c30f001");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final byte OP_SET_IDLE = 0x01;
    public static final byte OP_BEGIN = 0x02;
    public static final byte OP_SHOW = 0x03;
    public static final byte OP_GET = 0x04;
    public static final byte OP_SET_PANEL = 0x05;
    public static final byte OP_RESULT = (byte) 0x81;
    public static final byte OP_INFO = (byte) 0x82;

    public interface Listener {
        void onBackendChanged(boolean connected, String label);

        void onPanelChanged(String label);

        void onDetail(String text);

        void onProgress(int done, int total);

        void onLog(String line);

        void onSessionStart();

        void onSessionEnd();

        void onScanUpdated(List<DeviceRow> devices);
    }

    public static final class DeviceRow {
        public final String address;
        public final String name;
        public final boolean bonded;

        public DeviceRow(String address, String name, boolean bonded) {
            this.address = address;
            this.name = name == null || name.isEmpty() ? address : name;
            this.bonded = bonded;
        }
    }

    private final Activity activity;
    private final AppSettings settings;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object gate = new Object();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ctrl;
    private BluetoothGattCharacteristic img;
    private volatile boolean connected;
    private volatile boolean ready;
    private volatile boolean busy;
    private volatile boolean cancel;
    private volatile boolean scanning;
    private volatile int attPayload = 20;
    private final Map<String, DeviceRow> found = new LinkedHashMap<>();
    private CountDownLatch resultLatch;
    private final AtomicInteger lastStatus = new AtomicInteger(-1);
    private final AtomicInteger lastReq = new AtomicInteger(-1);
    private final AtomicReference<byte[]> lastInfo = new AtomicReference<>();

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            String name = device.getName();
            if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            boolean match = DEVICE_NAME.equals(name);
            if (!match && result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    if (SVC.equals(uuid.getUuid())) {
                        match = true;
                        break;
                    }
                }
            }
            if (!match) return;
            synchronized (found) {
                found.put(device.getAddress(), new DeviceRow(device.getAddress(), name, device.getBondState() == BluetoothDevice.BOND_BONDED));
            }
            publishScan();
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("扫描失败：" + errorCode);
            scanning = false;
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                post(() -> {
                    listener.onBackendChanged(true, "蓝牙 · 已连接");
                    listener.onPanelChanged("配对中");
                    listener.onLog("蓝牙已连接：" + g.getDevice().getAddress());
                });
                g.requestMtu(517);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                ready = false;
                attPayload = 20;
                ctrl = null;
                img = null;
                post(() -> {
                    listener.onBackendChanged(false, "蓝牙 · 未连接");
                    listener.onPanelChanged("未检测");
                    listener.onLog("蓝牙已断开。芯片休眠后需按键唤醒。");
                    if (busy) endBusy("连接断开");
                });
                closeGatt();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23)
                attPayload = Math.min(500, mtu - 3);
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService service = g.getService(SVC);
            if (service == null) {
                log("未找到 InkSight 服务");
                g.disconnect();
                return;
            }
            ctrl = service.getCharacteristic(CTRL);
            img = service.getCharacteristic(IMG);
            if (ctrl == null || img == null) {
                log("缺少控制或图像特征");
                g.disconnect();
                return;
            }
            g.setCharacteristicNotification(ctrl, true);
            BluetoothGattDescriptor cccd = ctrl.getDescriptor(CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(cccd);
            } else {
                markReady(g);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            markReady(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null || value.length < 1) return;
            if (value[0] == OP_RESULT && value.length >= 3) {
                lastReq.set(value[1] & 255);
                lastStatus.set(value[2] & 255);
                CountDownLatch latch = resultLatch;
                if (latch != null) latch.countDown();
            } else if (value[0] == OP_INFO && value.length >= 8) {
                lastInfo.set(value.clone());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            CountDownLatch latch = resultLatch;
            if (IMG.equals(characteristic.getUuid()) && latch != null) latch.countDown();
        }
    };

    public BleLink(Activity activity, AppSettings settings, Listener listener) {
        this.activity = activity;
        this.settings = settings;
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public void detach() {
        stopScan();
        disconnect();
        listener = noop();
    }

    public boolean ready() {
        return ready && connected;
    }

    public boolean busy() {
        return busy;
    }

    public boolean connected() {
        return connected;
    }

    public void connectSaved() {
        String address = settings.bleAddress();
        if (address.isEmpty()) {
            post(() -> listener.onDetail("尚未保存蓝牙设备，请点击连接状态选择。"));
            return;
        }
        connectAddress(address);
    }

    public void connectAddress(String address) {
        if (adapter == null || !adapter.isEnabled()) {
            post(() -> listener.onDetail("请先打开手机蓝牙。"));
            return;
        }
        stopScan();
        closeGatt();
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            settings.setBleAddress(address);
            String name = device.getName();
            if (name != null) settings.setBleName(name);
            post(() -> {
                listener.onBackendChanged(false, "蓝牙 · 连接中");
                listener.onLog("正在连接 " + address + " …");
                listener.onDetail("若芯片已休眠，请先按设备按键唤醒。");
            });
            if (Build.VERSION.SDK_INT >= 23)
                gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else
                gatt = device.connectGatt(activity, false, gattCallback);
        } catch (Exception e) {
            log("连接失败：" + e.getMessage());
        }
    }

    public void disconnect() {
        cancel = true;
        closeGatt();
    }

    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            post(() -> listener.onDetail("请先打开手机蓝牙。"));
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        synchronized (found) {
            found.clear();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (DEVICE_NAME.equals(name))
                    found.put(device.getAddress(), new DeviceRow(device.getAddress(), name, true));
            }
        }
        publishScan();
        scanning = true;
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        main.postDelayed(this::stopScan, 15000);
        log("开始扫描 InkSight398 …");
    }

    public void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
    }

    public List<DeviceRow> devices() {
        synchronized (found) {
            return new ArrayList<>(found.values());
        }
    }

    public boolean pushImage(byte[] blePacked) {
        return runSession(() -> {
            if (blePacked == null || blePacked.length != BleRaster.IMAGE_BYTES)
                throw new IllegalArgumentException("蓝牙图像必须为 " + BleRaster.IMAGE_BYTES + " 字节");
            post(() -> {
                listener.onSessionStart();
                listener.onPanelChanged("传输中");
                listener.onDetail("正在通过蓝牙推送图片…");
            });
            writeCtrl(beginCmd(blePacked.length));
            awaitResult(OP_BEGIN, 8000);
            int chunk = Math.max(20, attPayload);
            int total = (blePacked.length + chunk - 1) / chunk;
            for (int offset = 0, part = 0; offset < blePacked.length; offset += chunk, part++) {
                checkCancel();
                int n = Math.min(chunk, blePacked.length - offset);
                writeImg(blePacked, offset, n);
                if (part % 8 == 0 || part + 1 == total) {
                    final int done = part + 1;
                    post(() -> listener.onProgress(done, total));
                }
            }
            writeCtrl(new byte[]{OP_SHOW});
            awaitResult(OP_SHOW, 8000);
            post(() -> listener.onDetail("屏幕刷新中，请稍候…"));
            awaitResult(OP_SHOW, 120000);
            post(() -> {
                listener.onPanelChanged("推送完成");
                listener.onDetail("蓝牙推送完成，请目视确认画面。");
                listener.onLog("蓝牙推送完成。");
            });
        });
    }

    public boolean pushConfig(int idleMinutes, int panelRev) {
        return runSession(() -> {
            post(() -> {
                listener.onSessionStart();
                listener.onDetail("正在推送配置到芯片…");
            });
            byte[] idle = new byte[]{OP_SET_IDLE, (byte) (idleMinutes & 255), (byte) ((idleMinutes >> 8) & 255)};
            writeCtrl(idle);
            awaitResult(OP_SET_IDLE, 5000);
            writeCtrl(new byte[]{OP_SET_PANEL, (byte) (panelRev & 1)});
            awaitResult(OP_SET_PANEL, 5000);
            writeCtrl(new byte[]{OP_GET});
            post(() -> {
                listener.onPanelChanged("配置已写入");
                listener.onDetail("空闲=" + idleMinutes + " min；屏幕=" + (panelRev == 0 ? "A0" : "A1"));
                listener.onLog("配置已推送到芯片。");
            });
        });
    }

    public void cancel(String reason) {
        cancel = true;
        CountDownLatch latch = resultLatch;
        if (latch != null) latch.countDown();
        if (busy) endBusy(reason);
    }

    private boolean runSession(SessionBody body) {
        if (busy) {
            post(() -> listener.onDetail("上一轮操作尚未结束。"));
            return false;
        }
        if (!ready()) {
            post(() -> listener.onDetail("请先连接 InkSight398。芯片休眠时需按键唤醒。"));
            return false;
        }
        busy = true;
        cancel = false;
        new Thread(() -> {
            try {
                body.run();
            } catch (InterruptedException e) {
                post(() -> listener.onDetail("已停止：" + e.getMessage()));
            } catch (Exception e) {
                post(() -> {
                    listener.onPanelChanged("需要检查");
                    listener.onDetail("蓝牙失败：" + e.getMessage());
                    listener.onLog("蓝牙失败：" + e);
                });
            } finally {
                endBusy(null);
            }
        }, "ble-push").start();
        return true;
    }

    private void endBusy(String reason) {
        busy = false;
        post(() -> {
            if (reason != null) {
                listener.onDetail(reason);
                listener.onLog(reason);
            }
            listener.onSessionEnd();
            listener.onProgress(0, 1);
        });
    }

    private void markReady(BluetoothGatt g) {
        ready = true;
        settings.setBleAddress(g.getDevice().getAddress());
        String name = g.getDevice().getName();
        if (name != null) settings.setBleName(name);
        post(() -> {
            listener.onBackendChanged(true, "蓝牙 · 已就绪");
            listener.onPanelChanged("已连接");
            listener.onLog("InkSight398 已就绪。");
            listener.onDetail("蓝牙已连接 " + settings.bleName());
        });
        try {
            writeCtrl(new byte[]{OP_GET});
        } catch (Exception ignored) {
        }
    }

    private static byte[] beginCmd(int total) {
        return new byte[]{
                OP_BEGIN,
                (byte) (total), (byte) (total >> 8), (byte) (total >> 16), (byte) (total >> 24),
                (byte) (BleRaster.BLE_WIDTH), (byte) (BleRaster.BLE_WIDTH >> 8),
                (byte) (BleRaster.BLE_HEIGHT), (byte) (BleRaster.BLE_HEIGHT >> 8)
        };
    }

    private void writeCtrl(byte[] value) throws Exception {
        checkCancel();
        if (gatt == null || ctrl == null) throw new IllegalStateException("未连接");
        synchronized (gate) {
            resultLatch = new CountDownLatch(1);
            lastStatus.set(-1);
            lastReq.set(-1);
            ctrl.setValue(value);
            ctrl.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(ctrl)) throw new IllegalStateException("控制写入失败");
        }
    }

    private void writeImg(byte[] data, int offset, int length) throws Exception {
        checkCancel();
        if (gatt == null || img == null) throw new IllegalStateException("未连接");
        byte[] chunk = new byte[length];
        System.arraycopy(data, offset, chunk, 0, length);
        synchronized (gate) {
            resultLatch = new CountDownLatch(1);
            img.setValue(chunk);
            img.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (!gatt.writeCharacteristic(img)) throw new IllegalStateException("图像写入失败");
            // Write-without-response may not callback on every stack; small pacing delay.
            Thread.sleep(8);
            CountDownLatch latch = resultLatch;
            if (latch != null) latch.countDown();
        }
    }

    private void awaitResult(int op, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            checkCancel();
            CountDownLatch latch = resultLatch;
            if (latch != null) latch.await(200, TimeUnit.MILLISECONDS);
            if (lastReq.get() == (op & 255) && lastStatus.get() >= 0) {
                int st = lastStatus.getAndSet(-1);
                if (st == 0) return;
                if (st == 2) throw new IllegalStateException("芯片正忙，请稍后再试");
                if (st == 3) throw new IllegalStateException("图像未收齐");
                if (st == 4) throw new IllegalStateException("图像溢出");
                throw new IllegalStateException("芯片返回错误状态 " + st);
            }
        }
        throw new IllegalStateException("等待芯片应答超时");
    }

    private void checkCancel() throws InterruptedException {
        if (cancel) throw new InterruptedException("用户停止");
    }

    private void closeGatt() {
        ready = false;
        connected = false;
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        ctrl = null;
        img = null;
    }

    private void publishScan() {
        List<DeviceRow> rows = devices();
        post(() -> listener.onScanUpdated(rows));
    }

    private void log(String line) {
        post(() -> listener.onLog(line));
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (listener != null) r.run();
        });
    }

    private interface SessionBody {
        void run() throws Exception;
    }

    private static Listener noop() {
        return new Listener() {
            public void onBackendChanged(boolean c, String l) {
            }

            public void onPanelChanged(String l) {
            }

            public void onDetail(String t) {
            }

            public void onProgress(int d, int t) {
            }

            public void onLog(String l) {
            }

            public void onSessionStart() {
            }

            public void onSessionEnd() {
            }

            public void onScanUpdated(List<DeviceRow> d) {
            }
        };
    }
}
