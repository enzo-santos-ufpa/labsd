package br.ufpa.icen.lib;

import org.apache.zookeeper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Uma barreira distribuída dupla usando o Apache ZooKeeper.
 */
public class ZooKeeperDoubleBarrier implements AutoCloseable {
    private final ZooKeeper zk;
    private final String barrierNode;
    private final CountDownLatch enterLatch = new CountDownLatch(1);
    private final String id = UUID.randomUUID().toString();
    private CountDownLatch exitLatch;

    /**
     * Inicializa a barreira dupla do ZooKeeper.
     *
     * @param connectString String de conexão com o ZooKeeper.
     * @param barrierNode   Caminho do nó da barreira.
     * @throws IOException se a conexão falhar.
     */
    public ZooKeeperDoubleBarrier(String connectString, String barrierNode) throws IOException, InterruptedException, KeeperException {
        this.barrierNode = barrierNode;
        this.exitLatch = null;
        this.zk = createZooKeeperConnection(connectString, event -> {
            // Se um nó /ready for criado (ou seja, se o último cliente entrar na barreira),
            // libere este cliente para prosseguir com o seu processamento (`enterLatch`)
            if (event.getType() == Watcher.Event.EventType.NodeCreated) {
                if (event.getPath().equals(barrierNode + "/ready")) {
                    enterLatch.countDown();
                }
                // Se um nó for removido (ou seja, se um cliente terminar seu processamento
                // na barreira), libere este cliente para sair da barreira (`exitLatch`)
            } else if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                if (exitLatch != null) {
                    exitLatch.countDown();
                }
            }
        });
        // Cria o nó de barreira
        zk.create(barrierNode, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    protected ZooKeeper createZooKeeperConnection(String connectString, Watcher watcher) throws IOException {
        return new ZooKeeper(connectString, 3000, watcher);
    }

    public String getId() {
        return id;
    }

    /**
     * Faz com que o cliente atual entre na barreira.
     *
     * @throws KeeperException      se o ZooKeeper encontrar um erro.
     * @throws InterruptedException se a thread for interrompida.
     */
    public void enterBarrier() throws KeeperException, InterruptedException {
        // 1. Create a name n = b+"/"+p
        final String n = barrierNode + "/" + id;
        // 2. Set watch: exists(b + "/ready", true)
        zk.exists(barrierNode + "/ready", true);
        // 3. Create child: create( n, EPHEMERAL)
        zk.create(n,
                // Guarda a data de criação deste nó para consulta em `exitBarrier`
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        // 4. L = getChildren(b, false)
        final List<String> children = zk.getChildren(barrierNode, false);
        if (children.size() < 3) {
            // 5. if fewer children in L than x, wait for watch event
            enterLatch.await();
        } else {
            // 6. else create(b + "/ready", REGULAR)
            zk.create(barrierNode + "/ready", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
    }

    /**
     * Faz com que o cliente atual saia da barreira.
     *
     * @throws KeeperException      se o ZooKeeper encontrar um erro.
     * @throws InterruptedException se a thread for interrompida.
     */
    public void exitBarrier() throws KeeperException, InterruptedException {
        for (; ; ) {
            // 1. L = getChildren(b, false)
            final List<Map.Entry<String, LocalDateTime>> children = zk.getChildren(barrierNode, false)
                    // Para cada nó
                    .stream().collect(Collectors.toMap(id -> id, id -> {
                        // Leia seu conteúdo utilizando zk.getData()
                        final byte[] creationDateData;
                        try {
                            creationDateData = zk.getData(barrierNode + "/" + id, false, null);
                        } catch (KeeperException.NoNodeException e) {
                            // Se nó não existe, adiciona valor padrão que removeremos posteriormente
                            return LocalDateTime.MIN;
                        } catch (KeeperException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        // Converta o conteúdo para um LocalDateTime, para encontrar a data de criação deste nó
                        return LocalDateTime.parse(new String(creationDateData, StandardCharsets.UTF_8), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    }))
                    // Transforme em um Map<String, LocalDateTime>, onde
                    //  - as `keys` são os IDs do nó; e
                    //  - os `values` são suas datas de criação
                    .entrySet().stream()
                    // Remove nós que não existem por valor padrão que adicionamos anteriormente
                    .filter(entry -> entry.getValue() != LocalDateTime.MIN)
                    // Ordene o Map<String, LocalDateTime> pelos `values` de forma crescente
                    .sorted(Map.Entry.comparingByValue())
                    // Transforme em uma List<Map.Entry<String, LocalDateTime>>, onde
                    // - o nó mais antigo criado (lowest) está na primeira posição (entries.get(0))
                    // - o nó mais recente criado (highest) está na última posição (entries.get(entries.size() - 1))
                    .collect(Collectors.toList());
            // 2. if no children, exit
            if (children.isEmpty()) {
                return;
            }
            // 3. if p is only process node in L, delete(n) and exit
            if (children.size() == 1 && children.get(0).getKey().equals(id)) {
                zk.delete(barrierNode + "/" + id, -1);
                return;
            }
            exitLatch = new CountDownLatch(1);
            if (children.get(0).getKey().equals(id)) {
                // 4. if p is the lowest process node in L, wait on highest process node in L
                zk.exists(barrierNode + "/" + children.get(children.size() - 1), true);
            } else {
                // 5. else delete(n) if still exists and wait on lowest process node in L
                try {
                    zk.delete(barrierNode + "/" + id, -1);
                } catch (KeeperException.NoNodeException ignored) {
                }
                zk.exists(barrierNode + "/" + children.get(0), true);
            }
            exitLatch.countDown();
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