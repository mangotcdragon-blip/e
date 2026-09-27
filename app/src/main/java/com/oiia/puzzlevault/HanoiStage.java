package com.oiia.puzzlevault;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Tower of Hanoi: move the whole tower from the left peg to the right peg. */
class HanoiStage extends Stage {
    private static final int[] DISK_COLORS = {
            0xFFFF5C7A, 0xFFFF9F43, 0xFFFFC857, 0xFF3DDC97, 0xFF4DA3FF, 0xFF7C5CFF
    };

    private final int disks;
    private final List<List<Integer>> pegs = new ArrayList<>();
    private int selected = -1;
    private int moves;
    private boolean done;

    private final FrameLayout[] pegViews = new FrameLayout[3];
    private final LinearLayout[] stacks = new LinearLayout[3];
    private TextView counter;
    private LinearLayout row;
    private int pegWidth;

    HanoiStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        disks = Math.min(5, 3 + (level + 1) / 2); // 3, 4, 4, 5, 5, 5
        reset();
    }

    private void reset() {
        pegs.clear();
        for (int i = 0; i < 3; i++) pegs.add(new ArrayList<>());
        for (int d = disks; d >= 1; d--) pegs.get(0).add(d);
        selected = -1;
        moves = 0;
    }

    @Override
    String title() {
        return "The Tower";
    }

    @Override
    String instructions() {
        return "Move the whole tower to the RIGHT peg.\nTap a peg to pick up its top disk, then tap "
                + "another peg to drop it. A disk can never sit on a smaller one.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        row = Ui.row(c);
        pegWidth = Ui.cell(c, 3, 120, 8);
        int diskH = Ui.dp(c, 20);
        int height = diskH * (disks + 2) + Ui.dp(c, 16);
        for (int p = 0; p < 3; p++) {
            FrameLayout peg = new FrameLayout(c);
            View pole = new View(c);
            pole.setBackgroundColor(0xFF3A4556);
            peg.addView(pole, new FrameLayout.LayoutParams(Ui.dp(c, 6),
                    FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
            View base = new View(c);
            base.setBackgroundColor(0xFF3A4556);
            peg.addView(base, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    Ui.dp(c, 6), Gravity.BOTTOM));
            LinearLayout stack = Ui.column(c);
            stack.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            slp.bottomMargin = Ui.dp(c, 6);
            peg.addView(stack, slp);
            final int idx = p;
            peg.setOnClickListener(v -> tap(idx));
            int m = Ui.dp(c, 4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pegWidth, height);
            lp.setMargins(m, 0, m, 0);
            row.addView(peg, lp);
            pegViews[p] = peg;
            stacks[p] = stack;
        }
        col.addView(row);
        counter = Ui.text(c, "", 14, Ui.MUTED, false);
        col.addView(counter, Ui.wide(c, 14));
        Button restart = Ui.button(c, "Start over", Ui.CARD);
        restart.setOnClickListener(v -> {
            if (done) return;
            reset();
            render();
        });
        col.addView(restart, Ui.wide(c, 10));
        render();
        return col;
    }

    private void tap(int p) {
        if (done) return;
        List<Integer> to = pegs.get(p);
        if (selected < 0) {
            if (!to.isEmpty()) selected = p;
        } else if (selected == p) {
            selected = -1;
        } else {
            List<Integer> from = pegs.get(selected);
            int disk = from.get(from.size() - 1);
            if (!to.isEmpty() && to.get(to.size() - 1) < disk) {
                selected = -1;
                render();
                wrong(row, "A bigger disk can't go on a smaller one.");
                return;
            }
            from.remove(from.size() - 1);
            to.add(disk);
            selected = -1;
            moves++;
            if (pegs.get(2).size() == disks) {
                done = true;
                render();
                counter.setText("Done in " + moves + " moves (fewest possible: " + ((1 << disks) - 1) + ").");
                row.postDelayed(this::solved, 900);
                return;
            }
        }
        render();
    }

    private void render() {
        Context c = row.getContext();
        int diskH = Ui.dp(c, 18), gap = Ui.dp(c, 2);
        for (int p = 0; p < 3; p++) {
            pegViews[p].setBackground(Ui.rounded(c, p == selected ? 0xFF2A3444 : Color.TRANSPARENT, 10));
            LinearLayout stack = stacks[p];
            stack.removeAllViews();
            List<Integer> peg = pegs.get(p);
            for (int i = peg.size() - 1; i >= 0; i--) {
                int d = peg.get(i);
                View disk = new View(c);
                disk.setBackground(Ui.rounded(c, DISK_COLORS[(d - 1) % DISK_COLORS.length], 6));
                if (p == selected && i == peg.size() - 1) disk.setTranslationY(-Ui.dp(c, 12));
                int w = (int) (pegWidth * (0.3f + 0.65f * d / disks));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, diskH);
                lp.topMargin = gap;
                stack.addView(disk, lp);
            }
        }
        counter.setText("Moves: " + moves);
    }
}
