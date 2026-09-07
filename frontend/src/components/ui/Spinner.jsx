export default function Spinner({ size = "md", className = "" }) {
  const sizes = {
    sm: "h-4 w-4 border-2",
    md: "h-6 w-6 border-2",
    lg: "h-8 w-8 border-[3px]",
  };

  return (
    <div
      className={`${sizes[size]} rounded-full border-neutral-200 border-t-[#D63031] animate-spin ${className}`}
      role="status"
      aria-label="Cargando"
    />
  );
}
