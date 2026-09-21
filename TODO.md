# TODO — engine improvements

Algorithm work is **on hold**. These are ordered roughly by
benefit ÷ risk. Nothing here is committed to; it's a backlog.

## How to validate (mandatory for every item)

The project's hardest-won lesson is that a clean result is the most suspicious
kind, and that a wrong answer is usually silent. So each change needs:

- a **metric written down before measuring** (nodes, depth, or win rate),
- a **sample** (a position suite or a match), never one position,
- **alternating colours / fixed openings** when it's a strength claim,
- a **known-answer probe** for anything that can fail silently (search
  optimality against brute force, threat-solver playouts),
- `verify()` / `verifyHash()` / `stoneCount()==history` kept green.

## Search

- **PVS (principal variation search).** Search the first move with the full
  window, the rest with a null window and re-search on a fail-high. Cheap,
  low-risk. Metric: nodes to fixed depth.
- **Aspiration windows.** Start each iteration with a narrow window around the
  previous value and widen on fail. Pairs well with the TT. Metric: nodes to
  depth on a position suite.
- **Threat extensions / quiescence.** Extend (don't reduce) on moves creating a
  four or an open three, and/or run a small threat-only quiescence at leaves so
  the horizon never cuts off a forced sequence just past the depth. Highest
  expected strength gain in Gomoku, moderate risk of search explosion.
- **Null-move pruning.** Try it, but treat as experimental: threats are forcing,
  so passing may be unsound in tactical lines. Gate behind a flag, A/B it, and
  keep it only if the tactic suite stays green.
- **Late move reductions.** Same caution as null-move; reduce only late,
  quiet, low-ordering moves. Verify the optimality probe still passes.
- **Parallel search (Lazy SMP).** The phone has several cores; a shared TT with a
  few helper threads could give ~2×. Adds non-determinism, which complicates
  measurement — needs a deterministic single-thread fallback for tests.

## Transposition table

- **Bucketed replacement (2–4 way) + generation/aging.** Reduces
  depth-preferred collisions. Metric: nodes to depth, TT hit rate.
- **Two-tier layout.** Keep one depth-preferred and one always-replace entry per
  bucket so deep entries survive churn.
- **Zobrist collision audit.** A test that distinct positions never share a full
  key over a large random sample; and that the stored value for a node matches a
  fresh full-window search of the same node/depth.

## Move ordering

- **Killer moves per ply.** Complement the static gain ordering.
- **History heuristic.** Accumulate a depth-weighted bonus per cell.
- **Counter-move heuristic.** Reply to the opponent's last move with the move
  that historically refuted it.
- Blend with the existing `gain(i, p) + gain(i, opp)` rather than replacing it,
  and measure the blend weights.

## Threat solvers (VCF / VCT)

- **Threat-space search with proof-number evaluation.** The real fix for VCT's
  incompleteness. Replace the bounded open-three search with a proper
  threat-sequence search (Allis) whose proof number drives the search. This is
  the largest single strength item and the largest effort.
- **Threat TT.** Threat sequences transpose heavily; memoise them separately
  from the main TT.
- **Tempo-aware counter-threats.** Today VCT rejects an open-three move whenever
  the defender can make *any* four. A proper tempo comparison (who completes
  five first) would recover many real wins without inventing any.
- **VCF ordering + iterative deepening.** Order forcing moves by the number of
  winning points created; deepen rather than a single fixed depth.

## Evaluation

- **Tune `WEIGHT`.** The weights are hand-set. Run self-play with SPRT and
  coordinate descent / SPSA, alternating colours, fixed openings. Guard against
  overfitting with a held-out position set.
- **Explicit shape terms.** Add open/closed four and three, broken threes, and
  double-threat bonuses on top of the raw window counts, instead of relying on
  the window multiplicity to imply shape.
- **Edge / centre awareness.** Discount shapes near the board edge; small centre
  bias in the opening.
- **Threat-density term.** Reward having more independent threats than the
  opponent.

## Time management

- **Adaptive budget.** Spend more time when the position has many forcing moves;
  less in quiet positions. Replace the current fixed 1.5 s.
- **Pondering.** Predict the opponent's move and search on a background thread
  during their turn. Mind battery/thermal on-device.
- **Soft/hard limits.** Separate "stop starting new iterations" from "abort the
  current one" so the move is never late.

## Testing and benchmarks

- **Position suite.** A fixed set of mid-game positions with expected best moves
  and node/time budgets; a node-count regression gate for search changes.
- **SPRT self-play.** Replace ad-hoc match counts with sequential testing
  (H0/H1, elo bounds), fixed opening book, alternating colours, fixed seed.
- **Threat-generation invariants.** Assert `winningPoints` / open-four detection
  against a brute-force scan on random positions.
- **Adversarial VCT soundness.** Random positions: any move VCT claims as a win
  must survive a playout against the full alpha-beta defender (generalising the
  existing single-fork test).
- **Timeout/phantom stress at scale.** Keep hammering `bestMove` with a 0–2 ms
  budget and assert the board signature and hash never change.

## Stretch

- **Opening book** distilled from self-play, with refutations of weak lines.
- **Renju rules** (forbidden moves for Black) behind a mode flag.
- **Board-symmetry canonicalisation** of the Zobrist key (fold the 8 dihedral
  transforms) to raise TT hit rates.
- **Difficulty presets** as search-budget profiles, if the single hard level ever
  needs company.
