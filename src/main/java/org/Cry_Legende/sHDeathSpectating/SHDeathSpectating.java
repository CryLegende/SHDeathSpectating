package org.Cry_Legende.sHDeathSpectating;

import org.Cry_Legende.sHDeathSpectating.network.DeathDatabase;
import org.Cry_Legende.sHDeathSpectating.network.NetworkDeaths;
import org.Cry_Legende.sHDeathSpectating.network.NetworkLink;
import org.Cry_Legende.sHDeathSpectating.network.ShNetworkLink;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
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

    /** Où et par qui un joueur est mort, gardé jusqu'à sa réapparition sur place. */
    private record DeathInfo(Location location, Entity attacker) {
    }

    /** Vrai uniquement sur le fil principal du serveur (jamais sur le fil d'un monde ou d'une région). */
    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }


    // Relus par /shdeathspectating reload, lus depuis le fil principal et celui des mondes
    private volatile int spectatingDuration;
    private volatile int expLossPercentage;
    private volatile boolean hideSpectatorMenu;
    private volatile String deathSubtitle;
    private volatile String spawnCommand;
    private volatile String deathMessage;
    private volatile String commandBlockedMessage;
    private volatile Sound deathSound;
    private volatile Set<String> worldWhitelist;
    // Lues et écrites aussi depuis le thread d'un monde (mort pendant son tick), d'où les collections concurrentes.
    private final Map<UUID, Location> playerLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> playerAttackers = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> respawnTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> cameraLockTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ArmorStand> playerStands = new ConcurrentHashMap<>();
    private volatile boolean spectatorMaskFailed = false;
    // Vrai le temps d'une téléportation voulue par le plugin lui-même : seules celles-là passent pour un joueur mort
    private volatile boolean selfTeleport = false;
    private final Map<UUID, Float> lastPlayerYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPlayerPitch = new ConcurrentHashMap<>();
    // Morts réelles, entre l'événement de mort et la fin de la réapparition immédiate
    private final Map<UUID, DeathInfo> awaitingRespawn = new ConcurrentHashMap<>();
    // Mondes (UID) où le plugin a activé immediate_respawn alors qu'elle était fausse : rendue à l'arrêt
    private final Set<UUID> immediateRespawnEnabled = ConcurrentHashMap.newKeySet();
    private File spectatingFile;
    private FileConfiguration spectatingConfig;
    // Écritures de spectating.yml : numérotées pour qu'une ancienne n'écrase pas une plus récente
    private final Object saveLock = new Object();
    private long saveVersion;
    private long savedVersion;
    // Non nul seulement avec SHNetwork : l'état « mort » passe par la base partagée au lieu de spectating.yml
    private NetworkDeaths network;
    private volatile YamlConfiguration messages;
    private String networkSettingsAtStart = "";

    @Override
    public void onEnable() {
        serverThread = Thread.currentThread();
        this.saveDefaultConfig();
        networkSettingsAtStart = networkSettings();
        loadConfiguration();
        loadMessages();
        Bukkit.getPluginManager().registerEvents(this, this);
        createSpectatingFile();
        syncImmediateRespawn();
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
        restoreImmediateRespawn();
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

    /** Contenu de la section 'reseau' du config.yml, pour savoir si elle a changé depuis le démarrage. */
    private String networkSettings() {
        ConfigurationSection section = getConfig().getConfigurationSection("reseau");
        return section == null ? "" : section.getValues(true).toString();
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
        reloadConfig();
        loadConfiguration();
        syncImmediateRespawn();
        messages = newMessages;
        sender.sendMessage(message("reload-ok"));
        if (!networkSettings().equals(networkSettingsAtStart)) {
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
        boolean dead = playerStands.containsKey(id) || playerLocations.containsKey(id)
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

    // Le client n'ouvre le menu spectateur que si sa propre entrée de tab-list annonce le
    // gamemode 3. On lui envoie donc une entrée corrigée juste après le passage en GM3 :
    // un paquet par mort, adressé au seul joueur mort, plutôt qu'un listener permanent sur
    // PLAYER_INFO qui taxerait tout le trafic tab-list du serveur (TAB, vanish, etc.).
    // Le gamemode serveur, la caméra et la visibilité pour les autres joueurs ne changent pas.
    private void sendMaskedGameMode(Player player) {
        if (!hideSpectatorMenu || spectatorMaskFailed) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            spectatorMaskFailed = true;
            getLogger().warning("Masquage du menu spectateur indisponible : packetevents n'est pas installé.");
            return;
        }
        try {
            PacketEventsMask.send(player);
        } catch (Exception | LinkageError exception) {
            // En cas d'incompatibilité de version on abandonne uniquement ce masquage :
            // le blocage des téléportations, lui, reste actif.
            spectatorMaskFailed = true;
            getLogger().log(Level.WARNING, "Masquage du gamemode spectateur désactivé (incompatibilité packetevents).", exception);
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

    /**
     * Vraie mort d'un joueur d'un monde de worldWhitelist : le jeu fait tout (totem, Disparition, keepInventory, drops,
     * statistiques, responsable de la mort). Ici : pas de message de mort, perte d'exp selon la configuration, et on
     * retient où et par qui, pour réapparaître sur place en spectateur.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!worldWhitelist.contains(player.getWorld().getName())) return;

        event.deathMessage(null);
        if (!event.getKeepLevel()) {
            int total = player.calculateTotalExperiencePoints();
            int lost = (int) (total * (expLossPercentage / 100.0));
            int kept = total - lost;
            int level = 0;
            int rest = kept;
            while (rest >= getExpAtLevel(level)) {
                rest -= getExpAtLevel(level);
                level++;
            }
            event.setDroppedExp(lost);
            event.setNewTotalExp(kept);
            event.setNewLevel(level);
            event.setNewExp(rest);
        }
        awaitingRespawn.put(player.getUniqueId(), new DeathInfo(player.getLocation().clone(), event.getDamageSource().getCausingEntity()));

        // Le client réapparaît seul grâce à immediate_respawn ; ce filet couvre un client qui l'ignorerait (Bedrock)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        }, 5L);
    }

    /** La réapparition se fait là où le joueur est mort : c'est de là que la caméra de spectateur regarde. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        DeathInfo info = awaitingRespawn.get(event.getPlayer().getUniqueId());
        if (info != null) {
            event.setRespawnLocation(info.location());
        }
    }

    @EventHandler
    public void onPlayerPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        DeathInfo info = awaitingRespawn.remove(player.getUniqueId());
        if (info == null) return;
        // Passage en spectateur dans le même tick que la réapparition quand on est sur le fil principal
        runOnServerThread(() -> {
            if (player.isOnline()) {
                startSpectating(player, info);
            }
        });
    }

    private void runOnServerThread(Runnable task) {
        if (onServerThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    private void startSpectating(Player player, DeathInfo info) {
        UUID playerId = player.getUniqueId();
        // Une mort pendant le spectateur (/kill) ne doit pas laisser l'ancienne caméra dans le monde
        ArmorStand previous = playerStands.remove(playerId);
        if (previous != null) {
            previous.eject();
            previous.remove();
        }

        // Un autre plugin a pu choisir un autre point de réapparition : la caméra se pose là où le joueur est mort
        Location here = player.getLocation();
        Location death = info.location();
        if (!death.getWorld().equals(here.getWorld()) || here.distanceSquared(death) > 1.0) {
            teleportSelf(player, death);
        }
        player.setGameMode(GameMode.SPECTATOR);
        sendMaskedGameMode(player); // écrase, côté client seulement, le GM3 que setGameMode vient de diffuser
        player.sendTitle("", deathSubtitle, 10, 70, 20);
        playerLocations.put(playerId, info.location());
        player.playSound(player.getLocation(), deathSound, 1.0f, 1.0f);
        if (info.attacker() != null) {
            playerAttackers.put(playerId, info.attacker());
        }

        player.sendMessage(deathMessage.replace("{time}", String.valueOf(spectatingDuration / 20)));
        immobilizePlayer(player); // Cette méthode s'occupe de l'ArmorStand et du spectating
        markDead(playerId);
        scheduleRespawnAndFollowTasks(player);
    }

    /**
     * Active immediate_respawn dans les mondes de worldWhitelist, pour que le client réapparaisse sans écran de mort,
     * et la rend à son état d'origine dans les autres mondes ou à l'arrêt du plugin.
     */
    private void syncImmediateRespawn() {
        for (World world : Bukkit.getWorlds()) {
            syncImmediateRespawn(world);
        }
    }

    private void syncImmediateRespawn(World world) {
        UUID id = world.getUID();
        if (worldWhitelist.contains(world.getName())) {
            if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.IMMEDIATE_RESPAWN))) {
                immediateRespawnEnabled.add(id);
                world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
            }
        } else if (immediateRespawnEnabled.remove(id)) {
            world.setGameRule(GameRules.IMMEDIATE_RESPAWN, false);
        }
    }

    private void restoreImmediateRespawn() {
        for (UUID id : immediateRespawnEnabled) {
            World world = Bukkit.getWorld(id);
            if (world != null) {
                world.setGameRule(GameRules.IMMEDIATE_RESPAWN, false);
            }
        }
        immediateRespawnEnabled.clear();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        syncImmediateRespawn(event.getWorld());
    }

    /** Un spectateur mort ne peut ni changer de caméra ni la quitter. */
    @EventHandler
    public void onStartSpectating(PlayerStartSpectatingEntityEvent event) {
        ArmorStand stand = playerStands.get(event.getPlayer().getUniqueId());
        if (stand != null && !stand.equals(event.getNewSpectatorTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        ArmorStand stand = playerStands.get(event.getPlayer().getUniqueId());
        if (stand != null && stand.equals(event.getSpectatorTarget())) {
            event.setCancelled(true);
        }
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

    private void teleportSelf(Player player, Location destination) {
        selfTeleport = true;
        try {
            player.teleport(destination);
        } finally {
            selfTeleport = false;
        }
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
            teleportSelf(player, armorStand.getLocation());
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

        playerAttackers.remove(player.getUniqueId());
        playerLocations.remove(player.getUniqueId());
        lastPlayerYaw.remove(player.getUniqueId());
        lastPlayerPitch.remove(player.getUniqueId());

        clearDead(player.getUniqueId());
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
        awaitingRespawn.remove(playerId);
        playerAttackers.remove(playerId);
        if (playerLocations.containsKey(playerId)) {
            if (respawnTasks.containsKey(playerId)) respawnTasks.remove(playerId).cancel();
            if (cameraLockTasks.containsKey(playerId)) cameraLockTasks.remove(playerId).cancel();

            playerLocations.remove(playerId);
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

    /**
     * Un joueur mort reste sur sa caméra : toute téléportation qui ne vient pas du plugin est refusée (menu spectateur,
     * point de réapparition de SuperiorSkyblock, /spawn d'un autre plugin...). Un spectateur sur caméra n'a pas
     * l'ArmorStand pour véhicule côté serveur, on ne peut donc pas se fier à getVehicle().
     */
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (selfTeleport || !playerStands.containsKey(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
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
