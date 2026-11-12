package interview.guide.modules.auth.model;

/**
 * 验证码用途。不同用途的验证码在 Redis 中完全隔离，
 * 防止登录验证码被复用于账号注销等高风险操作。
 */
public enum CodePurpose {
  LOGIN,
  ACCOUNT_DELETION
}
