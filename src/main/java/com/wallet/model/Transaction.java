package com.assignment.wallet.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    // transactionId is the PRIMARY KEY -> the database itself
    // physically cannot store the same transactionId twice.
    // This is our last line of defense for idempotency.
    @Id
    private UUID transactionId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    // Cached response body, so a duplicate/retry request returns
    // exactly what the original caller would have received.
    @Lob
    private String cachedResponseJson;

    @Column(nullable = false)
    private Instant createdAt;
}
