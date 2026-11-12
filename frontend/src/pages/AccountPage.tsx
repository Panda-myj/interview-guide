import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Loader2, ShieldAlert, Trash2, X } from 'lucide-react';
import { authApi } from '../api/auth';
import { clearAuthSession } from '../auth/session';
import { useAuth } from '../auth/AuthProvider';
import { getErrorMessage } from '../api/request';
import { ROUTES } from '../constants/routes';
import type { LoginNotificationPage } from '../types/auth';

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

export default function AccountPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [notifications, setNotifications] = useState<LoginNotificationPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [readError, setReadError] = useState('');

  const [dialogOpen, setDialogOpen] = useState(false);
  const [stage, setStage] = useState<'form' | 'confirm'>('form');
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const [debugCode, setDebugCode] = useState<string | null>(null);

  const loadNotifications = useCallback(async () => {
    setLoading(true);
    try {
      setNotifications(await authApi.listNotifications());
      setLoadError('');
    } catch (err) {
      setLoadError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadNotifications(); }, [loadNotifications]);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  const handleMarkRead = async (id: number) => {
    setReadError('');
    try {
      await authApi.markNotificationRead(id);
      await loadNotifications();
    } catch (err) {
      setReadError(getErrorMessage(err));
    }
  };

  // 后端 MaskUtil.maskPhone 保留前 3 后 4，而库里手机号是 E.164（+8613800138000），
  // 掩码前 3 位其实是 "+86"，所以只能拿后 4 位比对。此处仅作体验提示，
  // 真正的手机号归属校验在 AccountDeletionService 里完成。
  const boundPhoneTail = user?.maskedPhone && /^\d{4}$/.test(user.maskedPhone.slice(-4))
    ? user.maskedPhone.slice(-4)
    : null;

  const phoneMismatch = () => {
    if (boundPhoneTail && !phone.replace(/\s/g, '').endsWith(boundPhoneTail)) {
      return `请输入当前账号绑定的手机号（${user?.maskedPhone}）`;
    }
    return null;
  };

  const handleSendDeleteCode = async () => {
    setDeleteError('');
    if (!/^1[3-9]\d{9}$/.test(phone.replace(/\s/g, ''))) {
      setDeleteError('请输入账号绑定的 11 位手机号');
      return;
    }
    const mismatch = phoneMismatch();
    if (mismatch) {
      setDeleteError(mismatch);
      return;
    }
    setSending(true);
    try {
      const response = await authApi.sendDeleteCode();
      setDebugCode(response.debugCode ?? null);
      setCountdown(60);
    } catch (err) {
      setDeleteError(getErrorMessage(err));
    } finally {
      setSending(false);
    }
  };

  const handleDelete = async () => {
    setDeleteError('');
    if (stage === 'form') {
      const mismatch = phoneMismatch();
      if (mismatch) {
        setDeleteError(mismatch);
        return;
      }
      if (!/^\d{6}$/.test(code)) {
        setDeleteError('请输入 6 位注销验证码');
        return;
      }
      setStage('confirm');
      return;
    }
    setSubmitting(true);
    try {
      await authApi.deleteAccount(phone, code);
      clearAuthSession();
      navigate(ROUTES.login, { replace: true });
    } catch (err) {
      setDeleteError(getErrorMessage(err));
      setStage('form');
    } finally {
      setSubmitting(false);
    }
  };

  const closeDialog = () => {
    setDialogOpen(false);
    setStage('form');
    setPhone('');
    setCode('');
    setDeleteError('');
    setDebugCode(null);
  };

  return (
    <div className="mx-auto max-w-4xl space-y-8">
      <header>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">账号与安全</h1>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          查看新设备登录提醒，管理账号数据。
        </p>
      </header>

      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-900">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Bell className="h-5 w-5 text-primary-500" />
            <h2 className="text-lg font-semibold text-slate-900 dark:text-white">登录提醒</h2>
            {notifications && notifications.unreadCount > 0 && (
              <span className="rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-600 dark:bg-red-950/30 dark:text-red-300">
                {notifications.unreadCount} 条未读
              </span>
            )}
          </div>
          <button
            type="button"
            onClick={() => void loadNotifications()}
            disabled={loading}
            className="text-sm text-primary-600 hover:text-primary-700 disabled:opacity-60"
          >
            刷新
          </button>
        </div>

        {(loadError || readError) && (
          <p className="mt-4 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-600 dark:bg-red-950/30 dark:text-red-300">
            {loadError || readError}
          </p>
        )}

        {loading ? (
          <div className="flex justify-center py-10"><Loader2 className="h-6 w-6 animate-spin text-primary-500" /></div>
        ) : notifications && notifications.items.length > 0 ? (
          <ul className="mt-5 divide-y divide-slate-100 dark:divide-slate-800">
            {notifications.items.map((item) => (
              <li key={item.id} className="flex items-center justify-between gap-4 py-3">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-slate-800 dark:text-slate-100">
                    {item.read ? '' : '● '}新设备登录：{item.deviceLabel}
                  </p>
                  <p className="mt-1 text-xs text-slate-400">{formatTime(item.loginAt)}</p>
                </div>
                {item.read ? (
                  <span className="shrink-0 text-xs text-slate-400">已读</span>
                ) : (
                  <button
                    type="button"
                    onClick={() => void handleMarkRead(item.id)}
                    className="shrink-0 rounded-lg border border-primary-200 px-3 py-1.5 text-xs font-medium text-primary-600 hover:bg-primary-50 dark:border-primary-800 dark:text-primary-300"
                  >
                    标记已读
                  </button>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-6 text-sm text-slate-400">暂无登录提醒。</p>
        )}
      </section>

      <section className="rounded-2xl border border-red-200 bg-white p-6 shadow-sm dark:border-red-900/50 dark:bg-slate-900">
        <div className="flex items-center gap-3">
          <ShieldAlert className="h-5 w-5 text-red-500" />
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white">注销账号</h2>
        </div>
        <p className="mt-3 text-sm leading-6 text-slate-500 dark:text-slate-400">
          注销后，账号、简历、知识库、面试记录、向量数据与上传文件将被永久删除，操作不可恢复。
          注销需要短信验证码二次确认，完成后该手机号可重新注册。
        </p>
        <button
          type="button"
          onClick={() => setDialogOpen(true)}
          className="mt-5 inline-flex items-center gap-2 rounded-xl border border-red-200 px-4 py-2.5 text-sm font-semibold text-red-600 transition hover:bg-red-50 dark:border-red-900 dark:text-red-300 dark:hover:bg-red-950/30"
        >
          <Trash2 className="h-4 w-4" />
          注销账号
        </button>
      </section>

      {dialogOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/50 px-4">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl dark:bg-slate-900">
            <div className="flex items-start justify-between">
              <h3 className="text-lg font-semibold text-slate-900 dark:text-white">
                {stage === 'form' ? '注销账号验证' : '最后确认'}
              </h3>
              <button type="button" onClick={closeDialog} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" title="关闭">
                <X className="h-5 w-5" />
              </button>
            </div>

            {stage === 'form' ? (
              <form onSubmit={(event) => { event.preventDefault(); void handleDelete(); }} className="mt-5 space-y-4">
                <p className="text-sm leading-6 text-slate-500 dark:text-slate-400">
                  请输入账号绑定的手机号与注销专用验证码。此操作会永久删除你的全部数据，不可恢复。
                </p>
                <label className="block">
                  <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">手机号</span>
                  <input
                    value={phone}
                    onChange={(event) => setPhone(event.target.value.replace(/[^\d]/g, '').slice(0, 11))}
                    inputMode="tel"
                    placeholder="账号绑定的 11 位手机号"
                    className="h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-sm outline-none focus:border-primary-500 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                  />
                  {boundPhoneTail && (
                    <span className="mt-1.5 block text-xs text-slate-400 dark:text-slate-500">
                      当前账号绑定手机号：{user?.maskedPhone}，请输入完整的 11 位号码
                    </span>
                  )}
                </label>
                <label className="block">
                  <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">注销验证码</span>
                  <div className="flex gap-2">
                    <input
                      value={code}
                      onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
                      inputMode="numeric"
                      placeholder="6 位验证码"
                      className="h-11 min-w-0 flex-1 rounded-xl border border-slate-200 bg-white px-3 tracking-widest outline-none focus:border-primary-500 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                    />
                    <button
                      type="button"
                      onClick={() => void handleSendDeleteCode()}
                      disabled={sending || countdown > 0}
                      className="w-28 shrink-0 rounded-xl border border-primary-200 text-sm font-medium text-primary-600 hover:bg-primary-50 disabled:opacity-60 dark:border-primary-800 dark:text-primary-300"
                    >
                      {sending ? <Loader2 className="mx-auto h-4 w-4 animate-spin" /> : countdown > 0 ? `${countdown}s` : '获取验证码'}
                    </button>
                  </div>
                </label>
                {debugCode && (
                  <button
                    type="button"
                    onClick={() => setCode(debugCode)}
                    className="w-full rounded-lg border border-amber-200 bg-amber-50 px-3 py-2.5 text-left text-sm text-amber-700 transition hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-300 dark:hover:bg-amber-950/50"
                  >
                    开发模式验证码{' '}
                    <span className="font-mono text-base font-semibold tracking-[0.2em]">{debugCode}</span>
                    <span className="ml-2 text-xs opacity-70">点击自动填入</span>
                  </button>
                )}
                {deleteError && <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-950/30 dark:text-red-300">{deleteError}</p>}
                <button
                  type="submit"
                  className="h-11 w-full rounded-xl bg-red-600 text-sm font-semibold text-white hover:bg-red-700"
                >
                  下一步
                </button>
              </form>
            ) : (
              <div className="mt-5 space-y-5">
                <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm leading-6 text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300">
                  确认永久注销？账号、简历、知识库、面试记录及所有上传文件将立即删除，且无法恢复。
                </div>
                {deleteError && <p className="text-sm text-red-600 dark:text-red-300">{deleteError}</p>}
                <div className="flex gap-3">
                  <button type="button" onClick={() => setStage('form')} className="h-11 flex-1 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 dark:border-slate-700 dark:text-slate-300">
                    返回
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleDelete()}
                    disabled={submitting}
                    className="flex h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-red-600 text-sm font-semibold text-white hover:bg-red-700 disabled:opacity-60"
                  >
                    {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
                    确认永久注销
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
