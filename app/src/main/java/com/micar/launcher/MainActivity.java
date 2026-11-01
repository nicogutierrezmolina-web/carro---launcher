package com.micar.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
    private static final String[] DEFAULT_NAMES = {
            "Navi", "Radio", "Música", "Vídeo", "Bluetooth", "Info del Coche"
    };

    private static final int COLOR_BG_1 = 0xFF0B0B0D;
    private static final int COLOR_BG_2 = 0xFF1C060A;
    private static final int COLOR_TILE = 0xFF18181D;
    private static final int COLOR_TILE_PRESSED = 0xFF5A0F18;
    private static final int COLOR_RED = 0xFFE11D2E;
    private static final int COLOR_BORDER = 0xFF3A1015;

    private PackageManager pm;
    private SharedPreferences prefs;

    private TextView speedView;
    private final LinearLayout[] tiles = new LinearLayout[SLOTS];
    private final ImageView[] icons = new ImageView[SLOTS];
    private final TextView[] labels = new TextView[SLOTS];

    private interface OnPick {
        void pick(ResolveInfo ri);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGps();
    }

    @Override
    public void onBackPressed() {
        // Un launcher no debe cerrarse con el boton atras.
    }

    // ------------------------------------------------------------------
    // Interfaz
    // ------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{COLOR_BG_1, COLOR_BG_2});
        root.setBackground(bg);

        // ---- Barra superior ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        timeCol.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d 'de' MMMM");
        date.setFormat12Hour("EEEE d 'de' MMMM");
        date.setTextColor(0xFFAAAAAA);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        timeCol.addView(date);

        top.addView(timeCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        top.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout speedCol = new LinearLayout(this);
        speedCol.setOrientation(LinearLayout.HORIZONTAL);
        speedCol.setGravity(Gravity.BOTTOM);
        speedView = new TextView(this);
        speedView.setText("--");
        speedView.setTextColor(COLOR_RED);
        speedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        speedView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        speedCol.addView(speedView);
        TextView unit = new TextView(this);
        unit.setText(" km/h");
        unit.setTextColor(0xFFAAAAAA);
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        unit.setPadding(0, 0, 0, dp(10));
        speedCol.addView(unit);
        top.addView(speedCol);

        top.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView orig = new TextView(this);
        orig.setText("Launcher original");
        orig.setTextColor(Color.WHITE);
        orig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        orig.setGravity(Gravity.CENTER);
        orig.setPadding(dp(18), dp(12), dp(18), dp(12));
        orig.setBackground(tileBg(dp(14)));
        orig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOriginalLauncher();
            }
        });
        top.addView(orig);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Fila de botones ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rowLp.topMargin = dp(10);
        rowLp.bottomMargin = dp(6);

        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            LinearLayout tile = makeTile();
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView tv = makeLabel();
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

            tiles[i] = tile;
            icons[i] = iv;
            labels[i] = tv;
            row.addView(tile, tileParams());
        }

        // Boton "Apps" (todas las aplicaciones)
        LinearLayout appsTile = makeTile();
        appsTile.addView(new DotsView(this), new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView appsLabel = makeLabel();
        appsLabel.setText("Apps");
        appsTile.addView(appsLabel);
        appsTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllApps();
            }
        });
        row.addView(appsTile, tileParams());

        root.addView(row, rowLp);

        TextView hint = new TextView(this);
        hint.setText("Mantén presionado un botón para cambiar su aplicación");
        hint.setTextColor(0xFF777777);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private View spacer() {
        return new View(this);
    }

    private LinearLayout makeTile() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        t.setBackground(tileBg(dp(22)));
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

    private TextView makeLabel() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private Drawable tileBg(int radius) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(COLOR_TILE);
        normal.setCornerRadius(radius);
        normal.setStroke(dp(1), COLOR_BORDER);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(COLOR_TILE_PRESSED);
        pressed.setCornerRadius(radius);
        pressed.setStroke(dp(2), COLOR_RED);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

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
                    float cx = w * (0.25f + 0.25f * x);
                    float cy = h * (0.25f + 0.25f * y);
                    canvas.drawCircle(cx, cy, r, paint);
                }
            }
        }
    }

    private Drawable plusDrawable() {
        int s = 96;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF888888);
        p.setStrokeWidth(8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(s / 2f, 24, s / 2f, s - 24, p);
        c.drawLine(24, s / 2f, s - 24, s / 2f, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    // ------------------------------------------------------------------
    // Botones configurables
    // ------------------------------------------------------------------

    private void refreshTiles() {
        for (int i = 0; i < SLOTS; i++) {
            ComponentName cn = getSlot(i);
            boolean ok = false;
            if (cn != null) {
                try {
                    icons[i].setImageDrawable(pm.getActivityIcon(cn));
                    labels[i].setText(pm.getActivityInfo(cn, 0).loadLabel(pm));
                    ok = true;
                } catch (PackageManager.NameNotFoundException e) {
                    ok = false;
                }
            }
            if (!ok) {
                icons[i].setImageDrawable(plusDrawable());
                labels[i].setText(DEFAULT_NAMES[i]);
            }
        }
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
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
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
                row.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
                tv = new TextView(MainActivity.this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                tv.setPadding(dp(16), 0, 0, 0);
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
    // Volver al launcher original de la radio
    // ------------------------------------------------------------------

    private void openOriginalLauncher() {
        Intent h = new Intent(Intent.ACTION_MAIN);
        h.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> all = pm.queryIntentActivities(h, 0);
        final List<ResolveInfo> others = new ArrayList<ResolveInfo>();
        for (ResolveInfo ri : all) {
            if (!getPackageName().equals(ri.activityInfo.packageName)) others.add(ri);
        }
        if (others.isEmpty()) {
            Toast.makeText(this, "No se encontró otro launcher", Toast.LENGTH_LONG).show();
        } else if (others.size() == 1) {
            startHome(others.get(0));
        } else {
            showList("Elegir launcher original", others, new OnPick() {
                @Override
                public void pick(ResolveInfo ri) {
                    startHome(ri);
                }
            });
        }
    }

    private void startHome(ResolveInfo ri) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setComponent(toComponent(ri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el launcher original", Toast.LENGTH_LONG).show();
        }
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
        if (loc == null || speedView == null) return;
        if (!loc.hasSpeed()) {
            speedView.setText("0");
            return;
        }
        long kmh = Math.round(loc.getSpeed() * 3.6f);
        if (kmh < 2) kmh = 0;
        speedView.setText(String.valueOf(kmh));
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (speedView != null) speedView.setText("--");
    }
}
