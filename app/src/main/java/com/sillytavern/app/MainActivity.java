package com.sillytavern.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Legacy entry point kept so home-screen shortcuts created by pre-2.0
 * versions keep working: forwards to instance 1 exactly like the old
 * single-instance app did.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, Inst1Activity.class));
        finish();
    }
}
