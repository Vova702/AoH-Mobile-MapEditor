package age.of.civilizations2.jakowski.lukasz;

import java.io.Serializable;
import java.util.List;

/**
 * Модель age.of.civilizations2.jakowski.lukasz.Province_GameData2, звірена
 * з реальним декомпільованим кодом гри: serialVersionUID = 0L оголошено
 * явно в оригіналі, тож жодного workaround'у для версій більше не треба.
 *
 * Увага: lPointsX / lPointsY / lNeighboringProvinces / lNeighboringSeaProvinces
 * у грі зберігаються як List<Short>, а НЕ як short[] - саме так вони й
 * повинні бути оголошені тут, інакше стандартна Java-серіалізація не
 * зможе співставити поля за типом.
 */
public class Province_GameData2 implements Serializable {

    private static final long serialVersionUID = 0L;

    public List<Short> lPointsX;
    public List<Short> lPointsY;

    public List<Province_Border_GameData> lProvinceBorder;
    public List<Short> lNeighboringProvinces;
    public List<Short> lNeighboringSeaProvinces;

    public int iLevelOfPort;
    public int iPort_ShiftX;
    public int iPort_ShiftY;

    public Province_Info_GameData3 provinceInfo;
}
