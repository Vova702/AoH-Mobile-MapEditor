package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which provinces are written, and in which order, when the map is saved in
 * "new order" mode. Pure Java on purpose, so it can be unit-tested.
 *
 * Before this existed, "new order" mode wrote ONLY the provinces the user had tapped.
 * Every province that was not tapped silently vanished from disk (and a save with nothing
 * tapped wiped the whole map), so the next screen / reload came back with missing provinces.
 */
final class SaveOrder {

    private SaveOrder() {
    }

    /**
     * @param chosen the ids the user picked, in the order they picked them (may contain
     *               duplicates / out-of-range ids, which are ignored)
     * @param total  number of provinces that currently exist
     * @return every id in 0..total-1 exactly once: the chosen ones first, in picked order,
     *         then all the remaining ones in their current order
     */
    static List<Integer> outputOrder(List<Integer> chosen, int total) {
        List<Integer> out = new ArrayList<>(total);
        var used = new boolean[Math.max(total, 0)];
        if (chosen != null) {
            for (Integer id : chosen) {
                if (id == null || id < 0 || id >= total || used[id]) continue;
                used[id] = true;
                out.add(id);
            }
        }
        for (int id = 0; id < total; id++) {
            if (!used[id]) out.add(id);
        }
        return out;
    }
}
