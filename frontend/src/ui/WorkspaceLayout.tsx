import type { ReactNode } from 'react';
import { Files, LogOut, ShieldCheck } from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Brand } from './Brand';
import { Button } from './Button';

type Props = Readonly<{
  children: ReactNode;
  title: string;
  filesCount?: number;
  leaving: boolean;
  onSignOut(): void;
}>;

export function WorkspaceLayout({ children, title, filesCount, leaving, onSignOut }: Props) {
  const { user } = useAuth();
  return (
    <div className="workspace">
      <a className="skip-link" href="#main-content">
        Перейти к содержимому
      </a>
      <aside className="sidebar">
        <Brand />
        <span className="sidebar-label">ПРОСТРАНСТВО</span>
        <nav aria-label="Главная навигация">
          <NavLink to="/files" className="nav-item">
            <Files size={20} />
            Мои файлы{filesCount !== undefined && <span>{filesCount}</span>}
          </NavLink>
          <NavLink to="/security" className="nav-item">
            <ShieldCheck size={20} />
            Безопасность
          </NavLink>
        </nav>
        <div className="sidebar-bottom">
          <div className="privacy-card">
            <ShieldCheck size={23} />
            <strong>Личное — остаётся личным</strong>
            <p>Только ваш аккаунт имеет доступ к этим файлам.</p>
          </div>
          <div className="account">
            <span className="avatar">{user?.username.slice(0, 2).toUpperCase()}</span>
            <div>
              <strong>{user?.username}</strong>
              <small>Личный аккаунт</small>
            </div>
            <Button variant="ghost" aria-label="Выйти" title="Выйти" busy={leaving} onClick={onSignOut}>
              <LogOut size={19} />
            </Button>
          </div>
        </div>
      </aside>
      <div className="workspace-body">
        <header className="topbar">
          <span>
            Моё пространство <span className="breadcrumb">/</span> <strong>{title}</strong>
          </span>
          <span className="private-badge">
            <ShieldCheck size={15} />
            Приватный доступ
          </span>
        </header>
        {children}
      </div>
    </div>
  );
}
