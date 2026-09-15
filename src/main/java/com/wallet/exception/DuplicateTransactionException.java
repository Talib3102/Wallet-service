package com.wallet.exception;

import com.wallet.dto.TransactionResponse;

public class DuplicateTransactionException extends RuntimeException{
    private final TransactionResponse cachedResponse;

    public DuplicateTransactionException(TransactionResponse cachedResponse) {
        super("Duplicate transactionId detected: " + cachedResponse.getTransactionId());
        this.cachedResponse = cachedResponse;
    }

    public TransactionResponse getCachedResponse() {
        return cachedResponse;
    }
}
