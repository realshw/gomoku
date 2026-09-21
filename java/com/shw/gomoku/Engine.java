package com.shw.gomoku;

import java.util.ArrayList;

/**
 * Gomoku (freestyle, 15x15) engine.
 *
 * Incremental "window" evaluation + iterative-deepening alpha-beta with
 * threat-ordered move generation, plus a VCF (victory by continuous fours)
 * solver. Pure Java: no Android dependencies, so it can be unit-tested on the
 * host.
 */
public final class Engine {
	public static final int N = 15;
	public static final int EMPTY = 0, BLACK = 1, WHITE = 2;

	private static final int[] DIRX = {1, 0, 1, 1};
	private static final int[] DIRY = {0, 1, 1, -1};

	/** value of a clean length-5 window holding k stones of one colour */
	private static final int[] WEIGHT = {0, 1, 18, 260, 3600, 140000};
	private static final int WIN = 100_000_000;
	private static final int INF = Integer.MAX_VALUE / 4;
	private static final int VCF_DEPTH = 14;
	private static final int VCT_DEPTH = 8;
	private static final int VCT_NODE_CAP = 40000;
	private static final int[] NO_PHANTOMS = new int[0];

	// --- transposition table (shared: one search runs at a time) ---
	private static final int TT_BITS = 19;
	private static final int TT_SIZE = 1 << TT_BITS;
	private static final int TT_MASK = TT_SIZE - 1;
	private static final long[] TT_KEY = new long[TT_SIZE];
	private static final int[] TT_VAL = new int[TT_SIZE];
	private static final int[] TT_MOVE = new int[TT_SIZE];
	private static final byte[] TT_DEPTH = new byte[TT_SIZE];
	private static final byte[] TT_FLAG = new byte[TT_SIZE]; // 0 empty, 1 exact, 2 lower, 3 upper
	private static final byte F_EXACT = 1, F_LOWER = 2, F_UPPER = 3;
	private static final int MATE_THRESHOLD = WIN / 2;

	/** every length-5 segment on the board, as five cell indices */
	private static final int[][] WINDOWS;
	/** for each cell, the ids of the windows that contain it */
	private static final int[][] CELL_WINDOWS;
	/** for each cell, cells within Chebyshev distance 2 (used for candidacy) */
	private static final int[][] NEI;
	/** Zobrist key for [cell][colour-1] */
	private static final long[][] ZOB = new long[N * N][2];

	static {
		ArrayList<int[]> ws = new ArrayList<>();
		for (int d = 0; d < 4; d++) {
			int dx = DIRX[d], dy = DIRY[d];
			for (int y = 0; y < N; y++) {
				for (int x = 0; x < N; x++) {
					int ex = x + dx * 4, ey = y + dy * 4;
					if (ex < 0 || ex >= N || ey < 0 || ey >= N) continue;
					int[] w = new int[5];
					for (int i = 0; i < 5; i++) w[i] = (y + dy * i) * N + (x + dx * i);
					ws.add(w);
				}
			}
		}
		WINDOWS = ws.toArray(new int[0][]);
		int[] counts = new int[N * N];
		for (int[] w : WINDOWS) for (int c : w) counts[c]++;
		CELL_WINDOWS = new int[N * N][];
		for (int c = 0; c < N * N; c++) CELL_WINDOWS[c] = new int[counts[c]];
		int[] fill = new int[N * N];
		for (int wi = 0; wi < WINDOWS.length; wi++)
			for (int c : WINDOWS[wi]) CELL_WINDOWS[c][fill[c]++] = wi;

		NEI = new int[N * N][];
		for (int y = 0; y < N; y++)
			for (int x = 0; x < N; x++) {
				ArrayList<Integer> list = new ArrayList<>();
				for (int ny = Math.max(0, y - 2); ny <= Math.min(N - 1, y + 2); ny++)
					for (int nx = Math.max(0, x - 2); nx <= Math.min(N - 1, x + 2); nx++)
						if (nx != x || ny != y) list.add(ny * N + nx);
				int[] a = new int[list.size()];
				for (int i = 0; i < a.length; i++) a[i] = list.get(i);
				NEI[y * N + x] = a;
			}

		java.util.Random zr = new java.util.Random(0x5EEDC0DEL);
		for (int i = 0; i < N * N; i++)
			for (int c = 0; c < 2; c++) ZOB[i][c] = zr.nextLong();
	}

	private final int[] board = new int[N * N];
	/** wc[0] = black stones in window, wc[1] = white stones */
	private final int[][] wc = new int[2][WINDOWS.length];
	/** sum of window contributions from Black's point of view */
	private int total;
	/** committed stones currently on the board (search placements are transient) */
	private int stones;
	/** true while bestMove is exploring; speculative placements are tracked */
	private boolean searching;
	private final int[] path = new int[N * N];
	private int pathLen;
	private int publishTick;
	/** volatile snapshot of the live search path; read from other threads */
	private volatile int[] phantom = NO_PHANTOMS;
	private long deadline;
	private int nodes;
	/** Zobrist hash of the committed + speculative position */
	private long hash;
	/** toggle for A/B measurement */
	public boolean ttEnabled = true;
	/** when false, bestMove skips the VCF/VCT proof searches (the "fast" mode) */
	public boolean useThreatSolvers = true;
	/** diagnostics from the last bestMove() */
	public int lastDepth, lastNodes;
	private int vctNodes;

	private final int[] tmpIdx = new int[N * N];
	private final int[] tmpScore = new int[N * N];
	private final int[] cand = new int[N * N];
	/** per-ply copy of the ordered candidate list; `generate` reuses `cand` */
	private final int[][] level = new int[64][N * N];

	private static final class TimeUp extends RuntimeException {
		@Override public synchronized Throwable fillInStackTrace() { return this; }
	}
	private static final TimeUp TIME_UP = new TimeUp();

	// ---------------------------------------------------------------- board

	public void reset() {
		java.util.Arrays.fill(board, EMPTY);
		for (int[] row : wc) java.util.Arrays.fill(row, 0);
		total = 0;
		stones = 0;
		hash = 0;
		clearTT();
	}

	/** Empty the transposition table. */
	public static void clearTT() {
		java.util.Arrays.fill(TT_FLAG, (byte) 0);
	}

	/** Recompute the Zobrist hash from the board and fail loudly on drift. */
	public void verifyHash() {
		long h = 0;
		for (int i = 0; i < board.length; i++)
			if (board[i] != EMPTY) h ^= ZOB[i][board[i] - 1];
		if (h != hash)
			throw new IllegalStateException("hash " + hash + " != recomputed " + h);
	}

	public int get(int idx) { return board[idx]; }

	/** Number of stones committed on the board. */
	public int stoneCount() { return stones; }

	/** Place a stone; any existing stone on the cell is removed first. */
	public void place(int idx, int color) {
		if (board[idx] == color) return;
		if (board[idx] != EMPTY) removeStone(idx);
		if (color != EMPTY) addStone(idx, color);
	}

	/** Remove whatever stone occupies the cell. */
	public void take(int idx) {
		if (board[idx] != EMPTY) removeStone(idx);
	}

	/**
	 * Recompute all incremental state from the raw board and fail loudly on any
	 * mismatch. A search that forgets to undo a "phantom" stone, or an update
	 * that touches wc/total inconsistently, is caught here instead of silently
	 * skewing the evaluation.
	 */
	public void verify() {
		int[][] cw = new int[2][WINDOWS.length];
		int n = 0;
		for (int idx = 0; idx < board.length; idx++) {
			int c = board[idx];
			if (c == EMPTY) continue;
			n++;
			int[] cws = CELL_WINDOWS[idx];
			for (int i = 0; i < cws.length; i++) cw[c - 1][cws[i]]++;
		}
		if (n != stones)
			throw new IllegalStateException("stone count " + stones + " != board " + n);
		int t = 0;
		for (int wi = 0; wi < WINDOWS.length; wi++) {
			if (cw[0][wi] != wc[0][wi] || cw[1][wi] != wc[1][wi])
				throw new IllegalStateException("window " + wi + " counts out of sync");
			int b = cw[0][wi], w = cw[1][wi];
			if (b > 0 && w > 0) continue;
			if (b > 0) t += WEIGHT[b];
			else if (w > 0) t -= WEIGHT[w];
		}
		if (t != total)
			throw new IllegalStateException("total " + total + " != recomputed " + t);
	}

	/** Occupancy snapshot, e.g. "0012..."; for tests that assert nothing leaks. */
	public String signature() {
		StringBuilder sb = new StringBuilder(board.length);
		for (int i = 0; i < board.length; i++) sb.append((char) ('0' + board[i]));
		return sb.toString();
	}

	private void addStone(int idx, int color) {
		board[idx] = color;
		stones++;
		hash ^= ZOB[idx][color - 1];
		if (searching) {
			if (pathLen < path.length) path[pathLen] = idx;
			pathLen++;
			if ((++publishTick & 255) == 0) publishPhantoms();
		}
		int[] cw = CELL_WINDOWS[idx];
		for (int i = 0; i < cw.length; i++) {
			int wi = cw[i];
			total -= contrib(wi);
			wc[color - 1][wi]++;
			total += contrib(wi);
		}
	}

	private void removeStone(int idx) {
		int color = board[idx];
		board[idx] = EMPTY;
		stones--;
		hash ^= ZOB[idx][color - 1];
		if (searching && pathLen > 0) pathLen--;
		int[] cw = CELL_WINDOWS[idx];
		for (int i = 0; i < cw.length; i++) {
			int wi = cw[i];
			total -= contrib(wi);
			wc[color - 1][wi]--;
			total += contrib(wi);
		}
	}

	private void publishPhantoms() {
		phantom = java.util.Arrays.copyOf(path, Math.min(pathLen, path.length));
	}

	/** window contribution with Black positive, White negative */
	private int contrib(int wi) {
		int b = wc[0][wi], w = wc[1][wi];
		if (b > 0 && w > 0) return 0;
		if (b > 0) return WEIGHT[b];
		if (w > 0) return -WEIGHT[w];
		return 0;
	}

	// ----------------------------------------------------------- primitives

	public boolean isFive(int idx, int player) {
		int x = idx % N, y = idx / N;
		for (int d = 0; d < 4; d++) {
			int cnt = 1;
			for (int s = 1; s < 5; s++) {
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
				cnt++;
			}
			for (int s = 1; s < 5; s++) {
				int nx = x - DIRX[d] * s, ny = y - DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
				cnt++;
			}
			if (cnt >= 5) return true;
		}
		return false;
	}

	/** The full run (>=5 cells) of `player` through idx, or null if there is none. */
	public int[] fiveLine(int idx, int player) {
		int x = idx % N, y = idx / N;
		for (int d = 0; d < 4; d++) {
			int cnt = 1;
			for (int s = 1; s < 5; s++) {
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
				cnt++;
			}
			for (int s = 1; s < 5; s++) {
				int nx = x - DIRX[d] * s, ny = y - DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
				cnt++;
			}
			if (cnt >= 5) {
				int[] res = new int[cnt];
				int k = 0;
				res[k++] = idx;
				for (int s = 1; s < 5; s++) {
					int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
					if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
					res[k++] = ny * N + nx;
				}
				for (int s = 1; s < 5; s++) {
					int nx = x - DIRX[d] * s, ny = y - DIRY[d] * s;
					if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
					res[k++] = ny * N + nx;
				}
				return res;
			}
		}
		return null;
	}

	/** Would player make five by putting a stone on this (empty) cell? */
	private boolean wouldFive(int idx, int player) {
		int x = idx % N, y = idx / N;
		for (int d = 0; d < 4; d++) {
			int cnt = 1;
			for (int s = 1; s < 5; s++) {
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
				cnt++;
			}
			for (int s = 1; s < 5; s++) {
				int nx = x - DIRX[d] * s, ny = y - DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N || board[ny * N + nx] != player) break;
				cnt++;
			}
			if (cnt >= 5) return true;
		}
		return false;
	}

	private boolean hasNeighbor(int idx) {
		int[] ns = NEI[idx];
		for (int i = 0; i < ns.length; i++) if (board[ns[i]] != EMPTY) return true;
		return false;
	}

	private boolean anyStone() {
		for (int i = 0; i < board.length; i++) if (board[i] != EMPTY) return true;
		return false;
	}

	// ------------------------------------------------------- move selection

	/** Static value of putting a stone of `color` on an empty cell, from color's view. */
	private int gainFor(int idx, int color) {
		int d = 0;
		int[] cw = CELL_WINDOWS[idx];
		for (int i = 0; i < cw.length; i++) {
			int wi = cw[i];
			int before = contrib(wi);
			wc[color - 1][wi]++;
			int after = contrib(wi);
			wc[color - 1][wi]--;
			d += after - before;
		}
		return color == BLACK ? d : -d;
	}

	/**
	 * Fill cand[] with candidate cells ordered best-first, at most `limit`.
	 * Ordering blends the value of playing there myself with the value the
	 * opponent would get from it (i.e. how much there is to block).
	 */
	private int generate(int player, int limit) {
		int opp = 3 - player;
		int n = 0;
		for (int idx = 0; idx < board.length; idx++) {
			if (board[idx] != EMPTY || !hasNeighbor(idx)) continue;
			int my = gainFor(idx, player);
			int op = gainFor(idx, opp);
			tmpIdx[n] = idx;
			tmpScore[n] = my + op;
			n++;
		}
		// insertion sort of (tmpIdx,tmpScore) by score, descending
		for (int i = 1; i < n; i++) {
			int si = tmpScore[i], ii = tmpIdx[i], j = i - 1;
			while (j >= 0 && tmpScore[j] < si) {
				tmpScore[j + 1] = tmpScore[j];
				tmpIdx[j + 1] = tmpIdx[j];
				j--;
			}
			tmpScore[j + 1] = si;
			tmpIdx[j + 1] = ii;
		}
		int k = Math.min(n, limit);
		for (int i = 0; i < k; i++) cand[i] = tmpIdx[i];
		return k;
	}

	public int bestMove(int me, int timeMs) {
		if (!anyStone()) return (N / 2) * N + (N / 2);
		// 1. win now
		int w = findFive(me);
		if (w >= 0) return w;
		// 2. stop an immediate loss
		int b = findFive(3 - me);
		if (b >= 0) return b;
		// From here on every addStone/removeStone is a speculative search
		// placement; publish them so a UI can show the enumeration faded.
		searching = true;
		try {
			long end = System.nanoTime() + timeMs * 1_000_000L;
			if (useThreatSolvers) {
				// threat solvers get a bounded slice so alpha-beta always keeps some time
				deadline = Math.min(end, System.nanoTime() + 300_000_000L);
				int v = findVcf(me, VCF_DEPTH);
				if (v >= 0) return v;

				deadline = Math.min(end, System.nanoTime() + 250_000_000L);
				int vct = findVct(me, VCT_DEPTH);
				if (vct >= 0) return vct;
			}

			// 4. iterative-deepening alpha-beta
			deadline = end;
			nodes = 0;
			lastDepth = 0;
			int best = -1;
			int firstMove = 0;
			for (int depth = 2; depth <= 12; depth += 2) {
				try {
					int m = rootSearch(me, depth, firstMove);
					if (m >= 0) {
						best = m;
						firstMove = m;
						lastDepth = depth;
					}
				} catch (TimeUp t) {
					break;
				}
			}
			if (best >= 0) return best;
			int n = generate(me, 1);
			return n > 0 ? cand[0] : firstEmpty();
		} finally {
			searching = false;
			pathLen = 0;
			phantom = NO_PHANTOMS;
			lastNodes = nodes;
		}
	}

	/** Snapshot of the search path currently being explored, oldest first. */
	public int[] phantomSnapshot() { return phantom; }

	/** Start a fresh time budget for direct calls to findVcf/findVct. */
	public void setBudget(int ms) { deadline = System.nanoTime() + ms * 1_000_000L; }

	private int firstEmpty() {
		for (int i = 0; i < board.length; i++) if (board[i] == EMPTY) return i;
		return -1;
	}

	private int findFive(int player) {
		for (int idx = 0; idx < board.length; idx++) {
			if (board[idx] != EMPTY || !hasNeighbor(idx)) continue;
			board[idx] = player;
			boolean win = isFive(idx, player);
			board[idx] = EMPTY;
			if (win) return idx;
		}
		return -1;
	}

	/** Root alpha-beta over the ordered candidate list; returns best cell. */
	private int rootSearch(int me, int depth, int pv) {
		int n = generate(me, 18);
		if (n == 0) return -1;
		if (pv != 0) for (int i = 0; i < n; i++) if (cand[i] == pv) {
			int t = cand[0]; cand[0] = cand[i]; cand[i] = t;
			break;
		}
		int[] moves = level[0];
		System.arraycopy(cand, 0, moves, 0, n);
		int alpha = -INF, bestIdx = moves[0];
		for (int i = 0; i < n; i++) {
			int idx = moves[i];
			addStone(idx, me);
			int val;
			try {
				if (isFive(idx, me)) val = WIN;
				else val = -negamax(depth - 1, -INF, -alpha, 3 - me, 1);
			} finally {
				removeStone(idx);
			}
			if (val > alpha) {
				alpha = val;
				bestIdx = idx;
			}
		}
		return bestIdx;
	}

	private int negamax(int depth, int alpha, int beta, int player, int ply) {
		if ((++nodes & 511) == 0 && System.nanoTime() > deadline) throw TIME_UP;
		int alpha0 = alpha;
		int slot = (int) (hash & TT_MASK);
		int ttMove = 0;
		if (ttEnabled && TT_FLAG[slot] != 0 && TT_KEY[slot] == hash) {
			ttMove = TT_MOVE[slot];
			if (TT_DEPTH[slot] >= depth) {
				int v = fromTT(TT_VAL[slot], ply);
				byte f = TT_FLAG[slot];
				if (f == F_EXACT) return v;
				if (f == F_LOWER && v >= beta) return v;
				if (f == F_UPPER && v <= alpha) return v;
			}
		}
		if (depth <= 0) return evalFor(player);
		int n = generate(player, depth >= 6 ? 14 : 10);
		if (n == 0) return evalFor(player);
		if (ttMove != 0) {
			for (int i = 0; i < n; i++) if (cand[i] == ttMove) {
				int t = cand[0]; cand[0] = cand[i]; cand[i] = t;
				break;
			}
		}
		int[] moves = level[ply];
		System.arraycopy(cand, 0, moves, 0, n);
		int best = -INF, bestIdx = moves[0];
		for (int i = 0; i < n; i++) {
			int idx = moves[i];
			addStone(idx, player);
			int val;
			try {
				if (isFive(idx, player)) val = WIN - ply;
				else val = -negamax(depth - 1, -beta, -alpha, 3 - player, ply + 1);
			} finally {
				removeStone(idx);
			}
			if (val > best) { best = val; bestIdx = idx; }
			if (best > alpha) alpha = best;
			if (alpha >= beta) break;
		}
		if (ttEnabled && (TT_FLAG[slot] == 0 || TT_KEY[slot] == hash || depth >= TT_DEPTH[slot])) {
			TT_KEY[slot] = hash;
			TT_VAL[slot] = toTT(best, ply);
			TT_MOVE[slot] = bestIdx;
			TT_DEPTH[slot] = (byte) Math.min(127, depth);
			TT_FLAG[slot] = (byte) (best <= alpha0 ? F_UPPER : (best >= beta ? F_LOWER : F_EXACT));
		}
		return best;
	}

	private int evalFor(int player) {
		return player == BLACK ? total : -total;
	}

	/**
	 * Mate scores are stored relative to the node, not the root, so a win found
	 * at one ply isn't reused as a win at a different ply.
	 */
	private static int toTT(int v, int ply) {
		if (v > MATE_THRESHOLD) return v + ply;
		if (v < -MATE_THRESHOLD) return v - ply;
		return v;
	}

	private static int fromTT(int v, int ply) {
		if (v > MATE_THRESHOLD) return v - ply;
		if (v < -MATE_THRESHOLD) return v + ply;
		return v;
	}

	/** Fixed-depth search returning the node count; for A/B measurement. */
	public int lastBenchMove;
	public int benchFixedDepth(int me, int depth) {
		clearTT();
		nodes = 0;
		deadline = Long.MAX_VALUE;
		searching = true;
		try {
			lastBenchMove = rootSearch(me, depth, 0);
		} finally {
			searching = false;
			pathLen = 0;
			phantom = NO_PHANTOMS;
		}
		return nodes;
	}

	/** Iterative deepening to `maxDepth`, returning total nodes; for A/B measurement. */
	public int benchIterative(int me, int maxDepth) {
		clearTT();
		nodes = 0;
		deadline = Long.MAX_VALUE;
		searching = true;
		int first = 0;
		try {
			for (int d = 2; d <= maxDepth; d += 2) first = rootSearch(me, d, first);
		} finally {
			searching = false;
			pathLen = 0;
			phantom = NO_PHANTOMS;
		}
		return nodes;
	}

	/** Exact value of a specific root move at a fixed depth, TT disabled (ground truth). */
	public int evalMove(int me, int move, int depth) {
		boolean saved = ttEnabled;
		ttEnabled = false;
		deadline = Long.MAX_VALUE;
		addStone(move, me);
		int v;
		try {
			v = isFive(move, me) ? WIN : -negamax(depth - 1, -INF, INF, 3 - me, 1);
		} finally {
			removeStone(move);
			ttEnabled = saved;
		}
		return v;
	}

	// ----------------------------------------------------------------- VCF

	/**
	 * Search for a win by continuous fours. Returns the forcing first move, or -1.
	 * A "four" forces a single reply; an open four (two winning points) is already
	 * unstoppable.
	 */
	public int findVcf(int player, int depth) {
		int[] cells = candidates();
		for (int i = 0; i < cells.length; i++) {
			if (System.nanoTime() > deadline) return -1;
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean win = false;
			if (isFive(idx, player)) win = true;
			else if (findFive(3 - player) < 0) {
				int[] wp = winningPoints(idx, player);
				if (wp.length >= 2) win = true;
				else if (wp.length == 1) {
					addStone(wp[0], 3 - player);
					win = vcfWin(player, depth - 1);
					removeStone(wp[0]);
				}
			}
			removeStone(idx);
			if (win) return idx;
		}
		return -1;
	}

	private boolean vcfWin(int player, int depth) {
		if (depth <= 0) return false;
		int[] cells = candidates();
		for (int i = 0; i < cells.length; i++) {
			if (System.nanoTime() > deadline) return false;
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean win = false;
			if (isFive(idx, player)) win = true;
			else if (findFive(3 - player) < 0) {
				int[] wp = winningPoints(idx, player);
				if (wp.length >= 2) win = true;
				else if (wp.length == 1) {
					addStone(wp[0], 3 - player);
					win = vcfWin(player, depth - 1);
					removeStone(wp[0]);
				}
			}
			removeStone(idx);
			if (win) return true;
		}
		return false;
	}

	/** Empty cells near existing stones. */
	private int[] candidates() {
		int n = 0;
		for (int idx = 0; idx < board.length; idx++)
			if (board[idx] == EMPTY && hasNeighbor(idx)) cand[n++] = idx;
		int[] out = new int[n];
		System.arraycopy(cand, 0, out, 0, n);
		return out;
	}

	/**
	 * Empty cells that would complete a five for `player`, considering only
	 * lines through `idx` (a five made after playing idx must include idx).
	 */
	private int[] winningPoints(int idx, int player) {
		int x = idx % N, y = idx / N;
		int[] out = new int[16];
		int n = 0;
		for (int d = 0; d < 4; d++) {
			for (int s = -4; s <= 4; s++) {
				if (s == 0) continue;
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N) continue;
				int c = ny * N + nx;
				if (board[c] != EMPTY) continue;
				if (wouldFive(c, player)) {
					boolean dup = false;
					for (int i = 0; i < n; i++) if (out[i] == c) { dup = true; break; }
					if (!dup && n < out.length) out[n++] = c;
				}
			}
		}
		int[] res = new int[n];
		System.arraycopy(out, 0, res, 0, n);
		return res;
	}

	// ----------------------------------------------------------------- VCT

	/**
	 * Victory by Continuous Threats: VCF plus open threes. Returns a forcing
	 * first move, or -1.
	 *
	 * Soundness rests on two conservative rules. (1) A move that makes a four
	 * forces a single reply; the attacker exploits a double threat. (2) An
	 * open-three move is only treated as forcing when the defender cannot make a
	 * four of its own — otherwise the defender's four wins the tempo race — and
	 * then the defender's replies are enumerated over *every* empty cell on the
	 * threat's lines (a superset of the blocking moves). Both rules can miss
	 * wins, but neither can invent one.
	 */
	public int findVct(int player, int depth) {
		vctNodes = 0;
		int[] cells = candidates();
		int n = cells.length;
		// immediate wins first: a five, or an open-four move
		for (int i = 0; i < n; i++) {
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean imm;
			try {
				imm = isFive(idx, player) || winningPoints(idx, player).length >= 2;
			} finally {
				removeStone(idx);
			}
			if (imm) return idx;
		}
		// order the rest by fork potential (open-four moves they create)
		int[] sc = new int[n];
		for (int i = 0; i < n; i++) {
			int idx = cells[i];
			if (board[idx] != EMPTY) { sc[i] = -1; continue; }
			addStone(idx, player);
			try {
				sc[i] = openFourMoveCount(idx, player);
			} finally {
				removeStone(idx);
			}
		}
		for (int i = 0; i < n; i++)
			for (int j = i + 1; j < n; j++)
				if (sc[j] > sc[i]) {
					int t = sc[i]; sc[i] = sc[j]; sc[j] = t;
					t = cells[i]; cells[i] = cells[j]; cells[j] = t;
				}
		for (int i = 0; i < n; i++) {
			if (System.nanoTime() > deadline) {
				return -1;
			}
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean win;
			try {
				win = attackerMoveWins(idx, player, depth);
			} finally {
				removeStone(idx);
			}
			if (win) return idx;
		}
		return -1;
	}

	/** How many open-four moves (each making two winning points) `idx` enables. */
	private int openFourMoveCount(int idx, int player) {
		int x = idx % N, y = idx / N;
		int cnt = 0;
		for (int d = 0; d < 4; d++) {
			for (int s = -4; s <= 4; s++) {
				if (s == 0) continue;
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N) continue;
				int q = ny * N + nx;
				if (board[q] != EMPTY) continue;
				addStone(q, player);
				int w;
				try {
					w = winningPoints(q, player).length;
				} finally {
					removeStone(q);
				}
				if (w >= 2) cnt++;
			}
		}
		return cnt;
	}

	/** `player` to move: can it force a win by continuous threats? */
	private boolean vctWin(int player, int depth) {
		if (depth <= 0 || vctNodes > VCT_NODE_CAP) return false;
		if (findFive(player) >= 0) return true;
		int[] cells = candidates();
		// 1. immediate: a five, or a move that makes an open four (two threats)
		for (int i = 0; i < cells.length; i++) {
			if (System.nanoTime() > deadline) return false;
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean imm;
			try {
				imm = isFive(idx, player) || winningPoints(idx, player).length >= 2;
			} finally {
				removeStone(idx);
			}
			if (imm) return true;
		}
		// 2. forcing moves: fours (single forced reply) then open threes
		for (int i = 0; i < cells.length; i++) {
			if (System.nanoTime() > deadline) return false;
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean win;
			try {
				win = attackerMoveWins(idx, player, depth);
			} finally {
				removeStone(idx);
			}
			if (win) return true;
		}
		return false;
	}

	/** `player` just played `idx` (still placed): does that move win against every defence? */
	private boolean attackerMoveWins(int idx, int player, int depth) {
		vctNodes++;
		int opp = 3 - player;
		if (vctNodes > VCT_NODE_CAP) return false;
		if (isFive(idx, player)) return true;
		if (findFive(opp) >= 0) return false;
		int[] wp = winningPoints(idx, player);
		if (wp.length >= 2) return true;
		if (wp.length == 1) {
			addStone(wp[0], opp);
			boolean win;
			try {
				win = vctWin(player, depth - 1);
			} finally {
				removeStone(wp[0]);
			}
			return win;
		}
		if (!hasOpenFourMove(idx, player)) return false;
		if (canMakeFour(opp)) return false;
		int[] defs = defenseCells(idx, player);
		for (int i = 0; i < defs.length; i++) {
			int d = defs[i];
			if (board[d] != EMPTY) continue;
			addStone(d, opp);
			boolean ok;
			try {
				ok = !isFive(d, opp) && winningPoints(d, opp).length == 0
						&& vctWin(player, depth - 1);
			} finally {
				removeStone(d);
			}
			if (!ok) return false;
		}
		return true;
	}

	/** Does `player` have a move near `idx` that makes an open four or five? */
	private boolean hasOpenFourMove(int idx, int player) {
		int x = idx % N, y = idx / N;
		for (int d = 0; d < 4; d++) {
			for (int s = -4; s <= 4; s++) {
				if (s == 0) continue;
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N) continue;
				int q = ny * N + nx;
				if (board[q] != EMPTY) continue;
				addStone(q, player);
				boolean strong;
				try {
					strong = isFive(q, player) || winningPoints(q, player).length >= 2;
				} finally {
					removeStone(q);
				}
				if (strong) return true;
			}
		}
		return false;
	}

	/** Can `player` play a move that creates a four (threatens five next)? */
	private boolean canMakeFour(int player) {
		int[] cells = candidates();
		for (int i = 0; i < cells.length; i++) {
			int idx = cells[i];
			if (board[idx] != EMPTY) continue;
			addStone(idx, player);
			boolean four;
			try {
				four = isFive(idx, player) || winningPoints(idx, player).length >= 1;
			} finally {
				removeStone(idx);
			}
			if (four) return true;
		}
		return false;
	}

	/**
	 * Defender replies that can possibly stop `player`'s open three: for every
	 * open-four move q the attacker has (a move making two winning points), the
	 * reply must occupy q itself or one of the winning points q would create.
	 * Anything further away cannot refute, so this stays a superset of the
	 * refuting moves while being far smaller than the whole line.
	 */
	private int[] defenseCells(int idx, int player) {
		int x = idx % N, y = idx / N;
		int[] out = new int[32];
		int n = 0;
		for (int d = 0; d < 4; d++) {
			for (int s = -4; s <= 4; s++) {
				if (s == 0) continue;
				int nx = x + DIRX[d] * s, ny = y + DIRY[d] * s;
				if (nx < 0 || nx >= N || ny < 0 || ny >= N) continue;
				int q = ny * N + nx;
				if (board[q] != EMPTY) continue;
				addStone(q, player);
				int[] wp;
				try {
					wp = winningPoints(q, player);
				} finally {
					removeStone(q);
				}
				if (wp.length < 2) continue; // q is not an open-four move
				n = addUnique(out, n, q);
				for (int w = 0; w < wp.length; w++) n = addUnique(out, n, wp[w]);
			}
		}
		int[] res = new int[n];
		System.arraycopy(out, 0, res, 0, n);
		return res;
	}

	private static int addUnique(int[] a, int n, int v) {
		for (int i = 0; i < n; i++) if (a[i] == v) return n;
		if (n < a.length) a[n++] = v;
		return n;
	}
}
