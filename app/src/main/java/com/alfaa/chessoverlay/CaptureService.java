package com.alfaa.chessoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.Nullable;

/**
 * Foreground service: MediaProjection screen capture + periodic frame
 * analysis + engine call + updates the floating overlay arrow.
 */
public class CaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static final String CHANNEL_ID = "capture";
    private static final long FRAME_INTERVAL_MS = 700; // analysis cadence

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private WindowManager wm;
    private android.view.View overlayRoot;
    private ArrowOverlayView arrowView;

    private BoardDetector detector = new BoardDetector();
    private StockfishEngine engine = new StockfishEngine();
    private boolean running = false;
    private int screenWidth, screenHeight, screenDpi;
    private String lastFen = null;
    private boolean whiteToMove = true;
    private boolean calibrated = false;

    private final Runnable analysisLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try { grabAndAnalyze(); } catch (Throwable t) { /* keep loop alive */ }
            handler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDpi = dm.densityDpi;
        thread = new HandlerThread("capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        startForeground(1, buildNotification());
        showOverlay();
        engine.start(new java.io.File(getApplicationInfo().nativeLibraryDir), s -> {});
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }
        startCapture();
        running = true;
        handler.post(analysisLoop);
        return START_STICKY;
    }

    private void startCapture() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("chesscap",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);
    }

    private void grabAndAnalyze() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            int rowPadding = plane.getRowStride() - plane.getPixelStride() * screenWidth;
            Bitmap bmp = Bitmap.createBitmap(screenWidth + rowPadding / plane.getPixelStride(), screenHeight, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(plane.getBuffer());
            Bitmap frame = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight);

            if (!calibrated) {
                calibrated = detector.autoDetectBoard(frame);
                if (!calibrated) return;
                arrowView.setBoardRect(detector.boardX, detector.boardY, detector.boardSize);
            }

            String placement = detector.analyzeFrame(frame);
            if (placement == null) return;

            // Move detection: if the placement changed from last frame, it means
            // a move was just played -> now it is our turn.
            if (lastFen != null && !placement.equals(lastFen)) {
                whiteToMove = !whiteToMove;
                lastFen = placement;
                String fen = BoardDetector.buildFen(placement, whiteToMove);
                String best = engine.bestMove(fen, 1000);
                if (best != null && arrowView != null) arrowView.setMove(best);
            } else if (lastFen == null) {
                lastFen = placement;
            }
        } finally {
            if (image != null) image.close();
        }
    }

    private void showOverlay() {
        handler.post(() -> {
            overlayRoot = new android.widget.FrameLayout(this);
            arrowView = new ArrowOverlayView(this);
            ((android.widget.FrameLayout) overlayRoot).addView(arrowView,
                    new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            int type = android.os.Build.VERSION.SDK_INT >= 26
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE;
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            try { wm.addView(overlayRoot, lp); } catch (Exception ignored) {}
        });
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Chess capture", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Chess Overlay aktif")
                .setContentText("Menganalisis papan catur...")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(analysisLoop);
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (imageReader != null) imageReader.close();
        if (overlayRoot != null) try { wm.removeView(overlayRoot); } catch (Exception ignored) {}
        engine.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
