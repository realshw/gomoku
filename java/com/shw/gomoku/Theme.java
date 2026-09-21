package com.shw.gomoku;

import android.graphics.Color;

/** Shared palette for the app. */
final class Theme {
	private Theme() {}

	static final int BG_TOP = 0xFF0D121B;
	static final int BG_BOTTOM = 0xFF05070B;

	static final int GOLD = 0xFFFFD166;
	static final int GOLD_DIM = 0xFFB98A34;
	static final int TEXT = 0xFFF3F5F9;
	static final int MUTED = 0xFF8B94A5;
	static final int RED = 0xFFFF6B6B;

	static final int CARD = 0x10FFFFFF;
	static final int CARD_STROKE = 0x1AFFFFFF;
	static final int CARD_ACTIVE = 0x1FFFD166;

	static final int WOOD_TOP = 0xFFE9C488;
	static final int WOOD_MID = 0xFFD2A055;
	static final int WOOD_BOTTOM = 0xFFA96E28;
	static final int WOOD_FRAME = 0xFF4E320F;
	static final int GRID = 0xC0462C10;

	static int alpha(int color, float f) {
		return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0, Math.min(1, f))) << 24);
	}

	static int mix(int a, int b, float t) {
		int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
		int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
		return Color.rgb(
				Math.round(ar + (br - ar) * t),
				Math.round(ag + (bg - ag) * t),
				Math.round(ab + (bb - ab) * t));
	}
}
