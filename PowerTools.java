package de.powersmp.powertools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class PowerTools extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final long TOOL_LIFETIME_MS = 5L * 24L * 60L * 60L * 1000L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 60L;
    private static final int MAX_TREE_BLOCKS = 64;
    private static final int BUCKET_MAX_CHARGES = 21;

    private NamespacedKey toolKey;
    private NamespacedKey createdAtKey;
    private NamespacedKey bucketChargesKey;
    private final Set<UUID> extraBreakInProgress = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        toolKey = new NamespacedKey(this, "powertool_type");
        createdAtKey = new NamespacedKey(this, "powertool_created_at");
        bucketChargesKey = new NamespacedKey(this, "blitz_bucket_charges");

        if (getCommand("powertools") == null) {
            getLogger().severe("Der Befehl 'powertools' fehlt in plugin.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getCommand("powertools").setExecutor(this);
        getCommand("powertools").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTaskTimer(this, this::removeExpiredTools,
                CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
        getServer().getScheduler().runTask(this, this::removeExpiredTools);

        getLogger().info("PowerTools aktiviert: 3x3 Drill, Baumfaeller, 3x3 Schaufel, 21er Eimer und 5-Tage-Ablauf.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("powertools.admin")) {
                sender.sendMessage(color("&cDazu hast du keine Berechtigung."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(color("&aPowerTools-Konfiguration neu geladen."));
            return true;
        }

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(color("&eVerwendung: &6/pt give <Spieler> <drill|axe|shovel|bucket> [Anzahl]"));
            sender.sendMessage(color("&eOder: &6/pt reload"));
            return true;
        }

        if (!sender.hasPermission("powertools.admin")) {
            sender.sendMessage(color("&cDazu hast du keine Berechtigung."));
            return true;
        }

        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(color("&cDieser Spieler ist nicht online."));
            return true;
        }

        String type = args[2].toLowerCase(Locale.ROOT);
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(color("&cDie Anzahl muss eine Zahl sein."));
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage(color("&cDie Anzahl muss zwischen 1 und 64 liegen."));
            return true;
        }
        if (type.equals("bucket") && amount > 1) {
            sender.sendMessage(color("&cDer Blitz Eimer kann nur einzeln vergeben werden."));
            return true;
        }

        ItemStack item = createTool(type);
        if (item == null) {
            sender.sendMessage(color("&cUnbekanntes oder deaktiviertes Tool. Nutze drill, axe, shovel oder bucket."));
            return true;
        }

        item.setAmount(amount);
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
        leftovers.values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));

        sender.sendMessage(color("&aDu hast &e" + amount + "x " + displayName(type)
                + " &aan &e" + target.getName() + " &avergeben."));
        if (!sender.equals(target)) {
            target.sendMessage(color("&aDu hast " + amount + "x " + displayName(type)
                    + " &avon PowerTools erhalten."));
        }
        return true;
    }

    private ItemStack createTool(String type) {
        Material material;
        String name;
        switch (type) {
            case "drill" -> {
                material = Material.NETHERITE_PICKAXE;
                name = "&e&l⚡ Blitz Drill! ⚡";
            }
            case "axe" -> {
                material = Material.NETHERITE_AXE;
                name = "&6&l⚡ Blitz Axt! ⚡";
            }
            case "shovel" -> {
                material = Material.NETHERITE_SHOVEL;
                name = "&e&l⚡ Blitz Schaufel! ⚡";
            }
            case "bucket" -> {
                if (!getConfig().getBoolean("tools.bucket.enabled", true)) return null;
                material = Material.WATER_BUCKET;
                name = "&9&l⚡ Blitz Eimer! ⚡";
            }
            default -> {
                return null;
            }
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(toolKey, PersistentDataType.STRING, type);
        pdc.set(createdAtKey, PersistentDataType.LONG, System.currentTimeMillis());
        if (type.equals("bucket")) {
            pdc.set(bucketChargesKey, PersistentDataType.INTEGER, BUCKET_MAX_CHARGES);
        }

        meta.setDisplayName(color(name));
        item.setItemMeta(meta);
        updateLore(item, System.currentTimeMillis());

        if (!type.equals("bucket")) {
            addEnchantment(item, Enchantment.EFFICIENCY,
                    getConfig().getInt("tools." + type + ".efficiency", 5));
            addEnchantment(item, Enchantment.UNBREAKING,
                    getConfig().getInt("tools." + type + ".unbreaking", 3));
            if (type.equals("drill")) {
                addEnchantment(item, Enchantment.FORTUNE,
                        getConfig().getInt("tools.drill.fortune", 0));
            }
        }
        return item;
    }

    private void updateLore(ItemStack item, long now) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String type = meta.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (type == null) return;

        long created = meta.getPersistentDataContainer().getOrDefault(
                createdAtKey, PersistentDataType.LONG, now);
        long remaining = Math.max(0L, TOOL_LIFETIME_MS - (now - created));

        List<String> lore = new ArrayList<>();
        lore.add(color("&8&m--------------------"));
        lore.add(color("&b&l✦ POWERTOOLS ✦"));
        lore.add(color("&7Geschmiedet fuer &e&lPowerSMP"));
        lore.add(color("&8"));
        lore.add(color("&6&l⚡ SPEZIALFAEHIGKEIT"));
        switch (type) {
            case "drill" -> {
                lore.add(color("&f• &e3x3 Abbau"));
                lore.add(color("&7Baut eine 3x3 Flaeche vor dir ab."));
                lore.add(color("&7Ideal fuer grosse Mining-Tunnel."));
                lore.add(color("&8"));
                lore.add(color("&7Verzauberung: &bEffizienz V"));
            }
            case "axe" -> {
                lore.add(color("&f• &eBlitz-Baumfaellung"));
                lore.add(color("&7Faellt verbundene Holzstamm-Bloecke."));
                lore.add(color("&7Maximal 64 Stamm-Bloecke pro Baum."));
            }
            case "shovel" -> {
                lore.add(color("&f• &e3x3 Erd-Abbau"));
                lore.add(color("&7Baut Erde, Sand, Kies und Schnee"));
                lore.add(color("&7in einer 3x3 Flaeche ab."));
            }
            case "bucket" -> {
                int charges = meta.getPersistentDataContainer().getOrDefault(
                        bucketChargesKey, PersistentDataType.INTEGER, BUCKET_MAX_CHARGES);
                lore.add(color("&f• &eWasser-Vorrat"));
                lore.add(color("&7Platziert Wasser ohne den Eimer"));
                lore.add(color("&7zu verlieren."));
                lore.add(color("&bWasserladungen: &f" + charges + "&7/&f" + BUCKET_MAX_CHARGES));
            }
            default -> lore.add(color("&7PowerSMP Spezialwerkzeug"));
        }
        lore.add(color("&8"));
        lore.add(color("&c&l⌛ VERFALL"));
        lore.add(color("&7Verbleibend: &f" + formatRemaining(remaining)));
        lore.add(color("&8&m--------------------"));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private String formatRemaining(long millis) {
        long totalMinutes = Math.max(0L, millis / 60_000L);
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long minutes = totalMinutes % 60L;
        return days + "T " + hours + "Std " + minutes + "Min";
    }

    private void addEnchantment(ItemStack item, Enchantment enchantment, int level) {
        if (level > 0) item.addUnsafeEnchantment(enchantment, Math.min(level, 10));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpecialBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        String type = getToolType(tool);
        if (type == null || extraBreakInProgress.contains(player.getUniqueId())) return;

        if (type.equals("drill")) {
            breakThreeByThree(event, tool, false);
        } else if (type.equals("shovel")) {
            breakThreeByThree(event, tool, true);
        } else if (type.equals("axe") && isLog(event.getBlock().getType())) {
            fellTree(event, tool);
        }
    }

    private void breakThreeByThree(BlockBreakEvent event, ItemStack tool, boolean shovelMode) {
        Block center = event.getBlock();
        Material centerType = center.getType();

        if (shovelMode && !isShovelBlock(centerType)) return;
        if (!shovelMode && !isPickaxeBlock(centerType)) return;

        Player player = event.getPlayer();
        Vector direction = player.getEyeLocation().getDirection();
        double ax = Math.abs(direction.getX());
        double ay = Math.abs(direction.getY());
        double az = Math.abs(direction.getZ());

        List<int[]> offsets = new ArrayList<>();
        if (ay >= ax && ay >= az) {
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++)
                    if (x != 0 || z != 0) offsets.add(new int[]{x, 0, z});
        } else if (ax >= az) {
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    if (y != 0 || z != 0) offsets.add(new int[]{0, y, z});
        } else {
            for (int x = -1; x <= 1; x++)
                for (int y = -1; y <= 1; y++)
                    if (x != 0 || y != 0) offsets.add(new int[]{x, y, 0});
        }

        UUID id = player.getUniqueId();
        extraBreakInProgress.add(id);
        try {
            for (int[] offset : offsets) {
                Block block = center.getRelative(offset[0], offset[1], offset[2]);
                if (block.getType().isAir() || block.isLiquid() || block.getType().getHardness() < 0) continue;
                if (shovelMode ? !isShovelBlock(block.getType()) : !isPickaxeBlock(block.getType())) continue;
                if (isProtectedBlockState(block)) continue;

                BlockBreakEvent extra = new BlockBreakEvent(block, player);
                getServer().getPluginManager().callEvent(extra);
                if (!extra.isCancelled()) block.breakNaturally(tool);
            }
        } finally {
            extraBreakInProgress.remove(id);
        }
    }

    private void fellTree(BlockBreakEvent event, ItemStack tool) {
        Block start = event.getBlock();
        Player player = event.getPlayer();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        UUID id = player.getUniqueId();
        extraBreakInProgress.add(id);
        int broken = 0;
        try {
            while (!queue.isEmpty() && broken < MAX_TREE_BLOCKS) {
                Block current = queue.removeFirst();
                if (!isLog(current.getType())) continue;

                if (current.equals(start)) {
                    // Der Startblock wird vom normalen BlockBreakEvent abgebaut.
                } else if (!isProtectedBlockState(current)) {
                    BlockBreakEvent extra = new BlockBreakEvent(current, player);
                    getServer().getPluginManager().callEvent(extra);
                    if (extra.isCancelled()) continue;
                    current.breakNaturally(tool);
                    broken++;
                }

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            Block next = current.getRelative(dx, dy, dz);
                            if (visited.add(next) && isLog(next.getType())) queue.addLast(next);
                        }
                    }
                }
            }
        } finally {
            extraBreakInProgress.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlitzBucketUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (!"bucket".equals(getToolType(hand))) return;

        event.setCancelled(true);
        if (event.getClickedBlock() == null || event.getBlockFace() == BlockFace.SELF) return;

        ItemMeta meta = hand.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int charges = pdc.getOrDefault(bucketChargesKey, PersistentDataType.INTEGER, BUCKET_MAX_CHARGES);
        if (charges <= 0) {
            event.getPlayer().sendMessage(color("&cDein Blitz Eimer ist leer!"));
            return;
        }

        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!target.getType().isAir() && !target.isLiquid() && !target.isPassable()) {
            event.getPlayer().sendMessage(color("&cDort kann kein Wasser platziert werden."));
            return;
        }
        if (isProtectedBlockState(target)) return;

        target.setType(Material.WATER, false);
        charges--;
        if (charges <= 0) {
            event.getPlayer().getInventory().setItemInMainHand(null);
            event.getPlayer().sendMessage(color("&cDein Blitz Eimer ist jetzt leer."));
        } else {
            pdc.set(bucketChargesKey, PersistentDataType.INTEGER, charges);
            hand.setItemMeta(meta);
            updateLore(hand, System.currentTimeMillis());
            event.getPlayer().getInventory().setItemInMainHand(hand);
            event.getPlayer().sendMessage(color("&bWasser platziert! &7Noch &f" + charges + "&7/&f" + BUCKET_MAX_CHARGES + " &7Ladungen."));
        }
    }

    private boolean isProtectedBlockState(Block block) {
        BlockState state = block.getState();
        return state instanceof TileState;
    }

    private boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_STEM") || name.equals("CRIMSON_HYPHAE") || name.equals("WARPED_HYPHAE");
    }

    private boolean isShovelBlock(Material material) {
        String name = material.name();
        return name.endsWith("_DIRT") || name.equals("DIRT") || name.equals("GRASS_BLOCK")
                || name.equals("SAND") || name.equals("RED_SAND") || name.equals("GRAVEL")
                || name.equals("CLAY") || name.equals("SNOW") || name.equals("SNOW_BLOCK")
                || name.equals("SOUL_SAND") || name.equals("SOUL_SOIL") || name.equals("MUD")
                || name.equals("MUDDY_MANGROVE_ROOTS") || name.equals("PODZOL")
                || name.equals("MYCELIUM") || name.equals("ROOTED_DIRT") || name.equals("COARSE_DIRT");
    }

    private boolean isPickaxeBlock(Material material) {
        String name = material.name();
        return name.endsWith("_ORE") || name.endsWith("_STONE") || name.endsWith("_DEEPSLATE")
                || name.equals("STONE") || name.equals("COBBLESTONE") || name.equals("COBBLED_DEEPSLATE")
                || name.equals("NETHERRACK") || name.equals("END_STONE") || name.equals("BLACKSTONE")
                || name.equals("BASALT") || name.equals("SMOOTH_BASALT") || name.equals("TUFF")
                || name.equals("CALCITE") || name.equals("OBSIDIAN") || name.equals("CRYING_OBSIDIAN")
                || name.equals("QUARTZ_BLOCK") || name.equals("NETHER_BRICKS")
                || name.equals("PRISMARINE") || name.equals("DARK_PRISMARINE")
                || name.equals("AMETHYST_BLOCK") || name.equals("BUDDING_AMETHYST")
                || name.equals("ANCIENT_DEBRIS") || name.equals("GILDED_BLACKSTONE")
                || name.equals("MOSSY_COBBLESTONE") || name.equals("BRICKS")
                || name.equals("DEEPSLATE") || name.equals("DRIPSTONE_BLOCK");
    }

    private String getToolType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private void removeExpiredTools() {
        long now = System.currentTimeMillis();
        int removed = 0;

        for (Player player : getServer().getOnlinePlayers()) {
            removed += cleanInventory(player.getInventory(), now);
            removed += cleanInventory(player.getEnderChest(), now);
        }

        for (World world : getServer().getWorlds()) {
            for (Item droppedItem : world.getEntitiesByClass(Item.class)) {
                ItemStack stack = droppedItem.getItemStack();
                if (isExpired(stack, now)) {
                    droppedItem.remove();
                    removed++;
                } else {
                    updateLore(stack, now);
                    droppedItem.setItemStack(stack);
                }
            }
        }

        if (removed > 0) getLogger().info(removed + " abgelaufene PowerTools-Item(s) entfernt.");
    }

    private int cleanInventory(Inventory inventory, long now) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isExpired(stack, now)) {
                inventory.setItem(slot, null);
                removed++;
            } else if (stack != null && getToolType(stack) != null) {
                updateLore(stack, now);
                inventory.setItem(slot, stack);
            }
        }
        return removed;
    }

    private boolean isExpired(ItemStack item, long now) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(toolKey, PersistentDataType.STRING)) return false;
        Long createdAt = pdc.get(createdAtKey, PersistentDataType.LONG);
        return createdAt != null && now - createdAt >= TOOL_LIFETIME_MS;
    }

    private String displayName(String type) {
        return switch (type) {
            case "drill" -> "Blitz Drill";
            case "axe" -> "Blitz Axt";
            case "shovel" -> "Blitz Schaufel";
            case "bucket" -> "Blitz Eimer";
            default -> type;
        };
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return startsWith(args[0], List.of("give", "reload"));
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return startsWith(args[2], List.of("drill", "axe", "shovel", "bucket"));
        }
        return java.util.Collections.emptyList();
    }

    private List<String> startsWith(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
