package jp.ibuki.geyserswap;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GeyserSwapPlugin extends JavaPlugin {

    public static final int MAX_GRACE_MS = 3000;

    private BedrockDetector bedrockDetector;
    private AttackTicker attackTicker;
    private StabInvoker stabInvoker;
    private GeyserMouseover mouseover;

    private boolean enabled;
    private int graceMs;
    private boolean keepAttackCharge;
    private boolean bedrockOnly;
    private boolean debug;
    private boolean spearReach;
    private boolean airGrace;
    private int airGraceMaxMs;
    private boolean ignoreSwapBack;
    private boolean onlyStronger;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        bedrockDetector = new BedrockDetector(this);
        attackTicker = new AttackTicker(getLogger());
        stabInvoker = new StabInvoker(getLogger());
        mouseover = new GeyserMouseover(this);

        SwapListener listener = new SwapListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            if (bedrockDetector.isBedrock(online)) {
                mouseover.attach(online);
            }
        }

        PluginCommand command = getCommand("geyserswap");
        SwapCommand executor = new SwapCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getLogger().info("猶予 " + graceMs + "ms / 検出: " + bedrockDetector.describe());
    }

    @Override
    public void onDisable() {
        if (mouseover != null) {
            mouseover.detachAll();
        }
    }

    public void loadSettings() {
        reloadConfig();
        enabled = getConfig().getBoolean("enabled", true);
        graceMs = clampGrace(getConfig().getInt("grace-ms", 150));
        keepAttackCharge = getConfig().getBoolean("keep-attack-charge", true);
        bedrockOnly = getConfig().getBoolean("bedrock-only", true);
        debug = getConfig().getBoolean("debug", false);
        spearReach = getConfig().getBoolean("spear-reach", true);
        airGrace = getConfig().getBoolean("air-grace", true);
        airGraceMaxMs = Math.max(0, Math.min(10000, getConfig().getInt("air-grace-max-ms", 3000)));
        ignoreSwapBack = getConfig().getBoolean("ignore-swap-back", true);
        onlyStronger = getConfig().getBoolean("only-stronger", true);
    }

    private void save(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
    }

    static int clampGrace(int ms) {
        return Math.max(0, Math.min(MAX_GRACE_MS, ms));
    }

    public BedrockDetector bedrockDetector() { return bedrockDetector; }
    public AttackTicker attackTicker() { return attackTicker; }
    public StabInvoker stabInvoker() { return stabInvoker; }
    public GeyserMouseover mouseover() { return mouseover; }

    public boolean swapEnabled() { return enabled; }
    public int graceMs() { return graceMs; }
    public boolean keepAttackCharge() { return keepAttackCharge; }
    public boolean bedrockOnly() { return bedrockOnly; }
    public boolean debug() { return debug; }

    public void setSwapEnabled(boolean value) { enabled = value; save("enabled", value); }
    public void setGraceMs(int value) { graceMs = clampGrace(value); save("grace-ms", graceMs); }
    public void setKeepAttackCharge(boolean value) { keepAttackCharge = value; save("keep-attack-charge", value); }
    public void setBedrockOnly(boolean value) { bedrockOnly = value; save("bedrock-only", value); }
    public void setDebug(boolean value) { debug = value; save("debug", value); }

    public boolean spearReach() { return spearReach; }
    public boolean airGrace() { return airGrace; }
    public int airGraceMaxMs() { return airGraceMaxMs; }
    public boolean ignoreSwapBack() { return ignoreSwapBack; }
    public boolean onlyStronger() { return onlyStronger; }
    public void setOnlyStronger(boolean value) { onlyStronger = value; save("only-stronger", value); }

    public void setSpearReach(boolean value) { spearReach = value; save("spear-reach", value); }
    public void setAirGrace(boolean value) { airGrace = value; save("air-grace", value); }
    public void setAirGraceMaxMs(int value) { airGraceMaxMs = Math.max(0, Math.min(10000, value)); save("air-grace-max-ms", airGraceMaxMs); }
    public void setIgnoreSwapBack(boolean value) { ignoreSwapBack = value; save("ignore-swap-back", value); }
}
