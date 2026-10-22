package ar.gob.municipalidad.rentas;

interface EventosDeDominio {
    void deudaSaldada(Debt deuda);
    void deudaAjustada(Debt deuda, AdjustmentRequest ajuste);
    void planCaducado(PaymentPlan plan);
    void planRefinanciado(PaymentPlan anterior, PaymentPlan nuevo);
    void pagoRegistrado(Payment pago);
    void planCompletado(PaymentPlan plan);
}
