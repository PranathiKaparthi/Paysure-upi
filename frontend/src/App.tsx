import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { DashboardPage } from './pages/DashboardPage'
import { HistoryPage } from './pages/HistoryPage'
import { LabPage } from './pages/LabPage'
import { LoginPage } from './pages/LoginPage'
import { MonitoringPage } from './pages/MonitoringPage'
import { PaymentDetailPage } from './pages/PaymentDetailPage'
import { ReconciliationPage } from './pages/ReconciliationPage'
import { RequestMoneyPage } from './pages/RequestMoneyPage'
import { RiskAlertsPage } from './pages/RiskAlertsPage'
import { SendPaymentPage } from './pages/SendPaymentPage'
import { WebhooksPage } from './pages/WebhooksPage'

/** UI convenience only: the backend enforces ADMIN_DEMO on every /api/admin/** endpoint. */
function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  return user?.role === 'ADMIN_DEMO' ? (
    <>{children}</>
  ) : (
    <div className="card text-sm text-slate-600">This page is for demo administrators (ADMIN_DEMO). Sign in as the lab admin to use it.</div>
  )
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  return user ? <>{children}</> : <Navigate to="/" replace />
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/send" element={<SendPaymentPage />} />
        <Route path="/request" element={<RequestMoneyPage />} />
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/payments/:id" element={<PaymentDetailPage />} />
 <Route path="/webhooks" element={<WebhooksPage />} />
        <Route path="/risk" element={<RiskAlertsPage />} />
        <Route path="/monitoring" element={<RequireAdmin><MonitoringPage /></RequireAdmin>} />
        <Route path="/lab" element={<RequireAdmin><LabPage /></RequireAdmin>} />
        <Route path="/reconciliation" element={<RequireAdmin><ReconciliationPage /></RequireAdmin>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  )
}
