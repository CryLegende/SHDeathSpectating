<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHDeathSpectating

*Remplace l'écran de mort : quelques secondes en spectateur avant la réapparition, avec perte d'exp.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Remplace l'écran de mort : quelques secondes en spectateur avant la réapparition, avec perte d'exp.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : aucune
- Optionnelles : `packetevents` (masque le menu de choix de joueur du mode spectateur), `SHNetwork` (mode réseau), `HuskSync`

## Fonctionnement

La mort est la vraie : le jeu applique lui-même le Totem d'immortalité, la Malédiction de disparition, `keepInventory`, les drops, les statistiques et le responsable de la mort, et les autres plugins reçoivent un vrai `PlayerDeathEvent`. Le plugin ajoute, dans les mondes de `worldWhitelist` :

- pas de message de mort dans le tchat ;
- une perte d'exp de `expLossPercentage` % (le reste est gardé), lâchée au sol ;
- une réapparition immédiate sur place, puis `spectatingDuration` secondes en spectateur, caméra fixée au point de la mort et tournée vers le responsable ;
- la réapparition finale par `spawnCommand`.

`immediate_respawn` est activée par le plugin dans ces mondes (le client réapparaît sans écran de mort) et remise à `false` à l'arrêt ou si le monde sort de la liste. Un client qui l'ignorerait est réapparu par le serveur au bout de 5 ticks. Clients de la 1.19.4 à la dernière version (via ViaVersion).

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), l'état « mort, en attente de réapparition » n'est plus dans `spectating.yml` mais dans MariaDB (table `shdeathspectating_morts` : `uuid`, `since`), partagée entre les serveurs de jeu :

- la mort et la réapparition s'écrivent en base, sur un fil à part ;
- au départ d'un joueur, SHNetwork attend que ces écritures soient finies ;
- à son arrivée sur l'autre serveur, la base est relue : un joueur parti mort réapparaît exactement comme après une déconnexion sur un serveur seul (survie, commande de spawn, vie pleine). Sans cela il restait spectateur, sans caméra, sur le serveur d'arrivée ;
- avec HuskSync, qui réapplique le gamemode du joueur à son arrivée, la réapparition attend la fin de sa synchronisation (`reseau.attente-synchro-secondes` au plus).

`/shdeathspectating import` copie une fois les joueurs encore marqués dans `spectating.yml` vers la base. En mode réseau, `spectating.yml` n'est plus écrit.

Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : état local, fichier `spectating.yml`. Si la base est injoignable, le joueur garde l'état de mort local de son serveur. La table se crée toute seule au démarrage, ou à la main avec `sql/schema.sql`.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shdeathspectating import` | — | Copie les morts de `spectating.yml` dans la base partagée (mode réseau). | `shdeathspectating.admin.import` |
| `/shdeathspectating reload` | — | Relit `config.yml` et `messages.yml`, appliqué aux morts suivantes. En cas d'erreur YAML, l'ancienne configuration reste en place et le fichier fautif est indiqué. La section `reseau` demande un redémarrage. | `shdeathspectating.admin.reload` |
| `/shdeathspectating respawn <joueur>` | — | Fait réapparaître un joueur resté en état de mort ; refusé s'il n'est pas mort. | `shdeathspectating.admin.respawn` |

La console peut utiliser les trois.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shdeathspectating.admin` | Toutes les sous-commandes de `/shdeathspectating`. | `op` |
| `shdeathspectating.admin.import` | `/shdeathspectating import`. | `op` |
| `shdeathspectating.admin.reload` | `/shdeathspectating reload`. | `op` |
| `shdeathspectating.admin.respawn` | `/shdeathspectating respawn`. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHDeathSpectating/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
