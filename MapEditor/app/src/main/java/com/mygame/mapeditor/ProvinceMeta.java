package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every per-province list that is indexed by province id (extras, sea flag,
 * continent, "new order" ids) in step when a province is added, deleted or an
 * undo brings it back. Pure Java on purpose, so it can be unit-tested.
 *
 * Before this existed, deleting a province shifted the geometry lists but not the
 * sea / continent lists, so every province after the deleted one silently got its
 * neighbor's sea flag and continent.
 */
final class ProvinceMeta {

    private ProvinceMeta() {
    }

    /** What a delete removed - enough to put everything back exactly (undo). */
    static final class Removal {
        ProvinceExtra extra;
        boolean sea;
        int continent;
        /** Provinces (numbering BEFORE the delete) that linked to the deleted one: {provinceId, 0 = land list / 1 = sea list}. */
        final List<int[]> referrers = new ArrayList<>();
        /** Position of the deleted id inside the "new order" list, -1 if it was not there. */
        int orderPosition = -1;
    }

    /** Makes the three lists exactly {@code count} long (missing entries = defaults). */
    static void pad(List<ProvinceExtra> extras, List<Boolean> sea, List<Integer> continent, int count) {
        while (extras.size() < count) extras.add(new ProvinceExtra());
        while (sea.size() < count) sea.add(false);
        while (continent.size() < count) continent.add(0);
    }

    static Removal remove(List<ProvinceExtra> extras, List<Boolean> sea, List<Integer> continent,
                          List<Integer> orderIds, int provinceCountBeforeDelete, int id) {
        pad(extras, sea, continent, provinceCountBeforeDelete);
        var r = new Removal();
        r.extra = extras.remove(id);
        r.sea = Boolean.TRUE.equals(sea.remove(id));
        r.continent = continent.remove(id);

        for (int p = 0; p < extras.size(); p++) {
            int originalId = p < id ? p : p + 1;
            ProvinceExtra e = extras.get(p);
            if (e.links.contains(id)) r.referrers.add(new int[]{originalId, 0});
            if (e.seaLinks.contains(id)) r.referrers.add(new int[]{originalId, 1});
            dropAndShift(e.links, id);
            dropAndShift(e.seaLinks, id);
        }

        r.orderPosition = orderIds.indexOf(id);
        if (r.orderPosition >= 0) orderIds.remove(r.orderPosition);
        shiftDown(orderIds, id);
        return r;
    }

    static void restore(List<ProvinceExtra> extras, List<Boolean> sea, List<Integer> continent,
                        List<Integer> orderIds, int id, Removal r) {
        for (ProvinceExtra e : extras) {
            shiftUp(e.links, id);
            shiftUp(e.seaLinks, id);
        }
        shiftUp(orderIds, id);

        extras.add(id, r.extra);
        sea.add(id, r.sea);
        continent.add(id, r.continent);

        for (int[] ref : r.referrers) {
            ProvinceExtra e = extras.get(ref[0]);
            List<Integer> list = ref[1] == 0 ? e.links : e.seaLinks;
            if (!list.contains(id)) list.add(id);
        }
        if (r.orderPosition >= 0) orderIds.add(Math.min(r.orderPosition, orderIds.size()), id);
    }

    /** Removes the LAST province (undo of "commit new province"). */
    static void removeLast(List<ProvinceExtra> extras, List<Boolean> sea, List<Integer> continent, int provinceCountBeforeRemove) {
        pad(extras, sea, continent, provinceCountBeforeRemove);
        int last = provinceCountBeforeRemove - 1;
        extras.remove(last);
        sea.remove(last);
        continent.remove(last);
    }

    private static void dropAndShift(List<Integer> list, int removedId) {
        List<Integer> out = new ArrayList<>(list.size());
        for (Integer n : list) {
            if (n == null || n == removedId) continue;
            out.add(n > removedId ? n - 1 : n);
        }
        list.clear();
        list.addAll(out);
    }

    private static void shiftDown(List<Integer> list, int removedId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) > removedId) list.set(i, list.get(i) - 1);
        }
    }

    private static void shiftUp(List<Integer> list, int insertedId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) >= insertedId) list.set(i, list.get(i) + 1);
        }
    }
}
