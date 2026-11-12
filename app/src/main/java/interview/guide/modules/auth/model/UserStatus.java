package interview.guide.modules.auth.model;

public enum UserStatus {
    ACTIVE,
    DISABLED,
    /** 注销进行中：已停权、业务数据已删除，等待外部资源清理完成后物理删除 users 行。 */
    DELETING
}
