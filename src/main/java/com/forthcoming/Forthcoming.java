package com.forthcoming;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Forthcoming extends JavaPlugin implements Listener {

    // Configuration constants
    private static final int TELEPORT_DISTANCE = 2500;
    private static final int PUNISHMENT_DURATION_SECONDS = 120;
    private static final long INITIAL_DELAY_TICKS = 40L;
    private static final long TITLES_START_DELAY = 100L;
    private static final long EFFECTS_START_DELAY = 200L;

    private final Set<UUID> teleportAfterRespawn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> punishingPlayers = ConcurrentHashMap.newKeySet();

    // Track pet kills: Key is killer UUID, Value is map of (owner-name:pet-name) -> count
    private final Map<UUID, Map<String, Integer>> petKillTracker = new ConcurrentHashMap<>();

    // File handling
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private File dataFile;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // Create data directory if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        dataFile = new File(getDataFolder(), "pet_kills.json");
        loadPetKills();

        getLogger().info("Forthcoming enabled!");
    }

    @Override
    public void onDisable() {
        savePetKills();
        getLogger().info("Forthcoming disabled!");
    }

    @EventHandler
    public void onPetDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Tameable)) return;
        Tameable pet = (Tameable) event.getEntity();
        if (!pet.isTamed()) return;
        if (!(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        if (pet.getOwner() != null && pet.getOwner().equals(attacker)) return;

        // Only trigger on killing blow
        double damage = event.getFinalDamage();
        double petHealth = ((Damageable) event.getEntity()).getHealth();
        if (damage < petHealth) return;

        UUID killerId = attacker.getUniqueId();
        if (punishingPlayers.contains(killerId)) return;
        punishingPlayers.add(killerId);

        // Track the pet kill
        recordPetKill(attacker, pet);

        // Wait 2 seconds before first title
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!attacker.isOnline()) {
                punishingPlayers.remove(killerId);
                return;
            }

            // TITLE 1: "What have you done" with scary cave sound
            attacker.sendTitle("§cWhat have you done", "", 5, 100, 5);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.AMBIENT_CAVE, 2f, 0.8f);

            // TITLE 2 after 5 seconds with spooky bat sound
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!attacker.isOnline()) return;
                attacker.sendTitle("§cYou have awakened the Forthcoming, the END", "", 5, 100, 5);
                attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_BAT_AMBIENT, 2f, 0.8f);

                // Fade out after 10 seconds
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!attacker.isOnline()) return;
                    attacker.sendTitle("", "", 10, 1, 10);
                }, 200L);

            }, TITLES_START_DELAY);

            // Start 2-minute effects
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!attacker.isOnline()) {
                    punishingPlayers.remove(killerId);
                    return;
                }

                int duration = 20 * PUNISHMENT_DURATION_SECONDS;
                applyEffects(attacker, duration);

                // Gradual cave noises every second
                for (int i = 0; i < PUNISHMENT_DURATION_SECONDS; i++) {
                    int delay = i * 20;
                    int finalI = i;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (!attacker.isOnline()) return;
                        Sound sound;
                        switch (finalI % 3) {
                            case 0 -> sound = Sound.AMBIENT_CAVE;
                            case 1 -> sound = Sound.ENTITY_BAT_AMBIENT;
                            default -> sound = Sound.AMBIENT_UNDERWATER_LOOP;
                        }
                        float pitch = 1f + (finalI / 240f);
                        attacker.getWorld().playSound(attacker.getLocation(), sound, 2f, pitch);
                    }, delay);
                }

                // Final punishment after 2 minutes
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!attacker.isOnline()) {
                        punishingPlayers.remove(killerId);
                        return;
                    }
                    attacker.sendTitle("§cDon't Do It Again Please :)", "", 10, 100, 10);
                    attacker.sendMessage("§cDon't Do It Again Please :)");
                    attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 1f);

                    // Give the kill record book
                    attacker.getInventory().addItem(createPetKillBook(attacker));

                    teleportAfterRespawn.add(attacker.getUniqueId());
                    attacker.setHealth(0.0);
                    punishingPlayers.remove(killerId);
                }, duration);

            }, EFFECTS_START_DELAY);
        }, INITIAL_DELAY_TICKS);
    }

    private void recordPetKill(Player killer, Tameable pet) {
        UUID killerId = killer.getUniqueId();
        String ownerName = pet.getOwner() != null ? ((Player) pet.getOwner()).getName() : "Unknown";
        String petName = pet.getCustomName() != null ? pet.getCustomName() : pet.getType().toString();
        String key = ownerName + ":" + petName;

        petKillTracker.computeIfAbsent(killerId, k -> new ConcurrentHashMap<>())
                .merge(key, 1, Integer::sum);

        // Save to file asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(this, this::savePetKills);
    }

    private ItemStack createPetKillBook(Player killer) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        String killerName = killer.getName();
        meta.setTitle("§c§lDeath Note");
        meta.setAuthor("Forthcoming");

        StringBuilder content = new StringBuilder();
        content.append("§6=== Pet Kill Record ===\n\n");
        content.append("§eKiller: §f").append(killerName).append("\n\n");

        Map<String, Integer> kills = petKillTracker.get(killer.getUniqueId());
        if (kills != null && !kills.isEmpty()) {
            content.append("§ePets Killed:\n\n");
            for (Map.Entry<String, Integer> entry : kills.entrySet()) {
                String[] parts = entry.getKey().split(":");
                String owner = parts[0];
                String petName = parts.length > 1 ? parts[1] : "Unknown";
                int count = entry.getValue();

                content.append("§f• §ePet: §f").append(petName).append("\n");
                content.append("  §eOwner: §f").append(owner).append("\n");
                content.append("  §eKilled: §f").append(count).append(" time").append(count > 1 ? "s" : "").append("\n\n");
            }
        } else {
            content.append("§cNo pet kills recorded.\n");
        }

        meta.addPage(content.toString());
        book.setItemMeta(meta);
        return book;
    }

    private void applyEffects(Player player, int duration) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, duration, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 2, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, duration, 2, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, duration, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.UNLUCK, duration, 0, false, false));
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (teleportAfterRespawn.contains(id)) {
            teleportAfterRespawn.remove(id);
            Location loc = player.getLocation();
            Location farAway = new Location(loc.getWorld(), loc.getX() + TELEPORT_DISTANCE, loc.getY(), loc.getZ());
            event.setRespawnLocation(farAway);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        UUID id = player.getUniqueId();

        if (!punishingPlayers.contains(id)) return;

        // Prevent player from dying early (cap damage to keep them at 1 health)
        double newHealth = player.getHealth() - event.getFinalDamage();
        if (newHealth <= 1.0) {
            event.setDamage(player.getHealth() - 1.0);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        punishingPlayers.remove(id);
        teleportAfterRespawn.remove(id);
    }

    // File I/O Methods
    private void savePetKills() {
        try (FileWriter writer = new FileWriter(dataFile)) {
            Map<String, Map<String, Integer>> dataToSave = new HashMap<>();
            for (Map.Entry<UUID, Map<String, Integer>> entry : petKillTracker.entrySet()) {
                dataToSave.put(entry.getKey().toString(), entry.getValue());
            }
            gson.toJson(dataToSave, writer);
            getLogger().info("Pet kill data saved!");
        } catch (IOException e) {
            getLogger().severe("Failed to save pet kill data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadPetKills() {
        if (!dataFile.exists()) {
            getLogger().info("No existing pet kill data found. Starting fresh.");
            return;
        }

        try (FileReader reader = new FileReader(dataFile)) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
            Map<String, Map<String, Integer>> loadedData = gson.fromJson(reader, type);

            if (loadedData != null) {
                for (Map.Entry<String, Map<String, Integer>> entry : loadedData.entrySet()) {
                    UUID killerId = UUID.fromString(entry.getKey());
                    petKillTracker.put(killerId, new ConcurrentHashMap<>(entry.getValue()));
                }
                getLogger().info("Pet kill data loaded! Found " + loadedData.size() + " killers.");
            }
        } catch (IOException e) {
            getLogger().severe("Failed to load pet kill data: " + e.getMessage());
            e.printStackTrace();
        }
    }
}