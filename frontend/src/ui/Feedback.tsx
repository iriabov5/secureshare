import type { ReactNode } from 'react';
import { AlertCircle, CheckCircle2, LoaderCircle } from 'lucide-react';
import { Brand } from './Brand';

export function Message({
  children,
  tone = 'error',
}: Readonly<{ children: ReactNode; tone?: 'error' | 'success' }>) {
  const Icon = tone === 'error' ? AlertCircle : CheckCircle2;
  return (
    <output className={`message message--${tone}`} aria-live={tone === 'error' ? 'assertive' : 'polite'}>
      <Icon size={18} aria-hidden="true" />
      <span>{children}</span>
    </output>
  );
}
export function LoadingScreen() {
  return (
    <main className="boot-screen">
      <Brand />
      <output className="boot-screen__status">
        <LoaderCircle className="spin" size={20} aria-hidden="true" />
        Проверяем сессию…
      </output>
    </main>
  );
}
