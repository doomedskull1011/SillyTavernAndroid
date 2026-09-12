package com.sillytavern.app;

import android.content.Context;
import android.graphics.Color;

/** Shared SillyTavern-flavored dark theme constants for the native UI. */
public class Ui {
    public static final int BG = Color.parseColor("#1A1625");
    public static final int CARD = Color.parseColor("#25203A");
    public static final int CARD_BORDER = Color.parseColor("#3A3252");
    public static final int ACCENT = Color.parseColor("#7C6FCE");
    public static final int ACCENT_DIM = Color.parseColor("#4A4170");
    public static final int TEXT = Color.WHITE;
    public static final int TEXT_DIM = Color.parseColor("#8F86A6");
    public static final int GOOD = Color.parseColor("#7BC47F");
    public static final int BAD = Color.parseColor("#D96459");

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
