import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { AlertTriangle, CheckCircle2, Info, X } from 'lucide-react';

export type ToastVariant = 'success' | 'error' | 'info';

export interface ToastOptions {
  variant?: ToastVariant;
  /** 自动关闭毫秒数，0 表示不自动关闭 */
  duration?: number;
}

interface ToastItem {
  id: number;
  message: string;
  variant: ToastVariant;
}

interface ToastContextValue {
  show: (message: string, options?: ToastOptions) => void;
  success: (message: string, options?: Omit<ToastOptions, 'variant'>) => void;
  error: (message: string, options?: Omit<ToastOptions, 'variant'>) => void;
  info: (message: string, options?: Omit<ToastOptions, 'variant'>) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const MAX_VISIBLE = 3;
const DEFAULT_DURATION = 4000;

const variantStyles: Record<ToastVariant, string> = {
  success: 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950 dark:text-emerald-200',
  error: 'border-rose-200 bg-rose-50 text-rose-800 dark:border-rose-800 dark:bg-rose-950 dark:text-rose-200',
  info: 'border-slate-200 bg-white text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100',
};

const variantIcons: Record<ToastVariant, ReactNode> = {
  success: <CheckCircle2 className="w-4 h-4 shrink-0" aria-hidden="true" />,
  error: <AlertTriangle className="w-4 h-4 shrink-0" aria-hidden="true" />,
  info: <Info className="w-4 h-4 shrink-0" aria-hidden="true" />,
};

/**
 * 全局轻提示。
 *
 * 替代阻断式 `alert`：不阻塞用户操作、可自动消失、错误上下文不会丢失（FEDGE-003）。
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const idRef = useRef(0);
  const timersRef = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());

  const dismiss = useCallback((id: number) => {
    setItems((prev) => prev.filter((item) => item.id !== id));
    const timer = timersRef.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timersRef.current.delete(id);
    }
  }, []);

  const show = useCallback(
    (message: string, options?: ToastOptions) => {
      if (!message) {
        return;
      }
      idRef.current += 1;
      const id = idRef.current;
      const variant = options?.variant ?? 'info';
      setItems((prev) => [...prev, { id, message, variant }].slice(-MAX_VISIBLE));

      const duration = options?.duration ?? DEFAULT_DURATION;
      if (duration > 0) {
        const timer = setTimeout(() => dismiss(id), duration);
        timersRef.current.set(id, timer);
      }
    },
    [dismiss],
  );

  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      timers.forEach((timer) => clearTimeout(timer));
      timers.clear();
    };
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({
      show,
      success: (message, options) => show(message, { ...options, variant: 'success' }),
      error: (message, options) => show(message, { ...options, variant: 'error' }),
      info: (message, options) => show(message, { ...options, variant: 'info' }),
    }),
    [show],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        className="fixed top-4 right-4 z-[100] flex w-[min(92vw,380px)] flex-col gap-2"
        role="region"
        aria-label="通知"
      >
        {items.map((item) => (
          <div
            key={item.id}
            role={item.variant === 'error' ? 'alert' : 'status'}
            className={`flex items-start gap-2 rounded-xl border px-4 py-3 text-sm shadow-lg backdrop-blur-sm ${variantStyles[item.variant]}`}
          >
            <span className="mt-0.5">{variantIcons[item.variant]}</span>
            <p className="flex-1 whitespace-pre-line break-words">{item.message}</p>
            <button
              type="button"
              onClick={() => dismiss(item.id)}
              className="rounded p-0.5 opacity-60 transition-opacity hover:opacity-100"
              aria-label="关闭通知"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/**
 * 获取轻提示能力。必须在 {@link ToastProvider} 内使用。
 */
export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast 必须在 ToastProvider 内使用');
  }
  return context;
}
