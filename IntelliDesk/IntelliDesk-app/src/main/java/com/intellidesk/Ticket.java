package com.intellidesk;
import com.intellidesk.Enums.*;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="tickets")
public class Ticket {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="ticket_id") public Long id;
  @Column(nullable=false,length=200) public String title;
  @Column(nullable=false,length=4000) public String description;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public TicketStatus status;
  @ManyToOne(optional=false) @JoinColumn(name="submitted_by") public User submitter;
  @ManyToOne @JoinColumn(name="assigned_to") public User assignee;
  @ManyToOne(optional=false) @JoinColumn(name="category_id") public Category category;
  @ManyToOne(optional=false) @JoinColumn(name="priority_id") public PriorityPolicy priority;
  @Column(nullable=false) public Instant createdAt;
  @Column(nullable=false) public Instant dueAt;
  /** Set while the ticket is PENDING (waiting on the employee); the SLA clock is paused from this moment. */
  public Instant pausedAt;
  /** Total seconds the SLA clock was paused in earlier PENDING periods; dueAt already includes them. */
  public Long pausedSeconds;
  public Instant resolvedAt;
  @Column(length=4000) public String resolutionNotes;
  @ManyToOne @JoinColumn(name="escalated_by") public User escalatedBy;
  @Column(length=1000) public String escalationReason;
}
