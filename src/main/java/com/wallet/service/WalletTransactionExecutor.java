package com.wallet.service;

import com.wallet.exception.WalletNotFoundException;
import com.wallet.model.Transaction;
import com.wallet.model.TransactionStatus;
import com.wallet.model.TransactionType;
import com.wallet.model.Wallet;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;


import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class WalletTransactionExecutor {
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public WalletTransactionExecutor(WalletRepository walletRepository,
                                     TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }
    @Transactional
    public Transaction debit(UUID transactionId, UUID userId, BigDecimal amount) {

        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new WalletNotFoundException("No wallet found for userId: " + userId));

        Transaction txn = new Transaction();
        txn.setTransactionId(transactionId);
        txn.setUserId(userId);
        txn.setAmount(amount);
        txn.setType(TransactionType.DEBIT);
        txn.setCreatedAt(Instant.now());

        if (wallet.getBalance().compareTo(amount) < 0) {
            txn.setStatus(TransactionStatus.FAILED_INSUFFICIENT_FUNDS);
            return transactionRepository.save(txn);
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        txn.setStatus(TransactionStatus.SUCCESS);
        return transactionRepository.save(txn);
    }
    @Transactional
    public Transaction credit(UUID transactionId, UUID userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new WalletNotFoundException("No wallet found for userId: " + userId));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction txn = new Transaction();
        txn.setTransactionId(transactionId);
        txn.setUserId(userId);
        txn.setAmount(amount);
        txn.setType(TransactionType.CREDIT);
        txn.setStatus(TransactionStatus.SUCCESS);
        txn.setCreatedAt(Instant.now());
        return transactionRepository.save(txn);
    }

    public BigDecimal getBalance(UUID userId) {
        return walletRepository.findById(userId)
                .orElseThrow(() -> new WalletNotFoundException("No wallet found for userId: " + userId))
                .getBalance();
    }


}