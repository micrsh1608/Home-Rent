package vn.ancu.rental.contract;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.auth.SessionAuthHelper;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractLifecycleService contractLifecycleService;
    private final SessionAuthHelper sessionAuthHelper;

    public ContractController(ContractLifecycleService contractLifecycleService, SessionAuthHelper sessionAuthHelper) {
        this.contractLifecycleService = contractLifecycleService;
        this.sessionAuthHelper = sessionAuthHelper;
    }

    @GetMapping
    public List<ContractDto> listContracts(HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.listContractsForActor(actor);
    }

    @GetMapping("/renewal-notices")
    public List<ContractDto> listRenewalNotices(HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.listRenewalNotices(actor, null);
    }

    @GetMapping("/{id}")
    public ContractDto getContract(@PathVariable String id, HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.getContractForActor(actor, id);
    }

    @GetMapping("/{id}/history")
    public List<ContractHistoryDto> getContractHistory(@PathVariable String id, HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.getContractHistory(actor, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractDto createContract(
            @RequestBody ContractLifecycleService.CreateContractRequest body,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.createContract(actor, body, null);
    }

    @PostMapping("/{id}/schedule")
    public ContractDto scheduleAppointment(
            @PathVariable String id,
            @RequestBody ContractLifecycleService.ScheduleAppointmentRequest body,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "TENANT");
        return contractLifecycleService.scheduleAppointment(actor, id, body);
    }

    @PostMapping("/{id}/confirm-sign-and-deposit")
    public ContractDto confirmSignAndDeposit(
            @PathVariable String id,
            @RequestBody ContractLifecycleService.ConfirmSignAndDepositRequest body,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "OWNER");
        return contractLifecycleService.confirmSignAndDepositByOwner(actor, id, body, null);
    }

    @PostMapping("/{id}/cancel-no-show")
    public ContractDto cancelNoShowAfterThreeDays(
            @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "OWNER");
        return contractLifecycleService.cancelContractForNoShowAfterThreeDays(actor, id, today);
    }

    @PostMapping("/{id}/handover")
    public ContractDto recordHandover(
            @PathVariable String id,
            @RequestBody ContractLifecycleService.HandoverRequest body,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "OWNER");
        return contractLifecycleService.recordHandover(actor, id, body, null);
    }

    @PostMapping("/{id}/renewal")
    public ContractDto respondRenewal(
            @PathVariable String id,
            @RequestBody ContractLifecycleService.RenewalResponseRequest body,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "TENANT");
        return contractLifecycleService.respondRenewal(actor, id, body, null);
    }

    @GetMapping("/{id}/rent-schedule")
    public LeaseRentCalculator.LeaseTermBreakDown previewRentSchedule(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        ContractDto contract = contractLifecycleService.getContractForActor(actor, id);
        return LeaseRentCalculator.calculateLeaseBreakdown(contract.startDate(), contract.endDate(), contract.rent());
    }

    @GetMapping("/rent-preview")
    public LeaseRentCalculator.LeaseTermBreakDown previewCustomRentSchedule(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "MONTH") String unit,
            @RequestParam(defaultValue = "1") int value,
            @RequestParam long monthlyRent
    ) {
        LocalDate effectiveEnd = endDate != null
                ? endDate
                : LeaseRentCalculator.calculateEndDateForTerm(startDate, unit, value);
        return LeaseRentCalculator.calculateLeaseBreakdown(startDate, effectiveEnd, monthlyRent);
    }

    @PostMapping("/{id}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDto createInvoice(
            @PathVariable String id,
            @RequestBody ContractLifecycleService.CreateInvoiceRequest body,
            HttpServletRequest request
    ) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "OWNER");
        return contractLifecycleService.createPeriodInvoice(actor, id, body);
    }
}
