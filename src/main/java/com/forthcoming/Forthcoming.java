package com.forthcoming;



import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Forthcoming extends JavaPlugin implements Listener {

    private final Set<UUID> teleportAfterRespawn = new HashSet<>();
    private final Set<UUID> punishingPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Forthcoming enabled!");
    }

    @Override
    public void onDisable() {
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
        if (damage < petHealth) return; // pet survives, no punishment

        UUID id = attacker.getUniqueId();
        if (punishingPlayers.contains(id)) return; // already being punished
        punishingPlayers.add(id);

        // Wait 2 seconds before first title
        Bukkit.getScheduler().runTaskLater(this, () -> {

            // TITLE 1: "What have you done" with short fade-in and scary cave sound
            attacker.sendTitle("§cWhat have you done", "", 5, 100, 5);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.AMBIENT_CAVE, 2f, 0.8f);

            // TITLE 2 after 5 seconds with short fade-in and spooky bat sound
            Bukkit.getScheduler().runTaskLater(this, () -> {
                attacker.sendTitle("§cYou have awakened the Forthcoming, the END", "", 5, 100, 5);
                attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_BAT_AMBIENT, 2f, 0.8f);

                // Fade out after 10 seconds
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    attacker.sendTitle("", "", 10, 1, 10);
                }, 200L);

            }, 100L); // 5 seconds after first title

            // Start 2-minute effects
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int duration = 20 * 120; // 2 minutes
                applyEffects(attacker, duration);

                // Gradual cave noises every second
                for (int i = 0; i < 120; i++) {
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
                    if (!attacker.isOnline()) return;
                    attacker.sendTitle("§cDon't Do It Again Please :)", "", 10, 100, 10);
                    attacker.sendMessage("§cDon't Do It Again Please :)");
                    attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f, 1f);
                    teleportAfterRespawn.add(attacker.getUniqueId());
                    attacker.setHealth(0.0);
                    punishingPlayers.remove(id);
                }, duration);

            }, 200L); // start effects after titles fade
        }, 40L); // 2-second initial delay
    }

    private void applyEffects(Player player, int duration) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, duration, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 2));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, duration, 2));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, duration, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, duration, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, duration, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, duration, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.UNLUCK, duration, 0));
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (teleportAfterRespawn.contains(player.getUniqueId())) {
            teleportAfterRespawn.remove(player.getUniqueId());
            Location loc = player.getLocation();
            Location farAway = new Location(loc.getWorld(), loc.getX() + 2500, loc.getY(), loc.getZ());
            player.teleport(farAway);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (punishingPlayers.contains(player.getUniqueId())) {
            // Prevent player from dying early
            if (player.getHealth() - event.getFinalDamage() <= 1.0) {
                event.setDamage(player.getHealth() - 1.0);
            }
        }
    }
}
