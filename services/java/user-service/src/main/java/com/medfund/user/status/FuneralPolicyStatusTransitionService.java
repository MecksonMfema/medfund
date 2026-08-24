package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.FuneralPolicy;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.repository.FuneralPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class FuneralPolicyStatusTransitionService extends PolicyStatusTransitionService<FuneralPolicy> {

    public FuneralPolicyStatusTransitionService(FuneralPolicyRepository repository,
                                                StatusTransitionRecorder recorder,
                                                AuditPublisher auditPublisher,
                                                PolicyStatusChangedPublisher policyStatusChangedPublisher,
                                                TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected String policySource() { return "FUNERAL_POLICY"; }
    @Override protected String insuranceLine() { return "FUNERAL"; }
    @Override protected String getStatus(FuneralPolicy e) { return e.getStatus(); }
    @Override protected FuneralPolicy setStatus(FuneralPolicy e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(FuneralPolicy e) { return e.getId(); }
    @Override protected String getEntityName(FuneralPolicy e) { return e.getPolicyNumber(); }
}
