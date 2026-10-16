package age.of.civilizations2.jakowski.lukasz;

import java.io.Serializable;
import java.util.List;

/**
 * Модель age.of.civilizations2.jakowski.lukasz.Province_Border_GameData,
 * звірена з реальним декомпільованим кодом гри:
 *
 *   class Province_Border_GameData implements Serializable {
 *       private static final long serialVersionUID = 0;
 *       private List<Short> lPointsX;
 *       private List<Short> lPointsY;
 *       private int withProvinceID;
 *       protected Province_Border_GameData(int i, List<Short> list, List<Short> list2) { ... }
 *       protected final List<Short> getPointsX() { ... }
 *       protected final List<Short> getPointsY() { ... }
 *       protected final int getWithProvinceID() { ... }
 *   }
 *
 * Поля тут навмисно public (замість private) і додано публічний
 * no-arg конструктор - на сумісність серіалізації це НЕ впливає:
 * стандартний механізм Java Serialization співставляє поля виключно
 * за іменем і типом, а не за модифікатором доступу (readObject/
 * writeObject працюють через внутрішню reflection незалежно від
 * visibility). Спрощення потрібне лише для того, щоб ProvinceDecoder
 * (інший пакет) міг зручно читати/записувати ці поля напряму.
 *
 * serialVersionUID = 0 підтверджений з оригіналу - точно як у
 * Province_GameData2 / Province_Info_GameData3.
 */
public class Province_Border_GameData implements Serializable {

    private static final long serialVersionUID = 0;

    public List<Short> lPointsX;
    public List<Short> lPointsY;
    public int withProvinceID;

    public Province_Border_GameData() {
    }

    public Province_Border_GameData(int withProvinceID, List<Short> lPointsX, List<Short> lPointsY) {
        this.withProvinceID = withProvinceID;
        this.lPointsX = lPointsX;
        this.lPointsY = lPointsY;
    }
}
