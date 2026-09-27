package com.oiia.puzzlevault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Decides which puzzles make up a run. The run has one chapter per code digit, each with
 * {@link #STEPS} puzzles. Everything derives from the run's seed so a saved run can be
 * rebuilt exactly after the app restarts.
 */
final class Plan {
    static final int DIGITS = 6;
    static final int STEPS = 3;
    static final int TOTAL = DIGITS * STEPS;

    private static final int TYPES = 10;

    private Plan() {}

    /** Stage types for every slot of the run, chapter by chapter. */
    static int[] build(long seed) {
        Random r = new Random(seed);
        int[] out = new int[TOTAL];
        int[] used = new int[TYPES];
        List<Integer> prev = new ArrayList<>();
        for (int ch = 0; ch < DIGITS; ch++) {
            List<Integer> cand = new ArrayList<>();
            for (int t = 0; t < TYPES; t++) cand.add(t);
            Collections.shuffle(cand, r);
            // Prefer the least-used types, and avoid repeating last chapter's puzzles.
            final List<Integer> last = prev;
            cand.sort((a, b) -> Integer.compare(
                    used[a] * 2 + (last.contains(a) ? 1 : 0),
                    used[b] * 2 + (last.contains(b) ? 1 : 0)));
            prev = new ArrayList<>(cand.subList(0, STEPS));
            for (int s = 0; s < STEPS; s++) {
                int t = cand.get(s);
                used[t]++;
                out[ch * STEPS + s] = t;
            }
        }
        return out;
    }

    static Stage create(int type, Stage.Host host, long seed, int index) {
        Random rnd = new Random(seed * 1_000_003L + index * 7919L);
        int level = index / STEPS;
        switch (type) {
            case 0: return new LightsOutStage(host, rnd, level);
            case 1: return new SlidingStage(host, rnd, level);
            case 2: return new HanoiStage(host, rnd, level);
            case 3: return new CodeBreakerStage(host, rnd, level);
            case 4: return new KnightsStage(host, rnd, level);
            case 5: return new WaterJugStage(host, rnd, level);
            case 6: return new SudokuStage(host, rnd, level);
            case 7: return new NimStage(host, rnd, level);
            case 8: return new MemoryStage(host, rnd, level);
            default: return new CipherStage(host, rnd, level);
        }
    }
}
