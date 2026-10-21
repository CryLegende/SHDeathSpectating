package org.Cry_Legende.sHDeathSpectating;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SHDeathSpectating extends JavaPlugin implements Listener {

    private int spectatingDuration;
    private int expLossPercentage;
    private boolean hideSpectatorMenu;
    private String deathSubtitle;
    private String spawnCommand;
    private String deathMessage;
    private String commandBlockedMessage;
    private Sound deathSound;
    private Set<String> worldWhitelist;
    private Map<UUID, Location> playerLocations = new HashMap<>();
    private Map<UUID, Entity> playerAttackers = new HashMap<>();
    private Map<UUID, BukkitRunnable> respawnTasks = new HashMap<>();
    private Map<UUID, BukkitRunnable> cameraLockTasks = new HashMap<>();
    private Map<UUID, ArmorStand> playerStands = new HashMap<>();
    // Lu depuis les listeners ProtocolLib (thread netty), d'où la collection concurrente.
    private final Set<UUID> spectatingPlayers = ConcurrentHashMap.newKeySet();
    private boolean spectatorMaskFailed = false;
    // lastAttackerLocations n'est pas utilisé, peut être retiré si souhaité
    private Map<UUID, Location> lastAttackerLocations = new HashMap<>();
    private Map<UUID, Float> lastPlayerYaw = new HashMap<>();
    private Map<UUID, Float> lastPlayerPitch = new HashMap<>();
    private File spectatingFile;
    private FileConfiguration spectatingConfig;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        loadConfiguration();
        Bukkit.getPluginManager().registerEvents(this, this);
        createSpectatingFile();
        registerProtocolListeners();
    }

    private void registerProtocolListeners() {
        // Seul paquet écouté en permanence : il n'est émis que par un spectateur qui clique
        // dans le menu ou sur une entité, donc coût nul en régime normal.
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Client.SPECTATE) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isPlayerTemporary()) return;
                    Player player = event.getPlayer();
                    if (player != null && spectatingPlayers.contains(player.getUniqueId())) {
                        event.setCancelled(true);
                    }
                }
            });
    }

    // Le client n'ouvre le menu spectateur que si sa propre entrée de tab-list annonce le
    // gamemode 3. On lui envoie donc une entrée corrigée juste après le passage en GM3 :
    // un paquet par mort, adressé au seul joueur mort, plutôt qu'un listener permanent sur
    // PLAYER_INFO qui taxerait tout le trafic tab-list du serveur (TAB, vanish, etc.).
    // Le gamemode serveur, la caméra et la visibilité pour les autres joueurs ne changent pas.
    private void sendMaskedGameMode(Player player) {
        if (!hideSpectatorMenu || spectatorMaskFailed) return;
        try {
            // packetevents construit le paquet par sérialisation : pas d'écriture de champ final,
            // contrairement à ProtocolLib 5.2 qui échoue sur ClientboundPlayerInfoUpdatePacket.
            if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                PacketEventsMask.send(player);
                return;
            }
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE));
            packet.getPlayerInfoDataLists().write(0, Collections.singletonList(new PlayerInfoData(
                player.getUniqueId(), 0, true, EnumWrappers.NativeGameMode.ADVENTURE,
                WrappedGameProfile.fromPlayer(player), null)));
            // filters = false : ce paquet maison n'a pas à repasser par les autres plugins ProtocolLib.
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet, false);
        } catch (Exception exception) {
            // En cas d'incompatibilité de version on abandonne uniquement ce masquage :
            // le blocage des téléportations, lui, reste actif.
            spectatorMaskFailed = true;
            getLogger().log(Level.WARNING, "Masquage du gamemode spectateur désactivé (incompatibilité ProtocolLib).", exception);
        }
    }

    private void loadConfiguration() {
        FileConfiguration config = getConfig();
        spectatingDuration = config.getInt("spectatingDuration", 10) * 20;
        expLossPercentage = config.getInt("expLossPercentage", 10);
        deathSubtitle = ChatColor.translateAlternateColorCodes('&', config.getString("deathSubtitle", "&8&l« &c&lVous êtes mort &8&l»"));
        spawnCommand = config.getString("spawnCommand", "/spawn %player%");
        deathMessage = ChatColor.translateAlternateColorCodes('&', config.getString("deathMessage", "&7&l« &c&lSH &8&l» &cVous êtes morts&7, réapparition dans &c{time} secondes"));
        commandBlockedMessage = ChatColor.translateAlternateColorCodes('&', config.getString("commandBlockedMessage", "&cVous ne pouvez pas exécuter de commandes en étant mort."));
        deathSound = Sound.valueOf(config.getString("deathSound", "ENTITY_ENDER_DRAGON_GROWL"));
        hideSpectatorMenu = config.getBoolean("hideSpectatorMenu", true);
        worldWhitelist = new HashSet<>(config.getStringList("worldWhitelist"));
    }

    private void createSpectatingFile() {
        spectatingFile = new File(getDataFolder(), "spectating.yml");
        if (!spectatingFile.exists()) {
            spectatingFile.getParentFile().mkdirs();
            try {
                spectatingFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        spectatingConfig = YamlConfiguration.loadConfiguration(spectatingFile);
    }

    private void saveSpectatingConfig() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                spectatingConfig.save(spectatingFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player)) return;

        final Player player = (Player) event.getEntity();
        if (!worldWhitelist.contains(player.getWorld().getName())) return;

        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent byEntityEvent = (EntityDamageByEntityEvent) event;
            playerAttackers.put(player.getUniqueId(), byEntityEvent.getDamager());
        }

        if (player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            handlePlayerDeath(player, event.getCause());
            scheduleRespawnAndFollowTasks(player);
        }
    }

    private void handlePlayerDeath(Player player, EntityDamageEvent.DamageCause cause) {
        // Marqué avant le changement de gamemode : le paquet de tab-list émis juste en
        // dessous doit déjà être masqué pour ce joueur.
        spectatingPlayers.add(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        sendMaskedGameMode(player); // écrase, côté client seulement, le GM3 que setGameMode vient de diffuser
        player.sendTitle("", deathSubtitle, 10, 70, 20);
        playerLocations.put(player.getUniqueId(), player.getLocation());
        player.playSound(player.getLocation(), deathSound, 1.0f, 1.0f);

        player.setStatistic(Statistic.DEATHS, player.getStatistic(Statistic.DEATHS) + 1);
        Entity attacker = playerAttackers.get(player.getUniqueId());
        if (attacker instanceof Player) {
            Player attackerPlayer = (Player) attacker;
            attackerPlayer.setStatistic(Statistic.PLAYER_KILLS, attackerPlayer.getStatistic(Statistic.PLAYER_KILLS) + 1);
            // La logique d'orientation est maintenant gérée entièrement dans followAttacker
        }

        List<ItemStack> playerInventoryItems = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                playerInventoryItems.add(item.clone());
            }
        }
        for (ItemStack armorItem : player.getInventory().getArmorContents()) {
            if (armorItem != null && armorItem.getType() != Material.AIR) {
                playerInventoryItems.add(armorItem.clone());
            }
        }
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem != null && offHandItem.getType() != Material.AIR) {
            playerInventoryItems.add(offHandItem.clone());
        }

        int totalExp = getTotalExperience(player);
        int expToDropBasedOnConfig = (int) (totalExp * (expLossPercentage / 100.0));
        PlayerDeathEvent deathEvent = createDeathEvent(player, playerInventoryItems);
        if (!player.getWorld().getGameRuleValue(GameRule.KEEP_INVENTORY)) {
            deathEvent.setDroppedExp(expToDropBasedOnConfig);
        }

        Location playerDeathLocation = player.getLocation().clone();
        Bukkit.getPluginManager().callEvent(deathEvent);

        player.sendMessage(deathMessage.replace("{time}", String.valueOf(spectatingDuration / 20)));
        immobilizePlayer(player); // Cette méthode s'occupe de l'ArmorStand et du spectating

        boolean effectiveKeepInventory = player.getWorld().getGameRuleValue(GameRule.KEEP_INVENTORY) || deathEvent.getKeepInventory();

        if (!effectiveKeepInventory) {
            if (!deathEvent.getDrops().isEmpty()) {
                for (ItemStack itemToDrop : deathEvent.getDrops()) {
                    if (itemToDrop != null && itemToDrop.getType() != Material.AIR) {
                        player.getWorld().dropItemNaturally(playerDeathLocation, itemToDrop);
                    }
                }
            }
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(null);

            int finalExpToDrop = deathEvent.getDroppedExp();
            if (finalExpToDrop > 0) {
                int currentTotalExp = getTotalExperience(player);
                setTotalExperience(player, Math.max(0, currentTotalExp - finalExpToDrop));
                ExperienceOrb orb = player.getWorld().spawn(playerDeathLocation, ExperienceOrb.class);
                orb.setExperience(finalExpToDrop);
            }
        } else {
            deathEvent.getDrops().clear();
            deathEvent.setDroppedExp(0);
        }

        spectatingConfig.set(player.getUniqueId().toString(), true);
        saveSpectatingConfig();
    }

    // --- NOUVELLE LOGIQUE D'IMMOBILISATION ET DE SPECTATING ---
    private void immobilizePlayer(Player player) {
        // Crée une nouvelle variable Location qui est 1.5 bloc au-dessus des pieds du joueur.
        Location spawnLocation = player.getLocation().add(0, 1.5, 0);
        ArmorStand armorStand = player.getWorld().spawn(spawnLocation, ArmorStand.class);

        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setMarker(true);
        armorStand.setSmall(true);

        // Fait monter le joueur sur l'ArmorStand
        armorStand.addPassenger(player);

        // --- MODIFICATION CLÉ ---
        // Définit la vue du joueur pour qu'elle corresponde à celle de l'ArmorStand.
        // Cela résout le problème de l'entité transparente et simplifie la rotation.
        player.setSpectatorTarget(armorStand);
        // ----------------------

        playerStands.put(player.getUniqueId(), armorStand);
    }

    // --- NOUVELLE LOGIQUE DE LIBÉRATION ---
    private void freePlayer(Player player) {
        spectatingPlayers.remove(player.getUniqueId());
        ArmorStand armorStand = playerStands.remove(player.getUniqueId());
        if (armorStand != null) {
            // --- MODIFICATION CLÉ ---
            // Il est crucial de libérer la caméra du joueur AVANT de retirer le véhicule.
            if (player.getSpectatorTarget() != null && player.getSpectatorTarget().equals(armorStand)) {
                player.setSpectatorTarget(player); // Le joueur se specte lui-même pour libérer la caméra.
            }
            // ----------------------

            armorStand.eject(); // Éjecter d'abord pour plus de sécurité
            if (armorStand.isValid()) {
                armorStand.remove();
            }
        }
    }

    private void scheduleRespawnAndFollowTasks(Player player) {
        UUID playerId = player.getUniqueId();

        if (respawnTasks.containsKey(playerId)) respawnTasks.remove(playerId).cancel();
        if (cameraLockTasks.containsKey(playerId)) cameraLockTasks.remove(playerId).cancel();

        BukkitRunnable respawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    respawnPlayer(player);
                }
                respawnTasks.remove(playerId);
            }
        };
        respawnTask.runTaskLater(this, spectatingDuration);
        respawnTasks.put(playerId, respawnTask);

        // Tourne pour toutes les morts (et plus seulement celles causées par une entité) :
        // c'est ce verrou qui remet le joueur sur son ArmorStand s'il réussit à s'en détacher.
        BukkitRunnable cameraLockTask = new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !playerStands.containsKey(playerId) || !respawnTasks.containsKey(playerId)) {
                    this.cancel();
                    cameraLockTasks.remove(playerId);
                    return;
                }
                enforceSpectatorLock(player);
                if (playerAttackers.containsKey(playerId)) {
                    followAttacker(player);
                }
                // Rappel une fois par seconde au cas où un autre plugin réémettrait
                // l'entrée de tab-list réelle du joueur pendant la mort.
                if (++ticks % 20 == 0) {
                    sendMaskedGameMode(player);
                }
            }
        };
        cameraLockTask.runTaskTimer(this, 0L, 1L);
        cameraLockTasks.put(playerId, cameraLockTask);
    }

    // Filet de sécurité : si une téléportation spectateur passe malgré tout (autre plugin,
    // version de serveur différente), le joueur est raccroché à sa caméra au tick suivant.
    private void enforceSpectatorLock(Player player) {
        ArmorStand armorStand = playerStands.get(player.getUniqueId());
        if (armorStand == null || !armorStand.isValid()) return;

        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
            sendMaskedGameMode(player); // setGameMode vient de rediffuser le vrai GM3 au client
        }
        if (!armorStand.equals(player.getVehicle())) {
            player.teleport(armorStand.getLocation());
            armorStand.addPassenger(player);
        }
        if (!armorStand.equals(player.getSpectatorTarget())) {
            player.setSpectatorTarget(armorStand);
        }
    }

    // --- LA LOGIQUE DE ROTATION EST MAINTENANT APPLIQUÉE À L'ARMORSTAND ---
    private void followAttacker(Player player) {
        UUID playerId = player.getUniqueId();
        ArmorStand armorStand = playerStands.get(playerId);
        Entity attacker = playerAttackers.get(playerId);

        // On vérifie que toutes les entités nécessaires existent et sont valides
        if (armorStand != null && armorStand.isValid() && attacker != null && attacker.isValid() && player.getWorld().equals(attacker.getWorld())) {

            // Le rattachement de la caméra est assuré en amont par enforceSpectatorLock.

            // On calcule la direction depuis l'ArmorStand (la "caméra") vers l'attaquant
            Location standLocation = armorStand.getLocation();
            Location attackerLocation = attacker.getLocation();
            org.bukkit.util.Vector direction = attackerLocation.toVector().subtract(standLocation.toVector());

            float targetYaw = getLookAtYaw(direction);
            float targetPitch = getLookAtPitch(direction);

            // On récupère la rotation précédente de la "caméra" (l'armor stand)
            float lastYaw = lastPlayerYaw.getOrDefault(playerId, standLocation.getYaw());
            float lastPitch = lastPlayerPitch.getOrDefault(playerId, standLocation.getPitch());

            // Votre logique d'interpolation reste inchangée, elle est excellente pour le lissage
            float newYaw = interpolateAngle(lastYaw, targetYaw, 0.1f); // Vous pouvez ajuster le facteur
            float newPitch = interpolateAngle(lastPitch, targetPitch, 0.1f);

            // On sauvegarde la nouvelle rotation
            lastPlayerYaw.put(playerId, newYaw);
            lastPlayerPitch.put(playerId, newPitch);

            // --- MODIFICATION CLÉ ---
            // On applique la rotation à l'ARMOR STAND, pas au joueur.
            // La méthode teleport est la plus fiable pour mettre à jour la rotation d'une entité instantanément.
            standLocation.setYaw(newYaw);
            standLocation.setPitch(newPitch);
            armorStand.teleport(standLocation);
            // ----------------------
        }
    }

    private void respawnPlayer(Player player) {
        BukkitRunnable cameraLockTask = cameraLockTasks.remove(player.getUniqueId());
        if (cameraLockTask != null) {
            try {
                cameraLockTask.cancel();
            } catch (IllegalStateException ignored) {
            }
        }

        freePlayer(player); // Libère le joueur de l'ArmorStand ET de la vue spectateur
        player.setGameMode(GameMode.SURVIVAL);
        player.setWalkSpeed(0.2f);

        if (this.spawnCommand != null && !this.spawnCommand.isEmpty()) {
            String commandToExecute = this.spawnCommand.replace("%player%", player.getName());
            if (commandToExecute.startsWith("/")) {
                commandToExecute = commandToExecute.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandToExecute);
        } else {
            getLogger().warning("spawnCommand n'est pas configuré. Téléportation au spawn du monde par défaut.");
            World spawnWorld = player.getWorld();
            if (spawnWorld.getSpawnLocation() == null && !Bukkit.getWorlds().isEmpty()) {
                spawnWorld = Bukkit.getWorlds().get(0);
            }
            player.teleport(spawnWorld.getSpawnLocation());
        }

        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(5.0F);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setFireTicks(0);

        playerAttackers.remove(player.getUniqueId());
        playerLocations.remove(player.getUniqueId());
        lastPlayerYaw.remove(player.getUniqueId());
        lastPlayerPitch.remove(player.getUniqueId());

        spectatingConfig.set(player.getUniqueId().toString(), null);
        saveSpectatingConfig();
    }

    // Les méthodes utilitaires restent inchangées
    public void setTotalExperience(Player player, int amount) {
        player.setExp(0.0F);
        player.setLevel(0);
        player.setTotalExperience(0);
        if (amount > 0) {
            player.giveExp(amount);
        }
    }

    // Le constructeur (Player, List, int, String) n'existe plus en 1.21 : on passe par celui avec
    // DamageSource (API 1.20.4+) quand il est disponible, sinon on garde l'ancien (Opale 1.20.2).
    private static PlayerDeathEvent createDeathEvent(Player player, List<ItemStack> drops) {
        try {
            Class<?> damageSource = Class.forName("org.bukkit.damage.DamageSource");
            Class<?> damageType = Class.forName("org.bukkit.damage.DamageType");
            Object builder = damageSource.getMethod("builder", damageType).invoke(null, damageType.getField("GENERIC").get(null));
            Object source = Class.forName("org.bukkit.damage.DamageSource$Builder").getMethod("build").invoke(builder);
            return PlayerDeathEvent.class
                .getConstructor(Player.class, damageSource, List.class, int.class, String.class)
                .newInstance(player, source, drops, 0, "");
        } catch (ReflectiveOperationException ignored) {
            return new PlayerDeathEvent(player, drops, 0, "");
        }
    }

    private int getTotalExperience(Player player) {
        int level = player.getLevel();
        int exp = Math.round(getExpAtLevel(level) * player.getExp());
        while (level > 0) {
            level--;
            exp += getExpAtLevel(level);
        }
        return exp;
    }

    private int getExpAtLevel(int level) {
        if (level <= 16) {
            return 2 * level + 7;
        } else if (level <= 31) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }

    private float interpolateAngle(float start, float end, float factor) {
        float difference = end - start;
        while (difference < -180) difference += 360;
        while (difference > 180) difference -= 360;
        return start + difference * factor;
    }

    private float getLookAtYaw(org.bukkit.util.Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private float getLookAtPitch(org.bukkit.util.Vector direction) {
        double dxz = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
        return (float) Math.toDegrees(Math.atan2(-direction.getY(), dxz));
    }

    // Les events de gestion de joueur restent inchangés et fonctionnels
    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player && playerStands.containsKey(event.getExited().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (playerStands.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (playerLocations.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(commandBlockedMessage);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (playerLocations.containsKey(playerId)) {
            if (respawnTasks.containsKey(playerId)) respawnTasks.remove(playerId).cancel();
            if (cameraLockTasks.containsKey(playerId)) cameraLockTasks.remove(playerId).cancel();

            playerLocations.remove(playerId);
            playerAttackers.remove(playerId);
            lastPlayerYaw.remove(playerId);
            lastPlayerPitch.remove(playerId);
            freePlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (spectatingConfig.getBoolean(player.getUniqueId().toString())) {
            respawnPlayer(player);
        }
    }

    // Ces events restent des sécurités utiles
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (playerStands.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!playerStands.containsKey(player.getUniqueId())) return;

        // Téléportation demandée par le joueur depuis le menu spectateur : toujours refusée.
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
            return;
        }

        ArmorStand stand = playerStands.get(player.getUniqueId());
        // S'assurer que le joueur est bien dans le véhicule qu'on contrôle
        if (player.getVehicle() != null && player.getVehicle().equals(stand)) {
            event.setCancelled(true);
        }
    }

    // Classe séparée : chargée seulement si packetevents est présent (softdepend).
    private static final class PacketEventsMask {
        static void send(Player player) {
            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    new com.github.retrooper.packetevents.protocol.player.UserProfile(player.getUniqueId(), player.getName()),
                    true, 0, com.github.retrooper.packetevents.protocol.player.GameMode.ADVENTURE, null, null);
            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate packet =
                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate(
                    com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE, info);
            // Silencieux : ce paquet maison ne repasse pas par les listeners des autres plugins.
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        }
    }
}
