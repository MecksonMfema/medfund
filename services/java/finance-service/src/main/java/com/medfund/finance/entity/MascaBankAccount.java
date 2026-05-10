package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("masca_bank_accounts")
public class MascaBankAccount {

    @Id
    private UUID id;

    @Column("bank_name")
    private String bankName;

    @Column("account_number")
    private String accountNumber;

    @Column("branch_code")
    private String branchCode;

    @Column("swift_code")
    private String swiftCode;

    @Column("account_name")
    private String accountName;

    @Column("currency_code")
    private String currencyCode;

    @Column("is_nominated")
    private Boolean nominated = false;

    @Column("is_active")
    private Boolean active = true;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
