package org.nfcresearch.qpolllab.link;

import android.app.Activity;
import java.util.List;
import org.nfcresearch.qpolllab.core.PanelType;
import org.nfcresearch.qpolllab.image.BleRaster;
import org.nfcresearch.qpolllab.image.PanelImage;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Routes connect / push / cancel to NFC (Shizuku) or BLE based on AppSettings. */
public final class DeviceHub implements PanelLink.Listener, BleLink.Listener {
    public interface Listener {
        void onBackendChanged(boolean connected, String label);

        void onPanelChanged(String label);

        void onDetail(String text);

        void onProgress(int done, int total);

        void onLog(String line);

        void onSessionStart();

        void onSessionEnd();

        void onScanUpdated(List<BleLink.DeviceRow> devices);
    }

    private final AppSettings settings;
    private final PanelLink nfc;
    private final BleLink ble;
    private Listener listener;

    public DeviceHub(Activity activity, AppSettings settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
        this.nfc = new PanelLink(activity, settings, this);
        this.ble = new BleLink(activity, settings, this);
    }

    public PanelLink nfc() {
        return nfc;
    }

    public BleLink ble() {
        return ble;
    }

    public void detach() {
        nfc.detach();
        ble.detach();
        listener = null;
    }

    public boolean bleMode() {
        return settings.bleMode();
    }

    public boolean ready() {
        return bleMode() ? ble.ready() : nfc.ready();
    }

    public boolean busy() {
        return nfc.busy() || ble.busy();
    }

    public void connectIfPermitted() {
        if (bleMode()) {
            if (!settings.bleAddress().isEmpty()) ble.connectSaved();
        } else {
            nfc.connectIfPermitted();
        }
    }

    public void connect() {
        if (bleMode()) {
            if (!settings.bleAddress().isEmpty()) ble.connectSaved();
            else ble.startScan();
        } else {
            nfc.connect();
        }
    }

    public void cancel(String reason) {
        if (nfc.busy()) nfc.cancel(reason);
        if (ble.busy()) ble.cancel(reason);
    }

    /** Push a prepared panel image over the active link. */
    public boolean push(PanelImage.Prepared prepared) {
        if (prepared == null) return false;
        if (bleMode()) {
            if (!PanelType.isValid(settings.panelType())) {
                emitDetail("请先在配置页选择屏幕类型 A0 或 A1。");
                return false;
            }
            byte[] wire = BleRaster.fromCodes(prepared.codes);
            return ble.pushImage(wire);
        }
        return nfc.refresh(prepared.packed, settings.powerMs(), settings.pollMs(), prepared.panelType);
    }

    public boolean pushConfig() {
        if (!bleMode()) {
            emitDetail("推送配置仅支持蓝牙模式。");
            return false;
        }
        String type = settings.panelType();
        if (!PanelType.isValid(type)) {
            emitDetail("请先选择屏幕类型 A0 或 A1。");
            return false;
        }
        return ble.pushConfig(settings.idleMinutes(), "A0".equals(type) ? 0 : 1);
    }

    private void emitDetail(String text) {
        if (listener != null) listener.onDetail(text);
        if (listener != null) listener.onLog(text);
    }

    // ---- PanelLink.Listener / BleLink.Listener ----------------------------

    @Override
    public void onBackendChanged(boolean connected, String label) {
        if (listener != null) listener.onBackendChanged(connected, label);
    }

    @Override
    public void onPanelChanged(String label) {
        if (listener != null) listener.onPanelChanged(label);
    }

    @Override
    public void onDetail(String text) {
        if (listener != null) listener.onDetail(text);
    }

    @Override
    public void onProgress(int done, int total) {
        if (listener != null) listener.onProgress(done, total);
    }

    @Override
    public void onLog(String line) {
        if (listener != null) listener.onLog(line);
    }

    @Override
    public void onSessionStart() {
        if (listener != null) listener.onSessionStart();
    }

    @Override
    public void onSessionEnd() {
        if (listener != null) listener.onSessionEnd();
    }

    @Override
    public void onScanUpdated(List<BleLink.DeviceRow> devices) {
        if (listener != null) listener.onScanUpdated(devices);
    }
}
