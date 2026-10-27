import { useCallback, useState } from "react";
import { settlementService } from "../services/rentasService.js";
import { serverFieldErrors, validAmount, validDate, validPeriod } from "../lib/validation.js";
import useAsyncAction from "./useAsyncAction.js";

const empty = { taxpayerId: "", conceptCode: "", period: "", baseAmount: "", dueDate: "" };
const aliases = { taxConceptId: "conceptCode", taxableBase: "baseAmount" };
export default function useSettlementForm(onCreated) {
  const [form, setForm] = useState(empty);
  const [errors, setErrors] = useState({});
  const [preview, setPreview] = useState(null);
  const action = useAsyncAction();
  const clearError = action.clearError;
  const onChange = useCallback((event) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
    setErrors((previous) => ({ ...previous, [name]: undefined }));
    setPreview(null);
    clearError();
  }, [clearError]);
  const validate = () => {
    const found = {};
    if (!form.taxpayerId) found.taxpayerId = "Seleccioná el contribuyente.";
    if (!form.conceptCode) found.conceptCode = "Seleccioná el concepto.";
    if (!validPeriod(form.period)) found.period = "Usá el formato AAAA-MM con un mes válido.";
    if (!validAmount(form.baseAmount, { allowZero: true })) found.baseAmount = "Ingresá un importe mayor o igual a cero.";
    if (!validDate(form.dueDate)) found.dueDate = "Indicá un vencimiento válido.";
    setErrors(found);
    return !Object.keys(found).length;
  };
  const execute = (operation) => {
    if (action.pending || !validate()) return;
    return action.run(operation).catch((error) => { setErrors(serverFieldErrors(error, aliases)); });
  };
  return {
    form, errors, preview, onChange, submitting: action.pending, submitError: action.error,
    onPreview: (event) => { event?.preventDefault(); return execute(async () => { setPreview(await settlementService.preview(form)); }); },
    onSubmit: (event) => {
      event?.preventDefault();
      return execute(async () => {
        const settlement = await settlementService.generate(form);
        setForm(empty); setPreview(null); onCreated(settlement);
      });
    },
  };
}
