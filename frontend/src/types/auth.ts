export type UserRole = 'USER' | 'ADMIN';

export interface AuthUser {
  id: number;
  maskedPhone: string;
  displayName: string;
  role: UserRole;
}

export interface AuthResponse {
  accessToken: string;
  expiresIn: number;
  user: AuthUser;
}

export interface SendSmsCodeResponse {
  message: string;
  /** 仅本地 mock 短信且开启 APP_AUTH_EXPOSE_DEBUG_CODE 时返回，生产/pnvs 恒为 null。 */
  debugCode?: string | null;
}

export interface LoginNotification {
  id: number;
  deviceLabel: string;
  loginAt: string;
  read: boolean;
  readAt: string | null;
}

export interface LoginNotificationPage {
  items: LoginNotification[];
  unreadCount: number;
}
