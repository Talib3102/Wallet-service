package com.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.model.Wallet;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@SpringBootTest
class TransactionProcessingTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanState() {
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    private String buildPayload(UUID transactionId, UUID userId, String amount, String type) {
        return String.format(
                "{\"transactionId\":\"%s\",\"userId\":\"%s\",\"amount\":%s,\"type\":\"%s\"}",
                transactionId, userId, amount, type);
    }
    // ---------------------------------------------
    // TEST 1: HAPPY PATH
    // ---------------------------------------------
    @Test
    @DisplayName("Processes a single valid debit transaction successfully")
    void happyPath_singleDebit_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        walletRepository.save(new Wallet(userId, new BigDecimal("500.00")));

        System.out.println("\n[HAPPY PATH TEST] Sending single DEBIT of 250.00 against balance 500.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(transactionId, userId, "250.00", "DEBIT")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), "Expected HTTP 200 for a valid debit");

        Wallet updated = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(updated.getBalance()),
                "Balance should be reduced by exactly the debited amount");

        System.out.println("[HAPPY PATH TEST] PASSED — resulting balance = " + updated.getBalance());
    }
    // -----------------------------------------
    // TEST 2: IDEMPOTENCY
    // ------------------------------------------
    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void idempotency_threeIdenticalRequests_onlyOneSucceeds() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID(); // SAME id used by all 3 requests
        walletRepository.save(new Wallet(userId, new BigDecimal("500.00")));
        String payload = buildPayload(transactionId, userId, "250.00", "DEBIT");

        System.out.println("\n[IDEMPOTENCY TEST] Firing 3 identical transactionId=" + transactionId + " requests concurrently");

        int threadCount = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                startLatch.await(); // all threads fire as close to simultaneously as possible
                MvcResult result = mockMvc.perform(post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();
                return result.getResponse().getStatus();
            }));
        }

        readyLatch.await();
        startLatch.countDown(); // release all 3 at once
        pool.shutdown();

        int successCount = 0;
        int conflictCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(5, TimeUnit.SECONDS);
            if (status == 200) successCount++;
            if (status == 409) conflictCount++;
        }

        System.out.println("[IDEMPOTENCY TEST] statuses -> 200 OK: " + successCount + ", 409 Conflict: " + conflictCount);

        assertEquals(1, successCount, "Exactly one of the 3 identical requests must succeed");
        assertEquals(2, conflictCount, "The other two identical requests must return 409 Conflict");

        Wallet updated = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(updated.getBalance()),
                "Balance must be deducted exactly once despite 3 identical requests");

        long txnRowCount = transactionRepository.count();
        assertEquals(1, txnRowCount, "Only one transaction row should exist for this transactionId");

        System.out.println("[IDEMPOTENCY TEST] PASSED — balance deducted once, final balance = " + updated.getBalance());
    }

    //----------------------------------
    // TEST 3: RACE CONDITION
    // ----------------------------------
    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void raceCondition_tenConcurrentDebits_exactlyFiveFail() throws Exception {
        UUID userId = UUID.randomUUID();
        walletRepository.save(new Wallet(userId, new BigDecimal("500.00")));

        int threadCount = 10;
        System.out.println("\n[RACE CONDITION TEST] Firing " + threadCount + " concurrent DEBIT requests of 100.00 each against balance 500.00");

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            UUID transactionId = UUID.randomUUID(); // DIFFERENT id per request — this is testing balance locking, not idempotency
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(buildPayload(transactionId, userId, "100.00", "DEBIT")))
                        .andReturn();
                return result.getResponse().getStatus();
            }));
        }

        readyLatch.await();
        startLatch.countDown();
        pool.shutdown();

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        for (Future<Integer> f : futures) {
            int status = f.get(10, TimeUnit.SECONDS);
            if (status == 200) successCount.incrementAndGet();
            else failCount.incrementAndGet();
        }

        Wallet updated = walletRepository.findById(userId).orElseThrow();

        System.out.println("[RACE CONDITION TEST] successful debits: " + successCount.get()
                + ", failed (insufficient funds): " + failCount.get()
                + ", final balance: " + updated.getBalance());

        assertEquals(5, successCount.get(), "Exactly 5 debits of 100 should succeed against a 500 balance");
        assertEquals(5, failCount.get(), "Exactly 5 debits should fail with insufficient funds");
        assertEquals(0, BigDecimal.ZERO.compareTo(updated.getBalance()),
                "Final balance must be exactly 0, proving no lost updates / no negative balance");

        System.out.println("[RACE CONDITION TEST] PASSED — final balance is exactly 0, no overdraft occurred");
    }

}
