package jp.ibuki.geyserswap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;

final class SwapCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("grace", "on", "off", "keepcharge", "bedrockonly", "spearreach", "reachgrace", "airgrace", "airmax", "swapback", "stronger", "debug", "reload", "status");

    private final GeyserSwapPlugin plugin;

    SwapCommand(GeyserSwapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("geyserswap.admin")) {
            error(sender, "権限がありません (OP 専用)");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            status(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "grace" -> {
                if (args.length < 2) {
                    info(sender, "現在の猶予: " + plugin.graceMs() + "ms (" + ticks(plugin.graceMs()) + ")");
                    info(sender, "変更: /" + label + " grace <0〜" + GeyserSwapPlugin.MAX_GRACE_MS + " ミリ秒>  例: grace 150 / grace 3t");
                    return true;
                }
                Integer ms = parseMs(args[1]);
                if (ms == null || ms < 0 || ms > GeyserSwapPlugin.MAX_GRACE_MS) {
                    error(sender, "0〜" + GeyserSwapPlugin.MAX_GRACE_MS + " のミリ秒 (末尾 t で tick 指定) で入力してください");
                    return true;
                }
                plugin.setGraceMs(ms);
                ok(sender, "猶予を " + plugin.graceMs() + "ms (" + ticks(plugin.graceMs()) + ") にしました");
            }
            case "on" -> {
                plugin.setSwapEnabled(true);
                ok(sender, "疑似属性スワップを有効にしました");
            }
            case "off" -> {
                plugin.setSwapEnabled(false);
                ok(sender, "疑似属性スワップを無効にしました");
            }
            case "keepcharge" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setKeepAttackCharge(value);
                    ok(sender, "チャージ引き継ぎ: " + onOff(value));
                }
            }
            case "bedrockonly" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setBedrockOnly(value);
                    ok(sender, "統合版のみに適用: " + onOff(value));
                }
            }
            case "debug" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setDebug(value);
                    ok(sender, "デバッグ表示: " + onOff(value));
                }
            }
            case "spearreach" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setSpearReach(value);
                    ok(sender, "槍のリーチスワップ: " + onOff(value));
                }
            }
            case "airgrace" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setAirGrace(value);
                    ok(sender, "空中の猶予延長: " + onOff(value));
                }
            }
            case "airmax" -> {
                Integer ms = args.length < 2 ? null : parseMs(args[1]);
                if (ms == null || ms < 0 || ms > 10000) {
                    error(sender, "/" + label + " airmax <0〜10000 ミリ秒>  例: airmax 3000 / airmax 60t");
                    return true;
                }
                plugin.setAirGraceMaxMs(ms);
                ok(sender, "空中延長の上限を " + plugin.airGraceMaxMs() + "ms にしました");
            }
            case "swapback" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setIgnoreSwapBack(value);
                    ok(sender, "持ち戻しを無視: " + onOff(value));
                }
            }
            case "reachgrace" -> {
                Integer ms = args.length < 2 ? null : parseMs(args[1]);
                if (ms == null || ms < 0 || ms > GeyserSwapPlugin.MAX_GRACE_MS) {
                    error(sender, "/" + label + " reachgrace <0〜" + GeyserSwapPlugin.MAX_GRACE_MS + " ミリ秒>  例: reachgrace 200 / reachgrace 4t");
                    return true;
                }
                plugin.setSpearReachGraceMs(ms);
                ok(sender, "槍のリーチスワップの猶予を " + plugin.spearReachGraceMs() + "ms (" + ticks(plugin.spearReachGraceMs()) + ") にしました");
            }
            case "stronger" -> {
                Boolean value = parseBool(sender, args);
                if (value != null) {
                    plugin.setOnlyStronger(value);
                    ok(sender, "切り替え前が強い武器のときだけ属性を差し替え: " + onOff(value));
                }
            }
            case "reload" -> {
                plugin.loadSettings();
                ok(sender, "config.yml を読み直しました");
                status(sender);
            }
            default -> error(sender, "使い方: /" + label + " [grace <ms> | on | off | keepcharge | bedrockonly | spearreach | reachgrace <ms> | airgrace | airmax <ms> | swapback | stronger | debug | reload]");
        }
        return true;
    }

    private void status(CommandSender sender) {
        info(sender, "=== GeyserSwap ===");
        info(sender, "状態: " + onOff(plugin.swapEnabled()));
        info(sender, "猶予: " + plugin.graceMs() + "ms (" + ticks(plugin.graceMs()) + ")");
        info(sender, "チャージ引き継ぎ: " + onOff(plugin.keepAttackCharge()));
        info(sender, "統合版のみ: " + onOff(plugin.bedrockOnly()) + "  (検出: " + plugin.bedrockDetector().describe() + ")");
        info(sender, "槍のリーチスワップ: " + onOff(plugin.spearReach()) + "  猶予 " + plugin.spearReachGraceMs() + "ms");
        info(sender, "空中の猶予延長: " + onOff(plugin.airGrace()) + " (上限 " + plugin.airGraceMaxMs() + "ms)");
        info(sender, "持ち戻しを無視: " + onOff(plugin.ignoreSwapBack()));
        info(sender, "強い武器からのみ属性を差し替え: " + onOff(plugin.onlyStronger()));
        info(sender, "デバッグ表示: " + onOff(plugin.debug()));
    }

    /** "150" / "150ms" はミリ秒、"3t" は tick */
    private static Integer parseMs(String text) {
        String s = text.toLowerCase(Locale.ROOT).trim();
        try {
            if (s.endsWith("ms")) {
                return Integer.parseInt(s.substring(0, s.length() - 2));
            }
            if (s.endsWith("t")) {
                return Math.multiplyExact(Integer.parseInt(s.substring(0, s.length() - 1)), 50);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static Boolean parseBool(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "/" + "geyserswap " + args[0] + " <true|false>");
            return null;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> {
                error(sender, "true か false を指定してください");
                yield null;
            }
        };
    }

    private static String ticks(int ms) {
        return String.format("%.1f tick", ms / 50.0);
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static void info(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    private static void ok(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[GeyserSwap] " + text, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[GeyserSwap] " + text, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("geyserswap.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "grace" -> List.of("50", "100", "150", "200", "300");
                case "keepcharge", "bedrockonly", "spearreach", "airgrace", "swapback", "stronger", "debug" -> List.of("true", "false");
                case "airmax" -> List.of("2000", "3000", "5000");
                case "reachgrace" -> List.of("150", "200", "300");
                default -> List.of();
            };
        }
        return List.of();
    }
}
