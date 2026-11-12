package interview.guide.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * com.fasterxml (Jackson 2) ObjectMapper 配置。
 * <p>
 * 仅供显式注入 com.fasterxml ObjectMapper 的少数组件使用
 * （AliyunSmsSender、InterviewParseService、SecurityConfig、ActiveUserFilter、VoiceInterviewWebSocketHandler）；
 * MVC 层序列化由 Spring Boot 4 的 tools.jackson (Jackson 3) 自动配置驱动，与本 bean 无关。
 * 时区约定见 {@link AppClockConfig}：无时区墙钟 = Asia/Shanghai。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
