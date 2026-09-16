package com.wallet.service;

import com.wallet.dto.TransactionRequest;
import com.wallet.dto.TransactionResponse;
import com.wallet.exception.DuplicateTransactionException;
import com.wallet.model.Transaction;
import com.wallet.model.TransactionStatus;
import com.wallet.model.TransactionType;
import com.wallet.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@Service
public class TransactionServiceImpl implements TransactionService{
    private final TransactionRepository transactionRepository;
    private final WalletTransactionExecutor executor;

    private final Map<UUID, Object> transactionLocks = new ConcurrentHashMap<>();

    public TransactionServiceImpl(TransactionRepository transactionRepository,
                                  WalletTransactionExecutor executor) {
        this.transactionRepository = transactionRepository;
        this.executor = executor;
    }

    @Override
    public TransactionResponse processTransaction(TransactionRequest request) {
        UUID transactionId = request.getTransactionId();
        Object lock = transactionLocks.computeIfAbsent(transactionId, id -> new Object());

        synchronized (lock) {
//            here i do  idempotency check - has this exact transactionId already been processed (by this thread's earlier sibling,
//            or a previous request)?
            Transaction existing = transactionRepository.findById(transactionId).orElse(null);
            if (existing != null) {
                throw new DuplicateTransactionException(toResponse(existing));
            }

            //  not seen before -> actually process it.
            // This delegates to a DIFFERENT bean so @Transactional +
            // pessimistic locking correctly applies (see WalletTransactionExecutor).
            Transaction result;
            if (request.getType() == TransactionType.DEBIT) {
                result = executor.debit(transactionId, request.getUserId(), request.getAmount());
            } else {
                result = executor.credit(transactionId, request.getUserId(), request.getAmount());
            }

            TransactionResponse response = toResponse(result);

            if (result.getStatus() == TransactionStatus.FAILED_INSUFFICIENT_FUNDS) {
                response.setMessage("Insufficient funds");
                throw new InsufficientFundsExceptionWithResponse("Insufficient funds", response);
            }

            return response;
        }
    }

    private TransactionResponse toResponse(Transaction txn) {
        TransactionResponse response = new TransactionResponse();
        response.setTransactionId(txn.getTransactionId());
        response.setUserId(txn.getUserId());
        response.setAmount(txn.getAmount());
        response.setStatus(txn.getStatus());
        response.setMessage(txn.getStatus().name());
        return response;
    }

    public static class InsufficientFundsExceptionWithResponse extends RuntimeException {
        private final TransactionResponse response;

        public InsufficientFundsExceptionWithResponse(String message, TransactionResponse response) {
            super(message);
            this.response = response;
        }

        public TransactionResponse getResponse() {
            return response;
        }
    }
}
