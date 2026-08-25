import { Navigate, Outlet, useLocation } from "react-router-dom";
import Spinner from "../components/ui/Spinner.jsx";
import { useAuth } from "../context/AuthContext.jsx";

/** Bloquea el área de trabajo y, opcionalmente, restringe por rol. */
export default function ProtectedRoute({ roles }) {
  const { isAuthenticated, initializing, user } = useAuth();
  const location = useLocation();

  if (initializing) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (roles && !roles.includes(user.role)) {
    return <Navigate to="/rentas" replace />;
  }

  return <Outlet />;
}
