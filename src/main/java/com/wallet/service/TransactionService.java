package com.wallet.service;

import com.wallet.dto.TransactionRequest;
import com.wallet.dto.TransactionResponse;

public interface TransactionService {
    TransactionResponse processTransaction(TransactionRequest request);

}
