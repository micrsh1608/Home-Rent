package vn.ancu.rental.contract;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.auth.SessionAuthHelper;

import java.util.List;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final ContractLifecycleService contractLifecycleService;
    private final SessionAuthHelper sessionAuthHelper;

    public InvoiceController(ContractLifecycleService contractLifecycleService, SessionAuthHelper sessionAuthHelper) {
        this.contractLifecycleService = contractLifecycleService;
        this.sessionAuthHelper = sessionAuthHelper;
    }

    @GetMapping
    public List<InvoiceDto> listInvoices(HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.listInvoicesForActor(actor);
    }

    @GetMapping("/{id}")
    public InvoiceDto getInvoiceDetail(@PathVariable String id, HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        return contractLifecycleService.getInvoiceForActor(actor, id);
    }

    @PostMapping("/{id}/confirm-paid")
    public InvoiceDto confirmInvoicePaid(@PathVariable String id, HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireRole(request, "OWNER");
        return contractLifecycleService.confirmInvoicePaid(actor, id);
    }
}
