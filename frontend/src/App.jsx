import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes, useLocation } from 'react-router';
import { MotionConfig } from 'motion/react';
import { BellRinging, ChartLine, CookingPot, Basket, CalendarDots, Sliders, SignOut } from '@phosphor-icons/react';
import { AuthProvider } from './auth.jsx';
import { useAuth } from './shared.js';
import { Loading, ToastProvider } from './ui.jsx';
import AuthPage from './pages/AuthPage.jsx';
import PantryPage from './pages/PantryPage.jsx';
import AlertsPage from './pages/AlertsPage.jsx';
import PreferencesPage from './pages/PreferencesPage.jsx';
import RecipesPage from './pages/RecipesPage.jsx';
import PlansPage from './pages/PlansPage.jsx';

// Charts are the heaviest dependency, so the dashboard is loaded only when opened.
const DashboardPage = lazy(() => import('./pages/DashboardPage.jsx'));

const NAV = [
  { to: '/pantry', label: 'Pantry', icon: Basket },
  { to: '/alerts', label: 'Expiring', icon: BellRinging },
  { to: '/recipes', label: 'Recipes', icon: CookingPot },
  { to: '/plans', label: 'Meal plan', icon: CalendarDots },
  { to: '/dashboard', label: 'Dashboard', icon: ChartLine },
  { to: '/preferences', label: 'Diet & allergies', icon: Sliders },
];

function SignedIn() {
  const { user, logout } = useAuth();
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Main">
        <span className="brand">PantryChef</span>
        {NAV.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} className="nav-link">
            <Icon size={20} aria-hidden="true" />
            {label}
          </NavLink>
        ))}
        <span className="nav-spacer" />
        <button type="button" className="nav-link btn-link" onClick={logout}>
          <SignOut size={20} aria-hidden="true" />
          Sign out
        </button>
      </nav>
      <main>
        <Suspense fallback={<Loading />}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  );
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<AuthPage mode="login" />} />
      <Route path="/register" element={<AuthPage mode="register" />} />
      <Route element={<SignedIn />}>
        <Route path="/pantry" element={<PantryPage />} />
        <Route path="/alerts" element={<AlertsPage />} />
        <Route path="/recipes" element={<RecipesPage />} />
        <Route path="/plans" element={<PlansPage />} />
        <Route path="/plans/:planId" element={<PlansPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/preferences" element={<PreferencesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/pantry" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <MotionConfig reducedMotion="user">
      <AuthProvider>
        <ToastProvider>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </ToastProvider>
      </AuthProvider>
    </MotionConfig>
  );
}
