package vn.ancu.rental.contract;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ContractDto(
        String id,
        String code,
        String apartmentId,
        String tenantId,
        String tenantName,
        String ownerId,
        String ownerName,
        String renewalOf,
        String apartmentTitle,
        String room,
        String propertyName,
        String location,
        String termsVersion,
        LocalDate startDate,
        LocalDate endDate,
        String leaseTermUnit,
        int leaseTermValue,
        long rent,
        long deposit,
        long electricRate,
        long waterRate,
        String status,
        String payment,
        LocalDateTime appointment,
        LocalDateTime signedAt,
        String signedConfirmedBy,
        LocalDateTime paymentConfirmedAt,
        String paymentConfirmedBy,
        LocalDate moveInDeadline,
        LocalDate actualMoveIn,
        LocalDate actualMoveOut,
        boolean depositForfeited,
        LocalDateTime cancelledAt,
        String cancellationReason,
        String renewal,
        LocalDateTime renewalRespondedAt,
        boolean expired,
        boolean renewalOpen,
        long firstPeriodRent,
        long lastPeriodRent
) {
}
