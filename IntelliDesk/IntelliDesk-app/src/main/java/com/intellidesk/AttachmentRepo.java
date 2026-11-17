package com.intellidesk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AttachmentRepo extends JpaRepository<Attachment,Long> { List<Attachment> findByTicketOrderByUploadedAt(Ticket t); }
