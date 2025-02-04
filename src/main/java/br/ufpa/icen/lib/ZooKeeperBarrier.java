package br.ufpa.icen.lib;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Uma barreira distribuída simples usando o Apache ZooKeeper.
 */
public class ZooKeeperBarrier {
    private final ZooKeeper zk;
    private final String barrierNode;
    private final CountDownLatch latch = new CountDownLatch(1);

    /**
     * Inicializa a barreira do ZooKeeper.
     * 
     * @param connectString String de conexão com o ZooKeeper.
     * @param barrierNode Caminho do nó da barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperBarrier(String connectString, String barrierNode) throws IOException {
    	this.barrierNode = barrierNode;
    	this.zk = new ZooKeeper(connectString, 3000, event -> {
    		 if (event.getType() == Watcher.Event.EventType.NodeDeleted && event.getPath().equals(this.barrierNode)) {
                 latch.countDown();
             }
        });        
    }

    /**
     * Aguarda até que a barreira seja removida.
     * 
     * @throws KeeperException se o ZooKeeper encontrar um erro.
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
     * @throws KeeperException se o ZooKeeper encontrar um erro.
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
    public void close() throws InterruptedException {
        zk.close();
    }
}


