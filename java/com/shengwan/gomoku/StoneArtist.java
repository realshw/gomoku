package com.shengwan.gomoku;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Draws glossy 3D stones at a fixed radius (cached shaders). */
final class StoneArtist {
	private Shader black, white;
	private float r = -1;

	void radius(float radius) {
		if (radius == r) return;
		r = radius;
		black = new RadialGradient(-r * 0.40f, -r * 0.46f, r * 1.55f,
				new int[]{0xFFC6CFDB, 0xFF5A6370, 0xFF1A1F27, 0xFF05070B},
				new float[]{0f, 0.34f, 0.72f, 1f}, Shader.TileMode.CLAMP);
		white = new RadialGradient(-r * 0.40f, -r * 0.46f, r * 1.55f,
				new int[]{0xFFFFFFFF, 0xFFF6F8FB, 0xFFD2D9E2, 0xFFA9B2C0},
				new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP);
	}

	void draw(Canvas c, float cx, float cy, float scale, boolean isBlack, Paint p) {
		float rr = r * scale;
		p.setShader(null);
		p.setStyle(Paint.Style.FILL);
		p.setAlpha(255);
		p.setColor(0x5A000000);
		c.drawCircle(cx + rr * 0.10f, cy + rr * 0.17f, rr * 1.02f, p);

		c.save();
		c.translate(cx, cy);
		p.setAlpha(255); // setColor above left the paint translucent; shaders inherit that alpha
		p.setShader(isBlack ? black : white);
		c.drawCircle(0, 0, rr, p);
		p.setShader(null);
		p.setColor(isBlack ? 0x30FFFFFF : 0x99FFFFFF);
		c.drawCircle(-rr * 0.33f, -rr * 0.37f, rr * 0.21f, p);
		c.restore();
	}

	/** Faded preview stone: same material as a landed stone, but no contact
	 *  shadow and rendered at `alpha` — so it reads as "not yet placed". */
	void ghost(Canvas c, float cx, float cy, float scale, boolean isBlack, Paint p, int alpha) {
		float rr = r * scale;
		c.save();
		c.translate(cx, cy);
		p.setStyle(Paint.Style.FILL);
		p.setAlpha(alpha);
		p.setShader(isBlack ? black : white);
		c.drawCircle(0, 0, rr, p);
		c.restore();
	}
}
