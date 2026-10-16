package jp.ibuki.geyserswap;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 統合版クライアントが送る «照準が重なっている相手» (InteractPacket の MOUSEOVER) を、
 * Geyser が処理する前に横から読む。
 *
 * Geyser は MOUSEOVER の «照準を外した» (ID 0) を捨てるので、Geyser の状態を読むと
 * 最後に見た相手が残り続ける。そこで統合版セッションのパケットハンドラーを中継役 (Proxy) に
 * 差し替え、全パケットをそのまま元の Geyser のハンドラーへ渡しつつ、MOUSEOVER だけ時刻付きで記録する。
 * Geyser が同じサーバーに入っていない構成 (プロキシ側の Geyser + Floodgate) では使えない。
 */
final class GeyserMouseover {

    /** 照準の状態。target が null なら照準を外している */
    private record Look(UUID target, long nanos, UUID lastTarget, long clearedNanos) {
    }

    private final Logger logger;
    private final Map<UUID, Look> looks = new ConcurrentHashMap<>();
    private final Map<UUID, Object[]> attached = new ConcurrentHashMap<>(); // {BedrockSession, 元のハンドラー}
    private Object api;
    private Method connectionByUuid;
    private Method getUpstream;
    private Method getSession;
    private Method getPacketHandler;
    private Method setPacketHandler;
    private Class<?> handlerInterface;
    private boolean failed;

    GeyserMouseover(Plugin plugin) {
        this.logger = plugin.getLogger();
        Plugin geyser = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) {
            failed = true;
            return;
        }
        try {
            ClassLoader loader = geyser.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", true, loader);
            connectionByUuid = apiClass.getMethod("connectionByUuid", UUID.class);
            api = apiClass.getMethod("api").invoke(null);
            getUpstream = Class.forName("org.geysermc.geyser.session.GeyserSession", true, loader).getMethod("getUpstream");
            getSession = Class.forName("org.geysermc.geyser.session.UpstreamSession", true, loader).getMethod("getSession");
            Class<?> bedrockSession = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockSession", true, loader);
            handlerInterface = Class.forName("org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler", true, loader);
            getPacketHandler = bedrockSession.getMethod("getPacketHandler");
            setPacketHandler = bedrockSession.getMethod("setPacketHandler", handlerInterface);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.log(Level.WARNING, "Geyser の内部を読めません。照準 (マウスオーバー) は使わず視線で判定します", e);
            failed = true;
        }
    }

    boolean available() {
        return !failed;
    }

    /** 統合版プレイヤーのセッションに中継役を入れる (参加時に呼ぶ) */
    void attach(Player player) {
        if (failed || api == null || attached.containsKey(player.getUniqueId())) {
            return;
        }
        try {
            Object geyserSession = connectionByUuid.invoke(api, player.getUniqueId());
            if (geyserSession == null) {
                return;
            }
            Object bedrockSession = getSession.invoke(getUpstream.invoke(geyserSession));
            Object original = getPacketHandler.invoke(bedrockSession);
            if (original == null || Proxy.isProxyClass(original.getClass())) {
                return;
            }
            Object proxy = Proxy.newProxyInstance(original.getClass().getClassLoader(), new Class<?>[]{handlerInterface},
                    new Tap(player.getUniqueId(), geyserSession, original));
            setPacketHandler.invoke(bedrockSession, proxy);
            attached.put(player.getUniqueId(), new Object[]{bedrockSession, original});
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "統合版セッションに照準の読み取りを入れられません。以後は視線で判定します", e);
            failed = true;
        }
    }

    /** 退出時。セッションは閉じるので記録だけ消す */
    void forget(UUID uuid) {
        attached.remove(uuid);
        looks.remove(uuid);
    }

    /** プラグイン停止時。Geyser の元のハンドラーに戻す */
    void detachAll() {
        for (Object[] pair : attached.values()) {
            try {
                setPacketHandler.invoke(pair[0], pair[1]);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        attached.clear();
        looks.clear();
    }

    /**
     * 空振りのときに当てる相手。
     * 今照準が重なっていればその相手。外れていても、外れたのが持ち替え以降なら
     * (持ち替えでリーチが短くなりクライアントが照準を外した) 持ち替え時に見ていた相手。
     */
    UUID targetFor(Player player, long swapNanos) {
        Look look = looks.get(player.getUniqueId());
        if (look == null) {
            return null;
        }
        if (look.target() != null) {
            return look.target();
        }
        return look.lastTarget() != null && look.clearedNanos() >= swapNanos - 50_000_000L ? look.lastTarget() : null;
    }

    /** デバッグ用: 照準の記録があるか */
    boolean hasRecord(Player player) {
        return looks.containsKey(player.getUniqueId());
    }

    /** Geyser のハンドラーへ全部そのまま渡しつつ、MOUSEOVER だけ記録する中継役 */
    private final class Tap implements InvocationHandler {
        private final UUID player;
        private final Object geyserSession;
        private final Object original;
        private Method getAction;
        private Method getRuntimeEntityId;
        private Method getEntityCache;
        private Method getEntityByGeyserId;
        private Method getUuid;

        Tap(UUID player, Object geyserSession, Object original) {
            this.player = player;
            this.geyserSession = geyserSession;
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && "handlePacket".equals(method.getName())
                    && args[0] != null && args[0].getClass().getSimpleName().equals("InteractPacket")) {
                try {
                    record(args[0]);
                } catch (Throwable ignored) {
                    // 記録に失敗しても Geyser の処理は止めない
                }
            }
            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void record(Object packet) throws ReflectiveOperationException {
            if (getAction == null) {
                getAction = packet.getClass().getMethod("getAction");
                getRuntimeEntityId = packet.getClass().getMethod("getRuntimeEntityId");
            }
            if (!"MOUSEOVER".equals(String.valueOf(getAction.invoke(packet)))) {
                return;
            }
            long now = System.nanoTime();
            long runtimeId = (long) getRuntimeEntityId.invoke(packet);
            Look before = looks.get(player);
            UUID last = before == null ? null : (before.target() != null ? before.target() : before.lastTarget());
            if (runtimeId == 0) {
                looks.put(player, new Look(null, now, last, now));
                return;
            }
            if (getEntityCache == null) {
                getEntityCache = geyserSession.getClass().getMethod("getEntityCache");
            }
            Object cache = getEntityCache.invoke(geyserSession);
            if (getEntityByGeyserId == null) {
                getEntityByGeyserId = cache.getClass().getMethod("getEntityByGeyserId", long.class);
            }
            Object entity = getEntityByGeyserId.invoke(cache, runtimeId);
            if (entity == null) {
                return;
            }
            if (getUuid == null) {
                // 内部クラスのメソッド名は版で変わるので、公開 API のインターフェースから取る
                getUuid = Class.forName("org.geysermc.geyser.api.entity.type.GeyserEntity", true,
                        entity.getClass().getClassLoader()).getMethod("uuid");
            }
            UUID target = (UUID) getUuid.invoke(entity);
            looks.put(player, new Look(target, now, target, before == null ? 0 : before.clearedNanos()));
        }
    }
}
