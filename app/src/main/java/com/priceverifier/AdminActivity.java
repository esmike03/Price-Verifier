package com.priceverifier;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdminActivity extends Activity {
    private static final int REQUEST_IMPORT_FILE = 1001;
    private static final int REQUEST_LOGO_FILE = 1002;
    private static final int REQUEST_BANNER_FILE = 1003;
    private static final int REQUEST_VIDEO_FILE = 1004;
    private static final int REQUEST_SCHEDULE_IMAGE_FILE = 1005;
    private static final int REQUEST_SCHEDULE_VIDEO_FILE = 1006;
    private static final String PREFS = "admin";
    private static final String KEY_PIN = "pin";
    private static final String DEFAULT_PIN = "1234";
    // Size of the logo preview at 100%. The frame around it holds the preview at
    // AppSettings.MAX_LOGO_SCALE_PERCENT, so the preview never overflows.
    private static final float LOGO_PREVIEW_BASE_DP = 40f;
    private static final int LOGO_SCALE_STEP = 5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ProductDatabase database;
    private AppSettings settings;
    private SharedPreferences preferences;
    private LinearLayout adminRoot;
    private LinearLayout pinPanel;
    private View adminPanel;
    private LinearLayout lockedPanel;
    private TextView lockedMessage;
    private EditText pinInput;
    private TextView catalogSummary;

    // Views that live inside a module dialog. They are non-null only while their
    // dialog is on screen; async callbacks must null-check before touching them.
    private TextView productCount;
    private TextView importStatus;
    private EditText storeNameInput;
    private CheckBox videoLogoToggle;
    private EditText marqueeInput;
    private EditText marqueeBgColorInput;
    private EditText marqueeTextColorInput;
    private EditText scanPromptColorInput;
    private EditText imageSecondsInput;
    private EditText videoSecondsInput;
    private ImageView logoPreview;
    private SeekBar logoScaleSlider;
    private TextView logoScaleValue;
    private ImageView bannerPreview;
    private TextView logoStatus;
    private TextView bannerStatus;
    private TextView videoStatus;
    private LinearLayout mediaOrderList;
    private TextView mediaOrderStatus;
    private List<String> mediaOrderTokens;
    private List<EditText> mediaOrderInputs;
    private EditText lanUrlInput;
    private Button autoSyncTimeButton;
    private CheckBox autoSyncToggle;
    private TextView lanStatus;
    private TextView autoSyncHint;
    private View manualSyncButtons;
    private ProgressBar lanSyncProgress;
    private LinearLayout scheduleListContainer;
    private TextView scheduleEmptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        database = new ProductDatabase(this);
        settings = new AppSettings(this);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        adminRoot = findViewById(R.id.adminRoot);
        pinPanel = findViewById(R.id.pinPanel);
        adminPanel = findViewById(R.id.adminPanel);
        lockedPanel = findViewById(R.id.lockedPanel);
        lockedMessage = findViewById(R.id.lockedMessage);
        pinInput = findViewById(R.id.pinInput);
        catalogSummary = findViewById(R.id.catalogSummary);

        Button unlockButton = findViewById(R.id.unlockButton);
        Button adminBackButton = findViewById(R.id.adminBackButton);

        unlockButton.setOnClickListener(v -> unlockIfValid());
        adminBackButton.setOnClickListener(v -> finish());
        pinInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlockIfValid();
                return true;
            }
            return false;
        });

        findViewById(R.id.moduleCatalog).setOnClickListener(v -> showCatalogModule());
        findViewById(R.id.moduleManual).setOnClickListener(v -> showManualModule());
        findViewById(R.id.moduleMedia).setOnClickListener(v -> showMediaModule());
        findViewById(R.id.moduleSchedule).setOnClickListener(v -> showScheduleModule());
        findViewById(R.id.modulePriceCheck).setOnClickListener(v -> showPriceCheckModule());
        findViewById(R.id.moduleLan).setOnClickListener(v -> showLanModule());
        findViewById(R.id.moduleSecurity).setOnClickListener(v -> showSecurityModule());

        updateCount();

        // Gate the whole admin screen on the device being registered. An
        // unregistered device never sees the PIN prompt or the modules.
        if (!DeviceAccess.isAuthorized(this)) {
            lockDownForUnauthorizedDevice(DeviceAccess.serial(this));
            return;
        }
        focusPinInput();
    }

    private void lockDownForUnauthorizedDevice(String serial) {
        pinPanel.setVisibility(View.GONE);
        adminPanel.setVisibility(View.GONE);
        lockedPanel.setVisibility(View.VISIBLE);
        lockedMessage.setText(getString(R.string.device_not_authorized,
                serial.isEmpty() ? getString(R.string.unavailable) : serial));
        hideKeyboard();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        switch (requestCode) {
            case REQUEST_IMPORT_FILE:
                importFile(uri);
                break;
            case REQUEST_LOGO_FILE:
                saveImageFromUri(uri, settings.logoFile(), R.string.idle_logo_saved);
                break;
            case REQUEST_BANNER_FILE:
                saveImageFromUri(uri, settings.bannerFile(), R.string.idle_banner_saved);
                break;
            case REQUEST_VIDEO_FILE:
                saveImageFromUri(uri, settings.videoFile(), R.string.idle_video_saved);
                break;
            case REQUEST_SCHEDULE_IMAGE_FILE:
                saveScheduledMedia(uri, false);
                break;
            case REQUEST_SCHEDULE_VIDEO_FILE:
                saveScheduledMedia(uri, true);
                break;
        }
    }

    private void unlockIfValid() {
        String expected = preferences.getString(KEY_PIN, DEFAULT_PIN);
        if (Product.clean(pinInput.getText().toString()).equals(expected)) {
            pinInput.setText("");
            pinPanel.setVisibility(View.GONE);
            adminPanel.setVisibility(View.VISIBLE);
            hideKeyboard();
            adminRoot.requestFocus();
            updateCount();
        } else {
            Toast.makeText(this, R.string.invalid_pin, Toast.LENGTH_SHORT).show();
        }
    }

    // --- Module dialogs ---------------------------------------------------

    private AlertDialog buildModuleDialog(int titleRes, View content, Runnable onDismiss) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
        return dialog;
    }

    private void showCatalogModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_catalog, null);
        productCount = view.findViewById(R.id.productCount);
        importStatus = view.findViewById(R.id.importStatus);
        Button viewProductsButton = view.findViewById(R.id.viewProductsButton);
        Button importButton = view.findViewById(R.id.importButton);
        Button clearProductsButton = view.findViewById(R.id.clearProductsButton);

        viewProductsButton.setOnClickListener(v ->
                startActivity(new Intent(this, ProductListActivity.class)));
        importButton.setOnClickListener(v -> openImportPicker());
        clearProductsButton.setOnClickListener(v -> confirmClearProducts());

        importStatus.setText(R.string.admin_import_ready);
        updateCount();

        buildModuleDialog(R.string.module_catalog_title, view, () -> {
            productCount = null;
            importStatus = null;
        });
    }

    private void showManualModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_manual, null);
        EditText barcodeInput = view.findViewById(R.id.manualBarcodeInput);
        EditText nameInput = view.findViewById(R.id.manualNameInput);
        EditText uomInput = view.findViewById(R.id.manualUomInput);
        EditText categoryInput = view.findViewById(R.id.manualCategoryInput);
        EditText priceInput = view.findViewById(R.id.manualPriceInput);
        EditText itemCodeInput = view.findViewById(R.id.manualItemCodeInput);
        Button saveButton = view.findViewById(R.id.saveManualProductButton);

        saveButton.setOnClickListener(v -> saveManualProduct(
                barcodeInput, nameInput, uomInput, categoryInput, priceInput, itemCodeInput));

        buildModuleDialog(R.string.module_manual_title, view, () -> { });
    }

    private void showMediaModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_media, null);
        view.findViewById(R.id.designServerNote).setVisibility(
                ServerDesign.managesAny(settings, ServerDesign.MEDIA_KEYS) ? View.VISIBLE : View.GONE);
        logoPreview = view.findViewById(R.id.logoPreview);
        bannerPreview = view.findViewById(R.id.bannerPreview);
        logoStatus = view.findViewById(R.id.logoStatus);
        bannerStatus = view.findViewById(R.id.bannerStatus);
        videoStatus = view.findViewById(R.id.videoStatus);
        marqueeInput = view.findViewById(R.id.marqueeInput);
        marqueeBgColorInput = view.findViewById(R.id.marqueeBgColorInput);
        marqueeTextColorInput = view.findViewById(R.id.marqueeTextColorInput);
        scanPromptColorInput = view.findViewById(R.id.scanPromptColorInput);
        imageSecondsInput = view.findViewById(R.id.imageSecondsInput);
        videoSecondsInput = view.findViewById(R.id.videoSecondsInput);
        storeNameInput = view.findViewById(R.id.storeNameInput);
        videoLogoToggle = view.findViewById(R.id.videoLogoToggle);
        logoScaleSlider = view.findViewById(R.id.logoScaleSlider);
        logoScaleValue = view.findViewById(R.id.logoScaleValue);

        Button uploadLogoButton = view.findViewById(R.id.uploadLogoButton);
        Button removeLogoButton = view.findViewById(R.id.removeLogoButton);
        Button uploadBannerButton = view.findViewById(R.id.uploadBannerButton);
        Button removeBannerButton = view.findViewById(R.id.removeBannerButton);
        Button uploadVideoButton = view.findViewById(R.id.uploadVideoButton);
        Button removeVideoButton = view.findViewById(R.id.removeVideoButton);
        Button updateIdleDisplayButton = view.findViewById(R.id.updateIdleDisplayButton);
        Button pickMarqueeBgColorButton = view.findViewById(R.id.pickMarqueeBgColorButton);
        Button pickMarqueeTextColorButton = view.findViewById(R.id.pickMarqueeTextColorButton);

        uploadLogoButton.setOnClickListener(v -> openImagePicker(REQUEST_LOGO_FILE));
        removeLogoButton.setOnClickListener(v -> removeImage(settings.logoFile(), R.string.idle_logo_removed));
        uploadBannerButton.setOnClickListener(v -> openImagePicker(REQUEST_BANNER_FILE));
        removeBannerButton.setOnClickListener(v -> removeImage(settings.bannerFile(), R.string.idle_banner_removed));
        uploadVideoButton.setOnClickListener(v -> openVideoPicker(REQUEST_VIDEO_FILE));
        removeVideoButton.setOnClickListener(v -> removeImage(settings.videoFile(), R.string.idle_video_removed));
        updateIdleDisplayButton.setOnClickListener(v -> updateIdleDisplaySettings());
        pickMarqueeBgColorButton.setOnClickListener(v -> showColorPicker(marqueeBgColorInput));
        pickMarqueeTextColorButton.setOnClickListener(v -> showColorPicker(marqueeTextColorInput));
        view.findViewById(R.id.pickScanPromptColorButton)
                .setOnClickListener(v -> showColorPicker(scanPromptColorInput));

        imageSecondsInput.setText(String.valueOf(settings.getImageSeconds()));
        videoSecondsInput.setText(String.valueOf(settings.getVideoSeconds()));

        mediaOrderList = view.findViewById(R.id.mediaOrderList);
        mediaOrderStatus = view.findViewById(R.id.mediaOrderStatus);
        Button applyOrderButton = view.findViewById(R.id.applyOrderButton);
        applyOrderButton.setOnClickListener(v -> applyMediaOrder());
        Button useServerOrderButton = view.findViewById(R.id.useServerOrderButton);
        useServerOrderButton.setOnClickListener(v -> {
            settings.setLocalMediaOrder("");
            renderMediaOrder();
            Toast.makeText(this, R.string.idle_order_following_server, Toast.LENGTH_SHORT).show();
        });

        storeNameInput.setText(settings.getStoreName());
        videoLogoToggle.setChecked(settings.isVideoLogoEnabled());
        marqueeInput.setText(settings.getMarqueeText());
        marqueeBgColorInput.setText(formatColor(settings.getMarqueeBackgroundColor()));
        marqueeTextColorInput.setText(formatColor(settings.getMarqueeTextColor()));
        scanPromptColorInput.setText(formatColor(settings.getScanPromptColor()));
        setUpLogoScaleSlider();
        refreshIdlePreviews();
        renderMediaOrder();

        buildModuleDialog(R.string.module_media_title, view, () -> {
            logoPreview = null;
            logoScaleSlider = null;
            logoScaleValue = null;
            bannerPreview = null;
            logoStatus = null;
            bannerStatus = null;
            videoStatus = null;
            mediaOrderList = null;
            mediaOrderStatus = null;
            mediaOrderTokens = null;
            mediaOrderInputs = null;
            storeNameInput = null;
            videoLogoToggle = null;
            marqueeInput = null;
            marqueeBgColorInput = null;
            marqueeTextColorInput = null;
            scanPromptColorInput = null;
            imageSecondsInput = null;
            videoSecondsInput = null;
        });
    }

    /** Renders the playback-order list for the items present on this device. */
    private void renderMediaOrder() {
        if (mediaOrderList == null) {
            return;
        }
        mediaOrderTokens = currentDeviceOrder();
        mediaOrderInputs = new ArrayList<>();
        mediaOrderList.removeAllViews();
        if (mediaOrderTokens.isEmpty()) {
            if (mediaOrderStatus != null) {
                mediaOrderStatus.setText(R.string.idle_order_empty);
            }
            return;
        }
        if (mediaOrderStatus != null) {
            mediaOrderStatus.setText(settings.hasLocalMediaOrder()
                    ? R.string.idle_order_using_local : R.string.idle_order_following_server);
        }
        for (int i = 0; i < mediaOrderTokens.size(); i++) {
            mediaOrderList.addView(buildOrderRow(i));
        }
    }

    /**
     * The idle items on this device, sequenced by the effective order (local
     * override if set, otherwise the synced server order), with any present but
     * unordered item appended.
     */
    private List<String> currentDeviceOrder() {
        List<String> universe = deviceIdleTokens();
        List<String> result = new ArrayList<>();
        for (String token : IdleMediaItem.parseOrder(settings.getEffectiveMediaOrder())) {
            if (universe.contains(token) && !result.contains(token)) {
                result.add(token);
            }
        }
        for (String token : universe) {
            if (!result.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    /** Every idle item stored on this device, as order tokens. */
    private List<String> deviceIdleTokens() {
        List<String> tokens = new ArrayList<>();
        if (settings.hasBanner()) {
            tokens.add("banner");
        }
        if (settings.hasVideo()) {
            tokens.add("video");
        }
        List<String> media = new ArrayList<>();
        File[] files = settings.mediaDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file != null && file.isFile() && file.length() > 0
                        && IdleMediaItem.isScheduledMediaName(file.getName())) {
                    media.add(file.getName().toLowerCase(Locale.US));
                }
            }
        }
        Collections.sort(media);
        tokens.addAll(media);
        return tokens;
    }

    private View buildOrderRow(int index) {
        String token = mediaOrderTokens.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(6);
        row.setLayoutParams(rowParams);

        EditText position = new EditText(this);
        position.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(44)));
        position.setInputType(InputType.TYPE_CLASS_NUMBER);
        position.setGravity(Gravity.CENTER);
        position.setText(String.valueOf(index + 1));
        position.setTextColor(getColor(R.color.ink));
        position.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        position.setBackgroundResource(R.drawable.input_field);
        position.setContentDescription(getString(R.string.idle_order_position, tokenLabel(token)));
        mediaOrderInputs.add(position);

        TextView label = new TextView(this);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(10));
        label.setLayoutParams(labelParams);
        label.setText(tokenLabel(token));
        label.setTextColor(getColor(R.color.ink));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);

        row.addView(position);
        row.addView(label);
        return row;
    }

    private String tokenLabel(String token) {
        if (token.equals("banner")) {
            return getString(R.string.idle_order_banner);
        }
        if (token.equals("video")) {
            return getString(R.string.idle_order_video);
        }
        return token;
    }

    /**
     * Reads the number typed for each item and saves the new order locally: items
     * sort by their number (1 first), blanks/invalid entries keep their current
     * spot, and ties break by current position so nothing is lost.
     */
    private void applyMediaOrder() {
        if (mediaOrderTokens == null || mediaOrderInputs == null
                || mediaOrderTokens.isEmpty()
                || mediaOrderTokens.size() != mediaOrderInputs.size()) {
            return;
        }
        int count = mediaOrderTokens.size();
        Integer[] order = new Integer[count];
        final double[] positions = new double[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
            String raw = mediaOrderInputs.get(i).getText().toString().trim();
            double value;
            try {
                value = raw.isEmpty() ? (i + 1) : Double.parseDouble(raw);
            } catch (NumberFormatException ex) {
                value = i + 1;
            }
            positions[i] = value;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(positions[a], positions[b]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        StringBuilder sb = new StringBuilder();
        for (int i : order) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(mediaOrderTokens.get(i));
        }
        settings.setLocalMediaOrder(sb.toString());
        renderMediaOrder();
        Toast.makeText(this, R.string.idle_order_applied, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private void showLanModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_lan, null);
        lanUrlInput = view.findViewById(R.id.lanUrlInput);
        lanStatus = view.findViewById(R.id.lanStatus);
        autoSyncToggle = view.findViewById(R.id.autoSyncToggle);
        autoSyncTimeButton = view.findViewById(R.id.autoSyncTimeButton);
        autoSyncHint = view.findViewById(R.id.autoSyncHint);
        manualSyncButtons = view.findViewById(R.id.manualSyncButtons);
        lanSyncProgress = view.findViewById(R.id.lanSyncProgress);

        Button saveLanUrlButton = view.findViewById(R.id.saveLanUrlButton);
        Button syncItemsButton = view.findViewById(R.id.syncItemsButton);
        Button syncImagesButton = view.findViewById(R.id.syncImagesButton);
        Button syncVideosButton = view.findViewById(R.id.syncVideosButton);

        saveLanUrlButton.setOnClickListener(v -> saveLanUrl());
        syncItemsButton.setOnClickListener(v -> syncFromLan(LanSync.Scope.ITEMS));
        syncImagesButton.setOnClickListener(v -> syncFromLan(LanSync.Scope.IMAGES));
        syncVideosButton.setOnClickListener(v -> syncFromLan(LanSync.Scope.VIDEOS));
        autoSyncTimeButton.setOnClickListener(v -> pickAutoSyncTime());

        autoSyncToggle.setChecked(settings.isAutoSyncEnabled());
        autoSyncToggle.setOnCheckedChangeListener((v, checked) -> {
            settings.setAutoSyncEnabled(checked);
            refreshAutoSyncHint();
        });

        lanUrlInput.setText(settings.getLanUrl());
        refreshAutoSyncTimeButton();
        refreshLanStatus(null);
        refreshAutoSyncHint();

        buildModuleDialog(R.string.module_lan_title, view, () -> {
            lanUrlInput = null;
            lanStatus = null;
            autoSyncToggle = null;
            autoSyncTimeButton = null;
            autoSyncHint = null;
            manualSyncButtons = null;
            lanSyncProgress = null;
        });
    }

    private void showSecurityModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_security, null);
        // Changing the PIN is disabled by the admin; the input and Save button are
        // shown greyed out with a note explaining why, and stay unwired.
        EditText newPinInput = view.findViewById(R.id.newPinInput);
        Button savePinButton = view.findViewById(R.id.savePinButton);
        newPinInput.setEnabled(false);
        savePinButton.setEnabled(false);
        buildModuleDialog(R.string.module_security_title, view, () -> { });
    }

    private void showScheduleModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_schedule, null);
        scheduleListContainer = view.findViewById(R.id.scheduleList);
        scheduleEmptyView = view.findViewById(R.id.scheduleEmpty);
        Button uploadImageButton = view.findViewById(R.id.scheduleUploadImage);
        Button uploadVideoButton = view.findViewById(R.id.scheduleUploadVideo);
        uploadImageButton.setOnClickListener(v -> openScheduleImagePicker());
        uploadVideoButton.setOnClickListener(v -> openVideoPicker(REQUEST_SCHEDULE_VIDEO_FILE));
        populateScheduleList();
        buildModuleDialog(R.string.module_schedule_title, view, () -> {
            scheduleListContainer = null;
            scheduleEmptyView = null;
        });
    }

    private void populateScheduleList() {
        if (scheduleListContainer == null) {
            return;
        }
        scheduleListContainer.removeAllViews();

        List<File> scheduled = new ArrayList<>();
        File[] files = settings.mediaDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file != null && file.isFile()
                        && IdleMediaItem.isSupportedMediaName(file.getName())) {
                    scheduled.add(file);
                }
            }
        }
        Collections.sort(scheduled, (a, b) ->
                a.getName().toLowerCase(Locale.US).compareTo(b.getName().toLowerCase(Locale.US)));

        scheduleEmptyView.setVisibility(scheduled.isEmpty() ? View.VISIBLE : View.GONE);

        for (File file : scheduled) {
            View row = getLayoutInflater().inflate(
                    R.layout.row_schedule_media, scheduleListContainer, false);
            TextView name = row.findViewById(R.id.scheduleRowName);
            TextView meta = row.findViewById(R.id.scheduleRowMeta);
            Button rename = row.findViewById(R.id.scheduleRowRename);
            Button delete = row.findViewById(R.id.scheduleRowDelete);
            name.setText(file.getName());
            meta.setText(formatScheduleMeta(file));
            rename.setOnClickListener(v -> renameScheduledFile(file));
            delete.setOnClickListener(v -> confirmDeleteScheduledFile(file));
            scheduleListContainer.addView(row);
        }
    }

    private String formatScheduleMeta(File file) {
        String fileName = file.getName();
        String type = getString(IdleMediaItem.isVideoName(fileName)
                ? R.string.schedule_type_video : R.string.schedule_type_image);
        long endAt = IdleMediaItem.scheduledEndMillis(fileName);
        String base;
        if (endAt < 0) {
            base = getString(R.string.schedule_not_active, type);
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault());
            String when = fmt.format(new Date(endAt));
            if (endAt < System.currentTimeMillis()) {
                base = getString(R.string.schedule_expired, type, when);
            } else {
                base = getString(R.string.schedule_shows_until, type, when);
            }
        }
        // Surface why an item might never play: the idle playlist (IdleMediaItem.load)
        // drops any scheduled file that is empty, while this list shows it by name.
        long bytes = file.length();
        if (bytes <= 0) {
            return base + " - EMPTY FILE - will not play";
        }
        return base + " - " + formatBytes(bytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void renameScheduledFile(File file) {
        final String original = file.getName();
        final String ext = original.substring(original.lastIndexOf('.') + 1);

        final EditText input = new EditText(this);
        input.setText(original);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.schedule_rename_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.schedule_rename_title)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        // Wire the positive button after show so a failed validation keeps the
        // dialog open instead of dismissing it.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String newName = input.getText().toString().trim();
                    if (newName.equals(original)) {
                        dialog.dismiss();
                        return;
                    }
                    if (!IdleMediaItem.isScheduledMediaName(newName)) {
                        Toast.makeText(this, R.string.schedule_rename_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    String newExt = newName.substring(newName.lastIndexOf('.') + 1);
                    if (!newExt.equalsIgnoreCase(ext)) {
                        Toast.makeText(this, getString(R.string.schedule_rename_ext, ext),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    File target = new File(file.getParentFile(), newName);
                    if (target.exists()) {
                        Toast.makeText(this, R.string.schedule_rename_exists, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (file.renameTo(target)) {
                        Toast.makeText(this, R.string.schedule_renamed, Toast.LENGTH_SHORT).show();
                        populateScheduleList();
                        dialog.dismiss();
                    } else {
                        Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
                    }
                }));
        dialog.show();
    }

    private void confirmDeleteScheduledFile(File file) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.schedule_remove_title)
                .setMessage(getString(R.string.schedule_remove_message, file.getName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.schedule_remove, (d, w) -> {
                    if (file.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    Toast.makeText(this, R.string.schedule_removed, Toast.LENGTH_SHORT).show();
                    populateScheduleList();
                })
                .show();
    }

    private void showPriceCheckModule() {
        View view = getLayoutInflater().inflate(R.layout.dialog_admin_pricecheck, null);
        view.findViewById(R.id.designServerNote).setVisibility(
                ServerDesign.managesAny(settings, ServerDesign.CHECK_KEYS) ? View.VISIBLE : View.GONE);
        final FrameLayout regularPreview = view.findViewById(R.id.checkPreviewRegular);
        final FrameLayout promoPreview = view.findViewById(R.id.checkPreviewPromo);
        final EditText bgInput = view.findViewById(R.id.checkBgColorInput);
        final EditText nameInput = view.findViewById(R.id.checkNameColorInput);
        final EditText metaInput = view.findViewById(R.id.checkMetaColorInput);
        final EditText priceInput = view.findViewById(R.id.checkPriceColorInput);
        // The choices on screen, saved with the colours.
        final String[] layout = {settings.getCheckLayout()};
        final String[] promoStyle = {settings.getPromoStyle()};

        bgInput.setText(formatColor(settings.getCheckBackgroundColor()));
        nameInput.setText(formatColor(settings.getCheckNameColor()));
        metaInput.setText(formatColor(settings.getCheckMetaColor()));
        priceInput.setText(formatColor(settings.getCheckPriceColor()));

        final List<View> layoutTiles = new ArrayList<>();
        final List<View> promoTiles = new ArrayList<>();
        final Runnable[] refresh = new Runnable[1];
        addChoiceTiles(view.findViewById(R.id.checkLayoutTiles), PriceScreen.LAYOUTS,
                new int[]{R.string.check_layout_center, R.string.check_layout_left,
                        R.string.check_layout_split, R.string.check_layout_price_first},
                layoutTiles, choice -> {
                    layout[0] = choice;
                    refresh[0].run();
                });
        addChoiceTiles(view.findViewById(R.id.checkPromoTiles), PriceScreen.PROMO_STYLES,
                new int[]{R.string.check_promo_classic, R.string.check_promo_sticker,
                        R.string.check_promo_ribbon, R.string.check_promo_poster,
                        R.string.check_promo_tag},
                promoTiles, choice -> {
                    promoStyle[0] = choice;
                    refresh[0].run();
                });

        // Redraw the previews and the tiles live as the choices, the hex fields
        // (or the colour pickers, which write into them) change, falling back to
        // the saved colour while a field holds an incomplete value. Each tile
        // shows its own choice with the other one as currently picked.
        refresh[0] = () -> {
            PriceScreen.Colors colors = new PriceScreen.Colors(
                    colorOr(bgInput, settings.getCheckBackgroundColor()),
                    colorOr(nameInput, settings.getCheckNameColor()),
                    colorOr(metaInput, settings.getCheckMetaColor()),
                    colorOr(priceInput, settings.getCheckPriceColor()));
            renderSample(regularPreview, layout[0], promoStyle[0], colors, false);
            renderSample(promoPreview, layout[0], promoStyle[0], colors, true);
            for (int i = 0; i < layoutTiles.size(); i++) {
                String choice = PriceScreen.LAYOUTS.get(i);
                View tile = layoutTiles.get(i);
                markChoiceTile(tile, choice.equals(layout[0]));
                renderSample(choiceThumb(tile), choice, promoStyle[0], colors, false);
            }
            for (int i = 0; i < promoTiles.size(); i++) {
                String choice = PriceScreen.PROMO_STYLES.get(i);
                View tile = promoTiles.get(i);
                markChoiceTile(tile, choice.equals(promoStyle[0]));
                renderSample(choiceThumb(tile), layout[0], choice, colors, true);
            }
        };
        final Runnable refreshPreview = () -> refresh[0].run();
        // The previews need their widths, which are known once the dialog is laid
        // out, and are redrawn whenever that width changes.
        view.post(refreshPreview);
        regularPreview.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                v.post(refreshPreview);
            }
        });

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshPreview.run();
            }
        };
        bgInput.addTextChangedListener(watcher);
        nameInput.addTextChangedListener(watcher);
        metaInput.addTextChangedListener(watcher);
        priceInput.addTextChangedListener(watcher);

        view.findViewById(R.id.pickCheckBgButton).setOnClickListener(v -> showColorPicker(bgInput));
        view.findViewById(R.id.pickCheckNameButton).setOnClickListener(v -> showColorPicker(nameInput));
        view.findViewById(R.id.pickCheckMetaButton).setOnClickListener(v -> showColorPicker(metaInput));
        view.findViewById(R.id.pickCheckPriceButton).setOnClickListener(v -> showColorPicker(priceInput));
        view.findViewById(R.id.saveCheckColorsButton).setOnClickListener(v ->
                saveCheckDisplay(layout[0], promoStyle[0], bgInput, nameInput, metaInput, priceInput));

        buildModuleDialog(R.string.module_pricecheck_title, view, () -> { });
    }

    private interface ChoiceListener {
        void onChoice(String choice);
    }

    /** One tile per choice: a small drawing of it above its name. */
    private void addChoiceTiles(LinearLayout row, List<String> choices, int[] labels,
                                List<View> tiles, ChoiceListener listener) {
        for (int i = 0; i < choices.size(); i++) {
            String choice = choices.get(i);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(5), dp(5), dp(5), dp(6));
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(getString(labels[i]));

            FrameLayout thumb = new FrameLayout(this);
            // Taps go to the tile, not the drawing inside it.
            thumb.setClickable(false);
            tile.addView(thumb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setMaxLines(1);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(4);
            tile.addView(label, labelParams);

            tile.setOnClickListener(v -> listener.onChoice(choice));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                params.setMarginStart(dp(8));
            }
            row.addView(tile, params);
            tiles.add(tile);
        }
    }

    private static FrameLayout choiceThumb(View tile) {
        return (FrameLayout) ((LinearLayout) tile).getChildAt(0);
    }

    private void markChoiceTile(View tile, boolean selected) {
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(10));
        frame.setColor(getColor(selected ? R.color.brand_tint : R.color.surface));
        frame.setStroke(dp(selected ? 2 : 1), getColor(selected ? R.color.brand : R.color.divider));
        tile.setBackground(frame);
        tile.setSelected(selected);
        TextView label = (TextView) ((LinearLayout) tile).getChildAt(1);
        label.setTextColor(getColor(selected ? R.color.brand_dark : R.color.ink_soft));
    }

    /** Draws the sample item into {@code frame} at 16:10, once the frame has a width. */
    private void renderSample(FrameLayout frame, String layout, String promoStyle,
                              PriceScreen.Colors colors, boolean withPromo) {
        int width = frame.getWidth();
        if (width <= 0) {
            return;
        }
        int height = Math.round(width * 10f / 16f);
        if (frame.getLayoutParams().height != height) {
            frame.getLayoutParams().height = height;
            frame.requestLayout();
        }
        PriceScreen.render(frame, width, height, layout, promoStyle, colors,
                samplePriceCheck(withPromo));
    }

    /** The preview item, with a 50% promo ending in nine days when {@code withPromo}. */
    private PriceScreen.Content samplePriceCheck(boolean withPromo) {
        long regular = 129900L;
        String name = getString(R.string.module_pricecheck_preview_name);
        String meta = getString(R.string.module_pricecheck_preview_meta);
        if (!withPromo) {
            return new PriceScreen.Content(name, meta, PriceFormatter.format(regular), null);
        }
        Calendar end = Calendar.getInstance();
        end.add(Calendar.DAY_OF_YEAR, 9);
        Promo promo = new Promo(0L, meta, Promo.SOURCE_DEVICE, Promo.LABEL_PROMO,
                Promo.Type.PERCENT, 5000L, 0, 0, "",
                new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(end.getTime()));
        Promo.Best best = new Promo.Best(promo, promo.promoPriceCents(regular), null);
        return new PriceScreen.Content(name, meta, PriceFormatter.format(best.priceCents),
                PriceScreen.Deal.from(this, best, regular));
    }

    private static int colorOr(EditText input, int fallback) {
        Integer parsed = parseColorValue(input.getText().toString());
        return parsed == null ? fallback : parsed;
    }

    private void saveCheckDisplay(String layout, String promoStyle, EditText bg, EditText name,
                                  EditText meta, EditText price) {
        Integer bgColor = parseColorValue(bg.getText().toString());
        Integer nameColor = parseColorValue(name.getText().toString());
        Integer metaColor = parseColorValue(meta.getText().toString());
        Integer priceColor = parseColorValue(price.getText().toString());
        if (bgColor == null || nameColor == null || metaColor == null || priceColor == null) {
            Toast.makeText(this, R.string.idle_marquee_color_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        settings.setCheckBackgroundColor(bgColor);
        settings.setCheckNameColor(nameColor);
        settings.setCheckMetaColor(metaColor);
        settings.setCheckPriceColor(priceColor);
        settings.setCheckLayout(layout);
        settings.setPromoStyle(promoStyle);
        bg.setText(formatColor(bgColor));
        name.setText(formatColor(nameColor));
        meta.setText(formatColor(metaColor));
        price.setText(formatColor(priceColor));
        Toast.makeText(this, R.string.check_colors_saved, Toast.LENGTH_SHORT).show();
    }

    // --- Settings intervals ----------------------------------------------

    private void pickAutoSyncTime() {
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            settings.setAutoSyncTime(hourOfDay, minute);
            refreshAutoSyncTimeButton();
            refreshAutoSyncHint();
        }, settings.getAutoSyncHour(), settings.getAutoSyncMinute(), true).show();
    }

    private void refreshAutoSyncTimeButton() {
        if (autoSyncTimeButton != null) {
            autoSyncTimeButton.setText(formatAutoSyncTime());
        }
    }

    private void refreshAutoSyncHint() {
        if (autoSyncHint == null) {
            return;
        }
        autoSyncHint.setText(getString(R.string.lan_auto_sync_desc, formatAutoSyncTime()));
    }

    private String formatAutoSyncTime() {
        Calendar value = Calendar.getInstance();
        value.set(Calendar.HOUR_OF_DAY, settings.getAutoSyncHour());
        value.set(Calendar.MINUTE, settings.getAutoSyncMinute());
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(value.getTime());
    }

    // --- Catalog ----------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/csv",
                "text/plain",
                "text/comma-separated-values"
        });
        startActivityForResult(intent, REQUEST_IMPORT_FILE);
    }

    @SuppressWarnings("deprecation")
    private void openImagePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, requestCode);
    }

    @SuppressWarnings("deprecation")
    private void openScheduleImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png"});
        startActivityForResult(intent, REQUEST_SCHEDULE_IMAGE_FILE);
    }

    @SuppressWarnings("deprecation")
    private void openVideoPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        startActivityForResult(intent, requestCode);
    }

    private void importFile(Uri uri) {
        String fileName = displayName(uri);
        if (importStatus != null) {
            importStatus.setText(getString(R.string.importing_file, fileName));
        }

        executor.execute(() -> {
            try {
                ContentResolver resolver = getContentResolver();
                try (InputStream input = resolver.openInputStream(uri)) {
                    if (input == null) {
                        throw new IllegalArgumentException("Unable to open selected file.");
                    }
                    ProductImportResult result = new ProductImporter()
                            .importIntoDatabase(input, database, fileName);
                    runOnUiThread(() -> showImportResult(result));
                }
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (importStatus != null) {
                        importStatus.setText(getString(R.string.import_failed, ex.getMessage()));
                    }
                });
            }
        });
    }

    private void showImportResult(ProductImportResult result) {
        updateCount();
        if (importStatus != null) {
            importStatus.setText(getString(R.string.import_result, result.sourceName, result.totalRows,
                    result.validRows, result.inserted, result.updated, result.skipped));
        }
    }

    private void confirmClearProducts() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_products_title)
                .setMessage(R.string.clear_products_message)
                .setNegativeButton(R.string.clear_products_cancel, null)
                .setPositiveButton(R.string.clear_products_confirm, (dialog, which) -> clearProducts())
                .show();
    }

    private void clearProducts() {
        database.clearProducts();
        updateCount();
        if (importStatus != null) {
            importStatus.setText(R.string.clear_products_done);
        }
        Toast.makeText(this, R.string.clear_products_done, Toast.LENGTH_SHORT).show();
    }

    private void saveManualProduct(EditText barcodeInput, EditText nameInput, EditText uomInput,
                                   EditText categoryInput, EditText priceInput, EditText itemCodeInput) {
        String barcode = Product.normalizeBarcode(barcodeInput.getText().toString());
        String name = Product.clean(nameInput.getText().toString());
        Long priceCents = ProductImporter.parsePriceCents(priceInput.getText().toString());
        if (barcode.isEmpty() || name.isEmpty() || priceCents == null || priceCents < 0) {
            Toast.makeText(this, R.string.manual_product_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        Product product = new Product(
                barcode,
                name,
                priceCents,
                uomInput.getText().toString(),
                categoryInput.getText().toString(),
                itemCodeInput.getText().toString(),
                System.currentTimeMillis());
        boolean updated = database.upsertProduct(product);
        updateCount();
        barcodeInput.setText("");
        nameInput.setText("");
        uomInput.setText("");
        categoryInput.setText("");
        priceInput.setText("");
        itemCodeInput.setText("");
        hideKeyboard();
        adminRoot.requestFocus();
        Toast.makeText(this, updated ? R.string.manual_product_updated : R.string.manual_product_added,
                Toast.LENGTH_SHORT).show();
    }

    // --- Idle media -------------------------------------------------------

    private void saveImageFromUri(Uri uri, File target, int successMessageRes) {
        executor.execute(() -> {
            boolean ok = copyToFile(uri, target);
            if (ok && target.equals(settings.videoFile())) {
                settings.setVideoPlaybackError("");
            }
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, successMessageRes, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
                }
                refreshIdlePreviews();
            });
        });
    }

    private void saveScheduledMedia(Uri uri, boolean video) {
        String mime = getContentResolver().getType(uri);
        if (video && mime != null && !"video/mp4".equalsIgnoreCase(mime)) {
            Toast.makeText(this, R.string.schedule_video_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        if (!video && mime != null && !"image/jpeg".equalsIgnoreCase(mime)
                && !"image/png".equalsIgnoreCase(mime)) {
            Toast.makeText(this, R.string.schedule_image_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = scheduledUploadName(uri, displayName(uri), video);
        File target = uniqueMediaFile(fileName);
        executor.execute(() -> {
            boolean ok = copyToFile(uri, target);
            if (!ok && target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            runOnUiThread(() -> {
                int message = IdleMediaItem.isScheduledMediaName(target.getName())
                        ? R.string.schedule_uploaded_active : R.string.schedule_uploaded;
                Toast.makeText(this, ok
                                ? getString(message, target.getName())
                                : getString(R.string.import_failed),
                        ok ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                populateScheduleList();
            });
        });
    }

    private String scheduledUploadName(Uri uri, String sourceName, boolean video) {
        String cleaned = sourceName == null ? "" : sourceName
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                .trim();
        String mime = getContentResolver().getType(uri);
        String expectedExt = video ? "mp4"
                : "image/png".equalsIgnoreCase(mime) ? "png" : "jpg";
        String lower = cleaned.toLowerCase(Locale.US);
        boolean expectedType = video ? lower.endsWith(".mp4")
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        if (!expectedType) {
            cleaned = cleaned.isEmpty() ? (video ? "scheduled-video" : "scheduled-image")
                    : cleaned;
            cleaned += "." + expectedExt;
        }
        if (cleaned.length() > 180) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 ? cleaned.substring(dot) : "." + expectedExt;
            cleaned = cleaned.substring(0, Math.max(1, 180 - ext.length())) + ext;
        }
        return cleaned;
    }

    private File uniqueMediaFile(String requestedName) {
        File dir = settings.mediaDir();
        File target = new File(dir, requestedName);
        if (!target.exists()) {
            return target;
        }
        int dot = requestedName.lastIndexOf('.');
        String base = dot > 0 ? requestedName.substring(0, dot) : requestedName;
        String ext = dot > 0 ? requestedName.substring(dot) : "";
        for (int suffix = 2; suffix < 10000; suffix++) {
            target = new File(dir, base + " (" + suffix + ")" + ext);
            if (!target.exists()) {
                return target;
            }
        }
        return new File(dir, System.currentTimeMillis() + ext);
    }

    private boolean copyToFile(Uri uri, File target) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                return false;
            }
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void removeImage(File target, int messageRes) {
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        if (target.equals(settings.videoFile())) {
            settings.setVideoPlaybackError("");
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
        refreshIdlePreviews();
    }

    private void refreshIdlePreviews() {
        if (logoPreview == null) {
            // Idle-display dialog is not on screen; nothing to refresh.
            return;
        }
        Bitmap logo = decode(settings.logoFile());
        if (logo != null) {
            logoPreview.setImageBitmap(logo);
            logoStatus.setText(getString(R.string.idle_logo_label));
        } else {
            logoPreview.setImageResource(R.drawable.default_logo);
            logoStatus.setText(R.string.idle_default_logo);
        }
        showLogoScale(logoScalePercent());

        Bitmap banner = decode(settings.bannerFile());
        if (banner != null) {
            bannerPreview.setImageBitmap(banner);
            bannerStatus.setText(getString(R.string.idle_banner_label));
        } else {
            bannerPreview.setImageDrawable(null);
            bannerStatus.setText(R.string.idle_no_banner);
        }

        if (settings.hasVideo()) {
            String playbackError = settings.getVideoPlaybackError();
            if (playbackError.isEmpty()) {
                int seconds = settings.getVideoSeconds();
                videoStatus.setText(seconds == 0
                        ? getString(R.string.idle_video_label_full)
                        : getString(R.string.idle_video_label_set, seconds));
            } else {
                videoStatus.setText(getString(R.string.idle_video_label_error, playbackError));
            }
            videoStatus.setVisibility(View.VISIBLE);
        } else {
            // No video configured: don't show a "no video" line, the display just
            // stays in static picture mode.
            videoStatus.setText("");
            videoStatus.setVisibility(View.GONE);
        }
    }

    /**
     * Wires the logo-size slider: dragging previews the new size and releasing
     * saves it, so the idle screen picks it up the next time it resumes.
     */
    private void setUpLogoScaleSlider() {
        if (logoScaleSlider == null) {
            return;
        }
        int ceiling = settings.getLogoScaleCeilingPercent();
        logoScaleSlider.setMax(ceiling - AppSettings.MIN_LOGO_SCALE_PERCENT);
        logoScaleSlider.setProgress(Math.min(settings.getLogoScalePercent(), ceiling)
                - AppSettings.MIN_LOGO_SCALE_PERCENT);
        logoScaleSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                showLogoScale(logoScalePercent());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int percent = logoScalePercent();
                settings.setLogoScalePercent(percent);
                // Settle the thumb on the snapped value it just saved.
                seekBar.setProgress(percent - AppSettings.MIN_LOGO_SCALE_PERCENT);
            }
        });
        showLogoScale(logoScalePercent());
    }

    /** The slider position as a percentage, snapped to whole steps. */
    private int logoScalePercent() {
        if (logoScaleSlider == null) {
            return settings.getLogoScalePercent();
        }
        int percent = AppSettings.MIN_LOGO_SCALE_PERCENT + logoScaleSlider.getProgress();
        return Math.min(settings.getLogoScaleCeilingPercent(),
                Math.round(percent / (float) LOGO_SCALE_STEP) * LOGO_SCALE_STEP);
    }

    /** Updates the percentage label and resizes the preview to match it. */
    private void showLogoScale(int percent) {
        if (logoScaleValue != null) {
            logoScaleValue.setText(percent >= settings.getLogoScaleCeilingPercent()
                    ? getString(R.string.idle_logo_size_value_max, percent)
                    : getString(R.string.idle_logo_size_value, percent));
        }
        if (logoPreview == null) {
            return;
        }
        float baseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                LOGO_PREVIEW_BASE_DP, getResources().getDisplayMetrics());
        // Bound the preview the same way the idle screen bounds the real logo, so
        // a wide logo previews as wide rather than boxed into a square.
        int bound = Math.max(1, Math.round(baseSize * percent / 100f));
        logoPreview.setMaxWidth(bound);
        logoPreview.setMaxHeight(bound);
        logoPreview.requestLayout();
    }

    private static Bitmap decode(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateIdleDisplaySettings() {
        if (storeNameInput == null || marqueeInput == null || imageSecondsInput == null
                || videoSecondsInput == null) {
            return;
        }
        Integer bgColor = parseColorValue(marqueeBgColorInput.getText().toString());
        Integer textColor = parseColorValue(marqueeTextColorInput.getText().toString());
        Integer promptColor = parseColorValue(scanPromptColorInput.getText().toString());
        if (bgColor == null || textColor == null || promptColor == null) {
            Toast.makeText(this, R.string.idle_marquee_color_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        int imageSeconds;
        int videoSeconds;
        try {
            imageSeconds = Integer.parseInt(imageSecondsInput.getText().toString().trim());
            videoSeconds = Integer.parseInt(videoSecondsInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            Toast.makeText(this, R.string.idle_display_times_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageSeconds < 1 || videoSeconds < 0) {
            Toast.makeText(this, R.string.idle_display_times_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        settings.setStoreName(storeNameInput.getText().toString());
        if (videoLogoToggle != null) {
            settings.setVideoLogoEnabled(videoLogoToggle.isChecked());
        }
        settings.setLogoScalePercent(logoScalePercent());
        settings.setImageSeconds(imageSeconds);
        settings.setVideoSeconds(videoSeconds);
        settings.setMarqueeText(marqueeInput.getText().toString());
        settings.setMarqueeBackgroundColor(bgColor);
        settings.setMarqueeTextColor(textColor);
        settings.setScanPromptColor(promptColor);

        storeNameInput.setText(settings.getStoreName());
        imageSecondsInput.setText(String.valueOf(settings.getImageSeconds()));
        videoSecondsInput.setText(String.valueOf(settings.getVideoSeconds()));
        marqueeBgColorInput.setText(formatColor(bgColor));
        marqueeTextColorInput.setText(formatColor(textColor));
        scanPromptColorInput.setText(formatColor(promptColor));
        refreshIdlePreviews();
        hideKeyboard();
        adminRoot.requestFocus();
        Toast.makeText(this, R.string.idle_display_updated, Toast.LENGTH_SHORT).show();
    }

    private void showColorPicker(EditText targetInput) {
        Integer initial = parseColorValue(targetInput.getText().toString());
        if (initial == null) {
            initial = Color.BLACK;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_color_picker, null);
        TextView hex = view.findViewById(R.id.colorPickerHex);
        View preview = view.findViewById(R.id.colorPickerPreview);
        SeekBar red = view.findViewById(R.id.colorPickerRed);
        SeekBar green = view.findViewById(R.id.colorPickerGreen);
        SeekBar blue = view.findViewById(R.id.colorPickerBlue);

        red.setProgress(Color.red(initial));
        green.setProgress(Color.green(initial));
        blue.setProgress(Color.blue(initial));
        updateColorPickerPreview(hex, preview, red, green, blue);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateColorPickerPreview(hex, preview, red, green, blue);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        red.setOnSeekBarChangeListener(listener);
        green.setOnSeekBarChangeListener(listener);
        blue.setOnSeekBarChangeListener(listener);

        new AlertDialog.Builder(this)
                .setTitle(R.string.color_picker_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> targetInput.setText(hex.getText()))
                .show();
    }

    private static void updateColorPickerPreview(TextView hex, View preview,
                                                 SeekBar red, SeekBar green, SeekBar blue) {
        int color = Color.rgb(red.getProgress(), green.getProgress(), blue.getProgress());
        preview.setBackgroundColor(color);
        hex.setText(formatColor(color));
    }

    private static Integer parseColorValue(String value) {
        String cleaned = Product.clean(value);
        if (!cleaned.startsWith("#")) {
            cleaned = "#" + cleaned;
        }
        if (!cleaned.matches("#[0-9a-fA-F]{6}")) {
            return null;
        }
        try {
            return Color.parseColor(cleaned);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String formatColor(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    // --- LAN sync ---------------------------------------------------------

    private void saveLanUrl() {
        if (lanUrlInput == null) {
            return;
        }
        settings.setLanUrl(lanUrlInput.getText().toString());
        lanUrlInput.setText(settings.getLanUrl());
        Toast.makeText(this, R.string.lan_url_saved, Toast.LENGTH_SHORT).show();
    }

    private void syncFromLan(LanSync.Scope scope) {
        if (lanUrlInput == null) {
            return;
        }
        String url = lanUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.lan_url_required, Toast.LENGTH_SHORT).show();
            return;
        }
        settings.setLanUrl(url);
        lanStatus.setText(getString(R.string.lan_syncing, url));
        setManualSyncRunning(true);

        executor.execute(() -> {
            try {
                LanSync.Result result = new LanSync(database, settings).run(url, scope, true);
                runOnUiThread(() -> {
                    setManualSyncRunning(false);
                    updateCount();
                    refreshIdlePreviews();
                    refreshLanStatus(summarizeSync(result));
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    setManualSyncRunning(false);
                    if (lanStatus != null) {
                        lanStatus.setText(getString(R.string.lan_sync_failed, ex.getMessage()));
                    }
                });
            }
        });
    }

    private void setManualSyncRunning(boolean running) {
        if (manualSyncButtons != null) {
            manualSyncButtons.setEnabled(!running);
            if (manualSyncButtons instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) manualSyncButtons;
                for (int i = 0; i < row.getChildCount(); i++) {
                    row.getChildAt(i).setEnabled(!running);
                }
            }
        }
        if (lanSyncProgress != null) {
            lanSyncProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        }
    }

    static String summarizeSync(LanSync.Result result) {
        StringBuilder sb = new StringBuilder();
        if (result.catalogSynced && result.catalog != null) {
            sb.append("catalog ").append(result.catalog.inserted + result.catalog.updated).append(" rows");
        }
        if (result.promosSynced >= 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("promos ").append(result.promosSynced);
        }
        if (result.designSynced) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("design");
        }
        if (result.logoSynced) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("logo");
        }
        if (result.bannerSynced) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("image");
        }
        if (result.videoSynced) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("video");
        }
        if (result.mediaSynced) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("scheduled media");
        }
        if (result.marqueeSynced) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("message");
        }
        return sb.length() == 0 ? "no changes" : sb.toString();
    }

    private void refreshLanStatus(String successSummary) {
        if (lanStatus == null) {
            return;
        }
        long lastSync = settings.getLastSync();
        String lastSyncText;
        if (lastSync <= 0) {
            lastSyncText = getString(R.string.lan_never_synced);
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            lastSyncText = getString(R.string.lan_last_sync, fmt.format(new Date(lastSync)));
        }
        if (successSummary != null) {
            lanStatus.setText(getString(R.string.lan_sync_done, successSummary) + "\n" + lastSyncText);
        } else {
            lanStatus.setText(lastSyncText);
        }
    }

    // --- Security ---------------------------------------------------------

    // Currently unused: PIN changes are disabled by the admin (see
    // showSecurityModule). Kept so the feature can be re-enabled by re-wiring the
    // Save button.
    private void savePin(EditText newPinInput) {
        String pin = Product.clean(newPinInput.getText().toString());
        if (pin.length() < 4) {
            Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        preferences.edit().putString(KEY_PIN, pin).apply();
        newPinInput.setText("");
        Toast.makeText(this, R.string.pin_updated, Toast.LENGTH_SHORT).show();
    }

    // --- Shared helpers ---------------------------------------------------

    private void updateCount() {
        String text = getString(R.string.products_count, database.countProducts());
        catalogSummary.setText(text);
        if (productCount != null) {
            productCount.setText(text);
        }
    }

    private void focusPinInput() {
        pinInput.requestFocus();
        pinInput.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 250L);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(pinInput.getWindowToken(), 0);
        }
    }

    private String displayName(Uri uri) {
        String fallback = uri.getLastPathSegment() == null ? "catalog" : uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (!Product.clean(name).isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
