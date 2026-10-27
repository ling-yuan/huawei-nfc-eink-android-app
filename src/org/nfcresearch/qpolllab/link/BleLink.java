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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    /** 128-bit service UUID on air, little-endian. */
    private static final byte[] SVC_LE = new byte[] {
            0x01, (byte) 0xf0, 0x30, (byte) 0x9c, 0x5a, 0x1e, (byte) 0xd4, (byte) 0xb7,
            (byte) 0x91, 0x4c, 0x2e, (byte) 0x8a, 0x01, 0x00, 0x4c, 0x6f
    };

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
    private volatile int imgChunk = 20;
    private boolean bondReceiverRegistered;
    private CountDownLatch gattLatch;
    private final AtomicInteger gattWriteStatus = new AtomicInteger(-1);
    private final Map<String, DeviceRow> found = new LinkedHashMap<>();
    private CountDownLatch resultLatch;
    private final AtomicInteger lastStatus = new AtomicInteger(-1);
    private final AtomicInteger lastReq = new AtomicInteger(-1);
    private final AtomicReference<byte[]> lastInfo = new AtomicReference<>();

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || !isInkSight(device, result.getScanRecord())) return;
            String name = visibleName(device, result.getScanRecord());
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
                beginAfterBond(g);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                ready = false;
                attPayload = 20;
                imgChunk = 20;
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
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                attPayload = Math.min(500, mtu - 3);
                imgChunk = attPayload;
            }
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
            gattWriteStatus.set(status);
            CountDownLatch latch = gattLatch;
            if (latch != null) latch.countDown();
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
        unregisterBondReceiver();
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
            ensureBondReceiver();
            post(() -> {
                listener.onBackendChanged(false, "蓝牙 · 连接中");
                listener.onLog("正在连接 " + address + " …");
                listener.onDetail("请在本应用中完成配对。系统设置里的配对不能保持连接。");
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
        stopScan();
        synchronized (found) {
            found.clear();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (!isInkSight(device, null)) continue;
                found.put(device.getAddress(), new DeviceRow(device.getAddress(), visibleName(device, null), true));
            }
        }
        publishScan();
        scanning = true;
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setDeviceName(DEVICE_NAME).build());
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build());
        try {
            scanner.startScan(filters, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        } catch (Exception e) {
            scanning = false;
            log("扫描失败：" + e.getMessage());
            return;
        }
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
            int offset = 0;
            int part = 0;
            while (offset < blePacked.length) {
                checkCancel();
                int n = Math.min(Math.max(20, imgChunk), blePacked.length - offset);
                writeImg(blePacked, offset, n);
                offset += n;
                part++;
                int total = (blePacked.length + Math.max(20, imgChunk) - 1) / Math.max(20, imgChunk);
                if (part % 8 == 0 || offset >= blePacked.length) {
                    final int done = Math.min(part, total);
                    final int all = total;
                    post(() -> listener.onProgress(done, all));
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
        CountDownLatch write = gattLatch;
        if (write != null) write.countDown();
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
        new Thread(() -> {
            try {
                writeCtrl(new byte[]{OP_GET});
            } catch (Exception ignored) {
            }
        }, "ble-get").start();
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
            int code = submitWrite(ctrl, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (code != 0) throw new IllegalStateException("控制写入失败，状态 " + code);
            awaitGatt(8000);
        }
    }

    private void writeImg(byte[] data, int offset, int length) throws Exception {
        checkCancel();
        if (gatt == null || img == null) throw new IllegalStateException("未连接");
        byte[] chunk = new byte[length];
        System.arraycopy(data, offset, chunk, 0, length);
        synchronized (gate) {
            if (sendImage(chunk)) return;
            if (length <= 20) throw new IllegalStateException("图像写入失败，长度 " + length);
            imgChunk = 20;
            log("大包图像写入被拒绝，改为 20 字节。");
            for (int i = 0; i < length; i += 20) {
                checkCancel();
                int n = Math.min(20, length - i);
                byte[] small = new byte[n];
                System.arraycopy(chunk, i, small, 0, n);
                if (!sendImage(small)) throw new IllegalStateException("图像写入失败，长度 " + n);
            }
        }
    }

    /** @return false when the stack did not accept the buffer. The bytes were not sent. */
    private boolean sendImage(byte[] chunk) throws Exception {
        int code = submitWrite(img, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (code != 0) return false;
        awaitGatt(8000);
        return true;
    }

    private int submitWrite(BluetoothGattCharacteristic characteristic, byte[] value, int writeType) {
        gattLatch = new CountDownLatch(1);
        gattWriteStatus.set(-1);
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeCharacteristic(characteristic, value, writeType);
        characteristic.setValue(value);
        characteristic.setWriteType(writeType);
        return gatt.writeCharacteristic(characteristic) ? 0 : 201;
    }

    private void awaitGatt(long timeoutMs) throws Exception {
        CountDownLatch latch = gattLatch;
        if (latch == null || !latch.await(timeoutMs, TimeUnit.MILLISECONDS))
            throw new IllegalStateException("写入超时");
        checkCancel();
        int status = gattWriteStatus.get();
        if (status != BluetoothGatt.GATT_SUCCESS)
            throw new IllegalStateException("写入被拒绝，状态 " + status);
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

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            BluetoothGatt current = gatt;
            if (device == null || current == null) return;
            if (!device.getAddress().equals(current.getDevice().getAddress())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
            if (state == BluetoothDevice.BOND_BONDED) {
                log("配对完成");
                current.requestMtu(517);
            } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                log("配对被拒绝。请在系统蓝牙中忽略 InkSight398，再从本应用连接。");
                current.disconnect();
            }
        }
    };

    private void beginAfterBond(BluetoothGatt g) {
        int bond = g.getDevice().getBondState();
        if (bond == BluetoothDevice.BOND_BONDED) {
            g.requestMtu(517);
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDING) {
            log("配对进行中");
            return;
        }
        boolean started = false;
        try {
            started = g.getDevice().createBond();
        } catch (Exception e) {
            log("配对失败：" + e.getMessage());
        }
        if (!started) {
            log("未能发起配对，继续发现服务");
            g.requestMtu(517);
        }
    }

    private void ensureBondReceiver() {
        if (bondReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            activity.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            activity.registerReceiver(bondReceiver, filter);
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!bondReceiverRegistered) return;
        bondReceiverRegistered = false;
        try {
            activity.unregisterReceiver(bondReceiver);
        } catch (Exception ignored) {
        }
    }

    private static String visibleName(BluetoothDevice device, ScanRecord record) {
        String name = record == null ? null : record.getDeviceName();
        if (name == null || name.isEmpty()) name = device.getName();
        if ((name == null || name.isEmpty()) && Build.VERSION.SDK_INT >= 30) name = device.getAlias();
        return name;
    }

    private static boolean isInkSight(BluetoothDevice device, ScanRecord record) {
        if (DEVICE_NAME.equals(visibleName(device, record))) return true;
        if (record == null) return false;
        if (record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                if (SVC.equals(uuid.getUuid())) return true;
            }
        }
        byte[] raw = record.getBytes();
        return raw != null && (indexOf(raw, DEVICE_NAME.getBytes()) >= 0 || indexOf(raw, SVC_LE) >= 0);
    }

    private static int indexOf(byte[] data, byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) return -1;
        for (int i = 0; i <= data.length - needle.length; i++) {
            boolean same = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    same = false;
                    break;
                }
            }
            if (same) return i;
        }
        return -1;
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
