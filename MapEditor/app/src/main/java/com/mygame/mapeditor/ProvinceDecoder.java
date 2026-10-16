package com.mygame.mapeditor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import age.of.civilizations2.jakowski.lukasz.Province_Border_GameData;
import age.of.civilizations2.jakowski.lukasz.Province_GameData2;
import age.of.civilizations2.jakowski.lukasz.Province_Info_GameData3;

/**
 * Читає / пише оригінальні бінарні файли провінцій Age of Civilizations II
 * / Age of History II (assets/map/<map_name>/data/provinces/<id>), які гра
 * зберігає звичайним Java ObjectOutputStream/Province_GameData2.
 *
 * serialVersionUID = 0L підтверджений з реального декомпільованого коду
 * гри (Province_GameData2 / Province_Info_GameData3), тож звичайний
 * ObjectInputStream читає файли без жодних workaround'ів для версій.
 *
 * Використання (з MapDataRepository або GamePanel):
 *
 *   if (ProvinceDecoder.isJavaSerialized(file)) {
 *       ProvinceDecoder.DecodedProvince p = ProvinceDecoder.decodeProvince(file);
 *       // p.pointsX / p.pointsY - short[] контуру
 *       // p.continentId, p.neighboringProvinces - додатково
 *       String twoLineText = ProvinceDecoder.toEditorTextFormat(p);
 *       // twoLineText можна згодувати в addProvinceFromLines(...) як є
 *   }
 *
 * Для пакетного імпорту цілої папки провінцій - decodeProvincesDirectory().
 */
public final class ProvinceDecoder {

    private ProvinceDecoder() {
    }

    // ---------------------------------------------------------------------
    // 1. Автовизначення формату (Java Serialization magic header)
    // ---------------------------------------------------------------------

    private static final int JAVA_SERIAL_MAGIC = 0xACED;
    private static final int JAVA_SERIAL_VERSION = 0x0005;

    /** true, якщо файл починається з магічних байтів Java-серіалізації (0xAC 0xED 0x00 0x05). */
    public static boolean isJavaSerialized(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return isJavaSerialized(is);
        } catch (IOException e) {
            return false;
        }
    }

    /** Те саме, але для вже відкритого потоку. Потік НЕ закривається і НЕ споживається (mark/reset). */
    public static boolean isJavaSerialized(InputStream rawIs) throws IOException {
        InputStream is = rawIs.markSupported() ? rawIs : new BufferedInputStream(rawIs);
        is.mark(4);
        var header = new byte[4];
        int read = readFully(is, header);
        is.reset();
        if (read < 4) return false;

        int magic = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int version = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        return magic == JAVA_SERIAL_MAGIC && version == JAVA_SERIAL_VERSION;
    }

    private static int readFully(InputStream is, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = is.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // 2 & 3. Десеріалізація + витягування точок для редактора
    // ---------------------------------------------------------------------

    public static DecodedProvince decodeProvince(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return decodeProvince(is);
        }
    }

    public static DecodedProvince decodeProvince(InputStream is) throws IOException {
        InputStream buffered = is instanceof BufferedInputStream ? is : new BufferedInputStream(is);
        try (ObjectInputStream ois = new ObjectInputStream(buffered)) {
            Object raw = ois.readObject();
            if (!(raw instanceof Province_GameData2 game)) {
                throw new IOException("Очікувався Province_GameData2, отримано: "
                        + (raw == null ? "null" : raw.getClass().getName()));
            }
            return fromGameData(game);
        } catch (ClassNotFoundException e) {
            throw new IOException("Не вдалося знайти клас під час десеріалізації провінції", e);
        }
    }

    /**
     * Пакетне декодування папки з бінарними файлами провінцій (наприклад
     * assets/map/<map_name>/data/provinces/). Файли, що не є валідною
     * Java-серіалізацією або битi, просто пропускаються - решта імпорту не
     * зривається через один поганий файл.
     */
    public static List<DecodedProvince> decodeProvincesDirectory(File dir) throws IOException {
        List<DecodedProvince> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File f : files) {
            if (!f.isFile()) continue;
            if (!isJavaSerialized(f)) continue;
            try {
                result.add(decodeProvince(f));
            } catch (IOException ignored) {
                // Пропускаємо нерозпізнаний/пошкоджений файл, не зупиняємо весь імпорт.
            }
        }
        return result;
    }

    private static DecodedProvince fromGameData(Province_GameData2 src) {
        var out = new DecodedProvince();
        out.pointsX = shortListToArray(src.lPointsX);
        out.pointsY = shortListToArray(src.lPointsY);
        out.neighboringProvinces = shortListToIntList(src.lNeighboringProvinces);
        out.neighboringSeaProvinces = shortListToIntList(src.lNeighboringSeaProvinces);
        out.levelOfPort = src.iLevelOfPort;
        out.portShiftX = src.iPort_ShiftX;
        out.portShiftY = src.iPort_ShiftY;

        if (src.lProvinceBorder != null) {
            for (Province_Border_GameData b : src.lProvinceBorder) {
                if (b == null) continue;
                var db = new DecodedBorder();
                db.withProvinceId = b.withProvinceID;
                db.pointsX = shortListToArray(b.lPointsX);
                db.pointsY = shortListToArray(b.lPointsY);
                out.borders.add(db);
            }
        }

        if (src.provinceInfo != null) {
            out.continentId = src.provinceInfo.iContinentID;
            out.regionId = src.provinceInfo.iRegionID;
            out.shiftX = src.provinceInfo.iShiftX;
            out.shiftY = src.provinceInfo.iShiftY;
            out.growthRate = src.provinceInfo.fGrowthRate;
            out.terrainTag = src.provinceInfo.sTerrainTAG;
        }
        return out;
    }

    /** List<Short> (як у грі) -> short[] (зручніше для нашого DecodedProvince/редактора). */
    private static short[] shortListToArray(List<Short> list) {
        if (list == null) return new short[0];
        var arr = new short[list.size()];
        for (int i = 0; i < arr.length; i++) {
            Short v = list.get(i);
            arr[i] = v != null ? v : 0;
        }
        return arr;
    }

    /** short[] -> List<Short> (формат, у якому це реально зберігає гра). */
    private static List<Short> arrayToShortList(short[] arr) {
        List<Short> list = new ArrayList<>(arr != null ? arr.length : 0);
        if (arr != null) {
            for (short v : arr) list.add(v);
        }
        return list;
    }

    /** List<Short> (сусіди у грі) -> List<Integer> (зручніше для решти редактора). */
    private static List<Integer> shortListToIntList(List<Short> list) {
        List<Integer> out = new ArrayList<>();
        if (list == null) return out;
        for (Short v : list) out.add(v != null ? (int) v : 0);
        return out;
    }

    /** List<Integer> -> List<Short> (формат, у якому це реально зберігає гра). */
    private static List<Short> intListToShortList(List<Integer> list) {
        List<Short> out = new ArrayList<>();
        if (list == null) return out;
        for (Integer v : list) out.add(v != null ? v.shortValue() : 0);
        return out;
    }

    // ---------------------------------------------------------------------
    // 4. Зворотний запис (експорт у формат гри, на майбутнє)
    // ---------------------------------------------------------------------

    /**
     * Border-дані (lProvinceBorder) тепер відновлюються повноцінно -
     * структура Province_Border_GameData підтверджена з декомпільованого
     * коду гри, тож жодних втрат при повторному збереженні немає.
     *
     * УВАГА: цей варіант НЕ перевіряє й НЕ оновлює ID сусідів
     * (withProvinceId у border'ах, lNeighboringProvinces/SeaProvinces) -
     * якщо провінції в редакторі перенумеровувались (bNewOrderOfProvinces),
     * старі ID можуть уже нічого не означати в поточній моделі. Якщо
     * номери могли змінитись - використовуйте оверлоад з idRemap/validIds
     * нижче.
     */
    /** Same bytes {@link #encodeProvince} would write to a file, but returned in memory (used for pack files). */
    public static byte[] encodeProvinceToBytes(DecodedProvince data) throws IOException {
        Province_GameData2 game = toGameData(data);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(game);
        }
        return bos.toByteArray();
    }

    public static void encodeProvince(DecodedProvince data, File outFile) throws IOException {
        Province_GameData2 game = toGameData(data);
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(outFile)))) {
            oos.writeObject(game);
        }
    }

    /**
     * Той самий експорт, але перед записом перевіряє й за потреби оновлює
     * усі посилання на ID інших провінцій - border.withProvinceId,
     * lNeighboringProvinces, lNeighboringSeaProvinces - через
     * {@link #remapProvinceReferences}. Повертає список попереджень
     * (наприклад, про border, який довелось відкинути, бо сусід більше
     * не існує) - викликач сам вирішує, чи показати їх користувачу.
     *
     * @param idRemap         мапа "старий ID -> новий ID" (наприклад, зі
     *                        стану bNewOrderOfProvinces редактора). Може
     *                        бути {@code null}, якщо перенумерації не було.
     * @param validProvinceIds множина всіх ID, що реально існують у
     *                        поточній карті - потрібна, щоб відрізнити
     *                        "просто не перенумеровану" провінцію від
     *                        "видаленої". Може бути {@code null}, якщо
     *                        перевірку на видалені провінції робити не треба.
     */
    public static List<String> encodeProvince(DecodedProvince data, File outFile,
                                                Map<Integer, Integer> idRemap,
                                                Set<Integer> validProvinceIds) throws IOException {
        RemapResult remapped = remapProvinceReferences(data, idRemap, validProvinceIds);
        encodeProvince(remapped.province, outFile);
        return remapped.warnings;
    }

    /**
     * Перевіряє й за потреби оновлює всі "чужі" ID, на які посилається ця
     * провінція (border.withProvinceId + обидва списки сусідів), відносно
     * поточного стану карти. Для кожного старого ID:
     *
     *   1. якщо є запис у idRemap - береться нове значення звідти;
     *   2. інакше, якщо задано validProvinceIds і старий ID досі там є -
     *      ID лишається без змін (провінцію не перенумеровували);
     *   3. інакше, якщо validProvinceIds задано, а ID у ньому немає -
     *      посилання вважається "мертвим" (провінція видалена/перенумерована
     *      без збереження мапінгу) і відкидається - border видаляється зі
     *      списку, ID прибирається зі списку сусідів;
     *   4. якщо ні idRemap, ні validProvinceIds не задані - ID лишається
     *      як є (перевірку виконати нема з чим).
     *
     * Оригінальний {@code data} не змінюється - повертається нова копія.
     */
    public static RemapResult remapProvinceReferences(DecodedProvince data,
                                                        Map<Integer, Integer> idRemap,
                                                        Set<Integer> validProvinceIds) {
        List<String> warnings = new ArrayList<>();
        var out = new DecodedProvince();

        out.pointsX = data.pointsX;
        out.pointsY = data.pointsY;
        out.continentId = data.continentId;
        out.regionId = data.regionId;
        out.shiftX = data.shiftX;
        out.shiftY = data.shiftY;
        out.growthRate = data.growthRate;
        out.terrainTag = data.terrainTag;
        out.levelOfPort = data.levelOfPort;
        out.portShiftX = data.portShiftX;
        out.portShiftY = data.portShiftY;

        out.neighboringProvinces = remapIdList(
                data.neighboringProvinces, idRemap, validProvinceIds, "сусід (суходіл)", warnings);
        out.neighboringSeaProvinces = remapIdList(
                data.neighboringSeaProvinces, idRemap, validProvinceIds, "сусід (море)", warnings);

        out.borders = new ArrayList<>();
        if (data.borders != null) {
            for (DecodedBorder b : data.borders) {
                Integer resolved = resolveId(b.withProvinceId, idRemap, validProvinceIds);
                if (resolved == null) {
                    warnings.add("Border із withProvinceId=" + b.withProvinceId
                            + " відкинуто - провінції з таким ID більше немає на карті.");
                    continue;
                }
                var nb = new DecodedBorder();
                nb.withProvinceId = resolved;
                nb.pointsX = b.pointsX;
                nb.pointsY = b.pointsY;
                out.borders.add(nb);
                if (resolved != b.withProvinceId) {
                    warnings.add("Border withProvinceId оновлено: " + b.withProvinceId + " -> " + resolved);
                }
            }
        }

        return new RemapResult(out, warnings);
    }

    private static List<Integer> remapIdList(List<Integer> ids,
                                              Map<Integer, Integer> idRemap,
                                              Set<Integer> validProvinceIds,
                                              String label,
                                              List<String> warnings) {
        List<Integer> out = new ArrayList<>();
        if (ids == null) return out;

        for (Integer oldId : ids) {
            if (oldId == null) continue;
            Integer resolved = resolveId(oldId, idRemap, validProvinceIds);
            if (resolved == null) {
                warnings.add("ID " + label + " = " + oldId + " відкинуто - такої провінції більше немає.");
                continue;
            }
            out.add(resolved);
            if (!resolved.equals(oldId)) {
                warnings.add("ID " + label + " оновлено: " + oldId + " -> " + resolved);
            }
        }
        return out;
    }

    /**
     * @return нове значення ID, старе значення (якщо перевірити нема з чим
     *         або ID досі валідний), або {@code null}, якщо посилання
     *         визнано "мертвим" і його слід відкинути.
     */
    private static Integer resolveId(int oldId, Map<Integer, Integer> idRemap, Set<Integer> validProvinceIds) {
        if (idRemap != null && idRemap.containsKey(oldId)) {
            return idRemap.get(oldId);
        }
        if (validProvinceIds != null) {
            return validProvinceIds.contains(oldId) ? oldId : null;
        }
        return oldId;
    }

    /** Результат {@link #remapProvinceReferences}: оновлена провінція + людські пояснення змін/відкидань. */
    public static final class RemapResult {
        public final DecodedProvince province;
        public final List<String> warnings;

        RemapResult(DecodedProvince province, List<String> warnings) {
            this.province = province;
            this.warnings = warnings;
        }
    }

    private static Province_GameData2 toGameData(DecodedProvince data) {
        var game = new Province_GameData2();
        game.lPointsX = arrayToShortList(data.pointsX);
        game.lPointsY = arrayToShortList(data.pointsY);

        game.lProvinceBorder = new ArrayList<>();
        if (data.borders != null) {
            for (DecodedBorder db : data.borders) {
                game.lProvinceBorder.add(new Province_Border_GameData(
                        db.withProvinceId,
                        arrayToShortList(db.pointsX),
                        arrayToShortList(db.pointsY)));
            }
        }

        game.lNeighboringProvinces = intListToShortList(data.neighboringProvinces);
        game.lNeighboringSeaProvinces = intListToShortList(data.neighboringSeaProvinces);
        game.iLevelOfPort = data.levelOfPort;
        game.iPort_ShiftX = data.portShiftX;
        game.iPort_ShiftY = data.portShiftY;

        var info = new Province_Info_GameData3();
        info.iContinentID = data.continentId;
        info.iRegionID = data.regionId;
        info.iShiftX = data.shiftX;
        info.iShiftY = data.shiftY;
        info.fGrowthRate = data.growthRate;
        info.sTerrainTAG = data.terrainTag;
        game.provinceInfo = info;

        return game;
    }

    // ---------------------------------------------------------------------
    // Конвертери для нашого текстового формату редактора
    // ---------------------------------------------------------------------

    /** "X1,X2,X3,..." - той самий формат, який уже розуміє addProvinceFromLines() у GamePanel. */
    public static String toCsvLine(short[] values) {
        var sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /** Двострядковий запис "X1,X2,...\nY1,Y2,..." - як пара рядків у mapAoC2_v2.txt / файлах "0","1",... */
    public static String toEditorTextFormat(DecodedProvince p) {
        return toCsvLine(p.pointsX) + "\n" + toCsvLine(p.pointsY);
    }

    public static List<Integer> toIntList(short[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (short v : values) list.add((int) v);
        return list;
    }

    // ---------------------------------------------------------------------
    // Дані, які реально потрібні редактору
    // ---------------------------------------------------------------------

    public static final class DecodedProvince {
        public short[] pointsX = new short[0];
        public short[] pointsY = new short[0];

        public int continentId;
        public int regionId;
        public int shiftX;
        public int shiftY;
        public float growthRate;
        public String terrainTag;

        public int levelOfPort;
        public int portShiftX;
        public int portShiftY;

        public List<Integer> neighboringProvinces = new ArrayList<>();
        public List<Integer> neighboringSeaProvinces = new ArrayList<>();
        public List<DecodedBorder> borders = new ArrayList<>();

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "DecodedProvince{points=%d, continent=%d, region=%d, terrain=%s, neighbors=%d, borders=%d}",
                    pointsX.length, continentId, regionId, terrainTag,
                    neighboringProvinces.size(), borders.size());
        }
    }

    /**
     * Один запис lProvinceBorder - спільна ділянка контуру з конкретним
     * сусідом (withProvinceId), звірено з реальним Province_Border_GameData.
     */
    public static final class DecodedBorder {
        public int withProvinceId;
        public short[] pointsX = new short[0];
        public short[] pointsY = new short[0];
    }

}
