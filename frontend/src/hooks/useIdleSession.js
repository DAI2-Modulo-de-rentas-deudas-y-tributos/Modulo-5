import { useCallback, useEffect, useRef, useState } from "react";

const configuredMinutes = Number(import.meta.env.VITE_IDLE_TIMEOUT_MINUTES ?? 15);
const configuredWarning = Number(import.meta.env.VITE_IDLE_WARNING_SECONDS ?? 60);
export const IDLE_TIMEOUT_MS = (Number.isFinite(configuredMinutes) && configuredMinutes > 0 ? configuredMinutes : 15) * 60_000;
export const IDLE_WARNING_MS = Math.min(IDLE_TIMEOUT_MS / 2, (Number.isFinite(configuredWarning) && configuredWarning > 0 ? configuredWarning : 60) * 1000);

export default function useIdleSession({ active, onExpire, timeoutMs = IDLE_TIMEOUT_MS, warningMs = IDLE_WARNING_MS }) {
  const lastActivity = useRef(Date.now());
  const expireRef = useRef(onExpire);
  const expired = useRef(false);
  const [remaining, setRemaining] = useState(null);
  const [revision, setRevision] = useState(0);
  expireRef.current = onExpire;
  const continueSession = useCallback(() => {
    // Un aviso suspendido en una pestaña oculta no puede revivir una sesión ya vencida.
    if (Date.now() - lastActivity.current >= timeoutMs) return;
    lastActivity.current = Date.now();
    setRemaining(null);
    setRevision((value) => value + 1);
  }, [timeoutMs]);
  useEffect(() => {
    if (!active) { setRemaining(null); return undefined; }
    expired.current = false;
    lastActivity.current = Date.now();
  }, [active]);
  useEffect(() => {
    if (!active) return undefined;
    let timer;
    const inspect = () => {
      clearTimeout(timer);
      const left = timeoutMs - (Date.now() - lastActivity.current);
      if (left <= 0) {
        if (!expired.current) {
          expired.current = true;
          setRemaining(null);
          Promise.resolve(expireRef.current()).catch(() => {});
        }
        return;
      }
      const warning = left <= warningMs;
      setRemaining(warning ? Math.ceil(left / 1000) : null);
      timer = setTimeout(inspect, warning ? Math.min(left, 1000) : Math.max(1, left - warningMs));
    };
    const activity = (event) => {
      const idleFor = Date.now() - lastActivity.current;
      if (event.type === "pointermove" && idleFor < 1000) return;
      if (idleFor >= timeoutMs - warningMs) { inspect(); return; }
      lastActivity.current = Date.now();
      inspect();
    };
    const events = ["pointermove", "pointerdown", "keydown", "touchstart", "wheel"];
    for (const name of events) window.addEventListener(name, activity, { passive: true });
    document.addEventListener("visibilitychange", inspect);
    window.addEventListener("focus", inspect);
    inspect();
    return () => {
      clearTimeout(timer);
      for (const name of events) window.removeEventListener(name, activity);
      document.removeEventListener("visibilitychange", inspect);
      window.removeEventListener("focus", inspect);
    };
  }, [active, revision, timeoutMs, warningMs]);
  return { remaining, continueSession };
}
