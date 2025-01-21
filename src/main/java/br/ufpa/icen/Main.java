package br.ufpa.icen;

import org.apache.zookeeper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class Main {
    private static final Stack<Runnable> shutdownActions = new Stack<>();

    static class PlayerHub implements Watcher {
        private static final String ZOOKEEPER_ADDRESS = "localhost:2181";
        private static final int SESSION_TIMEOUT = 3000;
        private static final String HUB_PATH = "/gameHubBarrier";

        private final ZooKeeper zooKeeper;
        private final int maxPlayers;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<Map<String, String>> playersReference = new AtomicReference<>();

        public PlayerHub(int maxPlayers) throws IOException {
            this.maxPlayers = maxPlayers;
            zooKeeper = new ZooKeeper(ZOOKEEPER_ADDRESS, SESSION_TIMEOUT, this);
        }

        public void initialize() throws KeeperException, InterruptedException {
            // Cria um novo nó no ZooKeeper, caso ainda não exista
            if (zooKeeper.exists(HUB_PATH, true) == null) {
                zooKeeper.create(HUB_PATH, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            // Aguarda a conexão ser estabelecida com sucesso
            latch.await();
            // Adiciona escuta de nós em tempo real
            zooKeeper.getChildren(HUB_PATH, true);
        }

        public void run() throws InterruptedException {
            synchronized (zooKeeper) {
                // Aguarda o processo do ZooKeeper finalizar
                zooKeeper.wait();
            }
        }

        // Adiciona um jogador ao hub.
        public void add(String playerName) throws KeeperException, InterruptedException {
            final String playerId = zooKeeper.create(
                    HUB_PATH + "/player_",
                    playerName.getBytes(StandardCharsets.UTF_8), // Armazena o nome do jogador
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL_SEQUENTIAL // Para cada novo ID, adicione um sufixo incremental
            );
            // Quando este programa for finalizado, remova este jogador do hub
            shutdownActions.push(() -> {
                try {
                    remove(playerId);
                } catch (KeeperException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // Remove um jogador do hub.
        public void remove(String playerId) throws KeeperException, InterruptedException {
            zooKeeper.delete(playerId, -1);
        }

        // Retorna os jogadores atualmente esperando no hub.
        public Map<String, String> getPlayers() throws InterruptedException, KeeperException {
            final List<String> playersIds = zooKeeper.getChildren(HUB_PATH, true);
            final Map<String, String> players = new HashMap<>();
            for (String playerId : playersIds) {
                // Lê o nome do jogador com o respectivo ID
                final byte[] playerNameData = zooKeeper.getData(HUB_PATH + "/" + playerId, false, null);
                final String playerName = new String(playerNameData, StandardCharsets.UTF_8);
                players.put(playerId, playerName);
            }
            return players;
        }

        public void close() throws InterruptedException {
            zooKeeper.close();
        }

        @Override
        public void process(WatchedEvent event) {
            // Eventos relacionados ao nó raiz
            if (event.getPath() == null) {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    // Notifica que o ZooKeeper foi inicializado com sucesso
                    latch.countDown();
                }
                return;
            }
            // Eventos relacionados ao nó atual
            if (event.getPath().equals(HUB_PATH) && event.getType() == Event.EventType.NodeChildrenChanged) {
                // Verifica quantos jogadores estão no hub atualmente
                final Map<String, String> currentPlayers;
                try {
                    currentPlayers = this.getPlayers();
                } catch (InterruptedException | KeeperException e) {
                    System.out.println("Ocorreu um erro ao verificar quantidade de jogadores faltando.");
                    e.printStackTrace();
                    return;
                }

                // Se a lista de jogadores ainda não estiver sido preenchida
                if (playersReference.compareAndSet(null, currentPlayers)) {
                    System.out.println("+-----------------------+");
                    System.out.println("|   Hub de jogadores    |");
                    System.out.println("+-----------------------+");
                    for (String player : currentPlayers.values()) {
                        System.out.printf("| %-21s |\n", player);
                    }
                    System.out.println("+-----------------------+");
                } else {
                    // Atualiza listagem de jogadores e retorna quantos jogadores estavam esperando há 5 segundos
                    final Map<String, String> previousPlayers = playersReference.getAndSet(currentPlayers);

                    // Quando jogadores entram
                    final Set<String> joinersIds = new HashSet<>(currentPlayers.keySet());
                    joinersIds.removeAll(previousPlayers.keySet());
                    for (String playerId : joinersIds) {
                        System.out.println("! Jogador " + currentPlayers.get(playerId) + " entrou.");
                    }
                    // Quando jogadores saem
                    final Set<String> leaversIds = new HashSet<>(previousPlayers.keySet());
                    leaversIds.removeAll(currentPlayers.keySet());
                    for (String playerId : leaversIds) {
                        System.out.println("! Jogador " + previousPlayers.get(playerId) + " saiu.");
                    }
                }
                // Se ainda existem jogadores pendentes
                final int remainingPlayersAmount = this.maxPlayers - currentPlayers.size();
                if (remainingPlayersAmount > 0) {
                    if (remainingPlayersAmount == 1) {
                        System.out.println("! Esperando mais 1 jogador.");
                    } else {
                        System.out.printf("! Esperando mais %d jogadores.\n", remainingPlayersAmount);
                    }
                    return;
                }
                System.out.println("O jogo iniciou!");
            }
        }
    }

    public static void main(String[] args) throws InterruptedException, KeeperException, IOException {
        // Definem ações a serem executadas quando o programa terminar de executar
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            while (!shutdownActions.isEmpty()) {
                final Runnable action = shutdownActions.pop();
                action.run();
            }
        }));

        // Código principal
        final PlayerHub hub = new PlayerHub(3);
        hub.initialize();

        System.out.println("Insira o nome do seu jogador:");
        final String name = new Scanner(System.in).nextLine();
        hub.add(name);

        hub.run();
        hub.close();
    }
}
