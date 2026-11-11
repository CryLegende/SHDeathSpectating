package org.Cry_Legende.sHDeathSpectating;

import org.Cry_Legende.sHDeathSpectating.network.DeathDatabase;
import org.Cry_Legende.sHDeathSpectating.network.NetworkDeaths;
import org.Cry_Legende.sHDeathSpectating.network.NetworkLink;
import org.Cry_Legende.sHDeathSpectating.network.ShNetworkLink;
import org.bukkit.damage.DamageType;
import org.bukkit.damage.DamageSource;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SHDeathSpectating extends JavaPlugin implements Listener {

    /**
     * Fil principal du serveur, relevé à l'activation. Avec les ticks de mondes en parallèle (DivineMC), les fils des
     * mondes sont aussi des fils de tick : Bukkit.isPrimaryThread() y répond vrai, il ne sert donc pas à les distinguer.
     */
    private static volatile Thread serverThread;

    /** Vrai uniquement sur le fil principal du serveur (jamais sur le fil d'un monde ou d'une région). */
    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }


    // Relus par /shdeathspectating reload, lus depuis le fil principal, celui des mondes et celui de ProtocolLib
    private volatile int spectatingDuration;
    private volatile int expLossPercentage;
    private volatile boolean hideSpectatorMenu;
    private volatile String deathSubtitle;
    private volatile String spawnCommand;
    private volatile String deathMessage;
    private volatile String commandBlockedMessage;
    private volatile Sound deathSound;
    private volatile Set<String> worldWhitelist;
    // Lues et écrites aussi depuis le thread d'un monde (dégâts reçus pendant son tick), d'où les collections concurrentes.
    private final Map<UUID, Location> playerLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> playerAttackers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> respawnTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> cameraLockTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ArmorStand> playerStands = new ConcurrentHashMap<>();
    // Lu depuis les listeners ProtocolLib (thread netty), d'où la collection concurrente.
    private final Set<UUID> spectatingPlayers = ConcurrentHashMap.newKeySet();
    private boolean spectatorMaskFailed = false;
    // lastAttackerLocations n'est pas utilisé, peut être retiré si souhaité
    private final Map<UUID, Location> lastAttackerLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPlayerYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPlayerPitch = new ConcurrentHashMap<>();
    // Morts en attente du thread principal : un second coup mortel dans le même tick ne les relance pas.
    private final Set<UUID> pendingDeaths = ConcurrentHashMap.newKeySet();
    private File spectatingFile;
    private FileConfiguration spectatingConfig;
    // Écritures de spectating.yml : numérotées pour qu'une ancienne n'écrase pas une plus récente
    private final Object saveLock = new Object();
    private long saveVersion;
    private long savedVersion;
    // Non nul seulement avec SHNetwork : l'état « mort » passe par la base partagée au lieu de spectating.yml
    private NetworkDeaths network;
    private volatile YamlConfiguration messages;

    @Override
    public void onEnable() {
        serverThread = Thread.currentThread();
        this.saveDefaultConfig();
        loadConfiguration();
        loadMessages();
        Bukkit.getPluginManager().registerEvents(this, this);
        createSpectatingFile();
        registerProtocolListeners();
        enableNetwork();
    }

    @Override
    public void onDisable() {
        // Les ArmorStand de camera ne doivent pas rester dans le monde apres un arret ou un rechargement
        for (ArmorStand stand : playerStands.values()) {
            stand.eject();
            stand.remove();
        }
        playerStands.clear();
        if (network != null) {
            network.stop();
        } else if (spectatingConfig != null) {
            writeSpectating(spectatingConfig.saveToString(), ++saveVersion);
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        String url = getConfig().getString("reseau.base-de-donnees.url", "");
        if (url == null || url.isBlank()) {
            getLogger().warning("SHNetwork présent mais 'reseau.base-de-donnees' absente de config.yml : état de mort local à ce serveur.");
            return;
        }
        DeathDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new DeathDatabase(url,
                getConfig().getString("reseau.base-de-donnees.utilisateur"),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTable();
            Duration wait = Duration.ofSeconds(Math.max(1, getConfig().getInt("reseau.attente-max-secondes", 10)));
            long syncWait = Math.max(1, getConfig().getInt("reseau.attente-synchro-secondes", 15)) * 20L;
            NetworkDeaths net = new NetworkDeaths(this, db, link, wait, syncWait, this::onNetworkArrival);
            net.start();
            network = net;
            getLogger().info("SHNetwork détecté : l'état de mort est partagé entre les serveurs (base partagée).");
        } catch (Exception | LinkageError e) {
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, état de mort local à ce serveur : " + e);
        }
    }

    /** Au démarrage : messages.yml, avec les valeurs du jar pour toute clé absente du fichier du serveur. */
    private void loadMessages() {
        try {
            messages = readMessages();
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "messages.yml illisible, textes du jar utilisés : " + describe(e));
            YamlConfiguration fallback = new YamlConfiguration();
            YamlConfiguration defaults = jarMessages();
            if (defaults != null) {
                fallback.setDefaults(defaults);
            }
            messages = fallback;
        }
    }

    /** Lit messages.yml en mode strict : une erreur de syntaxe est levée au lieu d'être avalée. */
    private YamlConfiguration readMessages() throws IOException, InvalidConfigurationException {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.load(file);
        YamlConfiguration defaults = jarMessages();
        if (defaults != null) {
            loaded.setDefaults(defaults);
        }
        return loaded;
    }

    private YamlConfiguration jarMessages() {
        try (InputStreamReader defaults = new InputStreamReader(Objects.requireNonNull(getResource("messages.yml")), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(defaults);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "messages.yml de référence illisible : " + e.getMessage());
            return null;
        }
    }

    /** Cause d'une erreur de lecture sur une seule ligne. */
    private static String describe(Exception e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        text = text.replaceAll("\\s*\\R\\s*", " ").trim();
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

    private String message(String key, String... pairs) {
        String text = ChatColor.translateAlternateColorCodes('&', messages.getString(key, key));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return text;
    }

    /** Mort enregistrée : en base avec SHNetwork, sinon dans spectating.yml. */
    private void markDead(UUID playerId) {
        if (network != null) {
            network.markDead(playerId);
            return;
        }
        spectatingConfig.set(playerId.toString(), true);
        saveSpectatingConfig();
    }

    /** Réapparition enregistrée : en base avec SHNetwork, sinon dans spectating.yml. */
    private void clearDead(UUID playerId) {
        if (network != null) {
            network.clear(playerId);
            return;
        }
        spectatingConfig.set(playerId.toString(), null);
        saveSpectatingConfig();
    }

    /**
     * Joueur qui se connecte en mode réseau, une fois la base lue : réapparition s'il est parti mort d'un serveur.
     * Base injoignable (dead nul) : on retombe sur spectating.yml comme avant le mode réseau.
     */
    private void onNetworkArrival(Player player, Boolean dead) {
        boolean wasDead = dead != null ? dead : spectatingConfig.getBoolean(player.getUniqueId().toString());
        if (wasDead) {
            respawnPlayer(player);
        }
    }

    private static final String PERMISSION_IMPORT = "shdeathspectating.admin.import";
    private static final String PERMISSION_RELOAD = "shdeathspectating.admin.reload";
    private static final String PERMISSION_RESPAWN = "shdeathspectating.admin.respawn";

    /** Sous-commandes que l'expéditeur a le droit d'utiliser. */
    private List<String> allowedSubcommands(CommandSender sender) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission(PERMISSION_IMPORT)) subs.add("import");
        if (sender.hasPermission(PERMISSION_RELOAD)) subs.add("reload");
        if (sender.hasPermission(PERMISSION_RESPAWN)) subs.add("respawn");
        return subs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> subs = allowedSubcommands(sender);
        if (subs.isEmpty()) {
            sender.sendMessage(message("pas-de-permission"));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("import") && args.length == 1 && subs.contains("import")) {
            importDeaths(sender);
        } else if (sub.equals("reload") && args.length == 1 && subs.contains("reload")) {
            reloadCommand(sender);
        } else if (sub.equals("respawn") && args.length == 2 && subs.contains("respawn")) {
            respawnCommand(sender, args[1]);
        } else {
            sender.sendMessage(message("usage-liste", "{sous-commandes}", String.join("|", subs)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            for (String sub : allowedSubcommands(sender)) {
                if (sub.startsWith(start)) result.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("respawn") && sender.hasPermission(PERMISSION_RESPAWN)) {
            String start = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(start)) result.add(online.getName());
            }
        }
        return result;
    }

    private void importDeaths(CommandSender sender) {
        if (network == null) {
            sender.sendMessage(message("import-inactif"));
            return;
        }
        // spectating.yml est lu ici, sur le fil principal ; l'écriture en base se fait ailleurs
        List<UUID> dead = new ArrayList<>();
        for (String key : spectatingConfig.getKeys(false)) {
            if (!spectatingConfig.getBoolean(key)) continue;
            try {
                dead.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // clé qui n'est pas un joueur
            }
        }
        network.importDead(dead).thenAccept(created -> Bukkit.getScheduler().runTask(this, () -> {
            if (created < 0) {
                sender.sendMessage(message("import-erreur"));
            } else {
                sender.sendMessage(message("import-termine")
                    .replace("{lus}", String.valueOf(dead.size())).replace("{crees}", String.valueOf(created)));
            }
        }));
    }

    /**
     * Relit config.yml et messages.yml. Un fichier invalide laisse l'ancienne configuration en place. spectating.yml
     * (données) et la section 'reseau' (connexion à la base) ne sont pas relus.
     */
    private void reloadCommand(CommandSender sender) {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) saveDefaultConfig();
        YamlConfiguration newConfig = new YamlConfiguration();
        try {
            newConfig.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            sender.sendMessage(message("reload-erreur", "{fichier}", "config.yml", "{erreur}", describe(e)));
            return;
        }
        YamlConfiguration newMessages;
        try {
            newMessages = readMessages();
        } catch (IOException | InvalidConfigurationException e) {
            sender.sendMessage(message("reload-erreur", "{fichier}", "messages.yml", "{erreur}", describe(e)));
            return;
        }
        String networkBefore = String.valueOf(getConfig().get("reseau"));
        reloadConfig();
        loadConfiguration();
        messages = newMessages;
        sender.sendMessage(message("reload-ok"));
        if (!networkBefore.equals(String.valueOf(getConfig().get("reseau")))) {
            sender.sendMessage(message("reload-reseau"));
        }
    }

    /**
     * Force la réapparition d'un joueur resté en état de mort (spectateur sans caméra, mort d'un autre serveur, synchronisation
     * perdue). Refusé pour un joueur qui n'est pas mort.
     */
    private void respawnCommand(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(message("joueur-introuvable", "{joueur}", name));
            return;
        }
        UUID id = target.getUniqueId();
        boolean dead = spectatingPlayers.contains(id) || playerStands.containsKey(id) || playerLocations.containsKey(id)
            || respawnTasks.containsKey(id) || (network == null && spectatingConfig.getBoolean(id.toString()));
        if (dead) {
            forceRespawn(sender, target);
        } else if (network != null) {
            // l'état de mort est en base : lu hors du fil principal
            network.isDead(id).thenAccept(inBase -> Bukkit.getScheduler().runTask(this, () -> {
                if (Boolean.TRUE.equals(inBase)) {
                    forceRespawn(sender, target);
                } else {
                    sender.sendMessage(message("respawn-pas-mort", "{joueur}", target.getName()));
                }
            }));
        } else {
            sender.sendMessage(message("respawn-pas-mort", "{joueur}", target.getName()));
        }
    }

    private void forceRespawn(CommandSender sender, Player target) {
        if (!target.isOnline()) return;
        BukkitRunnable pending = respawnTasks.remove(target.getUniqueId());
        if (pending != null) pending.cancel();
        respawnPlayer(target);
        sender.sendMessage(message("respawn-ok", "{joueur}", target.getName()));
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
        deathSound = sound(config.getString("deathSound", "ENTITY_ENDER_DRAGON_GROWL"));
        if (deathSound == null) {
            getLogger().warning("deathSound invalide : " + config.getString("deathSound") + ", ENTITY_ENDER_DRAGON_GROWL utilisé.");
            deathSound = Sound.ENTITY_ENDER_DRAGON_GROWL;
        }
        hideSpectatorMenu = config.getBoolean("hideSpectatorMenu", true);
        worldWhitelist = new HashSet<>(config.getStringList("worldWhitelist"));
    }

    /**
     * Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup).
     */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
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
        // Sérialisé ici, sur le thread principal : la configuration est modifiée par les événements,
        // la parcourir depuis le thread asynchrone provoquait des accès concurrents
        final String data = spectatingConfig.saveToString();
        final long version = ++saveVersion;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeSpectating(data, version));
    }

    private void writeSpectating(String data, long version) {
        synchronized (saveLock) {
            if (version < savedVersion) return;
            savedVersion = version;
            try {
                Files.writeString(spectatingFile.toPath(), data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Impossible d'écrire spectating.yml", e);
            }
        }
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
            if (onServerThread()) {
                processDeath(player, event.getCause());
                return;
            }
            // Dégâts reçus pendant le tick d'un monde : la mort (gamemode, caméra, inventaire, événement de mort) passe par le thread principal
            UUID playerId = player.getUniqueId();
            if (!pendingDeaths.add(playerId)) return;
            final EntityDamageEvent.DamageCause cause = event.getCause();
            Bukkit.getScheduler().runTask(this, () -> {
                pendingDeaths.remove(playerId);
                if (player.isOnline()) {
                    processDeath(player, cause);
                }
            });
        }
    }

    private void processDeath(Player player, EntityDamageEvent.DamageCause cause) {
        handlePlayerDeath(player, cause);
        scheduleRespawnAndFollowTasks(player);
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
        // getContents() renvoie aussi l'armure et la main secondaire, ajoutées plus bas : on ne prend que le stockage
        for (ItemStack item : player.getInventory().getStorageContents()) {
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
        if (!player.getWorld().getGameRuleValue(GameRules.KEEP_INVENTORY)) {
            deathEvent.setDroppedExp(expToDropBasedOnConfig);
        }

        Location playerDeathLocation = player.getLocation().clone();
        Bukkit.getPluginManager().callEvent(deathEvent);

        player.sendMessage(deathMessage.replace("{time}", String.valueOf(spectatingDuration / 20)));
        immobilizePlayer(player); // Cette méthode s'occupe de l'ArmorStand et du spectating

        boolean effectiveKeepInventory = player.getWorld().getGameRuleValue(GameRules.KEEP_INVENTORY) || deathEvent.getKeepInventory();

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

        markDead(player.getUniqueId());
    }

    // --- NOUVELLE LOGIQUE D'IMMOBILISATION ET DE SPECTATING ---
    private void immobilizePlayer(Player player) {
        // Crée une nouvelle variable Location qui est 1.5 bloc au-dessus des pieds du joueur.
        Location spawnLocation = player.getLocation().add(0, 1.5, 0);
        ArmorStand armorStand = player.getWorld().spawn(spawnLocation, ArmorStand.class);
        // Jamais écrit sur disque : un arrêt du serveur ne laisse pas de caméra orpheline dans le monde
        armorStand.setPersistent(false);

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

        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
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

        clearDead(player.getUniqueId());
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

    // Événement de mort « de notification » : message vide, le constructeur à message Component remplace celui à String.
    private static PlayerDeathEvent createDeathEvent(Player player, List<ItemStack> drops) {
        DamageSource source = DamageSource.builder(DamageType.GENERIC).build();
        return new PlayerDeathEvent(player, source, drops, 0, Component.empty(), true);
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
        if (network != null) network.left(playerId);
        // Dernier attaquant retenu à chaque coup reçu : sans ce retrait, l'entité restait référencée après le départ
        playerAttackers.remove(playerId);
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
        if (network != null) {
            // l'état de mort vient de la base, relue une fois l'autre serveur déchargé du joueur
            network.arrive(player);
            return;
        }
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
