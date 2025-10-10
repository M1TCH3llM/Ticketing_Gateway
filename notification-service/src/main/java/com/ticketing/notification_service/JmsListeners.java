package com.ticketing.notification_service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Component
public class JmsListeners {
  private static final Logger log = LoggerFactory.getLogger(JmsListeners.class);

  private final JavaMailSender mail;
  private final NotificationServiceApplication.AppProps props;

  public JmsListeners(JavaMailSender mail,
                      NotificationServiceApplication.AppProps props) {
    this.mail = mail;
    this.props = props;
  }

  // ================= ASSIGNMENT =================
  // payload: ticketId(Long), title, openedBy, assignedTo, actor, at
  @JmsListener(destination = "ticket.assigned")
  public void onAssigned(Map<String, Object> p) {
    Long id = asLong(p.get("ticketId"));
    String subject = "[Ticketing] Ticket #" + id + " assigned";
    String to = resolveTo(p.get("assignedTo"));
    if (isBlank(to)) to = props.getMail().getManagerEmail();

    String body = """
        A ticket has been assigned.

        Ticket: #%d — %s
        Opened By: %s
        Assigned To: %s
        Actioned By: %s
        When: %s

        View: %s/api/tickets/%d
        """.formatted(
        id, n(s(p.get("title"))), n(s(p.get("openedBy"))), n(s(p.get("assignedTo"))),
        n(s(p.get("actor"))), n(s(p.get("at"))),
        props.getTicket().getServiceBaseUrl(), id
    );

    sendText(to, subject, body);
  }

  // ================= REJECTED (reason to requester) =================
  // payload: ticketId(Long), title, openedBy, reason, actor, at
  @JmsListener(destination = "ticket.rejected")
  public void onRejected(Map<String, Object> p) {
    Long id = asLong(p.get("ticketId"));
    String subject = "[Ticketing] Ticket #" + id + " was rejected";
    String to = resolveTo(p.get("openedBy"));
    if (isBlank(to)) to = props.getMail().getManagerEmail();

    String body = """
        Your ticket has been rejected.

        Ticket: #%d — %s
        Reason: %s
        By: %s
        When: %s

        You can reply to this email or reopen in the app.
        """.formatted(
        id, n(s(p.get("title"))), n(s(p.get("reason"))), n(s(p.get("actor"))), n(s(p.get("at")))
    );

    sendText(to, subject, body);
  }

  // ================= RESOLVED (details + optional PDF) =================
  // payload: ticketId(Long), title, openedBy, actor, resolvedAt, resolutionDetails?, pdfPath?, pdfName?
  @JmsListener(destination = "ticket.resolved")
  public void onResolved(Map<String, Object> p) {
    Long id = asLong(p.get("ticketId"));
    String to = resolveTo(p.get("openedBy"));
    if (isBlank(to)) to = props.getMail().getManagerEmail();

    String title = s(p.get("title"));
    String details = s(p.get("resolutionDetails"));
    String resolvedAt = s(p.get("resolvedAt"));
    String actor = s(p.get("actor"));

    String subject = "[Ticketing] Ticket #" + id + " resolved: " + title;

    String body = """
        Your ticket has been resolved.

        Ticket: #%d — %s
        Resolved By: %s
        Resolved At: %s

        Details:
        %s

        If everything looks good, please close the ticket.
        """.formatted(
        id, n(title), n(actor), n(resolvedAt), n(details)
    );

    // If a PDF path/name was provided in the payload, attach it; otherwise send text-only.
    String pdfPath = s(p.get("pdfPath"));
    String pdfName = s(p.get("pdfName"));

    if (!isBlank(pdfPath)) {
      try {
        File pdf = Path.of(pdfPath).toFile();
        if (isBlank(pdfName)) pdfName = pdf.getName();
        sendWithAttachment(to, subject, body, pdf, pdfName);
        return;
      } catch (Exception ex) {
        log.warn("Attachment provided but failed for ticket {}: {}", id, ex.getMessage());
      }
    }

    sendText(to, subject, body);
  }

  // ================= AUTO-CLOSE (stale > 7d) =================
  // payload: ticketId(Long), title, openedBy, lastTouchedAt, autoClosedAt, reason
  @JmsListener(destination = "ticket.autoclose.stale")
  public void onAutoCloseStale(Map<String, Object> p) {
    Long id = asLong(p.get("ticketId"));
    String to = resolveTo(p.get("openedBy"));
    if (isBlank(to)) to = props.getMail().getManagerEmail();

    String subject = "[Ticketing] Ticket #" + id + " auto-closed (no recent activity)";
    String body = """
        Your ticket has been automatically closed because there has been no activity for 7 days.

        Ticket: #%d — %s
        Last Activity: %s
        Auto-Closed: %s

        If you still need help, you can reopen it in the app.
        """.formatted(
        id, n(s(p.get("title"))), n(s(p.get("lastTouchedAt"))), n(s(p.get("autoClosedAt")))
    );

    sendText(to, subject, body);
  }

  // ================= AUTO-CLOSE (5d after RESOLVED) =================
  // payload: ticketId(Long), title, openedBy, resolvedAt, autoClosedAt, reason
  @JmsListener(destination = "ticket.autoclose.afterResolved")
  public void onAutoCloseAfterResolved(Map<String, Object> p) {
    Long id = asLong(p.get("ticketId"));
    String to = resolveTo(p.get("openedBy"));
    if (isBlank(to)) to = props.getMail().getManagerEmail();

    String subject = "[Ticketing] Ticket #" + id + " auto-closed after resolution";
    String body = """
        Your ticket was resolved and has been automatically closed after 5 days.

        Ticket: #%d — %s
        Resolved At: %s
        Auto-Closed: %s

        If something's still off, you can reopen it in the app.
        """.formatted(
        id, n(s(p.get("title"))), n(s(p.get("resolvedAt"))), n(s(p.get("autoClosedAt")))
    );

    sendText(to, subject, body);
  }

  // ===== helpers =====
  private void sendText(String to, String subject, String body) {
    if (isBlank(to)) to = props.getMail().getManagerEmail();
    try {
      SimpleMailMessage m = new SimpleMailMessage();
      m.setTo(to);
      m.setFrom(props.getMail().getFrom());
      m.setSubject(subject);
      m.setText(body);
      mail.send(m);
      log.info("Email sent (text) to={} subj='{}'", to, subject);
    } catch (MailException ex) {
      log.warn("Email send failed (text) to={} subj='{}' : {}", to, subject, ex.getMessage());
    }
  }

  private void sendWithAttachment(String to, String subject, String body, File file, String attachmentName) {
    if (isBlank(to)) to = props.getMail().getManagerEmail();
    try {
      MimeMessage mm = mail.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(mm, true);
      helper.setTo(to);
      helper.setFrom(props.getMail().getFrom());
      helper.setSubject(subject);
      helper.setText(body, false);
      helper.addAttachment(attachmentName, file);
      mail.send(mm);
      log.info("Email sent (attachment) to={} subj='{}' file='{}'", to, subject, file.getAbsolutePath());
    } catch (MessagingException | MailException ex) {
      log.warn("Email send failed (attachment) to={} subj='{}' : {}", to, subject, ex.getMessage());
      sendText(to, subject, body + "\n\n(Note: attachment could not be delivered.)");
    }
  }

  private static String s(Object o) { return o == null ? null : String.valueOf(o); }
  private static String n(String s) { return (s == null || s.isBlank()) ? "(none)" : s; }
  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static Long asLong(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.longValue();
    try { return Long.parseLong(String.valueOf(o)); } catch (Exception ex) { return null; }
  }
  
  private String resolveTo(Object candidate) {
	  String addr = s(candidate);
	 
	  boolean looksLikeEmail = addr != null && addr.contains("@");
	  if (looksLikeEmail) return addr;

	  String fallback = props.getMail().getManagerEmail();
	  if (isBlank(fallback)) fallback = props.getMail().getManagerEmail();
	  log.info("Recipient '{}' is not an email. Using fallback '{}'.", addr, fallback);
	  return fallback;
	}
}

