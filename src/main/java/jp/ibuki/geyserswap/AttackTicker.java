package jp.ibuki.geyserswap;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 攻撃チャージのカウンタ (net.minecraft の attackStrengthTicker / itemSwapTicker) を読み書きする。
 * Paper API には setter が無いので、Mojang 名のままのサーバー内部をリフレクションで触る。
 * 見つからない版では何もしない (チャージ引き継ぎだけが無効になる)。
 */
final class AttackTicker {

    private final Logger logger;
    private Method getHandle;
    private Field attackStrengthTicker;
    private Field itemSwapTicker;
    private boolean resolved;

    AttackTicker(Logger logger) {
        this.logger = logger;
    }

    private boolean resolve(Player player) {
        if (resolved) {
            return attackStrengthTicker != null;
        }
        resolved = true;
        try {
            getHandle = player.getClass().getMethod("getHandle");
            Class<?> handleClass = getHandle.invoke(player).getClass();
            attackStrengthTicker = findField(handleClass, "attackStrengthTicker");
            itemSwapTicker = findField(handleClass, "itemSwapTicker");
            if (attackStrengthTicker == null) {
                logger.warning("attackStrengthTicker が見つかりません。チャージ引き継ぎは無効です");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "サーバー内部のプレイヤーを取得できません。チャージ引き継ぎは無効です", e);
            attackStrengthTicker = null;
        }
        return attackStrengthTicker != null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** {attackStrengthTicker, itemSwapTicker}。読めなければ null */
    int[] read(Player player) {
        if (!resolve(player)) {
            return null;
        }
        try {
            Object handle = getHandle.invoke(player);
            int swap = itemSwapTicker != null ? itemSwapTicker.getInt(handle) : 0;
            return new int[]{attackStrengthTicker.getInt(handle), swap};
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    void write(Player player, int attack, int swap) {
        if (!resolve(player)) {
            return;
        }
        try {
            Object handle = getHandle.invoke(player);
            attackStrengthTicker.setInt(handle, attack);
            if (itemSwapTicker != null) {
                itemSwapTicker.setInt(handle, swap);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
