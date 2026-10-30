package com.ecommerce.shipping.exception;

import org.apache.kafka.common.errors.RetriableException;
import org.hibernate.TransactionException;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

/**
 * Tells "a dependency is not answering" from "this request or record is wrong". The first is expected
 * to pass: HTTP answers 503 with one throttled WARN line instead of a stack trace per request, and the
 * Kafka listener waits (exponential back-off) instead of dead-lettering good records.
 */
public final class DependencyFailures {

    private static final int MAX_DEPTH = 16;

    private DependencyFailures() { }

    /**
     * True when the failure, or anything in its cause chain, is a database or broker that did not answer:
     * no connection, connection pool timeout, lock or query timeout, transient or recoverable data access
     * failure, a retriable Kafka error (which includes its timeouts), and a commit or rollback that failed
     * because the connection died mid-transaction (a killed backend, a database restart: the rollback's
     * exception then replaces the original one). A unique-constraint race is not one of them: that stays a
     * client conflict.
     */
    public static boolean isUnavailable(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof RetriableException
                    || t instanceof SQLTransientConnectionException
                    || t instanceof SQLNonTransientConnectionException
                    || t instanceof SQLRecoverableException
                    || t instanceof SQLTimeoutException
                    || t instanceof JDBCConnectionException
                    || ((t instanceof TransactionException || t instanceof TransactionSystemException) && causedBySql(t))) {
                return true;
            }
        }
        return false;
    }

    private static boolean causedBySql(Throwable t) {
        Throwable cause = t.getCause();
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                return true;
            }
        }
        return false;
    }
}
