package com.intellidesk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.intellidesk.Enums.TicketStatus;

/** Screenshot uploads (JPEG/PNG up to 10 MB). Anyone who can view a ticket can see and add its attachments. */
@Service @Transactional
public class AttachmentService {
  static final long MAX_BYTES = 10L * 1024 * 1024;
  private final TicketService tickets; private final AttachmentRepo repo; private final HistoryRepo history;
  private final Path dir;
  public AttachmentService(TicketService tickets, AttachmentRepo repo, HistoryRepo history,
                           @Value("${intellidesk.upload-dir:uploads}") String dir) {
    this.tickets = tickets; this.repo = repo; this.history = history; this.dir = Path.of(dir).toAbsolutePath().normalize();
  }
  private static ResponseStatusException err(HttpStatus s, String m) { return new ResponseStatusException(s, m); }

  @Transactional(readOnly = true)
  public List<Attachment> list(User u, Long ticketId) { return repo.findByTicketOrderByUploadedAt(tickets.get(u, ticketId)); }

  public Attachment upload(User u, Long ticketId, MultipartFile file) {
    Ticket t = tickets.get(u, ticketId);
    if (t.status == TicketStatus.RESOLVED) throw err(HttpStatus.BAD_REQUEST, "Resolved tickets are final");
    if (file == null || file.isEmpty()) throw err(HttpStatus.BAD_REQUEST, "Choose a file to upload");
    if (file.getSize() > MAX_BYTES) throw err(HttpStatus.PAYLOAD_TOO_LARGE, "Files must be 10 MB or smaller");
    String type = sniff(file); // trust the file's bytes, not the browser's label
    if (type == null) throw err(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG and PNG images can be attached");

    Attachment a = new Attachment();
    a.ticket = t; a.uploadedBy = u; a.contentType = type; a.sizeBytes = file.getSize(); a.uploadedAt = Instant.now();
    a.fileName = cleanName(file.getOriginalFilename(), type);
    a.storedName = UUID.randomUUID() + (type.equals("image/png") ? ".png" : ".jpg");
    try (InputStream in = file.getInputStream()) {
      Files.createDirectories(dir);
      Files.copy(in, dir.resolve(a.storedName), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) { throw err(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the file"); }
    repo.save(a);

    TicketHistory h = new TicketHistory(); h.ticket = t; h.changedBy = u; h.field = "attachment";
    h.newValue = a.fileName; h.changedAt = a.uploadedAt; history.save(h);
    return a;
  }

  /** Returns the attachment and its file path after checking the user may view the ticket. */
  @Transactional(readOnly = true)
  public Object[] open(User u, Long attachmentId) {
    Attachment a = repo.findById(attachmentId).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "Attachment not found"));
    tickets.get(u, a.ticket.id); // throws if this user cannot see the ticket
    Path p = dir.resolve(a.storedName).normalize();
    if (!p.startsWith(dir) || !Files.isRegularFile(p)) throw err(HttpStatus.NOT_FOUND, "File is no longer available");
    return new Object[]{a, p};
  }

  private static String sniff(MultipartFile f) {
    byte[] b = new byte[8];
    try (InputStream in = f.getInputStream()) { if (in.readNBytes(b, 0, 8) < 8) return null; } catch (IOException e) { return null; }
    if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
    if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
    return null;
  }
  private static String cleanName(String original, String type) {
    String n = original == null ? "" : original.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
    if (n.isEmpty()) n = "screenshot" + (type.equals("image/png") ? ".png" : ".jpg");
    return n.length() > 120 ? n.substring(n.length() - 120) : n;
  }
}
