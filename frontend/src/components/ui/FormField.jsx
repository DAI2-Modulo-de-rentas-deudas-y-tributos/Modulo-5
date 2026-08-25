export default function FormField({
  label,
  name,
  type = "text",
  placeholder,
  value,
  onChange,
  error,
  required = false,
  options = [],
  disabled = false,
}) {
  const baseClasses =
    "w-full rounded-lg border bg-neutral-50 px-3.5 py-2.5 text-[14px] text-neutral-900 placeholder-neutral-400 outline-none transition-colors " +
    "focus:border-[#D63031]/40 focus:bg-white focus:ring-2 focus:ring-[#D63031]/10 " +
    "disabled:opacity-50 disabled:cursor-not-allowed";

  const borderClass = error ? "border-red-300" : "border-neutral-200";

  if (type === "textarea") {
    return (
      <div className="flex flex-col gap-1.5">
        <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
          {label}
          {required && <span className="text-[#D63031] ml-0.5">*</span>}
        </label>
        <textarea
          id={name}
          name={name}
          rows={4}
          placeholder={placeholder}
          value={value}
          onChange={onChange}
          required={required}
          disabled={disabled}
          className={`${baseClasses} ${borderClass} resize-none`}
        />
        {error && <p className="text-[12px] text-red-500">{error}</p>}
      </div>
    );
  }

  if (type === "select") {
    return (
      <div className="flex flex-col gap-1.5">
        <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
          {label}
          {required && <span className="text-[#D63031] ml-0.5">*</span>}
        </label>
        <select
          id={name}
          name={name}
          value={value}
          onChange={onChange}
          required={required}
          disabled={disabled}
          className={`${baseClasses} ${borderClass} appearance-none bg-[url('data:image/svg+xml;charset=UTF-8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%2212%22%20height%3D%2212%22%20viewBox%3D%220%200%2024%2024%22%20fill%3D%22none%22%20stroke%3D%22%239ca3af%22%20stroke-width%3D%222%22%3E%3Cpath%20d%3D%22m6%209%206%206%206-6%22%2F%3E%3C%2Fsvg%3E')] bg-no-repeat bg-[right_0.75rem_center]`}
        >
          <option value="">Seleccionar...</option>
          {options.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        {error && <p className="text-[12px] text-red-500">{error}</p>}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={name} className="text-[13px] font-medium text-neutral-700">
        {label}
        {required && <span className="text-[#D63031] ml-0.5">*</span>}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        placeholder={placeholder}
        value={value}
        onChange={onChange}
        required={required}
        disabled={disabled}
        className={`${baseClasses} ${borderClass}`}
      />
      {error && <p className="text-[12px] text-red-500">{error}</p>}
    </div>
  );
}
