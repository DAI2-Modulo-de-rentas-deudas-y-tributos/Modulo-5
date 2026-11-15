package ar.gob.municipalidad.rentas;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
class PaymentPlanRequestQueryService {
    private final PaymentPlanRequestRepository requests;
    private final FilteredQueryService queries;
    private final CurrentIdentity identity;

    PaymentPlanRequestQueryService(PaymentPlanRequestRepository requests, FilteredQueryService queries, CurrentIdentity identity) {
        this.requests = requests;
        this.queries = queries;
        this.identity = identity;
    }

    Page<PaymentPlanRequest> list(Map<String, String> filters, Pageable pageable) {
        Map<String, String> scopedFilters = new HashMap<>(filters);
        if (identity.hasRole("SUPERVISOR")) scopedFilters.put("status", PaymentPlanRequestStatus.PENDING_EXCEPTION_APPROVAL.name());
        return queries.list(requests, PaymentPlanRequest.class, scopedFilters, pageable);
    }
}