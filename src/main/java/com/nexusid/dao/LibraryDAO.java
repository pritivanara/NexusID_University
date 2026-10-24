package com.nexusid.dao;

import com.nexusid.db.Database;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.List;

public class LibraryDAO {
    private static final BigDecimal FINE_PER_DAY = new BigDecimal("5.00");

    private void markOverdue(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE library_issues SET status='OVERDUE' WHERE status='ISSUED' AND due_at < CURDATE()")) {
            p.executeUpdate();
        }
    }


    public List<String> catalogueForManagement(long actorId) throws SQLException {
        requireLibraryManager(actorId);
        List<String> out = new ArrayList<>();
        String q = "SELECT id,isbn,name,COALESCE(author,''),COALESCE(category,''),total_copies,available_copies,status FROM library_books ORDER BY name";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add("#" + r.getLong(1) + " | " + r.getString(3) + " | ISBN: " + r.getString(2) + " | " + r.getString(4) + " | " + r.getString(5) + " | Total: " + r.getInt(6) + " | Available: " + r.getInt(7) + " | " + r.getString(8));
        }
        return out;
    }

    public void addBook(long actorId, String isbn, String name, String author, String category, int copies) throws SQLException {
        requireLibraryManager(actorId);
        String cleanIsbn = isbn == null ? "" : isbn.trim();
        String cleanName = name == null ? "" : name.trim();
        if (cleanIsbn.isEmpty() || cleanName.isEmpty()) throw new SQLException("ISBN and book title are required.");
        if (cleanIsbn.length() > 40 || cleanName.length() > 200 || (author != null && author.trim().length() > 150) || (category != null && category.trim().length() > 100)) throw new SQLException("One or more fields exceed the allowed length.");
        if (copies < 1 || copies > 10000) throw new SQLException("Total copies must be between 1 and 10000.");
        String q = "INSERT INTO library_books(isbn,name,author,category,total_copies,available_copies,status) VALUES(?,?,?,?,?,?, 'AVAILABLE')";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setString(1, cleanIsbn); p.setString(2, cleanName); p.setString(3, author == null || author.isBlank() ? null : author.trim()); p.setString(4, category == null || category.isBlank() ? null : category.trim()); p.setInt(5, copies); p.setInt(6, copies); p.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw new SQLException("That ISBN already exists in the catalogue.", e);
            throw e;
        }
    }

    public void updateBook(long actorId, long bookId, String isbn, String name, String author, String category, int totalCopies, String status) throws SQLException {
        requireLibraryManager(actorId);
        if (bookId < 1) throw new SQLException("Select a book from the catalogue first.");
        String cleanIsbn = isbn == null ? "" : isbn.trim();
        String cleanName = name == null ? "" : name.trim();
        if (cleanIsbn.isEmpty() || cleanName.isEmpty()) throw new SQLException("ISBN and book title are required.");
        if (cleanIsbn.length() > 40 || cleanName.length() > 200 || (author != null && author.trim().length() > 150) || (category != null && category.trim().length() > 100)) throw new SQLException("One or more fields exceed the allowed length.");
        if (totalCopies < 1 || totalCopies > 10000) throw new SQLException("Total copies must be between 1 and 10000.");
        if (!java.util.Set.of("AVAILABLE", "INACTIVE").contains(status)) throw new SQLException("Invalid catalogue status.");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                int available; int total;
                try (PreparedStatement p = c.prepareStatement("SELECT total_copies,available_copies FROM library_books WHERE id=? FOR UPDATE")) {
                    p.setLong(1, bookId);
                    try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Book not found."); total = r.getInt(1); available = r.getInt(2); }
                }
                int borrowed = total - available;
                if (totalCopies < borrowed) throw new SQLException("Total copies cannot be less than the number currently on loan (" + borrowed + ").");
                String q = "UPDATE library_books SET isbn=?,name=?,author=?,category=?,total_copies=?,available_copies=?,status=? WHERE id=?";
                try (PreparedStatement p = c.prepareStatement(q)) {
                    p.setString(1, cleanIsbn); p.setString(2, cleanName); p.setString(3, author == null || author.isBlank() ? null : author.trim()); p.setString(4, category == null || category.isBlank() ? null : category.trim()); p.setInt(5, totalCopies); p.setInt(6, totalCopies - borrowed); p.setString(7, status); p.setLong(8, bookId);
                    if (p.executeUpdate() != 1) throw new SQLException("Book was not updated; refresh and try again.");
                }
                c.commit();
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw new SQLException("That ISBN already exists in the catalogue.", e);
            throw e;
        }
    }

    private void requireLibraryManager(long actorId) throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM users WHERE id=? AND role IN ('ADMIN','HOD') AND status='ACTIVE'")) {
            p.setLong(1, actorId);
            try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Only an active Admin or HOD can manage the library catalogue."); }
        }
    }

    public List<String> books() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT id,name,author,category,available_copies,total_copies FROM library_books WHERE status='AVAILABLE' ORDER BY name";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) {
                out.add("#" + r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3) + " | " + r.getString(4) + " | Available: " + r.getInt(5) + "/" + r.getInt(6));
            }
        }
        return out;
    }

    public List<String> myLoans(long userId) throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT li.id,b.name,li.issued_at,li.due_at,li.returned_at,li.status, " +
                "CASE WHEN li.returned_at IS NOT NULL THEN GREATEST(DATEDIFF(DATE(li.returned_at),li.due_at),0) " +
                "ELSE GREATEST(DATEDIFF(CURDATE(),li.due_at),0) END " +
                "FROM library_issues li JOIN library_books b ON b.id=li.book_id JOIN users u ON u.id=li.user_id WHERE li.user_id=? AND u.role=\'STUDENT\' AND u.status=\'ACTIVE\' ORDER BY li.id DESC LIMIT 100";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            markOverdue(c);
            p.setLong(1, userId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    long lateDays = r.getLong(7);
                    BigDecimal fine = FINE_PER_DAY.multiply(BigDecimal.valueOf(lateDays));
                    String returned = r.getTimestamp(5) == null ? "-" : r.getTimestamp(5).toString();
                    out.add("#" + r.getLong(1) + " | " + r.getString(2) + " | Issued: " + r.getTimestamp(3) +
                            " | Due: " + r.getDate(4) + " | Returned: " + returned + " | " + r.getString(6) +
                            " | Fine: ₹" + fine.setScale(2));
                }
            }
        }
        return out;
    }

    public void issue(long userId, long bookId) throws SQLException {
        String lockBook = "SELECT name,available_copies,status FROM library_books WHERE id=? FOR UPDATE";
        String check = "SELECT COUNT(*) FROM library_issues WHERE book_id=? AND user_id=? AND status IN ('ISSUED','OVERDUE')";
        Connection c = Database.getConnection();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement actor = c.prepareStatement("SELECT 1 FROM users WHERE id=? AND role='STUDENT' AND status='ACTIVE' FOR UPDATE")) {
                actor.setLong(1, userId);
                try (ResultSet r = actor.executeQuery()) {
                    if (!r.next()) throw new SQLException("Only an active student account can borrow library books.");
                }
            }
            String bookName;
            try (PreparedStatement p = c.prepareStatement(lockBook)) {
                p.setLong(1, bookId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new SQLException("Book not found.");
                    bookName = r.getString(1);
                    if (!"AVAILABLE".equals(r.getString(3)) || r.getInt(2) <= 0) throw new SQLException("No copy is currently available.");
                }
            }
            try (PreparedStatement p = c.prepareStatement(check)) {
                p.setLong(1, bookId); p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) { r.next(); if (r.getInt(1) > 0) throw new SQLException("You already have this book issued. Return it before borrowing another copy."); }
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO library_issues(book_id,user_id,due_at) VALUES(?,?,DATE_ADD(CURDATE(),INTERVAL 14 DAY))")) {
                p.setLong(1, bookId); p.setLong(2, userId); p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("UPDATE library_books SET available_copies=available_copies-1 WHERE id=? AND status='AVAILABLE' AND available_copies>0")) {
                p.setLong(1, bookId); if (p.executeUpdate() != 1) throw new SQLException("Book inventory changed; refresh and try again.");
            }
            notify(c, userId, "Library Update", "'" + bookName + "' has been issued to you. Due date is 14 days from today.");
            c.commit();
        } catch (Exception e) {
            try { c.rollback(); } catch (SQLException ignored) {}
            if (e instanceof SQLException se) throw se;
            throw new SQLException(e.getMessage(), e);
        } finally { c.close(); }
    }

    public BigDecimal returnBook(long userId, long issueId) throws SQLException {
        Connection c = Database.getConnection();
        try {
            c.setAutoCommit(false);
            requireActiveStudent(c, userId);
            long bookId;
            String bookName;
            Date due;
            String status;
            try (PreparedStatement p = c.prepareStatement("SELECT li.book_id,b.name,li.due_at,li.status FROM library_issues li JOIN library_books b ON b.id=li.book_id WHERE li.id=? AND li.user_id=? FOR UPDATE")) {
                p.setLong(1, issueId); p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new SQLException("Loan not found for your account.");
                    bookId = r.getLong(1); bookName = r.getString(2); due = r.getDate(3); status = r.getString(4);
                }
            }
            if ("RETURNED".equals(status)) throw new SQLException("This book has already been returned.");
            long lateDays = Math.max(0, ChronoUnit.DAYS.between(due.toLocalDate(), LocalDate.now()));
            BigDecimal fine = FINE_PER_DAY.multiply(BigDecimal.valueOf(lateDays)).setScale(2);
            try (PreparedStatement p = c.prepareStatement("UPDATE library_issues SET returned_at=NOW(),status='RETURNED' WHERE id=? AND user_id=? AND status IN ('ISSUED','OVERDUE')")) {
                p.setLong(1, issueId); p.setLong(2, userId); if (p.executeUpdate() != 1) throw new SQLException("Loan status changed; refresh and try again.");
            }
            try (PreparedStatement p = c.prepareStatement("UPDATE library_books SET available_copies=LEAST(total_copies,available_copies+1) WHERE id=?")) {
                p.setLong(1, bookId); if (p.executeUpdate() != 1) throw new SQLException("Book inventory could not be updated.");
            }
            String message = "'" + bookName + "' has been returned successfully." + (fine.signum() > 0 ? " Late fine calculated: ₹" + fine + "." : " No late fine.");
            notify(c, userId, "Library Return", message);
            c.commit();
            return fine;
        } catch (Exception e) {
            try { c.rollback(); } catch (SQLException ignored) {}
            if (e instanceof SQLException se) throw se;
            throw new SQLException(e.getMessage(), e);
        } finally { c.close(); }
    }

    public void renew(long userId, long issueId) throws SQLException {
        Connection c = Database.getConnection();
        try {
            c.setAutoCommit(false);
            requireActiveStudent(c, userId);
            String bookName;
            Date issuedAt;
            Date dueAt;
            String status;
            try (PreparedStatement p = c.prepareStatement("SELECT b.name,DATE(li.issued_at),li.due_at,li.status FROM library_issues li JOIN library_books b ON b.id=li.book_id WHERE li.id=? AND li.user_id=? FOR UPDATE")) {
                p.setLong(1, issueId); p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new SQLException("Loan not found for your account.");
                    bookName = r.getString(1); issuedAt = r.getDate(2); dueAt = r.getDate(3); status = r.getString(4);
                }
            }
            if (!"ISSUED".equals(status)) throw new SQLException("Only an active loan can be renewed.");
            if (dueAt.toLocalDate().isBefore(LocalDate.now())) throw new SQLException("Overdue books cannot be renewed. Please return the book first.");
            Calendar cal = Calendar.getInstance();
            cal.setTime(issuedAt); cal.add(Calendar.DAY_OF_YEAR, 21);
            if (!dueAt.before(cal.getTime())) throw new SQLException("This loan has already been renewed once.");
            try (PreparedStatement p = c.prepareStatement("UPDATE library_issues SET due_at=DATE_ADD(due_at,INTERVAL 7 DAY) WHERE id=? AND user_id=? AND status='ISSUED'")) {
                p.setLong(1, issueId); p.setLong(2, userId); if (p.executeUpdate() != 1) throw new SQLException("Loan status changed; refresh and try again.");
            }
            notify(c, userId, "Library Renewal", "'" + bookName + "' has been renewed for 7 additional days.");
            c.commit();
        } catch (Exception e) {
            try { c.rollback(); } catch (SQLException ignored) {}
            if (e instanceof SQLException se) throw se;
            throw new SQLException(e.getMessage(), e);
        } finally { c.close(); }
    }

    public List<String> history(long userId) throws SQLException {
        return myLoans(userId);
    }

    private void requireActiveStudent(Connection c, long userId) throws SQLException {
        if (userId < 1) throw new SQLException("Authorization denied.");
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM users WHERE id=? AND role='STUDENT' AND status='ACTIVE' FOR UPDATE")) {
            p.setLong(1, userId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Only an active student account can manage library loans.");
            }
        }
    }

    private void notify(Connection c, long userId, String title, String message) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")) {
            p.setLong(1, userId); p.setString(2, title); p.setString(3, message); p.executeUpdate();
        }
    }
}
