package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 *
 * AbstractStreamProducer<T>：
 * 负责将业务对象 T 序列化为 Map，推送到指定 Stream，并捕获发送失败后的回调。
 * 两者都依赖 RedisService（封装了 Redisson 或 Lettuce 的 Stream 操作）。
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }


    //调用 streamAdd 写入消息，携带 buildMessage(payload) 返回的字段。
    //
    //固定追加 AsyncTaskStreamConstants.FIELD_RETRY_COUNT 初始为 0（但需注意生产者的 buildMessage 是否自动包含，否则消费者会默认 0）。
    //
    //失败时调用 onSendFailed 供子类做补偿（如落库、告警）。
    protected void sendTask(T payload) {
        try {
            String messageId = redisService.streamAdd(
                streamKey(),
                buildMessage(payload),
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("{}任务已发送到Stream: {}, messageId={}",
                taskDisplayName(), payloadIdentifier(payload), messageId);
        } catch (Exception e) {
            log.error("发送{}任务失败: {}, error={}",
                taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败: " + e.getMessage());
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

//    taskDisplayName()：日志标识。
//
//    streamKey()：Stream 名称。
//
//    buildMessage(T)：将业务数据转为键值对。
//
//    payloadIdentifier(T)：用于日志中唯一标识任务（如订单号）。
//
//    onSendFailed(T, String)：自定义发送失败处理。
    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
