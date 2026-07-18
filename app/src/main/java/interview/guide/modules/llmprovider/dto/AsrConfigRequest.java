package interview.guide.modules.llmprovider.dto;

public record AsrConfigRequest(
    String url,
    String model,
    String apiKey,
    String language,
    String format,
    Integer sampleRate,
    Boolean enableTurnDetection,
    String turnDetectionType,
    Float turnDetectionThreshold,
    Integer turnDetectionSilenceDurationMs
) {}
//record 是 Java 14 引入（Java 16 正式转正）的一种不可变数据载体类。它的核心设计哲学是：当你只想表达“纯粹的数据”时，不应该被迫写一堆样板代码。
//简单来说：class 是通用的“行为+状态”容器，而 record 是专门为“只读数据聚合”设计的语法糖。