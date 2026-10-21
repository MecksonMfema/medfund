package com.medfund.user.reports.lifecycle.dto;

/**
 * One census row per <em>holder</em>: either a corporate group
 * ({@code holderType == "GROUP"}, {@code holderId} is a {@code groups.id})
 * or an ungrouped individual policyholder
 * ({@code holderType == "INDIVIDUAL"}, {@code holderId} is the principal
 * member's {@code members.id}).
 *
 * <p>Principal counts come from the {@code members} table; dependant
 * counts come from the {@code dependants} table joined via
 * {@code member_id}. {@code coveredLives} is
 * {@code totalMembers + totalDependants}: the number of humans this
 * holder is contributing cover for.
 */
public record GroupCensusRow(
    java.util.UUID groupId,
    String  holderType,
    String  groupName,
    String  registrationNumber,
    String  contactPerson,
    String  contactEmail,
    long    activeMembers,
    long    suspendedMembers,
    long    lapsedMembers,
    long    terminatedMembers,
    long    totalMembers,
    long    activeDependants,
    long    suspendedDependants,
    long    lapsedDependants,
    long    terminatedDependants,
    long    totalDependants,
    long    coveredLives
) {}
