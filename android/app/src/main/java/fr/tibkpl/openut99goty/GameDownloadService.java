package fr.tibkpl.openut99goty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Telecharge et installe Unreal Tournament 99 GOTY dans le dossier choisi, comme l'installeur
 * officiel d'OldUnreal : image ISO du DVD (diffusion gratuite autorisee par Epic Games) depuis
 * les miroirs d'OldUnreal ou Internet Archive, puis extraction (7-Zip, unshield) dans le runtime
 * Linux. Service au premier plan : continue ecran eteint ; un telechargement interrompu reprend.
 */
public class GameDownloadService extends Service {

    static final String EXTRA_DEST = "dest";

    /** Sources de l'ISO (installeur Linux d'OldUnreal) : URL, taille, SHA-256. */
    private static final String[][] ISO_SOURCES = {
            {"https://files.oldunreal.net/UT_GOTY_CD1.ISO", "649633792", "e184984ca88f001c5ddd52035d76cd64e266e26c74975161b5ed72366c74704f"},
            {"https://files2.oldunreal.net/UT_GOTY_CD1.ISO", "649633792", "e184984ca88f001c5ddd52035d76cd64e266e26c74975161b5ed72366c74704f"},
            {"https://files3.oldunreal.net/UT_GOTY_CD1.ISO", "649633792", "e184984ca88f001c5ddd52035d76cd64e266e26c74975161b5ed72366c74704f"},
            {"https://archive.org/download/ut-goty/UT_GOTY_CD1.iso", "649633792", "e184984ca88f001c5ddd52035d76cd64e266e26c74975161b5ed72366c74704f"}
    };

    /** Espace libre conseille : ISO (3 Go) puis extraction (~6 Go) avant suppression de l'ISO. */
    static final long REQUIRED_SPACE = 3L * 1024 * 1024 * 1024;

    private static final String TAG = "OpenUT99GOTY";
    private static final String CHANNEL = "download";
    private static final int NOTIFICATION_ID = 1;

    /** Etat partage avec l'ecran d'accueil. */
    static final class State {
        volatile boolean running;
        volatile boolean finished;
        volatile String message = "";
        volatile int percent = -1;
        volatile String error;
        volatile File gameDir;
    }

    static final State STATE = new State();

    private static volatile boolean workerActive;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context context, File dest) {
        Intent intent = new Intent(context, GameDownloadService.class).putExtra(EXTRA_DEST, dest.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String dest = intent == null ? null : intent.getStringExtra(EXTRA_DEST);
        startForegroundCompat(notification(getString(R.string.download_preparing), -1));
        // Indicateur propre au service (STATE.running est aussi positionne par l'ecran d'accueil
        // pour afficher la progression sans attendre)
        if (dest == null || workerActive) return START_NOT_STICKY;
        workerActive = true;

        STATE.running = true;
        STATE.finished = false;
        STATE.error = null;
        STATE.gameDir = null;
        acquireLocks();
        worker = new Thread(() -> {
            try {
                File gameDir = install(new File(dest));
                GameData.savePath(this, gameDir);
                STATE.gameDir = gameDir;
                update(getString(R.string.download_done), 100);
            } catch (Exception e) {
                Log.e(TAG, "Installation", e);
                STATE.error = e.getMessage();
            } finally {
                workerActive = false;
                STATE.running = false;
                STATE.finished = true;
                releaseLocks();
                showFinalNotification();
                stopForegroundCompat();
                stopSelf();
            }
        }, "ut99-download");
        worker.start();
        return START_NOT_STICKY;
    }

    private File install(File dest) throws Exception {
        GameInstaller.mkdirs(dest);
        File rootfs = GameInstaller.ensureRuntime(this, step -> update(step, -1));

        // 1. Image ISO du DVD (reprise si un telechargement precedent a ete interrompu)
        File installerDir = new File(dest, "Installer");
        GameInstaller.mkdirs(installerDir);
        File iso = null;
        IOException lastError = null;
        boolean downloaded = false;
        for (String[] source : ISO_SOURCES) {
            long size = Long.parseLong(source[1]);
            // Nom d'apres l'empreinte : les miroirs d'un meme fichier partagent le partiel
            iso = new File(installerDir, "UT99-" + source[2].substring(0, 8) + ".iso");
            try {
                update(getString(R.string.download_iso, 0), 0);
                Http.download(source[0], iso, size, null, true, (done, total) -> update(
                        getString(R.string.download_iso_progress, done >> 20, total >> 20),
                        total > 0 ? (int) (done * 100 / total) : -1));
                update(getString(R.string.download_verify), -1);
                if (!Http.sha256Of(iso).equalsIgnoreCase(source[2])) {
                    iso.delete();
                    throw new IOException("Image ISO corrompue");
                }
                downloaded = true;
                break;
            } catch (IOException e) {
                Log.w(TAG, "Source " + source[0] + " : " + e.getMessage());
                lastError = e;
            }
        }
        if (!downloaded) throw lastError != null ? lastError : new IOException("Telechargement impossible");

        File bonus = new File(installerDir, "bonuspack4.7z");
        Http.download("https://files.oldunreal.net/utbonuspack4-zip.7z", bonus, 11268844,
                "5b7a1080724a122a596c226c50d4dc7c2d7636ceaf067e9c12112014a170ffba", true,
                (done, total) -> update("Bonus Pack 4", total > 0 ? (int)(done * 100 / total) : -1));
        // 2. Extraction dans le runtime Linux (7-Zip + unshield), voir /opt/ut/install-data.sh
        // Archives intermediaires dans la memoire interne de l'appli (rapide, hors stockage
        // partage) : les ecrire sur la carte SD saturait la memoire et Android tuait l'appli.
        File work = new File(getCacheDir(), "ut-install");
        GameInstaller.mkdirs(work);
        List<String> binds = new ArrayList<>();
        binds.add(work.getAbsolutePath() + ":/work");
        ProcessBuilder pb = GameRuntime.proot(this, rootfs, binds, new ArrayList<>(), "/",
                "/opt/ut/install-data.sh", iso.getAbsolutePath(), dest.getAbsolutePath(), "/work", bonus.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder log = new StringBuilder();
        String step = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, "install : " + line);
                if (line.startsWith("STEP ")) {
                    step = line.substring(7).trim();
                    update(getString(R.string.download_install, step), 0);
                } else if (line.startsWith("PROGRESS ")) {
                    update(getString(R.string.download_install, step), parseInt(line.substring(9)));
                } else {
                    log.append(line).append('\n');
                    if (log.length() > 4000) log.delete(0, log.length() - 4000);
                }
            }
        }
        int code = process.waitFor();
        GameInstaller.deleteRecursively(work);
        if (code != 0) throw new IOException("Extraction echouee (code " + code + ")\n" + log);

        // 3. L'ISO (et un eventuel partiel d'une autre source) n'est plus utile
        GameInstaller.deleteRecursively(installerDir);
        File gameDir = GameData.findGameDir(dest);
        if (gameDir == null) throw new IOException("Installation incomplete : " + GameData.missing(dest));
        return gameDir;
    }

    private static int parseInt(String s) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void update(String message, int percent) {
        STATE.message = message;
        STATE.percent = percent;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification(message, percent));
    }

    private Notification notification(String text, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, LauncherActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.download_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void showFinalNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        String text = STATE.error == null ? getString(R.string.download_done)
                : getString(R.string.download_failed, STATE.error);
        Notification n = notification(text, 0);
        Notification.Builder b = Notification.Builder.recoverBuilder(this, n)
                .setOngoing(false).setProgress(0, 0, false).setAutoCancel(true)
                .setSmallIcon(STATE.error == null ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error);
        nm.notify(NOTIFICATION_ID + 1, b.build());
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenUT99GOTY:download");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenUT99GOTY:download");
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override
    public void onDestroy() {
        if (worker != null && STATE.running) worker.interrupt();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
