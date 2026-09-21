package com.shw.gomoku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

	/**
	 * The CPU is one object with three effort states. OFF means the CPU's side is
	 * played by hand (a human impersonating the CPU) — everything else in the UI
	 * still reads You vs CPU; only the button changes.
	 */
	private static final int CPU_OFF = 0, CPU_FAST = 1, CPU_HARD = 2;
	private static final int FAST_MS = 450, HARD_MS = 1500;

	private final Engine engine = new Engine();
	private final List<Integer> history = new ArrayList<>();

	private BoardView board;
	private TextView cpuBtn;
	private SharedPreferences prefs;

	private int humanColor = Engine.BLACK;
	private int youWins, cpuWins;
	private int cpuMode = CPU_HARD;
	private boolean thinking, gameOver;

	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	private int[] lastPhantom = new int[0];
	private long lastTick;
	/** While the CPU thinks: sample its search path, show it faded, and tick softly. */
	private final Runnable phantomPoller = new Runnable() {
		@Override public void run() {
			if (!thinking) return;
			int[] snap = engine.phantomSnapshot();
			board.setSearchPhantoms(snap, 3 - humanColor);
			board.invalidate();
			long now = System.nanoTime();
			if (snap.length > 0 && snap != lastPhantom && now - lastTick > 70_000_000L) {
				lastPhantom = snap;
				lastTick = now;
				Haptics.tick(board);
			}
			uiHandler.postDelayed(this, 40);
		}
	};

	@Override protected void onCreate(Bundle b) {
		super.onCreate(b);
		prefs = getSharedPreferences("gomoku", MODE_PRIVATE);
		humanColor = prefs.getInt("human", Engine.BLACK);
		youWins = prefs.getInt("you", 0);
		cpuWins = prefs.getInt("cpu", 0);
		cpuMode = prefs.getInt("cpuMode", CPU_HARD);
		buildUi();
		newGame();
	}

	@Override protected void onPause() {
		super.onPause();
		uiHandler.removeCallbacks(phantomPoller);
		board.clearPhantom();
		board.clearSearchPhantoms();
	}

	private boolean cpuOn() { return cpuMode != CPU_OFF; }

	/**
	 * Invariant: the engine's committed board must match the move history, and
	 * its incremental evaluation must match a recomputation. Phantom stones
	 * (search placements, hover preview) must never survive into this state.
	 */
	private void checkCommitted() {
		engine.verify();
		if (engine.stoneCount() != history.size())
			throw new IllegalStateException("committed stones " + engine.stoneCount()
					+ " != move history " + history.size());
	}

	private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

	private void toast(String msg) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
	}

	// ------------------------------------------------------------------ layout

	private void buildUi() {
		FrameLayout root = new FrameLayout(this);
		root.addView(new Backdrop(this), new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

		LinearLayout column = new LinearLayout(this);
		column.setOrientation(LinearLayout.VERTICAL);
		column.setGravity(Gravity.CENTER_VERTICAL);
		root.addView(column, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		applyInsets(root, column);

		// top controls: New, Swap (snug above the board)
		LinearLayout top = new LinearLayout(this);
		top.setOrientation(LinearLayout.HORIZONTAL);
		top.addView(pill("New", this::requestNewGame));
		top.addView(pill("Swap", this::requestSwapSides));
		LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		tlp.bottomMargin = (int) dp(10);
		column.addView(top, tlp);

		FrameLayout middle = new FrameLayout(this);
		board = new BoardView(this);
		board.setListener(this::onHumanCell);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
		blp.gravity = Gravity.CENTER;
		middle.addView(board, blp);
		column.addView(middle);

		// bottom controls: Undo, CPU (snug below the board)
		LinearLayout bottom = new LinearLayout(this);
		bottom.setOrientation(LinearLayout.HORIZONTAL);
		bottom.addView(pill("Undo", this::undo));
		cpuBtn = buttonBase("Hard");
		cpuBtn.setOnClickListener(v -> cycleCpu());
		bottom.addView(cpuBtn);
		LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		blp2.topMargin = (int) dp(10);
		column.addView(bottom, blp2);

		setContentView(root);
	}

	private void applyInsets(View root, LinearLayout column) {
		final int l = (int) dp(16), t = (int) dp(14), r = (int) dp(16), b = (int) dp(18);
		root.setOnApplyWindowInsetsListener((v, insets) -> {
			int sl = 0, st = 0, sr = 0, sb = 0;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
				sl = bars.left; st = bars.top; sr = bars.right; sb = bars.bottom;
			} else {
				sl = insets.getSystemWindowInsetLeft();
				st = insets.getSystemWindowInsetTop();
				sr = insets.getSystemWindowInsetRight();
				sb = insets.getSystemWindowInsetBottom();
			}
			column.setPadding(l + sl, t + st, r + sr, b + sb);
			return insets;
		});
		root.requestApplyInsets();
	}

	/** A single-word button: shared sizing, no icon. */
	private TextView buttonBase(String label) {
		TextView t = new TextView(this);
		t.setText(label);
		t.setTextSize(15);
		t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
		t.setTextColor(0xFFC9D0DA);
		t.setGravity(Gravity.CENTER);
		t.setPadding(0, (int) dp(16), 0, (int) dp(16));
		t.setClickable(true);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		lp.setMargins((int) dp(5), 0, (int) dp(5), 0);
		t.setLayoutParams(lp);
		return t;
	}

	private TextView pill(String label, Runnable action) {
		TextView t = buttonBase(label);
		t.setOnClickListener(v -> action.run());
		t.setBackground(pillBackground());
		return t;
	}

	private StateListDrawable pillBackground() {
		GradientDrawable normal = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{0x1AFFFFFF, 0x0AFFFFFF});
		normal.setCornerRadius(dp(18));
		normal.setStroke((int) dp(1), 0x26FFFFFF);
		GradientDrawable pressed = new GradientDrawable();
		pressed.setColor(0x33FFD166);
		pressed.setCornerRadius(dp(18));
		pressed.setStroke((int) dp(1), 0x88FFD166);
		StateListDrawable s = new StateListDrawable();
		s.addState(new int[]{android.R.attr.state_pressed}, pressed);
		s.addState(new int[]{}, normal);
		return s;
	}

	/** Paint the CPU button for its state: off (quiet) -> fast (outlined) -> hard (solid). */
	private void applyCpuStyle() {
		GradientDrawable g = new GradientDrawable();
		g.setCornerRadius(dp(18));
		switch (cpuMode) {
			case CPU_OFF:
				g.setColor(0x14FFFFFF);
				g.setStroke((int) dp(1), 0x26FFFFFF);
				cpuBtn.setText("Off");
				cpuBtn.setTextColor(Theme.GOLD);
				break;
			case CPU_FAST:
				g.setColor(0x1FFFD166);
				g.setStroke((int) dp(1), 0x88FFD166);
				cpuBtn.setText("Fast");
				cpuBtn.setTextColor(Theme.GOLD);
				break;
			default:
				g.setColor(Theme.GOLD);
				g.setStroke((int) dp(1), Theme.GOLD);
				cpuBtn.setText("Hard");
				cpuBtn.setTextColor(0xFF0A0E14);
				break;
		}
		cpuBtn.setBackground(g);
	}

	// --------------------------------------------------------------- game flow

	private int centre() { return (Engine.N / 2) * Engine.N + Engine.N / 2; }

	private int sideToMove() { return history.size() % 2 == 0 ? Engine.BLACK : Engine.WHITE; }

	/** Cycle the one CPU through Off -> Fast -> Hard. Never restarts the game. */
	private void cycleCpu() {
		cpuMode = (cpuMode + 1) % 3;
		prefs.edit().putInt("cpuMode", cpuMode).apply();
		updateUi();
		if (cpuOn() && !gameOver && !thinking && sideToMove() != humanColor) think();
	}

	/** Confirm before wiping the board; skip when it is already empty. */
	private void requestNewGame() {
		if (history.isEmpty()) newGame();
		else confirm("New game?", "Discard the current game and start over.", this::newGame);
	}

	private void requestSwapSides() {
		if (history.isEmpty()) swapSides();
		else confirm("Swap sides?", "Start a new game with the other colour.", this::swapSides);
	}

	private void confirm(String title, String message, Runnable action) {
		new AlertDialog.Builder(this)
				.setTitle(title)
				.setMessage(message)
				.setNegativeButton("Cancel", null)
				.setPositiveButton("Confirm", (d, w) -> action.run())
				.show();
	}

	private void newGame() {
		engine.reset();
		history.clear();
		gameOver = false;
		thinking = false;
		uiHandler.removeCallbacks(phantomPoller);
		board.clearBoard();
		if (cpuOn() && humanColor == Engine.WHITE) {
			engine.place(centre(), Engine.BLACK);
			history.add(centre());
			board.land(centre(), Engine.BLACK);
			board.setLastMove(centre());
		}
		updateUi();
	}

	private void onHumanCell(int idx) {
		if (gameOver || thinking) return;
		if (cpuOn() && sideToMove() != humanColor) return;
		int me = sideToMove();
		play(idx, me);
		if (finishIfWon(idx, me)) return;
		if (history.size() == Engine.N * Engine.N) { draw(); return; }
		if (cpuOn() && sideToMove() != humanColor) {
			think();
		} else {
			updateUi();
		}
	}

	private void play(int idx, int color) {
		engine.place(idx, color);
		history.add(idx);
		board.land(idx, color);
		board.setLastMove(idx);
	}

	private boolean finishIfWon(int idx, int color) {
		int[] line = engine.fiveLine(idx, color);
		if (line == null) return false;
		gameOver = true;
		board.setWinLine(line);
		if (color == humanColor) {
			youWins++;
			toast("You win!");
		} else {
			cpuWins++;
			toast("CPU wins");
		}
		prefs.edit().putInt("you", youWins).putInt("cpu", cpuWins).apply();
		updateUi();
		return true;
	}

	private void draw() {
		gameOver = true;
		toast("Draw");
		updateUi();
	}

	private void think() {
		thinking = true;
		updateUi();
		lastPhantom = new int[0];
		final int me = 3 - humanColor;
		final boolean hard = cpuMode == CPU_HARD;
		engine.useThreatSolvers = hard;
		final int budget = hard ? HARD_MS : FAST_MS;
		uiHandler.postDelayed(phantomPoller, 16);
		new Thread(() -> {
			int m = engine.bestMove(me, budget);
			runOnUiThread(() -> {
				thinking = false;
				uiHandler.removeCallbacks(phantomPoller);
				board.clearSearchPhantoms();
				if (gameOver || !cpuOn()) { updateUi(); return; }
				if (m < 0 || engine.get(m) != Engine.EMPTY) { updateUi(); return; }
				play(m, me);
				Haptics.land(board);
				if (finishIfWon(m, me)) return;
				if (history.size() == Engine.N * Engine.N) { draw(); return; }
				updateUi();
			});
		}, "gomoku-ai").start();
	}

	private void undo() {
		if (thinking || history.isEmpty()) return;
		gameOver = false;
		board.setWinLine(null);
		if (cpuOn()) {
			do {
				int idx = history.remove(history.size() - 1);
				engine.take(idx);
				board.unland(idx);
			} while (!history.isEmpty() && sideToMove() != humanColor);
		} else {
			int idx = history.remove(history.size() - 1);
			engine.take(idx);
			board.unland(idx);
		}
		board.refresh();
		if (!history.isEmpty()) board.setLastMove(history.get(history.size() - 1));
		if (cpuOn() && sideToMove() != humanColor) {
			think();
		} else {
			updateUi();
		}
	}

	private void swapSides() {
		humanColor = 3 - humanColor;
		prefs.edit().putInt("human", humanColor).apply();
		newGame();
	}

	private void updateUi() {
		int turn = sideToMove();
		board.setGhostBlack(turn == Engine.BLACK);
		board.setInputEnabled(!gameOver && !thinking && (!cpuOn() || turn == humanColor));
		applyCpuStyle();

		if (!thinking) {
			uiHandler.removeCallbacks(phantomPoller);
			board.clearSearchPhantoms();
		}
		// only safe to inspect the board when the search thread is not running
		if (!thinking) checkCommitted();
	}
}
