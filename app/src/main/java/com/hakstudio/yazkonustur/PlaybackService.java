package com.hakstudio.yazkonustur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

/**
 * Okuma sürerken uygulamanın arka planda ve ekran kapalıyken çalışmaya devam etmesini sağlar.
 * Bildirim ve kilit ekranındaki düğmeler sayfaya (YKApp.cmd) iletilir.
 */
public class PlaybackService extends Service {
    static final String CH = "reading";
    static final int ID = 7;
    static final String ACT_CMD = "yk.cmd";
    static MainActivity activity;
    static PlaybackService self;
    static boolean playing;
    static String title = "", text = "";
    static int pos, total;

    MediaSessionCompat session;
    PowerManager.WakeLock cpu;

    static void update(Context c, boolean isPlaying, String t, String line, int p, int n) {
        playing = isPlaying;
        title = t == null ? "" : t;
        text = line == null ? "" : line;
        pos = p;
        total = n;
        if (self != null) {
            self.refresh();
            return;
        }
        if (!isPlaying) return; // duraklatılmışken yeniden başlatmaya gerek yok
        Intent i = new Intent(c, PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        playing = false;
        if (self != null) self.shutdown();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSessionCompat(this, "YazKonustur");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSkipToPrevious() { send("prev"); }
            @Override public void onStop() { send("stop"); }
        });
        session.setActive(true);
        PowerManager pm = getSystemService(PowerManager.class);
        cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YazKonustur:reading");
        cpu.setReferenceCounted(false);
    }

    static void send(String cmd) {
        MainActivity a = activity;
        if (a != null) a.js("YKApp.cmd('" + cmd + "')");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
        if (intent != null) {
            if (ACT_CMD.equals(intent.getAction())) send(intent.getStringExtra("c"));
            else MediaButtonReceiver.handleIntent(session, intent);
        }
        refresh();
        return START_NOT_STICKY;
    }

    PendingIntent cmd(String c, int req) {
        Intent i = new Intent(this, PlaybackService.class).setAction(ACT_CMD).putExtra("c", c);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    Notification build() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String sub = total > 0 ? "Parça " + Math.min(pos + 1, total) + " / " + total : "";
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(text.isEmpty() ? sub : text)
                .setSubText(sub)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setDeleteIntent(cmd("stop", 5))
                .addAction(android.R.drawable.ic_media_previous, "Önceki", cmd("prev", 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Duraklat" : "Oynat", cmd(playing ? "pause" : "play", 2))
                .addAction(android.R.drawable.ic_media_next, "Sonraki", cmd("next", 3))
                .setStyle(new MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    void refresh() {
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.isEmpty() ? getString(R.string.app_name) : title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, text)
                .build());
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        try {
            getSystemService(NotificationManager.class).notify(ID, build());
        } catch (Exception ignored) {
        }
        // Ses üretimi ekran kapalıyken de sürsün diye işlemciyi uyanık tut (en fazla 3 saat)
        if (playing) cpu.acquire(3 * 60 * 60 * 1000L);
        else if (cpu.isHeld()) cpu.release();
    }

    void shutdown() {
        if (cpu.isHeld()) cpu.release();
        session.setActive(false);
        session.release();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
        self = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Uygulama son kullanılanlardan kaydırılırsa okumayı bitir
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (self == this) {
            if (cpu.isHeld()) cpu.release();
            session.release();
            self = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
