package com.medfund.rules.fact;

/**
 * Fact object representing a healthcare provider inserted into the Drools KieSession for rule evaluation.
 * This is a plain POJO — not a JPA entity.
 */
public class ProviderFact {

    private String providerId;
    private String registrationStatus;
    private String ahfozSpecialty;
    /** True when the provider is contracted with the fund. Referenced by
     *  {@code CoPaymentTemplates.CP01} and {@code WAIVE_IN_NETWORK_TIER_1}. */
    private Boolean inNetwork;
    /** Free-text tier label (V077, MVP for G16). Values like "TIER_1",
     *  "IN_NETWORK", "PREFERRED". Formal reference table deferred as F5. */
    private String networkTier;

    public ProviderFact() {
    }

    // --- Getters and Setters ---

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getRegistrationStatus() {
        return registrationStatus;
    }

    public void setRegistrationStatus(String registrationStatus) {
        this.registrationStatus = registrationStatus;
    }

    public String getAhfozSpecialty() {
        return ahfozSpecialty;
    }

    public void setAhfozSpecialty(String ahfozSpecialty) {
        this.ahfozSpecialty = ahfozSpecialty;
    }

    public Boolean getInNetwork() {
        return inNetwork;
    }

    public void setInNetwork(Boolean inNetwork) {
        this.inNetwork = inNetwork;
    }

    public String getNetworkTier() {
        return networkTier;
    }

    public void setNetworkTier(String networkTier) {
        this.networkTier = networkTier;
    }
}
