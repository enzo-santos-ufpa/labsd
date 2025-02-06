package br.ufpa.icen.lib;

import org.apache.curator.test.TestingServer;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Uma barreira distribuída simples usando o Apache ZooKeeper.
 */
public class ZooKeeperBarrier implements AutoCloseable {
    private final ZooKeeper zk;
    private final String barrierNode;
    private final CountDownLatch latch = new CountDownLatch(1);

    /**
     * Inicializa a barreira do ZooKeeper.
     *
     * @param connectString String de conexão com o ZooKeeper.
     * @param barrierNode   Caminho do nó da barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperBarrier(String connectString, String barrierNode) throws IOException, InterruptedException, KeeperException {
        this.barrierNode = barrierNode;
        this.zk = createZooKeeperConnection(connectString, event -> {
            if (event.getType() == Watcher.Event.EventType.NodeDeleted && event.getPath().equals(this.barrierNode)) {
                latch.countDown();
            }
        });
    }

    public static void main(String[] args) {
        final Logger logger = LogManager.getLogger(ZooKeeperBarrier.class);

        try (
                // Inicializa servidor de teste, simulando o ZooKeeper
                final TestingServer t = new TestingServer();
                // Inicializa o controlador de barreira do ZooKeeper
                final ZooKeeperBarrier controller = new ZooKeeperBarrier(t.getConnectString(), "/armazem")) {
            // Cria a barreira
            controller.zk.create("/armazem", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            System.out.println("Pedidos ainda não estão prontos. Entregadores aguardando...");

            final int numCouriers = 3;
            final List<Future<Void>> futures = new ArrayList<>(numCouriers);
            for (int i = 0; i < numCouriers; i++) {
                final int courierId = i;
                // Para cada entregador, execute uma ação (Future)
                futures.add(CompletableFuture.runAsync(() -> {
                    // Cria uma instância do ZooKeeper conectada à barreira
                    try (final ZooKeeperBarrier courier = new ZooKeeperBarrier(t.getConnectString(), "/armazem")) {
                        System.out.println("Entregador " + courier + " esperando os pedidos serem processados...");
                        try {
                            // Aguarda pela barreira
                            courier.waitForBarrier();
                        } catch (KeeperException | InterruptedException e) {
                            logger.error("erro em entregador " + courierId + " ao aguardar por barreira", e);
                            return;
                        }

                    } catch (InterruptedException | IOException e) {
                        logger.error("erro em entregador " + courierId + " ao criar barreira", e);
                        return;
                    } catch (KeeperException e) {
                        throw new RuntimeException(e);
                    }
                    System.out.println("Entregador " + courierId + " saiu para entrega!");
                }));
            }

            // Libera entregadores após 3 segundos
            final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.schedule(() -> {
                try {
                    controller.removeBarrier();
                } catch (KeeperException | InterruptedException e) {
                    logger.error("erro ao remover barreira por controlador", e);
                    return;
                }
                System.out.println("Pedidos prontos! Liberando entregadores para entrega...");
            }, 3, TimeUnit.SECONDS);

            // Inicia processamento de entregadores em paralelo
            try {
                //noinspection SuspiciousToArrayCall
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException e) {
                logger.error("erro ao executar pedidos", e);
                return;
            }
            System.out.println("Todos os entregadores iniciaram a entrega!");

        } catch (Exception e) {
            logger.error("erro ao inicializar programa", e);
        }
    }

    protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
        return new ZooKeeper(connectString, 3000, watcher);
    }

    /**
     * Aguarda até que a barreira seja removida.
     *
     * @throws KeeperException      se o ZooKeeper encontrar um erro.
     * @throws InterruptedException se a thread for interrompida.
     */
    public void waitForBarrier() throws KeeperException, InterruptedException {
        while (true) {
            Stat stat = zk.exists(barrierNode, true);
            if (stat == null) {
                return; // A barreira foi removida, pode prosseguir
            }
            latch.await(); // Aguarda até que o nó seja excluído
        }
    }

    /**
     * Remove o nó da barreira.
     *
     * @throws KeeperException      se o ZooKeeper encontrar um erro.
     * @throws InterruptedException se a thread for interrompida.
     */
    public void removeBarrier() throws KeeperException, InterruptedException {
        Stat stat = zk.exists(barrierNode, false);
        if (stat != null) {
            zk.delete(barrierNode, -1);
        }
    }

    /**
     * Fecha a conexão com o ZooKeeper.
     *
     * @throws InterruptedException se a thread for interrompida.
     */
    @Override
    public void close() throws InterruptedException {
        zk.close();
    }
}


