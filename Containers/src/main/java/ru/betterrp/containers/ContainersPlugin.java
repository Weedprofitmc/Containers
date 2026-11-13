package ru.betterrp.containers;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContainersPlugin extends JavaPlugin implements Listener {

    private final Map<Integer, Container> containers = new HashMap<>();
    private final Map<UUID, Integer> editing = new HashMap<>();
    private final Map<UUID, Integer> waitingBetInput = new HashMap<>();
    private final Map<UUID, LotDraft> creatingLot = new HashMap<>();
    private final Map<UUID, Integer> waitingLotInput = new HashMap<>();

    private Economy economy;

    private File statsFile;
    private FileConfiguration statsConfig;
    private File containersFile;
    private FileConfiguration containersConfig;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    private NamespacedKey signKey;

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE);
    private static final String BET_GUI_PREFIX = "Ставки — ";
    private static final String EDIT_GUI_PREFIX = "Контейнер #";
    private static final String SELL_GUI_PREFIX = "Создание лота — ";

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault не найден или нет плагина экономики! Плагин будет отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        syncConfig();
        signKey = new NamespacedKey(this, "container_id");
        loadStats();
        loadContainers();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tickTimers, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::tickHolograms, 1L, 1L);
        // Периодически фиксируем содержимое открытых редакторов, чтобы минимизировать потери при аварийном завершении.
        getServer().getScheduler().runTaskTimer(this, this::autosaveEditingInventories, 100L, 100L);
        getLogger().info("Containers v2.1.0 запущен. Экономика: " + economy.getName());
    }

    @Override
    public void onDisable() {
        // Сначала снимаем актуальное содержимое всех открытых GUI, иначе при /reload
        // изменения, которые ещё не вызвали InventoryCloseEvent, могут потеряться.
        flushEditingInventories();
        for (Container c : containers.values()) c.removeHologram();
        saveContainers();
        saveStats();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void syncConfig() {
        InputStream in = getResource("config.yml");
        if (in == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (key.startsWith("containers") || key.startsWith("pending")) continue;
            if (defaults.isConfigurationSection(key)) continue;
            if (!getConfig().isSet(key)) {
                getConfig().set(key, defaults.get(key));
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
            getLogger().info("config.yml дополнен недостающими ключами.");
        }
    }

    // ==================== КОНФИГ-ХЕЛПЕРЫ ====================

    private String msg(String key, Map<String, String> ph) {
        String s = getConfig().getString("messages." + key, "&c[miss:" + key + "]");
        if (ph != null) {
            for (Map.Entry<String, String> e : ph.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        String prefix = getConfig().getString("prefix", "");
        s = s.replace("%prefix%", prefix);
        return ChatColor.translateAlternateColorCodes('&', s);
    }
    private String msg(String key) { return msg(key, (Map<String, String>) null); }

    private String msg(String key, String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return msg(key, m);
    }

    private String msg(String key, String k1, String v1, String k2, String v2) {
        Map<String, String> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return msg(key, m);
    }

    private String msg(String key, String k1, String v1, String k2, String v2, String k3, String v3) {
        Map<String, String> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return msg(key, m);
    }

    private String msg(String key, String k1, String v1, String k2, String v2, String k3, String v3, String k4, String v4) {
        Map<String, String> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        return msg(key, m);
    }

    private String viewTitle(InventoryView view) {
        return PlainTextComponentSerializer.plainText().serialize(view.title());
    }

    private String currency() { return getConfig().getString("economy.currency", "£"); }
    private double refundRate() { return getConfig().getDouble("economy.refund-rate", 0.85); }
    private double betMin() { return getConfig().getDouble("economy.bet-min", 1.0); }
    private double betMax() { return getConfig().getDouble("economy.bet-max", 0.0); }
    private double betStep() { return getConfig().getDouble("economy.bet-step", 1.0); }
    private boolean antiSnipeEnabled() { return getConfig().getBoolean("anti-snipe.enabled", true); }
    private long antiSnipeThreshold() { return getConfig().getLong("anti-snipe.threshold-ms", 30000); }
    private long antiSnipeExtend() { return getConfig().getLong("anti-snipe.extend-ms", 60000); }
    private boolean deadZoneEnabled() { return getConfig().getBoolean("dead-zone.enabled", false); }
    private long deadZoneThreshold() { return getConfig().getLong("dead-zone.threshold-ms", 5000); }
    private boolean hologramEnabled() { return getConfig().getBoolean("hologram.enabled", true); }
    private double hologramRadius() { return getConfig().getDouble("hologram.radius", 5.0); }
    private double hologramHeight() { return getConfig().getDouble("hologram.height", 1.3); }
    private int hologramFadeStep() { return getConfig().getInt("hologram.fade-step", 25); }
    private String barFilled() { return getConfig().getString("hologram.bar-filled", "▰"); }
    private String barEmpty() { return getConfig().getString("hologram.bar-empty", "▱"); }
    private int barSegments() { return getConfig().getInt("hologram.bar-segments", 10); }
    private double soundRadius() { return getConfig().getDouble("sounds.radius", 20.0); }
    private long tickSoundThreshold() { return getConfig().getLong("sounds.tick-threshold-ms", 10000); }
    private boolean auctionEnabled() { return getConfig().getBoolean("auction.enabled", true); }

    // ==================== ЭКОНОМИКА (VAULT) ====================

    private double getBalance(UUID uuid) {
        return economy.getBalance(Bukkit.getOfflinePlayer(uuid));
    }
    private boolean hasBalance(UUID uuid, double amount) {
        return economy.has(Bukkit.getOfflinePlayer(uuid), amount);
    }
    private boolean takeBalance(UUID uuid, double amount) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        if (!economy.has(op, amount)) return false;
        economy.withdrawPlayer(op, amount);
        return true;
    }
    private void addBalance(UUID uuid, double amount) {
        economy.depositPlayer(Bukkit.getOfflinePlayer(uuid), amount);
    }
    private String money(double v) { return currency() + String.format("%.2f", v); }

    // ==================== СТАТИСТИКА ====================

    private void loadStats() {
        stats.clear();
        statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            getDataFolder().mkdirs();
            try { statsFile.createNewFile(); } catch (IOException ignored) {}
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        ConfigurationSection sec = statsConfig.getConfigurationSection("stats");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                PlayerStats ps = new PlayerStats();
                ps.nick = s.getString("nick", "?");
                ps.wins = s.getInt("wins", 0);
                ps.wonMoney = s.getDouble("won-money", 0.0);
                ps.spent = s.getDouble("spent", 0.0);
                ps.lost = s.getDouble("lost", 0.0);
                stats.put(uuid, ps);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveStats() {
        statsConfig.set("stats", null);
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            String path = "stats." + e.getKey();
            PlayerStats s = e.getValue();
            statsConfig.set(path + ".nick", s.nick);
            statsConfig.set(path + ".wins", s.wins);
            statsConfig.set(path + ".won-money", s.wonMoney);
            statsConfig.set(path + ".spent", s.spent);
            statsConfig.set(path + ".lost", s.lost);
        }
        try { statsConfig.save(statsFile); } catch (IOException e) {
            getLogger().severe("Не могу сохранить stats.yml: " + e.getMessage());
        }
    }

    private PlayerStats getStats(UUID uuid) { return stats.computeIfAbsent(uuid, k -> new PlayerStats()); }
    private void addSpent(UUID uuid, String nick, double amt) { PlayerStats s = getStats(uuid); s.nick = nick; s.spent += amt; saveStats(); }
    private void addLost(UUID uuid, String nick, double amt) { PlayerStats s = getStats(uuid); s.nick = nick; s.lost += amt; saveStats(); }
    private void addWin(UUID uuid, String nick, double amount) { PlayerStats s = getStats(uuid); s.nick = nick; s.wins += 1; s.wonMoney += amount; saveStats(); }

    // ==================== КОНТЕЙНЕРЫ ====================

    private void loadContainers() {
        containers.clear();
        containersFile = new File(getDataFolder(), "containers.yml");

        // Новое хранилище отделено от config.yml: перезагрузка настроек не затрагивает данные.
        boolean hasDedicatedStorage = containersFile.isFile();
        containersConfig = hasDedicatedStorage
                ? YamlConfiguration.loadConfiguration(containersFile)
                : getConfig();

        ConfigurationSection sec = containersConfig.getConfigurationSection("containers");
        if (sec == null) {
            if (!hasDedicatedStorage) saveContainers();
            return;
        }

        for (String key : sec.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                ConfigurationSection c = sec.getConfigurationSection(key);
                if (c == null) continue;

                Container container = new Container(id);
                container.name = c.getString("name", "Контейнер #" + id);
                container.bettor = c.getString("bettor", null);
                container.bet = c.getDouble("bet", 0.0);
                container.expiresAt = c.getLong("expires-at", 0L);
                container.totalDuration = c.getLong("total-duration", 0L);
                container.signWorld = c.getString("sign.world", null);
                container.signX = c.getInt("sign.x", 0);
                container.signY = c.getInt("sign.y", 0);
                container.signZ = c.getInt("sign.z", 0);

                String ownerStr = c.getString("owner-uuid", null);
                container.ownerUuid = ownerStr == null || ownerStr.isBlank() ? null : UUID.fromString(ownerStr);
                container.ownerName = c.getString("owner-name", null);
                container.buyoutPrice = c.getDouble("buyout-price", 0.0);
                container.playerCreated = c.getBoolean("player-created", false);
                container.startPrice = c.getDouble("start-price", 0.0);

                // Поддерживаются оба формата: старые ItemStack-объекты и карты serialize().
                List<?> rawItems = c.getList("items");
                if (rawItems != null) {
                    for (Object rawItem : rawItems) {
                        ItemStack item = deserializeItem(rawItem);
                        if (item != null && item.getType() != Material.AIR) {
                            container.items.add(item.clone());
                        }
                    }
                }

                List<Map<?, ?>> hist = c.getMapList("history");
                for (Map<?, ?> m : hist) {
                    try {
                        String nick = String.valueOf(m.get("nick"));
                        double amount = Double.parseDouble(String.valueOf(m.get("amount")));
                        long time = Long.parseLong(String.valueOf(m.get("time")));
                        container.history.add(new BetRecord(nick, amount, time));
                    } catch (Exception ignored) {
                        getLogger().warning("Пропущена повреждённая запись истории контейнера #" + id);
                    }
                }
                containers.put(id, container);
            } catch (Exception ex) {
                getLogger().warning("Ошибка загрузки контейнера " + key + ": " + ex.getMessage());
            }
        }

        if (!hasDedicatedStorage) {
            getLogger().info("Выполняется перенос контейнеров из config.yml в containers.yml...");
            saveContainers();
            // После успешного переноса удаляем старую копию, чтобы данные не дублировались.
            getConfig().set("containers", null);
            saveConfig();
            getLogger().info("Перенос завершён. Загружено контейнеров: " + containers.size());
        } else {
            getLogger().info("Загружено контейнеров: " + containers.size());
        }
    }

    private ItemStack deserializeItem(Object raw) {
        try {
            if (raw instanceof ItemStack item) return item.clone();
            if (raw instanceof Map<?, ?> rawMap) {
                Map<String, Object> serialized = new HashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() != null) serialized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return ItemStack.deserialize(serialized);
            }
        } catch (Exception ex) {
            getLogger().warning("Не удалось восстановить предмет из containers.yml: " + ex.getMessage());
        }
        return null;
    }

    private void saveContainers() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Не удалось создать папку данных плагина.");
            return;
        }
        if (containersFile == null) containersFile = new File(getDataFolder(), "containers.yml");

        YamlConfiguration data = new YamlConfiguration();
        for (Container c : containers.values()) {
            String path = "containers." + c.id;
            data.set(path + ".name", c.name);
            data.set(path + ".bettor", c.bettor);
            data.set(path + ".bet", c.bet);
            data.set(path + ".expires-at", c.expiresAt);
            data.set(path + ".total-duration", c.totalDuration);
            data.set(path + ".owner-uuid", c.ownerUuid == null ? null : c.ownerUuid.toString());
            data.set(path + ".owner-name", c.ownerName);
            data.set(path + ".buyout-price", c.buyoutPrice);
            data.set(path + ".player-created", c.playerCreated);
            data.set(path + ".start-price", c.startPrice);
            if (c.signWorld != null) {
                data.set(path + ".sign.world", c.signWorld);
                data.set(path + ".sign.x", c.signX);
                data.set(path + ".sign.y", c.signY);
                data.set(path + ".sign.z", c.signZ);
            }

            List<Map<String, Object>> serializedItems = new ArrayList<>();
            for (ItemStack item : c.items) {
                if (item != null && item.getType() != Material.AIR) serializedItems.add(item.serialize());
            }
            data.set(path + ".items", serializedItems);

            List<Map<String, Object>> serializedHistory = new ArrayList<>();
            for (BetRecord record : c.history) {
                Map<String, Object> row = new HashMap<>();
                row.put("nick", record.nick);
                row.put("amount", record.amount);
                row.put("time", record.time);
                serializedHistory.add(row);
            }
            data.set(path + ".history", serializedHistory);
        }

        // Запись во временный файл и атомарная замена защищают от частично записанного YAML.
        File tempFile = new File(getDataFolder(), "containers.yml.tmp");
        try {
            Files.writeString(tempFile.toPath(), data.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tempFile.toPath(), containersFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile.toPath(), containersFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            containersConfig = data;
        } catch (IOException ex) {
            getLogger().severe("Не удалось безопасно сохранить containers.yml: " + ex.getMessage());
            try { Files.deleteIfExists(tempFile.toPath()); } catch (IOException ignored) {}
        }
    }

    private boolean copyInventoryToContainer(Container container, Inventory inventory) {
        List<ItemStack> updated = new ArrayList<>();
        for (int slot = 0; slot < Math.min(27, inventory.getSize()); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR) updated.add(item.clone());
        }
        if (container.items.equals(updated)) return false;
        container.items.clear();
        container.items.addAll(updated);
        return true;
    }

    private void autosaveEditingInventories() {
        if (editing.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<UUID, Integer> entry : new ArrayList<>(editing.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Container container = containers.get(entry.getValue());
            if (player == null || container == null || !player.isOnline()) continue;
            Inventory top = player.getOpenInventory().getTopInventory();
            if (copyInventoryToContainer(container, top)) {
                updateSign(container);
                changed = true;
            }
        }
        if (changed) saveContainers();
    }

    private void flushEditingInventories() {
        boolean changed = false;
        for (Map.Entry<UUID, Integer> entry : new ArrayList<>(editing.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Container container = containers.get(entry.getValue());
            if (player == null || container == null) continue;
            if (copyInventoryToContainer(container, player.getOpenInventory().getTopInventory())) {
                updateSign(container);
                changed = true;
            }
        }
        if (changed) saveContainers();
    }

    private int nextContainerId() {
        int max = 0;
        for (int id : containers.keySet()) if (id > max) max = id;
        return max + 1;
    }

    // ==================== GUI НАПОЛНЕНИЯ ====================

    private void openContainerGui(Player admin, Container c) {
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text("Контейнер #" + c.id + " — " + c.name, NamedTextColor.DARK_GREEN));
        for (int i = 0; i < c.items.size() && i < 27; i++) inv.setItem(i, c.items.get(i));
        admin.openInventory(inv);
        editing.put(admin.getUniqueId(), c.id);
        admin.sendMessage(msg("create.gui-hint"));
    }

    private void saveFromInventory(Container c, Inventory inv) {
        copyInventoryToContainer(c, inv);
        saveContainers();
        updateSign(c);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!editing.containsKey(uuid)) return;
        int id = editing.remove(uuid);
        Container c = containers.get(id);
        if (c == null) return;
        saveFromInventory(c, event.getInventory());
        player.sendMessage(msg("create.gui-saved", "id", String.valueOf(id)));
    }

    // ==================== GUI ЗАЩИТА ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = viewTitle(event.getView());

        if (title.startsWith(BET_GUI_PREFIX)) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            int slot = event.getRawSlot();
            if (slot == 26 && clicked.getType() == Material.BARRIER) { player.closeInventory(); return; }
            String containerName = title.substring(BET_GUI_PREFIX.length());
            Container c = null;
            for (Container cc : containers.values()) if (cc.name.equals(containerName)) { c = cc; break; }
            if (c == null) return;
            double currentBet = c.bet;
            switch (slot) {
                case 10: tryBet(player, c, currentBet + 100); break;
                case 12: tryBet(player, c, currentBet + 1000); break;
                case 14: tryBet(player, c, currentBet + 10000); break;
                case 16: tryBet(player, c, Math.max(currentBet * 2, 100)); break;
                case 22:
                    waitingBetInput.put(player.getUniqueId(), c.id);
                    player.closeInventory();
                    player.sendMessage(msg("bet.input-prompt"));
                    break;
                case 24:
                    if (c.buyoutPrice > 0) tryBuyout(player, c);
                    break;
                default: break;
            }
            return;
        }

        if (title.startsWith(SELL_GUI_PREFIX)) {
            int slot = event.getRawSlot();
            if (slot >= 27) {
                event.setCancelled(true);
                if (slot == 35) {
                    creatingLot.remove(player.getUniqueId());
                    player.closeInventory();
                    player.sendMessage(msg("auction.cancelled"));
                } else if (slot == 27) {
                    LotDraft draft = creatingLot.get(player.getUniqueId());
                    if (draft == null) return;
                    draft.items.clear();
                    for (int i = 0; i < 27; i++) {
                        ItemStack is = event.getInventory().getItem(i);
                        if (is != null && is.getType() != Material.AIR) draft.items.add(is.clone());
                    }
                    if (draft.items.isEmpty()) { player.sendMessage(msg("auction.empty")); return; }
                    player.closeInventory();
                    waitingLotInput.put(player.getUniqueId(), 0);
                    player.sendMessage(msg("auction.enter-start-price"));
                }
                return;
            }
            event.setCancelled(false);
            return;
        }

        if (title.startsWith(EDIT_GUI_PREFIX) && editing.containsKey(player.getUniqueId())) {
            if (event.isShiftClick()) { event.setCancelled(true); return; }
            switch (event.getClick()) {
                case NUMBER_KEY: case DOUBLE_CLICK: case CONTROL_DROP: case DROP:
                    event.setCancelled(true); break;
                default: break;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = viewTitle(event.getView());
        if (title.startsWith(BET_GUI_PREFIX)) { event.setCancelled(true); return; }
        if (title.startsWith(SELL_GUI_PREFIX)) {
            for (int slot : event.getRawSlots()) if (slot >= 27) { event.setCancelled(true); return; }
            return;
        }
        if (title.startsWith(EDIT_GUI_PREFIX) && editing.containsKey(player.getUniqueId())) {
            for (int slot : event.getRawSlots()) if (slot >= 27) { event.setCancelled(true); return; }
        }
    }

    private void tryBet(Player player, Container c, double amount) {
        if (amount <= 0) return;
        if (c.items.isEmpty()) { player.sendMessage(msg("bet.container-empty")); return; }
        if (amount < betMin()) { player.sendMessage(msg("bet.too-small", "min", money(betMin()))); return; }
        if (betMax() > 0 && amount > betMax()) { player.sendMessage(msg("bet.too-high", "max", money(betMax()))); return; }
        if (c.playerCreated && c.bettor == null) {
            if (amount < c.startPrice) { player.sendMessage(msg("bet.too-small", "min", money(c.startPrice))); return; }
        } else if (amount < c.bet + betStep()) {
            player.sendMessage(msg("bet.too-low-step", "min", money(c.bet + betStep()))); return;
        }
        if (!hasBalance(player.getUniqueId(), amount)) {
            player.sendMessage(msg("bet.no-money", "balance", money(getBalance(player.getUniqueId()))));
            return;
        }
        placeBet(player, c, amount);
        player.closeInventory();
    }

    private void tryBuyout(Player player, Container c) {
        if (c.buyoutPrice <= 0) return;
        if (!hasBalance(player.getUniqueId(), c.buyoutPrice)) {
            player.sendMessage(msg("auction.buyout-no-money", "amount", money(c.buyoutPrice)));
            return;
        }
        takeBalance(player.getUniqueId(), c.buyoutPrice);
        if (c.bettor != null && c.bet > 0) {
            OfflinePlayer prev = Bukkit.getOfflinePlayer(c.bettor);
            if (prev.getUniqueId() != null) {
                addBalance(prev.getUniqueId(), c.bet);
                Player op = prev.getPlayer();
                if (op != null && op.isOnline()) op.sendMessage(msg("bet.prev-interrupted", "id", String.valueOf(c.id)));
            }
        }
        c.bettor = player.getName();
        c.bet = c.buyoutPrice;
        player.sendMessage(msg("auction.buyout-done", "id", String.valueOf(c.id), "amount", money(c.buyoutPrice)));
        player.closeInventory();
        finishContainer(c, null, true);
    }

    // ==================== ВВОД ЧЕРЕЗ ЧАТ ====================

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!waitingBetInput.containsKey(uuid) && !waitingLotInput.containsKey(uuid)) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        getServer().getScheduler().runTask(this, () -> {
            if (waitingBetInput.containsKey(uuid)) { handleBetInput(player, text); return; }
            if (waitingLotInput.containsKey(uuid)) { handleLotInput(player, text); }
        });
    }

    private void handleBetInput(Player player, String text) {
        UUID uuid = player.getUniqueId();
        Integer cid = waitingBetInput.remove(uuid);
        if (cid == null) return;
        Container c = containers.get(cid);
        if (c == null) { player.sendMessage(msg("bet.container-not-found", "id", String.valueOf(cid))); return; }
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            player.sendMessage(msg("bet.input-cancel")); return;
        }
        double amount;
        try {
            amount = Double.parseDouble(text.replace(',', '.'));
            if (amount <= 0) throw new NumberFormatException();
        } catch (Exception e) { player.sendMessage(msg("bet.input-invalid")); return; }
        tryBet(player, c, amount);
    }

    private void handleLotInput(Player player, String text) {
        UUID uuid = player.getUniqueId();
        Integer stage = waitingLotInput.get(uuid);
        LotDraft draft = creatingLot.get(uuid);
        if (stage == null || draft == null) return;
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            waitingLotInput.remove(uuid);
            creatingLot.remove(uuid);
            player.sendMessage(msg("auction.cancelled"));
            return;
        }
        switch (stage) {
            case 0: {
                double price;
                try { price = Double.parseDouble(text.replace(',', '.')); if (price <= 0) throw new NumberFormatException(); }
                catch (Exception e) { player.sendMessage(msg("bet.input-invalid")); return; }
                double minStart = getConfig().getDouble("auction.min-start-price", 10.0);
                if (price < minStart) { player.sendMessage(msg("auction.start-too-low", "min", money(minStart))); return; }
                draft.startPrice = price;
                waitingLotInput.put(uuid, 1);
                player.sendMessage(msg("auction.enter-duration"));
                break;
            }
            case 1: {
                long dur = parseTime(text);
                long min = getConfig().getLong("auction.min-duration-ms", 300000);
                long max = getConfig().getLong("auction.max-duration-ms", 604800000);
                if (dur <= 0 || dur < min || dur > max) {
                    player.sendMessage(msg("auction.bad-duration", "min", formatTime(min), "max", formatTime(max)));
                    return;
                }
                draft.duration = dur;
                if (getConfig().getBoolean("auction.allow-buyout", true)) {
                    waitingLotInput.put(uuid, 2);
                    player.sendMessage(msg("auction.enter-buyout"));
                } else {
                    finalizeLot(player, draft);
                }
                break;
            }
            case 2: {
                double buyout;
                try { buyout = Double.parseDouble(text.replace(',', '.')); if (buyout < 0) throw new NumberFormatException(); }
                catch (Exception e) { player.sendMessage(msg("bet.input-invalid")); return; }
                if (buyout > 0 && buyout <= draft.startPrice) {
                    player.sendMessage(msg("auction.bad-buyout")); return;
                }
                draft.buyoutPrice = buyout;
                finalizeLot(player, draft);
                break;
            }
        }
    }

    private void finalizeLot(Player player, LotDraft draft) {
        UUID uuid = player.getUniqueId();
        waitingLotInput.remove(uuid);
        creatingLot.remove(uuid);
        double feePercent = getConfig().getDouble("auction.creation-fee-percent", 5.0);
        double fee = draft.startPrice * feePercent / 100.0;
        if (!hasBalance(uuid, fee)) {
            player.sendMessage(msg("auction.no-money-fee", "fee", money(fee)));
            returnItems(player, draft.items);
            return;
        }
        takeBalance(uuid, fee);
        int id = nextContainerId();
        Container c = new Container(id);
        c.name = "Лот " + player.getName();
        c.playerCreated = true;
        c.ownerUuid = uuid;
        c.ownerName = player.getName();
        c.startPrice = draft.startPrice;
        c.buyoutPrice = draft.buyoutPrice;
        c.bet = 0.0;
        c.bettor = null;
        c.expiresAt = System.currentTimeMillis() + draft.duration;
        c.totalDuration = draft.duration;
        c.items.addAll(draft.items);
        containers.put(id, c);
        saveContainers();
        player.getInventory().addItem(createSignItem(c));
        player.sendMessage(msg("auction.created",
                "id", String.valueOf(id),
                "start", money(draft.startPrice),
                "buyout", draft.buyoutPrice > 0 ? money(draft.buyoutPrice) : "нет"));
        player.sendMessage(msg("auction.sign-given"));
    }

    private void returnItems(Player player, List<ItemStack> items) {
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(it.clone());
            for (ItemStack drop : left.values())
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        waitingBetInput.remove(uuid);
        Integer stage = waitingLotInput.remove(uuid);
        if (stage != null) {
            LotDraft draft = creatingLot.remove(uuid);
            if (draft != null) returnItems(event.getPlayer(), draft.items);
        }
    }

    // ==================== МЕНЮ СТАВОК ====================

    private void openBetGui(Player player, Container c) {
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text(BET_GUI_PREFIX + c.name, NamedTextColor.DARK_GREEN));
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.setDisplayName(" "); glass.setItemMeta(gm); }
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.GOLD + "Контейнер #" + c.id + " — " + c.name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Содержимое:");
            for (ItemStack it : c.items) lore.add(ChatColor.AQUA + " • " + it.getAmount() + "x " + prettyName(it.getType()));
            if (c.items.isEmpty()) lore.add(ChatColor.DARK_GRAY + " пусто");
            lore.add("");
            lore.add(ChatColor.GRAY + "Текущая ставка: " + (c.bettor == null ? "нет" : ChatColor.YELLOW + c.bettor + " (" + money(c.bet) + ")"));
            if (c.playerCreated) {
                lore.add(ChatColor.GRAY + "Старт: " + ChatColor.WHITE + money(c.startPrice));
                if (c.buyoutPrice > 0) lore.add(ChatColor.GRAY + "Выкуп: " + ChatColor.GREEN + money(c.buyoutPrice));
                lore.add(ChatColor.GRAY + "Продавец: " + ChatColor.WHITE + (c.ownerName == null ? "?" : c.ownerName));
            }
            if (c.expiresAt > 0) {
                long left = c.expiresAt - System.currentTimeMillis();
                if (left > 0) {
                    lore.add(ChatColor.GRAY + "До конца: " + ChatColor.YELLOW + formatTime(left));
                    lore.add(progressBarComponent(c));
                }
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Баланс: " + ChatColor.GREEN + money(getBalance(player.getUniqueId())));
            im.setLore(lore);
            info.setItemMeta(im);
        }
        inv.setItem(4, info);
        inv.setItem(10, button(Material.GOLD_NUGGET, ChatColor.YELLOW + "+100", "Добавить " + money(100) + " к текущей"));
        inv.setItem(12, button(Material.GOLD_INGOT, ChatColor.YELLOW + "+1000", "Добавить " + money(1000) + " к текущей"));
        inv.setItem(14, button(Material.GOLD_BLOCK, ChatColor.YELLOW + "+10000", "Добавить " + money(10000) + " к текущей"));
        inv.setItem(16, button(Material.DIAMOND, ChatColor.AQUA + "X2 ставка", "Удвоить текущую ставку"));
        inv.setItem(22, button(Material.NAME_TAG, ChatColor.GREEN + "Своя сумма", "Введи сумму в чате"));
        if (c.buyoutPrice > 0) {
            inv.setItem(24, button(Material.EMERALD, ChatColor.GREEN + "Выкупить за " + money(c.buyoutPrice),
                    msg("auction.buyout-lore")));
        }
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        if (cm != null) { cm.setDisplayName(ChatColor.RED + "Закрыть"); close.setItemMeta(cm); }
        inv.setItem(26, close);
        player.openInventory(inv);
    }

    private ItemStack button(Material mat, String name, String desc) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + desc);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private String prettyName(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ==================== GUI СОЗДАНИЯ ЛОТА ====================

    private void openSellGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 36,
                Component.text(SELL_GUI_PREFIX + player.getName(), NamedTextColor.DARK_GREEN));
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) { gm.setDisplayName(" "); glass.setItemMeta(gm); }
        for (int i = 27; i < 36; i++) inv.setItem(i, glass);
        inv.setItem(27, button(Material.LIME_CONCRETE, ChatColor.GREEN + "Создать лот",
                "Нажми, чтобы задать цену и время"));
        inv.setItem(35, button(Material.BARRIER, ChatColor.RED + "Отмена", "Закрыть без сохранения"));
        player.openInventory(inv);
        creatingLot.put(player.getUniqueId(), new LotDraft());
    }

    // ==================== ТАБЛИЧКИ ====================

    private void updateSign(Container c) {
        if (c.signWorld == null) return;
        World w = Bukkit.getWorld(c.signWorld);
        if (w == null) return;
        Block b = w.getBlockAt(c.signX, c.signY, c.signZ);
        if (!(b.getState() instanceof Sign sign)) return;
        sign.getSide(Side.FRONT).setLine(0, ChatColor.DARK_GREEN + "[Контейнер]");
        sign.getSide(Side.FRONT).setLine(1, ChatColor.YELLOW + "#" + c.id + " " + c.name);
        sign.getSide(Side.FRONT).setLine(2, ChatColor.GRAY + "Ставка: " + ChatColor.WHITE + money(c.bet));
        sign.getSide(Side.FRONT).setLine(3, ChatColor.GRAY + (c.bettor == null ? "-" : c.bettor));
        sign.update(true, false);
    }

    private ItemStack createSignItem(Container c) {
        ItemStack signItem = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = signItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "Контейнер #" + c.id + " — " + c.name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Поставьте на блок — табличка привяжется");
            lore.add(ChatColor.GRAY + "автоматически и станет кликабельной.");
            lore.add(ChatColor.GRAY + "ID: " + ChatColor.YELLOW + c.id);
            if (c.playerCreated && c.buyoutPrice > 0) lore.add(ChatColor.GREEN + "Выкуп: " + money(c.buyoutPrice));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(signKey, PersistentDataType.INTEGER, c.id);
            signItem.setItemMeta(meta);
        }
        return signItem;
    }

    @EventHandler
    public void onSignPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() == Material.AIR) return;
        if (!item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        Integer containerId = meta.getPersistentDataContainer().get(signKey, PersistentDataType.INTEGER);
        if (containerId == null) return;
        Container c = containers.get(containerId);
        if (c == null) {
            event.getPlayer().sendMessage(msg("bet.container-not-found", "id", String.valueOf(containerId)));
            return;
        }
        Block b = event.getBlockPlaced();
        c.signWorld = b.getWorld().getName();
        c.signX = b.getX(); c.signY = b.getY(); c.signZ = b.getZ();
        saveContainers();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (b.getState() instanceof Sign sign) {
                sign.getPersistentDataContainer().set(signKey, PersistentDataType.INTEGER, containerId);
                updateSign(c);
            }
            playSignPlaceEffect(b.getLocation());
        }, 2L);
        event.getPlayer().sendMessage(msg("create.sign-place", "id", String.valueOf(c.id)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpenSign(PlayerOpenSignEvent event) {
        Block b = event.getSign().getBlock();
        if (findContainerBySign(b) != null) { event.setCancelled(true); return; }
        if (b.getState() instanceof Sign sign) {
            if (sign.getPersistentDataContainer().has(signKey, PersistentDataType.INTEGER)) {
                event.setCancelled(true);
            }
        }
    }

    private void playSignPlaceEffect(Location center) {
        World w = center.getWorld();
        if (w == null) return;
        Location base = center.clone().add(0.5, 0.6, 0.5);
        w.spawnParticle(Particle.HAPPY_VILLAGER, base, 12, 0.4, 0.6, 0.4, 0.0);
        w.spawnParticle(Particle.COMPOSTER, base, 8, 0.35, 0.5, 0.35, 0.0);
        w.spawnParticle(Particle.END_ROD, base.clone().add(0, 0.6, 0), 4, 0.15, 0.2, 0.15, 0.01);
        if (getConfig().getBoolean("sounds.place.enabled", true)) {
            String snd = getConfig().getString("sounds.place.sound", "BLOCK_AMETHYST_BLOCK_CHIME");
            float vol = (float) getConfig().getDouble("sounds.place.volume", 0.7);
            float pit = (float) getConfig().getDouble("sounds.place.pitch", 1.4);
            try { w.playSound(base, Sound.valueOf(snd), vol, pit); } catch (Exception ignored) {}
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Block block = event.getBlock();
        if (block.getState() instanceof Sign sign) {
            if (sign.getPersistentDataContainer().has(signKey, PersistentDataType.INTEGER)) {
                event.setCancelled(true); return;
            }
        }
        String l0 = event.getLine(0);
        if (l0 == null) return;
        if (!l0.equalsIgnoreCase("[container]") && !l0.equalsIgnoreCase("[контейнер]")) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("betterrp.containers.admin")) {
            player.sendMessage(msg("errors.no-permission")); return;
        }
        String idStr = event.getLine(1);
        String name = event.getLine(2);
        String timeStr = event.getLine(3);
        int id;
        try { id = Integer.parseInt(idStr.trim()); } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Строка 2 должна содержать числовой ID."); return;
        }
        if (containers.containsKey(id)) { player.sendMessage(msg("create.exists")); return; }
        long duration = parseTime(timeStr == null ? "" : timeStr.trim());
        if (duration <= 0) duration = 3600_000L;
        Container c = new Container(id);
        c.name = (name == null || name.isEmpty()) ? "Контейнер #" + id : ChatColor.stripColor(name);
        c.expiresAt = System.currentTimeMillis() + duration;
        c.totalDuration = duration;
        c.signWorld = block.getWorld().getName();
        c.signX = block.getX(); c.signY = block.getY(); c.signZ = block.getZ();
        containers.put(id, c);
        saveContainers();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (block.getState() instanceof Sign sign) {
                sign.getPersistentDataContainer().set(signKey, PersistentDataType.INTEGER, id);
                updateSign(c);
            }
            playSignPlaceEffect(block.getLocation());
        }, 1L);
        player.sendMessage(ChatColor.GREEN + "Контейнер #" + id + " привязан. Наполните: /cont edit " + id);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!isAnySign(b.getType())) return;
        Container c = findContainerBySign(b);
        if (c == null) return;
        if (!event.getPlayer().hasPermission("betterrp.containers.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg("create.sign-no-perm"));
            return;
        }
        c.removeHologram();
        c.signWorld = null;
        saveContainers();
    }

    private Container findContainerBySign(Block b) {
        for (Container c : containers.values()) {
            if (c.signWorld == null) continue;
            if (c.signWorld.equals(b.getWorld().getName())
                    && c.signX == b.getX() && c.signY == b.getY() && c.signZ == b.getZ()) return c;
        }
        return null;
    }

    @EventHandler
    public void onSignInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = event.getClickedBlock();
        if (b == null || !isAnySign(b.getType())) return;
        Container c = findContainerBySign(b);
        if (c == null) return;
        event.setCancelled(true);
        openBetGui(event.getPlayer(), c);
    }

    // ==================== УДАЛЕНИЕ ТАБЛИЧКИ ====================

    private boolean isAnySign(Material m) { return m != null && m.name().endsWith("_SIGN"); }

    private void removeContainerSign(Container c, boolean withEffect) {
        if (c.signWorld == null) return;
        World w = Bukkit.getWorld(c.signWorld);
        if (w == null) { c.signWorld = null; return; }
        int chunkX = c.signX >> 4;
        int chunkZ = c.signZ >> 4;
        boolean wasLoaded = w.isChunkLoaded(chunkX, chunkZ);
        if (!wasLoaded) w.getChunkAt(chunkX, chunkZ);
        Block b = w.getBlockAt(c.signX, c.signY, c.signZ);
        if (withEffect) playSignEndEffect(b.getLocation());
        if (isAnySign(b.getType())) b.setType(Material.AIR, false);
        if (!wasLoaded) w.unloadChunk(chunkX, chunkZ);
        c.removeHologram();
        c.signWorld = null;
    }

    private void playSignEndEffect(Location center) {
        World w = center.getWorld();
        if (w == null) return;
        Location base = center.clone().add(0.5, 0.6, 0.5);
        w.spawnParticle(Particle.HAPPY_VILLAGER, base, 25, 0.5, 0.7, 0.5, 0.02);
        w.spawnParticle(Particle.END_ROD, base, 8, 0.3, 0.4, 0.3, 0.03);
        if (getConfig().getBoolean("sounds.finish.enabled", true)) {
            String snd = getConfig().getString("sounds.finish.sound", "BLOCK_AMETHYST_BLOCK_BREAK");
            float vol = (float) getConfig().getDouble("sounds.finish.volume", 0.8);
            float pit = (float) getConfig().getDouble("sounds.finish.pitch", 1.2);
            try { w.playSound(base, Sound.valueOf(snd), vol, pit); } catch (Exception ignored) {}
        }
    }

    // ==================== ГОЛОГРАММЫ ====================

    private void tickHolograms() {
        if (!hologramEnabled()) return;
        double radius = hologramRadius();
        for (Container c : containers.values()) {
            if (c.signWorld == null) continue;
            World w = Bukkit.getWorld(c.signWorld);
            if (w == null) continue;
            Location signLoc = new Location(w, c.signX + 0.5, c.signY + 0.5, c.signZ + 0.5);
            boolean hasNearby = false;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(signLoc) <= radius * radius) { hasNearby = true; break; }
            }
            c.hologramTarget = hasNearby ? 255 : 0;
            int step = hologramFadeStep();
            if (c.hologramOpacity < c.hologramTarget) c.hologramOpacity = Math.min(c.hologramTarget, c.hologramOpacity + step);
            else if (c.hologramOpacity > c.hologramTarget) c.hologramOpacity = Math.max(c.hologramTarget, c.hologramOpacity - step);

            if (c.hologramOpacity <= 0) {
                if (c.hologramUUID != null) {
                    Entity e = Bukkit.getEntity(c.hologramUUID);
                    if (e != null) e.remove();
                    c.hologramUUID = null;
                }
                continue;
            }
            if (c.hologramUUID == null || Bukkit.getEntity(c.hologramUUID) == null) {
                Location holoLoc = signLoc.clone().add(0, hologramHeight(), 0);
                TextDisplay display = w.spawn(holoLoc, TextDisplay.class, td -> {
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setSeeThrough(true);
                    td.setShadowed(true);
                    td.setPersistent(false);
                    td.text(buildHologramText(c));
                });
                c.hologramUUID = display.getUniqueId();
            } else {
                Entity ent = Bukkit.getEntity(c.hologramUUID);
                if (ent instanceof TextDisplay td) td.text(buildHologramText(c));
            }
        }
    }

    private Component buildHologramText(Container c) {
        StringBuilder sb = new StringBuilder();
        sb.append("&e&lКонтейнер #").append(c.id).append(" &f").append(c.name).append("\n");
        if (c.expiresAt > 0) {
            long left = c.expiresAt - System.currentTimeMillis();
            sb.append(progressBarString(c)).append("\n");
            sb.append("&7⏱ &f").append(left > 0 ? formatTime(left) : "истекает").append("\n");
        }
        sb.append("&7💰 Ставка: &a").append(c.bettor == null ? "нет" : c.bettor + " (" + money(c.bet) + ")").append("\n");
        StringBuilder content = new StringBuilder();
        if (!c.items.isEmpty()) content.append(c.items.size()).append(" предмет(ов)");
        if (content.length() == 0) content.append("пусто");
        if (c.playerCreated && c.buyoutPrice > 0) content.append(" &7| &aВыкуп: ").append(money(c.buyoutPrice));
        sb.append("&7📦 &f").append(content);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(sb.toString());
    }

    private String progressBarString(Container c) {
        long now = System.currentTimeMillis();
        long left = Math.max(0, c.expiresAt - now);
        long total = c.totalDuration > 0 ? c.totalDuration : (c.expiresAt - now);
        if (total <= 0) return "";
        double ratio = (double) left / (double) total;
        int seg = barSegments();
        int filled = (int) Math.ceil(ratio * seg);
        if (filled < 0) filled = 0;
        if (filled > seg) filled = seg;
        String color;
        if (ratio > 0.5) color = "&a";
        else if (ratio > 0.2) color = "&e";
        else if (ratio > 0.05) color = "&6";
        else color = "&c";
        StringBuilder bar = new StringBuilder();
        bar.append(color);
        for (int i = 0; i < filled; i++) bar.append(barFilled());
        bar.append("&7");
        for (int i = filled; i < seg; i++) bar.append(barEmpty());
        return bar.toString();
    }

    private String progressBarComponent(Container c) {
        long now = System.currentTimeMillis();
        long left = Math.max(0, c.expiresAt - now);
        long total = c.totalDuration > 0 ? c.totalDuration : (c.expiresAt - now);
        if (total <= 0) return "";
        double ratio = (double) left / (double) total;
        int seg = barSegments();
        int filled = (int) Math.ceil(ratio * seg);
        if (filled < 0) filled = 0;
        if (filled > seg) filled = seg;
        ChatColor color;
        if (ratio > 0.5) color = ChatColor.GREEN;
        else if (ratio > 0.2) color = ChatColor.YELLOW;
        else if (ratio > 0.05) color = ChatColor.GOLD;
        else color = ChatColor.RED;
        StringBuilder bar = new StringBuilder();
        bar.append(color);
        for (int i = 0; i < filled; i++) bar.append(barFilled());
        bar.append(ChatColor.DARK_GRAY);
        for (int i = filled; i < seg; i++) bar.append(barEmpty());
        return bar.toString();
    }

    // ==================== ТАЙМЕР + ЗВУКИ ====================

    private void tickTimers() {
        long now = System.currentTimeMillis();
        List<Integer> toEnd = new ArrayList<>();
        for (Container c : containers.values()) {
            if (c.expiresAt > 0 && now >= c.expiresAt) toEnd.add(c.id);
        }
        for (int id : toEnd) {
            Container c = containers.get(id);
            if (c != null) finishContainer(c, null, false);
        }
        for (Container c : containers.values()) {
            if (c.expiresAt <= 0 || c.signWorld == null) continue;
            long left = c.expiresAt - now;
            if (left <= 0 || left > tickSoundThreshold()) { c.lastTickSecond = -1; continue; }
            int secondsLeft = (int) Math.ceil(left / 1000.0);
            if (secondsLeft == c.lastTickSecond) continue;
            c.lastTickSecond = secondsLeft;
            playTickSound(c, secondsLeft);
        }
    }

    private void playTickSound(Container c, int secondsLeft) {
        if (!getConfig().getBoolean("sounds.tick.enabled", true)) return;
        World w = Bukkit.getWorld(c.signWorld);
        if (w == null) return;
        Location loc = new Location(w, c.signX + 0.5, c.signY + 0.5, c.signZ + 0.5);
        int urgent = getConfig().getInt("sounds.tick.urgent-seconds", 3);
        float pitch = secondsLeft <= urgent
                ? (float) getConfig().getDouble("sounds.tick.pitch-urgent", 1.8)
                : (float) getConfig().getDouble("sounds.tick.pitch-normal", 1.4);
        float volume = (float) getConfig().getDouble("sounds.tick.volume", 0.6);
        String sndName = getConfig().getString("sounds.tick.sound", "BLOCK_NOTE_BLOCK_HAT");
        Sound snd;
        try { snd = Sound.valueOf(sndName); } catch (Exception e) { return; }
        double radius = soundRadius();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radius * radius) p.playSound(p.getLocation(), snd, volume, pitch);
        }
    }

    private void playBetSound(Container c) {
        if (!getConfig().getBoolean("sounds.bet.enabled", true)) return;
        if (c.signWorld == null) return;
        World w = Bukkit.getWorld(c.signWorld);
        if (w == null) return;
        Location loc = new Location(w, c.signX + 0.5, c.signY + 0.5, c.signZ + 0.5);
        String sndName = getConfig().getString("sounds.bet.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        float vol = (float) getConfig().getDouble("sounds.bet.volume", 0.7);
        float pit = (float) getConfig().getDouble("sounds.bet.pitch", 1.2);
        Sound snd;
        try { snd = Sound.valueOf(sndName); } catch (Exception e) { return; }
        double radius = soundRadius();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radius * radius) p.playSound(p.getLocation(), snd, vol, pit);
        }
    }

    private long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0L;
        Matcher m = TIME_PATTERN.matcher(s);
        long total = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "s": total += n * 1000L; break;
                case "m": total += n * 60_000L; break;
                case "h": total += n * 3_600_000L; break;
                case "d": total += n * 86_400_000L; break;
            }
        }
        return found ? total : 0L;
    }

    private String formatTime(long millis) {
        long s = millis / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0) sb.append(m).append("м ");
        sb.append(s).append("с");
        return sb.toString().trim();
    }

    // ==================== КОМАНДЫ ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cont")) return false;
        if (args.length == 0) { printHelp(sender); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info":    handleInfo(sender); break;
            case "sell":    handleSell(sender); break;
            case "create":  handleCreate(sender, args); break;
            case "spawner": handleSpawner(sender, args); break;
            case "edit":    handleEdit(sender, args); break;
            case "send":    handleSend(sender, args); break;
            case "end":     handleEnd(sender, args); break;
            case "remove":  handleRemove(sender, args); break;
            case "cancel":  handleCancel(sender, args); break;
            case "log":     handleLog(sender, args); break;
            case "top":     handleTop(sender, args); break;
            case "reload":  handleReload(sender); break;
            case "stats":   handleStats(sender); break;
            case "help":
            default:        printHelp(sender);
        }
        return true;
    }

    private void printHelp(CommandSender s) {
        s.sendMessage("");
        s.sendMessage(msg("help.header"));
        s.sendMessage(msg("help.divider"));
        s.sendMessage(msg("help.line-info"));
        s.sendMessage(msg("help.line-sell"));
        s.sendMessage(msg("help.line-send"));
        s.sendMessage(msg("help.line-log"));
        s.sendMessage(msg("help.line-top"));
        s.sendMessage(msg("help.divider"));
        if (s.hasPermission("betterrp.containers.admin")) {
            s.sendMessage(msg("help.admin-header"));
            s.sendMessage(msg("help.admin-create"));
            s.sendMessage(msg("help.admin-spawner"));
            s.sendMessage(msg("help.admin-edit"));
            s.sendMessage(msg("help.admin-other"));
            s.sendMessage(msg("help.admin-time"));
        }
        s.sendMessage("");
    }

    private void handleSell(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("errors.only-players")); return; }
        if (!auctionEnabled()) { player.sendMessage(msg("auction.disabled")); return; }
        int max = getConfig().getInt("auction.max-lots-per-player", 2);
        long count = containers.values().stream()
                .filter(c -> c.playerCreated && player.getUniqueId().equals(c.ownerUuid))
                .count();
        if (count >= max) {
            player.sendMessage(msg("auction.too-many-lots", "max", String.valueOf(max)));
            return;
        }
        openSellGui(player);
    }

    private void handleTop(CommandSender sender, String[] args) {
        String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "wins";
        String titleKey;
        Comparator<Map.Entry<UUID, PlayerStats>> cmp;
        switch (category) {
            case "spent":
                titleKey = "top.title-spent";
                cmp = Comparator.comparingDouble(e -> ((Map.Entry<UUID, PlayerStats>) e).getValue().spent);
                cmp = cmp.reversed(); break;
            case "won":
                titleKey = "top.title-won";
                cmp = Comparator.comparingDouble(e -> ((Map.Entry<UUID, PlayerStats>) e).getValue().wonMoney);
                cmp = cmp.reversed(); break;
            case "lost":
                titleKey = "top.title-lost";
                cmp = Comparator.comparingDouble(e -> ((Map.Entry<UUID, PlayerStats>) e).getValue().lost);
                cmp = cmp.reversed(); break;
            case "wins":
            default:
                titleKey = "top.title-wins";
                cmp = Comparator.comparingInt(e -> ((Map.Entry<UUID, PlayerStats>) e).getValue().wins);
                cmp = cmp.reversed(); break;
        }
        List<Map.Entry<UUID, PlayerStats>> list = new ArrayList<>(stats.entrySet());
        list.sort(cmp);
        sender.sendMessage("");
        sender.sendMessage(msg("top.header", "title", ChatColor.stripColor(
                ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + titleKey, "")))));
        sender.sendMessage(msg("top.divider"));
        if (list.isEmpty()) { sender.sendMessage(msg("top.empty")); sender.sendMessage(""); return; }
        int i = 1;
        for (Map.Entry<UUID, PlayerStats> e : list) {
            if (i > 10) break;
            PlayerStats st = e.getValue();
            String value;
            if (category.equals("wins")) value = String.valueOf(st.wins);
            else if (category.equals("spent")) value = money(st.spent);
            else if (category.equals("won")) value = money(st.wonMoney);
            else value = money(st.lost);
            String medal = i == 1 ? "🥇" : i == 2 ? "🥈" : i == 3 ? "🥉" : "  ";
            sender.sendMessage(msg("top.line",
                    "medal", medal,
                    "i", String.valueOf(i),
                    "nick", st.nick,
                    "value", value));
            i++;
        }
        sender.sendMessage("");
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(msg("info.header"));
        List<Container> list = new ArrayList<>(containers.values());
        if (list.isEmpty()) { sender.sendMessage(msg("info.empty")); return; }
        list.sort(Comparator.comparingInt(c -> c.id));
        for (Container c : list) {
            Map<String, String> ph = new HashMap<>();
            ph.put("id", String.valueOf(c.id));
            ph.put("name", c.name);
            StringBuilder content = new StringBuilder();
            if (!c.items.isEmpty()) content.append(msg("info.content-items", "count", String.valueOf(c.items.size())));
            if (content.length() == 0) content.append(msg("info.content-empty"));
            ph.put("content", content.toString());
            if (c.bettor == null) ph.put("bettor", msg("info.bettor-none"));
            else ph.put("bettor", msg("info.bettor-set", "nick", c.bettor, "amount", money(c.bet)));
            if (c.expiresAt > 0) {
                long left = c.expiresAt - System.currentTimeMillis();
                ph.put("time", msg("info.time", "time", (left > 0 ? formatTime(left) : msg("info.time-expired"))));
            } else ph.put("time", "");
            sender.sendMessage(msg("info.line", ph));
            if (c.expiresAt > 0 && c.expiresAt > System.currentTimeMillis()) {
                String barLine = getConfig().getString("messages.info.bar-line", "");
                if (barLine != null && !barLine.isEmpty()) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', barLine.replace("{bar}", progressBarString(c))));
                }
            }
        }
    }

    private void handleLog(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Использование: /cont log <id>"); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { sender.sendMessage(ChatColor.RED + "ID — число."); return; }
        Container c = containers.get(id);
        if (c == null) { sender.sendMessage(msg("bet.container-not-found", "id", String.valueOf(id))); return; }
        sender.sendMessage(msg("log.header", "name", c.name));
        if (c.history.isEmpty()) { sender.sendMessage(msg("log.empty")); return; }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        int i = 1;
        for (BetRecord r : c.history) {
            sender.sendMessage(msg("log.line",
                    "i", String.valueOf(i),
                    "nick", r.nick,
                    "amount", money(r.amount),
                    "time", fmt.format(new Date(r.time))));
            i++;
        }
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("errors.only-players")); return; }
        if (!player.hasPermission("betterrp.containers.admin")) { player.sendMessage(msg("errors.no-permission")); return; }
        if (args.length < 4) { player.sendMessage(msg("create.usage")); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { player.sendMessage(msg("create.bad-id")); return; }
        if (containers.containsKey(id)) { player.sendMessage(msg("create.exists")); return; }
        String name = args[2];
        long duration = parseTime(args[3]);
        if (duration <= 0) { player.sendMessage(msg("create.bad-time")); return; }
        Container c = new Container(id);
        c.name = name;
        c.expiresAt = System.currentTimeMillis() + duration;
        c.totalDuration = duration;
        containers.put(id, c);
        saveContainers();
        player.getInventory().addItem(createSignItem(c));
        player.sendMessage(msg("create.created", "id", String.valueOf(id)));
        player.sendMessage(msg("create.sign-given"));
        openContainerGui(player, c);
    }

    private void handleSpawner(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("errors.only-players")); return; }
        if (!player.hasPermission("betterrp.containers.admin")) { player.sendMessage(msg("errors.no-permission")); return; }
        if (args.length < 4) { player.sendMessage(msg("create.usage")); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { player.sendMessage(msg("create.bad-id")); return; }
        if (containers.containsKey(id)) { player.sendMessage(msg("create.exists")); return; }
        String name = args[2];
        long duration = parseTime(args[3]);
        if (duration <= 0) { player.sendMessage(msg("create.bad-time")); return; }
        Container c = new Container(id);
        c.name = name;
        c.expiresAt = System.currentTimeMillis() + duration;
        c.totalDuration = duration;
        containers.put(id, c);
        saveContainers();
        player.getInventory().addItem(createSignItem(c));
        player.sendMessage(msg("create.created", "id", String.valueOf(id)));
        player.sendMessage(msg("create.sign-given"));
    }

    private void handleEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("errors.only-players")); return; }
        if (!player.hasPermission("betterrp.containers.admin")) { player.sendMessage(msg("errors.no-permission")); return; }
        if (args.length < 2) { player.sendMessage(msg("create.edit-usage")); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { player.sendMessage(ChatColor.RED + "ID — число."); return; }
        Container c = containers.get(id);
        if (c == null) { player.sendMessage(msg("create.edit-not-found", "id", String.valueOf(id))); return; }
        openContainerGui(player, c);
    }

    private void handleSend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("errors.only-players")); return; }
        if (args.length < 3) { player.sendMessage(ChatColor.YELLOW + "Использование: /cont send <id> <ставка>"); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { player.sendMessage(ChatColor.RED + "ID — число."); return; }
        Container c = containers.get(id);
        if (c == null) { player.sendMessage(msg("bet.container-not-found", "id", String.valueOf(id))); return; }
        if (c.items.isEmpty()) { player.sendMessage(msg("bet.container-empty")); return; }
        double bet;
        try { bet = Double.parseDouble(args[2].replace(',', '.')); if (bet <= 0) throw new NumberFormatException(); }
        catch (Exception e) { player.sendMessage(msg("bet.input-invalid")); return; }
        tryBet(player, c, bet);
    }

    private void placeBet(Player player, Container c, double bet) {
        long now = System.currentTimeMillis();
        if (deadZoneEnabled() && c.expiresAt > 0) {
            long left = c.expiresAt - now;
            if (left > 0 && left <= deadZoneThreshold()) {
                player.sendMessage(msg("bet.dead-zone", "time", formatTime(left)));
                return;
            }
        }
        if (c.bettor != null && c.bet > 0) {
            OfflinePlayer prev = Bukkit.getOfflinePlayer(c.bettor);
            if (prev.getUniqueId() != null) {
                double refund = c.bet * refundRate();
                double lost = c.bet - refund;
                addBalance(prev.getUniqueId(), refund);
                addLost(prev.getUniqueId(), c.bettor, lost);
                Player op = prev.getPlayer();
                if (op != null && op.isOnline()) {
                    op.sendMessage(msg("bet.prev-interrupted", "id", String.valueOf(c.id)));
                    op.sendMessage(msg("bet.prev-refund",
                            "percent", String.valueOf((int)(refundRate() * 100)),
                            "amount", money(refund),
                            "lost", money(lost)));
                }
            }
        }
        takeBalance(player.getUniqueId(), bet);
        addSpent(player.getUniqueId(), player.getName(), bet);
        c.bettor = player.getName();
        c.bet = bet;
        if (antiSnipeEnabled() && c.expiresAt > 0) {
            long left = c.expiresAt - now;
            if (left > 0 && left <= antiSnipeThreshold()) {
                c.expiresAt = now + antiSnipeExtend();
                if (c.totalDuration < antiSnipeExtend()) c.totalDuration = antiSnipeExtend();
                Bukkit.broadcastMessage(msg("bet.anti-snipe",
                        "id", String.valueOf(c.id),
                        "time", formatTime(antiSnipeExtend())));
            }
        }
        c.history.add(0, new BetRecord(player.getName(), bet, now));
        while (c.history.size() > 20) c.history.remove(c.history.size() - 1);
        saveContainers();
        updateSign(c);
        playBetSound(c);
        Bukkit.broadcastMessage(msg("bet.placed-broadcast",
                "nick", player.getName(),
                "amount", money(bet),
                "id", String.valueOf(c.id),
                "name", c.name));
        if (c.playerCreated && c.ownerUuid != null && !c.ownerUuid.equals(player.getUniqueId())) {
            Player ownerOnline = Bukkit.getPlayer(c.ownerUuid);
            if (ownerOnline != null && ownerOnline.isOnline()) {
                ownerOnline.sendMessage(msg("auction.new-bet",
                        "id", String.valueOf(c.id),
                        "amount", money(bet),
                        "nick", player.getName()));
            }
        }
    }

    private void handleEnd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("betterrp.containers.admin")) { sender.sendMessage(msg("errors.no-permission")); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Использование: /cont end <id>"); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { sender.sendMessage(ChatColor.RED + "ID — число."); return; }
        Container c = containers.get(id);
        if (c == null) { sender.sendMessage(msg("remove.not-found")); return; }
        finishContainer(c, sender, false);
    }

    private void finishContainer(Container c, CommandSender initiator, boolean forceBuyout) {
        if (c.bettor != null) {
            Player winner = Bukkit.getPlayerExact(c.bettor);
            OfflinePlayer winnerOff = Bukkit.getOfflinePlayer(c.bettor);
            if (winner != null && winner.isOnline()) {
                for (ItemStack it : c.items) {
                    Map<Integer, ItemStack> left = winner.getInventory().addItem(it.clone());
                    for (ItemStack drop : left.values())
                        winner.getWorld().dropItemNaturally(winner.getLocation(), drop);
                }
                addWin(winner.getUniqueId(), c.bettor, c.bet);
            } else if (winnerOff.getUniqueId() != null) {
                addWin(winnerOff.getUniqueId(), c.bettor, c.bet);
                if (!c.items.isEmpty()) {
                    List<Map<String, Object>> pending = new ArrayList<>();
                    for (ItemStack is : c.items) pending.add(is.serialize());
                    String path = "pending." + c.bettor;
                    List<Map<?, ?>> existing = getConfig().getMapList(path);
                    for (Map<String, Object> m : pending) existing.add(m);
                    getConfig().set(path, existing);
                    saveConfig();
                }
                if (initiator != null) initiator.sendMessage(msg("finish.offline-saved"));
            }
            if (c.playerCreated && c.ownerUuid != null) {
                double commissionPercent = getConfig().getDouble("auction.sale-commission-percent", 10.0);
                double commission = c.bet * commissionPercent / 100.0;
                double net = c.bet - commission;
                addBalance(c.ownerUuid, net);
                Player ownerOnline = Bukkit.getPlayer(c.ownerUuid);
                if (ownerOnline != null && ownerOnline.isOnline()) {
                    ownerOnline.sendMessage(msg(forceBuyout ? "auction.bought-out" : "auction.sold",
                            "id", String.valueOf(c.id),
                            "amount", money(c.bet),
                            "fee", money(commission),
                            "net", money(net)));
                }
            }
            Bukkit.broadcastMessage(msg("finish.winner-broadcast",
                    "id", String.valueOf(c.id),
                    "nick", c.bettor,
                    "amount", money(c.bet)));
        } else {
            if (c.playerCreated && c.ownerUuid != null) {
                Player ownerOnline = Bukkit.getPlayer(c.ownerUuid);
                if (ownerOnline != null && ownerOnline.isOnline()) {
                    returnItems(ownerOnline, c.items);
                    ownerOnline.sendMessage(msg("auction.returned", "id", String.valueOf(c.id)));
                } else {
                    List<Map<String, Object>> pending = new ArrayList<>();
                    for (ItemStack is : c.items) pending.add(is.serialize());
                    String path = "pending." + c.ownerName;
                    List<Map<?, ?>> existing = getConfig().getMapList(path);
                    for (Map<String, Object> m : pending) existing.add(m);
                    getConfig().set(path, existing);
                    saveConfig();
                }
            }
            if (initiator != null) initiator.sendMessage(msg("finish.no-bets", "id", String.valueOf(c.id)));
        }
        removeContainerSign(c, true);
        containers.remove(c.id);
        saveContainers();
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("betterrp.containers.admin")) { sender.sendMessage(msg("errors.no-permission")); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Использование: /cont cancel <id>"); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { sender.sendMessage(ChatColor.RED + "ID — число."); return; }
        Container c = containers.remove(id);
        if (c == null) { sender.sendMessage(msg("remove.not-found")); return; }
        if (c.bettor != null && c.bet > 0) {
            OfflinePlayer prev = Bukkit.getOfflinePlayer(c.bettor);
            if (prev.getUniqueId() != null) {
                addBalance(prev.getUniqueId(), c.bet);
                Player op = prev.getPlayer();
                if (op != null && op.isOnline())
                    op.sendMessage(msg("remove.cancel-refund",
                            "id", String.valueOf(c.id),
                            "amount", money(c.bet)));
            }
        }
        if (sender instanceof Player admin) {
            returnItems(admin, c.items);
            admin.sendMessage(msg("remove.cancel-items", "id", String.valueOf(id)));
        }
        removeContainerSign(c, false);
        saveContainers();
        Bukkit.broadcastMessage(msg("remove.cancelled", "id", String.valueOf(id)));
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("betterrp.containers.admin")) { sender.sendMessage(msg("errors.no-permission")); return; }
        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Использование: /cont remove <id>"); return; }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (Exception e) { sender.sendMessage(ChatColor.RED + "ID — число."); return; }
        Container c = containers.remove(id);
        if (c == null) { sender.sendMessage(msg("remove.not-found")); return; }
        removeContainerSign(c, false);
        saveContainers();
        sender.sendMessage(msg("remove.removed", "id", String.valueOf(id)));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("betterrp.containers.admin")) { sender.sendMessage(msg("errors.no-permission")); return; }
        reloadConfig();
        syncConfig();
        loadStats();
        saveContainers();
        sender.sendMessage(msg("reload-done"));
    }

    private void handleStats(CommandSender sender) {
        if (!sender.hasPermission("betterrp.containers.admin")) { sender.sendMessage(msg("errors.no-permission")); return; }
        sender.sendMessage("");
        sender.sendMessage(msg("stats.header"));
        sender.sendMessage(msg("stats.divider"));
        double totalBets = 0.0;
        int withBets = 0;
        for (Container c : containers.values()) {
            totalBets += c.bet;
            if (c.bettor != null) withBets++;
        }
        sender.sendMessage(msg("stats.active", "count", String.valueOf(containers.size())));
        sender.sendMessage(msg("stats.with-bets", "count", String.valueOf(withBets)));
        sender.sendMessage(msg("stats.total-bets", "amount", money(totalBets)));
        sender.sendMessage(msg("stats.stats-entries", "count", String.valueOf(stats.size())));
        sender.sendMessage("");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String path = "pending." + p.getName();
        List<Map<?, ?>> pending = getConfig().getMapList(path);
        if (pending == null || pending.isEmpty()) return;
        int delivered = 0;
        for (Map<?, ?> map : pending) {
            try {
                @SuppressWarnings("unchecked")
                ItemStack is = ItemStack.deserialize((Map<String, Object>) map);
                if (is != null && is.getType() != Material.AIR) {
                    Map<Integer, ItemStack> left = p.getInventory().addItem(is);
                    for (ItemStack drop : left.values())
                        p.getWorld().dropItemNaturally(p.getLocation(), drop);
                    delivered++;
                }
            } catch (Exception ignored) {}
        }
        getConfig().set(path, null);
        saveConfig();
        if (delivered > 0) {
            p.sendMessage(msg("finish.offline-delivered", "id", "?", "reward", delivered + " предмет(ов)"));
        }
    }

    // ==================== МОДЕЛЬ ====================

    private static class Container {
        final int id;
        String name = "";
        final List<ItemStack> items = new ArrayList<>();
        String bettor;
        double bet = 0.0;
        long expiresAt = 0L;
        long totalDuration = 0L;
        String signWorld = null;
        int signX, signY, signZ;
        final List<BetRecord> history = new ArrayList<>();
        UUID hologramUUID = null;
        int hologramOpacity = 0;
        int hologramTarget = 0;
        int lastTickSecond = -1;
        UUID ownerUuid = null;
        String ownerName = null;
        double buyoutPrice = 0.0;
        boolean playerCreated = false;
        double startPrice = 0.0;
        Container(int id) { this.id = id; }
        void removeHologram() {
            if (hologramUUID != null) {
                Entity e = Bukkit.getEntity(hologramUUID);
                if (e != null) e.remove();
                hologramUUID = null;
            }
            hologramOpacity = 0;
            hologramTarget = 0;
            lastTickSecond = -1;
        }
    }

    private static class BetRecord {
        final String nick;
        final double amount;
        final long time;
        BetRecord(String nick, double amount, long time) { this.nick = nick; this.amount = amount; this.time = time; }
    }

    private static class PlayerStats {
        String nick = "?";
        int wins = 0;
        double wonMoney = 0.0;
        double spent = 0.0;
        double lost = 0.0;
    }

    private static class LotDraft {
        List<ItemStack> items = new ArrayList<>();
        double startPrice = 0.0;
        double buyoutPrice = 0.0;
        long duration = 0L;
    }
}