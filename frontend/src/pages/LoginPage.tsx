import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, Loader2, LockKeyhole, Phone, ShieldCheck, Sparkles } from 'lucide-react';
import { authApi } from '../api/auth';
import { useAuth } from '../auth/AuthProvider';
import { getErrorMessage } from '../api/request';
import { ROUTES } from '../constants/routes';

export default function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [debugCode, setDebugCode] = useState<string | null>(null);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  if (user) {
    return <Navigate to={ROUTES.resumeHistory} replace />;
  }

  const handleSendCode = async () => {
    setError('');
    if (!/^1[3-9]\d{9}$/.test(phone.replace(/\s/g, ''))) {
      setError('请输入正确的 11 位手机号');
      return;
    }
    setSending(true);
    try {
      const response = await authApi.sendCode(phone);
      setDebugCode(response.debugCode ?? null);
      setCountdown(60);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSending(false);
    }
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    if (!/^\d{6}$/.test(code)) {
      setError('请输入 6 位验证码');
      return;
    }
    setSubmitting(true);
    try {
      await login(phone, code);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== ROUTES.login ? from : ROUTES.resumeHistory, { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen grid lg:grid-cols-[1.15fr_0.85fr] bg-slate-950">
      <section className="relative hidden lg:flex flex-col justify-between overflow-hidden p-14 text-white">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_20%_15%,rgba(99,102,241,.45),transparent_38%),radial-gradient(circle_at_75%_70%,rgba(14,165,233,.3),transparent_35%)]" />
        <div className="relative flex items-center gap-3">
          <span className="w-11 h-11 rounded-2xl bg-primary-500 flex items-center justify-center shadow-lg shadow-primary-500/30">
            <Sparkles className="w-6 h-6" />
          </span>
          <div>
            <p className="text-xl font-bold tracking-tight">AI Interview</p>
            <p className="text-sm text-slate-400">智能面试练习平台</p>
          </div>
        </div>
        <div className="relative max-w-xl">
          <p className="text-sm font-semibold uppercase tracking-[0.24em] text-primary-300">Practice smarter</p>
          <h1 className="mt-5 text-5xl font-bold leading-tight">让每一次面试练习<br />都有清晰反馈</h1>
          <p className="mt-6 text-lg leading-8 text-slate-300">
            简历分析、文字与语音模拟面试、知识库问答和个人面试记录，现在都属于你的独立空间。
          </p>
          <div className="mt-10 grid grid-cols-3 gap-4">
            {['专属数据空间', '验证码安全登录', '新设备站内提醒'].map((item) => (
              <div key={item} className="rounded-2xl border border-white/10 bg-white/5 p-4 text-sm text-slate-200">
                <ShieldCheck className="mb-3 h-5 w-5 text-primary-300" />
                {item}
              </div>
            ))}
          </div>
        </div>
        <p className="relative text-sm text-slate-500">AI Interview Platform</p>
      </section>

      <main className="flex items-center justify-center bg-slate-50 px-6 py-12 dark:bg-slate-900">
        <div className="w-full max-w-md">
          <div className="mb-9 lg:hidden flex items-center gap-3">
            <span className="w-10 h-10 rounded-xl bg-primary-500 text-white flex items-center justify-center">
              <Sparkles className="w-5 h-5" />
            </span>
            <span className="text-lg font-bold text-slate-900 dark:text-white">AI Interview</span>
          </div>
          <p className="text-sm font-semibold text-primary-600">欢迎回来</p>
          <h2 className="mt-2 text-3xl font-bold text-slate-900 dark:text-white">手机号登录</h2>
          <p className="mt-3 text-sm leading-6 text-slate-500 dark:text-slate-400">
            未注册手机号验证成功后将自动创建普通用户账号。
          </p>

          <form onSubmit={handleSubmit} className="mt-8 space-y-5">
            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">手机号</span>
              <div className="flex items-center rounded-xl border border-slate-200 bg-white px-4 focus-within:border-primary-500 focus-within:ring-4 focus-within:ring-primary-500/10 dark:border-slate-700 dark:bg-slate-800">
                <Phone className="h-5 w-5 text-slate-400" />
                <input
                  value={phone}
                  onChange={(event) => setPhone(event.target.value.replace(/[^\d+]/g, '').slice(0, 13))}
                  inputMode="tel"
                  autoComplete="tel"
                  placeholder="请输入 11 位手机号"
                  className="h-12 flex-1 bg-transparent px-3 text-slate-900 outline-none dark:text-white"
                />
              </div>
            </label>

            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">验证码</span>
              <div className="flex gap-3">
                <div className="flex flex-1 items-center rounded-xl border border-slate-200 bg-white px-4 focus-within:border-primary-500 focus-within:ring-4 focus-within:ring-primary-500/10 dark:border-slate-700 dark:bg-slate-800">
                  <LockKeyhole className="h-5 w-5 text-slate-400" />
                  <input
                    value={code}
                    onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    placeholder="6 位验证码"
                    className="h-12 w-full bg-transparent px-3 tracking-[0.25em] text-slate-900 outline-none dark:text-white"
                  />
                </div>
                <button
                  type="button"
                  onClick={handleSendCode}
                  disabled={sending || countdown > 0}
                  className="w-32 rounded-xl border border-primary-200 bg-primary-50 text-sm font-semibold text-primary-600 transition hover:bg-primary-100 disabled:cursor-not-allowed disabled:opacity-60 dark:border-primary-800 dark:bg-primary-900/30 dark:text-primary-300"
                >
                  {sending ? <Loader2 className="mx-auto h-5 w-5 animate-spin" /> : countdown > 0 ? `${countdown}s` : '获取验证码'}
                </button>
              </div>
            </label>

            {debugCode && (
              <button
                type="button"
                onClick={() => setCode(debugCode)}
                className="w-full rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-left text-sm text-amber-700 transition hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-300 dark:hover:bg-amber-950/50"
              >
                开发模式验证码{' '}
                <span className="font-mono text-base font-semibold tracking-[0.2em]">{debugCode}</span>
                <span className="ml-2 text-xs opacity-70">点击自动填入</span>
              </button>
            )}

            {error && <p className="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-600 dark:bg-red-950/30 dark:text-red-300">{error}</p>}

            <button
              type="submit"
              disabled={submitting}
              className="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-primary-600 font-semibold text-white shadow-lg shadow-primary-600/20 transition hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? <Loader2 className="h-5 w-5 animate-spin" /> : <>登录并继续 <ArrowRight className="h-5 w-5" /></>}
            </button>
          </form>
          <p className="mt-6 text-center text-xs leading-5 text-slate-400">
            登录即表示你同意平台仅将手机号用于身份认证和安全提醒。
          </p>
        </div>
      </main>
    </div>
  );
}
