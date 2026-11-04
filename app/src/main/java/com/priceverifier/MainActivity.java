package com.priceverifier;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final long CLEAR_DELAY_MS = 7000L;
    private static final long SCAN_IDLE_COMMIT_MS = 500L;
    private static final long BLINK_PERIOD_MS = 900L;
    private static final long SCAN_PROMPT_BLINK_MS = 1800L;
    private static final long SCAN_PROMPT_BOUNCE_MS = 850L;
    private static final long VIDEO_FADE_MS = 500L;
    private static final long VIDEO_PREPARE_TIMEOUT_MS = 15000L;
    private static final long APP_UPDATE_CHECK_INTERVAL_MS = 5L * 60L * 1000L;
    // How often the scanner checks in with the server: it keeps the portal's
    // Scanners tab showing this device as online, and picks up a "sync now"
    // request made there.
    private static final long REMOTE_SYNC_CHECK_INTERVAL_MS = RemoteSync.CHECK_INTERVAL_SECONDS * 1000L;
    // The scan prompt's glow and outline, kept in step with the configured colour.
    private static final float SCAN_PROMPT_GLOW_RADIUS = 12f;
    private static final int SCAN_PROMPT_GLOW_ALPHA = 0xCC000000;
    private static final float SCAN_PROMPT_STROKE_DP = 2f;
    // The logo column keeps the width the layout gives it, so the banner never
    // moves or shrinks. A logo scaled past the column spills over the gap and the
    // banner's left edge instead of widening anything (the layout turns off
    // clipping and elevates the column for exactly this). The height stays inside
    // a fraction of the column so the date and title keep their place.
    private static final float LOGO_MAX_HEIGHT_FRACTION = 0.45f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    // The check-ins get their own thread, so a long sync does not hold them up
    // and make the portal show this scanner as offline.
    private final ExecutorService checkInExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean autoSyncRunning = new AtomicBoolean();
    private final AtomicBoolean appUpdateRunning = new AtomicBoolean();
    private final AtomicBoolean remoteSyncChecking = new AtomicBoolean();
    private final AtomicBoolean remoteRequestRunning = new AtomicBoolean();
    private volatile boolean resumed;
    private final SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM", Locale.getDefault());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("d", Locale.getDefault());
    private final SimpleDateFormat lastSyncFormat =
            new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault());

    private ProductDatabase database;
    private AppSettings settings;
    private View idleView;
    private View displayArea;
    private FrameLayout productPanel;
    private TextView scanHerePrompt;
    // The bar under the price that blinks after a scan; PriceScreen draws it,
    // and some designs have none.
    private View blinkIndicator;
    private ImageView idleLogo;
    private View idleLogoFrame;
    private int baseLogoSizePx;
    private ImageView idleBanner;
    private View idleBannerPlaceholder;
    private TextView idleTitle;
    private TextView idleMonth;
    private TextView idleDay;
    private TextView idleLastSync;
    private TextView idleMarquee;
    private EditText scannerInput;
    private TextureView idleVideo;
    private TextView videoStoreName;
    private ImageView videoStoreLogo;
    private TextView notRegisteredBanner;
    private MediaPlayer videoPlayer;
    private Surface videoSurface;
    private boolean videoRequested;
    private File currentVideoFile;
    private boolean currentVideoPlayOnce;
    private long currentVideoTurnMs;
    private long currentVideoTurnDeadlineMs;
    private int pendingVideoSeekMs;
    private File interruptedVideoFile;
    private boolean interruptedVideoPlayOnce;
    private long interruptedVideoRemainingMs;
    private int interruptedVideoPositionMs;
    private boolean hasInterruptedVideoResume;
    private ValueAnimator blinkAnimator;
    private ValueAnimator scanPromptAnimator;
    private ValueAnimator scanPromptBounceAnimator;
    private Bitmap bannerBitmap;
    private List<IdleMediaItem> idlePlaylist = new ArrayList<>();
    // The idle rotation expanded into its exact play order: the default group
    // (banner image, idle video) first, then every scheduled item (a scheduled
    // image before each scheduled video), then it loops. Rebuilt by
    // buildIdleCycle() whenever the media changes; idleCycleIndex walks it.
    private List<IdleMediaItem> idleCycle = new ArrayList<>();
    private int idleCycleIndex = -1;
    private boolean appUpdatePromptShowing;
    private String lastPromptedUpdateId = "";

    private final Runnable idleCycleRunnable = this::advanceIdleCycle;
    private final Runnable appUpdateTick = new Runnable() {
        @Override
        public void run() {
            checkForAppUpdate();
            handler.postDelayed(this, APP_UPDATE_CHECK_INTERVAL_MS);
        }
    };
    private final Runnable remoteSyncTick = new Runnable() {
        @Override
        public void run() {
            checkRemoteSync();
            handler.postDelayed(this, REMOTE_SYNC_CHECK_INTERVAL_MS);
        }
    };
    private final Runnable videoPrepareTimeout = () -> {
        if (videoRequested) {
            settings.setVideoPlaybackError("Video could not start within 15 seconds. Use a 720p H.264 MP4.");
            stopIdleVideo();
            handler.postDelayed(idleCycleRunnable, imageDisplayMs());
        }
    };

    private final Runnable resetRunnable = this::showIdle;
    private final Runnable dateTick = new Runnable() {
        @Override
        public void run() {
            updateDate();
            updateLastSync();
            handler.postDelayed(this, msUntilNextMinute());
        }
    };
    private final Runnable autoSyncTick = new Runnable() {
        @Override
        public void run() {
            if (isDailyAutoSyncDue()) {
                runAutoSync();
            }
            handler.postDelayed(this, 30_000L);
        }
    };
    private final Runnable commitScannerInput = this::commitScan;
    private final BroadcastReceiver scannerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleScannerIntent(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        enterImmersive();

        database = new ProductDatabase(this);
        settings = new AppSettings(this);

        idleView = findViewById(R.id.idleView);
        displayArea = findViewById(R.id.displayArea);
        productPanel = findViewById(R.id.productPanel);
        scanHerePrompt = findViewById(R.id.scanHerePrompt);
        idleLogo = findViewById(R.id.idleLogo);
        idleLogoFrame = findViewById(R.id.idleLogoFrame);
        baseLogoSizePx = getResources().getDimensionPixelSize(R.dimen.idle_logo_size);
        // The height cap needs the column's measured height, which is not known on
        // the first pass; re-apply once it is (applyLogoScale is a no-op when the
        // bounds already match, so this settles instead of looping).
        idleLogoFrame.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                 oldL, oldT, oldR, oldB) -> applyLogoScale());
        idleBanner = findViewById(R.id.idleBanner);
        idleBannerPlaceholder = findViewById(R.id.idleBannerPlaceholder);
        idleTitle = findViewById(R.id.idleTitle);
        idleMonth = findViewById(R.id.idleMonth);
        idleDay = findViewById(R.id.idleDay);
        idleLastSync = findViewById(R.id.idleLastSync);
        idleMarquee = findViewById(R.id.idleMarquee);
        scannerInput = findViewById(R.id.scannerInput);
        // The hidden scanner field must keep focus to receive hardware scans, but
        // it must never raise the on-screen keyboard (e.g. on the Datalogic Smart
        // Portal). The XML attribute is not public, so disable it in code.
        scannerInput.setShowSoftInputOnFocus(false);
        idleVideo = findViewById(R.id.idleVideo);
        videoStoreName = findViewById(R.id.videoStoreName);
        videoStoreLogo = findViewById(R.id.videoStoreLogo);
        notRegisteredBanner = findViewById(R.id.notRegisteredBanner);
        // Unregistered devices show a persistent banner. It has the highest
        // elevation in the display area, so it stays on top of the idle view, the
        // product panel, and the playing video for as long as the app is open.
        notRegisteredBanner.setVisibility(
                DeviceAccess.isAuthorized(this) ? View.GONE : View.VISIBLE);
        idleVideo.setOpaque(false);
        idleVideo.setOnClickListener(v -> showStaticIdlePhase());

        // A TextureView is a normal view (unlike VideoView's SurfaceView), so it
        // draws over the white background and can be alpha-animated for a fade.
        idleVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (videoRequested) {
                    openVideoPlayer(surface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releaseVideoPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        focusScannerInput();
        scannerInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                handler.removeCallbacks(commitScannerInput);
                if (text.length() > 0) {
                    handler.postDelayed(commitScannerInput, SCAN_IDLE_COMMIT_MS);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        scannerInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_TAB));
            if (done) {
                commitScan();
                return true;
            }
            return false;
        });

        idleTitle.setOnLongClickListener(v -> {
            startActivity(new Intent(this, AdminActivity.class));
            return true;
        });

        idleBanner.addOnLayoutChangeListener((v, left, top, right, bottom,
                                              oldL, oldT, oldR, oldB) -> applyBannerMatrix());

        showIdle();
        handleScannerIntent(getIntent());
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onResume() {
        super.onResume();
        enterImmersive();
        applyIdleContent();
        if (notRegisteredBanner != null) {
            notRegisteredBanner.setVisibility(
                    DeviceAccess.isAuthorized(this) ? View.GONE : View.VISIBLE);
        }
        focusScannerInput();
        handler.removeCallbacks(dateTick);
        handler.post(dateTick);
        handler.removeCallbacks(autoSyncTick);
        handler.post(autoSyncTick);
        handler.removeCallbacks(appUpdateTick);
        handler.postDelayed(appUpdateTick, 5000L);
        resumed = true;
        handler.removeCallbacks(remoteSyncTick);
        handler.postDelayed(remoteSyncTick, 2000L);
        if (idleView != null && idleView.getVisibility() == View.VISIBLE) {
            startIdleCycle();
        }
        startScanPromptBlink();
        IntentFilter filter = new IntentFilter("com.priceverifier.SCAN");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scannerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scannerReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(dateTick);
        handler.removeCallbacks(autoSyncTick);
        handler.removeCallbacks(appUpdateTick);
        // The check-ins carry on while the Admin screen is open, so the portal
        // keeps showing this scanner as online; a sync request waits until the
        // home screen is back.
        resumed = false;
        handler.removeCallbacks(idleCycleRunnable);
        stopScanPromptBlink();
        stopIdleVideo();
        stopBlink();
        unregisterReceiver(scannerReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(remoteSyncTick);
        releaseVideoPlayer();
        syncExecutor.shutdownNow();
        checkInExecutor.shutdownNow();
        super.onDestroy();
    }

    private void runAutoSync() {
        final String url = settings.getLanUrl();
        if (url.isEmpty()) {
            return;
        }
        if (!autoSyncRunning.compareAndSet(false, true)) {
            return;
        }
        settings.setLastAutoSyncDay(dayKey(Calendar.getInstance()));
        syncExecutor.execute(() -> {
            try {
                LanSync.Result result = new LanSync(database, settings).run(url);
                handler.post(this::updateLastSync);
                if (result.anythingChanged()) {
                    handler.post(() -> {
                        if (idleView != null && idleView.getVisibility() == View.VISIBLE) {
                            applyIdleContent();
                            // Don't call startIdleCycle() here: it resets the
                            // playlist index to the front, so a frequently-firing
                            // auto-sync keeps replaying the banner/default video and
                            // starves later items (e.g. scheduled videos), which
                            // never get their turn. advanceIdleCycle() already
                            // reloads the playlist each tick and resets the index
                            // only when the playlist actually changes, so freshly
                            // synced media is picked up without losing our place.
                        }
                    });
                }
            } catch (Exception ignored) {
            } finally {
                autoSyncRunning.set(false);
            }
        });
    }

    /**
     * Checks in with the server, so the portal's Scanners tab shows this device
     * as online, and carries out a "sync now" request made there. Each request
     * is acted on once; the sync runs in the background like the daily one.
     */
    private void checkRemoteSync() {
        if (settings == null || !remoteSyncChecking.compareAndSet(false, true)) {
            return;
        }
        final String url = settings.getLanUrl();
        if (url.isEmpty()) {
            remoteSyncChecking.set(false);
            return;
        }
        checkInExecutor.execute(() -> {
            try {
                RemoteSync.Request request = RemoteSync.poll(this, url);
                if (request == null || request.id <= settings.getHandledSyncRequest()) {
                    return;
                }
                if (request.ageSeconds > RemoteSync.MAX_REQUEST_AGE_SECONDS) {
                    settings.setHandledSyncRequest(request.id);
                    return;
                }
                if (!resumed || !remoteRequestRunning.compareAndSet(false, true)) {
                    return;     // Admin is open, or the request is already being carried out
                }
                syncExecutor.execute(() -> {
                    try {
                        carryOutRemoteRequest(request, url);
                    } finally {
                        remoteRequestRunning.set(false);
                    }
                });
            } catch (RuntimeException ignored) {
                // The app is closing; nothing left to do.
            } finally {
                remoteSyncChecking.set(false);
            }
        });
    }

    /** Runs on the sync thread: does what the portal asked and reports back. */
    private void carryOutRemoteRequest(RemoteSync.Request request, String url) {
        if (!request.isSupported()) {
            settings.setHandledSyncRequest(request.id);
            RemoteSync.report(this, url, request.id, false, "This app version cannot do that; update the app.");
            return;
        }
        if (request.appUpdate) {
            // Checked again even when "Later" was chosen for this version before.
            AppUpdate.Info info = AppUpdate.check(this, url);
            settings.setHandledSyncRequest(request.id);
            if (info == null) {
                RemoteSync.report(this, url, request.id, true, "no newer app version on the server");
                return;
            }
            handler.post(() -> {
                if (!appUpdatePromptShowing && !isFinishing()) {
                    showAppUpdatePrompt(info);
                }
            });
            String version = info.version == null ? "" : info.version.trim();
            RemoteSync.report(this, url, request.id, true,
                    "update prompt shown" + (version.isEmpty() ? "" : " (" + version + ")"));
            return;
        }
        if (!autoSyncRunning.compareAndSet(false, true)) {
            return;     // the daily sync is running; the request is picked up at the next check
        }
        boolean ok = false;
        String message;
        try {
            // Like the daily sync, only files that changed on the server are downloaded.
            LanSync.Result result = new LanSync(database, settings).run(url, request.scope, false);
            ok = true;
            message = AdminActivity.summarizeSync(result);
            handler.post(this::updateLastSync);
            if (result.anythingChanged()) {
                handler.post(() -> {
                    if (idleView != null && idleView.getVisibility() == View.VISIBLE) {
                        applyIdleContent();
                    }
                });
            }
        } catch (Exception error) {
            message = error.getMessage() == null ? "Sync failed" : error.getMessage();
        } finally {
            autoSyncRunning.set(false);
        }
        // Marked done either way: a failing sync is not repeated at every
        // check; the portal shows the error and the request can be made again.
        settings.setHandledSyncRequest(request.id);
        RemoteSync.report(this, url, request.id, ok, message);
    }

    private boolean isDailyAutoSyncDue() {
        if (settings == null || !settings.isAutoSyncEnabled()
                || settings.getLanUrl().isEmpty()) {
            return false;
        }
        Calendar now = Calendar.getInstance();
        if (settings.getLastAutoSyncDay() == dayKey(now)) {
            return false;
        }
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int syncMinutes = settings.getAutoSyncHour() * 60 + settings.getAutoSyncMinute();
        return nowMinutes >= syncMinutes;
    }

    private void checkForAppUpdate() {
        if (settings == null || appUpdatePromptShowing
                || !appUpdateRunning.compareAndSet(false, true)) {
            return;
        }
        final String url = settings.getLanUrl();
        if (url.isEmpty()) {
            appUpdateRunning.set(false);
            return;
        }
        syncExecutor.execute(() -> {
            AppUpdate.Info info = AppUpdate.check(this, url);
            handler.post(() -> {
                appUpdateRunning.set(false);
                if (info != null
                        && !info.releaseId.equals(settings.getHandledAppUpdateId())
                        && !info.releaseId.equals(lastPromptedUpdateId)
                        && !isFinishing()) {
                    showAppUpdatePrompt(info);
                }
            });
        });
    }

    private void showAppUpdatePrompt(AppUpdate.Info info) {
        appUpdatePromptShowing = true;
        lastPromptedUpdateId = info.releaseId;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(formatUpdateMessage(info))
                .setNegativeButton(R.string.update_later, (d, w) ->
                        markAppUpdateHandled(info))
                .setPositiveButton(R.string.update_download, null)
                .create();
        dialog.setOnDismissListener(d -> appUpdatePromptShowing = false);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    downloadAppUpdate(info);
                }));
        dialog.show();
    }

    private String formatUpdateMessage(AppUpdate.Info info) {
        String version = info.version == null || info.version.trim().isEmpty()
                ? getString(R.string.update_version_unknown) : info.version.trim();
        String changes = info.changes == null || info.changes.trim().isEmpty()
                ? getString(R.string.update_changes_empty) : info.changes.trim();
        return getString(R.string.update_available_message,
                version, formatBytes(info.size), changes);
    }

    private void downloadAppUpdate(AppUpdate.Info info) {
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_LONG).show();
        syncExecutor.execute(() -> {
            try {
                AppUpdate.download(this, info);
                markAppUpdateHandled(info);
                handler.post(() -> {
                    Toast.makeText(this, R.string.update_downloaded, Toast.LENGTH_SHORT).show();
                    openDownloadedUpdate();
                });
            } catch (Exception ignored) {
                handler.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.update_failed_title)
                        .setMessage(R.string.update_failed_message)
                        .setPositiveButton(R.string.close, null)
                        .show());
            }
        });
    }

    private void markAppUpdateHandled(AppUpdate.Info info) {
        settings.setHandledAppUpdateModified(info.modifiedSeconds);
        settings.setHandledAppUpdateId(info.releaseId);
    }

    private void openDownloadedUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            showInstallPermissionPrompt();
            return;
        }
        Uri apk = UpdateApkProvider.updateUri();
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(apk, UpdateApkProvider.APK_MIME);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Intent fallback = new Intent(Intent.ACTION_VIEW);
            fallback.setDataAndType(apk, UpdateApkProvider.APK_MIME);
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(fallback);
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, R.string.update_installer_missing, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showInstallPermissionPrompt() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException ignored) {
                        Toast.makeText(this, R.string.update_permission_missing,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static int dayKey(Calendar calendar) {
        return calendar.get(Calendar.YEAR) * 10000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleScannerIntent(intent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Only intercept the scan terminator. Every other character (digits from
        // either raw key events or an IME committing text) is allowed to flow into
        // the focused scannerInput, so a single accumulator holds the whole barcode
        // and no character can be split between two capture paths.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_TAB) {
                commitScan();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void commitScan() {
        if (scannerInput == null) {
            return;
        }
        handler.removeCallbacks(commitScannerInput);
        // Flush any text the IME is still composing so the final digit is included
        // before we read the field.
        scannerInput.clearComposingText();
        String barcode = scannerInput.getText().toString();
        scannerInput.setText("");
        handleScan(barcode);
    }

    private void handleScannerIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String barcode = firstNonEmpty(
                intent.getStringExtra("barcode"),
                intent.getStringExtra("data"),
                intent.getStringExtra("SCAN_BARCODE1"),
                intent.getStringExtra("com.symbol.datawedge.data_string"));
        if (!barcode.isEmpty()) {
            handleScan(barcode);
        }
    }

    private void handleScan(String rawBarcode) {
        String barcode = Product.normalizeBarcode(rawBarcode);
        if (barcode.isEmpty()) {
            focusScannerInput();
            return;
        }

        handler.removeCallbacks(resetRunnable);
        Product product = database.findByBarcode(barcode);
        if (product == null) {
            showNotFound(barcode);
        } else {
            showProduct(product);
        }
        handler.postDelayed(resetRunnable, CLEAR_DELAY_MS);
        focusScannerInput();
    }

    /**
     * Shows the best active promo: its price becomes the big number with the
     * regular price struck through, drawn in the chosen promo style. A Buy X
     * Take Y keeps the regular price, alongside a price promo when both apply.
     */
    private void showProduct(Product product) {
        showProductPanel();
        Promo.Best best = Promo.choose(database.promosFor(product.barcode),
                product.priceCents, Promo.today());
        String price = PriceFormatter.format(
                best.priced != null ? best.priceCents : product.priceCents);
        renderPriceCheck(new PriceScreen.Content(formatNameWithUom(product), product.barcode,
                price, PriceScreen.Deal.from(this, best, product.priceCents)));
        startBlink();
    }

    private void renderPriceCheck(PriceScreen.Content content) {
        int width = displayArea.getWidth();
        int height = displayArea.getHeight();
        if (width <= 0 || height <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
            height = getResources().getDisplayMetrics().heightPixels;
        }
        blinkIndicator = PriceScreen.render(productPanel, width, height, settings.getCheckLayout(),
                settings.getPromoStyle(), PriceScreen.Colors.from(settings), content);
    }

    private static String formatNameWithUom(Product product) {
        if (product.uom.isEmpty()) {
            return product.name;
        }
        return product.name + " (" + product.uom + ")";
    }

    private void showNotFound(String barcode) {
        showProductPanel();
        renderPriceCheck(new PriceScreen.Content(getString(R.string.no_product_record), barcode,
                getString(R.string.ask_for_assistance), null));
        startBlink();
    }

    private void showIdle() {
        handler.removeCallbacks(resetRunnable);
        stopBlink();
        productPanel.setVisibility(View.GONE);
        idleView.setVisibility(View.VISIBLE);
        if (scanHerePrompt != null) {
            scanHerePrompt.setVisibility(View.VISIBLE);
        }
        applyIdleContent();
        handler.removeCallbacks(commitScannerInput);
        if (scannerInput != null) {
            scannerInput.setText("");
        }
        focusScannerInput();
        if (!resumeInterruptedVideo()) {
            startIdleCycle();
        }
    }

    private void showProductPanel() {
        rememberInterruptedVideo();
        handler.removeCallbacks(idleCycleRunnable);
        stopIdleVideo();
        idleView.setVisibility(View.GONE);
        productPanel.setVisibility(View.VISIBLE);
        // The "SCAN HERE" prompt belongs to the idle screen only; hide it while a
        // scanned price is on display.
        if (scanHerePrompt != null) {
            scanHerePrompt.setVisibility(View.GONE);
        }
    }

    private void startIdleCycle() {
        handler.removeCallbacks(idleCycleRunnable);
        stopIdleVideo();
        idlePlaylist = IdleMediaItem.load(settings);
        idleCycle = buildIdleCycle(idlePlaylist);
        idleCycleIndex = -1;
        advanceIdleCycle();
    }

    private void advanceIdleCycle() {
        if (idleView == null || idleView.getVisibility() != View.VISIBLE) {
            return;
        }
        // A tap parked a clip mid-play: the static frame has now had its turn, so
        // pick that clip up where it left off instead of walking on to the next
        // item. resumeInterruptedVideo() clears the pending resume either way, so
        // a clip whose file has since gone falls through to the normal advance.
        if (hasInterruptedVideoResume && resumeInterruptedVideo()) {
            return;
        }
        List<IdleMediaItem> freshPlaylist = IdleMediaItem.load(settings);
        if (playlistChanged(freshPlaylist)) {
            idlePlaylist = freshPlaylist;
            idleCycle = buildIdleCycle(freshPlaylist);
            idleCycleIndex = -1;
        }
        if (idleCycle.isEmpty()) {
            showNoIdleMedia();
            handler.postDelayed(idleCycleRunnable, imageDisplayMs());
            return;
        }
        idleCycleIndex = (idleCycleIndex + 1) % idleCycle.size();
        IdleMediaItem item = idleCycle.get(idleCycleIndex);
        if (item.type == IdleMediaItem.Type.VIDEO) {
            boolean started = startIdleVideo(item);
            if (!started) {
                // Missing/empty clip: skip to the next item.
                handler.postDelayed(idleCycleRunnable, imageDisplayMs());
            } else if (!item.playOnce) {
                // Fixed-duration (or default) clip: advance after its turn length.
                // A play-once clip instead advances when it finishes playing
                // (see the completion handling in openVideoPlayer).
                scheduleVideoAdvance(videoTurnMs(item));
            }
        } else {
            showIdleImage(item.file);
            handler.postDelayed(idleCycleRunnable, imageTurnMs(item));
        }
    }

    /**
     * Expands the loaded media into the exact idle play order:
     *   default image, default video,
     *   then for every scheduled video a scheduled image followed by that video,
     *   cycling through all scheduled videos before the loop repeats.
     * Within a group an image precedes each video; when a group has videos but no
     * image of its own, the default image is reused so a static frame still
     * separates the clips.
     */
    private List<IdleMediaItem> buildIdleCycle(List<IdleMediaItem> playlist) {
        List<String> order = IdleMediaItem.parseOrder(settings.getEffectiveMediaOrder());
        if (!order.isEmpty()) {
            List<IdleMediaItem> ordered = buildOrderedCycle(playlist, order);
            if (!ordered.isEmpty()) {
                return ordered;
            }
        }
        List<IdleMediaItem> defImages = new ArrayList<>();
        List<IdleMediaItem> defVideos = new ArrayList<>();
        List<IdleMediaItem> schImages = new ArrayList<>();
        List<IdleMediaItem> schVideos = new ArrayList<>();
        for (IdleMediaItem item : playlist) {
            boolean video = item.type == IdleMediaItem.Type.VIDEO;
            if (item.defaultItem) {
                (video ? defVideos : defImages).add(item);
            } else {
                (video ? schVideos : schImages).add(item);
            }
        }
        List<IdleMediaItem> cycle = new ArrayList<>();
        appendGroup(cycle, defImages, defVideos);
        // Scheduled videos use a scheduled image as their static frame, falling
        // back to the default banner when no scheduled image exists.
        appendGroup(cycle, schImages.isEmpty() ? defImages : schImages, schVideos);
        return cycle;
    }

    /**
     * Appends one group to the cycle, placing an image before each video and
     * reusing images cyclically when there are fewer images than videos.
     */
    private void appendGroup(List<IdleMediaItem> out,
                             List<IdleMediaItem> images, List<IdleMediaItem> videos) {
        if (videos.isEmpty()) {
            out.addAll(images);
            return;
        }
        int turns = Math.max(images.size(), videos.size());
        for (int i = 0; i < turns; i++) {
            if (!images.isEmpty()) {
                out.add(images.get(i % images.size()));
            }
            out.add(videos.get(i % videos.size()));
        }
    }

    /**
     * Builds the cycle from an explicit playback order (set on the server portal
     * or overridden on this device). Items are played literally in the given
     * order; anything present but not named in the order is appended afterward
     * so newly added media still shows until it is arranged.
     */
    private List<IdleMediaItem> buildOrderedCycle(List<IdleMediaItem> playlist, List<String> order) {
        Map<String, IdleMediaItem> byKey = new LinkedHashMap<>();
        for (IdleMediaItem item : playlist) {
            byKey.put(item.orderKey(), item);
        }
        List<IdleMediaItem> out = new ArrayList<>();
        for (String token : order) {
            IdleMediaItem item = byKey.remove(token);
            if (item != null) {
                out.add(item);
            }
        }
        // Present but not yet ordered: keep them in the loop at the end.
        out.addAll(byKey.values());
        return out;
    }

    /**
     * Tapping a playing clip parks it: the static frame it is paired with takes
     * over for one image turn, then the same clip picks up from the exact
     * position that was on screen when the tap landed, with only the time left
     * in its turn. The position is kept (see rememberInterruptedVideo) until the
     * clip finishes, so a scan in between still returns to the tapped point.
     */
    private void showStaticIdlePhase() {
        rememberInterruptedVideo();
        File tappedVideo = interruptedVideoFile;
        handler.removeCallbacks(idleCycleRunnable);
        stopIdleVideo();
        if (idleView != null && idleView.getVisibility() == View.VISIBLE) {
            showStaticFrameFor(tappedVideo);
            handler.postDelayed(idleCycleRunnable, imageDisplayMs());
        }
    }

    /**
     * Shows the static frame that belongs to the given clip: the nearest image
     * before it in the cycle, wrapping around so the last image still pairs with
     * a clip at the head of the loop. Falls back to the first image when the clip
     * is no longer part of the cycle.
     */
    private void showStaticFrameFor(File videoFile) {
        idlePlaylist = IdleMediaItem.load(settings);
        idleCycle = buildIdleCycle(idlePlaylist);
        int videoIndex = -1;
        if (videoFile != null) {
            for (int i = 0; i < idleCycle.size(); i++) {
                if (idleCycle.get(i).file.equals(videoFile)) {
                    videoIndex = i;
                    break;
                }
            }
        }
        if (videoIndex >= 0) {
            for (int step = 1; step <= idleCycle.size(); step++) {
                int i = (videoIndex - step + idleCycle.size()) % idleCycle.size();
                IdleMediaItem item = idleCycle.get(i);
                if (item.type == IdleMediaItem.Type.IMAGE) {
                    idleCycleIndex = i;
                    showIdleImage(item.file);
                    return;
                }
            }
        }
        showFirstImageOrPlaceholder();
    }

    private boolean playlistChanged(List<IdleMediaItem> freshPlaylist) {
        if (freshPlaylist.size() != idlePlaylist.size()) {
            return true;
        }
        for (int i = 0; i < freshPlaylist.size(); i++) {
            if (!freshPlaylist.get(i).file.equals(idlePlaylist.get(i).file)) {
                return true;
            }
        }
        return false;
    }

    private void showFirstImageOrPlaceholder() {
        idlePlaylist = IdleMediaItem.load(settings);
        idleCycle = buildIdleCycle(idlePlaylist);
        for (int i = 0; i < idleCycle.size(); i++) {
            IdleMediaItem item = idleCycle.get(i);
            if (item.type == IdleMediaItem.Type.IMAGE) {
                idleCycleIndex = i;
                showIdleImage(item.file);
                return;
            }
        }
        showNoIdleMedia();
    }

    private void showIdleImage(File file) {
        stopIdleVideo();
        Bitmap image = decode(file);
        bannerBitmap = image;
        if (image != null) {
            idleBanner.setImageBitmap(image);
            idleBanner.setVisibility(View.VISIBLE);
            idleBannerPlaceholder.setVisibility(View.GONE);
            applyBannerMatrix();
        } else {
            showNoIdleMedia();
        }
    }

    private void showNoIdleMedia() {
        stopIdleVideo();
        bannerBitmap = null;
        if (idleBanner != null) {
            idleBanner.setImageDrawable(null);
            idleBanner.setVisibility(View.GONE);
        }
        if (idleBannerPlaceholder != null) {
            idleBannerPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private long imageDisplayMs() {
        return settings.getImageSeconds() * 1000L;
    }

    private long videoDisplayMs() {
        return settings.getVideoSeconds() * 1000L;
    }

    private long imageTurnMs(IdleMediaItem item) {
        return item.displaySeconds > 0 ? item.displaySeconds * 1000L : imageDisplayMs();
    }

    private long videoTurnMs(IdleMediaItem item) {
        return item.displaySeconds > 0 ? item.displaySeconds * 1000L : videoDisplayMs();
    }

    private void scheduleVideoAdvance(long delayMs) {
        long safeDelay = Math.max(1L, delayMs);
        handler.removeCallbacks(idleCycleRunnable);
        currentVideoTurnDeadlineMs = SystemClock.elapsedRealtime() + safeDelay;
        handler.postDelayed(idleCycleRunnable, safeDelay);
    }

    private void rememberInterruptedVideo() {
        if (!videoRequested || currentVideoFile == null) {
            return;
        }
        interruptedVideoFile = currentVideoFile;
        interruptedVideoPlayOnce = currentVideoPlayOnce;
        interruptedVideoPositionMs = 0;
        if (videoPlayer != null) {
            try {
                interruptedVideoPositionMs = Math.max(0, videoPlayer.getCurrentPosition());
            } catch (RuntimeException ignored) {
            }
        }
        if (currentVideoPlayOnce) {
            interruptedVideoRemainingMs = 0L;
        } else {
            interruptedVideoRemainingMs = currentVideoTurnDeadlineMs > 0L
                    ? Math.max(1L, currentVideoTurnDeadlineMs - SystemClock.elapsedRealtime())
                    : Math.max(1L, currentVideoTurnMs);
        }
        hasInterruptedVideoResume = true;
    }

    private boolean resumeInterruptedVideo() {
        if (!hasInterruptedVideoResume) {
            return false;
        }
        File file = interruptedVideoFile;
        boolean playOnce = interruptedVideoPlayOnce;
        long remainingMs = interruptedVideoRemainingMs;
        int positionMs = interruptedVideoPositionMs;
        clearInterruptedVideoResume();

        idlePlaylist = IdleMediaItem.load(settings);
        idleCycle = buildIdleCycle(idlePlaylist);
        idleCycleIndex = -1;
        for (int i = 0; i < idleCycle.size(); i++) {
            if (idleCycle.get(i).file.equals(file)) {
                idleCycleIndex = i;
                break;
            }
        }
        return startIdleVideo(file, playOnce,
                playOnce ? Math.max(1L, videoDisplayMs()) : Math.max(1L, remainingMs),
                positionMs);
    }

    private void clearInterruptedVideoResume() {
        hasInterruptedVideoResume = false;
        interruptedVideoFile = null;
        interruptedVideoPlayOnce = false;
        interruptedVideoRemainingMs = 0L;
        interruptedVideoPositionMs = 0;
    }

    private boolean startIdleVideo(IdleMediaItem item) {
        return item != null && startIdleVideo(
                item.file, item.playOnce, videoTurnMs(item), 0);
    }

    private boolean startIdleVideo(File videoFile, boolean playOnce, long turnMs, int seekPositionMs) {
        if (idleVideo == null || videoFile == null || !videoFile.exists() || videoFile.length() == 0) {
            return false;
        }
        // A video is already on screen when we're swapping straight from one clip
        // to the next. In that case keep the TextureView fully opaque so the last
        // frame of the outgoing clip stays put while the next one prepares, instead
        // of dropping to alpha 0 and flashing the static idle screen between videos.
        boolean videoToVideo = videoRequested && idleVideo.getVisibility() == View.VISIBLE
                && idleVideo.getAlpha() > 0f;
        currentVideoFile = videoFile;
        currentVideoPlayOnce = playOnce;
        currentVideoTurnMs = Math.max(1L, turnMs);
        pendingVideoSeekMs = Math.max(0, seekPositionMs);
        videoRequested = true;
        showVideoStoreName();
        handler.removeCallbacks(videoPrepareTimeout);
        handler.postDelayed(videoPrepareTimeout, VIDEO_PREPARE_TIMEOUT_MS);
        idleVideo.animate().cancel();
        idleVideo.setVisibility(View.VISIBLE);
        if (videoToVideo) {
            // Hold the previous frame (no fade) until the next clip renders, so the
            // hand-off reads as a clean cut rather than a flash through the wallpaper.
            idleVideo.setAlpha(1f);
        } else {
            // Coming from a static image / idle screen: fade the video in. A view
            // kept at alpha 0 is not drawn, which would stop the TextureView from
            // ever consuming a frame; ramping alpha up right away keeps it drawing
            // while the clip prepares. The TextureView is transparent until its
            // first frame, so the static screen shows through during the fade
            // instead of a black flash.
            idleVideo.setAlpha(0f);
            idleVideo.animate().alpha(1f).setDuration(VIDEO_FADE_MS).start();
        }
        // If the surface is already live, open immediately; otherwise the
        // SurfaceTextureListener will open it once the surface is created.
        if (idleVideo.isAvailable()) {
            openVideoPlayer(idleVideo.getSurfaceTexture());
        }
        return true;
    }

    private void openVideoPlayer(SurfaceTexture texture) {
        releaseVideoPlayer();
        try {
            File videoFile = currentVideoFile != null ? currentVideoFile : settings.videoFile();
            videoSurface = new Surface(texture);
            videoPlayer = new MediaPlayer();
            videoPlayer.setSurface(videoSurface);
            videoPlayer.setDataSource(videoFile.getAbsolutePath());
            videoPlayer.setLooping(!currentVideoPlayOnce);
            videoPlayer.setOnCompletionListener(mp -> {
                if (!currentVideoPlayOnce || mp != videoPlayer) {
                    return;
                }
                handler.removeCallbacks(idleCycleRunnable);
                handler.post(idleCycleRunnable);
            });
            videoPlayer.setOnPreparedListener(mp -> {
                if (!videoRequested || mp != videoPlayer) {
                    return;
                }
                handler.removeCallbacks(videoPrepareTimeout);
                settings.setVideoPlaybackError("");
                try {
                    if (pendingVideoSeekMs > 0) {
                        mp.seekTo(pendingVideoSeekMs);
                        pendingVideoSeekMs = 0;
                    }
                    mp.start();
                    handler.removeCallbacks(idleCycleRunnable);
                    if (!currentVideoPlayOnce) {
                        scheduleVideoAdvance(currentVideoTurnMs);
                    }
                } catch (RuntimeException ex) {
                    settings.setVideoPlaybackError("Playback could not start. Use a 720p H.264 MP4.");
                    stopIdleVideo();
                    handler.postDelayed(idleCycleRunnable, imageDisplayMs());
                }
            });
            videoPlayer.setOnErrorListener((mp, what, extra) -> {
                settings.setVideoPlaybackError("Playback error " + what + "/" + extra
                        + ". Use a 720p H.264 MP4.");
                stopIdleVideo();
                handler.postDelayed(idleCycleRunnable, imageDisplayMs());
                return true;
            });
            videoPlayer.prepareAsync();
        } catch (Exception ex) {
            settings.setVideoPlaybackError("Playback failed: " + ex.getClass().getSimpleName()
                    + ". Use a 720p H.264 MP4.");
            stopIdleVideo();
            handler.postDelayed(idleCycleRunnable, imageDisplayMs());
        }
    }

    private void stopIdleVideo() {
        videoRequested = false;
        currentVideoFile = null;
        currentVideoPlayOnce = false;
        currentVideoTurnMs = 0L;
        currentVideoTurnDeadlineMs = 0L;
        pendingVideoSeekMs = 0;
        handler.removeCallbacks(videoPrepareTimeout);
        releaseVideoPlayer();
        if (idleVideo != null) {
            idleVideo.animate().cancel();
            idleVideo.setAlpha(0f);
            idleVideo.setVisibility(View.GONE);
        }
        if (videoStoreName != null) {
            videoStoreName.setVisibility(View.GONE);
        }
        if (videoStoreLogo != null) {
            videoStoreLogo.setVisibility(View.GONE);
        }
    }

    /**
     * Shows the overlay that sits on top of a playing idle video: either the store
     * name or, when the admin picked that option, the store logo.
     */
    private void showVideoStoreName() {
        if (videoStoreName == null) {
            return;
        }
        if (videoStoreLogo != null && settings.isVideoLogoEnabled()) {
            Bitmap logo = decode(settings.logoFile());
            if (logo != null) {
                videoStoreLogo.setImageBitmap(logo);
            } else {
                videoStoreLogo.setImageResource(R.drawable.default_logo);
            }
            videoStoreLogo.setVisibility(View.VISIBLE);
            videoStoreName.setVisibility(View.GONE);
            return;
        }
        if (videoStoreLogo != null) {
            videoStoreLogo.setVisibility(View.GONE);
        }
        String name = settings.getStoreName();
        if (name.isEmpty()) {
            videoStoreName.setVisibility(View.GONE);
        } else {
            videoStoreName.setText(name);
            videoStoreName.setVisibility(View.VISIBLE);
        }
    }

    private void releaseVideoPlayer() {
        if (videoPlayer != null) {
            try {
                videoPlayer.setOnPreparedListener(null);
                videoPlayer.setOnErrorListener(null);
                videoPlayer.setOnCompletionListener(null);
                videoPlayer.reset();
                videoPlayer.release();
            } catch (Exception ignored) {
            }
            videoPlayer = null;
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
    }

    private void applyIdleContent() {
        if (idleView == null) {
            return;
        }
        updateDate();
        updateLastSync();
        // No scrolling message set (here or on the server): hide the bar rather
        // than scroll a placeholder.
        String marquee = settings.getMarqueeText();
        idleMarquee.setVisibility(marquee.isEmpty() ? View.GONE : View.VISIBLE);
        idleMarquee.setText(marquee);
        idleMarquee.setBackgroundColor(settings.getMarqueeBackgroundColor());
        idleMarquee.setTextColor(settings.getMarqueeTextColor());

        applyScanPromptColor();

        Bitmap logo = decode(settings.logoFile());
        if (logo != null) {
            idleLogo.setImageBitmap(logo);
            idleLogo.setVisibility(View.VISIBLE);
        } else {
            idleLogo.setImageResource(R.drawable.default_logo);
            idleLogo.setVisibility(View.VISIBLE);
        }
        applyLogoScale();

        Bitmap banner = decode(settings.bannerFile());
        bannerBitmap = banner;
        if (banner != null) {
            idleBanner.setImageBitmap(banner);
            idleBanner.setVisibility(View.VISIBLE);
            idleBannerPlaceholder.setVisibility(View.GONE);
            applyBannerMatrix();
        } else {
            idleBanner.setImageDrawable(null);
            idleBanner.setVisibility(View.GONE);
            idleBannerPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Resizes the logo frame to the admin-configured percentage of the size the
     * layout gives it. Resizing the frame rather than scaling the ImageView keeps
     * the date/marquee column laid out around the new size instead of overlapping
     * or clipping it.
     */
    private void applyLogoScale() {
        if (idleLogo == null || baseLogoSizePx <= 0) {
            return;
        }
        Drawable art = idleLogo.getDrawable();
        if (art == null || art.getIntrinsicWidth() <= 0 || art.getIntrinsicHeight() <= 0) {
            return;
        }
        // Size the logo explicitly rather than through maxWidth/adjustViewBounds: a
        // wrap_content child is measured against its parent's width, so the column
        // would cap it however large the bounds allowed and the logo would stop
        // growing. An exact layout size is measured as given, and the layout turns
        // off clipping so anything wider than the column still draws.
        float aspect = art.getIntrinsicWidth() / (float) art.getIntrinsicHeight();
        int size = Math.max(1, Math.round(baseLogoSizePx * settings.getLogoScalePercent() / 100f));
        int width = size;
        int height = Math.max(1, Math.round(size / aspect));
        View column = idleLogoFrame == null ? null : (View) idleLogoFrame.getParent();
        if (column != null && column.getWidth() > 0) {
            View row = column.getParent() instanceof View ? (View) column.getParent() : null;
            int rowWidth = row == null ? 0 : row.getWidth();
            if (rowWidth > 0) {
                // The screen, not the column, is the real limit; record it so the
                // admin slider never offers a size that would run off the display.
                settings.setLogoScaleCeilingPercent(
                        Math.round(rowWidth * 100f / baseLogoSizePx));
                if (width > rowWidth) {
                    width = rowWidth;
                    height = Math.max(1, Math.round(width / aspect));
                }
            }
            // Hold the height back so the date and title keep their place; a tall
            // logo gives up width to stay in proportion.
            int heightLimit = Math.round(column.getHeight() * LOGO_MAX_HEIGHT_FRACTION);
            if (heightLimit > 0 && height > heightLimit) {
                height = heightLimit;
                width = Math.max(1, Math.round(height * aspect));
            }
        }
        ViewGroup.LayoutParams params = idleLogo.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            idleLogo.setLayoutParams(params);
        }
        idleLogo.setTranslationX(column == null ? 0f : logoSpillOffset(column, width));
    }

    /**
     * Repaints the floating "SCAN HERE" prompt in the configured colour. The text,
     * its glow, the badge outline and the arrows either side all follow the one
     * colour so the prompt stays a single accent.
     */
    private void applyScanPromptColor() {
        if (scanHerePrompt == null) {
            return;
        }
        int color = settings.getScanPromptColor();
        scanHerePrompt.setTextColor(color);
        scanHerePrompt.setShadowLayer(SCAN_PROMPT_GLOW_RADIUS, 0f, 0f,
                (color & 0x00FFFFFF) | SCAN_PROMPT_GLOW_ALPHA);
        scanHerePrompt.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        Drawable badge = scanHerePrompt.getBackground();
        if (badge instanceof GradientDrawable) {
            int stroke = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    SCAN_PROMPT_STROKE_DP, getResources().getDisplayMetrics()));
            // mutate() so the recolour stays with this view and not the shared
            // constant state the shape drawable was inflated from.
            ((GradientDrawable) badge.mutate()).setStroke(stroke, color);
        }
    }

    /**
     * How far right an oversized logo has to be nudged to keep its left edge on
     * screen. The logo is centred in its column, so half of anything wider than the
     * column hangs off the left; only the row padding is available to absorb it.
     */
    private float logoSpillOffset(View column, int size) {
        float spill = (size - column.getWidth()) / 2f;
        float roomLeft = column.getLeft();
        return spill > roomLeft ? spill - roomLeft : 0f;
    }

    private void applyBannerMatrix() {
        if (idleBanner == null || bannerBitmap == null) {
            return;
        }
        int vw = idleBanner.getWidth();
        int vh = idleBanner.getHeight();
        int bw = bannerBitmap.getWidth();
        int bh = bannerBitmap.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
            return;
        }
        float scale = Math.max((float) vw / (float) bw, (float) vh / (float) bh);
        float tx = (vw - bw * scale) / 2f;
        float ty = (vh - bh * scale) / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(tx, ty);
        idleBanner.setImageMatrix(matrix);
    }

    private void updateDate() {
        Date now = new Date();
        if (idleMonth != null) {
            idleMonth.setText(monthFormat.format(now).toUpperCase(Locale.getDefault()));
        }
        if (idleDay != null) {
            idleDay.setText(dayFormat.format(now));
        }
    }

    /** Shows when the last server sync succeeded; LanSync records it only on success. */
    private void updateLastSync() {
        if (idleLastSync == null || settings == null) {
            return;
        }
        long last = settings.getLastSync();
        // The date and time followed by "Sync", shorter than a "Last sync:" label in front.
        idleLastSync.setText(last > 0
                ? getString(R.string.idle_last_sync, lastSyncFormat.format(new Date(last)))
                : getString(R.string.lan_never_synced));
    }

    private long msUntilNextMinute() {
        Calendar c = Calendar.getInstance();
        long now = c.getTimeInMillis();
        c.add(Calendar.MINUTE, 1);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return Math.max(1000L, c.getTimeInMillis() - now);
    }

    private static Bitmap decode(java.io.File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void startBlink() {
        stopBlink();
        if (blinkIndicator == null) {
            return;
        }
        blinkIndicator.setAlpha(1f);
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.15f);
        blinkAnimator.setDuration(BLINK_PERIOD_MS);
        blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnimator.setRepeatMode(ValueAnimator.REVERSE);
        blinkAnimator.setInterpolator(new LinearInterpolator());
        blinkAnimator.addUpdateListener(a -> blinkIndicator.setAlpha((float) a.getAnimatedValue()));
        blinkAnimator.start();
    }

    private void stopBlink() {
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
            blinkAnimator = null;
        }
        if (blinkIndicator != null) {
            blinkIndicator.setAlpha(1f);
        }
    }

    private void startScanPromptBlink() {
        stopScanPromptBlink();
        if (scanHerePrompt == null) {
            return;
        }
        scanHerePrompt.setAlpha(1f);
        scanPromptAnimator = ValueAnimator.ofFloat(1f, 0.35f);
        scanPromptAnimator.setDuration(SCAN_PROMPT_BLINK_MS);
        scanPromptAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanPromptAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanPromptAnimator.setInterpolator(new LinearInterpolator());
        scanPromptAnimator.addUpdateListener(a ->
                scanHerePrompt.setAlpha((float) a.getAnimatedValue()));
        scanPromptAnimator.start();

        float bounceDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, getResources().getDisplayMetrics());
        scanPromptBounceAnimator = ValueAnimator.ofFloat(0f, bounceDistance);
        scanPromptBounceAnimator.setDuration(SCAN_PROMPT_BOUNCE_MS);
        scanPromptBounceAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanPromptBounceAnimator.setRepeatMode(ValueAnimator.REVERSE);
        scanPromptBounceAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        scanPromptBounceAnimator.addUpdateListener(a ->
                scanHerePrompt.setTranslationY((float) a.getAnimatedValue()));
        scanPromptBounceAnimator.start();
    }

    private void stopScanPromptBlink() {
        if (scanPromptAnimator != null) {
            scanPromptAnimator.cancel();
            scanPromptAnimator = null;
        }
        if (scanPromptBounceAnimator != null) {
            scanPromptBounceAnimator.cancel();
            scanPromptBounceAnimator = null;
        }
        if (scanHerePrompt != null) {
            scanHerePrompt.setAlpha(1f);
            scanHerePrompt.setTranslationY(0f);
        }
    }

    private void focusScannerInput() {
        if (scannerInput == null) {
            return;
        }
        scannerInput.requestFocus();
        scannerInput.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(scannerInput.getWindowToken(), 0);
            }
        }, 50L);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String cleaned = Product.clean(value);
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return "";
    }
}
