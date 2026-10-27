import { useCallback } from "react";
import { useAuth } from "../../context/AuthContext.jsx";
import useIdleSession from "../../hooks/useIdleSession.js";
import Modal from "./Modal.jsx";
import Button from "./Button.jsx";

export default function SessionTimeoutWarning({ timeoutMs, warningMs }) {
  const { user, logout } = useAuth();
  const expire = useCallback(() => logout({ reason: "inactivity" }), [logout]);
  const { remaining, continueSession } = useIdleSession({ active: user?.id ?? user?.username ?? false, onExpire: expire, timeoutMs, warningMs });
  if (remaining === null) return null;
  const keepWarningFocused = (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      continueSession();
    }
    if (event.key === "Tab") {
      const buttons = [...event.currentTarget.querySelectorAll("button:not(:disabled)")];
      const first = buttons[0], last = buttons.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  };
  return <div className="fixed inset-0 z-[100]" onKeyDownCapture={keepWarningFocused}>
    <Modal open title="Tu sesión está por cerrarse" onClose={continueSession} footer={<>
    <Button variant="secondary" onClick={() => { expire().catch(() => {}); }}>Cerrar sesión</Button>
    <Button autoFocus onClick={continueSession}>Seguir trabajando</Button>
  </>}>
    <p role="status" aria-live="polite">Por inactividad, tu sesión se cerrará en {remaining} segundos. Elegí seguir trabajando para conservarla.</p>
    </Modal>
  </div>;
}
