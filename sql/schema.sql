-- Table des joueurs morts de SHDeathSpectating avec SHNetwork. Le plugin la crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à la créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.shdeathspectating_morts (
    uuid  CHAR(36) NOT NULL,                -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    since BIGINT   NOT NULL,                -- instant de la mort, en millisecondes depuis 1970
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shdeathspectating'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.shdeathspectating_morts TO 'shdeathspectating'@'127.0.0.1';
