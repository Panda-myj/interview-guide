package interview.guide.modules.auth.model;

/**
 * 身份来源。手机号不再是唯一身份类型，WECHAT 仅为扩展位，当前未实现登录。
 */
public enum IdentityProvider {
  PHONE,
  WECHAT
}
