package com.shw.gomoku;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** A player status card: stone, name, role, active/thinking state. */
final class PlayerCard extends View {

	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final StoneArtist stones = new StoneArtist();
	private final RectF rect = new RectF();

	private String name = "You";
	private String subtitle = "Black";
	private boolean black = true;
	private boolean active, thinking;
	private float phase;
	private ValueAnimator anim;
	private float pad, stoneR;

	PlayerCard(Context c) {
		super(c);
		paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		text.setColor(Theme.MUTED);
		bold.setColor(Theme.TEXT);
		bold.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
	}

	void setName(String s) { name = s; invalidate(); }
	void setSubtitle(String s) { subtitle = s; invalidate(); }
	void setBlack(boolean b) { black = b; invalidate(); }

	void setActive(boolean a) {
		active = a;
		ensureAnim();
		invalidate();
	}

	void setThinking(boolean t) {
		thinking = t;
		ensureAnim();
		invalidate();
	}

	private void ensureAnim() {
		if ((active || thinking) && anim == null) {
			anim = ValueAnimator.ofFloat(0f, 1f);
			anim.setDuration(1100);
			anim.setRepeatCount(ValueAnimator.INFINITE);
			anim.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
			anim.start();
		} else if (!active && !thinking && anim != null) {
			anim.cancel();
			anim = null;
			phase = 0;
		}
	}

	@Override protected void onSizeChanged(int w, int h, int ow, int oh) {
		float d = getResources().getDisplayMetrics().density;
		pad = 14 * d;
		stoneR = h * 0.26f;
		bold.setTextSize(17 * getResources().getDisplayMetrics().scaledDensity);
		text.setTextSize(12.5f * getResources().getDisplayMetrics().scaledDensity);
		stones.radius(stoneR);
		rect.set(0, 0, w, h);
	}

	@Override protected void onDraw(Canvas c) {
		float cr = rect.height() * 0.30f;

		paint.setShader(null);
		paint.setStyle(Paint.Style.FILL);
		if (active) {
			paint.setColor(Theme.CARD_ACTIVE);
			c.drawRoundRect(rect, cr, cr, paint);
		} else {
			paint.setShader(new LinearGradient(0, 0, 0, rect.height(),
					Theme.alpha(0xFFFFFFFF, 0.07f), Theme.alpha(0xFFFFFFFF, 0.02f),
					Shader.TileMode.CLAMP));
			c.drawRoundRect(rect, cr, cr, paint);
		}
		paint.setShader(null);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(active ? 2.2f : 1.2f);
		paint.setColor(active ? Theme.alpha(Theme.GOLD, 0.85f) : Theme.CARD_STROKE);
		c.drawRoundRect(rect, cr, cr, paint);

		float cx = pad + stoneR;
		float cy = rect.height() / 2f;
		stones.draw(c, cx, cy, 1f, black, paint);

		float tx = cx + stoneR + 12 * getResources().getDisplayMetrics().density;
		float baseline = rect.height() / 2f + bold.getTextSize() * 0.34f;
		bold.setColor(active ? Theme.TEXT : 0xFFDDE2EA);
		c.drawText(name, tx, baseline - text.getTextSize() * 0.95f, bold);
		c.drawText(subtitle, tx, baseline + text.getTextSize() * 1.05f, text);

		boolean showDots = thinking;
		if (showDots) {
			float dr = rect.height() * 0.045f;
			float gap = dr * 3.1f;
			float dx = rect.width() - pad - dr;
			float dy = rect.height() / 2f;
			for (int i = 0; i < 3; i++) {
				float a = 0.35f + 0.65f * Math.max(0f,
						(float) Math.sin((phase * 2 * Math.PI) - i * 0.9f));
				paint.setStyle(Paint.Style.FILL);
				paint.setColor(Theme.alpha(Theme.GOLD, a));
				c.drawCircle(dx - (2 - i) * gap, dy, dr, paint);
			}
		} else if (active) {
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(Theme.alpha(Theme.GOLD, 0.55f + 0.45f * (float) Math.sin(phase * 2 * Math.PI)));
			c.drawCircle(rect.width() - pad - rect.height() * 0.05f, rect.height() * 0.5f,
					rect.height() * 0.05f, paint);
		}
	}

	@Override protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (anim != null) { anim.cancel(); anim = null; }
	}
}
