package vn.ancu.rental.contract;

import java.time.LocalDateTime;

public record ContractHistoryDto(
        String id,
        String contractId,
        String fromStatus,
        String toStatus,
        String fromPaymentStatus,
        String toPaymentStatus,
        String actionType,
        String actorId,
        String actorName,
        String note,
        LocalDateTime createdAt
) {
}
