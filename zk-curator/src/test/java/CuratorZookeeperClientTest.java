import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @Author: wangxw
 * @DateTime: 2020/7/2
 * @Description: TODO
 */
public class CuratorZookeeperClientTest {

    private TestingServer zkServer;
    private CuratorZookeeperClient curatorClient;
    CuratorFramework client = null;

    //    @BeforeEach
    public void setUp() {
        curatorClient = new CuratorZookeeperClient(2181);
        curatorClient.addStateListener(state -> {
            if (state == StateListener.CONNECTED) {
                System.out.println("建立连接");
            } else if (state == StateListener.RECONNECTED) {
                System.out.println("重新建立连接");
            } else if (state == StateListener.SESSION_LOST) {
                System.out.println("会话过期");
            } else if (state == StateListener.SUSPENDED) {
                System.out.println("连接断开");
            } else if (state == StateListener.NEW_SESSION_CREATED) {
                System.out.println("会话超时后重新建立连接");
            }
        });
    }

    @BeforeEach
    public void setTestServer() throws Exception {
        int port = getRandomPort();
        System.out.println("zkPort: " + port);
        zkServer = new TestingServer(port, true);
        client = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        client.start();
    }

    @Test
    public void testWatcher() throws Exception {
        CountDownLatch downLatch = new CountDownLatch(2);
        String path = "/test/cache";
        CuratorWatcher watcher = event -> {
            System.out.println("事件类型：" + event.getType() + " | path：" + event.getPath()
                    + " | state：" + event.getState());
            downLatch.countDown();
        };
        client.create().creatingParentsIfNeeded().forPath(path);
        client.getData().usingWatcher(watcher).forPath(path);
        client.setData().forPath(path, "01".getBytes());
        List<String> clilds = client.getChildren().usingWatcher(watcher).forPath(path);
        System.out.println(clilds);
        client.create().forPath(path + "/child");
        downLatch.await();
    }

    @Test
    public void testTreeCache() throws Exception {
        CountDownLatch downLatch = new CountDownLatch(5);
        String path = "/test/cache";
        client.create().creatingParentsIfNeeded().forPath(path);
        TreeCache cache = new TreeCache(client, path);
        TreeCacheListener listener = ((client, event) -> {
            System.out.println("事件类型：" + event.getType() +
                    " | 路径：" + (null != event.getData() ? event.getData().getPath() : null));
            downLatch.countDown();
        });
        cache.getListenable().addListener(listener);
        cache.start();
        client.setData().forPath(path, "01".getBytes());
        client.setData().forPath(path, "02".getBytes());
        client.create().forPath(path + "/chlid");
        client.delete().deletingChildrenIfNeeded().forPath(path);
        downLatch.await();
    }

    @Test
    public void testConnection() {
        String path = "/locks/name";
        curatorClient.create(path, "name:wang,age:11", true);
        try {
            TimeUnit.SECONDS.sleep(Integer.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testSessionTimeOut() {
        assertThat(curatorClient.checkExists("/locks/name"), is(true));
    }

    @Test
    public void testGetContent() {
        curatorClient.create("/locks/server", "demo", false);
        assertThat(curatorClient.getContent("/locks/server"), is("demo"));
    }

    public static int getRandomPort() {
        return 30000 + ThreadLocalRandom.current().nextInt(10000);
    }
}
