import org.apache.curator.framework.recipes.cache.TreeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/**
 * @Author: wangxw
 * @DateTime: 2020/7/2
 * @Description: TODO
 */
public abstract class AbstractZookeeperClient<TargetDataListener> implements ZookeeperClient {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final Set<String> persistentExistNodePath = new HashSet<>();
    private volatile boolean closed = false;
    private Map<String, TreeCache> treeCacheMap = new ConcurrentHashMap<>();
    private Set<StateListener> stateListeners = new CopyOnWriteArraySet<>();
    private final ConcurrentMap<String, ConcurrentHashMap<DataListener, TargetDataListener>> dataListeners = new ConcurrentHashMap();

    @Override
    public void create(String path, boolean ephemeral) {
        // 创建持久节点
        if (!ephemeral) {
            if (persistentExistNodePath.contains(path)) {
                return;
            }
            if (checkExists(path)) {
                persistentExistNodePath.add(path);
                return;
            }
        }
        int i = path.lastIndexOf('/');
        // 递归创建
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path);
        } else {
            createPersistent(path);
            persistentExistNodePath.add(path);
        }
    }

    @Override
    public void create(String path, String content, boolean ephemeral) {
        if (checkExists(path)) {
            delete(path);
        }
        int i = path.lastIndexOf('/');
        // 递归创建之前的节点
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path, content);
        } else {
            createPersistent(path, content);
        }
    }

    @Override
    public void delete(String path) {
        persistentExistNodePath.remove(path);
        deletePath(path);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getStateListeners() {
        return stateListeners;
    }

    protected void stateChanged(int state) {
        for (StateListener sessionListener : getStateListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener);
    }

    @Override
    public void addDataListener(String path, DataListener listener, Executor executor) {
        ConcurrentHashMap<DataListener, TargetDataListener> dataListenerMap = dataListeners.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        TargetDataListener targetDataListener = dataListenerMap.computeIfAbsent(listener, k -> createTargetDataListener(path, k));
        addTargetDataListener(path, targetDataListener, executor);
    }

    @Override
    public void removeDataListener(String path, DataListener listener) {
        ConcurrentHashMap<DataListener, TargetDataListener> dataListenerMap = dataListeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetDataListener = dataListenerMap.remove(listener);
            if (targetDataListener != null) {
                removeTargetDataListener(path, targetDataListener);
            }
        }
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    protected abstract boolean checkExists(String path);

    protected abstract String doGetContent(String path);

    protected abstract void deletePath(String path);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

}
