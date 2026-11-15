/**
 * Nombres de los módulos del sistema municipal.
 *
 * No salen de la base: son módulos ajenos a Rentas y ninguna tabla local los
 * registra. Es rotulación de presentación, no dato de negocio.
 */
export const MODULE_LABELS = {
  M1: "Sistema de identidad ciudadana",
  M2: "Atención Ciudadana",
  M4: "Habilitaciones",
  M5: "Rentas",
  M7: "Tránsito",
  M8: "Desarrollo Social",
  M9: "Plataforma de integración",
};

export const EVENT_LABELS = {
  paymentRegistered: "Pago registrado",
  paymentReversed: "Pago reversado",
  debtSettled: "Deuda cancelada",
  overdueDebt: "Deuda vencida",
  debtOverdue: "Deuda vencida",
  ticketCreated: "Ticket recibido",
  ticketUpdated: "Ticket actualizado",
  updateTicketStatus: "Actualización de ticket solicitada",
  infractionConfirmed: "Infracción confirmada",
  permitFeeGenerated: "Tasa de habilitación recibida",
  permitUpdate: "Habilitación actualizada",
  paymentPlanRequested: "Plan de pago solicitado",
  updatePaymentPlanStatus: "Resolución de plan de pago",
  exemptionRequested: "Exención solicitada",
  updateExemptionStatus: "Resolución de exención",
  socialBenefitUpdated: "Beneficio social actualizado",
};
