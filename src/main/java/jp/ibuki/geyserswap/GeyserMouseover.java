package jp.ibuki.geyserswap;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 統合版クライアントが «今照準が重なっている相手» として送ってくる MOUSEOVER を、
 * Geyser のセッション (GeyserSession#getMouseoverEntity) から読む。
 * クライアント自身の画面で判定した相手なので、サーバー側で視線を引き直すより正確。
 * Geyser が同じサーバーに入っていない構成 (プロキシ側の Geyser + Floodgate) では使えない。
 */
final class GeyserMouseover {

    private final Logger logger;
    private Object api;
    private Method connectionByUuid;
    private Method getMouseover;
    private Method getUuid;
    private boolean failed;

    GeyserMouseover(Plugin plugin) {
        this.logger = plugin.getLogger();
        Plugin geyser = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) {
            failed = true;
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", true, geyser.getClass().getClassLoader());
            connectionByUuid = apiClass.getMethod("connectionByUuid", UUID.class);
            // 内部クラスのメソッド名は版で変わる (getUuid → uuid) ので、公開 API のインターフェースから取る
            Class<?> entityApi = Class.forName("org.geysermc.geyser.api.entity.type.GeyserEntity", true, geyser.getClass().getClassLoader());
            getUuid = entityApi.getMethod("uuid");
            api = apiClass.getMethod("api").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.log(Level.WARNING, "Geyser API を読めません。マウスオーバーは使わず視線で判定します", e);
            failed = true;
        }
    }

    boolean available() {
        return !failed;
    }

    /** 照準が重なっている相手の UUID。無い・読めないときは null */
    UUID target(Player player) {
        if (failed) {
            return null;
        }
        try {
            if (api == null) {
                return null;
            }
            Object session = connectionByUuid.invoke(api, player.getUniqueId());
            if (session == null) {
                return null;
            }
            if (getMouseover == null) {
                getMouseover = session.getClass().getMethod("getMouseoverEntity");
            }
            Object entity = getMouseover.invoke(session);
            if (entity == null) {
                return null;
            }
            return (UUID) getUuid.invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "Geyser のマウスオーバーを読めません。以後は視線で判定します", e);
            failed = true;
            return null;
        }
    }
}
