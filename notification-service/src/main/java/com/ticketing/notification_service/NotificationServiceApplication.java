package com.ticketing.notification_service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.lang.Nullable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


@SpringBootApplication
@EnableJms
@EnableScheduling
@EnableConfigurationProperties(NotificationServiceApplication.AppProps.class)
public class NotificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationServiceApplication.class, args);
	}

	// ---- Configurable properties (mapped from application.properties) ----
	@ConfigurationProperties(prefix = "app")
	public static class AppProps {
		private TicketProps ticket = new TicketProps();
		private MailProps mail = new MailProps();
		private PdfProps pdf = new PdfProps();
		private JmsQueues jms = new JmsQueues();

		public TicketProps getTicket() { return ticket; }
		public MailProps getMail() { return mail; }
		public PdfProps getPdf() { return pdf; }
		public JmsQueues getJms() { return jms; }

		public static class TicketProps {
			private String serviceBaseUrl = "http://localhost:8081";
			public String getServiceBaseUrl() { return serviceBaseUrl; }
			public void setServiceBaseUrl(String serviceBaseUrl) { this.serviceBaseUrl = serviceBaseUrl; }
		}
		public static class MailProps {
			private String from = "no-reply@ticketing.local";
			private String managerEmail = "manager@example.com";
			public String getFrom() { return from; }
			public void setFrom(String from) { this.from = from; }
			public String getManagerEmail() { return managerEmail; }
			public void setManagerEmail(String managerEmail) { this.managerEmail = managerEmail; }
		}
		public static class PdfProps {
			private String outDir = System.getProperty("java.io.tmpdir") + "/ticket-pdfs";
			public String getOutDir() { return outDir; }
			public void setOutDir(String outDir) { this.outDir = outDir; }
		}
		public static class JmsQueues {
			private String ticketCreated = "ticket.created";
			private String ticketResolved = "ticket.resolved";
			private String autocloseResolved = "ticket.autoclose";
			public String getTicketCreated() { return ticketCreated; }
			public void setTicketCreated(String ticketCreated) { this.ticketCreated = ticketCreated; }
			public String getTicketResolved() { return ticketResolved; }
			public void setTicketResolved(String ticketResolved) { this.ticketResolved = ticketResolved; }
			public String getAutocloseResolved() { return autocloseResolved; }
			public void setAutocloseResolved(String autocloseResolved) { this.autocloseResolved = autocloseResolved; }
		}
	}

	// ---- Beans (RestClient) ----

	private final RestClient tickets;
	private final JavaMailSender mail;
	private final JmsTemplate jms;
	private final AppProps props;

	public NotificationServiceApplication(AppProps props, JavaMailSender mail, JmsTemplate jms,
	                                      @Value("${app.ticket.serviceBaseUrl:http://localhost:8081}") String baseUrl) {
		this.props = props;
		this.mail = mail;
		this.jms = jms;
		this.tickets = RestClient.builder()
				.requestFactory(new JdkClientHttpRequestFactory())
				.baseUrl(baseUrl)
				.build();
	}

	// =============================================================================
	//  JMS LISTENERS
	// =============================================================================

	/** Receives a minimal map for ticket.created and emails a SimpleMailMessage. */
	@JmsListener(destination = "${app.jms.queues.ticketCreated:ticket.created}")
	public void onTicketCreated(Map<String, Object> payload) {
		
		 System.out.println("onTicketCreated payload=" + payload);

		
		long id = num(payload.get("id"));
		String to = strOr(payload.get("notifyEmail"), props.getMail().getManagerEmail());
		

		SimpleMailMessage msg = new SimpleMailMessage();
		msg.setFrom(props.getMail().getFrom());
		msg.setTo(to);
		msg.setSubject("[Ticketing] Ticket #" + id + " created");
		msg.setText("""
				A new ticket has been created.

				ID: %d
				Title: %s
				Status: %s
				Opened By: %s
				Assigned To: %s
				Created At: %s

				Description:
				%s
				""".formatted(
				id,
				strOr(payload.get("title"), "(no title)"),
				strOr(payload.get("status"), "(unknown)"),
				strOr(payload.get("openedBy"), "(unknown)"),
				strOr(payload.get("assignedTo"), "(unassigned)"),
				strOr(payload.get("createdAt"), nowIso()),
				strOr(payload.get("description"), "(none)")
		));
		try {
			mail.send(msg);
		} catch (Exception ex) {
			System.err.println("Failed to send creation email: " + ex.getMessage());
		}
	}

	/** Receives ticket.resolved and emails a PDF summary as attachment. */
	@JmsListener(destination = "${app.jms.queues.ticketResolved:ticket.resolved}")

	public void onTicketResolved(Map<String, Object> payload) {
		// expected keys: id, title, assignedTo, openedBy, createdAt, resolvedAt, description(optional), note(optional), notifyEmail(required)
		long id = num(payload.get("id"));
		String to = strOr(payload.get("notifyEmail"), props.getMail().getManagerEmail());

		try {
			Path pdf = generateResolutionPdf(
					id,
					strOr(payload.get("title"), "(no title)"),
					strOr(payload.get("status"), "RESOLVED"),
					strOr(payload.get("openedBy"), "(unknown)"),
					strOr(payload.get("assignedTo"), "(unassigned)"),
					strOr(payload.get("createdAt"), nowIso()),
					strOr(payload.get("resolvedAt"), nowIso()),
					strOr(payload.get("description"), "(none)"),
					strOr(payload.get("note"), "")
			);
			sendResolvedWithAttachment(to, id, strOr(payload.get("title"), "(no title)"), pdf);
		} catch (Exception e) {
			System.err.println("Failed to send resolved email/PDF: " + e.getMessage());
		}
	}
	
	@JmsListener(destination = "ticket.assigned")
	public void onTicketAssigned(Map<String, Object> p) {
	    long id = num(p.get("ticketId"));
	    String subject = "[Ticketing] Ticket #" + id + " assigned";
	    String to = strOr(p.get("assignedTo"), props.getMail().getManagerEmail());
	    String body = """
	        A ticket has been assigned.

	        Ticket: #%d — %s
	        Opened By: %s
	        Assigned To: %s
	        Actioned By: %s
	        When: %s

	        View in app: %s/tickets/%d
	        """.formatted(
	        id,
	        strOr(p.get("title"), "(no title)"),
	        strOr(p.get("openedBy"), "(unknown)"),
	        strOr(p.get("assignedTo"), "(unassigned)"),
	        strOr(p.get("actor"), "(system)"),
	        strOr(p.get("at"), nowIso()),
	        props.getTicket().getServiceBaseUrl(), id
	    );

	    SimpleMailMessage msg = new SimpleMailMessage();
	    msg.setFrom(props.getMail().getFrom());
	    msg.setTo(to);
	    msg.setSubject(subject);
	    msg.setText(body);
	    try { mail.send(msg); } catch (Exception ex) {
	        System.err.println("Failed to send assignment email: " + ex.getMessage());
	    }
	}

	// ===================== NEW: REJECTED (reason to requester) =====================
	@JmsListener(destination = "ticket.rejected")
	public void onTicketRejected(Map<String, Object> p) {
	    long id = num(p.get("ticketId"));
	    String to = strOr(p.get("openedBy"), props.getMail().getManagerEmail());
	    String subject = "[Ticketing] Ticket #" + id + " was rejected";
	    String body = """
	        Your ticket has been rejected.

	        Ticket: #%d — %s
	        Reason: %s
	        By: %s
	        When: %s

	        You can reply to this email or reopen in the app.
	        """.formatted(
	        id,
	        strOr(p.get("title"), "(no title)"),
	        strOr(p.get("reason"), "(no reason provided)"),
	        strOr(p.get("actor"), "(system)"),
	        strOr(p.get("at"), nowIso())
	    );

	    SimpleMailMessage msg = new SimpleMailMessage();
	    msg.setFrom(props.getMail().getFrom());
	    msg.setTo(to);
	    msg.setSubject(subject);
	    msg.setText(body);
	    try { mail.send(msg); } catch (Exception ex) {
	        System.err.println("Failed to send rejection email: " + ex.getMessage());
	    }
	}

	// ===================== NEW: AUTO-CLOSE (stale > 7d) =====================
	@JmsListener(destination = "ticket.autoclose.stale")
	public void onAutoCloseStale(Map<String, Object> p) {
	    long id = num(p.get("ticketId"));
	    String to = strOr(p.get("openedBy"), props.getMail().getManagerEmail());
	    String subject = "[Ticketing] Ticket #" + id + " auto-closed (no recent activity)";
	    String body = """
	        Your ticket has been automatically closed because there has been no activity for 7 days.

	        Ticket: #%d — %s
	        Last Activity: %s
	        Auto-Closed: %s

	        If you still need help, you can reopen it in the app.
	        """.formatted(
	        id,
	        strOr(p.get("title"), "(no title)"),
	        strOr(p.get("lastTouchedAt"), "(unknown)"),
	        strOr(p.get("autoClosedAt"), nowIso())
	    );

	    SimpleMailMessage msg = new SimpleMailMessage();
	    msg.setFrom(props.getMail().getFrom());
	    msg.setTo(to);
	    msg.setSubject(subject);
	    msg.setText(body);
	    try { mail.send(msg); } catch (Exception ex) {
	        System.err.println("Failed to send stale autoclose email: " + ex.getMessage());
	    }
	}

	// ===================== NEW: AUTO-CLOSE (5d after RESOLVED) =====================
	@JmsListener(destination = "ticket.autoclose.afterResolved")
	public void onAutoCloseAfterResolved(Map<String, Object> p) {
	    long id = num(p.get("ticketId"));
	    String to = strOr(p.get("openedBy"), props.getMail().getManagerEmail());
	    String subject = "[Ticketing] Ticket #" + id + " auto-closed after resolution";
	    String body = """
	        Your ticket was resolved and has been automatically closed after 5 days.

	        Ticket: #%d — %s
	        Resolved At: %s
	        Auto-Closed: %s

	        If something's still off, you can reopen it in the app.
	        """.formatted(
	        id,
	        strOr(p.get("title"), "(no title)"),
	        strOr(p.get("resolvedAt"), "(unknown)"),
	        strOr(p.get("autoClosedAt"), nowIso())
	    );

	    SimpleMailMessage msg = new SimpleMailMessage();
	    msg.setFrom(props.getMail().getFrom());
	    msg.setTo(to);
	    msg.setSubject(subject);
	    msg.setText(body);
	    try { mail.send(msg); } catch (Exception ex) {
	        System.err.println("Failed to send after-resolved autoclose email: " + ex.getMessage());
	    }
	}

	// =============================================================================
	//  SCHEDULED TASKS
	// =============================================================================

	/**
	 * Daily at 07:30 local:
	 *  - find SUBMITTED tickets older than 7 days → email manager
	 *  - find RESOLVED tickets older than 5 days → send "ticket.autoclose" JMS message
	 */
	@Scheduled(cron = "0 30 7 * * *")
	public void dailySweep() {
		try {
			// --- Pending >7d (assume SUBMITTED == pending) ---
			List<Map<String, Object>> submitted = tickets.get()
					.uri("/api/tickets?status=SUBMITTED")
					.retrieve()
					.body(List.class);

			List<Map<String, Object>> oldPending = (submitted == null ? List.<Map<String,Object>>of() : submitted)
					.stream()
					.filter(m -> olderThan(strOr(m.get("createdAt"), null), 7))
					.collect(Collectors.toList());

			if (!oldPending.isEmpty()) {
				emailManagerPending(oldPending);
			}

			// --- Resolved >5d → autoclose ---
			List<Map<String, Object>> resolved = tickets.get()
					.uri("/api/tickets?status=RESOLVED")
					.retrieve()
					.body(List.class);

			List<Long> toClose = (resolved == null ? List.<Map<String,Object>>of() : resolved)
					.stream()
					.filter(m -> olderThan(strOr(m.get("updatedAt"), strOr(m.get("resolvedAt"), null)), 5))
					.map(m -> num(m.get("id")))
					.collect(Collectors.toList());

			for (Long id : toClose) {
				Map<String, Object> msg = Map.of("id", id, "reason", "Resolved>5d");
				jms.convertAndSend(props.getJms().getAutocloseResolved(), msg);
			}
		} catch (Exception e) {
			System.err.println("dailySweep error: " + e.getMessage());
		}
	}

	// =============================================================================
	//  EMAIL + PDF (PDFBox)
	// =============================================================================

	private void emailManagerPending(List<Map<String, Object>> tickets) {
		String to = props.getMail().getManagerEmail();
		String subject = "[Ticketing] " + tickets.size() + " ticket(s) pending > 7 days";
		StringBuilder body = new StringBuilder("The following tickets have been pending for more than 7 days:\n\n");
		for (Map<String, Object> t : tickets) {
			body.append("#").append(num(t.get("id")))
				.append("  ").append(strOr(t.get("title"), "(no title)"))
				.append("  openedBy=").append(strOr(t.get("openedBy"), "(unknown)"))
				.append("  createdAt=").append(strOr(t.get("createdAt"), ""))
				.append("\n");
		}
		SimpleMailMessage msg = new SimpleMailMessage();
		msg.setFrom(props.getMail().getFrom());
		msg.setTo(to);
		msg.setSubject(subject);
		msg.setText(body.toString());
		try {
			mail.send(msg);
		} catch (Exception e) {
			System.err.println("Failed to send pending summary: " + e.getMessage());
		}
	}

	private void sendResolvedWithAttachment(String to, long id, String title, Path pdf) throws MessagingException {
		MimeMessage mime = mail.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
		helper.setFrom(props.getMail().getFrom());
		helper.setTo(to);
		helper.setSubject("[Ticketing] Ticket #" + id + " resolved");
		helper.setText("Ticket #" + id + " (“" + title + "”) has been resolved. See attached PDF.", false);
		helper.addAttachment(pdf.getFileName().toString(), pdf.toFile());
		mail.send(mime);
	}

	private Path generateResolutionPdf(long id, String title, String status, String openedBy, String assignedTo,
	                                   String createdAt, String resolvedAt, String description, @Nullable String note)
			throws IOException {
		Path dir = Path.of(props.getPdf().getOutDir());
		Files.createDirectories(dir);
		Path out = dir.resolve(("ticket-" + id + "-resolution.pdf").replaceAll("[^a-zA-Z0-9._-]", "_"));

		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.LETTER);
			doc.addPage(page);
			float margin = 48f, y = page.getMediaBox().getUpperRightY() - margin, width = page.getMediaBox().getWidth() - 2*margin;

			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				// Title
				cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 18); cs.newLineAtOffset(margin, y);
				cs.showText("Ticket Resolution Summary"); cs.endText(); y -= 24;

				// Fields
				y = drawLine(cs, "ID: " + id, margin, y);
				y = drawLine(cs, "Title: " + title, margin, y);
				y = drawLine(cs, "Status: " + status, margin, y);
				y = drawLine(cs, "Opened By: " + openedBy, margin, y);
				y = drawLine(cs, "Assigned To: " + assignedTo, margin, y);
				y = drawLine(cs, "Created At: " + createdAt, margin, y);
				y = drawLine(cs, "Resolved At: " + resolvedAt, margin, y);
				y -= 6;

				// Description
				y = drawLine(cs, "Description:", margin, y);
				y = drawParagraph(cs, description, margin, y, width);
				y -= 6;

				if (StringUtils.hasText(note)) {
					y = drawLine(cs, "Resolution Note:", margin, y);
					y = drawParagraph(cs, note, margin, y, width);
				}
			}
			doc.save(out.toFile());
		}
		return out;
	}

	private float drawLine(PDPageContentStream cs, String text, float x, float y) throws IOException {
		if (y < 64) return y;
		cs.beginText();
		cs.setFont(PDType1Font.HELVETICA, 11);
		cs.newLineAtOffset(x, y);
		cs.showText(text);
		cs.endText();
		return y - 14;
	}

	private float drawParagraph(PDPageContentStream cs, String text, float x, float y, float maxWidth) throws IOException {
		var words = text.replace("\r","").split("\\s+");
		String line = "";
		for (String w : words) {
			String test = (line.isEmpty()? w : line + " " + w);
			float wpx = PDType1Font.HELVETICA.getStringWidth(test)/1000f * 11;
			if (wpx > maxWidth && !line.isEmpty()) {
				y = drawLine(cs, line, x, y);
				line = w;
			} else {
				line = test;
			}
		}
		if (!line.isEmpty()) y = drawLine(cs, line, x, y);
		return y;
	}

	// =============================================================================
	//  helpers
	// =============================================================================

	private static String nowIso() {
		return OffsetDateTime.now().toString();
	}
	private static boolean olderThan(@Nullable String isoTs, int days) {
		if (!StringUtils.hasText(isoTs)) return false;
		try {
			Instant t = Instant.parse(isoTs);
			Instant cutoff = Instant.now().minusSeconds(days * 86400L);
			return t.isBefore(cutoff);
		} catch (Exception e) { return false; }
	}
	private static long num(Object o) {
		if (o instanceof Number n) return n.longValue();
		if (o == null) return 0L;
		try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
	}
	private static String strOr(Object o, String d) {
		return (o == null || o.toString().isBlank()) ? d : o.toString();
	}
}
