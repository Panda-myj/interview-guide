import { useState, lazy, Suspense } from 'react';
import oneDark from 'react-syntax-highlighter/dist/esm/styles/prism/one-dark';
import { Check, Copy } from 'lucide-react';
import type { SyntaxHighlighterProps } from 'react-syntax-highlighter';

/**
 * 按需加载 Prism 高亮器与其语言包。
 * 使用 prism-light 而不是完整 prism 构建，避免把全部语言打包进产物体积（FEDGE-002）。
 */
const SyntaxHighlighter = lazy(async () => {
  const [highlighterModule, java, python, javascript, typescript, sql, bash, json, xml, yaml, go, cpp] =
    await Promise.all([
      import('react-syntax-highlighter/dist/esm/prism-light'),
      import('react-syntax-highlighter/dist/esm/languages/prism/java'),
      import('react-syntax-highlighter/dist/esm/languages/prism/python'),
      import('react-syntax-highlighter/dist/esm/languages/prism/javascript'),
      import('react-syntax-highlighter/dist/esm/languages/prism/typescript'),
      import('react-syntax-highlighter/dist/esm/languages/prism/sql'),
      import('react-syntax-highlighter/dist/esm/languages/prism/bash'),
      import('react-syntax-highlighter/dist/esm/languages/prism/json'),
      import('react-syntax-highlighter/dist/esm/languages/prism/markup'),
      import('react-syntax-highlighter/dist/esm/languages/prism/yaml'),
      import('react-syntax-highlighter/dist/esm/languages/prism/go'),
      import('react-syntax-highlighter/dist/esm/languages/prism/cpp'),
    ]);

  const instance = highlighterModule.default;
  instance.registerLanguage('java', java.default);
  instance.registerLanguage('python', python.default);
  instance.registerLanguage('javascript', javascript.default);
  instance.registerLanguage('js', javascript.default);
  instance.registerLanguage('typescript', typescript.default);
  instance.registerLanguage('ts', typescript.default);
  instance.registerLanguage('sql', sql.default);
  instance.registerLanguage('bash', bash.default);
  instance.registerLanguage('shell', bash.default);
  instance.registerLanguage('json', json.default);
  instance.registerLanguage('xml', xml.default);
  instance.registerLanguage('html', xml.default);
  instance.registerLanguage('yaml', yaml.default);
  instance.registerLanguage('yml', yaml.default);
  instance.registerLanguage('go', go.default);
  instance.registerLanguage('cpp', cpp.default);
  instance.registerLanguage('c', cpp.default);

  return { default: instance as unknown as React.ComponentType<SyntaxHighlighterProps> };
});

interface CodeBlockProps {
  language?: string;
  children: string;
}

export default function CodeBlock({ language, children }: CodeBlockProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(children);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('复制失败:', err);
    }
  };

  // 清理代码内容
  const code = children?.trim() || '';

  return (
    <div className="relative group my-3">
      {/* 语言标签和复制按钮 */}
      <div className="flex items-center justify-between px-4 py-2 bg-slate-700 rounded-t-xl border-b border-slate-600">
        <span className="text-xs text-slate-400 font-mono">
          {language || 'code'}
        </span>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1.5 px-2 py-1 text-xs text-slate-400 hover:text-white hover:bg-slate-600 rounded transition-colors"
          title="复制代码"
        >
          {copied ? (
            <>
              <Check className="w-3.5 h-3.5 text-green-400" />
              <span className="text-green-400">已复制</span>
            </>
          ) : (
            <>
              <Copy className="w-3.5 h-3.5" />
              <span>复制</span>
            </>
          )}
        </button>
      </div>

      {/* 代码内容 */}
      <div className="bg-[#282c34] rounded-b-xl text-sm leading-6">
        <Suspense fallback={
          <div className="p-4 text-slate-400 font-mono text-xs">Loading code...</div>
        }>
          <SyntaxHighlighter
            language={language || 'text'}
            style={oneDark}
            customStyle={{
              margin: 0,
              borderTopLeftRadius: 0,
              borderTopRightRadius: 0,
              borderBottomLeftRadius: '0.75rem',
              borderBottomRightRadius: '0.75rem',
              fontSize: '0.875rem',
              lineHeight: '1.5',
            }}
            showLineNumbers={code.split('\n').length > 3}
            wrapLines
          >
            {code}
          </SyntaxHighlighter>
        </Suspense>
      </div>
    </div>
  );
}
