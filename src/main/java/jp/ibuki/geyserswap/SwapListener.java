package jp.ibuki.geyserswap;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 版の属性スワップ:
 *   ホットバー切り替えと攻撃が同じ tick に届くと、攻撃力・攻撃速度などの «属性» は
 *   切り替え前のアイテム (装備の反映は次の entity tick) のまま、エンチャント・耐久消費・
 *   メイスの落下ボーナスは切り替え後のアイテム (getWeaponItem) から取られる。
 *   さらにチャージのリセットも次の tick なので、切り替え前のチャージで殴れる。
 *
 * 統合版は Geyser 経由でも切り替えと攻撃を 50ms 以内に揃えるのが難しいので、
 * 切り替えから grace-ms 以内の攻撃を «同じ tick» とみなし、攻撃の直前に
 * 切り替え前アイテムの属性とチャージを一時的に戻す。属性は次の tick で元に戻す。
 */
final class SwapListener implements Listener {

    /** 切り替え 1 回分の記録 */
    private static final class PendingSwap {
        ItemStack oldItem;      // 属性を使う «切り替え前» の武器 (連続切り替え中で一番強いもの)
        long nanos;             // 最後の切り替え時刻 (猶予はここから数える)
        final int tick;         // 最初の切り替えの tick (チャージ計算用)
        final int[] ticker;     // 最初の切り替え時点のチャージ (読めなければ null)
        boolean inAir;          // 最後の切り替えが空中だったか
        boolean landed;         // 最後の切り替え後に着地したか
        boolean applied;        // サーバーが新しいアイテムの属性を反映済みか

        PendingSwap(ItemStack oldItem, long nanos, int tick, int[] ticker, boolean inAir) {
            this.oldItem = oldItem;
            this.nanos = nanos;
            this.tick = tick;
            this.ticker = ticker;
            this.inAir = inAir;
        }
    }

    /** クライアントが見ていた相手は少し遅れた位置なので、槍のリーチに足す余裕 (ブロック) */
    private static final double MOUSEOVER_SLACK = 1.0;

    /** 直前に成立したスワップ (持ち戻し判定用) */
    private record Consumed(ItemStack oldItem, long nanos) {
    }

    /** 一時的に差し替えた属性を戻すための記録 */
    private record Restore(AttributeInstance instance, List<AttributeModifier> added, List<AttributeModifier> saved) {
        void run() {
            for (AttributeModifier modifier : added) {
                if (!sameModifier(instance.getModifier(modifier.key()), modifier)) {
                    // サーバー側が既に装備を反映し直している (攻撃後の装備更新・再度の持ち替え)
                    return;
                }
            }
            for (AttributeModifier modifier : added) {
                instance.removeModifier(modifier.key());
            }
            for (AttributeModifier modifier : saved) {
                if (instance.getModifier(modifier.key()) == null) {
                    instance.addTransientModifier(modifier);
                }
            }
        }
    }

    private final GeyserSwapPlugin plugin;
    private final Map<UUID, PendingSwap> pending = new HashMap<>();
    private final Map<UUID, List<Restore>> restores = new HashMap<>();
    private final Map<UUID, Consumed> consumed = new HashMap<>();
    private final Map<UUID, Integer> lastAttackTick = new HashMap<>();
    private final Set<UUID> reachAttacks = new HashSet<>();
    private final Map<UUID, Integer> lastStabTick = new HashMap<>();

    SwapListener(GeyserSwapPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isTarget(Player player) {
        return plugin.swapEnabled() && (!plugin.bedrockOnly() || plugin.bedrockDetector().isBedrock(player));
    }

    private static long elapsedMs(long since, long now) {
        return (now - since) / 1_000_000L;
    }

    /**
     * 切り替えがまだ «同じ tick» とみなせるか。
     * 空中でメイスに持ち替えて落下攻撃になる場合だけ、着地するまで air-grace-max-ms を上限に延長する。
     * (統合版は落下の途中で持ち替えるので、通常の猶予では殴る前に切れてしまう。
     *  メイス以外まで延長すると、空中で普通に持ち替えて殴っただけでも前の武器の属性になってしまう)
     */
    private boolean withinWindow(Player player, PendingSwap swap, long now) {
        long elapsed = elapsedMs(swap.nanos, now);
        if (elapsed <= plugin.graceMs()) {
            return true;
        }
        return plugin.airGrace() && swap.inAir && !swap.landed && elapsed <= plugin.airGraceMaxMs()
                && player.getInventory().getItemInMainHand().getType() == Material.MACE
                && player.getFallDistance() > MACE_SMASH_FALL_DISTANCE;
    }

    /** MaceItem.SMASH_ATTACK_FALL_THRESHOLD */
    private static final float MACE_SMASH_FALL_DISTANCE = 1.5F;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemHeldEarly(PlayerItemHeldEvent event) {
        // サーバーが新しい手持ちとの差分を取る前に、一時的に差し替えた属性を戻しておく
        restoreNow(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!isTarget(player)) {
            pending.remove(player.getUniqueId());
            return;
        }
        long now = System.nanoTime();
        UUID uuid = player.getUniqueId();
        PendingSwap existing = pending.get(uuid);
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        Consumed last = consumed.get(uuid);
        if (plugin.ignoreSwapBack() && last != null && elapsedMs(last.nanos, now) <= plugin.graceMs()
                && next != null && next.isSimilar(last.oldItem)) {
            // スワップで殴った直後に元の武器へ戻しただけ。これをスワップにすると次の一撃が弱くなる
            consumed.remove(uuid);
            pending.remove(uuid);
            debug(player, String.format("切り替え %d→%d (%s → %s)  持ち戻しなのでスワップにしない",
                    event.getPreviousSlot(), event.getNewSlot(), name(previous), name(next)));
            return;
        }
        ItemStack prev = previous == null ? ItemStack.empty() : previous.clone();
        boolean inAir = !player.isOnGround();
        String move = String.format("切り替え %d→%d (%s → %s)", event.getPreviousSlot(), event.getNewSlot(), name(previous), name(next));
        if (existing != null && (!existing.applied || elapsedMs(existing.nanos, now) <= plugin.graceMs())) {
            // 猶予内の連続切り替え (パールやウィンドチャージを挟む等) は 1 回の切り替えとみなす。
            // 属性は途中で持った一番強い武器のもの、猶予は最後の切り替えから数える
            if (attackBonus(prev) > attackBonus(existing.oldItem)) {
                existing.oldItem = prev;
            }
            existing.nanos = now;
            existing.inAir = inAir;
            existing.landed = false;
            debug(player, move + "  連続: 切り替え前の武器は " + name(existing.oldItem) + airNote(inAir, next));
            return;
        }
        // 切り替え前が武器でなくても記録する (チャージの引き継ぎは持ち物に関係ないため)。
        // 属性の差し替えは攻撃時に「切り替え前が武器」のときだけ行う
        pending.put(uuid, new PendingSwap(prev, now, Bukkit.getCurrentTick(), plugin.attackTicker().read(player), inAir));
        debug(player, move + String.format("  猶予 %dms", plugin.graceMs())
                + (isWeapon(prev) ? "" : "  (切り替え前が武器でないのでチャージのみ)") + airNote(inAir, next));
    }

    private String airNote(boolean inAir, ItemStack next) {
        return inAir && next != null && next.getType() == Material.MACE && plugin.airGrace()
                ? "  (空中でメイスへ: 落下攻撃なら着地まで延長)" : "";
    }

    /** メインハンドでの攻撃力の上乗せ (ADD_NUMBER の attack_damage 修正値の合計) */
    private static double attackBonus(ItemStack item) {
        double sum = 0;
        for (AttributeModifier modifier : mainHandModifiers(item).getOrDefault(Attribute.ATTACK_DAMAGE, List.of())) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    /** 攻撃力の上乗せがあるか、槍のようにリーチを持つアイテム */
    private static boolean isWeapon(ItemStack item) {
        return attackBonus(item) > 0 || (item != null && !item.isEmpty() && item.hasData(DataComponentTypes.ATTACK_RANGE));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PendingSwap swap = pending.get(event.getPlayer().getUniqueId());
        if (swap != null && swap.inAir && !swap.landed && event.getPlayer().isOnGround()) {
            swap.landed = true;
        }
    }

    /**
     * 槍のリーチスワップ。
     * Java 版では槍を持った状態の照準 (槍のリーチ) で選んだ相手を、同じ tick に持ち替えた武器で殴れる。
     * 統合版クライアントは自分のリーチ (3 ブロック) より遠い相手には攻撃を送らず空振りになるので、
     * 槍から持ち替えた直後の空振りを、槍の attack_range でサーバー側から照準し直して攻撃にする。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || !isTarget(player)) {
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.hasData(DataComponentTypes.PIERCING_WEAPON)) {
            stabSwap(player, inHand);
            return;
        }
        if (!plugin.spearReach()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Integer attackedTick = lastAttackTick.get(uuid);
        if (attackedTick != null && attackedTick == Bukkit.getCurrentTick()) {
            return; // 攻撃に付随する腕振り
        }
        PendingSwap swap = pending.get(uuid);
        if (swap == null || !withinWindow(player, swap, System.nanoTime())) {
            return;
        }
        AttackRange oldRange = swap.oldItem.getData(DataComponentTypes.ATTACK_RANGE);
        ItemStack held = player.getInventory().getItemInMainHand();
        if (oldRange == null || held.hasData(DataComponentTypes.PIERCING_WEAPON)) {
            return;
        }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        double min = (creative ? oldRange.minCreativeReach() : oldRange.minReach()) - oldRange.hitboxMargin();
        double max = (creative ? oldRange.maxCreativeReach() : oldRange.maxReach()) + oldRange.hitboxMargin();
        double normal = normalReach(player, held, creative);
        if (max <= normal) {
            debug(player, String.format("空振り (%s → %s)  切り替え前のリーチ %.2f が通常 %.2f 以下", name(swap.oldItem), name(held), max, normal));
            return;
        }

        // 空振りした瞬間の落下距離 (視線を待つ 1 tick の間に着地しても、空中で振った落下攻撃として扱う)
        float fallAtSwing = player.getFallDistance();
        // 1) クライアント自身が «照準が重なっている» と送ってきた相手
        UUID looked = plugin.mouseover().targetFor(player, swap.nanos);
        if (looked != null) {
            Entity e = player.getWorld().getEntity(looked);
            Location eye = player.getEyeLocation();
            if (e != null && reachable(player, e)) {
                double d = distanceToBox(eye, e);
                // 通常リーチ内は統合版クライアント自身が «外れ» と判定した攻撃なので当てない (統合版だけの補助になる)
                if (d > normal && d >= min && d <= max + MOUSEOVER_SLACK) {
                    reachAttack(player, uuid, e, d, "マウスオーバー", fallAtSwing);
                    return;
                }
                debug(player, String.format("マウスオーバーの相手 %s は %.2f ブロック: 対象外 (槍のリーチが要る %.2f〜%.2f の外)",
                        e.getName(), d, Math.max(min, normal), max + MOUSEOVER_SLACK));
            }
        }
        // 2) 記録が無ければ視線で探す。Geyser は同じ入力の «空振り» を «視線の更新» より先に送るので、
        //    1 tick 待って更新後の視線で引く
        double margin = oldRange.hitboxMargin();
        Bukkit.getScheduler().runTask(plugin, () -> reachByRay(player, uuid, held, min, max, normal, margin, fallAtSwing));
    }

    private void reachByRay(Player player, UUID uuid, ItemStack held, double min, double max, double normal, double margin, float fallAtSwing) {
        if (!player.isOnline() || !player.getInventory().getItemInMainHand().isSimilar(held)) {
            return;
        }
        Integer attackedTick = lastAttackTick.get(uuid);
        if (attackedTick != null && Bukkit.getCurrentTick() - attackedTick <= 1) {
            return; // 待っている間に通常の攻撃が届いた
        }
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), max, FluidCollisionMode.NEVER, true,
                margin, e -> e != player && reachable(player, e));
        Entity target = hit == null ? null : hit.getHitEntity();
        if (target == null) {
            debug(player, String.format("空振り (槍から %s)  槍のリーチ %.1f 以内に相手なし (照準の記録%s)%s",
                    name(held), max, !plugin.mouseover().available() ? "は使えない構成" : plugin.mouseover().hasRecord(player) ? "も外れていた" : "なし", nearestHint(player, eye)));
            return;
        }
        double distance = distanceToBox(eye, target);
        if (distance <= normal || distance < min || distance > max) {
            // 通常リーチ内は統合版クライアント自身が «外れ» と判定した攻撃なので当てない
            debug(player, String.format("空振り (槍から %s)  相手まで %.2f ブロック: 対象外 (槍のリーチが要る %.2f〜%.2f の外)",
                    name(held), distance, Math.max(min, normal), max));
            return;
        }
        reachAttack(player, uuid, target, distance, "視線", fallAtSwing);
    }

    private void reachAttack(Player player, UUID uuid, Entity target, double distance, String how, float fallAtSwing) {
        float fallNow = player.getFallDistance();
        boolean restoreFall = fallAtSwing > MACE_SMASH_FALL_DISTANCE && fallAtSwing > fallNow;
        debug(player, String.format("槍のリーチスワップ: %.2f ブロック先の %s を攻撃 (%s)%s", distance, target.getName(), how,
                restoreFall ? String.format("  落下距離は空振り時の %.2f (今 %.2f)", fallAtSwing, fallNow)
                        : fallNow > MACE_SMASH_FALL_DISTANCE ? String.format("  落下距離 %.2f", fallNow) : ""));
        lastAttackTick.put(uuid, Bukkit.getCurrentTick());
        reachAttacks.add(uuid);
        if (restoreFall) {
            player.setFallDistance(fallAtSwing);
        }
        try {
            player.attack(target);
        } finally {
            reachAttacks.remove(uuid);
            // 攻撃は同期で終わっているので、差し替えた属性はすぐ戻す (次の tick まで残さない)
            restoreNow(uuid);
            if (restoreFall && player.getFallDistance() == fallAtSwing) {
                player.setFallDistance(fallNow); // 落下攻撃にならなかったときは元に戻す
            }
        }
    }

    private static boolean reachable(Player player, Entity e) {
        return e != player && e instanceof LivingEntity living && !living.isDead() && player.canSee(e)
                && !(e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR);
    }

    /** 視線と «目 → 相手の当たり判定の中心» のなす角 (度) */
    private static double angleTo(Location eye, Entity e) {
        Vector to = e.getBoundingBox().getCenter().subtract(eye.toVector());
        return Math.toDegrees(to.angle(eye.getDirection()));
    }

    /** 外れたときの手がかり: 一番近い相手の距離と、視線からの角度 */
    private static String nearestHint(Player player, Location eye) {
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(8, 8, 8)) {
            if (reachable(player, e)) {
                double d = distanceToBox(eye, e);
                if (d < best) {
                    best = d;
                    nearest = e;
                }
            }
        }
        return nearest == null ? "" : String.format("  (一番近い %s: %.2f ブロック・視線から %.0f°)", nearest.getName(), best, angleTo(eye, nearest));
    }

    /**
     * 槍のチャージスワップ (突進)。
     * 槍は最低チャージ 1.0 (満タン) が無いと突けない。Java 版では持ち替えと突きが同じ tick だと、
     * 攻撃速度もゲージも持ち替え前のまま判定されるので、チャージの短い物で溜め切ってから槍に替えて
     * すぐ突けば突きが通り、突進 (Lunge) が出る。
     * 統合版は tick をまたぐのでゲージが槍の遅い速度で 0 からになり、突き自体が弾かれる。
     * Geyser は突き (STAB) の直後に腕振りを送るので、ここで «弾かれた突き» を見つけて、
     * 持ち替え前の属性とゲージに戻したうえで突きを実行し直す。
     */
    private void stabSwap(Player player, ItemStack spear) {
        if (!plugin.keepAttackCharge()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        // 突き 1 回につき腕振りが 2 回届く (Geyser の STAB 直後と、統合版自身の腕振り) ので 2 回目は無視
        Integer stabTick = lastStabTick.put(uuid, Bukkit.getCurrentTick());
        if (stabTick != null && Bukkit.getCurrentTick() - stabTick <= 2) {
            return;
        }
        PendingSwap swap = pending.get(uuid);
        Float required = spear.getData(DataComponentTypes.MINIMUM_ATTACK_CHARGE);
        int[] before = plugin.attackTicker().read(player);
        if (required == null || required <= 0 || before == null) {
            return;
        }
        String food = "  満腹度 " + player.getFoodLevel() + (player.getFoodLevel() < 7 ? " (7 未満なので突進は出ない)" : "");
        if (before[0] < before[1]) {
            // 持ち替え後に攻撃カウンタだけがリセットされている = 突き (か攻撃) はもう通った
            debug(player, "突き (" + name(spear) + ") 通った" + food);
            return;
        }
        if (canStab(player, before[0], required)) {
            debug(player, "突き (" + name(spear) + ") チャージ満タン" + food);
            return; // バニラでも通る状態 (突きは通っている)
        }
        if (swap == null) {
            debug(player, String.format("突き (%s) 弾かれた: 槍のチャージ不足 %.0f%% (持ち替え記録なし)", name(spear), player.getAttackCooldown() * 100));
            return;
        }
        long sinceSwap = elapsedMs(swap.nanos, System.nanoTime());
        if (!swap.applied || !withinWindow(player, swap, System.nanoTime())) {
            // 猶予を広げていれば通ったかを試算して出す (属性とゲージは試したあと元に戻す)
            applyOldAttributes(player, swap.oldItem, spear);
            restoreCharge(player, swap);
            int[] trial = plugin.attackTicker().read(player);
            boolean wouldPass = trial != null && canStab(player, trial[0], required);
            restoreNow(uuid);
            plugin.attackTicker().write(player, before[0], before[1]);
            debug(player, String.format("突き (%s) 弾かれた: 持ち替えから %dms > 猶予 %dms%s",
                    name(spear), sinceSwap, plugin.graceMs(),
                    wouldPass ? "  (持ち替え前のチャージは満タン: 猶予が " + (sinceSwap + 10) + "ms 以上なら通った)"
                            : "  (持ち替え前 " + name(swap.oldItem) + " のチャージも足りない)"));
            return;
        }

        long elapsed = elapsedMs(swap.nanos, System.nanoTime());
        applyOldAttributes(player, swap.oldItem, spear);
        boolean charged = restoreCharge(player, swap);
        int[] now = plugin.attackTicker().read(player);
        if (now == null || !charged || !canStab(player, now[0], required)) {
            restoreNow(uuid);
            plugin.attackTicker().write(player, before[0], before[1]);
            debug(player, String.format("突き (%s) 弾かれた: 持ち替え前 (%s) のチャージでも足りない", name(spear), name(swap.oldItem)));
            return;
        }
        pending.remove(uuid);
        consumed.put(uuid, new Consumed(swap.oldItem, System.nanoTime()));
        lastAttackTick.put(uuid, Bukkit.getCurrentTick());
        boolean ok = plugin.stabInvoker().stab(player);
        restoreNow(uuid); // 突きは同期で終わっているので、差し替えた属性はすぐ戻す
        debug(player, String.format("槍のチャージスワップ: %s → %s  %dms  突き%s  満腹度 %d%s", name(swap.oldItem), name(spear), elapsed,
                ok ? "を実行" : "に失敗", player.getFoodLevel(), player.getFoodLevel() < 7 ? " (7 未満なので突進は出ない)" : ""));
    }

    /** バニラの Player#cannotAttackWithItem(item, 5) の逆 */
    private static boolean canStab(Player player, int ticker, float required) {
        double speed = attributeValue(player, Attribute.ATTACK_SPEED);
        if (speed <= 0) {
            return false;
        }
        double delay = 1.0 / speed * 20.0;
        return (ticker + 5) / delay >= required;
    }

    /** 今の手持ちでの通常のリーチ (統合版クライアントが自分で攻撃を送る距離の目安) */
    private static double normalReach(Player player, ItemStack held, boolean creative) {
        AttackRange range = held.getData(DataComponentTypes.ATTACK_RANGE);
        if (range != null) {
            return (creative ? range.maxCreativeReach() : range.maxReach()) + range.hitboxMargin();
        }
        return attributeValue(player, Attribute.ENTITY_INTERACTION_RANGE);
    }

    private static double distanceToBox(Location eye, Entity entity) {
        BoundingBox box = entity.getBoundingBox();
        double x = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        return Math.sqrt(eye.toVector().distanceSquared(new Vector(x, y, z)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PendingSwap swap = pending.get(player.getUniqueId());
        if (swap != null && !swap.applied && event.getEquipmentChanges().containsKey(EquipmentSlot.HAND)) {
            swap.applied = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer();
        if (!isTarget(player)) {
            pending.remove(player.getUniqueId());
            return;
        }
        if (event.isCancelled() || !event.willAttack()) {
            // 他のプラグインに取り消された攻撃では記録を消費しない (直後の本当の攻撃でスワップが効くように)
            debug(player, "攻撃 (" + name(player.getInventory().getItemInMainHand()) + ") は取り消された/攻撃にならない");
            return;
        }
        PendingSwap swap = pending.remove(player.getUniqueId());
        long now = System.nanoTime();
        lastAttackTick.put(player.getUniqueId(), Bukkit.getCurrentTick());
        String held = name(player.getInventory().getItemInMainHand());
        if (swap == null) {
            debug(player, String.format("攻撃 (%s) 切り替え記録なし → 通常攻撃  攻撃力 %.1f チャージ %.0f%%",
                    held, attributeValue(player, Attribute.ATTACK_DAMAGE), player.getAttackCooldown() * 100));
            return;
        }
        long elapsedMs = elapsedMs(swap.nanos, now);
        if (!swap.applied) {
            consumed.put(player.getUniqueId(), new Consumed(swap.oldItem, now));
            // 切り替えと同じ tick の攻撃。バニラの属性スワップがそのまま起きる
            debug(player, String.format("攻撃 (%s) 同 tick %dms → バニラのスワップ  攻撃力 %.1f チャージ %.0f%%",
                    held, elapsedMs, attributeValue(player, Attribute.ATTACK_DAMAGE), player.getAttackCooldown() * 100));
            return;
        }
        // 槍のリーチスワップは空振りを受け取った時点で猶予を確かめ済み (視線を待つため攻撃は 1 tick 後になる)
        boolean reach = reachAttacks.contains(player.getUniqueId());
        if (!reach && !withinWindow(player, swap, now)) {
            debug(player, String.format("攻撃 (%s) 切り替えから %dms > 猶予 %dms%s → 通常攻撃", held, elapsedMs, plugin.graceMs(),
                    swap.inAir ? (swap.landed ? " (着地済み)" : " (空中延長も超過)") : ""));
            return;
        }
        ItemStack newItem = player.getInventory().getItemInMainHand();
        double damageBefore = attributeValue(player, Attribute.ATTACK_DAMAGE);

        // 属性の差し替え: 切り替え前が武器で、今の武器より弱くないとき (槍のリーチスワップは常に)
        String skip = null;
        if (!isWeapon(swap.oldItem)) {
            skip = "切り替え前 (" + name(swap.oldItem) + ") が武器でない";
        } else if (!reach && plugin.onlyStronger() && attackBonus(swap.oldItem) < attackBonus(newItem)) {
            skip = "切り替え前 (" + name(swap.oldItem) + ") のほうが弱い";
        }
        if (skip == null) {
            applyOldAttributes(player, swap.oldItem, newItem);
            consumed.put(player.getUniqueId(), new Consumed(swap.oldItem, now));
        }
        // チャージ: 殴った瞬間だけ、持ち替えでのリセットを取り消す (属性の差し替えとは独立)
        double chargeBefore = player.getAttackCooldown() * 100;
        boolean chargeKept = plugin.keepAttackCharge() && restoreCharge(player, swap);

        if (plugin.debug()) {
            String line = skip == null
                    ? String.format("疑似スワップ %s → %s  %dms  攻撃力 %.1f → %.1f",
                        name(swap.oldItem), name(newItem), elapsedMs, damageBefore, attributeValue(player, Attribute.ATTACK_DAMAGE))
                    : String.format("攻撃 (%s) %dms  属性はそのまま: %s", held, elapsedMs, skip);
            line += chargeKept
                    ? String.format("  チャージ %.0f%% → %.0f%% (引き継ぎ)", chargeBefore, player.getAttackCooldown() * 100)
                    : String.format("  チャージ %.0f%%", player.getAttackCooldown() * 100);
            player.sendMessage(Component.text("[GeyserSwap] " + line, NamedTextColor.GRAY));
            plugin.getLogger().info(player.getName() + ": " + line);
        }
    }

    /** 実際に入ったダメージ (デバッグ用) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (plugin.debug() && event.getDamager() instanceof Player player && isTarget(player)) {
            debug(player, String.format("  → %s に %.2f ダメージ (軽減前 %.2f)",
                    event.getEntity().getName(), event.getFinalDamage(), event.getDamage()));
        }
    }

    private void debug(Player player, String line) {
        if (plugin.debug()) {
            player.sendMessage(Component.text("[GeyserSwap] " + line, NamedTextColor.DARK_GRAY));
            plugin.getLogger().info(player.getName() + ": " + line);
        }
    }

    /** 手持ちの属性を切り替え前アイテムのものに差し替え、次の tick で戻す */
    private void applyOldAttributes(Player player, ItemStack oldItem, ItemStack newItem) {
        restoreNow(player.getUniqueId());

        Map<Attribute, List<AttributeModifier>> oldMods = mainHandModifiers(oldItem);
        Map<Attribute, List<AttributeModifier>> newMods = mainHandModifiers(newItem);
        Set<Attribute> attributes = new HashSet<>(oldMods.keySet());
        attributes.addAll(newMods.keySet());

        List<Restore> list = new ArrayList<>();
        for (Attribute attribute : attributes) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            List<AttributeModifier> adding = oldMods.getOrDefault(attribute, List.of());
            Set<Key> keys = new HashSet<>();
            adding.forEach(m -> keys.add(m.key()));
            newMods.getOrDefault(attribute, List.of()).forEach(m -> keys.add(m.key()));

            List<AttributeModifier> saved = new ArrayList<>();
            for (AttributeModifier current : instance.getModifiers()) {
                if (keys.contains(current.key())) {
                    saved.add(current);
                }
            }
            saved.forEach(m -> instance.removeModifier(m.key()));

            Set<Key> addedKeys = new HashSet<>();
            List<AttributeModifier> added = new ArrayList<>();
            for (AttributeModifier modifier : adding) {
                if (addedKeys.add(modifier.key())) {
                    instance.addTransientModifier(modifier);
                    added.add(modifier);
                }
            }
            list.add(new Restore(instance, added, saved));
        }

        UUID uuid = player.getUniqueId();
        restores.put(uuid, list);
        // スケジューラは次の tick の entity tick より前に回るので、
        // サーバーの装備差分検出 (detectEquipmentUpdates) が狂う前に戻る
        Bukkit.getScheduler().runTask(plugin, () -> restoreNow(uuid));
    }

    /**
     * 切り替えでリセットされたチャージを «切り替えなかった場合» の値に戻す。
     * カウンタは毎 tick +1 なので、切り替え時点の値 + 経過 tick。
     * (リセットが装備反映 tick 内か、Paper の update-equipment-on-player-actions で
     *  パケット処理中かに関係なく成り立つ。同じ種類のアイテム同士ならリセットされず、そのまま一致する)
     */
    private boolean restoreCharge(Player player, PendingSwap swap) {
        if (swap.ticker == null) {
            return false;
        }
        int[] now = plugin.attackTicker().read(player);
        if (now == null) {
            return false;
        }
        int elapsed = Math.max(0, Bukkit.getCurrentTick() - swap.tick);
        int attack = swap.ticker[0] + elapsed;
        int itemSwap = swap.ticker[1] + elapsed;
        if (attack <= now[0]) {
            return false;
        }
        plugin.attackTicker().write(player, attack, Math.max(itemSwap, now[1]));
        return true;
    }

    private void restoreNow(UUID uuid) {
        List<Restore> list = restores.remove(uuid);
        if (list != null) {
            list.forEach(Restore::run);
        }
    }

    /** バニラの ItemStack#forEachModifier(MAINHAND) と同じく、メインハンドで効く属性を集める */
    private static Map<Attribute, List<AttributeModifier>> mainHandModifiers(ItemStack item) {
        Map<Attribute, List<AttributeModifier>> result = new LinkedHashMap<>();
        if (item == null || item.isEmpty() || isBroken(item)) {
            return result;
        }
        ItemAttributeModifiers component = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (component == null) {
            return result;
        }
        for (ItemAttributeModifiers.Entry entry : component.modifiers()) {
            if (entry.getGroup().test(EquipmentSlot.HAND)) {
                result.computeIfAbsent(entry.attribute(), a -> new ArrayList<>()).add(entry.modifier());
            }
        }
        return result;
    }

    private static boolean isBroken(ItemStack item) {
        Integer max = item.getData(DataComponentTypes.MAX_DAMAGE);
        Integer damage = item.getData(DataComponentTypes.DAMAGE);
        return max != null && damage != null && !item.hasData(DataComponentTypes.UNBREAKABLE) && damage >= max;
    }

    private static boolean sameModifier(AttributeModifier a, AttributeModifier b) {
        return a != null && a.key().equals(b.key()) && a.getAmount() == b.getAmount() && a.getOperation() == b.getOperation();
    }

    private static double attributeValue(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    private static String name(ItemStack item) {
        return item == null || item.isEmpty() ? "素手" : item.getType().getKey().getKey();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Geyser のセッション準備を待ってから照準の読み取りを入れる
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.bedrockDetector().isBedrock(player)) {
                plugin.mouseover().attach(player);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pending.remove(uuid);
        consumed.remove(uuid);
        lastAttackTick.remove(uuid);
        lastStabTick.remove(uuid);
        plugin.mouseover().forget(uuid);
        restoreNow(uuid);
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            for (UUID uuid : new ArrayList<>(restores.keySet())) {
                restoreNow(uuid);
            }
            pending.clear();
        }
    }
}
