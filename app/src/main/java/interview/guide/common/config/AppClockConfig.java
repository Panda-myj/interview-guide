package interview.guide.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 全局时钟约定。
 * <p>
 * 时区约定：数据库与 API 中的「无时区墙钟」一律表示 Asia/Shanghai（无时区墙钟 = Asia/Shanghai）。
 * 业务代码禁止直接调用 LocalDateTime.now() / Instant.now()，统一注入本 Clock：
 * LocalDateTime.now(clock) / clock.instant()，便于测试注入固定时钟。
 */
@Configuration
public class AppClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
