package org.Cry_Legende.sHDeathSpectating.network;

import org.Cry_Legende.sHDeathSpectating.SHDeathSpectating;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Mode réseau : l'état « mort, en attente de réapparition » est en base partagée au lieu de spectating.yml.
 *
 * <ul>
 *   <li>à la mort et à la réapparition : écriture en base, sur un fil à part (jamais le fil principal) ;</li>
 *   <li>au départ du joueur : la barrière de SHNetwork attend que ces écritures soient finies ;</li>
 *   <li>à l'arrivée : une fois le départ confirmé, la base dit si le joueur est mort ; si oui, il réapparaît
 *       exactement comme après une déconnexion sur un serveur seul (gamemode survie, commande de spawn, vie pleine).</li>
 * </ul>
 *
 * <p>Avec HuskSync, le gamemode du joueur (spectateur, s'il est parti mort) est réappliqué à son arrivée. La
 * réapparition attend donc la fin de la synchronisation, sinon HuskSync remettrait le joueur en spectateur sans
 * caméra après coup.</p>
 */
public final class NetworkDeaths implements Listener {

    private static final int WRITE_ATTEMPTS = 3;

    /** Arrivée d'un joueur sur ce serveur : la réapparition se joue quand la base a répondu et la synchronisation est finie. */
    private static final class Arrival {
        volatile boolean databaseDone;
        volatile boolean syncDone;
        /** Faux : pas mort ; vrai : mort ; nul : base injoignable. */
        volatile Boolean dead;
        boolean played;
    }

    private final SHDeathSpectating plugin;
    private final DeathDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final long syncWaitTicks;
    /** Appelée sur le fil principal pour chaque joueur arrivé ; reçoit le joueur et son état en base (nul si la base est injoignable). */
    private final BiConsumer<Player, Boolean> respawn;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHDeathSpectating-reseau");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();
    /** Vrai quand HuskSync est présent et que sa fin de synchronisation est écoutée. */
    private volatile boolean syncAware;

    public NetworkDeaths(SHDeathSpectating plugin, DeathDatabase db, NetworkLink link, Duration waitMax,
                         long syncWaitTicks, BiConsumer<Player, Boolean> respawn) {
        this.plugin = plugin;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
        this.syncWaitTicks = syncWaitTicks;
        this.respawn = respawn;
    }

    /** Branche le mode réseau : sauvegarde au départ, fin de synchronisation de HuskSync. */
    public void start() {
        link.registerSaveTask(player -> flush());
        listenHuskSync();
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des écritures de morts n'ont pas pu être terminées à l'arrêt.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    /** Le joueur vient de mourir : écrit l'état en base. */
    public void markDead(UUID uuid) {
        long now = System.currentTimeMillis();
        submit(() -> retry("mort de " + uuid, () -> db.markDead(uuid, now)));
    }

    /** Le joueur a réapparu : retire l'état en base. */
    public void clear(UUID uuid) {
        submit(() -> retry("réapparition de " + uuid, () -> db.clear(uuid)));
    }

    /** Ajoute des joueurs marqués morts, hors du fil principal ; rend le nombre de lignes créées, ou -1 en cas d'erreur. */
    public CompletableFuture<Integer> importDead(Collection<UUID> uuids) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    result.complete(db.importAll(uuids, System.currentTimeMillis()));
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Import de spectating.yml impossible : " + e.getMessage());
                    result.complete(-1);
                }
            });
        } catch (RejectedExecutionException stopped) {
            result.complete(-1);
        }
        return result;
    }

    /** Un joueur se connecte : la base dit s'il est parti mort d'un serveur, et il réapparaît si oui. */
    public void arrive(Player player) {
        UUID uuid = player.getUniqueId();
        Arrival arrival = arrivals.computeIfAbsent(uuid, u -> new Arrival());
        if (!syncAware) {
            arrival.syncDone = true;
        } else if (!arrival.syncDone) {
            // si HuskSync ne rend jamais la main, on ne laisse pas le joueur mort pour autant
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (arrivals.get(uuid) == arrival && !arrival.syncDone) {
                    plugin.getLogger().warning("Synchronisation HuskSync de " + player.getName()
                        + " non terminée après " + (syncWaitTicks / 20) + " s : réapparition sans l'attendre.");
                    arrival.syncDone = true;
                    proceed(uuid, arrival);
                }
            }, syncWaitTicks);
        }
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + uuid + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : état de mort relu quand même.");
            }
            submit(() -> {
                try {
                    arrival.dead = db.isDead(uuid);
                } catch (SQLException e) {
                    // base injoignable : le joueur garde l'état local (spectating.yml), comme avant le mode réseau
                    plugin.getLogger().log(Level.WARNING, "Etat de mort de " + uuid + " non relu en base : " + e.getMessage());
                    arrival.dead = null;
                }
                arrival.databaseDone = true;
                proceed(uuid, arrival);
            });
        });
    }

    /** Le joueur quitte ce serveur. */
    public void left(UUID uuid) {
        arrivals.remove(uuid);
    }

    private void proceed(UUID uuid, Arrival arrival) {
        synchronized (arrival) {
            if (arrival.played || !arrival.databaseDone || !arrival.syncDone) return;
            arrival.played = true;
        }
        Boolean dead = arrival.dead;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (arrivals.get(uuid) != arrival) return;
            arrivals.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                // dead == null : base injoignable, l'appelant retombe sur spectating.yml
                respawn.accept(player, dead);
            }
        });
    }

    // ------------------------------------------------------------------ HuskSync

    /**
     * Écoute la fin de synchronisation de HuskSync (classe lue par réflexion : le plugin n'en dépend pas à la
     * compilation, et rien n'est chargé sans lui).
     */
    private void listenHuskSync() {
        Plugin husk = plugin.getServer().getPluginManager().getPlugin("HuskSync");
        if (husk == null || !husk.isEnabled()) return;
        try {
            Class<? extends Event> type = Class.forName("net.william278.husksync.event.BukkitSyncCompleteEvent", false,
                husk.getClass().getClassLoader()).asSubclass(Event.class);
            Method getUser = type.getMethod("getUser");
            Method getUuid = getUser.getReturnType().getMethod("getUuid");
            plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                try {
                    UUID uuid = (UUID) getUuid.invoke(getUser.invoke(event));
                    Arrival arrival = arrivals.computeIfAbsent(uuid, u -> new Arrival());
                    arrival.syncDone = true;
                    proceed(uuid, arrival);
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().log(Level.WARNING, "Evénement HuskSync illisible : " + e);
                }
            }, plugin);
            syncAware = true;
            plugin.getLogger().info("HuskSync détecté : la réapparition d'un joueur arrivé mort attend la fin de sa synchronisation.");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "HuskSync présent mais sa fin de synchronisation est inécoutable : " + e);
        }
    }

    // ------------------------------------------------------------------ écritures

    private void retry(String what, SqlTask task) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                task.run();
                return;
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    plugin.getLogger().log(Level.WARNING, "Ecriture en base impossible (" + what + ") : " + e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Futur terminé quand toutes les écritures déjà demandées sont faites (le fil d'écriture les traite dans l'ordre). */
    private CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            io.execute(() -> done.complete(null));
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à écrire
        }
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws SQLException;
    }
}
