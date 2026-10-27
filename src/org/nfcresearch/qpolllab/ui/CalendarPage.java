package org.nfcresearch.qpolllab.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.nfcresearch.qpolllab.R;
import org.nfcresearch.qpolllab.calendar.CalendarEvent;
import org.nfcresearch.qpolllab.calendar.CalendarMonth;
import org.nfcresearch.qpolllab.calendar.CalendarRenderer;
import org.nfcresearch.qpolllab.calendar.CalendarRepository;
import org.nfcresearch.qpolllab.calendar.CalendarStyle;
import org.nfcresearch.qpolllab.image.PanelImage;
import org.nfcresearch.qpolllab.link.DeviceHub;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Calendar page: import the month, render a four-colour page and push it. */
public final class CalendarPage {
    public static final int REQUEST_CALENDAR = 42;

    private static final String[] FONT_LABELS = {"很小", "小", "标准", "大", "很大"};
    private static final String[] ORIENT_LABELS = {"正向 0°", "顺时针 90°", "倒置 180°", "逆时针 90°"};

    private final Activity activity;
    private final AppSettings settings;
    private final DeviceHub hub;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final PixelPreview preview;
    private final TextView info;
    private final Spinner orientation, marginTop, marginBottom, marginLeft, marginRight;
    private final Spinner weekStart, todayStyle, weekendStyle;
    private final Spinner fontTitle, fontWeek, fontDate, fontAgenda;
    private final Spinner markerStyle, maxAgenda;
    private final CheckBox showTime, showMarks, showAdjust, showBorder;
    private final Button push;

    private List<CalendarEvent> events = new ArrayList<>();
    private PanelImage.Prepared prepared;
    private int renderRevision, loadRevision;
    private boolean loading;
    private boolean autoPushPending;
    private String note = "";

    public CalendarPage(Activity activity, AppSettings settings, DeviceHub hub) {
        this.activity = activity;
        this.settings = settings;
        this.hub = hub;
        preview = activity.findViewById(R.id.calendar_preview);
        info = activity.findViewById(R.id.calendar_info);
        orientation = activity.findViewById(R.id.sp_orientation);
        marginTop = activity.findViewById(R.id.sp_margin_top);
        marginBottom = activity.findViewById(R.id.sp_margin_bottom);
        marginLeft = activity.findViewById(R.id.sp_margin_left);
        marginRight = activity.findViewById(R.id.sp_margin_right);
        weekStart = activity.findViewById(R.id.sp_week_start);
        todayStyle = activity.findViewById(R.id.sp_today_style);
        weekendStyle = activity.findViewById(R.id.sp_weekend_style);
        fontTitle = activity.findViewById(R.id.sp_font_title);
        fontWeek = activity.findViewById(R.id.sp_font_week);
        fontDate = activity.findViewById(R.id.sp_font_date);
        fontAgenda = activity.findViewById(R.id.sp_font_agenda);
        markerStyle = activity.findViewById(R.id.sp_marker_style);
        maxAgenda = activity.findViewById(R.id.sp_max_agenda);
        showTime = activity.findViewById(R.id.cb_show_time);
        showMarks = activity.findViewById(R.id.cb_show_marks);
        showAdjust = activity.findViewById(R.id.cb_show_adjust);
        showBorder = activity.findViewById(R.id.cb_show_border);
        push = activity.findViewById(R.id.btn_push_calendar);

        bindOptions();
        wire();
    }

    private void bindOptions() {
        orientation.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, ORIENT_LABELS));
        orientation.setSelection(settings.orientation());

        int[] margins = AppSettings.marginOptions();
        String[] marginLabels = new String[margins.length];
        for (int i = 0; i < margins.length; i++) marginLabels[i] = margins[i] + " px";
        bind(marginTop, marginLabels, margins, settings.marginTop());
        bind(marginBottom, marginLabels, margins, settings.marginBottom());
        bind(marginLeft, marginLabels, margins, settings.marginLeft());
        bind(marginRight, marginLabels, margins, settings.marginRight());

        weekStart.setAdapter(strings("周一", "周日"));
        weekStart.setSelection(settings.weekStart() == Calendar.MONDAY ? 0 : 1);
        todayStyle.setAdapter(strings(CalendarStyle.TODAY_LABELS));
        todayStyle.setSelection(clamp(settings.todayStyle(), CalendarStyle.TODAY_LABELS.length - 1));
        weekendStyle.setAdapter(strings("红色", "黄色标记", "不标注"));
        weekendStyle.setSelection(clamp(settings.weekendStyle(), 2));

        fontTitle.setAdapter(strings(FONT_LABELS));
        fontWeek.setAdapter(strings(FONT_LABELS));
        fontDate.setAdapter(strings(FONT_LABELS));
        fontAgenda.setAdapter(strings(FONT_LABELS));
        fontTitle.setSelection(settings.fontTitle());
        fontWeek.setSelection(settings.fontWeek());
        fontDate.setSelection(settings.fontDate());
        fontAgenda.setSelection(settings.fontAgenda());

        markerStyle.setAdapter(strings(CalendarStyle.MARKER_LABELS));
        markerStyle.setSelection(settings.markerStyle());

        int[] limits = AppSettings.agendaLimits();
        String[] limitLabels = new String[limits.length];
        for (int i = 0; i < limits.length; i++) limitLabels[i] = "最多 " + limits[i] + " 条";
        bind(maxAgenda, limitLabels, limits, settings.maxAgenda());

        showTime.setChecked(settings.showTime());
        showMarks.setChecked(settings.showMarks());
        showAdjust.setChecked(settings.showAdjust());
        showBorder.setChecked(settings.showBorder());
    }

    private static void bind(Spinner spinner, String[] labels, int[] values, int value) {
        spinner.setAdapter(new ArrayAdapter<>(spinner.getContext(),
                android.R.layout.simple_spinner_dropdown_item, labels));
        for (int i = 0; i < values.length; i++)
            if (values[i] == value) {
                spinner.setSelection(i);
                return;
            }
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private ArrayAdapter<String> strings(String... values) {
        return new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, values);
    }

    public void initialize() {
        if (CalendarRepository.hasPermission(activity)) {
            note = "";
            load();
        } else {
            note = "未授权读取日历 · 仅显示空白月历，点“导入 / 刷新当月日程”授权。";
            renderEmpty();
        }
    }

    private void wire() {
        activity.findViewById(R.id.btn_prev_month).setOnClickListener(v -> shiftMonth(-1));
        activity.findViewById(R.id.btn_this_month).setOnClickListener(v -> {
            settings.setMonthOffset(0);
            load();
        });
        activity.findViewById(R.id.btn_next_month).setOnClickListener(v -> shiftMonth(1));
        activity.findViewById(R.id.btn_import_calendar).setOnClickListener(v -> {
            if (!CalendarRepository.hasPermission(activity)) {
                activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_CALENDAR);
            } else {
                load();
            }
        });
        activity.findViewById(R.id.btn_calendar_sources).setOnClickListener(v -> showSources());

        orientation.setOnItemSelectedListener(change(() -> settings.setOrientation(orientation.getSelectedItemPosition())));

        final int[] margins = AppSettings.marginOptions();
        marginTop.setOnItemSelectedListener(change(() -> settings.setMarginTop(margins[marginTop.getSelectedItemPosition()])));
        marginBottom.setOnItemSelectedListener(change(() -> settings.setMarginBottom(margins[marginBottom.getSelectedItemPosition()])));
        marginLeft.setOnItemSelectedListener(change(() -> settings.setMarginLeft(margins[marginLeft.getSelectedItemPosition()])));
        marginRight.setOnItemSelectedListener(change(() -> settings.setMarginRight(margins[marginRight.getSelectedItemPosition()])));

        weekStart.setOnItemSelectedListener(change(() -> settings.setWeekStart(
                weekStart.getSelectedItemPosition() == 0 ? Calendar.MONDAY : Calendar.SUNDAY)));
        todayStyle.setOnItemSelectedListener(change(() -> settings.setTodayStyle(todayStyle.getSelectedItemPosition())));
        weekendStyle.setOnItemSelectedListener(change(() -> settings.setWeekendStyle(weekendStyle.getSelectedItemPosition())));

        fontTitle.setOnItemSelectedListener(change(() -> settings.setFontTitle(fontTitle.getSelectedItemPosition())));
        fontWeek.setOnItemSelectedListener(change(() -> settings.setFontWeek(fontWeek.getSelectedItemPosition())));
        fontDate.setOnItemSelectedListener(change(() -> settings.setFontDate(fontDate.getSelectedItemPosition())));
        fontAgenda.setOnItemSelectedListener(change(() -> settings.setFontAgenda(fontAgenda.getSelectedItemPosition())));

        markerStyle.setOnItemSelectedListener(change(() -> settings.setMarkerStyle(markerStyle.getSelectedItemPosition())));
        final int[] limits = AppSettings.agendaLimits();
        maxAgenda.setOnItemSelectedListener(change(() -> settings.setMaxAgenda(limits[maxAgenda.getSelectedItemPosition()])));

        showTime.setOnCheckedChangeListener((b, checked) -> {
            settings.setShowTime(checked);
            render();
        });
        showMarks.setOnCheckedChangeListener((b, checked) -> {
            settings.setShowMarks(checked);
            render();
        });
        showAdjust.setOnCheckedChangeListener((b, checked) -> {
            settings.setShowAdjust(checked);
            render();
        });
        showBorder.setOnCheckedChangeListener((b, checked) -> {
            settings.setShowBorder(checked);
            render();
        });
        push.setOnClickListener(v -> confirmPush());
    }

    private AdapterView.OnItemSelectedListener change(Runnable apply) {
        return new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                apply.run();
                render();
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
    }

    public void onPermissionResult() {
        if (CalendarRepository.hasPermission(activity)) {
            note = "";
            load();
        } else {
            note = "未授权读取日历 · 仅显示空白月历。";
            renderEmpty();
        }
    }

    private void shiftMonth(int delta) {
        settings.setMonthOffset(settings.monthOffset() + delta);
        load();
    }

    private Calendar yearMonth() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.add(Calendar.MONTH, settings.monthOffset());
        return calendar;
    }

    private void load() {
        if (!CalendarRepository.hasPermission(activity)) {
            renderEmpty();
            return;
        }
        final Calendar ym = yearMonth();
        final int year = ym.get(Calendar.YEAR), month = ym.get(Calendar.MONTH);
        final int token = ++loadRevision;
        loading = true;
        info.setText("正在读取 " + (month + 1) + " 月日程…");
        new Thread(() -> {
            List<CalendarEvent> loaded = CalendarRepository.events(activity, year, month, settings.calendarIds());
            main.post(() -> {
                if (token != loadRevision) return;
                events = loaded;
                loading = false;
                render();
            });
        }, "calendar-load").start();
    }

    private void renderEmpty() {
        events = new ArrayList<>();
        render();
    }

    private void render() {
        final Calendar ym = yearMonth();
        final CalendarMonth month = CalendarMonth.build(ym.get(Calendar.YEAR), ym.get(Calendar.MONTH), events, settings.weekStart());
        final CalendarStyle style = CalendarStyle.from(settings);
        final int token = ++renderRevision;
        new Thread(() -> {
            try {
                Bitmap page = CalendarRenderer.render(month, style);
                PanelImage.Prepared result = PanelImage.encodePanel(page,
                        style.summary() + " · 日历：" + (settings.calendarIds().isEmpty() ? "全部可见" : settings.calendarIds().size() + " 个"),
                        settings.panelType());
                main.post(() -> {
                    if (token != renderRevision) return;
                    prepared = result;
                    preview.setImage(result.preview);
                    info.setText((note.isEmpty() ? "" : note + "\n")
                            + month.title() + " · " + month.events.size() + " 条日程"
                            + (loading ? " · 更新中…" : "")
                            + "\n" + style.summary());
                    if (autoPushPending && settings.monthOffset() == 0) {
                        autoPushPending = false;
                        if (settings.bleMode() && settings.calendarAutoPush() && hub.ready() && !hub.busy())
                            hub.push(prepared);
                    } else {
                        autoPushPending = false;
                    }
                });
            } catch (Throwable e) {
                main.post(() -> {
                    if (token != renderRevision) return;
                    autoPushPending = false;
                    prepared = null;
                    info.setText("日历渲染失败：" + e.getMessage());
                });
            }
        }, "calendar-render").start();
    }

    private void showSources() {
        if (!CalendarRepository.hasPermission(activity)) {
            Toast.makeText(activity, "请先导入 / 授权读取日历", Toast.LENGTH_SHORT).show();
            return;
        }
        List<CalendarRepository.CalendarInfo> calendars = CalendarRepository.calendars(activity);
        if (calendars.isEmpty()) {
            Toast.makeText(activity, "未找到可用的日历账户", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<Long> selected = settings.calendarIds();
        boolean all = selected.isEmpty();
        String[] names = new String[calendars.size()];
        boolean[] checked = new boolean[calendars.size()];
        for (int i = 0; i < calendars.size(); i++) {
            names[i] = calendars.get(i).name + (calendars.get(i).visible ? "" : "（隐藏）");
            checked[i] = all || selected.contains(calendars.get(i).id);
        }
        new AlertDialog.Builder(activity)
                .setTitle("选择要导入的日历")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    Set<Long> picked = new HashSet<>();
                    int count = 0;
                    for (int i = 0; i < checked.length; i++)
                        if (checked[i]) {
                            picked.add(calendars.get(i).id);
                            count++;
                        }
                    // All selected is the same as no filter.
                    settings.setCalendarIds(count == calendars.size() ? new HashSet<>() : picked);
                    load();
                })
                .show();
    }

    /** Reload the month on screen. Does not push. */
    public void reloadQuiet() {
        autoPushPending = false;
        if (!CalendarRepository.hasPermission(activity)) return;
        load();
    }

    /** Switch to the current month, then push after that preview is ready. */
    public void reloadCurrentMonthThenPush() {
        autoPushPending = true;
        if (settings.monthOffset() != 0) settings.setMonthOffset(0);
        if (!CalendarRepository.hasPermission(activity)) {
            autoPushPending = false;
            return;
        }
        load();
    }

    private void confirmPush() {
        if (prepared == null) {
            Toast.makeText(activity, "预览尚未生成", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hub.ready()) {
            Toast.makeText(activity, "请先完成连接", Toast.LENGTH_SHORT).show();
            return;
        }
        String mode = settings.bleMode() ? "蓝牙" : "Shizuku + NFC";
        new AlertDialog.Builder(activity)
                .setTitle("推送日程页到墨水屏？")
                .setMessage("连接方式：" + mode + "\n\n" + prepared.settings
                        + "\n\n请保持应用在前台，直到日志提示结束。")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("开始推送", (d, w) -> {
                    if (!hub.push(prepared))
                        Toast.makeText(activity, "无法开始推送，请检查连接状态", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
