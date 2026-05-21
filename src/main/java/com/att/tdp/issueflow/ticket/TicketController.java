package com.att.tdp.issueflow.ticket;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketCsvService ticketCsvService;

    public TicketController(TicketService ticketService, TicketCsvService ticketCsvService) {
        this.ticketService = ticketService;
        this.ticketCsvService = ticketCsvService;
    }

    @GetMapping
    public List<TicketResponse> getTicketsByProject(@RequestParam Long projectId) {
        return ticketService.getTicketsByProject(projectId);
    }

    @GetMapping("/deleted")
    public List<TicketResponse> getDeletedTickets(@RequestParam Long projectId) {
        return ticketService.getDeletedTickets(projectId);
    }

    @PostMapping("/{ticketId}/restore")
    public ResponseEntity<Void> restoreTicket(@PathVariable Long ticketId) {
        ticketService.restoreTicket(ticketId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{ticketId}")
    public TicketResponse getTicket(@PathVariable Long ticketId) {
        return ticketService.getTicket(ticketId);
    }

    @PostMapping
    public TicketResponse createTicket(@Valid @RequestBody CreateTicketRequest request) {
        return ticketService.createTicket(request);
    }

    @PatchMapping("/{ticketId}")
    public ResponseEntity<Void> updateTicket(
            @PathVariable Long ticketId,
            @Valid @RequestBody UpdateTicketRequest request
    ) {
        ticketService.updateTicket(ticketId, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> deleteTicket(@PathVariable Long ticketId) {
        ticketService.deleteTicket(ticketId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/export")
    public ResponseEntity<String> exportTickets(@RequestParam Long projectId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tickets.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(ticketCsvService.exportTickets(projectId));
    }

    @PostMapping("/import")
    public ImportTicketsResponse importTickets(@RequestParam MultipartFile file, @RequestParam Long projectId) {
        return ticketCsvService.importTickets(file, projectId);
    }
}
