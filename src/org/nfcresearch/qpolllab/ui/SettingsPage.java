package org.nfcresearch.qpolllab.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.nfcresearch.qpolllab.R;
import org.nfcresearch.qpolllab.link.DeviceHub;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Settings page: link mode, panel type, idle, NFC diagnostics and logs. */
public final class SettingsPage {
    private final Activity activity;
    private final AppSettings settings;
    private final DeviceHub hub;
    private final LogPanel logs;
    private final Spinner linkMode, panelType, idle, scanSeconds, power, poll;
    private final View nfcSection;
    private final CheckBox calendarAuto;
    private boolean wiring;

    public SettingsPage(Activity activity, AppSettings settings, DeviceHub hub, LogPanel logs) {
        this.activity = activity;
        this.settings = settings;
        this.hub = hub;
        this.logs = logs;
        linkMode = activity.findViewById(R.id.sp_link_mode);
        panelType = activity.findViewById(R.id.sp_panel_type);
        idle = activity.findViewById(R.id.sp_idle);
        scanSeconds = activity.findViewById(R.id.sp_scan_seconds);
        power = activity.findViewById(R.id.sp_power);
        poll = activity.findViewById(R.id.sp_poll);
        nfcSection = activity.findViewById(R.id.nfc_section);
        calendarAuto = activity.findViewById(R.id.cb_calendar_auto);
        wire();
        refreshVisibility();
    }

    private void wire() {
        wiring = true;
        linkMode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Shizuku + NFC", "蓝牙"}));
        linkMode.setSelection(settings.linkMode());
        linkMode.setOnItemSelectedListener(pick(pos -> {
            if (wiring) return;
            settings.setLinkMode(pos);
            refreshVisibility();
            if (activity instanceof MainActivity) ((MainActivity) activity).onLinkModeChanged();
        }));

        panelType.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"请选择屏幕类型", "A0 · 华为 552×768 四色", "A1 · 华为 552×768 四色"}));
        String saved = settings.panelType();
        panelType.setSelection("A0".equals(saved) ? 1 : "A1".equals(saved) ? 2 : 0);
        panelType.setOnItemSelectedListener(pick(pos -> {
            if (wiring) return;
            settings.setPanelType(pos == 1 ? "A0" : pos == 2 ? "A1" : "");
        }));

        int[] idleValues = AppSettings.idleMinuteOptions();
        String[] idleLabels = new String[idleValues.length];
        for (int i = 0; i < idleValues.length; i++)
            idleLabels[i] = idleValues[i] == AppSettings.IDLE_NEVER ? "从不断开" : idleValues[i] + " 分钟";
        idle.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, idleLabels));
        select(idle, idleValues, settings.idleMinutes());
        idle.setOnItemSelectedListener(pick(pos -> {
            if (wiring) return;
            settings.setIdleMinutes(AppSettings.idleMinuteOptions()[pos]);
        }));

        activity.findViewById(R.id.btn_push_config).setOnClickListener(v -> {
            if (!hub.pushConfig())
                Toast.makeText(activity, "无法推送配置，请检查蓝牙连接", Toast.LENGTH_SHORT).show();
        });
        activity.findViewById(R.id.btn_app_info).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        });

        RadioButton shizuku = activity.findViewById(R.id.rb_shizuku);
        RadioButton root = activity.findViewById(R.id.rb_root);
        (settings.useRoot() ? root : shizuku).setChecked(true);
        shizuku.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) return;
            settings.setUseRoot(false);
            hub.nfc().useRoot(false);
        });
        root.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) return;
            settings.setUseRoot(true);
            hub.nfc().useRoot(true);
        });

        activity.findViewById(R.id.btn_connect).setOnClickListener(v -> hub.nfc().connect());
        activity.findViewById(R.id.btn_inspect).setOnClickListener(v -> start(hub.nfc().inspect(), "检查芯片 / NFC"));
        activity.findViewById(R.id.btn_qpoll).setOnClickListener(v -> start(hub.nfc().testQPoll(settings.scanSeconds()), "Q-Poll 能力测试"));
        activity.findViewById(R.id.btn_standard).setOnClickListener(v -> start(hub.nfc().testStandard(settings.scanSeconds()), "普通 NFC-A 对照"));

        int[] seconds = AppSettings.scanSecondOptions();
        String[] secondLabels = new String[seconds.length];
        for (int i = 0; i < seconds.length; i++) secondLabels[i] = "寻卡 " + seconds[i] + " 秒";
        scanSeconds.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, secondLabels));
        select(scanSeconds, seconds, settings.scanSeconds());
        scanSeconds.setOnItemSelectedListener(pick(value -> settings.setScanSeconds(AppSettings.scanSecondOptions()[value])));

        int[] powers = AppSettings.powerTimes();
        String[] powerLabels = new String[powers.length];
        for (int i = 0; i < powers.length; i++) powerLabels[i] = powers[i] + " ms" + (powers[i] == 2000 ? "（默认）" : "");
        power.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, powerLabels));
        select(power, powers, settings.powerMs());
        power.setOnItemSelectedListener(pick(value -> settings.setPowerMs(AppSettings.powerTimes()[value])));

        int[] polls = AppSettings.pollTimes();
        String[] pollLabels = new String[polls.length];
        for (int i = 0; i < polls.length; i++)
            pollLabels[i] = polls[i] == 0 ? "0 ms · 连续查询" : polls[i] + " ms" + (polls[i] == 500 ? "（默认）" : "");
        poll.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, pollLabels));
        select(poll, polls, settings.pollMs());
        poll.setOnItemSelectedListener(pick(value -> settings.setPollMs(AppSettings.pollTimes()[value])));

        activity.findViewById(R.id.btn_restore_defaults).setOnClickListener(v -> {
            settings.restoreTransportDefaults();
            select(scanSeconds, AppSettings.scanSecondOptions(), settings.scanSeconds());
            select(power, AppSettings.powerTimes(), settings.powerMs());
            select(poll, AppSettings.pollTimes(), settings.pollMs());
            Toast.makeText(activity, "已恢复默认传输参数", Toast.LENGTH_SHORT).show();
        });

        calendarAuto.setChecked(settings.calendarAutoPush());
        calendarAuto.setOnCheckedChangeListener((b, checked) -> {
            settings.setCalendarAutoPush(checked);
            if (activity instanceof MainActivity) ((MainActivity) activity).syncKeepAlive();
        });

        ((Button) activity.findViewById(R.id.btn_copy_log)).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("墨水屏日志", logs.text()));
            Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show();
        });
        activity.findViewById(R.id.btn_clear_log).setOnClickListener(v -> logs.clear());
        activity.findViewById(R.id.btn_license).setOnClickListener(v -> showLicense());
        wiring = false;
    }

    private void refreshVisibility() {
        boolean nfc = !settings.bleMode();
        nfcSection.setVisibility(nfc ? View.VISIBLE : View.GONE);
        activity.findViewById(R.id.btn_push_config).setEnabled(!nfc);
    }

    private static void select(Spinner spinner, int[] values, int value) {
        for (int i = 0; i < values.length; i++)
            if (values[i] == value) {
                spinner.setSelection(i);
                return;
            }
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private AdapterView.OnItemSelectedListener pick(IntConsumer apply) {
        return new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                apply.accept(position);
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
    }

    private void start(boolean ok, String label) {
        if (!ok) Toast.makeText(activity, "无法开始" + label + "，请检查连接状态", Toast.LENGTH_SHORT).show();
    }

    private void showLicense() {
        StringBuilder text = new StringBuilder();
        appendAsset(text, "licenses/kur0neko-NOTICE.txt");
        text.append('\n');
        appendAsset(text, "licenses/kur0neko-LICENSE.txt");
        text.append('\n');
        appendAsset(text, "licenses/Shizuku-LICENSE.txt");
        new AlertDialog.Builder(activity).setTitle(R.string.settings_about).setMessage(text.toString())
                .setPositiveButton(R.string.action_close, null).show();
    }

    private void appendAsset(StringBuilder text, String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(activity.getAssets().open(name), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        } catch (Exception e) {
            text.append("无法读取 ").append(name).append("：").append(e.getMessage()).append('\n');
        }
    }
}
