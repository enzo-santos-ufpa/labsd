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

public class ZooKeeperDoubleBarrierReusable implements AutoCloseable {
    private final ZooKeeper zk;
    private final String barrierNode;
    private final String id = UUID.randomUUID().toString();
    private CountDownLatch enterLatch;
    private CountDownLatch exitLatch;

    public ZooKeeperDoubleBarrierReusable(String connectString, String barrierNode) throws IOException, InterruptedException, KeeperException {
        this.barrierNode = barrierNode;
        this.zk = createZooKeeperConnection(connectString);
        ensureBarrierNodeExists();
    }

    private ZooKeeper createZooKeeperConnection(String connectString) throws IOException {
        return new ZooKeeper(connectString, 3000, this::processEvent);
    }

    private void ensureBarrierNodeExists() throws KeeperException, InterruptedException {
        if (zk.exists(barrierNode, false) == null) {
            zk.create(barrierNode, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    private void processEvent(WatchedEvent event) {
        if (event.getType() == Watcher.Event.EventType.NodeCreated && event.getPath().equals(barrierNode + "/ready")) {
            if (enterLatch != null) enterLatch.countDown();
        } else if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
            if (exitLatch != null) exitLatch.countDown();
        }
    }

    public String getId() {
        return id;
    }

    public void enterBarrier() throws KeeperException, InterruptedException {
        enterLatch = new CountDownLatch(1);
        String nodePath = barrierNode + "/" + id;
        zk.exists(barrierNode + "/ready", true);
        zk.create(nodePath, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).getBytes(StandardCharsets.UTF_8),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        List<String> children = zk.getChildren(barrierNode, false);
        if (children.size() < 3) {
            enterLatch.await();
        } else {
            zk.create(barrierNode + "/ready", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
    }

    public void exitBarrier() throws KeeperException, InterruptedException {
        exitLatch = new CountDownLatch(1);
        while (true) {
            List<Map.Entry<String, LocalDateTime>> children = zk.getChildren(barrierNode, false).stream()
                    .map(id -> {
                        try {
                            byte[] data = zk.getData(barrierNode + "/" + id, false, null);
                            return Map.entry(id, LocalDateTime.parse(new String(data, StandardCharsets.UTF_8), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        } catch (Exception e) {
                            return Map.entry(id, LocalDateTime.MIN);
                        }
                    })
                    .filter(entry -> entry.getValue() != LocalDateTime.MIN)
                    .sorted(Map.Entry.comparingByValue())
                    .collect(Collectors.toList());

            if (children.isEmpty()) return;
            if (children.size() == 1 && children.get(0).getKey().equals(id)) {
                zk.delete(barrierNode + "/" + id, -1);
                return;
            }

            if (children.get(0).getKey().equals(id)) {
                zk.exists(barrierNode + "/" + children.get(children.size() - 1).getKey(), true);
            } else {
                zk.delete(barrierNode + "/" + id, -1);
                zk.exists(barrierNode + "/" + children.get(0).getKey(), true);
            }
            exitLatch.await();
        }
    }

    @Override
    public void close() throws InterruptedException {
        zk.close();
    }
}

