package interview.guide.common.aspect;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 限流 AOP 切面
 * 支持可重复注解，逐条执行独立的限流规则，任一规则不通过即拒绝
 *
 * AOP 限流切面，用 Redis + Lua 脚本实现滑动窗口限流
 * ⭐⭐ 学 AOP 切面编程 + Redis Lua
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;

    private static final String LUA_SCRIPT;
    private String luaScriptSha;
    private RScript rScript;
//    static {} 块在类加载时读取 Lua 文件，IO 操作只发生一次，不影响运行时性能
    static {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
            LUA_SCRIPT = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载限流 Lua 脚本失败", e);
        }
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        rScript = redissonClient.getScript(StringCodec.INSTANCE);
        loadScript();
    }

    // 启动时加载脚本到 Redis，拿到 SHA1 摘要
    private void loadScript() {
        this.luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
    }

    /**
     * 环绕通知：拦截带 @RateLimit 或 @RateLimit.Container 注解的方法
     */
    @Around("@annotation(interview.guide.common.annotation.RateLimit) || " +
            "@annotation(interview.guide.common.annotation.RateLimit.Container)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();
//可重复注解	getAnnotationsByType 支持同一方法配置多条限流规则（如：全局 1000/s + 单用户 10/s），任一超限即拒绝
        RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
        long nowMs = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        for (RateLimit rule : rules) {
            long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
            String key = generateKey(className, methodName, rule.dimension());

            Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());

            if (result == null || result == 0) {
                return handleRateLimitExceeded(joinPoint, rule, key);
            }
        }

        return joinPoint.proceed();
    }
/*
* Spring AOP 共提供 5 种通知注解，按执行时机排列如下：
表格
注解	执行时机	能否阻止目标方法	能否获取返回值	典型场景
@Before	目标方法之前	❌	❌	参数校验、日志打印
@After	目标方法之后（无论正常/异常）	❌	❌	资源清理（类似 finally）
@AfterReturning	目标方法正常返回后	❌	✅	返回值脱敏、审计记录
@AfterThrowing	目标方法抛出异常后	❌	✅（异常对象）	异常告警、错误码转换
@Around	包裹整个方法执行	✅	✅	缓存、锁、监控、重试
💡 选择原则：能用 @Before / @AfterReturning 解决的，就不要用 @Around。@Around 虽然万能，但侵入性最强、调试难度最高、性能开销略大。只有在需要控制流程或同时访问入参和返回值时才使用它。
* */


// 为什么用 Lua 脚本？
// 限流需要“读取当前窗口计数 → 判断是否超限 → 写入新记录”三步操作。如果这三步在 Java 端分开执行，并发下必然出现竞态条件（Race Condition）。
// Lua 脚本在 Redis 中是单线程原子执行的，从根本上保证了限流的准确性。
    private Long executeRateLimitScript(String key, long nowMs, String requestId, long intervalMs, double count) {
        List<Object> keysList = Collections.singletonList(key);
        Object[] args = {
                String.valueOf(nowMs),
                String.valueOf(1),
                String.valueOf(intervalMs),
                String.valueOf(count),
                requestId
        };

        /*首次：scriptLoad 将完整 Lua 脚本发送到 Redis 并缓存，返回 SHA1。
        后续：evalSha 只传 SHA1，Redis 从内存缓存中匹配执行。
        收益：避免每次限流都传输几 KB 的脚本文本，大幅降低网络 IO 开销。对于高频限流接口，这是必要的性能优化。*/
        try {
            // 执行时用 SHA1 代替完整脚本传输
            Object resultObj = rScript.evalSha(
                    RScript.Mode.READ_WRITE,
                    luaScriptSha,
                    RScript.ReturnType.VALUE,
                    keysList,
                    args
            );
            return convertToLong(resultObj);
        } catch (org.redisson.client.RedisException e) {
            // Redis 重启后脚本缓存丢失，重新加载并重试
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                loadScript();
                Object resultObj = rScript.evalSha(
                        RScript.Mode.READ_WRITE,
                        luaScriptSha,
                        RScript.ReturnType.VALUE,
                        keysList,
                        args
                );
                return convertToLong(resultObj);
            }
            throw e;
        }
    }

    private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
//        在 Java 14+ 引入的增强 switch 语法中，使用箭头标签 -> 时，不需要手动写 break。它执行完右侧的表达式后会自动终止，不会掉落到下一个 case。
        return switch (unit) {
            case MILLISECONDS -> interval;
            case SECONDS -> interval * 1000;
            case MINUTES -> interval * 60 * 1000;
            case HOURS -> interval * 3600 * 1000;
            case DAYS -> interval * 86400 * 1000;
        };
    }
/*
如果你在 -> 后面跟的是花括号 { }（代码块），那么：

如果是 switch 语句（无返回值），可以用 break; 来提前跳出块，但块末尾不需要写 break（执行完自然结束）。

如果是 switch 表达式（有返回值），则必须用 yield 替代 break 来返回值。

例如：

java
// 表达式（有返回值），必须用 yield
long result = switch (unit) {
    case DAYS -> {
        long calc = interval * 86400 * 1000;
        yield calc;  // 这里不是 break，是 yield
    }
    // ...
};*/

    private Long convertToLong(Object obj) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("无法将字符串转换为Long: {}", obj);
                return null;
            }
        }
        log.warn("不支持的对象类型转换为Long: {}", obj != null ? obj.getClass().getName() : "null");
        return null;
    }

    private String generateKey(String className, String methodName, RateLimit.Dimension dimension) {
        String hashTag = "{" + className + ":" + methodName + "}";
        String keyPrefix = "ratelimit:" + hashTag;

        return switch (dimension) {
            case GLOBAL -> keyPrefix + ":global";
            case IP -> keyPrefix + ":ip:" + getClientIp();
            case USER -> keyPrefix + ":user:" + getCurrentUserId();
        };
    }

    private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String key)
            throws Throwable {
        String methodName = joinPoint.getSignature().getName();

        if (rateLimit.fallback() != null && !rateLimit.fallback().isEmpty()) {
            try {
                Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
                if (fallbackMethod != null) {
                    log.debug("限流触发，执行降级方法: {}.{} -> {}",
                            joinPoint.getTarget().getClass().getSimpleName(),
                            methodName,
                            rateLimit.fallback());
                    if (fallbackMethod.getParameterCount() > 0) {
                        return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
                    } else {
                        return fallbackMethod.invoke(joinPoint.getTarget());
                    }
                }
            } catch (Exception e) {
                log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
            }
        }

        log.debug("限流触发，拒绝请求: key={}, count={} per {} {}",
                key, rateLimit.count(), rateLimit.interval(), rateLimit.timeUnit());
        throw new RateLimitExceededException("请求过于频繁，请稍后再试");
    }

    private Method findFallbackMethod(ProceedingJoinPoint joinPoint, String fallbackName) {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] parameterTypes = signature.getParameterTypes();

        try {
            Method method = targetClass.getDeclaredMethod(fallbackName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            try {
                Method method = targetClass.getDeclaredMethod(fallbackName);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ex) {
                log.warn("未找到降级方法: {}.{} (需无参或参数列表一致)",
                        targetClass.getSimpleName(), fallbackName);
                return null;
            }
        }
    }

    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
//        “多级降级获取真实客户端 IP”
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip != null ? ip : "unknown";
    }

    private String getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "anonymous";
        }

        HttpServletRequest request = attributes.getRequest();

        Object userId = request.getAttribute("userId");
        if (userId != null) {
            return userId.toString();
        }

        userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return userId.toString();
        }

        return "anonymous";
    }
}

//潜在改进点
//getClientIp() 的安全风险：如前所述，X-Forwarded-For 可被伪造。若限流用于安全防护，应改为只信任反向代理设置的特定 Header。
//Fallback 反射性能：每次限流触发都做反射查找。可加一个 ConcurrentHashMap<Method+FallbackName, Method> 缓存，避免重复反射。
//Lua 脚本内容不可见：当前代码未展示 rate_limit_single.lua 的具体实现。理解限流算法（滑动窗口 vs 令牌桶 vs 漏桶）的关键在 Lua 脚本中，建议结合脚本一起阅读。
//getCurrentUserId 耦合度高：硬编码了 userId 属性和 X-User-Id Header。更好的做法是注入一个 UserIdExtractor 策略接口，适配不同的认证体系。
