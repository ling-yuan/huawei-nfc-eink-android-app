package org.nfcresearch.qpolllab.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.nfcresearch.qpolllab.R;
import org.nfcresearch.qpolllab.link.BleKeepAliveService;
import org.nfcresearch.qpolllab.link.BleLink;
import org.nfcresearch.qpolllab.link.DeviceHub;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Shell: fixed status header, three pages and dual-channel device hub. */
public final class MainActivity extends Activity implements DeviceHub.Listener {
    private static final int REQUEST_BLE = 61;
    private static final int REQUEST_NOTIFY = 62;

    private AppSettings settings;
    private DeviceHub hub;
    private LogPanel logs;
    private ImagePage imagePage;
    private CalendarPage calendarPage;
    private SettingsPage settingsPage;

    private TextView chipBackend, chipPanel, detail;
    private ProgressBar progress;
    private Button stopButton;
    private final Button[] tabs = new Button[3];
    private final android.view.View[] pages = new android.view.View[3];
    private final Handler main = new Handler(Looper.getMainLooper());
    private ContentObserver calendarObserver;
    private final Runnable calendarAutoPush = this::autoPushCalendar;
    private final List<BleLink.DeviceRow> bleRows = new ArrayList<>();
    private ArrayAdapter<String> bleAdapter;
    private AlertDialog bleDialog;
    private int announcedDevices = -1;
    private boolean calendarDirty;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        settings = new AppSettings(this);
        logs = new LogPanel(this, findViewById(R.id.log_text));

        chipBackend = findViewById(R.id.chip_backend);
        chipPanel = findViewById(R.id.chip_panel);
        detail = findViewById(R.id.detail_text);
        progress = findViewById(R.id.progress);
        stopButton = findViewById(R.id.btn_stop);
        tabs[0] = findViewById(R.id.tab_image);
        tabs[1] = findViewById(R.id.tab_calendar);
        tabs[2] = findViewById(R.id.tab_settings);
        pages[0] = findViewById(R.id.page_image);
        pages[1] = findViewById(R.id.page_calendar);
        pages[2] = findViewById(R.id.page_settings);

        hub = new DeviceHub(this, settings, this);
        imagePage = new ImagePage(this, settings, hub);
        calendarPage = new CalendarPage(this, settings, hub);
        settingsPage = new SettingsPage(this, settings, hub, logs);

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(v -> selectPage(index));
        }
        chipBackend.setOnClickListener(v -> showConnectionUi());
        stopButton.setOnClickListener(v -> hub.cancel("用户停止"));
        findViewById(R.id.btn_logs).setOnClickListener(v -> showLogs());

        setChip(chipPanel, getString(R.string.chip_panel_idle), false);
        selectPage(settings.page());
        logs.append("v3.1 就绪；支持 Shizuku+NFC 与蓝牙双通道。");
        imagePage.initialize();
        calendarPage.initialize();
        ensureBlePermissions();
        syncKeepAlive();
        registerCalendarObserver();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hub.connectIfPermitted();
    }

    @Override
    protected void onStop() {
        if (hub.busy() && !settings.bleMode()) hub.cancel("页面已离开，自动停止");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(calendarAutoPush);
        if (calendarObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(calendarObserver);
            } catch (Exception ignored) {
            }
        }
        if (hub.busy()) hub.cancel("页面关闭，自动停止");
        hub.detach();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ImagePage.REQUEST_PICK && result == RESULT_OK) imagePage.handlePick(data);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == CalendarPage.REQUEST_CALENDAR) calendarPage.onPermissionResult();
        if (request == REQUEST_BLE && bleConnectGranted()) {
            hub.connectIfPermitted();
            syncKeepAlive();
        }
    }

    void onLinkModeChanged() {
        imagePage.onLinkModeChanged();
        syncKeepAlive();
        hub.connectIfPermitted();
        detail.setText(settings.bleMode()
                ? "已切换到蓝牙。点击顶部连接状态扫描或重连。"
                : "已切换到 Shizuku + NFC。");
    }

    void syncKeepAlive() {
        boolean need = settings.bleMode() && (settings.carouselEnabled() || settings.calendarAutoPush());
        if (need && !bleConnectGranted()) {
            need = false;
            logs.append("未授予蓝牙权限，未启动保持连接服务。");
        }
        Intent intent = new Intent(this, BleKeepAliveService.class);
        if (need) {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } else {
            stopService(intent);
        }
    }

    private void registerCalendarObserver() {
        calendarObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (settings.calendarAutoPush() && settings.bleMode()) {
                    calendarDirty = true;
                    main.removeCallbacks(calendarAutoPush);
                    main.postDelayed(calendarAutoPush, 2500);
                } else {
                    calendarPage.reloadQuiet();
                }
            }
        };
        try {
            getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver);
            getContentResolver().registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, calendarObserver);
        } catch (Exception e) {
            logs.append("日历监听注册失败：" + e.getMessage());
        }
    }

    private void autoPushCalendar() {
        if (!calendarDirty) return;
        if (!settings.calendarAutoPush() || !settings.bleMode()) {
            calendarDirty = false;
            calendarPage.reloadQuiet();
            return;
        }
        if (!hub.ready()) {
            calendarDirty = false;
            calendarPage.reloadQuiet();
            return;
        }
        if (hub.busy()) {
            main.postDelayed(calendarAutoPush, 2500);
            return;
        }
        calendarDirty = false;
        calendarPage.reloadCurrentMonthThenPush();
    }

    private void showConnectionUi() {
        if (settings.bleMode()) showBlePicker();
        else hub.nfc().connect();
    }

    private void showBlePicker() {
        ensureBlePermissions();
        hub.ble().startScan();
        if (bleDialog != null && bleDialog.isShowing()) bleDialog.dismiss();
        bleRows.clear();
        bleRows.addAll(hub.ble().devices());
        bleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceLabels());
        bleDialog = new AlertDialog.Builder(this)
                .setTitle("蓝牙设备")
                .setAdapter(bleAdapter, (d, which) -> {
                    if (which < 0 || which >= bleRows.size()) return;
                    hub.ble().connectAddress(bleRows.get(which).address);
                })
                .setNeutralButton("刷新扫描", (d, w) -> showBlePicker())
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void refreshBleDialog(List<BleLink.DeviceRow> devices) {
        if (bleDialog == null || !bleDialog.isShowing() || bleAdapter == null) return;
        bleRows.clear();
        bleRows.addAll(devices);
        bleAdapter.clear();
        bleAdapter.addAll(deviceLabels());
        bleAdapter.notifyDataSetChanged();
    }

    private List<String> deviceLabels() {
        List<String> labels = new ArrayList<>();
        if (bleRows.isEmpty()) {
            labels.add("未发现设备（请确认芯片已按键唤醒）");
            return labels;
        }
        for (BleLink.DeviceRow row : bleRows)
            labels.add(row.name + "\n" + row.address + (row.bonded ? " · 已配对" : ""));
        return labels;
    }

    private void ensureBlePermissions() {
        if (Build.VERSION.SDK_INT < 31) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQUEST_BLE);
    }

    private boolean bleConnectGranted() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void selectPage(int index) {
        int activePage = Math.max(0, Math.min(2, index));
        for (int i = 0; i < pages.length; i++)
            pages[i].setVisibility(i == activePage ? android.view.View.VISIBLE : android.view.View.GONE);
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == activePage;
            tabs[i].setTextColor(getColor(active ? R.color.white : R.color.text_secondary));
            tabs[i].setBackgroundTintList(ColorStateList.valueOf(getColor(active ? R.color.primary : R.color.surface)));
        }
        settings.setPage(activePage);
    }

    @Override
    public void onBackendChanged(boolean connected, String label) {
        setChip(chipBackend, label, connected);
        imagePage.onConnectedChanged();
        if (connected) syncKeepAlive();
    }

    @Override
    public void onPanelChanged(String label) {
        boolean ok = label.contains("已连接") || label.contains("已发现") || label.contains("通过")
                || label.contains("完成") || label.contains("传输中") || label.contains("就绪") || label.contains("配置");
        boolean bad = label.contains("需要检查") || label.contains("失败");
        setChip(chipPanel, "墨水屏 · " + label, ok);
        if (bad) chipPanel.setTextColor(getColor(R.color.warn));
    }

    @Override
    public void onDetail(String text) {
        detail.setText(text);
    }

    @Override
    public void onProgress(int done, int total) {
        progress.setMax(Math.max(1, total));
        progress.setProgress(done);
    }

    @Override
    public void onLog(String line) {
        logs.append(line);
    }

    @Override
    public void onSessionStart() {
        stopButton.setVisibility(android.view.View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onSessionEnd() {
        stopButton.setVisibility(android.view.View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setProgress(0);
        imagePage.scheduleCarousel();
        if (calendarDirty) main.postDelayed(calendarAutoPush, 800);
    }

    @Override
    public void onScanUpdated(List<BleLink.DeviceRow> devices) {
        refreshBleDialog(devices);
        if (devices.size() == announcedDevices) return;
        announcedDevices = devices.size();
        if (announcedDevices > 0) logs.append("发现蓝牙设备 " + announcedDevices + " 个");
    }

    private void setChip(TextView chip, String text, boolean active) {
        chip.setText(text);
        chip.setTextColor(getColor(active ? R.color.primary : R.color.text_secondary));
    }

    private void showLogs() {
        TextView view = new TextView(this);
        view.setText(logs.text());
        view.setTextIsSelectable(true);
        view.setTextSize(11);
        view.setPadding(32, 24, 32, 24);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_logs)
                .setView(view)
                .setNeutralButton(R.string.settings_copy, (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("墨水屏日志", logs.text()));
                    Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.action_close, null)
                .show();
    }
}
