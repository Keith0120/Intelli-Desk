package com.intellidesk;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;

@RestController @RequestMapping("/api")
public class AttachmentController {
  private final AttachmentService svc; private final Auth auth;
  public AttachmentController(AttachmentService svc, Auth auth) { this.svc = svc; this.auth = auth; }

  @GetMapping("/tickets/{id}/attachments")
  public List<Attachment> list(@PathVariable Long id, HttpSession s) { return svc.list(auth.current(s), id); }

  @PostMapping(value = "/tickets/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Attachment upload(@PathVariable Long id, @RequestParam("file") MultipartFile file, HttpSession s) {
    return svc.upload(auth.current(s), id, file);
  }

  /** Streams the image to signed-in users who can view the ticket. */
  @GetMapping("/attachments/{id}")
  public ResponseEntity<Resource> open(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download, HttpSession s) {
    Object[] r = svc.open(auth.current(s), id);
    Attachment a = (Attachment) r[0];
    ContentDisposition cd = (download ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(a.fileName).build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(a.contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
        .body(new FileSystemResource((Path) r[1]));
  }

}
