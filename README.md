# Idempotent Payment/Wallet Event Processor

## Stack
Java 17, Spring Boot 3.3.4, Spring Data JPA, H2 (in-memory), JUnit 5, MockMvc, Lombok.

## Structure (MVC)
```
src/main/java/com/wallet/
├── controller/   -> TransactionController (HTTP only)
├── service/      -> TransactionService (interface), TransactionServiceImpl (idempotency),
│                    WalletTransactionExecutor (transactional wallet debit/credit + row locking)
├── repository/   -> WalletRepository, TransactionRepository
├── model/        -> Wallet, Transaction, TransactionType, TransactionStatus
├── dto/          -> TransactionRequest, TransactionResponse
└── exception/    -> DuplicateTransactionException, WalletNotFoundException, GlobalExceptionHandler
```

## How to run
```bash
mvn spring-boot:run
```
Then:
```bash
curl -X POST http://localhost:8080/api/v1/transactions/process \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"11111111-1111-1111-1111-111111111111","userId":"22222222-2222-2222-2222-222222222222","amount":250.00,"type":"DEBIT"}'
```
> **Note:** A wallet must exist for the `userId` before debiting. Add a seed row in a `CommandLineRunner`, via the H2 console (`/h2-console`), or through the test suite, which seeds wallets automatically.

## How to test 
Open the project in IntelliJ and run:
```
src/test/java/com/wallet/TransactionProcessingTests.java
```
or from the command line:
```bash
mvn test
```
All three required tests run with zero external setup (H2 in-memory, created fresh per run via `ddl-auto: create-drop`). Console output prints the intent and result of each test explicitly:
- **Happy Path:** Processes a single valid debit transaction successfully.
- **Idempotency:** Sends 3 identical transaction IDs simultaneously and ensures the balance is only deducted once (1 succeeds with `200 OK`, 2 return `409 Conflict`).
- **Race Condition:** Sends 10 concurrent debit requests of ₹100 against a ₹500 balance, ensuring exactly 5 succeed, 5 fail due to insufficient funds, and the final balance is exactly ₹0 without negative balance or lost updates.
