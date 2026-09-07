import { Check } from "lucide-react";

export default function StepIndicatorGeneric({
  steps = [],
  currentStep = 0,
  colors = {
    completed: "#D63031",
    current: "#0F2C59",
    pending: "#f3f4f6",
    completedText: "white",
    currentText: "white",
    pendingText: "#9ca3af",
    connectorCompleted: "#D63031",
    connectorPending: "#e5e7eb",
  },
  hideLabelsOnMobile = true,
  showConnectors = true,
}) {
  if (steps.length === 0) {
    return null;
  }

  return (
    <div className="flex items-center gap-2">
      {steps.map((step, index) => {
        const isCompleted = index < currentStep;
        const isCurrent = index === currentStep;

        const circleColor = isCompleted ? colors.completed : isCurrent ? colors.current : colors.pending;
        const textColor = isCompleted ? colors.completedText : isCurrent ? colors.currentText : colors.pendingText;

        return (
          <div key={`${step.label}-${index}`} className="flex items-center gap-2">
            <div className="flex items-center gap-1.5">
              <div
                className="flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-semibold transition-all duration-300"
                style={{
                  backgroundColor: circleColor,
                  color: textColor,
                  boxShadow: isCurrent ? `0 0 0 2px ${colors.current}33` : "none",
                }}
              >
                {isCompleted ? (
                  <Check className="h-3.5 w-3.5" strokeWidth={3} />
                ) : (
                  index + 1
                )}
              </div>
              {step.label && (
                <span
                  className={`text-[12px] transition-colors ${
                    hideLabelsOnMobile ? "hidden sm:inline" : "inline"
                  } ${
                    isCurrent
                      ? "font-semibold"
                      : isCompleted
                        ? "font-normal"
                        : "font-normal"
                  }`}
                  style={{
                    color: isCompleted
                      ? "#6b7280"
                      : isCurrent
                        ? "#1f2937"
                        : "#9ca3af",
                  }}
                >
                  {step.label}
                </span>
              )}
            </div>
            {showConnectors && index < steps.length - 1 && (
              <div
                className="h-px w-6 sm:w-10 transition-colors"
                style={{
                  backgroundColor: isCompleted
                    ? colors.connectorCompleted
                    : colors.connectorPending,
                }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}
