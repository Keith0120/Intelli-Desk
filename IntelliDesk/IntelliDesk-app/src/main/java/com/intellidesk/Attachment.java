package com.intellidesk;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
/** Metadata for a screenshot attached to a ticket. The file itself lives in protected storage, not in the table. */
@Entity @Table(name="attachments")
public class Attachment {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="attachment_id") public Long id;
  @JsonIgnore @ManyToOne(optional=false) @JoinColumn(name="ticket_id") public Ticket ticket;
  @ManyToOne(optional=false) @JoinColumn(name="uploaded_by") public User uploadedBy;
  @Column(nullable=false,length=255) public String fileName;
  @Column(nullable=false,length=50) public String contentType;
  @Column(nullable=false) public long sizeBytes;
  @JsonIgnore @Column(nullable=false,unique=true,length=80) public String storedName;
  @Column(nullable=false) public Instant uploadedAt;
}
