package com.ticketing.ticket.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketStatus;
import com.ticketing.ticket.service.TicketService;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.net.URI;

@CrossOrigin(
    origins = "http://localhost:8080",
    allowedHeaders = {"*"},
    exposedHeaders = {"Content-Disposition"},
    methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.OPTIONS}
)
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService svc;

    public TicketController(TicketService svc) {
        this.svc = svc;
    }

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    // --- Role helpers --------------------------------------------------------

    private boolean hasRole(HttpServletRequest req, String role) {
        String roles = req.getHeader("X-Roles");
        if (roles == null) return false;
        return Arrays.stream(roles.split(",")).map(String::trim).anyMatch(role::equals);
    }
    private boolean isAdmin(HttpServletRequest r){ return hasRole(r, "ROLE_ADMIN"); }
    private boolean isManager(HttpServletRequest r){ return hasRole(r, "ROLE_MANAGER"); }

    // --- Lists ---------------------------------------------------------------

    // Main list (optionally filter by status)
    @GetMapping(params = "!status")
    public List<Ticket> all() {
        return svc.findAll(null);
    }

    @GetMapping(params = "status")
    public List<Ticket> byStatus(@RequestParam TicketStatus status) {
        return svc.findAll(status);
    }

    // everything NOT CLOSED
    @GetMapping("/active")
    public List<Ticket> active() {
        return svc.findActive();
    }

    // CLOSED 
    @GetMapping("/history")
    public List<Ticket> historyTickets() {
        return svc.findHistory();
    }

    // --- Create --------------------------------------------------------------

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Ticket> createJson(@RequestBody Ticket incoming) {
        var saved = svc.create(incoming);
        return ResponseEntity.created(URI.create("/api/tickets/" + saved.getId())).body(saved);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Ticket> create(
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String openedBy,
            @RequestPart(name = "file", required = false) MultipartFile file) throws Exception {

        Ticket t = new Ticket();
        t.setTitle(title);
        t.setDescription(description);
        t.setOpenedBy(openedBy);

        if (file != null && !file.isEmpty()) {
            Path dir = Paths.get(uploadDir);
            if (!Files.exists(dir)) Files.createDirectories(dir);

            String clean = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
            String ext = "";
            int dot = clean.lastIndexOf('.');
            if (dot > -1) ext = clean.substring(dot);
            String stored = UUID.randomUUID() + ext;

            Path dest = dir.resolve(stored);
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

            t.setAttachmentName(clean);
            t.setAttachmentType(file.getContentType());
            t.setAttachmentPath(dest.toAbsolutePath().toString());
            t.setAttachmentSize(file.getSize());
        }

        var saved = svc.create(t);
        return ResponseEntity.created(URI.create("/api/tickets/" + saved.getId())).body(saved);
    }

    // --- Download attachment -------------------------------------------------

    @GetMapping("/{id:\\d+}/attachment")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id) throws Exception {
        var t = svc.findById(id).orElse(null);
        if (t == null || t.getAttachmentPath() == null) return ResponseEntity.notFound().build();

        Path path = Paths.get(t.getAttachmentPath());
        if (!Files.exists(path)) return ResponseEntity.notFound().build();

        var res = new FileSystemResource(path);
        String filename = (t.getAttachmentName() == null || t.getAttachmentName().isBlank())
                ? path.getFileName().toString()
                : t.getAttachmentName();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace("\"","") + "\"")
                .header(HttpHeaders.CONTENT_TYPE, t.getAttachmentType() == null ? "application/octet-stream" : t.getAttachmentType())
                .body(res);
    }

    // --- Single ticket & history --------------------------------------------

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<Ticket> one(@PathVariable("id") Long id) {
        return svc.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id:\\d+}/history")
    public ResponseEntity<List<com.ticketing.ticket.domain.TicketHistory>> history(@PathVariable("id") Long id) {
        if (svc.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(svc.historyFor(id));
    }

    
 // --- Partial field update ---------------------------------------------------
    @PatchMapping(path = "/{id:\\d+}")
    public ResponseEntity<?> updateFields(@PathVariable("id") Long id,
                                          @RequestBody(required = false) Ticket patch,
                                          HttpServletRequest request,
                                          @RequestParam(name = "note", required = false) String note) {
        String changedBy = request.getHeader("X-User");
        if (changedBy == null || changedBy.isBlank()) {
            return ResponseEntity.badRequest().body("Missing X-User header");
        }
        if (patch == null) {
            // empty body PATCH still can log a note-only entry
            patch = new Ticket();
        }

        try {
            return svc.updateFields(id, patch, changedBy, note)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to update fields: " + e.getMessage());
        }
    }

    
    // --- Status / approvals --------------------------------------------------
    
 
    
    @PatchMapping("/{id:\\d+}/status")
    public ResponseEntity<Ticket> updateStatus(@PathVariable("id") Long id,
                                               @RequestParam(name = "status") TicketStatus status,
                                               @RequestParam(name = "by", required = false) String changedBy,
                                               @RequestParam(name = "note", required = false) String note,
                                               HttpServletRequest request) {
        // Only ADMIN can resolve
        if (status == TicketStatus.RESOLVED && !isAdmin(request)) {
            return ResponseEntity.status(403).build();
        }

        return svc.findById(id)
            .map(t -> {
                if (changedBy != null && !changedBy.isBlank()) {
                    t.setAssignedTo(changedBy);
                }
                var saved = svc.updateStatus(id, status, changedBy, note).orElseThrow();
                return ResponseEntity.ok(saved);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    private boolean callerIsManager(HttpServletRequest req) { return isManager(req); }

    @PatchMapping("/{id:\\d+}/approve")
    public ResponseEntity<?> approve(@PathVariable("id") Long id,
                                     @RequestParam(name = "by") String manager,
                                     @RequestParam(name = "note", required = false) String note,
                                     HttpServletRequest request) {
        if (!callerIsManager(request)) {
            return ResponseEntity.status(403).body("Manager role required to approve.");
        }
        try {
            return svc.approve(id, manager, note)
                      .<ResponseEntity<?>>map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        }
    }

    @PatchMapping("/{id:\\d+}/reject")
    public ResponseEntity<?> reject(@PathVariable("id") Long id,
                                    @RequestParam(name = "by") String manager,
                                    @RequestParam(name = "note", required = false) String note,
                                    HttpServletRequest request) {
        if (!callerIsManager(request)) {
            return ResponseEntity.status(403).body("Manager role required to reject.");
        }
        try {
            return svc.reject(id, manager, note)
                      .<ResponseEntity<?>>map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        }
    }

    @PatchMapping("/{id:\\d+}/close")
    public ResponseEntity<?> close(@PathVariable("id") Long id,
                                   @RequestParam(name="by") String by,
                                   @RequestParam(name="note", required=false) String note,
                                   HttpServletRequest req) {
        if (!isAdmin(req) && !isManager(req)) return ResponseEntity.status(403).body("Manager or admin required to close.");
        try {
            return svc.close(id, by, note)
                     .<ResponseEntity<?>>map(ResponseEntity::ok)
                     .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        }
    }

    @PatchMapping("/{id:\\d+}/reopen")
    public ResponseEntity<?> reopen(@PathVariable Long id,
                                    @RequestParam(name="by") String by,
                                    @RequestParam(name="note", required=false) String note) {
      try {
        return svc.reopen(id, by, note)
                 .<ResponseEntity<?>>map(ResponseEntity::ok)
                 .orElseGet(() -> ResponseEntity.notFound().build());
      } catch (IllegalStateException e) {
        return ResponseEntity.status(409).body(e.getMessage());
      }
    }
}

