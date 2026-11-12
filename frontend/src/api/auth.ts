import request from './request';
import type {
  AuthResponse,
  AuthUser,
  LoginNotificationPage,
  SendSmsCodeResponse,
} from '../types/auth';

export const authApi = {
  sendCode(phone: string): Promise<SendSmsCodeResponse> {
    return request.post<SendSmsCodeResponse>('/api/auth/sms/send', { phone });
  },

  login(phone: string, code: string): Promise<AuthResponse> {
    return request.post<AuthResponse>('/api/auth/login/sms', { phone, code });
  },

  me(): Promise<AuthUser> {
    return request.get<AuthUser>('/api/auth/me');
  },

  logout(): Promise<void> {
    return request.post<void>('/api/auth/logout');
  },

  listNotifications(page = 0, size = 20): Promise<LoginNotificationPage> {
    return request.get<LoginNotificationPage>('/api/auth/notifications', {
      params: { page, size },
    });
  },

  markNotificationRead(id: number): Promise<void> {
    return request.post<void>(`/api/auth/notifications/${id}/read`);
  },

  sendDeleteCode(): Promise<SendSmsCodeResponse> {
    return request.post<SendSmsCodeResponse>('/api/auth/account/delete/code');
  },

  deleteAccount(phone: string, code: string): Promise<void> {
    return request.post<void>('/api/auth/account/delete', { phone, code });
  },

  createWsTicket(sessionId: number): Promise<void> {
    return request.post<void>('/api/auth/ws-ticket', { sessionId });
  },
};
