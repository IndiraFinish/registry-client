
/**
 * @Author: wangxw
 * @DateTime: 2020/7/6
 * @Description: TODO
 */
@FunctionalInterface
public interface DataListener {

    void dataChanged(String path, Object value, EventType eventType);
}
