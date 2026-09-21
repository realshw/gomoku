# Gomoku

A 15×15 freestyle Gomoku (five-in-a-row) engine and Android app. No Gradle, no
native code — the engine is plain Java so it can be unit-tested on the host, and
the app is built on-device with `aapt2`/`javac`/`d8`/`apksigner`.

- `java/com/shw/gomoku/Engine.java` — the engine (pure Java, no Android)
- `java/com/shw/gomoku/BoardView.java` — custom board renderer
- `java/com/shw/gomoku/MainActivity.java` — UI and game flow
- `test/com/shw/gomoku/HostTest.java` — host test suite

## 1. Position and incremental evaluation

Cells are indexed `i = y·15 + x`, colours are `0 = empty, 1 = black, 2 = white`.

The evaluation is not computed by scanning the board per node. Instead the board
is decomposed into **windows** — every length-5 segment in the four directions.
There are exactly **572** of them (165 horizontal + 165 vertical + 121 per
diagonal). For each window `w` we keep how many black and white stones it holds.

A window's contribution from Black's point of view is

```
c(w) =  0            if it holds both colours
      + W[b(w)]      if it holds only black stones (b of them)
      - W[o(w)]      if it holds only white stones
```

where the weight of a clean window holding `k` stones is

```
W = { 0, 1, 18, 260, 3600, 140000 }    for k = 0..5
```

and the static evaluation is `E = Σ_w c(w)`.

The nonlinearity is what encodes shape: an **open four** lies in two clean
windows (`2×3600`) while a blocked four lies in one (`3600`), and an open three
feeds three clean windows (`3×260`) versus one for a blocked three. Living
shapes therefore outscore dead ones without any pattern matching.

Placing or removing a stone touches only the windows through that cell
(`|CELL_WINDOWS(i)| ≤ 20`), and each window update is an O(1) add/subtract, so a
board change is near O(1) regardless of how full the board is.

## 2. Move generation and ordering

Only empty cells within Chebyshev distance 2 of an existing stone are
considered. Each candidate `i` is scored by

```
order(i, p) = gain(i, p) + gain(i, opponent)
```

where `gain(i, p)` is the change in `E` if `p` played at `i`. This blends attack
(the value I gain) with defence (the value I deny). Candidates are sorted
descending by this score and the top `K` are searched (`K = 18` at the root, 14
at depth ≥ 6, 10 deeper). Good ordering is what makes alpha-beta prune.

## 3. Search

Negamax with alpha-beta, iterative deepening from depth 2 to 12 in steps of 2,
under a wall-clock budget. At a node, a move that completes five returns
`WIN − ply` (`WIN = 10^8`), so shallower wins are preferred. Leaf nodes return
`E` negated for White. A `TimeUp` exception is raised by a periodic time check
and every speculative placement is undone in a `finally`, so an aborted search
can never leave a stone on the board.

## 4. Transposition table

Positions are hashed with **Zobrist** keys: `h = ⊕ Z[i][colour]` over all stones,
maintained incrementally, with a fixed-seed random table. The table has `2^19`
slots, indexed by `h & (2^19 − 1)`, storing key, depth, value, best move and a
flag (exact / lower / upper). A stored entry is reused when its depth is at least
the remaining depth; its move is always used to order children. Replacement is
depth-preferred.

Mate scores are **normalised to the node** before storing, so a win found at one
ply is not reused as a win at another:

```
store:  v >  MATE_THRESHOLD → v + ply      v < −MATE_THRESHOLD → v − ply
probe:  v >  MATE_THRESHOLD → v − ply      v < −MATE_THRESHOLD → v + ply
```

with `MATE_THRESHOLD = WIN/2`. Without this a cached mate silently corrupts the
value at a different distance from the root.

## 5. Threat solvers

Two exact solvers run before alpha-beta; each returns a proven move or `-1`.

**VCF — victory by continuous fours.** For each move, after placing it:
- five → win;
- ≥ 2 winning points (cells that complete a five) → an open four, unstoppable;
- exactly 1 winning point → the opponent is forced to block there; recurse.

**VCT — victory by continuous threats**, adding open threes to VCF. An open
three is a move after which the attacker has an *open-four move* (a move making
two winning points). Soundness rests on two conservative rules:

1. An open-three move only counts as forcing if the defender **cannot make a
   four of its own** — otherwise the defender's four wins the tempo race.
2. The defender's replies are enumerated over every cell that could refute:
   for each open-four move `q`, the replies are `q` itself and the winning
   points `q` would create. Occupying `q` removes the threat; occupying a
   winning point reduces the resulting open four to a single-ended four.

These rules can **miss** wins, but cannot invent one; a VCT result is
deterministic and falsifiable, and the fork it finds is played out against the
full alpha-beta defender in the test suite.

Both solvers share a bounded slice of the think budget so alpha-beta always
keeps time.

## 6. Landed vs phantom state

The renderer never reads the engine's live board. It owns its own `landed[]`
array (committed stones, drawn solid) while the engine's speculative search
placements and the human's hover preview are **phantoms** (drawn faded). During
a search the engine publishes a `volatile` snapshot of its current path, which
the UI samples to visualise the enumeration. `Engine.verify()` recomputes all
incremental state from the raw board and throws on any drift, and the app
asserts `engine.stoneCount() == history.size()` after every idle transition.

## 7. Tests

```
mkdir -p .htest
javac -d .htest java/com/shw/gomoku/Engine.java test/com/shw/gomoku/HostTest.java
java -cp .htest com.shw.gomoku.HostTest
```

The suite covers tactics (win/block/open three), a greedy match, forced-timeout
phantom leaks, hash and incremental-eval integrity, a brute-force optimality
probe for the search, a node-count A/B for the transposition table, and the VCT
fork.

## 8. Known limitations

No VCT threat-space/proof-number search (VCT is depth- and node-capped, so it is
incomplete), no opening book, and freestyle rules (no forbidden-move restriction
for Black). The engine is strong tactically but is not a solved-perfect player.
