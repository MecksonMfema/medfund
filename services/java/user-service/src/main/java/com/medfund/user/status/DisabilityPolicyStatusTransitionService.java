package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.DisabilityPolicy;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.repository.DisabilityPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class DisabilityPolicyStatusTransitionService extends PolicyStatusTransitionService<DisabilityPolicy> {

    public DisabilityPolicyStatusTransitionService(DisabilityPolicyRepository repository,
                                                   StatusTransitionRecorder recorder,
                                                   AuditPublisher auditPublisher,
                                                   PolicyStatusChangedPublisher policyStatusChangedPublisher,
                                                   TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected String policySource() { return "DISABILITY_POLICY"; }
    @Override protected String insuranceLine() { return "DISABILITY"; }
    @Override protected String getStatus(DisabilityPolicy e) { return e.getStatus(); }
    @Override protected DisabilityPolicy setStatus(DisabilityPolicy e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(DisabilityPolicy e) { return e.getId(); }
    @Override protected String getEntityName(DisabilityPolicy e) { return e.getPolicyNumber(); }
}
