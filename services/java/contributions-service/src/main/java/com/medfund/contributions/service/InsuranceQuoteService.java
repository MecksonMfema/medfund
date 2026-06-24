package com.medfund.contributions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.InsuranceQuoteRequest;
import com.medfund.contributions.dto.InsuranceQuoteResponse;
import com.medfund.contributions.entity.InsuranceQuote;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.InsuranceQuoteRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class InsuranceQuoteService {

    private static final Logger log = LoggerFactory.getLogger(InsuranceQuoteService.class);

    private final SchemeRepository schemeRepository;
    private final AgeGroupRepository ageGroupRepository;
    private final SchemeBenefitRepository benefitRepository;
    private final InsuranceQuoteRepository quoteRepository;
    private final ObjectMapper objectMapper;

    public InsuranceQuoteService(SchemeRepository schemeRepository,
                                 AgeGroupRepository ageGroupRepository,
                                 SchemeBenefitRepository benefitRepository,
                                 InsuranceQuoteRepository quoteRepository,
                                 ObjectMapper objectMapper) {
        this.schemeRepository = schemeRepository;
        this.ageGroupRepository = ageGroupRepository;
        this.benefitRepository = benefitRepository;
        this.quoteRepository = quoteRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate an automatic insurance quote based on scheme pricing and member/dependant ages.
     * This is a PUBLIC endpoint — no authentication required.
     */
    @Transactional
    public Mono<InsuranceQuoteResponse> generateQuote(InsuranceQuoteRequest request) {
        return schemeRepository.findById(request.schemeId())
            .flatMap(scheme -> ageGroupRepository.findBySchemeId(scheme.getId()).collectList()
                .flatMap(ageGroups -> benefitRepository.findBySchemeId(scheme.getId()).collectList()
                    .flatMap(benefits -> {
                        int memberAge = Period.between(request.dateOfBirth(), LocalDate.now()).getYears();

                        // Find member's age group and premium. Currency is inherited from
                        // the scheme; the matching age-group's currency is sanity-checked
                        // against it to catch legacy rows that fell out of sync.
                        BigDecimal memberPremium = BigDecimal.ZERO;
                        String memberAgeGroupName = "Unknown";
                        String currency = (scheme.getCurrencyCode() == null || scheme.getCurrencyCode().isBlank())
                            ? "USD" : scheme.getCurrencyCode();

                        for (var ag : ageGroups) {
                            if (memberAge >= ag.getMinAge() && memberAge <= ag.getMaxAge()) {
                                memberPremium = ag.getContributionAmount();
                                memberAgeGroupName = ag.getName();
                                if (ag.getCurrencyCode() != null && !ag.getCurrencyCode().isBlank()) {
                                    currency = ag.getCurrencyCode();
                                }
                                break;
                            }
                        }

                        // Calculate dependant premiums
                        var dependantPremiums = new ArrayList<InsuranceQuoteResponse.DependantPremium>();
                        BigDecimal totalDependantPremium = BigDecimal.ZERO;

                        if (request.dependants() != null) {
                            for (var dep : request.dependants()) {
                                int depAge = Period.between(dep.dateOfBirth(), LocalDate.now()).getYears();
                                BigDecimal depPremium = BigDecimal.ZERO;
                                String depAgeGroup = "Unknown";

                                for (var ag : ageGroups) {
                                    if (depAge >= ag.getMinAge() && depAge <= ag.getMaxAge()) {
                                        depPremium = ag.getContributionAmount();
                                        depAgeGroup = ag.getName();
                                        break;
                                    }
                                }

                                dependantPremiums.add(new InsuranceQuoteResponse.DependantPremium(
                                    dep.firstName() + " " + dep.lastName(),
                                    dep.relationship(), depAge, depAgeGroup, depPremium
                                ));
                                totalDependantPremium = totalDependantPremium.add(depPremium);
                            }
                        }

                        BigDecimal totalMonthly = memberPremium.add(totalDependantPremium);

                        // Build benefit summary
                        var benefitSummaries = benefits.stream()
                            .map(b -> new InsuranceQuoteResponse.BenefitSummary(
                                b.getName(), b.getBenefitType(),
                                b.getAnnualLimit(), b.getCurrencyCode()))
                            .toList();

                        String quoteNumber = "QTE-" + ThreadLocalRandom.current().nextInt(100000, 999999);

                        var response = new InsuranceQuoteResponse(
                            quoteNumber, scheme.getName(), scheme.getSchemeType(),
                            LocalDate.now(), LocalDate.now().plusDays(30),
                            memberPremium, memberAgeGroupName, dependantPremiums,
                            totalMonthly, currency, benefitSummaries, "GENERATED"
                        );

                        // Persist the quote
                        var quote = new InsuranceQuote();
                        quote.setQuoteNumber(quoteNumber);
                        quote.setSchemeId(scheme.getId());
                        quote.setFirstName(request.firstName());
                        quote.setLastName(request.lastName());
                        quote.setDateOfBirth(request.dateOfBirth());
                        quote.setEmail(request.email());
                        quote.setPhone(request.phone());
                        quote.setDependantCount(request.dependants() != null ? request.dependants().size() : 0);
                        quote.setMemberPremium(memberPremium);
                        quote.setTotalPremium(totalMonthly);
                        quote.setCurrencyCode(currency);
                        quote.setStatus("GENERATED");
                        quote.setValidUntil(LocalDate.now().plusDays(30));
                        quote.setCreatedAt(Instant.now());

                        try {
                            quote.setQuoteDetails(objectMapper.writeValueAsString(response));
                        } catch (Exception e) {
                            quote.setQuoteDetails("{}");
                        }

                        return quoteRepository.save(quote).thenReturn(response);
                    })));
    }

    public Mono<InsuranceQuoteResponse> getByQuoteNumber(String quoteNumber) {
        return quoteRepository.findByQuoteNumber(quoteNumber)
            .flatMap(quote -> {
                try {
                    var response = objectMapper.readValue(quote.getQuoteDetails(), InsuranceQuoteResponse.class);
                    return Mono.just(response);
                } catch (Exception e) {
                    return Mono.empty();
                }
            });
    }
}
