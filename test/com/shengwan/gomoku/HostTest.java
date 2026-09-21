package com.shengwan.gomoku;

/** Host-side validation of Engine (no Android). */
public class HostTest {
	static int failures = 0;

	static void check(String name, boolean ok) {
		System.out.println((ok ? "PASS " : "FAIL ") + name);
		if (!ok) failures++;
	}

	static int idx(int x, int y) { return y * Engine.N + x; }

	static boolean hasFive(Engine e, int p) {
		for (int i = 0; i < Engine.N * Engine.N; i++)
			if (e.get(i) == p && e.isFive(i, p)) return true;
		return false;
	}

	/** count distinct empty cells that would complete five for p */
	static int winningPointCount(Engine e, int p) {
		int n = 0;
		for (int i = 0; i < Engine.N * Engine.N; i++) {
			if (e.get(i) != Engine.EMPTY) continue;
			e.place(i, p);
			boolean w = e.isFive(i, p);
			e.take(i);
			if (w) n++;
		}
		return n;
	}

	public static void main(String[] args) {
		// 1. empty board -> centre
		{
			Engine e = new Engine();
			int m = e.bestMove(Engine.BLACK, 1000);
			check("empty board -> centre", m == idx(7, 7));
		}
		// 2. complete own five
		{
			Engine e = new Engine();
			e.place(idx(7, 7), Engine.BLACK);
			e.place(idx(8, 7), Engine.BLACK);
			e.place(idx(9, 7), Engine.BLACK);
			e.place(idx(10, 7), Engine.BLACK);
			e.place(idx(7, 8), Engine.WHITE);
			e.place(idx(8, 8), Engine.WHITE);
			int m = e.bestMove(Engine.BLACK, 1000);
			e.place(m, Engine.BLACK);
			check("completes its own five", e.isFive(m, Engine.BLACK));
		}
		// 3. block a lone winning point
		{
			Engine e = new Engine();
			for (int x = 0; x < 4; x++) e.place(idx(x, 7), Engine.WHITE);
			e.place(idx(7, 7), Engine.BLACK);
			e.place(idx(8, 8), Engine.BLACK);
			int m = e.bestMove(Engine.BLACK, 1000);
			check("blocks the single winning point", m == idx(4, 7));
		}
		// 4. from an open three, make the open four (forced win)
		{
			Engine e = new Engine();
			e.place(idx(7, 7), Engine.BLACK);
			e.place(idx(8, 7), Engine.BLACK);
			e.place(idx(9, 7), Engine.BLACK);
			e.place(idx(7, 3), Engine.WHITE);
			e.place(idx(8, 3), Engine.WHITE);
			int m = e.bestMove(Engine.BLACK, 2000);
			e.place(m, Engine.BLACK);
			check("open three -> creates double threat",
					winningPointCount(e, Engine.BLACK) >= 2);
		}
		// 5. never returns an occupied cell; survives a long random game
		{
			Engine e = new Engine();
			java.util.Random rnd = new java.util.Random(42);
			boolean ok = true;
			for (int ply = 0; ply < 60 && ok; ply++) {
				int me = (ply % 2 == 0) ? Engine.BLACK : Engine.WHITE;
				int m = e.bestMove(me, 40);
				if (m < 0 || e.get(m) != Engine.EMPTY) { ok = false; break; }
				e.place(m, me);
				if (hasFive(e, me)) break;
			}
			check("legal moves through a long game", ok);
		}
		// 6. bestMove must leave the board untouched (search must undo cleanly)
		{
			Engine e = new Engine();
			int[] setup = {idx(7, 7), idx(8, 7), idx(9, 7), idx(7, 8), idx(8, 8), idx(6, 9)};
			for (int i = 0; i < setup.length; i++)
				e.place(setup[i], i % 2 == 0 ? Engine.BLACK : Engine.WHITE);
			e.bestMove(Engine.BLACK, 400);
			boolean same = true;
			for (int i = 0; i < Engine.N * Engine.N; i++) {
				boolean was = false;
				for (int s : setup) if (s == i) was = true;
				boolean is = e.get(i) != Engine.EMPTY;
				if (was != is) same = false;
			}
			check("bestMove leaves the board unchanged", same);
		}
		// 7. must block an opponent open three (else it becomes an open four)
		{
			Engine e = new Engine();
			e.place(idx(7, 7), Engine.WHITE);
			e.place(idx(8, 7), Engine.WHITE);
			e.place(idx(9, 7), Engine.WHITE);
			e.place(idx(0, 0), Engine.BLACK);
			e.place(idx(1, 1), Engine.BLACK);
			int m = e.bestMove(Engine.BLACK, 2000);
			check("blocks an opponent open three", m == idx(6, 7) || m == idx(10, 7));
		}
		// 8. strength: beat a greedy opponent, alternating colours
		{
			int engineWins = 0, games = 8;
			for (int g = 0; g < games; g++) {
				boolean engineBlack = g % 2 == 0;
				int winner = playGreedy(engineBlack);
				if ((winner == Engine.BLACK) == engineBlack && winner != 0) engineWins++;
			}
			check("beats greedy opponent " + engineWins + "/" + games,
					engineWins == games);
		}
		// 9. search must never leak phantom stones, even when it times out mid-tree
		{
			Engine e = new Engine();
			java.util.Random rnd = new java.util.Random(7);
			boolean ok = true;
			for (int trial = 0; trial < 40 && ok; trial++) {
				e.reset();
				int n = 2 + rnd.nextInt(14);
				for (int i = 0; i < n; i++) {
					int idx;
					do { idx = rnd.nextInt(Engine.N * Engine.N); } while (e.get(idx) != Engine.EMPTY);
					e.place(idx, (i % 2 == 0) ? Engine.BLACK : Engine.WHITE);
				}
				String before = e.signature();
				int beforeCount = e.stoneCount();
				int m = e.bestMove((n % 2 == 0) ? Engine.BLACK : Engine.WHITE, rnd.nextInt(3));
				if (e.stoneCount() != beforeCount || !e.signature().equals(before)) { ok = false; break; }
				if (m < 0 || m >= Engine.N * Engine.N || e.get(m) != Engine.EMPTY) { ok = false; break; }
				e.verify();
				e.verifyHash();
			}
			check("search leaks no phantom stones on forced timeouts", ok);
		}
		// 10. the verifier itself must catch corruption (not a vacuous probe)
		{
			Engine e = new Engine();
			e.place(idx(7, 7), Engine.BLACK);
			boolean threw = false;
			try {
				java.lang.reflect.Field f = Engine.class.getDeclaredField("total");
				f.setAccessible(true);
				f.setInt(e, f.getInt(e) + 7);
				e.verify();
			} catch (IllegalStateException ex) {
				threw = true;
			} catch (Exception ex) {
				// reflection unavailable: ignore
			}
			check("verify() catches a corrupted incremental score", threw);
		}
		// 11. the engine publishes its live search path while thinking, then clears it
		{
			Engine e = new Engine();
			int[] setup = {idx(7, 7), idx(8, 8), idx(6, 6), idx(9, 7), idx(7, 9),
					idx(5, 8), idx(8, 5), idx(6, 10), idx(10, 6), idx(4, 4)};
			for (int i = 0; i < setup.length; i++)
				e.place(setup[i], (i % 2 == 0) ? Engine.BLACK : Engine.WHITE);
			Thread t = new Thread(() -> e.bestMove(Engine.BLACK, 700));
			t.start();
			boolean saw = false;
			int maxLen = 0;
			long end = System.currentTimeMillis() + 3000;
			while (t.isAlive() && System.currentTimeMillis() < end) {
				int[] p = e.phantomSnapshot();
				if (p != null && p.length > 0) { saw = true; maxLen = Math.max(maxLen, p.length); }
				try { Thread.sleep(1); } catch (InterruptedException ignored) { }
			}
			try { t.join(); } catch (InterruptedException ignored) { }
			int[] after = e.phantomSnapshot();
			check("engine publishes live search path (" + maxLen + " deep) and clears it",
					saw && maxLen >= 1 && after.length == 0);
		}
		// 12. transposition table: same answer, fewer nodes at fixed depth
		{
			Engine e = new Engine();
			int[] setup = {idx(7, 7), idx(8, 8), idx(6, 6), idx(9, 7), idx(7, 9),
					idx(5, 8), idx(8, 5), idx(6, 10), idx(10, 6), idx(4, 4)};
			for (int i = 0; i < setup.length; i++)
				e.place(setup[i], (i % 2 == 0) ? Engine.BLACK : Engine.WHITE);
			e.ttEnabled = true;
			int nTT = e.benchFixedDepth(Engine.BLACK, 6);
			int mTT = e.lastBenchMove;
			e.ttEnabled = false;
			int nNo = e.benchFixedDepth(Engine.BLACK, 6);
			int mNo = e.lastBenchMove;
			check("TT cuts nodes at fixed depth (" + nNo + " -> " + nTT + ")", nTT < nNo);
			check("TT keeps the same best move", mTT == mNo);
		}
		// 13. Zobrist hash always matches the board
		{
			Engine e = new Engine();
			java.util.Random rnd = new java.util.Random(11);
			boolean ok = true;
			for (int i = 0; i < 40 && ok; i++) {
				int idx;
				do { idx = rnd.nextInt(Engine.N * Engine.N); } while (e.get(idx) != Engine.EMPTY);
				e.place(idx, (i % 2 == 0) ? Engine.BLACK : Engine.WHITE);
				try { e.verifyHash(); } catch (IllegalStateException ex) { ok = false; }
			}
			check("Zobrist hash matches the board after every move", ok);
		}
		// 14. known-answer: at fixed depth the search must pick a truly optimal move
		// (this is what caught the shared-scratch-buffer aliasing bug)
		{
			Engine e = new Engine();
			int[] setup = {idx(7, 7), idx(8, 8), idx(6, 6), idx(9, 7), idx(7, 9),
					idx(5, 8), idx(8, 5), idx(6, 10), idx(10, 6), idx(4, 4)};
			for (int i = 0; i < setup.length; i++)
				e.place(setup[i], (i % 2 == 0) ? Engine.BLACK : Engine.WHITE);
			e.ttEnabled = true;
			e.benchFixedDepth(Engine.BLACK, 6);
			int chosen = e.lastBenchMove;
			int vChosen = e.evalMove(Engine.BLACK, chosen, 6);
			int trueBest = -Integer.MAX_VALUE / 2;
			for (int c = 0; c < Engine.N * Engine.N; c++) {
				if (e.get(c) != Engine.EMPTY) continue;
				int v = e.evalMove(Engine.BLACK, c, 6);
				if (v > trueBest) trueBest = v;
			}
			check("search picks an optimal root move (" + vChosen + " == " + trueBest + ")",
					vChosen == trueBest);
		}
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
		if (failures != 0) System.exit(1);
	}

	/** Engine vs greedy (win/block/centre-ish), engine plays `engineBlack`. Returns winner colour or 0. */
	static int playGreedy(boolean engineBlack) {
		Engine e = new Engine();
		int engine = engineBlack ? Engine.BLACK : Engine.WHITE;
		for (int ply = 0; ply < 225; ply++) {
			int me = (ply % 2 == 0) ? Engine.BLACK : Engine.WHITE;
			int m;
			if (me == engine) {
				m = e.bestMove(me, 200);
			} else {
				m = greedy(e, me);
			}
			e.place(m, me);
			if (e.isFive(m, me)) return me;
		}
		return 0;
	}

	static int greedy(Engine e, int me) {
		int opp = 3 - me;
		// win
		for (int i = 0; i < 225; i++) {
			if (e.get(i) != Engine.EMPTY) continue;
			e.place(i, me);
			boolean w = e.isFive(i, me);
			e.take(i);
			if (w) return i;
		}
		// block
		for (int i = 0; i < 225; i++) {
			if (e.get(i) != Engine.EMPTY) continue;
			e.place(i, opp);
			boolean w = e.isFive(i, opp);
			e.take(i);
			if (w) return i;
		}
		// nearest empty to centre
		int best = -1, bestD = Integer.MAX_VALUE;
		for (int i = 0; i < 225; i++) {
			if (e.get(i) != Engine.EMPTY) continue;
			int dx = i % Engine.N - 7, dy = i / Engine.N - 7;
			int d = dx * dx + dy * dy;
			if (d < bestD) { bestD = d; best = i; }
		}
		return best;
	}
}
