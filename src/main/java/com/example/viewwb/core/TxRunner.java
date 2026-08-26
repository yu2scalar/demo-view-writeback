package com.example.viewwb.core;

import com.example.viewwb.exception.CustomException;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.CrudConflictException;
import org.springframework.stereotype.Component;

/**
 * Runs a unit of work in one ScalarDB transaction: start → work → commit,
 * aborting on any failure and mapping conflicts to HTTP 409.
 */
@Component
public class TxRunner {

    private final DistributedTransactionManager manager;

    public TxRunner(DistributedTransactionManager manager) {
        this.manager = manager;
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T apply(DistributedTransaction tx) throws Exception;
    }

    public <T> T run(String operation, TxWork<T> work) {
        try {
            DistributedTransaction tx = manager.start();
            try {
                T result = work.apply(tx);
                tx.commit();
                return result;
            } catch (Exception e) {
                tx.abort();
                throw e;
            }
        } catch (CrudConflictException | CommitConflictException e) {
            throw new CustomException("同時更新を検出しました。再読込してやり直してください", 409);
        } catch (RuntimeException e) {
            // CustomException と、呼び出し側が扱う制御シグナル(FlowAborted 等)は素通し
            throw e;
        } catch (Exception e) {
            throw new CustomException(operation + " failed: " + e.getMessage(), e, 500);
        }
    }
}
