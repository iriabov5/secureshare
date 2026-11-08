import { Navigate, Route, Routes, Link } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { AuthPage } from './auth/AuthPage';
import { SecurityPage } from './security/SecurityPage';
import { FilesPage } from './files/FilesPage';
import { LoadingScreen, Message } from './ui/Feedback';
import { Button } from './ui/Button';

export function App() {
  const { user, status, error, retry } = useAuth();
  if (status === 'loading') return <LoadingScreen />;
  if (status === 'error')
    return (
      <main className="boot-screen">
        <Message>{error}</Message>
        <Button onClick={retry}>Попробовать снова</Button>
      </main>
    );
  return (
    <Routes>
      <Route
        path="/login"
        element={user ? <Navigate to="/files" replace /> : <AuthPage key="login" mode="login" />}
      />
      <Route
        path="/register"
        element={user ? <Navigate to="/files" replace /> : <AuthPage key="register" mode="register" />}
      />
      <Route path="/files" element={user ? <FilesPage key={user.id} /> : <Navigate to="/login" replace />} />
      <Route path="/security" element={user ? <SecurityPage /> : <Navigate to="/login" replace />} />
      <Route path="/" element={<Navigate to="/files" replace />} />
      <Route path="/app" element={<Navigate to="/files" replace />} />
      <Route
        path="*"
        element={
          <main className="boot-screen">
            <h1>Страница не найдена</h1>
            <Link to="/files">К моим файлам</Link>
          </main>
        }
      />
    </Routes>
  );
}
