package com.medfund.user.controller;

import com.medfund.user.dto.PricingSuggestionRequest;
import com.medfund.user.dto.PricingSuggestionResponse;
import com.medfund.user.service.PricingSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Enrolment-time AI pricing suggestion endpoint. Called by the
 * operator's "Suggest with AI" button on the Custom-premium field to
 * pre-fill the amount before saving.
 *
 * <p>Currently backed by
 * {@link PricingSuggestionService}'s hand-rolled stub. The response
 * envelope carries {@code stub = true} so the frontend can badge the
 * number and set expectations — the eventual production model swap
 * (calling the Python ai-service) doesn't require a frontend change.
 */
@RestController
@RequestMapping("/api/v1/pricing-suggestions")
@RequiredArgsConstructor
@Tag(name = "Pricing Suggestions",
    description = "Advisory suggested premiums for the operator's manual override at enrolment.")
@SecurityRequirement(name = "bearer-jwt")
public class PricingSuggestionController {

    private final PricingSuggestionService pricingSuggestionService;

    @PostMapping
    @Operation(summary = "Get an AI pricing suggestion for a new member or dependant",
        description = "Suggests a monthly premium based on the applicant's DoB + risk signals. " +
                "Operator retains final say — this number pre-fills the Custom-premium " +
                "amount field but they can edit before saving.")
    @ApiResponse(responseCode = "200", description = "Suggestion computed")
    public Mono<PricingSuggestionResponse> suggest(@Valid @RequestBody PricingSuggestionRequest request) {
        return pricingSuggestionService.suggest(request);
    }
}
