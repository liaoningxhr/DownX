package android.content.res;

import android.util.DisplayMetrics;

public class Resources {
    public static final Resources INSTANCE = new Resources();

    public DisplayMetrics getDisplayMetrics() { return DisplayMetrics.INSTANCE; }
}
