package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
// Redis Stream 消费者模板（抽象类），子类只需实现 9 个抽象方法
// ⭐⭐⭐ 设计模式——模板方法模式的实际应用

/*
第一步：理解业务背景和 Redis Stream 概念
为什么要用 Redis Stream？
它提供持久化、消费组、消息确认、重试等能力，适合构建轻量级可靠异步任务队列
消费组（Consumer Group）：允许多个消费者实例分担消息，且每个消息只被组内一个消费者处理。
关键机制：XADD 生产、XREADGROUP 消费、XACK 确认、XPENDING + XCLAIM 处理超时未确认消息。
与常见 MQ 对比：Redis Stream 是内存数据库上的功能，适合低延迟、中等吞吐场景，但不适合海量堆积。
*/

//AbstractStreamConsumer<T>：
//负责从指定 Stream 的消费组中拉取消息，解析为 T，执行业务逻辑，并管理重试、完成/失败状态及最终确认。
//两者都依赖 RedisService（封装了 Redisson 或 Lettuce 的 Stream 操作）。
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }


    /*消费者生命周期（AbstractStreamConsumer.java:44-75）：
    - @PostConstruct 时启动单线程守护线程池
    - 在 consumeLoop() 中无限循环拉取消息
    - @PreDestroy 时优雅关闭
    */
    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);    //用于 消费组内 区分实例
        this.executorService = new ThreadPoolExecutor(//创建单线程的守护线程池（保证只有一个消费线程，简化并发控制）。
                1,                          // corePoolSize: 核心线程数为1，保证始终只有一个工作线程在处理任务
                1,                          // maximumPoolSize: 最大线程数也为1，确保线程池永远不会创建第二个线程（严格单线程消费）
                0L,                         // keepAliveTime: 空闲线程存活时间为0（因core=max=1，此参数实际不生效）
                TimeUnit.MILLISECONDS,      // unit: 存活时间单位（毫秒）
                new LinkedBlockingQueue<>(),// workQueue: 无界阻塞队列，任务可无限堆积，不会触发拒绝策略（但也存在OOM风险）。new LinkedBlockingQueue<>(capacity)是有界队列
                r -> {                      // threadFactory: 自定义线程工厂
                    Thread t = new Thread(r, threadName()); // 以指定名称创建线程，便于日志追踪和问题排查
                    t.setDaemon(true);      // ⚠️ 设为守护线程：JVM退出时该线程自动终止，不会阻止主进程关闭
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy() // handler: 拒绝策略为抛出RejectedExecutionException
                // 注：由于使用了无界队列，任务永远不会被拒绝，此策略实际不会被触发
        );

        running.set(true);
        executorService.submit(this::startConsumer);//提交 startConsumer 任务 → 确保 Stream 和 Group 存在 → 进入无限消费循环。
//        this::startConsumer 是java 8 引入的方法引用（Method Reference）语法，
//        等价于lambda语法：
//        executorService.submit(this::startConsumer);
//        executorService.submit(() -> this.startConsumer());
//        当 startConsumer() 是一个无参、无返回值（void） 的方法时，它恰好符合 Runnable 函数式接口的 void run() 签名，因此可以直接作为 Runnable 传入 submit()。
//立即返回	submit() 不会阻塞当前线程，调用后立刻得到一个 Future<?> 对象
//异步执行	startConsumer() 被放入线程池的任务队列中，由线程池的工作线程在稍后某个时刻执行
//        注意：这里用的是 submit() 而非 execute()。submit() 会捕获任务内部的异常并封装到 Future 中，不会直接抛出；而 execute() 会将异常传播到线程的 UncaughtExceptionHandler

//        一句话总结：把当前对象的 startConsumer 方法作为一个异步任务，交给单线程守护线程池去后台持续运行，调用方不阻塞、不等待。
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }


    //调用 redisService.streamConsumeMessages，这是一个批量拉取 + 自动处理超时消息的封装。
    //该方法内部会：
    //
    //尝试读取新消息（XREADGROUP）
    //
    //同时处理 PENDING 列表中超时的消息（XCLAIM）
    //
    //每批最多 BATCH_SIZE 条，间隔 POLL_INTERVAL_MS。
    //
    //对每条消息，调用内部 processMessage，并传入消息 ID 和数据 Map。
    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }


    /*消息处理流程（processMessage）
    解析：parsePayload 将 Map 转为业务对象 T，若失败则直接确认并丢弃（避免阻塞）。

    重试次数：从消息字段 AsyncTaskStreamConstants.FIELD_RETRY_COUNT 解析。

    执行前钩子：shouldSkip（可被子类重写，实现业务级跳过，如任务已过期）。

    状态标记：markProcessing（业务侧记录“处理中”）。

    业务逻辑：processBusiness（真正执行任务）。

    完成标记：markCompleted。

    确认消息：ackMessage 调用 XACK 从 Pending 列表移除。

    异常处理：

    若失败且重试次数 < MAX_RETRY_COUNT，调用 retryMessage（子类需实现：可重新入队或更新业务状态），并确认原消息（避免 Pending 堆积）。

    若超过重试上限，调用 markFailed 记录最终失败原因（截断至 500 字符），然后确认原消息。

    关键设计点：

    消息确认总是在处理完成后（成功/失败/跳过），不依赖外部 ACK，防止 Pending 队列积压。

    重试机制由业务层自行实现（重新调用 producer 或直接设置新消息），基类只传递重试次数。

    所有抽象方法提供了业务介入点，形成完整的“处理模板”。*/
    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            ackMessage(messageId);
            return;
        }

        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
            ackMessage(messageId);
        }
    }

    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();//日志前缀

    protected abstract String streamKey();//	指定消费的 Stream 和消费组

    protected abstract String groupName();//	指定消费的 Stream 和消费组

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);//反序列化消息

    protected abstract String payloadIdentifier(T payload);//业务标识（日志用）

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
