package id.veyrion.roleplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Veyrion Roleplay
 * Pemain baru: layar judul Veyrion -> menu utama -> gacha ras -> pengumuman -> bebas bergerak.
 */
public final class VeyrionPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final TextColor WHITE = NamedTextColor.WHITE;
    private static final TextColor GOLD = TextColor.color(0xFFC83D);
    private static final TextColor BLURPLE = TextColor.color(0x5865F2);

    private static final int[] REEL = {10, 11, 12, 13, 14, 15, 16};
    private static final int SLOT_GACHA = 22;
    private static final int SLOT_EXIT = 31;
    private static final int SLOT_DISCORD = 40;

    private File dataFile;
    private YamlConfiguration data;

    /** Pemain yang belum mendapat hasil gacha (menu wajib terbuka). */
    private final Set<UUID> pending = new HashSet<>();
    /** Pemain yang tidak boleh bergerak/berinteraksi. */
    private final Set<UUID> frozen = new HashSet<>();
    /** Pemain yang menutup menu dengan sengaja (tombol Discord), jangan buka ulang otomatis. */
    private final Set<UUID> allowClose = new HashSet<>();
    private final Map<UUID, Gacha> rolling = new HashMap<>();
    private final Random random = new Random();

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("veyrion") != null) getCommand("veyrion").setExecutor(this);
        if (getCommand("ras") != null) getCommand("ras").setExecutor(this);

        // Handle pemain yang sudah online saat plugin di-load/reload.
        for (Player p : Bukkit.getOnlinePlayers()) begin(p);
    }

    @Override
    public void onDisable() {
        saveData();
        for (Gacha g : rolling.values()) {
            if (g.task != null) g.task.cancel();
        }
        rolling.clear();
        frozen.clear();
        pending.clear();
    }

    // ------------------------------------------------------------------ data

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException ex) {
                getLogger().warning("Gagal membuat data.yml: " + ex.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Gagal menyimpan data.yml: " + ex.getMessage());
        }
    }

    private Race getRace(UUID id) {
        String s = data.getString("players." + id + ".race");
        if (s == null) return null;
        try {
            return Race.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isDone(UUID id) {
        return data.getBoolean("players." + id + ".done");
    }

    private void setRace(UUID id, Race r, boolean done) {
        data.set("players." + id + ".race", r.name());
        data.set("players." + id + ".done", done);
        saveData();
    }

    // ------------------------------------------------------------------ helper UI

    private static Component txt(String s, TextColor c, boolean bold) {
        return Component.text(s, c)
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, Component name, Component... lore) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(name);
        if (lore.length > 0) meta.lore(Arrays.asList(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        is.setItemMeta(meta);
        return is;
    }

    private static ItemStack pane(Material m) {
        return item(m, Component.text(" "));
    }

    private void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(this, r, ticks);
    }

    private Player online(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return (p != null && p.isOnline()) ? p : null;
    }

    private static final class MenuHolder implements InventoryHolder {
        final MenuType type;
        Inventory inv;

        MenuHolder(MenuType type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private enum MenuType { MAIN, GACHA }

    private static final class Gacha {
        final Race result;
        Inventory inv;
        int total;
        BukkitTask task;

        Gacha(Race result) {
            this.result = result;
        }
    }

    // ------------------------------------------------------------------ alur pemain

    /** Mulai alur untuk pemain: gacha baru, atau ulang pengumuman kalau hasil sudah ada tapi belum selesai. */
    private void begin(Player p) {
        UUID id = p.getUniqueId();
        Race r = getRace(id);
        if (r != null && isDone(id)) return;

        frozen.add(id);

        if (r != null) {
            // Sudah dapat hasil gacha tapi keluar sebelum pengumuman selesai.
            later(20L, () -> {
                Player q = online(id);
                if (q != null) reveal(q, r);
            });
            return;
        }

        pending.add(id);
        later(20L, () -> {
            Player q = online(id);
            if (q == null || !pending.contains(id)) return;
            q.showTitle(Title.title(
                    txt("V E Y R I O N", WHITE, true),
                    txt("Roleplay", GOLD, true),
                    Title.Times.times(Duration.ofMillis(800), Duration.ofMillis(3000), Duration.ofMillis(800))));
            q.playSound(q.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        });
        later(100L, () -> {
            Player q = online(id);
            if (q != null && pending.contains(id) && !rolling.containsKey(id)) openMain(q);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        begin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Gacha g = rolling.remove(id);
        if (g != null && g.task != null) g.task.cancel();
        pending.remove(id);
        frozen.remove(id);
        allowClose.remove(id);
    }

    // ------------------------------------------------------------------ menu utama

    private void openMain(Player p) {
        MenuHolder h = new MenuHolder(MenuType.MAIN);
        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text("Veyrion", WHITE).decoration(TextDecoration.BOLD, true)
                        .append(Component.text(" Roleplay", GOLD)));
        h.inv = inv;

        for (int i = 0; i < 54; i++) {
            int row = i / 9, col = i % 9;
            boolean edge = row == 0 || row == 5 || col == 0 || col == 8;
            inv.setItem(i, pane(edge ? Material.PURPLE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE));
        }

        inv.setItem(4, item(Material.NETHER_STAR,
                txt("V E Y R I O N", WHITE, true),
                txt("Roleplay", GOLD, true)));

        inv.setItem(SLOT_GACHA, item(Material.ENCHANTED_BOOK,
                txt("Tentukan Ras", GOLD, true),
                txt("Klik untuk memutar gacha ras.", NamedTextColor.GRAY, false),
                txt("Elf | Beast | Human | Dragon", NamedTextColor.DARK_GRAY, false)));

        inv.setItem(SLOT_EXIT, item(Material.BARRIER,
                txt("Keluar", NamedTextColor.RED, true),
                txt("Klik untuk keluar dari server.", NamedTextColor.GRAY, false)));

        inv.setItem(SLOT_DISCORD, item(Material.NAME_TAG,
                txt("Discord", BLURPLE, true),
                txt("Klik untuk mendapatkan link Discord.", NamedTextColor.GRAY, false)));

        p.openInventory(inv);
    }

    private void sendDiscord(Player p) {
        String url = getConfig().getString("discord-url", "https://discord.gg/HQ3cM2psVX");
        allowClose.add(p.getUniqueId());
        p.closeInventory();
        p.sendMessage(Component.empty());
        p.sendMessage(Component.text(" » Klik di sini untuk masuk Discord Veyrion", BLURPLE)
                .decoration(TextDecoration.UNDERLINED, true)
                .clickEvent(ClickEvent.openUrl(url)));
        p.sendMessage(Component.text(" » [ Kembali ke Menu ]", GOLD)
                .clickEvent(ClickEvent.runCommand("/veyrion menu")));
        p.sendMessage(Component.empty());
    }

    // ------------------------------------------------------------------ gacha

    private Race pick() {
        Race[] all = Race.values();
        double[] w = new double[all.length];
        double total = 0;
        for (int i = 0; i < all.length; i++) {
            w[i] = Math.max(0, getConfig().getDouble("races." + all[i].id + ".weight", 25));
            total += w[i];
        }
        if (total <= 0) return all[random.nextInt(all.length)];
        double roll = random.nextDouble() * total;
        for (int i = 0; i < all.length; i++) {
            roll -= w[i];
            if (roll < 0) return all[i];
        }
        return all[all.length - 1];
    }

    private ItemStack raceIcon(Race r) {
        return item(r.icon, txt(r.display, r.color, true));
    }

    private void renderReel(Gacha g, int offset) {
        Race[] all = Race.values();
        for (int j = 0; j < REEL.length; j++) {
            g.inv.setItem(REEL[j], raceIcon(all[(offset + j) % all.length]));
        }
    }

    private void startGacha(Player p) {
        UUID id = p.getUniqueId();
        if (!pending.contains(id) || rolling.containsKey(id)) return;

        // Hasil ditentukan & disimpan di awal supaya tidak bisa di-reroll dengan relog.
        Race result = pick();
        setRace(id, result, false);

        Gacha g = new Gacha(result);
        MenuHolder h = new MenuHolder(MenuType.GACHA);
        Inventory inv = Bukkit.createInventory(h, 27, txt("Tentukan Ras", GOLD, true));
        h.inv = inv;
        g.inv = inv;

        for (int i = 0; i < 27; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
        inv.setItem(4, item(Material.END_ROD, txt("▼", GOLD, true)));
        inv.setItem(22, item(Material.END_ROD, txt("▲", GOLD, true)));
        renderReel(g, 0);

        // Slot tengah (index 3 di reel) = hasil. Cari jumlah langkah supaya berhenti di ras hasil.
        int idx = result.ordinal();
        int n = Race.values().length;
        int total = 20;
        while ((total + 3) % n != idx) total++;
        g.total = total;

        rolling.put(id, g);
        p.openInventory(inv);

        g.task = Bukkit.getScheduler().runTaskLater(this, () -> step(id, g, 1), 10L);
    }

    private void step(UUID id, Gacha g, int s) {
        Player p = online(id);
        if (p == null || rolling.get(id) != g) return;

        renderReel(g, s);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, Math.min(2f, 1f + s * 0.02f));

        if (s >= g.total) {
            rolling.remove(id);
            reveal(p, g.result);
            return;
        }
        long delay = 2 + (long) s * s / 60;
        g.task = Bukkit.getScheduler().runTaskLater(this, () -> step(id, g, s + 1), delay);
    }

    /** Pengumuman hasil lalu bebaskan pemain. */
    private void reveal(Player p, Race r) {
        UUID id = p.getUniqueId();
        pending.remove(id);
        frozen.add(id);
        setRace(id, r, true);

        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        String iconic = getConfig().getString("races." + r.id + ".iconic", "KEKUATANMU");

        // 1.5 detik: tutup GUI
        later(30L, () -> {
            Player q = online(id);
            if (q != null && q.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                q.closeInventory();
            }
        });

        // Tahap 1: SELAMAT KAMU MENJADI RAS (hasil)
        later(34L, () -> {
            Player q = online(id);
            if (q == null) return;
            q.showTitle(Title.title(
                    txt("SELAMAT KAMU MENJADI RAS", WHITE, true),
                    txt(r.display.toUpperCase(Locale.ROOT), r.color, true),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2800), Duration.ofMillis(300))));
            q.sendMessage(Component.text("SELAMAT KAMU MENJADI RAS ", WHITE)
                    .append(Component.text(r.display.toUpperCase(Locale.ROOT), r.color)
                            .decoration(TextDecoration.BOLD, true)));
            q.playSound(q.getLocation(), r.sound, 1f, 1f);
        });

        // Tahap 2: AYO TUNJUKKAN ... DI DUNIA VEYRION
        later(110L, () -> {
            Player q = online(id);
            if (q == null) return;
            q.showTitle(Title.title(
                    txt("AYO TUNJUKKAN", GOLD, true),
                    txt(iconic + " DI DUNIA VEYRION", WHITE, true),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2800), Duration.ofMillis(500))));
            q.sendMessage(Component.text("AYO TUNJUKKAN " + iconic + " DI DUNIA VEYRION", GOLD)
                    .decoration(TextDecoration.BOLD, true));
        });

        // Bebas bergerak
        later(170L, () -> {
            frozen.remove(id);
            Player q = online(id);
            if (q != null) q.playSound(q.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        });
    }

    // ------------------------------------------------------------------ event GUI

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (h.type != MenuType.MAIN) return;

        switch (e.getRawSlot()) {
            case SLOT_GACHA -> startGacha(p);
            case SLOT_EXIT -> p.kick(Component.text(getConfig().getString("exit-message", "Sampai jumpa di Veyrion."), WHITE));
            case SLOT_DISCORD -> sendDiscord(p);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (allowClose.contains(id)) return;
        if (!rolling.containsKey(id) && !pending.contains(id)) return;

        // Menu wajib: buka ulang kalau ditutup lewat ESC.
        later(1L, () -> {
            Player q = online(id);
            if (q == null) return;
            if (q.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) return;
            Gacha g = rolling.get(id);
            if (g != null) q.openInventory(g.inv);
            else if (pending.contains(id)) openMain(q);
        });
    }

    // ------------------------------------------------------------------ freeze

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player v && frozen.contains(v.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by
                && by.getDamager() instanceof Player d
                && frozen.contains(d.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommandPre(PlayerCommandPreprocessEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (m.startsWith("/veyrion") || m.startsWith("/vr")) return;
        e.setCancelled(true);
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("ras")) {
            if (!(s instanceof Player p)) {
                s.sendMessage("Hanya untuk pemain.");
                return true;
            }
            Race r = getRace(p.getUniqueId());
            if (r == null) {
                p.sendMessage(Component.text("Kamu belum menentukan ras.", NamedTextColor.GRAY));
            } else {
                p.sendMessage(Component.text("Ras kamu: ", NamedTextColor.GRAY)
                        .append(Component.text(r.display, r.color).decoration(TextDecoration.BOLD, true)));
            }
            return true;
        }

        if (a.length == 0) {
            s.sendMessage("/veyrion menu | reset <pemain> | reload");
            return true;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> {
                if (s instanceof Player pl && pending.contains(pl.getUniqueId())) {
                    UUID id = pl.getUniqueId();
                    allowClose.remove(id);
                    Gacha g = rolling.get(id);
                    if (g != null) pl.openInventory(g.inv);
                    else openMain(pl);
                }
            }
            case "reload" -> {
                if (!s.hasPermission("veyrion.admin")) {
                    s.sendMessage("Tidak punya izin.");
                    return true;
                }
                reloadConfig();
                s.sendMessage("Config Veyrion di-reload.");
            }
            case "reset" -> {
                if (!s.hasPermission("veyrion.admin")) {
                    s.sendMessage("Tidak punya izin.");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage("/veyrion reset <pemain>");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(a[1]);
                UUID id = target.getUniqueId();
                data.set("players." + id, null);
                saveData();
                Gacha g = rolling.remove(id);
                if (g != null && g.task != null) g.task.cancel();
                pending.remove(id);
                frozen.remove(id);
                allowClose.remove(id);
                Player online = target.getPlayer();
                if (online != null) {
                    online.closeInventory();
                    begin(online);
                }
                s.sendMessage("Ras " + a[1] + " di-reset.");
            }
            default -> s.sendMessage("/veyrion menu | reset <pemain> | reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!c.getName().equalsIgnoreCase("veyrion")) return out;
        if (a.length == 1) {
            out.add("menu");
            if (s.hasPermission("veyrion.admin")) {
                out.add("reset");
                out.add("reload");
            }
            out.removeIf(x -> !x.startsWith(a[0].toLowerCase(Locale.ROOT)));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("reset") && s.hasPermission("veyrion.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
