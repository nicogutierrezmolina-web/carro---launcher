package com.micar.t5;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Prueba 5 funciona");
        tv.setTextSize(40);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);
    }
}
