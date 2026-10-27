/** Compatibilidad de imports: cada dominio conserva su propio servicio y un único cliente HTTP. */
export { authService } from "./domains/auth.js";
export { taxpayerService } from "./domains/taxpayers.js";
export { taxConfigService } from "./domains/taxConfigurations.js";
export { settlementService, liquidationRunService } from "./domains/settlements.js";
export { debtService, administrationService, debtAdjustmentService } from "./domains/debts.js";
export { billService, paymentService, paymentReversalService, creditBalanceService, reconciliationService } from "./domains/payments.js";
export { paymentPlanService, paymentPlanConfigurationService, planExpirationService, refinancingService } from "./domains/paymentPlans.js";
export { exemptionService } from "./domains/exemptions.js";
export { eventService, externalObligationService, ticketService } from "./domains/integrations.js";
export { cashierService } from "./domains/cashier.js";
export { auditService } from "./domains/audit.js";
export { portalService } from "./domains/portal.js";
export { dashboardService } from "./domains/dashboards.js";
