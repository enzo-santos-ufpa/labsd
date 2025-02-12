package br.ufpa.icen.lib;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;

import java.io.IOException;

/**
 * Uma barreira distribuída reutilizável usando o Apache ZooKeeper.
 */
public class ZooKeeperReusableBarrier extends ZooKeeperBarrier {
    /**
     * Inicializa a barreira reutilizável do ZooKeeper.
     *
     * @param connectString String de conexão com o ZooKeeper.
     * @param barrierNode   Caminho do nó da barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperReusableBarrier(String connectString, String barrierNode) throws IOException, InterruptedException, KeeperException {
        super(connectString, barrierNode);
    }

    @Override
    public void waitForBarrier() throws KeeperException, InterruptedException {
        super.waitForBarrier();
        zk.create(barrierNode, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }
}

