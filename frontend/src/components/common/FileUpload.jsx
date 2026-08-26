import { useRef, useState } from "react";
import { Paperclip, X } from "lucide-react";

const MAX_FILE_MB = 5;
const ACCEPTED = ".pdf,.jpg,.jpeg,.png";

/**
 * Carga de documentación respaldatoria.
 *
 * Valida tipo y tamaño en el navegador y devuelve la lista de archivos elegidos.
 * La subida real la resuelve el backend contra S3: acá sólo se arma el adjunto.
 */
export default function FileUpload({
  files = [],
  onChange,
  label = "Documentación respaldatoria",
  hint = "PDF, JPG o PNG. Hasta 5 MB por archivo.",
  maxFiles = 5,
}) {
  const inputRef = useRef(null);
  const [error, setError] = useState(null);

  const onPick = (event) => {
    setError(null);
    const elegidos = Array.from(event.target.files ?? []);

    const pesados = elegidos.filter((f) => f.size > MAX_FILE_MB * 1024 * 1024);
    if (pesados.length > 0) {
      setError(`${pesados[0].name} supera los ${MAX_FILE_MB} MB.`);
    } else if (files.length + elegidos.length > maxFiles) {
      setError(`Podés adjuntar hasta ${maxFiles} archivos.`);
    } else {
      onChange([...files, ...elegidos]);
    }
    // Permite volver a elegir el mismo archivo después de quitarlo.
    event.target.value = "";
  };

  const quitar = (index) => onChange(files.filter((_, i) => i !== index));

  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-[13px] font-medium text-neutral-700">{label}</span>

      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        className="flex items-center justify-center gap-2 rounded-lg border border-dashed border-neutral-300 bg-neutral-50 px-3.5 py-3 text-[13px] font-medium text-neutral-600 transition-colors hover:border-[#D63031]/40 hover:bg-white"
      >
        <Paperclip className="h-4 w-4 text-neutral-400" strokeWidth={2} />
        Adjuntar archivo
      </button>

      <input
        ref={inputRef}
        type="file"
        multiple
        accept={ACCEPTED}
        onChange={onPick}
        aria-label={label}
        className="hidden"
      />

      {files.length > 0 && (
        <ul className="mt-1 flex flex-col gap-1.5">
          {files.map((file, index) => (
            <li
              key={`${file.name}-${index}`}
              className="flex items-center gap-2 rounded-lg border border-neutral-200 bg-white px-3 py-2"
            >
              <Paperclip className="h-3.5 w-3.5 shrink-0 text-neutral-400" strokeWidth={2} />
              <span className="min-w-0 flex-1 truncate text-[13px] text-neutral-700">
                {file.name}
              </span>
              <span className="shrink-0 text-[12px] tabular-nums text-neutral-400">
                {(file.size / 1024).toFixed(0)} KB
              </span>
              <button
                type="button"
                onClick={() => quitar(index)}
                aria-label={`Quitar ${file.name}`}
                className="shrink-0 rounded p-0.5 transition-colors hover:bg-neutral-100"
              >
                <X className="h-3.5 w-3.5 text-neutral-400" strokeWidth={2} />
              </button>
            </li>
          ))}
        </ul>
      )}

      {error ? (
        <p className="text-[12px] text-red-500">{error}</p>
      ) : (
        <p className="text-[12px] text-neutral-400">{hint}</p>
      )}
    </div>
  );
}
