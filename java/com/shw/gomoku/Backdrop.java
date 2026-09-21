package com.shw.gomoku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Deep gradient backdrop with two soft glows and a vignette. */
final class Backdrop extends View {

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private int w, h;

	Backdrop(Context c) {
		super(c);
		setClickable(false);
		setFocusable(false);
	}

	@Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
		w = nw; h = nh;
		paint.setShader(new LinearGradient(0, 0, 0, h,
				new int[]{Theme.BG_TOP, Theme.BG_BOTTOM}, new float[]{0f, 1f},
				Shader.TileMode.CLAMP));
	}

	@Override protected void onDraw(Canvas c) {
		c.drawRect(0, 0, w, h, paint);
		// warm glow behind the board, cool glow near the base
		drawGlow(c, w * 0.5f, h * 0.30f, w * 0.95f, Theme.alpha(Theme.GOLD, 0.10f));
		drawGlow(c, w * 0.15f, h * 0.92f, w * 0.85f, 0x142E6BFF);
		drawGlow(c, w * 0.92f, h * 0.80f, w * 0.60f, 0x1000C2A8);
	}

	private void drawGlow(Canvas c, float cx, float cy, float radius, int color) {
		paint.setShader(new RadialGradient(cx, cy, radius,
				new int[]{color, (color & 0x00FFFFFF)}, new float[]{0f, 1f},
				Shader.TileMode.CLAMP));
		c.drawRect(0, 0, w, h, paint);
	}
}
