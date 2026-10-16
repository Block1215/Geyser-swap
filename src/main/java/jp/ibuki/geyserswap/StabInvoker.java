package jp.ibuki.geyserswap;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 槍の突き (バニラの PiercingWeapon#attack) をサーバー内部から実行する。
 * Paper API に突きを出すメソッドが無いので、Mojang 名のままの内部をリフレクションで呼ぶ。
 * 突きの後の postPiercingAttack で、突進 (Lunge) などのエンチャント効果も通常どおり発動する。
 */
final class StabInvoker {

    private final Logger logger;
    private boolean resolved;
    private Method getHandle;
    private Method getItemBySlot;
    private Method getComponent;
    private Method attack;
    private Object mainHand;
    private Object piercingType;

    StabInvoker(Logger logger) {
        this.logger = logger;
    }

    private boolean resolve(Player player) {
        if (resolved) {
            return attack != null;
        }
        resolved = true;
        try {
            getHandle = player.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(player);
            ClassLoader loader = handle.getClass().getClassLoader();
            Class<?> slotClass = Class.forName("net.minecraft.world.entity.EquipmentSlot", true, loader);
            Class<?> livingClass = Class.forName("net.minecraft.world.entity.LivingEntity", true, loader);
            Class<?> typeClass = Class.forName("net.minecraft.core.component.DataComponentType", true, loader);
            Class<?> componentsClass = Class.forName("net.minecraft.core.component.DataComponents", true, loader);
            Class<?> piercingClass = Class.forName("net.minecraft.world.item.component.PiercingWeapon", true, loader);
            Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack", true, loader);

            mainHand = slotClass.getField("MAINHAND").get(null);
            piercingType = componentsClass.getField("PIERCING_WEAPON").get(null);
            getItemBySlot = livingClass.getMethod("getItemBySlot", slotClass);
            getComponent = stackClass.getMethod("get", typeClass);
            attack = piercingClass.getMethod("attack", livingClass, slotClass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "槍の突きを呼び出せません。槍のチャージスワップは無効です", e);
            attack = null;
        }
        return attack != null;
    }

    /** メインハンドの槍で突く。突けなければ false */
    boolean stab(Player player) {
        if (!resolve(player)) {
            return false;
        }
        try {
            Object handle = getHandle.invoke(player);
            Object stack = getItemBySlot.invoke(handle, mainHand);
            Object piercing = getComponent.invoke(stack, piercingType);
            if (piercing == null) {
                return false;
            }
            attack.invoke(piercing, handle, mainHand);
            return true;
        } catch (ReflectiveOperationException e) {
            logger.log(Level.WARNING, "槍の突きに失敗しました", e);
            return false;
        }
    }
}
