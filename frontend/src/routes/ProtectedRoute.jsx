import { Navigate, Outlet, useLocation } from "react-router-dom";
import Spinner from "../components/ui/Spinner.jsx";
import { useAuth } from "../context/AuthContext.jsx";
import { homePathForRole } from "../config/workspaces.js";
import Alert from "../components/ui/Alert.jsx";

/** Bloquea el área de trabajo y, opcionalmente, restringe por rol. */
export default function ProtectedRoute({ roles }) {
  const { isAuthenticated, initializing, user, hasRole } = useAuth();
  const location = useLocation();

  if (initializing) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}${location.hash}` }} />;
  }

  // Quien entra a un área que no le corresponde vuelve al panel de la suya.
  if (roles && !hasRole(...roles)) {
    if (roles.includes(user.role)) return <Alert variant="error" title="Sin permisos">Tu cuenta no tiene permisos para acceder a esta sección.</Alert>;
    return <Navigate to={homePathForRole(user.role)} replace />;
  }

  return <Outlet />;
}
