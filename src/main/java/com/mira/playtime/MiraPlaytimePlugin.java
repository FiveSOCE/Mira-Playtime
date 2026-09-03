package com.mira.playtime;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MiraPlaytimePlugin extends JavaPlugin implements Listener {
    private PlaytimeService service;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private long afkMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        afkMillis = getConfig().getLong("afk-seconds", 300) * 1000L;
        service = new PlaytimeService(this);
        getServer().getServicesManager().register(MiraPlaytimeApi.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, service::save, 20L * 60, 20L * 60);
        Objects.requireNonNull(getCommand("playtime")).setExecutor(this);
        Objects.requireNonNull(getCommand("playtimetop")).setExecutor(this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new PlaytimePlaceholders(this).register();
    }

    @Override public void onDisable() {
        if (service != null) service.save();
        getServer().getServicesManager().unregisterAll(this);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            long last = lastActivity.getOrDefault(player.getUniqueId(), now);
            if (now - last < afkMillis) service.addSecond(player);
        }
    }

    private void touch(Player p) { lastActivity.put(p.getUniqueId(), System.currentTimeMillis()); service.rememberName(p); }
    @EventHandler public void onJoin(PlayerJoinEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { lastActivity.remove(e.getPlayer().getUniqueId()); service.save(); }
    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ())) touch(e.getPlayer());
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) { touch(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { touch(e.getPlayer()); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("playtime")) {
            OfflinePlayer target;
            if (args.length == 0) {
                if (!(sender instanceof Player p)) return true;
                target = p;
            } else {
                if (!sender.hasPermission("miraplaytime.others")) { sender.sendMessage(c("&cYou do not have permission.")); return true; }
                target = Bukkit.getOfflinePlayer(args[0]);
            }
            sender.sendMessage(c("&6" + (target.getName() == null ? target.getUniqueId() : target.getName()) + " &7active playtime:"));
            sender.sendMessage(c("&7Today: &f" + format(service.seconds(target.getUniqueId(), Scope.DAILY))));
            sender.sendMessage(c("&7This week: &f" + format(service.seconds(target.getUniqueId(), Scope.WEEKLY))));
            sender.sendMessage(c("&7All time: &f" + format(service.seconds(target.getUniqueId(), Scope.ALL))));
            return true;
        }
        Scope scope = args.length >= 1 ? Scope.parse(args[0]) : Scope.ALL;
        sender.sendMessage(c("&6&lPlaytime Top &7(" + scope.name().toLowerCase(Locale.ROOT) + ")"));
        List<PlaytimeEntry> top = service.top(scope, 10);
        for (int i = 0; i < top.size(); i++) sender.sendMessage(c("&e#" + (i + 1) + " &f" + top.get(i).name() + " &8- &a" + format(top.get(i).seconds())));
        if (top.isEmpty()) sender.sendMessage(c("&7No playtime recorded yet."));
        return true;
    }

    static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    static String format(long seconds) {
        long d = seconds / 86400; seconds %= 86400;
        long h = seconds / 3600; seconds %= 3600;
        long m = seconds / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    public enum Scope {
        DAILY, WEEKLY, ALL;
        static Scope parse(String raw) {
            if (raw == null) return ALL;
            return switch (raw.toLowerCase(Locale.ROOT)) { case "day", "daily", "today" -> DAILY; case "week", "weekly" -> WEEKLY; default -> ALL; };
        }
    }
    public record PlaytimeEntry(UUID uuid, String name, long seconds) {}
    public interface MiraPlaytimeApi {
        long seconds(UUID player, Scope scope);
        boolean isAfk(UUID player);
        List<PlaytimeEntry> top(Scope scope, int limit);
    }

    final class PlaytimeService implements MiraPlaytimeApi {
        private final MiraPlaytimePlugin plugin;
        private final File file;
        private YamlConfiguration data;
        PlaytimeService(MiraPlaytimePlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "playtime.yml");
            data = YamlConfiguration.loadConfiguration(file);
        }
        synchronized void rememberName(OfflinePlayer p) { if (p.getName() != null) data.set("players." + p.getUniqueId() + ".name", p.getName()); }
        synchronized void addSecond(Player p) {
            rememberName(p);
            String root = "players." + p.getUniqueId();
            data.set(root + ".all", data.getLong(root + ".all") + 1);
            data.set(root + ".days." + dayKey(), data.getLong(root + ".days." + dayKey()) + 1);
            data.set(root + ".weeks." + weekKey(), data.getLong(root + ".weeks." + weekKey()) + 1);
        }
        @Override public synchronized long seconds(UUID player, Scope scope) {
            String root = "players." + player;
            return switch (scope) { case DAILY -> data.getLong(root + ".days." + dayKey()); case WEEKLY -> data.getLong(root + ".weeks." + weekKey()); case ALL -> data.getLong(root + ".all"); };
        }
        @Override public boolean isAfk(UUID player) { return System.currentTimeMillis() - lastActivity.getOrDefault(player, 0L) >= afkMillis; }
        @Override public synchronized List<PlaytimeEntry> top(Scope scope, int limit) {
            var root = data.getConfigurationSection("players");
            if (root == null) return List.of();
            List<PlaytimeEntry> out = new ArrayList<>();
            for (String id : root.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(id);
                    long seconds = seconds(uuid, scope);
                    if (seconds <= 0) continue;
                    out.add(new PlaytimeEntry(uuid, data.getString("players." + id + ".name", id), seconds));
                } catch (IllegalArgumentException ignored) {}
            }
            return out.stream().sorted(Comparator.comparingLong(PlaytimeEntry::seconds).reversed().thenComparing(PlaytimeEntry::name, String.CASE_INSENSITIVE_ORDER)).limit(Math.max(1, limit)).toList();
        }
        synchronized void save() { try { data.save(file); } catch (IOException e) { plugin.getLogger().severe("Failed to save playtime.yml: " + e.getMessage()); } }
        private String dayKey() { return LocalDate.now().toString(); }
        private String weekKey() {
            LocalDate d = LocalDate.now();
            WeekFields wf = WeekFields.ISO;
            return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
        }
    }

    static final class PlaytimePlaceholders extends PlaceholderExpansion {
        private final MiraPlaytimePlugin plugin;
        PlaytimePlaceholders(MiraPlaytimePlugin plugin) { this.plugin = plugin; }
        @Override public @NotNull String getIdentifier() { return "miraplaytime"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }
        @Override public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            if (player == null) return null;
            return switch (params.toLowerCase(Locale.ROOT)) {
                case "daily" -> format(plugin.service.seconds(player.getUniqueId(), Scope.DAILY));
                case "weekly" -> format(plugin.service.seconds(player.getUniqueId(), Scope.WEEKLY));
                case "all", "total" -> format(plugin.service.seconds(player.getUniqueId(), Scope.ALL));
                case "daily_seconds" -> Long.toString(plugin.service.seconds(player.getUniqueId(), Scope.DAILY));
                case "weekly_seconds" -> Long.toString(plugin.service.seconds(player.getUniqueId(), Scope.WEEKLY));
                case "all_seconds" -> Long.toString(plugin.service.seconds(player.getUniqueId(), Scope.ALL));
                case "afk" -> Boolean.toString(plugin.service.isAfk(player.getUniqueId()));
                default -> null;
            };
        }
    }
}
