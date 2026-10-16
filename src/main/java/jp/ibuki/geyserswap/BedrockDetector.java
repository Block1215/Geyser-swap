package jp.ibuki.geyserswap;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 統合版プレイヤーかどうかを Floodgate / Geyser の API で判定する。
 * どちらもリフレクションで呼ぶので、片方しか入っていない構成でも動く。
 * (Geyser をプロキシ側に置く構成では、バックエンドに Floodgate が必要)
 */
final class BedrockDetector {

    private Object floodgateApi;
    private Method floodgateIsBedrock;

    private Object geyserApi;
    private Method geyserIsBedrock;

    BedrockDetector(GeyserSwapPlugin plugin) {
        Plugin floodgate = plugin.getServer().getPluginManager().getPlugin("floodgate");
        if (floodgate != null) {
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, floodgate.getClass().getClassLoader());
                floodgateApi = api.getMethod("getInstance").invoke(null);
                floodgateIsBedrock = api.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Floodgate API を読めませんでした", e);
            }
        }

        Plugin geyser = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser != null) {
            try {
                Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi", true, geyser.getClass().getClassLoader());
                geyserApi = api.getMethod("api").invoke(null);
                geyserIsBedrock = api.getMethod("isBedrockPlayer", UUID.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Geyser API を読めませんでした", e);
            }
        }

        if (floodgateIsBedrock == null && geyserIsBedrock == null) {
            plugin.getLogger().warning("Geyser-Spigot も Floodgate も見つかりません。bedrock-only: true のままでは誰にも適用されません");
        }
    }

    boolean isBedrock(Player player) {
        UUID uuid = player.getUniqueId();
        if (floodgateIsBedrock != null && call(floodgateIsBedrock, floodgateApi, uuid)) {
            return true;
        }
        return geyserIsBedrock != null && call(geyserIsBedrock, geyserApi, uuid);
    }

    private static boolean call(Method method, Object target, UUID uuid) {
        try {
            return (Boolean) method.invoke(target, uuid);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    String describe() {
        if (floodgateIsBedrock != null && geyserIsBedrock != null) return "Floodgate + Geyser";
        if (floodgateIsBedrock != null) return "Floodgate";
        if (geyserIsBedrock != null) return "Geyser";
        return "なし";
    }
}
