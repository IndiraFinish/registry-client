import java.util.List;
import java.util.concurrent.Executor;

/**
 * @Author: wangxw
 * @DateTime: 2020/7/2
 * @Description: TODO
 */
public interface ZookeeperClient {
    /**
     * 递归创建节点
     *
     * @param path
     * @param ephemeral
     */
    void create(String path, boolean ephemeral);

    /**
     * 递归创建节点带内容
     *
     * @param path
     * @param ephemeral
     */
    void create(String path, String content, boolean ephemeral);

    void delete(String path);

    List<String> getChildren(String path);

    boolean isConnected();

    void close();

    String getContent(String path);

//    List<String> addChildListener(String path, ChildListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     */
    void addDataListener(String path, DataListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     * @param executor another thread
     */
    void addDataListener(String path, DataListener listener, Executor executor);

    void removeDataListener(String path, DataListener listener);

//    void removeChildListener(String path, ChildListener listener);
//
    void addStateListener(StateListener listener);

    void removeStateListener(StateListener listener);
}
