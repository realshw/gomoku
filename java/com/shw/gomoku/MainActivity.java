package com.shw.gomoku;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

	/** think budgets: deep runs the threat solvers too, fast skips them */
	private static final int DEEP_MS = 1500;
	private static final int FAST_MS = 450;

	private final Engine engine = new Engine();
	private final List<Integer> history = new ArrayList<>();

	private BoardView board;
	private PlayerCard youCard, cpuCard;
	private TextView status, scoreValue;
	private View scoreBoxView, swapBtn;
	private LinearLayout twoPlayerBtn, deepBtn;
	private SharedPreferences prefs;

	private int humanColor = Engine.BLACK;
	private int youWins, cpuWins;
	private boolean thinking, gameOver;
	private boolean twoPlayer;
	private boolean deepReasoning = true;

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
		twoPlayer = prefs.getBoolean("twoPlayer", false);
		deepReasoning = prefs.getBoolean("deep", true);
		buildUi();
		newGame();
	}

	@Override protected void onPause() {
		super.onPause();
		uiHandler.removeCallbacks(phantomPoller);
		board.clearPhantom();
		board.clearSearchPhantoms();
	}

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

	// ------------------------------------------------------------------ layout

	private void buildUi() {
		FrameLayout root = new FrameLayout(this);
		root.addView(new Backdrop(this), new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

		LinearLayout column = new LinearLayout(this);
		column.setOrientation(LinearLayout.VERTICAL);
		root.addView(column, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		applyInsets(root, column);

		// header: side | score | side
		LinearLayout header = new LinearLayout(this);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);

		youCard = new PlayerCard(this);
		cpuCard = new PlayerCard(this);
		cpuCard.setName("CPU");

		int cardH = (int) dp(76);
		header.addView(youCard, new LinearLayout.LayoutParams(0, cardH, 1f));
		scoreBoxView = scoreBox();
		header.addView(scoreBoxView, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, cardH));
		header.addView(cpuCard, new LinearLayout.LayoutParams(0, cardH, 1f));
		column.addView(header);

		status = new TextView(this);
		status.setTextSize(14);
		status.setTextColor(Theme.MUTED);
		status.setGravity(Gravity.CENTER);
		status.setPadding(0, (int) dp(14), 0, (int) dp(6));
		column.addView(status);

		FrameLayout middle = new FrameLayout(this);
		board = new BoardView(this);
		board.setListener(this::onHumanCell);
		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
		blp.gravity = Gravity.CENTER;
		middle.addView(board, blp);
		column.addView(middle, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		// controls: a row of actions, then a row of toggles
		LinearLayout controls = new LinearLayout(this);
		controls.setOrientation(LinearLayout.VERTICAL);
		controls.setPadding(0, (int) dp(14), 0, 0);

		LinearLayout actions = new LinearLayout(this);
		actions.setOrientation(LinearLayout.HORIZONTAL);
		actions.addView(pill("↺", "New Game", this::newGame));
		actions.addView(pill("↶", "Undo", this::undo));
		swapBtn = pill("⇄", "Swap Sides", this::swapSides);
		actions.addView(swapBtn);
		controls.addView(actions);

		LinearLayout toggles = new LinearLayout(this);
		toggles.setOrientation(LinearLayout.HORIZONTAL);
		toggles.setPadding(0, (int) dp(8), 0, 0);
		twoPlayerBtn = toggle("♟", "Two Players", () -> {
			twoPlayer = !twoPlayer;
			prefs.edit().putBoolean("twoPlayer", twoPlayer).apply();
			newGame();
		});
		deepBtn = toggle("✦", "Think Hard", () -> {
			deepReasoning = !deepReasoning;
			prefs.edit().putBoolean("deep", deepReasoning).apply();
			updateToggles();
		});
		toggles.addView(twoPlayerBtn);
		toggles.addView(deepBtn);
		controls.addView(toggles);
		column.addView(controls);

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

	private View scoreBox() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setGravity(Gravity.CENTER);
		box.setPadding((int) dp(12), 0, (int) dp(12), 0);

		scoreValue = new TextView(this);
		scoreValue.setTextSize(22);
		scoreValue.setTextColor(Theme.GOLD);
		scoreValue.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
		scoreValue.setGravity(Gravity.CENTER);
		box.addView(scoreValue);

		TextView cap = new TextView(this);
		cap.setText("SCORE");
		cap.setTextSize(9);
		cap.setLetterSpacing(0.22f);
		cap.setTextColor(Theme.MUTED);
		cap.setGravity(Gravity.CENTER);
		box.addView(cap);
		return box;
	}

	/** Build the shared icon+label body of an action button or a toggle. */
	private LinearLayout buttonBody(String glyph, String label, Runnable action) {
		LinearLayout b = new LinearLayout(this);
		b.setOrientation(LinearLayout.VERTICAL);
		b.setGravity(Gravity.CENTER);
		b.setPadding((int) dp(6), (int) dp(11), (int) dp(6), (int) dp(11));
		b.setClickable(true);
		b.setOnClickListener(v -> action.run());

		TextView icon = new TextView(this);
		icon.setText(glyph);
		icon.setTextSize(20);
		icon.setTextColor(Theme.GOLD);
		icon.setGravity(Gravity.CENTER);
		b.addView(icon);

		TextView name = new TextView(this);
		name.setText(label);
		name.setTextSize(12);
		name.setTextColor(0xFFC9D0DA);
		name.setGravity(Gravity.CENTER);
		name.setPadding(0, (int) dp(3), 0, 0);
		b.addView(name);

		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
				LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
		lp.setMargins((int) dp(5), 0, (int) dp(5), 0);
		b.setLayoutParams(lp);
		return b;
	}

	private View pill(String glyph, String label, Runnable action) {
		LinearLayout b = buttonBody(glyph, label, action);
		b.setBackground(pillBackground());
		return b;
	}

	private LinearLayout toggle(String glyph, String label, Runnable action) {
		return buttonBody(glyph, label, action);
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

	/** A toggle reads gold-filled when on, translucent when off. */
	private void applyToggle(LinearLayout b, boolean on) {
		GradientDrawable g = new GradientDrawable();
		g.setCornerRadius(dp(18));
		if (on) {
			g.setColor(Theme.GOLD);
			g.setStroke((int) dp(1), Theme.GOLD);
		} else {
			g.setColor(0x14FFFFFF);
			g.setStroke((int) dp(1), 0x26FFFFFF);
		}
		b.setBackground(g);
		((TextView) b.getChildAt(0)).setTextColor(on ? 0xFF0A0E14 : Theme.GOLD);
		((TextView) b.getChildAt(1)).setTextColor(on ? 0xFF0A0E14 : 0xFFC9D0DA);
	}

	private void setControlEnabled(View v, boolean en) {
		v.setEnabled(en);
		v.setClickable(en);
		v.setAlpha(en ? 1f : 0.35f);
	}

	private void updateToggles() {
		applyToggle(twoPlayerBtn, twoPlayer);
		applyToggle(deepBtn, deepReasoning);
		// Swap Sides and Think Hard only mean something against the CPU
		setControlEnabled(swapBtn, !twoPlayer);
		setControlEnabled(deepBtn, !twoPlayer);
	}

	// --------------------------------------------------------------- game flow

	private int centre() { return (Engine.N / 2) * Engine.N + Engine.N / 2; }

	private int sideToMove() { return history.size() % 2 == 0 ? Engine.BLACK : Engine.WHITE; }

	private String turnText() {
		return sideToMove() == Engine.BLACK
				? "Player 1 (Black) to move." : "Player 2 (White) to move.";
	}

	private void newGame() {
		engine.reset();
		history.clear();
		gameOver = false;
		thinking = false;
		uiHandler.removeCallbacks(phantomPoller);
		board.clearBoard();
		if (twoPlayer) {
			status.setText("Two players — Player 1 (Black) starts.");
		} else if (humanColor == Engine.WHITE) {
			engine.place(centre(), Engine.BLACK);
			history.add(centre());
			board.land(centre(), Engine.BLACK);
			board.setLastMove(centre());
			status.setText("CPU opened in the centre.");
		} else {
			status.setText("Your move — place a stone.");
		}
		updateUi();
	}

	private void onHumanCell(int idx) {
		if (gameOver || thinking) return;
		if (!twoPlayer && sideToMove() != humanColor) return;
		int me = sideToMove();
		play(idx, me);
		if (finishIfWon(idx, me)) return;
		if (history.size() == Engine.N * Engine.N) { draw(); return; }
		if (twoPlayer) {
			status.setText(turnText());
			updateUi();
		} else {
			status.setText("CPU is thinking…");
			updateUi();
			think();
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
		if (twoPlayer) {
			status.setText(color == Engine.BLACK ? "Player 1 wins! 🎉" : "Player 2 wins! 🎉");
		} else if (color == humanColor) {
			youWins++;
			status.setText("You win! 🎉");
			prefs.edit().putInt("you", youWins).putInt("cpu", cpuWins).apply();
		} else {
			cpuWins++;
			status.setText("CPU wins. Rematch?");
			prefs.edit().putInt("you", youWins).putInt("cpu", cpuWins).apply();
		}
		updateUi();
		return true;
	}

	private void draw() {
		gameOver = true;
		status.setText("Draw.");
		updateUi();
	}

	private void think() {
		thinking = true;
		updateUi();
		lastPhantom = new int[0];
		final int me = 3 - humanColor;
		final int budget = deepReasoning ? DEEP_MS : FAST_MS;
		engine.useThreatSolvers = deepReasoning;
		uiHandler.postDelayed(phantomPoller, 16);
		new Thread(() -> {
			int m = engine.bestMove(me, budget);
			runOnUiThread(() -> {
				thinking = false;
				uiHandler.removeCallbacks(phantomPoller);
				board.clearSearchPhantoms();
				if (gameOver) { updateUi(); return; }
				if (m < 0 || engine.get(m) != Engine.EMPTY) { updateUi(); return; }
				play(m, me);
				Haptics.land(board);
				if (finishIfWon(m, me)) return;
				if (history.size() == Engine.N * Engine.N) { draw(); return; }
				status.setText("Your move.");
				updateUi();
			});
		}, "gomoku-ai").start();
	}

	private void undo() {
		if (thinking || history.isEmpty()) return;
		gameOver = false;
		board.setWinLine(null);
		if (twoPlayer) {
			int idx = history.remove(history.size() - 1);
			engine.take(idx);
			board.unland(idx);
		} else {
			do {
				int idx = history.remove(history.size() - 1);
				engine.take(idx);
				board.unland(idx);
			} while (!history.isEmpty() && sideToMove() != humanColor);
		}
		board.refresh();
		if (!history.isEmpty()) board.setLastMove(history.get(history.size() - 1));
		if (!twoPlayer && sideToMove() != humanColor) {
			status.setText("CPU is thinking…");
			updateUi();
			think();
		} else {
			status.setText(twoPlayer ? turnText() : "Undone. Your move.");
			updateUi();
		}
	}

	private void swapSides() {
		if (twoPlayer) return;
		humanColor = 3 - humanColor;
		prefs.edit().putInt("human", humanColor).apply();
		newGame();
	}

	private void updateUi() {
		int turn = sideToMove();
		if (twoPlayer) {
			boolean blackTurn = turn == Engine.BLACK;
			youCard.setName("Player 1");
			youCard.setSubtitle("Black");
			youCard.setBlack(true);
			youCard.setActive(!gameOver && blackTurn);
			youCard.setThinking(false);
			cpuCard.setName("Player 2");
			cpuCard.setSubtitle("White");
			cpuCard.setBlack(false);
			cpuCard.setActive(!gameOver && !blackTurn);
			cpuCard.setThinking(false);
			board.setGhostBlack(blackTurn);
			board.setInputEnabled(!gameOver && !thinking);
		} else {
			boolean yours = !gameOver && !thinking && turn == humanColor;
			boolean cpus = !gameOver && (thinking || turn != humanColor);
			youCard.setName("You");
			youCard.setBlack(humanColor == Engine.BLACK);
			youCard.setSubtitle(humanColor == Engine.BLACK ? "Black" : "White");
			youCard.setActive(yours);
			youCard.setThinking(false);
			cpuCard.setName("CPU");
			cpuCard.setBlack(humanColor != Engine.BLACK);
			cpuCard.setSubtitle(humanColor == Engine.BLACK ? "White" : "Black");
			cpuCard.setActive(cpus);
			cpuCard.setThinking(thinking && !gameOver);
			board.setGhostBlack(humanColor == Engine.BLACK);
			board.setInputEnabled(yours);
		}
		scoreBoxView.setVisibility(twoPlayer ? View.GONE : View.VISIBLE);
		scoreValue.setText(youWins + " : " + cpuWins);
		updateToggles();
		if (!thinking) {
			uiHandler.removeCallbacks(phantomPoller);
			board.clearSearchPhantoms();
		}
		// only safe to inspect the board when the search thread is not running
		if (!thinking) checkCommitted();
	}
}
