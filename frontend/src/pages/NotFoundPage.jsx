import { useNavigate } from "react-router-dom";
import PageHeader from "../components/ui/PageHeader.jsx";
import Button from "../components/common/Button.jsx";
import { useAuth } from "../context/AuthContext.jsx";
import { homePathForRole } from "../config/workspaces.js";

export default function NotFoundPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  return (
    <>
      <PageHeader
        label="Error 404"
        title="No encontramos"
        highlight="esta página"
        description="La sección que buscás no existe o fue movida dentro del área de trabajo."
      />
      <div className="mx-auto max-w-6xl px-5 py-8">
        <Button variant="primary" onClick={() => navigate(homePathForRole(user?.role))}>
          Volver al panel
        </Button>
      </div>
    </>
  );
}
