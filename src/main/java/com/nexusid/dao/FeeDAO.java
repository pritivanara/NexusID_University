package com.nexusid.dao;

import com.nexusid.db.Database;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

public class FeeDAO {
    public static final class Invoice {
        private final long id;
        private final String title;
        private final BigDecimal amount;
        private final java.sql.Date dueDate;
        private final String status;

        public Invoice(long id, String title, BigDecimal amount, java.sql.Date dueDate, String status) {
            this.id = id;
            this.title = title;
            this.amount = amount;
            this.dueDate = dueDate;
            this.status = status;
        }
        public long id() { return id; }
        public String title() { return title; }
        public BigDecimal amount() { return amount; }
        public String status() { return status; }
        @Override public String toString() {
            return "#" + id + " | " + title + " | ₹" + amount + " | Due: " + dueDate + " | " + status;
        }
    }

    public List<Invoice> invoices(long studentId) throws SQLException {
        if (studentId < 1) throw new SQLException("Authorization denied.");
        List<Invoice> rows = new ArrayList<>();
        String sql = "SELECT f.id,f.title,f.amount,f.due_date,f.status FROM fee_invoices f JOIN users u ON u.id=f.student_id WHERE f.student_id=? AND u.role='STUDENT' AND u.status='ACTIVE' ORDER BY f.due_date,f.id";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, studentId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rows.add(new Invoice(r.getLong(1), r.getString(2), r.getBigDecimal(3), r.getDate(4), r.getString(5)));
            }
        }
        return rows;
    }

    public List<String> student(long studentId) throws SQLException {
        List<String> rows = new ArrayList<>();
        for (Invoice invoice : invoices(studentId)) rows.add(invoice.toString());
        return rows;
    }

    public List<String> paymentHistory(long studentId) throws SQLException {
        if (studentId < 1) throw new SQLException("Authorization denied.");
        List<String> rows = new ArrayList<>();
        String sql = "SELECT p.paid_at,f.title,p.amount,p.reference_id FROM fee_payments p JOIN fee_invoices f ON f.id=p.invoice_id JOIN users u ON u.id=p.student_id WHERE p.student_id=? AND u.role='STUDENT' AND u.status='ACTIVE' ORDER BY p.paid_at DESC,p.id DESC";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, studentId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rows.add(r.getTimestamp(1) + " | " + r.getString(2) + " | ₹" + r.getBigDecimal(3) + " | Ref: " + r.getString(4));
            }
        }
        return rows;
    }

    public String payWithWallet(long studentId, long invoiceId) throws SQLException {
        if (studentId < 1 || invoiceId < 1) throw new SQLException("Select a valid invoice.");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("SELECT id FROM users WHERE id=? AND role='STUDENT' AND status='ACTIVE' FOR UPDATE")) {
                    p.setLong(1, studentId);
                    try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("An active student account is required."); }
                }
                BigDecimal amount;
                String title;
                try (PreparedStatement p = c.prepareStatement("SELECT title,amount FROM fee_invoices WHERE id=? AND student_id=? AND status='PENDING' FOR UPDATE")) {
                    p.setLong(1, invoiceId); p.setLong(2, studentId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new SQLException("Invoice is unavailable, already paid, or does not belong to you.");
                        title = r.getString(1); amount = r.getBigDecimal(2);
                    }
                }
                if (amount == null || amount.signum() <= 0) throw new SQLException("Invoice amount is invalid.");
                BigDecimal balance;
                try (PreparedStatement p = c.prepareStatement("SELECT balance FROM wallets WHERE user_id=? AND status='ACTIVE' FOR UPDATE")) {
                    p.setLong(1, studentId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new SQLException("An active Campus Pay wallet is required.");
                        balance = r.getBigDecimal(1);
                    }
                }
                if (balance.compareTo(amount) < 0) throw new SQLException("Insufficient Campus Pay balance. Add funds before paying this invoice.");
                try (PreparedStatement p = c.prepareStatement("UPDATE wallets SET balance=balance-? WHERE user_id=? AND status='ACTIVE' AND balance>=?")) {
                    p.setBigDecimal(1, amount); p.setLong(2, studentId); p.setBigDecimal(3, amount);
                    if (p.executeUpdate() != 1) throw new SQLException("Wallet debit failed; no payment was made.");
                }
                String ref = "NX-FEE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
                try (PreparedStatement p = c.prepareStatement("INSERT INTO fee_payments(invoice_id,student_id,amount,reference_id) VALUES(?,?,?,?)")) {
                    p.setLong(1, invoiceId); p.setLong(2, studentId); p.setBigDecimal(3, amount); p.setString(4, ref); p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("UPDATE fee_invoices SET status='PAID' WHERE id=? AND student_id=? AND status='PENDING'")) {
                    p.setLong(1, invoiceId); p.setLong(2, studentId);
                    if (p.executeUpdate() != 1) throw new SQLException("Invoice status changed; payment was cancelled.");
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO transactions(user_id,amount,transaction_type,description,reference_id,payment_method,status) VALUES(?,?,'DEBIT',?,?,?,'SUCCESS')")) {
                    p.setLong(1, studentId); p.setBigDecimal(2, amount); p.setString(3, "Fee payment: " + title); p.setString(4, ref); p.setString(5, "CAMPUS_WALLET"); p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")) {
                    p.setLong(1, studentId); p.setString(2, "Fee payment successful"); p.setString(3, title + " paid: ₹" + amount + ". Reference: " + ref);
                    p.executeUpdate();
                }
                c.commit();
                return "Fee paid successfully.\nInvoice: " + title + "\nAmount: ₹" + amount + "\nReference: " + ref;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                if (e instanceof SQLException se) throw se;
                throw new SQLException("Fee payment failed; the transaction was rolled back.", e);
            }
        }
    }
}
