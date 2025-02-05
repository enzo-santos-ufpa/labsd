package br.ufpa.icen.lib;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Uma barreira dupla distribuída usando o Apache ZooKeeper.
 */
public class ZooKeeperDoubleBarrier {
    private final ZooKeeper zk;
    private final String barrierNode1;
    private final String barrierNode2;
    private final CountDownLatch latch1 = new CountDownLatch(1);
    private final CountDownLatch latch2 = new CountDownLatch(1);

    /**
     * Inicializa a barreira dupla do ZooKeeper.
     *
     * @param connectString String de conexão com o ZooKeeper.
     * @param barrierNode1 Caminho do primeiro nó da barreira.
     * @param barrierNode2 Caminho do segundo nó da barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperDoubleBarrier(String connectString, String barrierNode1, String barrierNode2) throws IOException {
        this.barrierNode1 = barrierNode1;
        this.barrierNode2 = barrierNode2;
        this.zk = new ZooKeeper(connectString, 3000, event -> {
            if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                if (event.getPath().equals(this.barrierNode1)) {
                    latch1.countDown();
                } else if (event.getPath().equals(this.barrierNode2)) {
                    latch2.countDown();
                }
            }
        });
    }

    /**
     * Aguarda até que ambas as barreiras sejam removidas.
     *
     * @throws KeeperException se o ZooKeeper encontrar um erro.
     * @throws InterruptedException se a thread for interrompida.
     */
    public void waitForBarriers() throws KeeperException, InterruptedException {
        while (true) {
            Stat stat1 = zk.exists(barrierNode1, true);
            Stat stat2 = zk.exists(barrierNode2, true);

            if (stat1 == null && stat2 == null) {
                return; // Ambas as barreiras foram removidas, pode prosseguir
            }

            if (stat1 != null) {
                latch1.await(); // Aguarda até que o primeiro nó seja excluído
            }
            if (stat2 != null) {
                latch2.await(); // Aguarda até que o segundo nó seja excluído
            }
        }
    }

    /**
     * Remove ambos os nós da barreira.
     *
     * @throws KeeperException se o ZooKeeper encontrar um erro.
     * @throws InterruptedException se a thread for interrompida.
     */
    public void removeBarriers() throws KeeperException, InterruptedException {
        Stat stat1 = zk.exists(barrierNode1, false);
        if (stat1 != null) {
            zk.delete(barrierNode1, -1);
        }

        Stat stat2 = zk.exists(barrierNode2, false);
        if (stat2 != null) {
            zk.delete(barrierNode2, -1);
        }
    }

    /**
     * Fecha a conexão com o ZooKeeper.
     *
     * @throws InterruptedException se a thread for interrompida.
     */
    public void close() throws InterruptedException {
        zk.close();
    }
}