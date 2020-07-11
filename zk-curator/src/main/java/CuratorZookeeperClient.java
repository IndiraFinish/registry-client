import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * @Author: wangxw
 * @DateTime: 2020/7/2
 * @Description: TODO
 */
public class CuratorZookeeperClient extends AbstractZookeeperClient<CuratorZookeeperClient.CuratorWatcherImpl> {
    private static final Logger logger = LoggerFactory.getLogger(CuratorZookeeperClient.class);
    static final Charset CHARSET = Charset.forName("UTF-8");
    private static final int CONNECTION_TIMEOUT = 5 * 1000;
    private static final int SESSION_TIMEOUT = 60 * 1000;
    private final CuratorFramework client;
    private Map<String, TreeCache> treeCacheMap = new ConcurrentHashMap<>();

    public CuratorZookeeperClient(int port) {
        try {
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString("127.0.0.1:" + port)
                    .retryPolicy(new RetryNTimes(1, 1000))
                    .connectionTimeoutMs(CONNECTION_TIMEOUT) // 连接创建超时间
                    .sessionTimeoutMs(SESSION_TIMEOUT); // 会话过期时间
            client = builder.build();
            client.getConnectionStateListenable().addListener(new CuratorConnectionStateListener());
            client.start();
            boolean connected = client.blockUntilConnected(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
            if (!connected) {
                throw new IllegalStateException("zookeeper not connected");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private class CuratorConnectionStateListener implements ConnectionStateListener {
        private final long UNKNOWN_SESSION_ID = -1L;

        private long lastSessionId;

        @Override
        public void stateChanged(CuratorFramework curatorFramework, ConnectionState state) {
            long sessionId = -1;
            try {
                sessionId = client.getZookeeperClient().getZooKeeper().getSessionId();
            } catch (Exception e) {
                logger.warn("Curator client state changed, but failed to get the related zk session instance.");
            }

            if (state == ConnectionState.LOST) {
                logger.warn("Curator zookeeper session " + Long.toHexString(lastSessionId) + " expired.");
                CuratorZookeeperClient.this.stateChanged(StateListener.SESSION_LOST);
            } else if (state == ConnectionState.SUSPENDED) {
                logger.warn("Curator zookeeper connection of session " + Long.toHexString(sessionId) + " timed out. " +
                        "connection timeout value is " + CONNECTION_TIMEOUT + ", session expire timeout value is " + SESSION_TIMEOUT);
                CuratorZookeeperClient.this.stateChanged(StateListener.SUSPENDED);
            } else if (state == ConnectionState.CONNECTED) {
                lastSessionId = sessionId;
                logger.info("Curator zookeeper client instance initiated successfully, session id is " + Long.toHexString(sessionId));
                CuratorZookeeperClient.this.stateChanged(StateListener.CONNECTED);
            } else if (state == ConnectionState.RECONNECTED) {
                // 重建session
                if (lastSessionId == sessionId && sessionId != UNKNOWN_SESSION_ID) {
                    logger.warn("Curator zookeeper connection recovered from connection lose, " +
                            "reuse the old session " + Long.toHexString(sessionId));
                    CuratorZookeeperClient.this.stateChanged(StateListener.RECONNECTED);
                } else { //和上次sessionid不同代表session过期 新创建session
                    logger.warn("New session created after old session lost, " +
                            "old session " + Long.toHexString(lastSessionId) + ", new session " + Long.toHexString(sessionId));
                    lastSessionId = sessionId;
                    CuratorZookeeperClient.this.stateChanged(StateListener.NEW_SESSION_CREATED);
                }
            }
        }
    }

    static class CuratorWatcherImpl implements TreeCacheListener {
        private String path;
        private DataListener dataListener;

        public CuratorWatcherImpl(String path, DataListener dataListener) {
            this.path = path;
            this.dataListener = dataListener;
        }

        @Override
        public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
            if (dataListener != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("listen the zookeeper changed. The changed data:" + event.getData());
                }
                TreeCacheEvent.Type type = event.getType();
                EventType eventType = null;
                String content = null;
                String path = null;
                switch (type) {
                    case NODE_ADDED:
                        eventType = EventType.NodeCreated;
                        path = event.getData().getPath();
                        content = event.getData().getData() == null ? "" : new String(event.getData().getData(), CHARSET);
                        break;
                    case NODE_UPDATED:
                        eventType = EventType.NodeDataChanged;
                        path = event.getData().getPath();
                        content = event.getData().getData() == null ? "" : new String(event.getData().getData(), CHARSET);
                        break;
                    case NODE_REMOVED:
                        path = event.getData().getPath();
                        eventType = EventType.NodeDeleted;
                        break;
                    case INITIALIZED:
                        eventType = EventType.INITIALIZED;
                        break;
                    case CONNECTION_LOST:
                        eventType = EventType.CONNECTION_LOST;
                        break;
                    case CONNECTION_RECONNECTED:
                        eventType = EventType.CONNECTION_RECONNECTED;
                        break;
                    case CONNECTION_SUSPENDED:
                        eventType = EventType.CONNECTION_SUSPENDED;
                        break;
                }
                dataListener.dataChanged(path, content, eventType);
            }
        }
    }


    @Override
    public void createPersistent(String path) {
        try {
            client.create().forPath(path);
        } catch (KeeperException.NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists.", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * ZNode已经存在，因此我们将只尝试在会话过期时重新创建一个节点，这个重复可能是由于zk服务器的删除延迟造成的，这意味着旧的会话
     * 可能仍然持有这个ZNode，服务器只是没有时间做删除。在这种情况下我们可以尝试删除，然后重新创建
     *
     * @param path
     */
    @Override
    public void createEphemeral(String path) {
        try {
            client.create().withMode(CreateMode.EPHEMERAL).forPath(path);
        } catch (KeeperException.NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists, since we will only try to recreate a node on a session expiration" +
                    ", this duplication might be caused by a delete delay from the zk server, which means the old expired session" +
                    " may still holds this ZNode and the server just hasn't got time to do the deletion. In this case, " +
                    "we can just try to delete and create again.", e);
            deletePath(path);
            createEphemeral(path);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void createPersistent(String path, String data) {
        byte[] dataBytes = data.getBytes(CHARSET);
        try {
            client.create().forPath(path, dataBytes);
        } catch (KeeperException.NodeExistsException e) {
            try {
                client.setData().forPath(path, dataBytes);
            } catch (Exception e1) {
                throw new IllegalStateException(e.getMessage(), e1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void createEphemeral(String path, String data) {
        byte[] dataBytes = data.getBytes(CHARSET);
        try {
            client.create().withMode(CreateMode.EPHEMERAL).forPath(path, dataBytes);
        } catch (KeeperException.NodeExistsException e) {
            logger.warn("ZNode " + path + " already exists, since we will only try to recreate a node on a session expiration" +
                    ", this duplication might be caused by a delete delay from the zk server, which means the old expired session" +
                    " may still holds this ZNode and the server just hasn't got time to do the deletion. In this case, " +
                    "we can just try to delete and create again.", e);
            deletePath(path);
            createEphemeral(path, data);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public boolean checkExists(String path) {
        try {
            if (client.checkExists().forPath(path) != null) {
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    public String doGetContent(String path) {
        try {
            byte[] dataBytes = client.getData().forPath(path);
            return (dataBytes == null || dataBytes.length == 0) ? null : new String(dataBytes, CHARSET);
        } catch (KeeperException.NoNodeException e) {
            // ignore NoNode Exception.
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public void deletePath(String path) {
        try {
            client.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (KeeperException.NoNodeException e) {
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void doClose() {
        client.close();

    }

    @Override
    public List<String> getChildren(String path) {
        try {
            return client.getChildren().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return client.getZookeeperClient().isConnected();
    }

    @Override
    protected CuratorWatcherImpl createTargetDataListener(String path, DataListener listener) {
        return new CuratorWatcherImpl(path, listener);
    }

    @Override
    protected void addTargetDataListener(String path, CuratorWatcherImpl treeCacheListener, Executor executor) {
        try {
            TreeCache treeCache = TreeCache.newBuilder(client, path).setCacheData(false).build();
            treeCacheMap.put(path,treeCache);
            if (executor == null) {
                treeCache.getListenable().addListener(treeCacheListener);
            } else {
                treeCache.getListenable().addListener(treeCacheListener, executor);
            }
            treeCache.start();
        } catch (Exception e) {
            throw new IllegalStateException("Add treeCache listener for path:" + path, e);
        }
    }

    @Override
    protected void removeTargetDataListener(String path, CuratorZookeeperClient.CuratorWatcherImpl treeCacheListener) {
        TreeCache treeCache = treeCacheMap.get(path);
        if (treeCache != null) {
            treeCache.getListenable().removeListener(treeCacheListener);
        }
        treeCacheListener.dataListener = null;
    }
}
