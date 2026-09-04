package com.mira.playtime;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.core.api.PaginationService;
import com.mira.leaderboards.MiraLeaderboardsPlugin.BoardScope;
import com.mira.leaderboards.MiraLeaderboardsPlugin.MiraLeaderboardsApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.RegisteredServiceProvider;
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
    private static final String PREFIX = "&5&lMira &8>> &r";

    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private MiraCore core;
    private PlaytimeService service;
    private MiraLeaderboardsApi leaderboards;
    private long afkMillis;
    private ZoneId zone;
    private String observedDay;
    private String observedWeek;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        afkMillis = Math.max(1L, getConfig().getLong("afk-seconds", 300L)) * 1000L;
        zone = parseZone(getConfig().getString("timezone", "UTC"));
        service = new PlaytimeService(this);

        getServer().getServicesManager().register(MiraPlaytimeApi.class, service, this, ServicePriority.Normal);
        core.modules().register(this, "MiraPlaytime");
        core.services().register(MiraPlaytimeApi.class, service);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) touch(player);

        observedDay = service.dayKey();
        observedWeek = service.weekKey();

        Objects.requireNonNull(getCommand("playtime")).setExecutor(this);
        Objects.requireNonNull(getCommand("playtimetop")).setExecutor(this);
        Objects.requireNonNull(getCommand("playtime")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("playtimetop")).setTabCompleter(this);

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, service::save, 20L * 60L, 20L * 60L);

        long syncTicks = Math.max(10L, getConfig().getLong("leaderboards.sync-seconds", 60L)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::maintenanceAndSync, 40L, syncTicks);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaytimePlaceholders(this).register();
        }

        connectLeaderboards();
        if (leaderboards != null) syncAllLeaderboards();

        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                leaderboards == null
                        ? "AFK-aware playtime ready; MiraLeaderboards unavailable"
                        : "AFK-aware playtime, milestones and Leaderboards publishing ready");

        getLogger().info("MiraPlaytime v" + getPluginMeta().getVersion() + " enabled using timezone " + zone + ".");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            if (leaderboards != null) {
                for (Player player : Bukkit.getOnlinePlayers()) publishPlayer(player.getUniqueId());
            }
            service.save();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (service != null) core.services().unregister(MiraPlaytimeApi.class, service);
            core.modules().unregister(this);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            long last = lastActivity.getOrDefault(player.getUniqueId(), now);
            if (now - last < afkMillis) {
                service.addSecond(player);
            }
        }
    }

    private void maintenanceAndSync() {
        String day = service.dayKey();
        String week = service.weekKey();

        if (!day.equals(observedDay)) {
            String previous = observedDay;
            observedDay = day;
            service.pruneHistory();
            if (leaderboards != null) {
                leaderboards.clear(dailyBoard());
                syncScope(Scope.DAILY);
            }
            core.audit().record("MiraPlaytime", "DAY_ROLLOVER", null, "scheduler",
                    day, "Playtime daily period rolled over",
                    Map.of("previous", previous == null ? "" : previous, "current", day));
        }

        if (!week.equals(observedWeek)) {
            String previous = observedWeek;
            observedWeek = week;
            service.pruneHistory();
            if (leaderboards != null) {
                leaderboards.clear(weeklyBoard());
                syncScope(Scope.WEEKLY);
            }
            core.audit().record("MiraPlaytime", "WEEK_ROLLOVER", null, "scheduler",
                    week, "Playtime weekly period rolled over",
                    Map.of("previous", previous == null ? "" : previous, "current", week));
        }

        if (leaderboards == null) connectLeaderboards();
        if (leaderboards != null) {
            for (Player player : Bukkit.getOnlinePlayers()) publishPlayer(player.getUniqueId());
        }

        for (Player player : Bukkit.getOnlinePlayers()) checkMilestones(player);
    }

    private void connectLeaderboards() {
        if (!getConfig().getBoolean("leaderboards.enabled", true)
                || !Bukkit.getPluginManager().isPluginEnabled("MiraLeaderboards")) {
            leaderboards = null;
            return;
        }
        RegisteredServiceProvider<MiraLeaderboardsApi> registration =
                Bukkit.getServicesManager().getRegistration(MiraLeaderboardsApi.class);
        leaderboards = registration == null ? null : registration.getProvider();
        if (leaderboards != null) {
            leaderboards.configure(dailyBoard(), BoardScope.ALL_TIME, "");
            leaderboards.configure(weeklyBoard(), BoardScope.ALL_TIME, "");
            leaderboards.configure(allTimeBoard(), BoardScope.ALL_TIME, "");
        }
    }

    private void syncAllLeaderboards() {
        syncScope(Scope.DAILY);
        syncScope(Scope.WEEKLY);
        syncScope(Scope.ALL);
    }

    private void syncScope(Scope scope) {
        if (leaderboards == null) return;
        String board = board(scope);
        for (PlaytimeEntry entry : service.top(scope, Integer.MAX_VALUE)) {
            leaderboards.publish("miraplaytime", board, entry.uuid().toString(), entry.name(), entry.seconds());
        }
    }

    private void publishPlayer(UUID playerId) {
        if (leaderboards == null || playerId == null) return;
        String name = service.name(playerId);
        leaderboards.publish("miraplaytime", dailyBoard(), playerId.toString(), name,
                service.seconds(playerId, Scope.DAILY));
        leaderboards.publish("miraplaytime", weeklyBoard(), playerId.toString(), name,
                service.seconds(playerId, Scope.WEEKLY));
        leaderboards.publish("miraplaytime", allTimeBoard(), playerId.toString(), name,
                service.seconds(playerId, Scope.ALL));
    }

    private void checkMilestones(Player player) {
        long seconds = service.seconds(player.getUniqueId(), Scope.ALL);
        for (Integer hours : getConfig().getIntegerList("milestones.hours")) {
            if (hours == null || hours <= 0) continue;
            if (seconds < hours.longValue() * 3600L) continue;
            core.milestones().award(player.getUniqueId(), "miraplaytime.hours_" + hours,
                    "MiraPlaytime", Map.of("hours", Integer.toString(hours)));
        }
    }

    private void touch(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        service.rememberName(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
        if (leaderboards != null) publishPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (leaderboards != null) publishPlayer(playerId);
        lastActivity.remove(playerId);
        service.save();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            touch(event.getPlayer());
        }
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) { touch(event.getPlayer()); }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent event) { touch(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { touch(event.getPlayer()); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("playtime")) {
            OfflinePlayer target;
            if (args.length == 0) {
                if (!(sender instanceof Player player)) {
                    msg(sender, "&cUse /playtime <player> from console.");
                    return true;
                }
                target = player;
            } else {
                if (!sender.hasPermission("miraplaytime.others")) {
                    msg(sender, "&cYou do not have permission.");
                    return true;
                }
                target = Bukkit.getOfflinePlayer(args[0]);
                if (target.getName() == null && !target.hasPlayedBefore() && !target.isOnline()) {
                    msg(sender, "&cPlayer not found.");
                    return true;
                }
            }

            UUID id = target.getUniqueId();
            msg(sender, "&6" + service.name(id) + " &7active playtime:");
            msg(sender, "&7Today: &f" + format(service.seconds(id, Scope.DAILY))
                    + " &8(#" + service.rank(id, Scope.DAILY) + ")");
            msg(sender, "&7This week: &f" + format(service.seconds(id, Scope.WEEKLY))
                    + " &8(#" + service.rank(id, Scope.WEEKLY) + ")");
            msg(sender, "&7All time: &f" + format(service.seconds(id, Scope.ALL))
                    + " &8(#" + service.rank(id, Scope.ALL) + ")");
            if (target.isOnline()) msg(sender, "&7AFK: " + (service.isAfk(id) ? "&eYes" : "&aNo"));
            return true;
        }

        Scope scope = args.length >= 1 ? Scope.parse(args[0]) : Scope.ALL;
        int requestedPage = args.length >= 2 ? parseInt(args[1], 1) : 1;
        List<PlaytimeEntry> values = service.top(scope, 100);
        PaginationService.Page<PlaytimeEntry> page = core.pagination().page(values, requestedPage, 10);
        msg(sender, "&6&lPlaytime Top &7(" + scope.display() + ", page " + page.page() + "/" + page.pages() + ")");
        int firstRank = (page.page() - 1) * page.pageSize() + 1;
        for (int i = 0; i < page.values().size(); i++) {
            PlaytimeEntry entry = page.values().get(i);
            msg(sender, "&e#" + (firstRank + i) + " &f" + entry.name()
                    + " &8- &a" + format(entry.seconds()));
        }
        if (values.isEmpty()) msg(sender, "&7No playtime recorded yet.");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("playtime")) {
            if (args.length == 1 && sender.hasPermission("miraplaytime.others")) {
                return complete(args[0], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            }
            return List.of();
        }
        if (args.length == 1) return complete(args[0], List.of("daily", "weekly", "all"));
        return List.of();
    }

    private String board(Scope scope) {
        return switch (scope) {
            case DAILY -> dailyBoard();
            case WEEKLY -> weeklyBoard();
            case ALL -> allTimeBoard();
        };
    }

    private String dailyBoard() {
        return getConfig().getString("leaderboards.daily-board", "playtime_daily");
    }

    private String weeklyBoard() {
        return getConfig().getString("leaderboards.weekly-board", "playtime_weekly");
    }

    private String allTimeBoard() {
        return getConfig().getString("leaderboards.all-time-board", "playtime_all");
    }

    private ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? "UTC" : raw.trim());
        } catch (DateTimeException exception) {
            getLogger().warning("Invalid timezone '" + raw + "'. Falling back to UTC.");
            return ZoneOffset.UTC;
        }
    }

    private void msg(CommandSender sender, String raw) {
        core.messages().send(sender, raw);
    }

    static String c(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    static String format(long seconds) {
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    static int parseInt(String raw, int fallback) {
        try { return Integer.parseInt(raw); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    public enum Scope {
        DAILY,
        WEEKLY,
        ALL;

        static Scope parse(String raw) {
            if (raw == null) return ALL;
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "day", "daily", "today" -> DAILY;
                case "week", "weekly" -> WEEKLY;
                default -> ALL;
            };
        }

        String display() {
            return switch (this) {
                case DAILY -> "daily";
                case WEEKLY -> "weekly";
                case ALL -> "all time";
            };
        }
    }

    public record PlaytimeEntry(UUID uuid, String name, long seconds) {}

    public interface MiraPlaytimeApi {
        long seconds(UUID player, Scope scope);
        boolean isAfk(UUID player);
        List<PlaytimeEntry> top(Scope scope, int limit);
        int rank(UUID player, Scope scope);
        Optional<Instant> lastActivity(UUID player);
        ZoneId timezone();
        String currentDayKey();
        String currentWeekKey();
    }

    final class PlaytimeService implements MiraPlaytimeApi {
        private final MiraPlaytimePlugin plugin;
        private final File file;
        private YamlConfiguration data;

        PlaytimeService(MiraPlaytimePlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "playtime.yml");
            this.data = YamlConfiguration.loadConfiguration(file);
            pruneHistory();
        }

        synchronized void rememberName(OfflinePlayer player) {
            if (player.getName() != null) {
                data.set("players." + player.getUniqueId() + ".name", player.getName());
            }
        }

        synchronized String name(UUID player) {
            return data.getString("players." + player + ".name",
                    Optional.ofNullable(Bukkit.getOfflinePlayer(player).getName()).orElse(player.toString()));
        }

        synchronized void addSecond(Player player) {
            rememberName(player);
            String root = "players." + player.getUniqueId();
            data.set(root + ".all", data.getLong(root + ".all") + 1L);
            String day = dayKey();
            String week = weekKey();
            data.set(root + ".days." + day, data.getLong(root + ".days." + day) + 1L);
            data.set(root + ".weeks." + week, data.getLong(root + ".weeks." + week) + 1L);
        }

        @Override
        public synchronized long seconds(UUID player, Scope scope) {
            if (player == null) return 0L;
            String root = "players." + player;
            return switch (scope) {
                case DAILY -> data.getLong(root + ".days." + dayKey());
                case WEEKLY -> data.getLong(root + ".weeks." + weekKey());
                case ALL -> data.getLong(root + ".all");
            };
        }

        @Override
        public boolean isAfk(UUID player) {
            if (player == null || Bukkit.getPlayer(player) == null) return false;
            Long last = lastActivity.get(player);
            return last != null && System.currentTimeMillis() - last >= afkMillis;
        }

        @Override
        public synchronized List<PlaytimeEntry> top(Scope scope, int limit) {
            ConfigurationSection root = data.getConfigurationSection("players");
            if (root == null || limit <= 0) return List.of();
            List<PlaytimeEntry> out = new ArrayList<>();
            for (String id : root.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(id);
                    long value = seconds(uuid, scope);
                    if (value <= 0) continue;
                    out.add(new PlaytimeEntry(uuid, data.getString("players." + id + ".name", id), value));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out.stream()
                    .sorted(Comparator.comparingLong(PlaytimeEntry::seconds).reversed()
                            .thenComparing(PlaytimeEntry::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(entry -> entry.uuid().toString()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized int rank(UUID player, Scope scope) {
            if (player == null || seconds(player, scope) <= 0L) return 0;
            List<PlaytimeEntry> ordered = top(scope, Integer.MAX_VALUE);
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).uuid().equals(player)) return i + 1;
            }
            return 0;
        }

        @Override
        public Optional<Instant> lastActivity(UUID player) {
            Long value = lastActivity.get(player);
            return value == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
        }

        @Override public ZoneId timezone() { return zone; }
        @Override public String currentDayKey() { return dayKey(); }
        @Override public String currentWeekKey() { return weekKey(); }

        synchronized void pruneHistory() {
            int retainDays = Math.max(1, plugin.getConfig().getInt("history.retain-days", 90));
            int retainWeeks = Math.max(1, plugin.getConfig().getInt("history.retain-weeks", 52));
            Set<String> keepDays = new HashSet<>();
            Set<String> keepWeeks = new HashSet<>();
            LocalDate today = LocalDate.now(zone);
            for (int i = 0; i < retainDays; i++) keepDays.add(today.minusDays(i).toString());
            for (int i = 0; i < retainWeeks; i++) keepWeeks.add(weekKey(today.minusWeeks(i)));

            ConfigurationSection root = data.getConfigurationSection("players");
            if (root == null) return;
            for (String player : root.getKeys(false)) {
                ConfigurationSection days = data.getConfigurationSection("players." + player + ".days");
                if (days != null) {
                    for (String key : new ArrayList<>(days.getKeys(false))) {
                        if (!keepDays.contains(key)) data.set("players." + player + ".days." + key, null);
                    }
                }
                ConfigurationSection weeks = data.getConfigurationSection("players." + player + ".weeks");
                if (weeks != null) {
                    for (String key : new ArrayList<>(weeks.getKeys(false))) {
                        if (!keepWeeks.contains(key)) data.set("players." + player + ".weeks." + key, null);
                    }
                }
            }
        }

        synchronized void save() {
            try {
                data.save(file);
            } catch (IOException exception) {
                plugin.getLogger().severe("Failed to save playtime.yml: " + exception.getMessage());
            }
        }

        private String dayKey() {
            return LocalDate.now(zone).toString();
        }

        private String weekKey() {
            return weekKey(LocalDate.now(zone));
        }

        private String weekKey(LocalDate date) {
            WeekFields fields = WeekFields.ISO;
            return date.get(fields.weekBasedYear()) + "-W"
                    + String.format(Locale.ROOT, "%02d", date.get(fields.weekOfWeekBasedYear()));
        }
    }

    static final class PlaytimePlaceholders extends PlaceholderExpansion {
        private final MiraPlaytimePlugin plugin;

        PlaytimePlaceholders(MiraPlaytimePlugin plugin) {
            this.plugin = plugin;
        }

        @Override public @NotNull String getIdentifier() { return "miraplaytime"; }
        @Override public @NotNull String getAuthor() { return "FiveS"; }
        @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
            if (player == null) return null;
            UUID id = player.getUniqueId();
            return switch (params.toLowerCase(Locale.ROOT)) {
                case "daily" -> format(plugin.service.seconds(id, Scope.DAILY));
                case "weekly" -> format(plugin.service.seconds(id, Scope.WEEKLY));
                case "all", "total" -> format(plugin.service.seconds(id, Scope.ALL));
                case "daily_seconds" -> Long.toString(plugin.service.seconds(id, Scope.DAILY));
                case "weekly_seconds" -> Long.toString(plugin.service.seconds(id, Scope.WEEKLY));
                case "all_seconds" -> Long.toString(plugin.service.seconds(id, Scope.ALL));
                case "daily_rank" -> Integer.toString(plugin.service.rank(id, Scope.DAILY));
                case "weekly_rank" -> Integer.toString(plugin.service.rank(id, Scope.WEEKLY));
                case "all_rank" -> Integer.toString(plugin.service.rank(id, Scope.ALL));
                case "afk" -> Boolean.toString(plugin.service.isAfk(id));
                case "day_key" -> plugin.service.currentDayKey();
                case "week_key" -> plugin.service.currentWeekKey();
                default -> null;
            };
        }
    }
}
