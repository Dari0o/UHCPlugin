// File: src/main/java/de/uhc/plugin/UHCPlugin.java
package de.uhc.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.data.BlockData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class UHCPlugin extends JavaPlugin {

    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.gameManager = new GameManager(this);

        // Register command
        this.getCommand("uhc").setExecutor(new Commands(this.gameManager));

        // Register listeners
        getServer().getPluginManager().registerEvents(new DeathListener(this.gameManager), this);
        getServer().getPluginManager().registerEvents(new BlockChangeListener(this.gameManager), this);

        getLogger().info("UHCPlugin enabled");
    }

    @Override
    public void onDisable() {
        // Ensure rollback if a game was running
        if (gameManager.isRunning()) {
            gameManager.endGame(true);
        }
        getLogger().info("UHCPlugin disabled");
    }

    // -----------------------------
    // Inner classes: GameManager
    // -----------------------------
    public static class GameManager {
        public enum State { LOBBY, RUNNING, ENDED }

        private final JavaPlugin plugin;
        private State state = State.LOBBY;
        private long startTimeMillis = -1;
        private final Set<UUID> alivePlayers = ConcurrentHashMap.newKeySet();
        private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();

        // Map of location -> snapshot of original block (before modifications)
        private final Map<Location, BlockSnapshot> originalBlocks = new ConcurrentHashMap<>();

        private BukkitRunnable shrinkTask = null;
        private BukkitRunnable teleportTask = null;

        public GameManager(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        public boolean isRunning() { return state == State.RUNNING; }

        public void startGame() {
            if (isRunning()) return;
            state = State.RUNNING;
            startTimeMillis = System.currentTimeMillis();

            originalBlocks.clear();
            alivePlayers.clear();
            spectators.clear();

            // Teleport and prepare players
            Location lobby = getLobbySpawn();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (lobby != null) p.teleport(lobby);
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear(); // optional: give items according to your rules
                alivePlayers.add(p.getUniqueId());
            }

            // Schedule worldborder shrink start and final teleport
            int shrinkStartMinutes = plugin.getConfig().getInt("border.shrink-start-minutes", 10);
            int shrinkDurationMinutes = plugin.getConfig().getInt("border.shrink-duration-minutes", 5);
            int shrinkStartTicks = shrinkStartMinutes * 60 * 20;

            // Schedule shrink start
            shrinkTask = new BukkitRunnable() {
                @Override
                public void run() {
                    startBorderShrink(shrinkDurationMinutes);
                }
            };
            shrinkTask.runTaskLater(plugin, shrinkStartTicks);

            // Schedule teleport after shrink duration (start + duration)
            int teleportTicks = (shrinkStartMinutes + shrinkDurationMinutes) * 60 * 20;
            teleportTask = new BukkitRunnable() {
                @Override
                public void run() {
                    teleportAliveToArena();
                }
            };
            teleportTask.runTaskLater(plugin, teleportTicks);

            plugin.getServer().broadcastMessage(ChatColor.GREEN + "UHC Runde gestartet! Worldborder schrumpft in " + shrinkStartMinutes + " Minuten.");
        }

        public void endGame(boolean rollback) {
            if (shrinkTask != null) {
                shrinkTask.cancel();
                shrinkTask = null;
            }
            if (teleportTask != null) {
                teleportTask.cancel();
                teleportTask = null;
            }

            state = State.ENDED;

            if (rollback) {
                rollbackWorld();
            }

            // reset sets
            alivePlayers.clear();
            spectators.clear();
            originalBlocks.clear();

            // go back to lobby state
            state = State.LOBBY;
            plugin.getServer().broadcastMessage(ChatColor.YELLOW + "UHC Runde beendet.");
        }

        public void playerDied(Player p) {
            UUID id = p.getUniqueId();
            alivePlayers.remove(id);
            spectators.add(id);

            // Schedule to set spectator after death event completes
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.setGameMode(GameMode.SPECTATOR);
            });

            // Check for last player
            if (alivePlayers.size() == 1) {
                // Find winner
                UUID winnerId = alivePlayers.iterator().next();
                Player winner = Bukkit.getPlayer(winnerId);
                if (winner != null && winner.isOnline()) {
                    winner.sendTitle("Gewonnen!", "Du hast die Runde gewonnen!", 10, 70, 20);
                    Bukkit.broadcastMessage(ChatColor.GOLD + winner.getName() + " hat die UHC Runde gewonnen!");
                }
                // End game and rollback
                endGame(true);
            } else if (alivePlayers.isEmpty()) {
                Bukkit.broadcastMessage(ChatColor.RED + "Kein Spieler übrig. Runde endet.");
                endGame(true);
            }
        }

        public void recordBlockChange(Block block) {
            // store original only if not already stored
            Location loc = block.getLocation();
            originalBlocks.computeIfAbsent(loc, k -> BlockSnapshot.from(block));
        }

        private void rollbackWorld() {
            plugin.getLogger().info("Rollback: Wiederherstellung von " + originalBlocks.size() + " Blöcken...");
            // Restore all blocks to their original state
            for (Map.Entry<Location, BlockSnapshot> e : originalBlocks.entrySet()) {
                Location loc = e.getKey();
                BlockSnapshot snap = e.getValue();
                if (loc.getWorld() == null) continue;
                Block b = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                // set type
                try {
                    b.setType(snap.material, false);
                } catch (Exception ignored) {}
                try {
                    b.setBlockData(snap.blockData, false);
                } catch (Exception ignored) {}

                // restore inventory if present
                if (snap.inventoryContents != null) {
                    BlockState state = b.getState();
                    if (state instanceof InventoryHolder) {
                        InventoryHolder ih = (InventoryHolder) state;
                        Inventory inv = ih.getInventory();
                        inv.clear();
                        inv.setContents(cloneItemArray(snap.inventoryContents));
                        state.update(true, false);
                    }
                }
            }
            plugin.getLogger().info("Rollback abgeschlossen.");
        }

        private ItemStack[] cloneItemArray(ItemStack[] arr) {
            if (arr == null) return null;
            ItemStack[] copy = new ItemStack[arr.length];
            for (int i = 0; i < arr.length; i++) {
                copy[i] = arr[i] == null ? null : arr[i].clone();
            }
            return copy;
        }

        private Location getArenaLocation() {
            World w = Bukkit.getWorld(plugin.getConfig().getString("arena.world", Bukkit.getWorlds().get(0).getName()));
            double x = plugin.getConfig().getDouble("arena.x", 0);
            double y = plugin.getConfig().getDouble("arena.y", 64);
            double z = plugin.getConfig().getDouble("arena.z", 0);
            float yaw = (float) plugin.getConfig().getDouble("arena.yaw", 0);
            float pitch = (float) plugin.getConfig().getDouble("arena.pitch", 0);
            if (w == null) return null;
            return new Location(w, x, y, z, yaw, pitch);
        }

        private Location getLobbySpawn() {
            World w = Bukkit.getWorld(plugin.getConfig().getString("lobby.world", Bukkit.getWorlds().get(0).getName()));
            double x = plugin.getConfig().getDouble("lobby.x", 0);
            double y = plugin.getConfig().getDouble("lobby.y", 64);
            double z = plugin.getConfig().getDouble("lobby.z", 0);
            float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0);
            float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0);
            if (w == null) return null;
            return new Location(w, x, y, z, yaw, pitch);
        }

        public void teleportAliveToArena() {
            Location arena = getArenaLocation();
            if (arena == null) {
                plugin.getServer().broadcastMessage(ChatColor.RED + "Arena nicht gesetzt! Verwende /uhc setarena als Operator.");
                return;
            }
            for (UUID id : new HashSet<>(alivePlayers)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    p.teleport(arena);
                }
            }
            plugin.getServer().broadcastMessage(ChatColor.AQUA + "Alle noch lebenden Spieler wurden in die Arena teleportiert.");
        }

        private void startBorderShrink(int durationMinutes) {
            // read config values
            String worldName = plugin.getConfig().getString("border.world", Bukkit.getWorlds().get(0).getName());
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getServer().broadcastMessage(ChatColor.RED + "Border-World nicht gefunden: " + worldName);
                return;
            }

            double startSize = plugin.getConfig().getDouble("border.start-size", world.getWorldBorder().getSize());
            double endSize = plugin.getConfig().getDouble("border.end-size", Math.max(10.0, startSize / 10.0));
            double centerX = plugin.getConfig().getDouble("border.center.x", world.getSpawnLocation().getX());
            double centerZ = plugin.getConfig().getDouble("border.center.z", world.getSpawnLocation().getZ());

            WorldBorder wb = world.getWorldBorder();
            wb.setCenter(centerX, centerZ);
            wb.setSize(startSize);

            final long totalSeconds = durationMinutes * 60L;
            final long intervalSeconds = 1L; // update every second
            final long totalTicks = totalSeconds * 20L;

            plugin.getServer().broadcastMessage(ChatColor.RED + "Worldborder-Schrumpfung begonnen: " + startSize + " -> " + endSize + " über " + durationMinutes + " Minuten.");

            // schedule repeating task that updates the border size each second
            new BukkitRunnable() {
                long elapsedSeconds = 0;
                @Override
                public void run() {
                    if (!isRunning()) { this.cancel(); return; }
                    elapsedSeconds++;
                    double progress = Math.min(1.0, (double) elapsedSeconds / (double) totalSeconds);
                    double newSize = startSize + (endSize - startSize) * progress; // linear interpolation
                    wb.setSize(newSize);
                    if (elapsedSeconds >= totalSeconds) {
                        plugin.getServer().broadcastMessage(ChatColor.RED + "Worldborder-Schrumpfung abgeschlossen.");
                        this.cancel();
                    }
                }
            }.runTaskTimer(plugin, 0L, 20L);
        }

        // helpers to set locations via commands
        public void setArena(Location loc) {
            plugin.getConfig().set("arena.world", loc.getWorld().getName());
            plugin.getConfig().set("arena.x", loc.getX());
            plugin.getConfig().set("arena.y", loc.getY());
            plugin.getConfig().set("arena.z", loc.getZ());
            plugin.getConfig().set("arena.yaw", loc.getYaw());
            plugin.getConfig().set("arena.pitch", loc.getPitch());
            plugin.saveConfig();
        }

        public void setLobby(Location loc) {
            plugin.getConfig().set("lobby.world", loc.getWorld().getName());
            plugin.getConfig().set("lobby.x", loc.getX());
            plugin.getConfig().set("lobby.y", loc.getY());
            plugin.getConfig().set("lobby.z", loc.getZ());
            plugin.getConfig().set("lobby.yaw", loc.getYaw());
            plugin.getConfig().set("lobby.pitch", loc.getPitch());
            plugin.saveConfig();
        }
    }

    // -----------------------------
    // BlockSnapshot helper
    // -----------------------------
    private static class BlockSnapshot {
        public final Material material;
        public final BlockData blockData;
        public final ItemStack[] inventoryContents; // for containers

        private BlockSnapshot(Material material, BlockData blockData, ItemStack[] contents) {
            this.material = material;
            this.blockData = blockData;
            this.inventoryContents = contents;
        }

        public static BlockSnapshot from(Block block) {
            Material m = block.getType();
            BlockData bd = block.getBlockData().clone();
            ItemStack[] contents = null;
            BlockState state = block.getState();
            if (state instanceof InventoryHolder) {
                Inventory inv = ((InventoryHolder) state).getInventory();
                contents = inv.getContents();
            }
            return new BlockSnapshot(m, bd, contents);
        }
    }

    // -----------------------------
    // Listeners
    // -----------------------------
    public static class DeathListener implements Listener {
        private final GameManager gm;
        public DeathListener(GameManager gm) { this.gm = gm; }

        @EventHandler
        public void onPlayerDeath(PlayerDeathEvent e) {
            if (!gm.isRunning()) return;
            Player p = e.getEntity();
            // Immediately set them to spectator next tick
            gm.playerDied(p);
        }

        @EventHandler
        public void onRespawn(PlayerRespawnEvent e) {
            Player p = e.getPlayer();
            if (gm.spectators.contains(p.getUniqueId())) {
                Bukkit.getScheduler().runTaskLater(gm.plugin, () -> p.setGameMode(GameMode.SPECTATOR), 1L);
            }
        }
    }

    public static class BlockChangeListener implements Listener {
        private final GameManager gm;
        public BlockChangeListener(GameManager gm) { this.gm = gm; }

        @EventHandler
        public void onBlockPlace(BlockPlaceEvent e) {
            if (!gm.isRunning()) return;
            Block b = e.getBlockPlaced();
            // record the block originally at this location before placement
            gm.recordBlockChange(b);
        }

        @EventHandler
        public void onBlockBreak(BlockBreakEvent e) {
            if (!gm.isRunning()) return;
            Block b = e.getBlock();
            // record the block before it was broken
            gm.recordBlockChange(b);
        }
    }

    // -----------------------------
    // Commands
    // -----------------------------
    public static class Commands implements org.bukkit.command.CommandExecutor {
        private final GameManager gm;
        public Commands(GameManager gm) { this.gm = gm; }

        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage("§aUHC Plugin: /uhc start | stop | setarena | setlobby | setborder");
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start")) {
                if (!sender.hasPermission("uhc.start")) {
                    sender.sendMessage(ChatColor.RED + "Keine Rechte.");
                    return true;
                }
                gm.startGame();
                sender.sendMessage(ChatColor.GREEN + "UHC Runde gestartet.");
                return true;
            } else if (sub.equals("stop")) {
                if (!sender.hasPermission("uhc.stop")) {
                    sender.sendMessage(ChatColor.RED + "Keine Rechte.");
                    return true;
                }
                gm.endGame(true);
                sender.sendMessage(ChatColor.YELLOW + "UHC Runde gestoppt und zurückgesetzt.");
                return true;
            } else if (sub.equals("setarena")) {
                if (!(sender instanceof Player)) { sender.sendMessage("Nur Spieler."); return true; }
                Player p = (Player) sender;
                gm.setArena(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Arena gesetzt.");
                return true;
            } else if (sub.equals("setlobby")) {
                if (!(sender instanceof Player)) { sender.sendMessage("Nur Spieler."); return true; }
                Player p = (Player) sender;
                gm.setLobby(p.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Lobby-Spawn gesetzt.");
                return true;
            } else if (sub.equals("setborder")) {
                // /uhc setborder <startSize> <endSize> <centerX> <centerZ> <world(optional)>
                if (!(sender instanceof Player)) { sender.sendMessage("Nur Spieler."); return true; }
                if (!sender.hasPermission("uhc.setborder")) { sender.sendMessage(ChatColor.RED + "Keine Rechte."); return true; }
                if (args.length < 5) { sender.sendMessage(ChatColor.RED + "Benutzung: /uhc setborder <startSize> <endSize> <centerX> <centerZ> [world]"); return true; }
                try {
                    double start = Double.parseDouble(args[1]);
                    double end = Double.parseDouble(args[2]);
                    double cx = Double.parseDouble(args[3]);
                    double cz = Double.parseDouble(args[4]);
                    String worldName = args.length >= 6 ? args[5] : ((Player)sender).getWorld().getName();
                    plugin.getConfig().set("border.start-size", start);
                    plugin.getConfig().set("border.end-size", end);
                    plugin.getConfig().set("border.center.x", cx);
                    plugin.getConfig().set("border.center.z", cz);
                    plugin.getConfig().set("border.world", worldName);
                    plugin.saveConfig();
                    sender.sendMessage(ChatColor.GREEN + "Border-Settings gespeichert.");
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Zahlen ungültig.");
                }
                return true;
            }

            sender.sendMessage("Unbekannter Befehl. /uhc start|stop|setarena|setlobby|setborder");
            return true;
        }
    }
}
