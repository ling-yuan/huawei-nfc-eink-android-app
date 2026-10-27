package org.nfcresearch.qpolllab.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.nfcresearch.qpolllab.R;
import org.nfcresearch.qpolllab.image.ImageStore;
import org.nfcresearch.qpolllab.image.PanelImage;
import org.nfcresearch.qpolllab.link.DeviceHub;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Image page: multi-image library, per-image settings, carousel and push. */
public final class ImagePage {
    public static final int REQUEST_PICK = 41;
    private static final int[] ROTATIONS = {0, 90, 180, 270};

    private final Activity activity;
    private final AppSettings settings;
    private final DeviceHub hub;
    private final ImageStore store;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final PixelPreview preview;
    private final TextView info;
    private final TextView thresholdLabel;
    private final LinearLayout thumbs;
    private final Spinner fit, rotation, blocks, carouselUnit;
    private final CheckBox binary, dither, carousel;
    private final SeekBar threshold;
    private final EditText carouselInterval;
    private final Button push;

    private ImageStore.Entry current;
    private Bitmap source;
    private PanelImage.Prepared prepared;
    private Dialog pixelDialog;
    private boolean wiring;
    private boolean pushAfterPreview;

    private final Object queueLock = new Object();
    private PreviewJob pending;
    private Thread worker;
    private int requestId, appliedId, sourceGeneration;
    private final Runnable debouncedRender = this::renderNow;
    private final Runnable carouselTick = this::carouselPush;

    private static final class PreviewJob {
        final Bitmap source;
        final int angle, block, threshold, generation, id;
        final boolean cover, dither, binary, cropped;
        final String panelType;

        PreviewJob(Bitmap source, int angle, boolean cover, boolean dither, int block,
                   boolean binary, int threshold, boolean cropped, String panelType, int generation, int id) {
            this.source = source;
            this.angle = angle;
            this.cover = cover;
            this.dither = dither;
            this.block = block;
            this.binary = binary;
            this.threshold = threshold;
            this.cropped = cropped;
            this.panelType = panelType;
            this.generation = generation;
            this.id = id;
        }
    }

    public ImagePage(Activity activity, AppSettings settings, DeviceHub hub) {
        this.activity = activity;
        this.settings = settings;
        this.hub = hub;
        this.store = new ImageStore(activity, settings);
        preview = activity.findViewById(R.id.image_preview);
        info = activity.findViewById(R.id.image_info);
        thresholdLabel = activity.findViewById(R.id.label_threshold);
        thumbs = activity.findViewById(R.id.image_thumbs);
        fit = activity.findViewById(R.id.sp_fit);
        rotation = activity.findViewById(R.id.sp_rotation);
        blocks = activity.findViewById(R.id.sp_blocks);
        binary = activity.findViewById(R.id.cb_binary);
        dither = activity.findViewById(R.id.cb_dither);
        threshold = activity.findViewById(R.id.seek_threshold);
        carousel = activity.findViewById(R.id.cb_carousel);
        carouselInterval = activity.findViewById(R.id.et_carousel_interval);
        carouselUnit = activity.findViewById(R.id.sp_carousel_unit);
        push = activity.findViewById(R.id.btn_push_image);
        wire();
    }

    public void initialize() {
        if (store.size() == 0) {
            try {
                store.addTestPattern(PanelImage.testPattern());
            } catch (Exception e) {
                info.setText("初始化失败：" + e.getMessage());
            }
        }
        rebuildThumbs();
        loadSelected();
        syncCarouselControls();
        scheduleCarousel();
    }

    public void onLinkModeChanged() {
        scheduleCarousel();
    }

    public void onConnectedChanged() {
        scheduleCarousel();
    }

    public PanelImage.Prepared prepared() {
        return prepared;
    }

    public void advanceAndPush() {
        if (store.size() == 0) return;
        int next = (store.selectedIndex() + 1) % store.size();
        store.select(next);
        rebuildThumbs();
        loadSelected(true);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void wire() {
        wiring = true;
        fit.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{activity.getString(R.string.image_fit_letterbox), activity.getString(R.string.image_fit_cover)}));
        rotation.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"0°", "90°", "180°", "270°"}));
        int[] sizes = AppSettings.blockSizes();
        String[] blockLabels = new String[sizes.length];
        blockLabels[0] = "原始细节";
        for (int i = 1; i < sizes.length; i++) blockLabels[i] = sizes[i] + "×" + sizes[i] + " 色块";
        blocks.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, blockLabels));
        carouselUnit.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"分钟", "小时", "天", "周"}));

        activity.findViewById(R.id.btn_crop).setOnClickListener(v -> showCrop());
        activity.findViewById(R.id.btn_reset_image).setOnClickListener(v -> resetDefaults());
        activity.findViewById(R.id.btn_delete_image).setOnClickListener(v -> deleteCurrent());
        preview.setOnClickListener(v -> showPixels());
        push.setOnClickListener(v -> confirmPush());

        fit.setOnItemSelectedListener(simple(pos -> {
            if (wiring || current == null) return;
            current.cover = pos == 1;
            store.saveEntry(current);
            renderSoon();
        }));
        rotation.setOnItemSelectedListener(simple(pos -> {
            if (wiring || current == null) return;
            current.rotation = ROTATIONS[pos];
            store.saveEntry(current);
            renderSoon();
        }));
        blocks.setOnItemSelectedListener(simple(pos -> {
            if (wiring || current == null) return;
            current.blockSize = AppSettings.blockSizes()[pos];
            store.saveEntry(current);
            syncControlState();
            renderSoon();
        }));
        binary.setOnCheckedChangeListener((b, checked) -> {
            if (wiring || current == null) return;
            current.binary = checked;
            store.saveEntry(current);
            syncControlState();
            renderSoon();
        });
        dither.setOnCheckedChangeListener((b, checked) -> {
            if (wiring || current == null) return;
            current.dither = checked;
            store.saveEntry(current);
            renderSoon();
        });
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                thresholdLabel.setText(activity.getString(R.string.image_threshold, value));
                if (!fromUser || wiring || current == null) return;
                current.threshold = value;
                store.saveEntry(current);
                if (current.binary) renderSoon();
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                if (wiring || current == null) return;
                current.threshold = seekBar.getProgress();
                store.saveEntry(current);
                if (current.binary) renderNow();
            }
        });
        carousel.setOnCheckedChangeListener((b, checked) -> {
            if (wiring) return;
            settings.setCarouselEnabled(checked);
            if (checked && !settings.bleMode())
                Toast.makeText(activity, R.string.image_carousel_hint, Toast.LENGTH_LONG).show();
            scheduleCarousel();
            if (activity instanceof MainActivity) ((MainActivity) activity).syncKeepAlive();
        });
        carouselUnit.setOnItemSelectedListener(simple(pos -> {
            if (wiring) return;
            settings.setCarouselUnitIndex(pos);
            scheduleCarousel();
        }));
        carouselInterval.setText(Integer.toString(settings.carouselInterval()));
        carouselInterval.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                if (wiring) return;
                try {
                    int value = Integer.parseInt(s.toString().trim());
                    settings.setCarouselInterval(Math.max(1, value));
                    scheduleCarousel();
                } catch (NumberFormatException ignored) {
                }
            }
        });
        wiring = false;
    }

    private AdapterView.OnItemSelectedListener simple(IntConsumer apply) {
        return new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                apply.accept(position);
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private void rebuildThumbs() {
        thumbs.removeAllViews();
        for (int i = 0; i < store.size(); i++) {
            final int index = i;
            ImageStore.Entry entry = store.get(i);
            ImageView thumb = new ImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
            lp.rightMargin = dp(8);
            thumb.setLayoutParams(lp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(Color.LTGRAY);
            if (index == store.selectedIndex()) thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
            Bitmap bmp = store.loadThumb(entry, 128);
            if (bmp != null) thumb.setImageBitmap(bmp);
            thumb.setOnClickListener(v -> {
                store.select(index);
                rebuildThumbs();
                loadSelected();
            });
            thumbs.addView(thumb);
        }
        Button add = new Button(activity);
        add.setText("+");
        add.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        add.setOnClickListener(v -> choose());
        thumbs.addView(add);
    }

    private void loadSelected() {
        loadSelected(false);
    }

    private void loadSelected(boolean pushAfter) {
        pushAfterPreview = pushAfter;
        current = store.selected();
        if (current == null) {
            pushAfterPreview = false;
            source = null;
            prepared = null;
            info.setText("请添加图片");
            return;
        }
        wiring = true;
        fit.setSelection(current.cover ? 1 : 0);
        int rotPos = 0;
        for (int i = 0; i < ROTATIONS.length; i++) if (ROTATIONS[i] == current.rotation) rotPos = i;
        rotation.setSelection(rotPos);
        int blockPos = 0;
        int[] sizes = AppSettings.blockSizes();
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == current.blockSize) blockPos = i;
        blocks.setSelection(blockPos);
        binary.setChecked(current.binary);
        dither.setChecked(current.dither);
        threshold.setProgress(current.threshold);
        thresholdLabel.setText(activity.getString(R.string.image_threshold, current.threshold));
        syncControlState();
        wiring = false;
        info.setText("正在加载图片…");
        final ImageStore.Entry entry = current;
        final int generation = ++sourceGeneration;
        new Thread(() -> {
            try {
                Bitmap bitmap = store.loadBitmap(entry);
                main.post(() -> {
                    if (generation != sourceGeneration || current != entry) return;
                    source = bitmap;
                    renderNow();
                });
            } catch (Throwable e) {
                main.post(() -> {
                    if (generation == sourceGeneration) pushAfterPreview = false;
                    info.setText("图片读取失败：" + e.getMessage());
                });
            }
        }, "image-load").start();
    }

    private void syncControlState() {
        if (current == null) return;
        boolean fine = current.blockSize == 1;
        dither.setEnabled(fine);
        if (!fine && current.dither) {
            current.dither = false;
            dither.setChecked(false);
            store.saveEntry(current);
        }
        threshold.setEnabled(current.binary);
        thresholdLabel.setEnabled(current.binary);
        thresholdLabel.setAlpha(current.binary ? 1f : 0.45f);
    }

    private void syncCarouselControls() {
        wiring = true;
        carousel.setChecked(settings.carouselEnabled());
        carouselUnit.setSelection(settings.carouselUnitIndex());
        carouselInterval.setText(Integer.toString(settings.carouselInterval()));
        wiring = false;
    }

    private void resetDefaults() {
        if (current == null) return;
        try {
            store.clearCrop(current);
            current.rotation = 0;
            current.cover = false;
            current.dither = false;
            current.blockSize = 1;
            current.binary = false;
            current.threshold = 128;
            store.saveEntry(current);
            loadSelected();
            rebuildThumbs();
        } catch (Exception e) {
            Toast.makeText(activity, "还原失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteCurrent() {
        if (store.size() == 0) return;
        int index = store.selectedIndex();
        store.remove(index);
        rebuildThumbs();
        if (store.size() == 0) {
            try {
                store.addTestPattern(PanelImage.testPattern());
                rebuildThumbs();
            } catch (Exception ignored) {
            }
        }
        loadSelected();
    }

    private void renderSoon() {
        main.removeCallbacks(debouncedRender);
        main.postDelayed(debouncedRender, 40);
    }

    private void renderNow() {
        main.removeCallbacks(debouncedRender);
        final Bitmap input = source;
        final ImageStore.Entry entry = current;
        if (input == null || entry == null) return;
        synchronized (queueLock) {
            pending = new PreviewJob(input, entry.rotation, entry.cover, entry.dither, entry.blockSize,
                    entry.binary, entry.threshold, entry.cropped, settings.panelType(), sourceGeneration, ++requestId);
            if (worker == null) {
                worker = new Thread(this::drainPreview, "image-preview");
                worker.setDaemon(true);
                worker.start();
            }
        }
        if (prepared == null) info.setText("正在生成四色预览…");
    }

    private void drainPreview() {
        while (true) {
            PreviewJob job;
            synchronized (queueLock) {
                job = pending;
                pending = null;
                if (job == null) {
                    worker = null;
                    return;
                }
            }
            PanelImage.Prepared result = null;
            Throwable error = null;
            try {
                result = PanelImage.prepare(job.source, job.angle, job.cover, job.dither,
                        job.block, job.binary, job.threshold, job.cropped, job.panelType);
            } catch (Throwable t) {
                error = t;
            }
            final PanelImage.Prepared done = result;
            final Throwable failure = error;
            main.post(() -> applyPreview(job, done, failure));
        }
    }

    private void applyPreview(PreviewJob job, PanelImage.Prepared result, Throwable error) {
        if (job.generation != sourceGeneration) return;
        if (job.id < appliedId) return;
        appliedId = job.id;
        if (result == null) {
            pushAfterPreview = false;
            prepared = null;
            info.setText("预览失败：" + (error == null ? "未知错误" : error.getMessage()));
            return;
        }
        prepared = result;
        preview.setImage(result.preview);
        info.setText(result.settings);
        if (pushAfterPreview && job.generation == sourceGeneration) {
            pushAfterPreview = false;
            if (settings.carouselEnabled() && settings.bleMode() && hub.ready() && !hub.busy())
                hub.push(prepared);
        }
    }

    private void choose() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    public void handlePick(Intent data) {
        if (data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        info.setText("正在读取图片…");
        new Thread(() -> {
            try {
                store.addFromUri(uri);
                main.post(() -> {
                    rebuildThumbs();
                    loadSelected();
                });
            } catch (Throwable e) {
                main.post(() -> info.setText("图片读取失败：" + e.getMessage()));
            }
        }, "image-import").start();
    }

    private void showCrop() {
        if (source == null || current == null) return;
        final CropView cropView = new CropView(activity, PanelImage.rotated(source, current.rotation));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), 0);
        TextView hint = new TextView(activity);
        hint.setText("单指拖动，双指缩放；白框内为保留区域。");
        hint.setTextSize(13);
        content.addView(hint);
        int height = Math.min(dp(380), activity.getResources().getDisplayMetrics().heightPixels / 2);
        content.addView(cropView, new LinearLayout.LayoutParams(-1, height));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.image_crop))
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton("重置裁切", null)
                .setPositiveButton("应用裁切", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> cropView.reset());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Bitmap cropped = cropView.result();
                store.setCropped(current, cropped);
                current.rotation = 0;
                current.cover = true;
                store.saveEntry(current);
                dialog.dismiss();
                loadSelected();
            } catch (Exception e) {
                Toast.makeText(activity, "裁切失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showPixels() {
        if (prepared == null) return;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(activity.getColor(R.color.bg));
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView title = new TextView(activity);
        title.setText("最终发送像素 · 552 × 768");
        title.setTextSize(18);
        title.setTextColor(activity.getColor(R.color.text));
        content.addView(title);
        final ZoomPreview viewer = new ZoomPreview(activity, prepared.preview);
        content.addView(viewer, new LinearLayout.LayoutParams(-1, 0, 1));
        Button close = new Button(activity);
        close.setText(R.string.action_close);
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close);
        dialog.setContentView(content);
        pixelDialog = dialog;
        dialog.setOnDismissListener(d -> pixelDialog = null);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }

    private void confirmPush() {
        if (prepared == null) {
            Toast.makeText(activity, "预览尚未生成，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hub.ready()) {
            Toast.makeText(activity, "请先完成连接", Toast.LENGTH_SHORT).show();
            return;
        }
        String mode = settings.bleMode() ? "蓝牙 InkSight398" : "Shizuku + NFC";
        new AlertDialog.Builder(activity)
                .setTitle("推送当前图片到墨水屏？")
                .setMessage("连接方式：" + mode + "\n\n" + prepared.settings
                        + "\n\n请保持应用在前台，直到日志提示结束。")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("开始推送", (d, w) -> {
                    if (!hub.push(prepared))
                        Toast.makeText(activity, "无法开始推送，请检查连接状态", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    public void scheduleCarousel() {
        main.removeCallbacks(carouselTick);
        if (!settings.carouselEnabled() || !settings.bleMode()) return;
        if (!hub.ready()) return;
        main.postDelayed(carouselTick, settings.carouselIntervalMs());
    }

    private void carouselPush() {
        if (!settings.carouselEnabled() || !settings.bleMode() || !hub.ready() || hub.busy()) {
            scheduleCarousel();
            return;
        }
        advanceAndPush();
        scheduleCarousel();
    }
}
