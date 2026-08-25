import { useNavigate } from "react-router-dom";
import PageHeader from "../components/ui/PageHeader.jsx";
import Button from "../components/common/Button.jsx";

export default function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <>
      <PageHeader
        label="Error 404"
        title="No encontramos"
        highlight="esta página"
        description="La sección que buscás no existe o fue movida dentro del área de trabajo."
      />
      <div className="mx-auto max-w-6xl px-5 py-8">
        <Button variant="primary" onClick={() => navigate("/rentas")}>
          Volver al panel
        </Button>
      </div>
    </>
  );
}
