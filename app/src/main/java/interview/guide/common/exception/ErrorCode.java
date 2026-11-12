package interview.guide.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    
    // ========== 通用错误 1xxx ==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    CONFIG_INVALID(500, "服务配置无效"),
    
    // ========== 简历模块错误 2xxx ==========
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_UPLOAD_FAILED(2003, "简历上传失败"),
    RESUME_DUPLICATE(2004, "简历已存在"),
    RESUME_FILE_TYPE_NOT_SUPPORTED(2006, "不支持的文件类型"),
    RESUME_ANALYSIS_FAILED(2007, "简历分析失败"),
    RESUME_ANALYSIS_NOT_FOUND(2008, "简历分析结果不存在"),
    
    // ========== 面试模块错误 3xxx ==========
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_SESSION_EXPIRED(3002, "面试会话已过期"),
    INTERVIEW_QUESTION_NOT_FOUND(3003, "面试问题不存在"),
    INTERVIEW_ALREADY_COMPLETED(3004, "面试已完成"),
    INTERVIEW_EVALUATION_FAILED(3005, "面试评估失败"),
    INTERVIEW_QUESTION_GENERATION_FAILED(3006, "面试问题生成失败"),
    INTERVIEW_NOT_COMPLETED(3007, "面试尚未完成"),
    INTERVIEW_ANSWER_SAVE_FAILED(3008, "面试答案保存失败"),
    INTERVIEW_ILLEGAL_STATE_TRANSITION(3009, "面试会话状态非法迁移"),
    INTERVIEW_EVALUATION_IN_PROGRESS(3010, "面试评估正在进行中"),
    INTERVIEW_REPORT_SAVE_FAILED(3011, "面试报告保存失败，请稍后重试"),
    
    // ========== 存储模块错误 4xxx ==========
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),
    RESOURCE_CLEANUP_FAILED(4004, "资源清理失败，已进入补偿流程"),
    
    // ========== 导出模块错误 5xxx ==========
    EXPORT_PDF_FAILED(5001, "PDF导出失败"),
    
    // ========== 知识库模块错误 6xxx ==========
    KNOWLEDGE_BASE_NOT_FOUND(6001, "知识库不存在"),
    KNOWLEDGE_BASE_PARSE_FAILED(6002, "知识库文件解析失败"),
    KNOWLEDGE_BASE_QUERY_FAILED(6004, "知识库查询失败"),
    KNOWLEDGE_BASE_DELETE_FAILED(6005, "知识库删除失败"),
    KNOWLEDGE_BASE_VECTOR_DELETE_FAILED(6007, "知识库向量删除失败，已进入补偿流程"),
    KNOWLEDGE_BASE_VECTORIZATION_FAILED(6006, "知识库向量化失败"),
    
    // ========== AI服务错误 7xxx ==========
    AI_SERVICE_UNAVAILABLE(7001, "AI服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(7002, "AI服务响应超时"),
    AI_SERVICE_ERROR(7003, "AI服务调用失败"),
    AI_API_KEY_INVALID(7004, "AI服务密钥无效"),
    AI_RATE_LIMIT_EXCEEDED(7005, "AI服务调用频率超限"),

    // ========== 限流模块错误 8xxx ==========
    RATE_LIMIT_EXCEEDED(8001, "请求过于频繁，请稍后再试"),

    // ========== 面试日程模块错误 9xxx ==========
    INTERVIEW_SCHEDULE_NOT_FOUND(9001, "面试日程不存在"),

    // ========== 语音面试模块错误 10xxx ==========
    VOICE_SESSION_NOT_FOUND(10001, "语音面试会话不存在"),
    VOICE_EVALUATION_FAILED(10004, "语音面试评估失败"),
    VOICE_EVALUATION_NOT_FOUND(10006, "语音面试评估结果不存在"),
    VOICE_ILLEGAL_STATE_TRANSITION(10007, "语音面试会话状态非法迁移"),
    VOICE_SESSION_LEASE_CONFLICT(10008, "语音面试会话已被其他连接占用"),
    VOICE_SESSION_FINALIZE_FAILED(10009, "语音面试会话收尾失败，已进入补偿队列"),
    VOICE_ASR_UNAVAILABLE(10010, "语音识别服务暂不可用，可改用文字提交"),

    // ========== Provider管理模块错误 11xxx ==========
    PROVIDER_NOT_FOUND(11001, "LLM Provider 不存在"),
    PROVIDER_ALREADY_EXISTS(11002, "LLM Provider 已存在"),
    PROVIDER_CONFIG_READ_FAILED(11004, "读取 Provider 配置失败"),
    PROVIDER_CONFIG_WRITE_FAILED(11005, "写入 Provider 配置失败"),
    PROVIDER_TEST_FAILED(11006, "Provider 连通性测试失败"),
    PROVIDER_DEFAULT_CANNOT_DELETE(11007, "默认 Provider 不可删除"),
    MODULE_NOT_FOUND(11008, "模块不存在"),
    VOICE_CONFIG_READ_FAILED(11009, "读取语音服务配置失败"),
    VOICE_CONFIG_WRITE_FAILED(11010, "写入语音服务配置失败"),
    VOICE_CONFIG_TEST_FAILED(11011, "语音服务连通性测试失败"),

    // ========== 认证与用户模块错误 12xxx ==========
    AUTH_INVALID_PHONE(12001, "请输入正确的手机号"),
    AUTH_SMS_SEND_TOO_FREQUENT(12002, "验证码发送过于频繁"),
    AUTH_SMS_DAILY_LIMIT(12003, "今日验证码发送次数已达上限"),
    AUTH_SMS_SEND_FAILED(12004, "验证码发送失败，请稍后重试"),
    AUTH_SMS_CODE_INVALID(12005, "验证码错误或已过期"),
    AUTH_SMS_CODE_ATTEMPTS_EXCEEDED(12006, "验证码尝试次数过多，请重新获取"),
    AUTH_REFRESH_TOKEN_INVALID(12007, "登录状态已失效，请重新登录"),
    AUTH_USER_DISABLED(12008, "账号已被禁用"),
    AUTH_WS_TICKET_INVALID(12009, "WebSocket 登录凭证无效或已过期"),
    AUTH_INTERNAL_ERROR(12010, "认证服务暂时不可用"),
    AUTH_ACCOUNT_DELETE_PHONE_MISMATCH(12011, "手机号与当前账号不一致"),
    AUTH_ACCOUNT_DELETE_LAST_ADMIN(12012, "系统需保留至少一名管理员，无法注销"),
    AUTH_ACCOUNT_DELETE_IN_PROGRESS(12013, "账号注销正在处理中"),
    AUTH_ACCOUNT_DELETE_NO_PHONE(12014, "当前账号未绑定手机号"),

    // ========== 异步任务错误 13xxx ==========
    ASYNC_TASK_ILLEGAL_STATE_TRANSITION(13001, "异步任务状态非法迁移");

    private final Integer code;
    private final String message;
}
