package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.TravelPolicy;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.repository.TravelPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class TravelPolicyStatusTransitionService extends PolicyStatusTransitionService<TravelPolicy> {

    public TravelPolicyStatusTransitionService(TravelPolicyRepository repository,
                                               StatusTransitionRecorder recorder,
                                               AuditPublisher auditPublisher,
                                               PolicyStatusChangedPublisher policyStatusChangedPublisher,
                                               TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected String policySource() { return "TRAVEL_POLICY"; }
    @Override protected String insuranceLine() { return "TRAVEL"; }
    @Override protected String getStatus(TravelPolicy e) { return e.getStatus(); }
    @Override protected TravelPolicy setStatus(TravelPolicy e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(TravelPolicy e) { return e.getId(); }
    @Override protected String getEntityName(TravelPolicy e) { return e.getPolicyNumber(); }
}
