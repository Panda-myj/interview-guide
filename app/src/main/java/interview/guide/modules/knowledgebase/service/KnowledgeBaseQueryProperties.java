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
    private Context context = new Context();
    private Retrieval retrieval = new Retrieval();
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

    /**
     * 注入上下文的上限控制：防止一次检索命中过多/过长的文档把提示词撑爆，
     * 同时限制用户问题与历史消息长度，避免超长输入放大 token 费用。
     */
    @Data
    public static class Context {
        /** 最多注入多少段文档（按检索顺序截断）。 */
        private int maxDocs = 6;
        /** 单段文档最大字符数。 */
        private int maxCharsPerDoc = 1200;
        /** 所有文档合计最大字符数。 */
        private int maxTotalChars = 6000;
        /** 用户问题最大字符数，超出直接截断。 */
        private int maxQuestionChars = 2000;
        /** 单条历史消息最大字符数。 */
        private int maxHistoryMessageChars = 800;
    }

    /**
     * 检索与排序策略。默认 {@code vector} 与升级前完全一致，改回该值即完成回滚。
     */
    @Data
    public static class Retrieval {
        /** vector | hybrid | vector_rerank | hybrid_rerank，未知值按 vector 处理。 */
        private String mode = "vector";
        /** 候选池上限，用于给重排留空间的同时封住成本。 */
        private int maxCandidates = 50;
        /** 重排预算（毫秒）：向量检索已耗时超过该值则跳过重排，直接按向量顺序返回。 */
        private long timeoutMs = 3000;
    }
}
