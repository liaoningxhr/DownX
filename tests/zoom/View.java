package android.view;

import android.content.res.Resources;

/** 桌面验证台用的极简 View：只留 Zoomer 会用到的缩放/位移属性 */
public class View {
    public int width, height;
    public float scaleX = 1f, scaleY = 1f;
    public float translationX, translationY;
    public float pivotX, pivotY;

    public View(int w, int h) {
        width = w;
        height = h;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setPivotX(float v) { pivotX = v; }
    public void setPivotY(float v) { pivotY = v; }
    public void setScaleX(float v) { scaleX = v; }
    public void setScaleY(float v) { scaleY = v; }
    public void setTranslationX(float v) { translationX = v; }
    public void setTranslationY(float v) { translationY = v; }

    public Resources getResources() { return Resources.INSTANCE; }
}
