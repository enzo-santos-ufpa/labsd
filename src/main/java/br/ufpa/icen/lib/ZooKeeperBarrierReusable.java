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
 * Uma barreira distribuída reutilizável usando o Apache ZooKeeper.
 */
public class ZooKeeperBarrierReusable implements AutoCloseable {
    private final ZooKeeper zk;
    private final String barrierNode;
    private final CyclicBarrier barrier;

    /**
     * Inicializa a barreira do ZooKeeper.
     *
     * @param connectString String de conexão com o ZooKeeper.
     * @param barrierNode   Caminho do nó da barreira.
     * @param parties       Número de participantes esperados para liberar a barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperBarrierReusable(String connectString, String barrierNode, int parties) throws IOException, InterruptedException, KeeperException {
        this.barrierNode = barrierNode;
        this.barrier = new CyclicBarrier(parties);

        this.zk = createZooKeeperConnection(connectString, event -> {
            if (event.getType() == Watcher.Event.EventType.NodeDeleted && event.getPath().equals(this.barrierNode)) {
                try {
                    barrier.await(); // Libera os participantes quando a barreira for removida
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    public static void main(String[] args) {
        final Logger logger = LogManager.getLogger(ZooKeeperBarrierReusable.class);

        try (
                final TestingServer t = new TestingServer();
                final ZooKeeperBarrierReusable controller = new ZooKeeperBarrierReusable(t.getConnectString(), "/armazem", 3)) {

            controller.zk.create("/armazem", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            System.out.println("Pedidos ainda não estão prontos. Entregadores aguardando...");

            final int numCouriers = 3;
            final List<Future<Void>> futures = new ArrayList<>(numCouriers);
            for (int i = 0; i < numCouriers; i++) {
                final int courierId = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try (final ZooKeeperBarrierReusable courier = new ZooKeeperBarrierReusable(t.getConnectString(), "/armazem", numCouriers)) {
                        System.out.println("Entregador " + courierId + " esperando os pedidos serem processados...");
                        try {
                            courier.waitForBarrier();
                        } catch (KeeperException | InterruptedException e) {
                            logger.error("Erro em entregador " + courierId + " ao aguardar por barreira", e);
                        }
                    } catch (InterruptedException | IOException | KeeperException e) {
                        logger.error("Erro em entregador " + courierId + " ao criar barreira", e);
                    }
                    System.out.println("Entregador " + courierId + " saiu para entrega!");
                }));
            }

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.schedule(() -> {
                try {
                    controller.removeBarrier();
                } catch (KeeperException | InterruptedException e) {
                    logger.error("Erro ao remover barreira por controlador", e);
                }
                System.out.println("Pedidos prontos! Liberando entregadores para entrega...");
            }, 3, TimeUnit.SECONDS);

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            System.out.println("Todos os entregadores iniciaram a entrega!");

        } catch (Exception e) {
            logger.error("Erro ao inicializar programa", e);
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
                try {
                    barrier.await();
                } catch (BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
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

    @Override
    public void close() throws InterruptedException {
        zk.close();
    }
}

