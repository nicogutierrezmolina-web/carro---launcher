package com.micar.launcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee (solo lectura) el titulo de la cancion que suena y el texto de la
 * notificacion de la app de navegacion elegida en el boton "Navi".
 * Necesita que el usuario active "Acceso a notificaciones" para esta app.
 */
public class NotifService extends NotificationListenerService {

    public interface Listener {
        void onChanged();
    }

    public static volatile boolean connected = false;
    public static volatile String mediaTitle = null;
    public static volatile String mediaArtist = null;
    public static volatile String mediaPackage = null;
    public static volatile String navText = null;
    public static volatile String debugSessions = "";
    public static volatile String debugNotifs = "";

    private static Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void setListener(Listener l) {
        listener = l;
    }

    private static void fire() {
        final Listener l = listener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    l.onChanged();
                }
            });
        }
    }

    private MediaSessionManager msm;
    private final List<MediaController> controllers = new ArrayList<MediaController>();

    private final MediaController.Callback ctrlCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            pickMedia();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            pickMedia();
        }

        @Override
        public void onSessionDestroyed() {
            pickMedia();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessCb =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    setControllers(list);
                }
            };

    @Override
    public void onListenerConnected() {
        connected = true;
        // En esta radio el servicio se conecta en un hilo sin Looper:
        // las sesiones de musica se preparan en el hilo principal.
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                initSessions();
            }
        });
        scanNav();
        scanAll();
        fire();
    }

    private void initSessions() {
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, NotifService.class);
            msm.addOnActiveSessionsChangedListener(sessCb, me);
            setControllers(msm.getActiveSessions(me));
        } catch (Throwable t) {
            debugSessions = "Error leyendo sesiones: " + t;
            fire();
        }
    }

    private void scanAll() {
        StringBuilder sb = new StringBuilder();
        try {
            StatusBarNotification[] arr = getActiveNotifications();
            if (arr != null) {
                int n = 0;
                for (StatusBarNotification sbn : arr) {
                    if (n++ >= 12) break;
                    String d = describe(sbn.getNotification());
                    sb.append(sbn.getPackageName()).append(": ")
                            .append(d == null ? "(sin texto)" : d).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("Error: ").append(e);
        }
        debugNotifs = sb.toString();
    }

    @Override
    public void onDestroy() {
        connected = false;
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessCb);
        } catch (Throwable t) {
            // nada
        }
        super.onDestroy();
    }

    private void setControllers(List<MediaController> list) {
        for (MediaController c : controllers) {
            try {
                c.unregisterCallback(ctrlCb);
            } catch (Throwable t) {
                // nada
            }
        }
        controllers.clear();
        if (list != null) {
            for (MediaController c : list) {
                controllers.add(c);
                try {
                    c.registerCallback(ctrlCb);
                } catch (Throwable t) {
                    // nada
                }
            }
        }
        pickMedia();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.length() > 0) return a;
        return b;
    }

    private void pickMedia() {
        String bestTitle = null;
        String bestArtist = null;
        String bestPkg = null;
        boolean bestPlaying = false;
        StringBuilder dbg = new StringBuilder();
        for (MediaController c : controllers) {
            try {
                MediaMetadata m = c.getMetadata();
                PlaybackState ps = c.getPlaybackState();
                int st = ps == null ? -1 : ps.getState();
                String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
                String a = m == null ? null : firstNonEmpty(
                        m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                        m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
                dbg.append(c.getPackageName()).append(": ").append(t).append(" - ")
                        .append(a).append(" [estado ").append(st).append("]\n");
                boolean playing = st == PlaybackState.STATE_PLAYING;
                if (t != null && t.length() > 0 && (bestTitle == null || (playing && !bestPlaying))) {
                    bestTitle = t;
                    bestArtist = a;
                    bestPkg = c.getPackageName();
                    bestPlaying = playing;
                }
            } catch (Throwable e) {
                dbg.append("error: ").append(e).append('\n');
            }
        }
        mediaTitle = bestTitle;
        mediaArtist = bestArtist;
        mediaPackage = bestPkg;
        debugSessions = dbg.toString();
        fire();
    }

    // ---- Navegacion: texto de la notificacion de la app elegida en "Navi" ----

    private String navPkg() {
        SharedPreferences p = getSharedPreferences("launcher", MODE_PRIVATE);
        String s = p.getString("slot0", null);
        if (s == null) return null;
        int i = s.indexOf('/');
        return i > 0 ? s.substring(0, i) : null;
    }

    private static String describe(Notification n) {
        if (n == null || n.extras == null) return null;
        Bundle e = n.extras;
        CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = e.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (big != null && big.length() > 0) text = big;
        StringBuilder sb = new StringBuilder();
        if (title != null && title.length() > 0) sb.append(title);
        if (text != null && text.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(text);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void scanNav() {
        String pkg = navPkg();
        String result = null;
        if (pkg != null) {
            try {
                StatusBarNotification[] arr = getActiveNotifications();
                if (arr != null) {
                    for (StatusBarNotification sbn : arr) {
                        if (pkg.equals(sbn.getPackageName())) {
                            String t = describe(sbn.getNotification());
                            if (t != null) result = t;
                        }
                    }
                }
            } catch (Throwable e) {
                // sin acceso
            }
        }
        navText = result;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        scanAll();
        String pkg = navPkg();
        if (pkg != null && sbn != null && pkg.equals(sbn.getPackageName())) {
            scanNav();
            fire();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        scanAll();
        String pkg = navPkg();
        if (pkg != null && sbn != null && pkg.equals(sbn.getPackageName())) {
            scanNav();
            fire();
        }
    }
}
