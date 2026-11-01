package com.micar.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {

    private static final int SLOTS = 6;
    private static final int SLOT_NAV = 0;
    private static final int SLOT_MUSIC = 2;
    private static final String[] DEFAULT_NAMES = {
            "Navi", "Radio", "Música", "Vídeo", "Bluetooth", "Info del Coche"
    };

    // Colores del tema rojo
    private static final int BG_TOP = 0xFF8A0B17;
    private static final int BG_BOTTOM = 0xFF2A0307;
    private static final int CARD_FILL = 0x8C000000;
    private static final int CARD_FILL_PRESSED = 0xB3500010;
    private static final int CARD_BORDER = 0x66FF6B78;
    private static final int RED = 0xFFE11D2E;
    private static final int RED_LIGHT = 0xFFFF4D5E;
    private static final int TEXT_SOFT = 0xFFE9B9BE;

    private PackageManager pm;
    private SharedPreferences prefs;
    private float scale = 1f;

    private GaugeView gauge;
    private final ImageView[] icons = new ImageView[SLOTS];
    private final TextView[] labels = new TextView[SLOTS];
    private ImageView musicIcon;
    private TextView musicTitle;
    private TextView musicSub;
    private String musicAppLabel = "";
    private TextView navLabel;
    private String navHint = "";
    private TextView activatePill;

    private String rxTitle = null;
    private String rxArtist = null;
    private String rxAction = null;

    private static final String[] MUSIC_ACTIONS = {
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.android.music.queuechanged",
            "com.spotify.music.metadatachanged",
            "com.htc.music.metachanged",
            "com.miui.player.metachanged",
            "com.sec.android.app.music.metachanged",
            "com.amazon.mp3.metachanged",
            "com.andrew.apollo.metachanged",
            "com.maxmpz.audioplayer.metachanged",
            "com.sonyericsson.music.metachanged"
    };

    private final NotifService.Listener svcListener = new NotifService.Listener() {
        @Override
        public void onChanged() {
            updateMusicUi();
            updateNavUi();
        }
    };

    private final BroadcastReceiver musicRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String t = firstExtra(i, "track", "title", "song", "name");
            if (t == null || t.length() == 0) return;
            rxTitle = t;
            rxArtist = firstExtra(i, "artist", "artist_name", "singer");
            rxAction = i.getAction();
            updateMusicUi();
        }
    };

    private interface OnPick {
        void pick(ResolveInfo ri);
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float shortSideDp = Math.min(dm.widthPixels, dm.heightPixels) / dm.density;
        scale = Math.max(0.6f, Math.min(1.5f, shortSideDp / 420f));

        autoDetectFirstRun();
        buildUi();
        refreshTiles();
        askGpsPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTiles();
        startGps();
        NotifService.setListener(svcListener);
        try {
            IntentFilter f = new IntentFilter();
            for (String a : MUSIC_ACTIONS) f.addAction(a);
            registerReceiver(musicRx, f);
        } catch (Exception e) {
            // sin receptor de musica
        }
        updateMusicUi();
        updateNavUi();
        updatePill();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGps();
        NotifService.setListener(null);
        try {
            unregisterReceiver(musicRx);
        } catch (Exception e) {
            // no estaba registrado
        }
    }

    @Override
    public void onBackPressed() {
        // Un launcher no se cierra con "atras".
    }

    // ------------------------------------------------------------------
    // Utilidades de tamano
    // ------------------------------------------------------------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density * scale + 0.5f);
    }

    private float sp(float v) {
        return v * scale;
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(sizeSp));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private View spacer() {
        return new View(this);
    }

    private Drawable cardBg(int radius) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(CARD_FILL);
        normal.setCornerRadius(radius);
        normal.setStroke(Math.max(1, dp(1)), CARD_BORDER);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(CARD_FILL_PRESSED);
        pressed.setCornerRadius(radius);
        pressed.setStroke(Math.max(2, dp(2)), RED_LIGHT);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    private Drawable ovalBg(int normalColor, int pressedColor) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(normalColor);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(pressedColor);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    // ------------------------------------------------------------------
    // Interfaz
    // ------------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(6));
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM}));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.addView(buildMusicCard(), cardParams(1.15f));
        main.addView(buildGaugeCard(), cardParams(1.0f));
        main.addView(buildNavCard(), cardParams(1.15f));
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mainLp.topMargin = dp(8);
        mainLp.bottomMargin = dp(8);
        root.addView(main, mainLp);

        root.addView(buildShortcutRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        TextView hint = text("Mantén presionado un botón para cambiar su aplicación",
                11, 0xFFC98A90, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private LinearLayout.LayoutParams cardParams(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        return lp;
    }

    private View buildTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(36));
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d 'de' MMMM");
        date.setFormat12Hour("EEEE d 'de' MMMM");
        date.setTextColor(TEXT_SOFT);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(15));
        date.setPadding(dp(14), 0, 0, 0);
        top.addView(date);

        top.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView brand = text("CHANGAN  ALSVIN", 14, 0xFFFFC9CE, true);
        brand.setLetterSpacing(0.25f);
        brand.setPadding(dp(10), dp(6), dp(10), dp(6));
        brand.setClickable(true);
        brand.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showDiagnostics();
                return true;
            }
        });
        top.addView(brand);

        top.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));

        activatePill = text("Activar canción y ruta", 14, Color.WHITE, false);
        activatePill.setGravity(Gravity.CENTER);
        activatePill.setPadding(dp(16), dp(8), dp(16), dp(8));
        activatePill.setBackground(cardBg(dp(16)));
        activatePill.setClickable(true);
        activatePill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openListenerSettings();
            }
        });
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.rightMargin = dp(10);
        top.addView(activatePill, pillLp);

        TextView home = text("Inicio", 14, Color.WHITE, false);
        home.setGravity(Gravity.CENTER);
        home.setPadding(dp(16), dp(8), dp(16), dp(8));
        home.setBackground(cardBg(dp(16)));
        home.setClickable(true);
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        });
        top.addView(home);
        return top;
    }

    // ---- Tarjeta de musica ----

    private View buildMusicCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBg(dp(22)));
        card.setClickable(true);

        card.addView(text("Música", 17, Color.WHITE, true));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        musicIcon = new ImageView(this);
        musicIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mid.addView(musicIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        musicTitle = text("", 18, Color.WHITE, true);
        musicTitle.setGravity(Gravity.CENTER);
        musicTitle.setMaxLines(2);
        musicTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        musicTitle.setPadding(0, dp(6), 0, 0);
        mid.addView(musicTitle);
        musicSub = text("", 13, TEXT_SOFT, false);
        musicSub.setGravity(Gravity.CENTER);
        musicSub.setMaxLines(2);
        musicSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(musicSub);
        card.addView(mid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.addView(makeControl(0, KeyEvent.KEYCODE_MEDIA_PREVIOUS, dp(46)));
        controls.addView(makeControl(1, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, dp(58)));
        controls.addView(makeControl(2, KeyEvent.KEYCODE_MEDIA_NEXT, dp(46)));
        card.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSlotClick(SLOT_MUSIC);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                chooseAppForSlot(SLOT_MUSIC);
                return true;
            }
        });
        return card;
    }

    private View makeControl(int type, final int keyCode, int size) {
        ControlView cv = new ControlView(this, type);
        if (type == 1) {
            cv.setBackground(ovalBg(RED, 0xFF8A0B17));
        } else {
            cv.setBackground(ovalBg(0x33FFFFFF, 0x66FFFFFF));
        }
        cv.setClickable(true);
        cv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMediaKey(keyCode);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        cv.setLayoutParams(lp);
        return cv;
    }

    private void sendMediaKey(int keyCode) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo enviar el comando", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Tarjeta velocimetro ----

    private View buildGaugeCard() {
        FrameLayout card = new FrameLayout(this);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(dp(22)));
        gauge = new GaugeView(this);
        try {
            gauge.setCar(BitmapFactory.decodeResource(getResources(), R.drawable.auto));
        } catch (Throwable t) {
            // sin imagen del auto: se muestra solo el velocimetro
        }
        card.addView(gauge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    // ---- Tarjeta navegacion ----

    private View buildNavCard() {
        FrameLayout card = new FrameLayout(this);
        card.setClickable(true);
        card.addView(new NavBgView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, dp(16), dp(12));
        info.addView(text("Navegación", 19, Color.WHITE, true));
        navLabel = text("", 13, TEXT_SOFT, false);
        navLabel.setMaxLines(2);
        navLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(navLabel);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        card.addView(info, ilp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSlotClick(SLOT_NAV);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                chooseAppForSlot(SLOT_NAV);
                return true;
            }
        });
        return card;
    }

    // ---- Fila de accesos ----

    private View buildShortcutRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            LinearLayout tile = makeTile();
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView tv = text("", 13, Color.WHITE, false);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setPadding(dp(2), dp(4), dp(2), 0);
            tile.addView(tv);

            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSlotClick(slot);
                }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    chooseAppForSlot(slot);
                    return true;
                }
            });
            icons[i] = iv;
            labels[i] = tv;
            row.addView(tile, tileParams());
        }

        LinearLayout appsTile = makeTile();
        appsTile.addView(new DotsView(this), new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView appsLabel = text("Apps", 13, Color.WHITE, false);
        appsLabel.setGravity(Gravity.CENTER);
        appsLabel.setPadding(dp(2), dp(4), dp(2), 0);
        appsTile.addView(appsLabel);
        appsTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllApps();
            }
        });
        row.addView(appsTile, tileParams());
        return row;
    }

    private LinearLayout makeTile() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        t.setBackground(cardBg(dp(18)));
        t.setClickable(true);
        return t;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = dp(5);
        lp.rightMargin = dp(5);
        return lp;
    }

    // ------------------------------------------------------------------
    // Vistas dibujadas a mano (livianas)
    // ------------------------------------------------------------------

    /** Icono de 9 puntos para el boton "Apps". */
    private static class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsView(Context c) {
            super(c);
            paint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 12f;
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) {
                    canvas.drawCircle(w * (0.25f + 0.25f * x), h * (0.25f + 0.25f * y), r, paint);
                }
            }
        }
    }

    /** Botones anterior / reproducir / siguiente. */
    private static class ControlView extends View {
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ControlView(Context c, int type) {
            super(c);
            this.type = type;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float s = Math.min(w, h) * 0.2f;
            path.reset();
            if (type == 1) {
                path.moveTo(cx - s * 0.7f, cy - s * 1.1f);
                path.lineTo(cx + s * 1.1f, cy);
                path.lineTo(cx - s * 0.7f, cy + s * 1.1f);
                path.close();
            } else if (type == 0) {
                canvas.drawRect(cx - s * 1.1f, cy - s, cx - s * 0.6f, cy + s, p);
                path.moveTo(cx + s * 1.1f, cy - s);
                path.lineTo(cx - s * 0.4f, cy);
                path.lineTo(cx + s * 1.1f, cy + s);
                path.close();
            } else {
                canvas.drawRect(cx + s * 0.6f, cy - s, cx + s * 1.1f, cy + s, p);
                path.moveTo(cx - s * 1.1f, cy - s);
                path.lineTo(cx + s * 0.4f, cy);
                path.lineTo(cx - s * 1.1f, cy + s);
                path.close();
            }
            canvas.drawPath(path, p);
        }
    }

    /** Velocimetro: arco con la velocidad actual. */
    private static class GaugeView extends View {
        private static final float MAX_SPEED = 200f;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF oval = new RectF();
        private final RectF carRect = new RectF();
        private Bitmap car;
        private int speed = 0;
        private boolean fix = false;

        void setCar(Bitmap b) {
            car = b;
            invalidate();
        }

        GaugeView(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x33FFFFFF);
            track.setStrokeCap(Paint.Cap.ROUND);
            prog.setStyle(Paint.Style.STROKE);
            prog.setColor(RED_LIGHT);
            prog.setStrokeCap(Paint.Cap.ROUND);
            tick.setStyle(Paint.Style.STROKE);
            tick.setColor(0x66FFFFFF);
            num.setColor(Color.WHITE);
            num.setTextAlign(Paint.Align.CENTER);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            small.setColor(TEXT_SOFT);
            small.setTextAlign(Paint.Align.CENTER);
        }

        void setSpeed(int s, boolean hasFix) {
            if (s != speed || hasFix != fix) {
                speed = s;
                fix = hasFix;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) / 2f - Math.min(w, h) * 0.08f;
            float sw = r * 0.13f;
            track.setStrokeWidth(sw);
            prog.setStrokeWidth(sw);
            tick.setStrokeWidth(Math.max(2f, r * 0.015f));

            float half = sw / 2f;
            oval.set(cx - r + half, cy - r + half, cx + r - half, cy + r - half);
            canvas.drawArc(oval, 135f, 270f, false, track);
            float frac = Math.max(0f, Math.min(1f, speed / MAX_SPEED));
            if (frac > 0f) canvas.drawArc(oval, 135f, 270f * frac, false, prog);

            // marcas cada 20 km/h dentro del arco
            for (int v = 0; v <= 200; v += 20) {
                double ang = Math.toRadians(135.0 + 270.0 * v / MAX_SPEED);
                float r1 = r - sw - r * 0.05f;
                float r2 = r1 - r * 0.09f;
                canvas.drawLine(
                        cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1,
                        cx + (float) Math.cos(ang) * r2, cy + (float) Math.sin(ang) * r2, tick);
            }

            String value = fix ? String.valueOf(speed) : "--";
            if (car != null && car.getWidth() > 0) {
                // numero arriba, auto abajo, todo dentro del arco
                num.setTextSize(r * 0.46f);
                canvas.drawText(value, cx, cy - r * 0.22f, num);
                small.setTextSize(r * 0.13f);
                canvas.drawText(fix ? "km/h" : "Sin GPS", cx, cy - r * 0.04f, small);
                float cw = r * 1.4f;
                float ch = cw * car.getHeight() / car.getWidth();
                float top = cy + r * 0.08f;
                carRect.set(cx - cw / 2f, top, cx + cw / 2f, top + ch);
                canvas.drawBitmap(car, null, carRect, bmpPaint);
            } else {
                num.setTextSize(r * 0.78f);
                canvas.drawText(value, cx, cy + r * 0.22f, num);
                small.setTextSize(r * 0.2f);
                canvas.drawText("km/h", cx, cy + r * 0.48f, small);
                small.setTextSize(r * 0.16f);
                canvas.drawText(fix ? "GPS" : "Sin GPS", cx, cy + r * 0.88f, small);
            }
        }
    }

    /** Fondo decorativo de la tarjeta de navegacion (no es un mapa real). */
    private static class NavBgView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final Path rp = new Path();

        NavBgView(Context c) {
            super(c);
            fill.setStyle(Paint.Style.FILL);
            grid.setStyle(Paint.Style.STROKE);
            grid.setColor(0x22FFFFFF);
            route.setStyle(Paint.Style.STROKE);
            route.setColor(RED_LIGHT);
            route.setStrokeCap(Paint.Cap.ROUND);
            route.setStrokeJoin(Paint.Join.ROUND);
            dot.setStyle(Paint.Style.FILL);
            border.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float rad = Math.min(w, h) * 0.1f;
            float b = Math.max(2f, h * 0.006f);
            rect.set(b, b, w - b, h - b);

            fill.setColor(isPressed() ? CARD_FILL_PRESSED : CARD_FILL);
            canvas.drawRoundRect(rect, rad, rad, fill);

            canvas.save();
            clip.reset();
            clip.addRoundRect(rect, rad, rad, Path.Direction.CW);
            canvas.clipPath(clip);

            grid.setStrokeWidth(Math.max(2f, h * 0.012f));
            for (int i = 1; i < 6; i++) {
                canvas.drawLine(w * i / 6f, 0, w * i / 6f, h, grid);
            }
            for (int i = 1; i < 5; i++) {
                canvas.drawLine(0, h * i / 5f, w, h * i / 5f, grid);
            }
            canvas.drawLine(0, h * 0.9f, w * 0.8f, 0, grid);

            route.setStrokeWidth(Math.max(4f, h * 0.035f));
            route.setPathEffect(new CornerPathEffect(h * 0.06f));
            rp.reset();
            rp.moveTo(w * 0.10f, h * 0.62f);
            rp.lineTo(w * 0.35f, h * 0.62f);
            rp.lineTo(w * 0.35f, h * 0.38f);
            rp.lineTo(w * 0.62f, h * 0.38f);
            rp.lineTo(w * 0.62f, h * 0.18f);
            rp.lineTo(w * 0.88f, h * 0.18f);
            canvas.drawPath(rp, route);

            dot.setColor(Color.WHITE);
            canvas.drawCircle(w * 0.10f, h * 0.62f, h * 0.035f, dot);
            dot.setColor(RED);
            canvas.drawCircle(w * 0.88f, h * 0.18f, h * 0.06f, dot);
            dot.setColor(Color.WHITE);
            canvas.drawCircle(w * 0.88f, h * 0.18f, h * 0.025f, dot);
            canvas.restore();

            border.setStrokeWidth(isPressed() ? Math.max(3f, h * 0.012f) : b);
            border.setColor(isPressed() ? RED_LIGHT : CARD_BORDER);
            canvas.drawRoundRect(rect, rad, rad, border);
        }
    }

    private Drawable plusDrawable() {
        int s = 96;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFC98A90);
        p.setStrokeWidth(8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(s / 2f, 24, s / 2f, s - 24, p);
        c.drawLine(24, s / 2f, s - 24, s / 2f, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    // ------------------------------------------------------------------
    // Botones configurables
    // ------------------------------------------------------------------

    private boolean loadSlot(int i, ImageView iv, TextView tv) {
        ComponentName cn = getSlot(i);
        if (cn != null) {
            try {
                iv.setImageDrawable(pm.getActivityIcon(cn));
                tv.setText(pm.getActivityInfo(cn, 0).loadLabel(pm));
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // la app ya no existe
            }
        }
        iv.setImageDrawable(plusDrawable());
        tv.setText(DEFAULT_NAMES[i]);
        return false;
    }

    private void refreshTiles() {
        for (int i = 0; i < SLOTS; i++) {
            loadSlot(i, icons[i], labels[i]);
        }
        TextView tmp = new TextView(this);
        if (loadSlot(SLOT_MUSIC, musicIcon, tmp)) {
            musicAppLabel = tmp.getText().toString();
        } else {
            musicAppLabel = "Toca para elegir tu app de música";
        }
        TextView tmp2 = new TextView(this);
        if (loadSlot(SLOT_NAV, new ImageView(this), tmp2)) {
            navHint = "Abrir " + tmp2.getText();
        } else {
            navHint = "Toca para elegir tu app de mapas";
        }
        updateMusicUi();
        updateNavUi();
        updatePill();
    }

    private ComponentName getSlot(int i) {
        String saved = prefs.getString("slot" + i, null);
        if (saved == null) return null;
        return ComponentName.unflattenFromString(saved);
    }

    private void onSlotClick(int slot) {
        ComponentName cn = getSlot(slot);
        if (cn == null) {
            chooseAppForSlot(slot);
            return;
        }
        if (!launch(cn)) {
            Toast.makeText(this, "No se pudo abrir. Mantén presionado para elegir otra app.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseAppForSlot(final int slot) {
        showList("Elegir app para: " + DEFAULT_NAMES[slot], launchableApps(), new OnPick() {
            @Override
            public void pick(ResolveInfo ri) {
                prefs.edit().putString("slot" + slot, toComponent(ri).flattenToString()).apply();
                refreshTiles();
            }
        });
    }

    private void showAllApps() {
        showList("Todas las aplicaciones", launchableApps(), new OnPick() {
            @Override
            public void pick(ResolveInfo ri) {
                launch(toComponent(ri));
            }
        });
    }

    private boolean launch(ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ComponentName toComponent(ResolveInfo ri) {
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    // ------------------------------------------------------------------
    // Lista de apps y deteccion automatica
    // ------------------------------------------------------------------

    private List<ResolveInfo> launchableApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(i, 0);
        List<ResolveInfo> list = new ArrayList<ResolveInfo>();
        for (ResolveInfo ri : all) {
            if (!getPackageName().equals(ri.activityInfo.packageName)) {
                list.add(ri);
            }
        }
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        return list;
    }

    private String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private ComponentName findByLabel(List<ResolveInfo> apps, String name) {
        String n = norm(name);
        for (ResolveInfo ri : apps) {
            if (norm(ri.loadLabel(pm).toString()).equals(n)) return toComponent(ri);
        }
        for (ResolveInfo ri : apps) {
            if (norm(ri.loadLabel(pm).toString()).contains(n)) return toComponent(ri);
        }
        return null;
    }

    private void autoDetectFirstRun() {
        if (prefs.getBoolean("init", false)) return;
        List<ResolveInfo> apps = launchableApps();
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < SLOTS; i++) {
            ComponentName cn = findByLabel(apps, DEFAULT_NAMES[i]);
            if (cn != null) ed.putString("slot" + i, cn.flattenToString());
        }
        ed.putBoolean("init", true);
        ed.apply();
    }

    // ------------------------------------------------------------------
    // Dialogo con lista de apps
    // ------------------------------------------------------------------

    private void showList(String title, final List<ResolveInfo> items, final OnPick cb) {
        if (items.isEmpty()) {
            Toast.makeText(this, "No se encontraron aplicaciones", Toast.LENGTH_LONG).show();
            return;
        }
        ListView lv = new ListView(this);
        lv.setAdapter(new AppAdapter(items));
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Holo_Dialog)
                .setTitle(title)
                .setView(lv)
                .setNegativeButton("Cerrar", null)
                .create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                dialog.dismiss();
                cb.pick(items.get(pos));
            }
        });
        dialog.show();
    }

    private class AppAdapter extends BaseAdapter {
        private final List<ResolveInfo> items;

        AppAdapter(List<ResolveInfo> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView iv;
            TextView tv;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                iv = new ImageView(MainActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
                tv = new TextView(MainActivity.this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(20));
                tv.setPadding(dp(14), 0, 0, 0);
                row.addView(tv);
            } else {
                row = (LinearLayout) convertView;
                iv = (ImageView) row.getChildAt(0);
                tv = (TextView) row.getChildAt(1);
            }
            ResolveInfo ri = items.get(position);
            iv.setImageDrawable(ri.loadIcon(pm));
            tv.setText(ri.loadLabel(pm));
            return row;
        }
    }

    // ------------------------------------------------------------------
    // Inicio, lectura de musica/ruta y diagnostico
    // ------------------------------------------------------------------

    private void goHome() {
        try {
            Intent h = new Intent(Intent.ACTION_MAIN);
            h.addCategory(Intent.CATEGORY_HOME);
            h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(h);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo volver al inicio", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isListenerEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void openListenerSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "Tu radio no tiene esa pantalla de ajustes. Mantén presionado CHANGAN ALSVIN para ver el diagnóstico.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir los ajustes", Toast.LENGTH_LONG).show();
        }
    }

    private void updatePill() {
        if (activatePill != null) {
            activatePill.setVisibility(isListenerEnabled() ? View.GONE : View.VISIBLE);
        }
    }

    private static String firstExtra(Intent i, String... keys) {
        try {
            Bundle b = i.getExtras();
            if (b == null) return null;
            for (String k : keys) {
                Object o = b.get(k);
                if (o instanceof CharSequence && ((CharSequence) o).length() > 0) {
                    return o.toString();
                }
            }
        } catch (Exception e) {
            // nada
        }
        return null;
    }

    private void updateMusicUi() {
        if (musicTitle == null || musicSub == null) return;
        String t = NotifService.mediaTitle;
        String a = NotifService.mediaArtist;
        if (t == null || t.length() == 0) {
            t = rxTitle;
            a = rxArtist;
        }
        if (t != null && t.length() > 0) {
            musicTitle.setText(t);
            musicSub.setText(a != null && a.length() > 0 ? a : musicAppLabel);
        } else {
            musicTitle.setText(musicAppLabel);
            musicSub.setText(isListenerEnabled()
                    ? "Esperando música…"
                    : "Activa la lectura para ver la canción");
        }
    }

    private void updateNavUi() {
        if (navLabel == null) return;
        String t = NotifService.navText;
        navLabel.setText(t != null && t.length() > 0 ? t : navHint);
    }

    private void showDiagnostics() {
        ComponentName nav = getSlot(SLOT_NAV);
        StringBuilder sb = new StringBuilder();
        sb.append("Lectura de notificaciones: ")
                .append(isListenerEnabled() ? "ACTIVADA" : "desactivada").append('\n');
        sb.append("Servicio conectado: ").append(NotifService.connected ? "sí" : "no").append("\n\n");
        sb.append("Sesiones de música:\n");
        String ds = NotifService.debugSessions;
        sb.append(ds == null || ds.length() == 0 ? "(ninguna)\n" : ds);
        sb.append("\nAvisos activos del sistema:\n");
        String dn = NotifService.debugNotifs;
        sb.append(dn == null || dn.length() == 0 ? "(ninguno)\n" : dn);
        sb.append("\nAviso de música (broadcast): ");
        sb.append(rxAction == null ? "(ninguno)" : rxAction + " → " + rxTitle).append("\n\n");
        sb.append("App de navegación: ").append(nav == null ? "(sin elegir)" : nav.getPackageName()).append('\n');
        sb.append("Texto leído: ").append(NotifService.navText == null ? "(nada)" : NotifService.navText);

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, android.R.style.Theme_Holo_Dialog)
                .setTitle("Diagnóstico")
                .setView(sv)
                .setPositiveButton("Abrir ajustes", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        openListenerSettings();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Velocidad por GPS
    // ------------------------------------------------------------------

    private boolean hasGpsPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askGpsPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasGpsPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startGps();
    }

    private void startGps() {
        if (!hasGpsPermission()) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            }
        } catch (SecurityException e) {
            // sin permiso: se queda en "--"
        } catch (Exception e) {
            // GPS no disponible
        }
    }

    private void stopGps() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception e) {
            // nada
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null || gauge == null) return;
        long kmh = loc.hasSpeed() ? Math.round(loc.getSpeed() * 3.6f) : 0;
        if (kmh < 2) kmh = 0;
        gauge.setSpeed((int) kmh, true);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (gauge != null) gauge.setSpeed(0, false);
    }
}
