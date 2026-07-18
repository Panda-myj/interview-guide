package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

//跨域、JSON 序列化、S3 存储等配置
@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private String allowedOrigins = "http://localhost:5173";
}
