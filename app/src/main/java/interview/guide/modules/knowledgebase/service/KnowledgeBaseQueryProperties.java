package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private History history = new History();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }
    /*查询长度（去空白）	TopK	minScore	策略意图
≤ 4 字（短查询）	20	0.18	口语化短查询歧义大，放宽阈值多召回，靠 LLM 自行筛选
5-12 字（中查询）	12	0.28	中等长度，平衡召回率和精准度
> 12 字（长查询）	8	0.28	查询已足够具体，收窄 TopK 减少噪声*/
    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }
}
