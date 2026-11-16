package com.xiye.formaspace.testsupport;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Unit-test callback boundary only. Does not simulate MySQL locks, isolation or rollback of rows. */
public class TestTransactions extends AbstractPlatformTransactionManager {
    public int commits;
    public int rollbacks;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
    @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
}
