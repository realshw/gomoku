package com.shw.gomoku;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Arrays;

/**
 * Wooden Gomoku board.
 *
 * This view owns the authored state it draws and never reads the engine's live
 * board: `landed` is the committed position (solid stones), `phantomIdx` is the
 * human's hover preview and `searchPhantoms` is the CPU's current search
 * enumeration — both of the latter are faded and purely transient.
 */
public class BoardView extends View {

	public interface Listener { void onCell(int idx); }

	private static final int[] NO_PHANTOMS = new int[0];

	private Listener listener;
	private final StoneArtist stones = new StoneArtist();

	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint winPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	private final RectF gridRect = new RectF();
	private final RectF frameRect = new RectF();
	private float cell, ox, oy, radius;
	private Shader woodShader, sheen;
	private DashPathEffect dash;

	/** committed stones: 0 empty, 1 black, 2 white */
	private final int[] landed = new int[Engine.N * Engine.N];
	private int lastMove = -1;
	private float stoneScale = 1f;
	private ValueAnimator placeAnim;
	private int[] winLine;
	private float winPulse;
	private ValueAnimator winAnim;

	/** human hover preview (faded, render-only) */
	private int phantomIdx = -1;
	private boolean ghostBlack = true;
	/** CPU search enumeration: alternating colours starting from `searchMe` */
	private int[] searchPhantoms = NO_PHANTOMS;
	private int searchMe = Engine.BLACK;

	private boolean inputEnabled = true;

	public BoardView(Context c) {
		super(c);
		stroke.setStyle(Paint.Style.STROKE);
		grain.setStyle(Paint.Style.STROKE);
		grain.setColor(0x1A5A3C1A);
		marker.setStyle(Paint.Style.STROKE);
		winPaint.setStyle(Paint.Style.STROKE);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setColor(0x885A3C1A);
	}

	public void setListener(Listener l) { listener = l; }

	// ------------------------------------------------------- committed state

	public void land(int idx, int color) { landed[idx] = color; }

	public void unland(int idx) { landed[idx] = Engine.EMPTY; }

	public void clearBoard() {
		Arrays.fill(landed, Engine.EMPTY);
		refresh();
	}

	// ---------------------------------------------------------- interactions

	public void setInputEnabled(boolean e) {
		inputEnabled = e;
		if (!e) clearPhantom();
	}

	public void setGhostBlack(boolean b) { ghostBlack = b; }

	/** Show the CPU's current search path as faded phantoms. */
	public void setSearchPhantoms(int[] cells, int me) {
		searchPhantoms = (cells == null) ? NO_PHANTOMS : cells;
		searchMe = me;
	}

	public void clearSearchPhantoms() {
		if (searchPhantoms.length != 0) { searchPhantoms = NO_PHANTOMS; invalidate(); }
	}

	/** Drop the human hover preview (called whenever it could go stale). */
	public void clearPhantom() {
		if (phantomIdx != -1) { phantomIdx = -1; invalidate(); }
	}

	@Override protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		clearPhantom();
	}

	@Override protected void onWindowVisibilityChanged(int v) {
		super.onWindowVisibilityChanged(v);
		if (v != VISIBLE) clearPhantom();
	}

	// ------------------------------------------------------------- animation

	public void setLastMove(int idx) {
		lastMove = idx;
		if (placeAnim != null) placeAnim.cancel();
		stoneScale = 0.45f;
		placeAnim = ValueAnimator.ofFloat(0.45f, 1f);
		placeAnim.setDuration(220);
		placeAnim.setInterpolator(new DecelerateInterpolator(1.7f));
		placeAnim.addUpdateListener(a -> { stoneScale = (float) a.getAnimatedValue(); invalidate(); });
		placeAnim.start();
	}

	public void setWinLine(int[] line) {
		winLine = line;
		if (line == null) {
			if (winAnim != null) { winAnim.cancel(); winAnim = null; }
			winPulse = 0;
			invalidate();
			return;
		}
		if (winAnim == null) {
			winAnim = ValueAnimator.ofFloat(0f, 1f);
			winAnim.setDuration(750);
			winAnim.setRepeatCount(ValueAnimator.INFINITE);
			winAnim.setRepeatMode(ValueAnimator.REVERSE);
			winAnim.addUpdateListener(a -> { winPulse = (float) a.getAnimatedValue(); invalidate(); });
			winAnim.start();
		}
	}

	/** Reset transient overlays (not the committed stones). */
	public void refresh() {
		lastMove = -1;
		phantomIdx = -1;
		searchPhantoms = NO_PHANTOMS;
		if (placeAnim != null) placeAnim.cancel();
		setWinLine(null);
		stoneScale = 1f;
		invalidate();
	}

	// ---------------------------------------------------------------- layout

	@Override protected void onMeasure(int w, int h) {
		int ws = MeasureSpec.getSize(w), hs = MeasureSpec.getSize(h);
		setMeasuredDimension(Math.min(ws, hs), Math.min(ws, hs));
	}

	@Override protected void onSizeChanged(int w, int h, int ow, int oh) {
		super.onSizeChanged(w, h, ow, oh);
		float margin = w * 0.062f;
		cell = (w - 2 * margin) / (Engine.N - 1);
		ox = margin;
		oy = margin;
		radius = cell * 0.44f;
		float fp = cell * 0.64f;
		gridRect.set(ox, oy, ox + cell * (Engine.N - 1), oy + cell * (Engine.N - 1));
		frameRect.set(gridRect.left - fp, gridRect.top - fp,
				gridRect.right + fp, gridRect.bottom + fp);
		stones.radius(radius);
		textPaint.setTextSize(cell * 0.40f);
		dash = new DashPathEffect(new float[]{cell * 0.24f, cell * 0.18f}, 0);
		woodShader = new LinearGradient(frameRect.left, frameRect.top,
				frameRect.right * 0.4f + gridRect.left * 0.6f, frameRect.bottom,
				new int[]{Theme.WOOD_TOP, Theme.WOOD_MID, Theme.WOOD_BOTTOM},
				new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
		sheen = new RadialGradient(frameRect.centerX() - frameRect.width() * 0.20f,
				frameRect.top + frameRect.height() * 0.14f, frameRect.width() * 0.72f,
				new int[]{0x26FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
	}

	// ----------------------------------------------------------------- draw

	@Override protected void onDraw(Canvas c) {
		drawFrameShadow(c);

		fill.setShader(null);
		fill.setColor(Theme.WOOD_FRAME);
		c.drawRoundRect(frameRect, cell * 0.55f, cell * 0.55f, fill);

		RectF wood = new RectF(frameRect);
		wood.inset(cell * 0.16f, cell * 0.16f);
		fill.setShader(woodShader);
		c.drawRoundRect(wood, cell * 0.45f, cell * 0.45f, fill);
		fill.setShader(null);

		grain.setStrokeWidth(1.1f);
		for (int i = 1; i < 22; i++) {
			float y = wood.top + (wood.height() * i / 22f);
			float wave = (float) Math.sin(i * 1.7f) * cell * 0.06f;
			c.drawLine(wood.left + cell * 0.2f, y, wood.right - cell * 0.2f, y + wave, grain);
		}

		fill.setShader(sheen);
		c.drawRoundRect(wood, cell * 0.45f, cell * 0.45f, fill);
		fill.setShader(null);

		stroke.setStrokeWidth(1.6f);
		stroke.setColor(0x40FFFFFF);
		c.drawLine(wood.left + cell * 0.4f, wood.top + 1, wood.right - cell * 0.4f, wood.top + 1, stroke);
		c.drawLine(wood.left + 1, wood.top + cell * 0.4f, wood.left + 1, wood.bottom - cell * 0.4f, stroke);
		stroke.setColor(0x40000000);
		c.drawLine(wood.left + cell * 0.4f, wood.bottom - 1, wood.right - cell * 0.4f, wood.bottom - 1, stroke);
		c.drawLine(wood.right - 1, wood.top + cell * 0.4f, wood.right - 1, wood.bottom - cell * 0.4f, stroke);

		drawGrid(c);
		drawStones(c);
		drawSearchPhantoms(c);
		drawGhost(c);
	}

	private void drawFrameShadow(Canvas c) {
		fill.setShader(null);
		fill.setStyle(Paint.Style.FILL);
		int steps = 8;
		for (int i = steps; i >= 1; i--) {
			float grow = i * cell * 0.13f;
			fill.setColor(Theme.alpha(0xFF000000, 0.05f * (1f - i / (float) (steps + 1))));
			RectF r = new RectF(frameRect);
			r.inset(-grow, -grow + cell * 0.10f);
			r.offset(0, cell * 0.10f);
			c.drawRoundRect(r, cell * 0.55f, cell * 0.55f, fill);
		}
	}

	private void drawGrid(Canvas c) {
		stroke.setStrokeWidth(Math.max(1.3f, cell * 0.028f));
		stroke.setColor(Theme.GRID);
		float x0 = gridRect.left, y0 = gridRect.top, x1 = gridRect.right, y1 = gridRect.bottom;
		for (int i = 0; i < Engine.N; i++) {
			float p = ox + i * cell;
			c.drawLine(p, y0, p, y1, stroke);
			p = oy + i * cell;
			c.drawLine(x0, p, x1, p, stroke);
		}
		stroke.setStrokeWidth(Math.max(2.2f, cell * 0.055f));
		stroke.setColor(0xCC4A3010);
		c.drawRect(gridRect, stroke);

		fill.setColor(0xCC4A3010);
		int[] sx = {3, 11, 3, 11, 7}, sy = {3, 3, 11, 11, 7};
		for (int i = 0; i < 5; i++)
			c.drawCircle(ox + sx[i] * cell, oy + sy[i] * cell, cell * 0.10f, fill);

		for (int i = 0; i < Engine.N; i++) {
			c.drawText(String.valueOf((char) ('A' + i)), ox + i * cell,
					gridRect.bottom + cell * 0.62f, textPaint);
			c.drawText(String.valueOf(i + 1), gridRect.left - cell * 0.54f,
					oy + i * cell + cell * 0.14f, textPaint);
		}
	}

	private void drawStones(Canvas c) {
		int lm = lastMove;
		for (int i = 0; i < landed.length; i++) {
			int v = landed[i];
			if (v == Engine.EMPTY) continue;
			float cx = ox + (i % Engine.N) * cell;
			float cy = oy + (i / Engine.N) * cell;
			stones.draw(c, cx, cy, (i == lm) ? stoneScale : 1f, v == Engine.BLACK, fill);
		}
		if (winLine != null) {
			float ax = ox + (winLine[0] % Engine.N) * cell, ay = oy + (winLine[0] / Engine.N) * cell;
			float bx = ox + (winLine[winLine.length - 1] % Engine.N) * cell;
			float by = oy + (winLine[winLine.length - 1] / Engine.N) * cell;
			winPaint.setStrokeCap(Paint.Cap.ROUND);
			winPaint.setStrokeWidth(cell * 0.16f);
			winPaint.setColor(Theme.alpha(Theme.GOLD, 0.30f + 0.25f * winPulse));
			c.drawLine(ax, ay, bx, by, winPaint);
			winPaint.setStrokeWidth(cell * 0.10f);
			for (int idx : winLine) {
				float cx = ox + (idx % Engine.N) * cell, cy = oy + (idx / Engine.N) * cell;
				winPaint.setColor(Theme.alpha(Theme.GOLD, 0.55f + 0.45f * winPulse));
				c.drawCircle(cx, cy, radius * 1.08f, winPaint);
			}
		}
		if (lm >= 0) {
			float cx = ox + (lm % Engine.N) * cell, cy = oy + (lm / Engine.N) * cell;
			marker.setStrokeWidth(Math.max(2f, radius * 0.16f));
			marker.setColor(0xCCFF6B6B);
			c.drawCircle(cx, cy, radius * 0.44f, marker);
		}
	}

	/**
	 * The CPU's search enumeration: each stone it is currently trying, along the
	 * live search path. Drawn faded (alpha grows with depth, capped well below
	 * solid) with a dashed ring on the deepest node.
	 */
	private void drawSearchPhantoms(Canvas c) {
		int[] p = searchPhantoms;
		if (p.length == 0) return;
		for (int k = 0; k < p.length; k++) {
			int idx = p[k];
			if (idx < 0 || idx >= landed.length) continue;
			if (landed[idx] != Engine.EMPTY) continue; // never overlap a real stone
			boolean black = (((k & 1) == 0) ? searchMe : 3 - searchMe) == Engine.BLACK;
			int alpha = 66 + Math.min(58, k * 7);
			float cx = ox + (idx % Engine.N) * cell, cy = oy + (idx / Engine.N) * cell;
			stones.ghost(c, cx, cy, 0.9f, black, fill, alpha);
		}
		int last = p[p.length - 1];
		if (last >= 0 && last < landed.length && landed[last] == Engine.EMPTY) {
			float cx = ox + (last % Engine.N) * cell, cy = oy + (last / Engine.N) * cell;
			marker.setStrokeWidth(Math.max(1.6f, radius * 0.11f));
			marker.setColor(Theme.alpha(Theme.GOLD, 0.55f));
			marker.setPathEffect(dash);
			c.drawCircle(cx, cy, radius * 0.98f, marker);
			marker.setPathEffect(null);
		}
	}

	private void drawGhost(Canvas c) {
		if (phantomIdx < 0 || phantomIdx >= landed.length) return;
		if (landed[phantomIdx] != Engine.EMPTY) return;
		float cx = ox + (phantomIdx % Engine.N) * cell, cy = oy + (phantomIdx / Engine.N) * cell;
		stones.ghost(c, cx, cy, 0.94f, ghostBlack, fill, 100);
		marker.setStrokeWidth(Math.max(2f, radius * 0.14f));
		marker.setColor(Theme.alpha(Theme.GOLD, 0.7f));
		marker.setPathEffect(dash);
		c.drawCircle(cx, cy, radius * 1.06f, marker);
		marker.setPathEffect(null);
	}

	private int cellAt(float px, float py) {
		int col = Math.round((px - ox) / cell);
		int row = Math.round((py - oy) / cell);
		if (col < 0 || col >= Engine.N || row < 0 || row >= Engine.N) return -1;
		float cx = ox + col * cell, cy = oy + row * cell;
		float dx = px - cx, dy = py - cy;
		if (dx * dx + dy * dy > cell * cell * 0.40f) return -1;
		return row * Engine.N + col;
	}

	@Override public boolean onTouchEvent(MotionEvent ev) {
		if (!inputEnabled) return false;
		switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
			case MotionEvent.ACTION_MOVE:
				phantomIdx = cellAt(ev.getX(), ev.getY());
				invalidate();
				return true;
			case MotionEvent.ACTION_UP: {
				int idx = cellAt(ev.getX(), ev.getY());
				phantomIdx = -1;
				invalidate();
				if (idx >= 0 && landed[idx] == Engine.EMPTY && listener != null) {
					performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
					listener.onCell(idx);
				}
				return true;
			}
			case MotionEvent.ACTION_CANCEL:
				phantomIdx = -1;
				invalidate();
				return true;
		}
		return false;
	}
}
