package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record CreateDependantRequest(
    @NotNull
    UUID memberId,

    @NotBlank @Size(max = 100)
    String firstName,

    @NotBlank @Size(max = 100)
    String lastName,

    @NotNull
    LocalDate dateOfBirth,

    @NotBlank
    @Pattern(regexp = "^(male|female|other)$",
        message = "gender must be male, female, or other")
    @Size(max = 10)
    String gender,

    @NotBlank @Size(max = 50)
    String relationship,

    @NotBlank @Size(max = 50)
    String nationalId
) {}
