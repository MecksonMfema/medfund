package com.medfund.claims.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("drugs")
public class Drug {

    @Id
    private UUID id;

    @Column("drug_name")
    private String drugName;

    @Column("drug_type")
    private String drugType;

    @Column("unit_of_measurement")
    private String unitOfMeasurement;

    @Column("tariff_code")
    private String tariffCode;

    @Column("wholesale_cost_zwl")
    private BigDecimal wholesaleCostZwl;

    @Column("wholesale_cost_usd")
    private BigDecimal wholesaleCostUsd;

    @Column("payment_percentage")
    private BigDecimal paymentPercentage;

    @Column("do_not_pay")
    private Boolean doNotPay;

    @Column("is_active")
    private Boolean isActive;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public String getDrugType() { return drugType; }
    public void setDrugType(String drugType) { this.drugType = drugType; }
    public String getUnitOfMeasurement() { return unitOfMeasurement; }
    public void setUnitOfMeasurement(String unitOfMeasurement) { this.unitOfMeasurement = unitOfMeasurement; }
    public String getTariffCode() { return tariffCode; }
    public void setTariffCode(String tariffCode) { this.tariffCode = tariffCode; }
    public BigDecimal getWholesaleCostZwl() { return wholesaleCostZwl; }
    public void setWholesaleCostZwl(BigDecimal wholesaleCostZwl) { this.wholesaleCostZwl = wholesaleCostZwl; }
    public BigDecimal getWholesaleCostUsd() { return wholesaleCostUsd; }
    public void setWholesaleCostUsd(BigDecimal wholesaleCostUsd) { this.wholesaleCostUsd = wholesaleCostUsd; }
    public BigDecimal getPaymentPercentage() { return paymentPercentage; }
    public void setPaymentPercentage(BigDecimal paymentPercentage) { this.paymentPercentage = paymentPercentage; }
    public Boolean getDoNotPay() { return doNotPay; }
    public void setDoNotPay(Boolean doNotPay) { this.doNotPay = doNotPay; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
