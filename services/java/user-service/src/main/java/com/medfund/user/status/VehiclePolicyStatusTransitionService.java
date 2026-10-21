package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.Vehicle;
import com.medfund.user.publisher.PolicyStatusChangedPublisher;
import com.medfund.user.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class VehiclePolicyStatusTransitionService extends PolicyStatusTransitionService<Vehicle> {

    public VehiclePolicyStatusTransitionService(VehicleRepository repository,
                                                StatusTransitionRecorder recorder,
                                                AuditPublisher auditPublisher,
                                                PolicyStatusChangedPublisher policyStatusChangedPublisher,
                                                TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, policyStatusChangedPublisher, tx);
    }

    @Override protected String policySource() { return "VEHICLE_POLICY"; }
    @Override protected String insuranceLine() { return "VEHICLE"; }
    @Override protected String getStatus(Vehicle e) { return e.getStatus(); }
    @Override protected Vehicle setStatus(Vehicle e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(Vehicle e) { return e.getId(); }
    @Override protected String getEntityName(Vehicle e) { return e.getRegistrationNumber(); }
}
