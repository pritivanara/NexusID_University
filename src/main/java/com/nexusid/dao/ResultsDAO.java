package com.nexusid.dao;

import com.nexusid.db.Database;
import java.sql.*;
import java.util.*;

public class ResultsDAO {
    public record Subject(long id, String code, String name) {
        @Override public String toString() { return code + " - " + name; }
    }
    public record Student(long id, String name, String email, String marks, String grade) {
        @Override public String toString() { return name + "  •  " + email; }
    }

    public List<Subject> facultySubjects(long actorId) throws SQLException {
        requireActiveRole(actorId, "FACULTY");
        List<Subject> out = new ArrayList<>();
        String q = "SELECT s.id,s.code,s.name FROM faculty_subjects fs JOIN subjects s ON s.id=fs.subject_id " +
                "WHERE fs.faculty_id=? ORDER BY s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, actorId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new Subject(r.getLong(1), r.getString(2), r.getString(3)));
            }
        }
        return out;
    }

    public List<Student> studentsFor(long actorId, long subjectId, String examName) throws SQLException {
        requireFacultySubject(actorId, subjectId);
        String exam = examName == null ? "" : examName.trim();
        if (exam.isEmpty() || exam.length() > 100) throw new IllegalArgumentException("Enter an exam name between 1 and 100 characters.");
        List<Student> out = new ArrayList<>();
        String q = "SELECT u.id,u.full_name,u.email,r.marks,r.grade FROM student_subjects ss " +
                "JOIN users u ON u.id=ss.student_id " +
                "LEFT JOIN results r ON r.student_id=ss.student_id AND r.subject_id=ss.subject_id AND r.exam_name=? " +
                "WHERE ss.subject_id=? AND ss.status='ENROLLED' AND u.status='ACTIVE' AND u.role='STUDENT' ORDER BY u.full_name";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setString(1, exam);
            p.setLong(2, subjectId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new Student(r.getLong(1), r.getString(2), r.getString(3),
                        r.getObject(4) == null ? "" : r.getString(4),
                        r.getString(5) == null ? "" : r.getString(5)));
            }
        }
        return out;
    }

    private void requireFacultySubject(long actorId, long subjectId) throws SQLException {
        try (Connection c = Database.getConnection()) { requireFacultySubject(c, actorId, subjectId); }
    }

    private void requireFacultySubject(Connection c, long actorId, long subjectId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT 1 FROM users u JOIN faculty_subjects fs ON fs.faculty_id=u.id " +
                "WHERE u.id=? AND u.role='FACULTY' AND u.status='ACTIVE' AND fs.subject_id=?")) {
            p.setLong(1, actorId); p.setLong(2, subjectId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Authorization denied: an active faculty member assigned to this subject is required.");
            }
        }
    }

    private void requireActiveRole(long actorId, String role) throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT 1 FROM users WHERE id=? AND role=? AND status='ACTIVE'")) {
            p.setLong(1, actorId); p.setString(2, role);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Authorization denied: active " + role + " access is required.");
            }
        }
    }

    public List<String> studentResults(long actorId, long studentId) throws SQLException {
        requireStudentRecordAccess(actorId, studentId);
        List<String> out=new ArrayList<>();
        String q="SELECT s.code,s.name,r.exam_name,r.marks,r.grade FROM results r JOIN subjects s ON s.id=r.subject_id WHERE r.student_id=? ORDER BY s.code,r.id DESC";
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,studentId);try(ResultSet r=p.executeQuery()){while(r.next())out.add(r.getString(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | Marks: "+r.getBigDecimal(4)+" | Grade: "+r.getString(5));}}
        return out;
    }

    public void save(long actorId, long studentId, long subjectId, String examName, double marks, String grade) throws SQLException {
        if (studentId <= 0 || subjectId <= 0) throw new IllegalArgumentException("Student and subject are required.");
        String exam = examName == null ? "" : examName.trim();
        if (exam.isEmpty() || exam.length() > 100) throw new IllegalArgumentException("Enter an exam name between 1 and 100 characters.");
        if (!Double.isFinite(marks) || marks < 0 || marks > 100) throw new IllegalArgumentException("Marks must be between 0 and 100.");
        String normalizedGrade = grade == null ? "" : grade.trim();
        if (normalizedGrade.isEmpty() || normalizedGrade.length() > 5) throw new IllegalArgumentException("A valid grade is required.");
        String enrolled = "SELECT 1 FROM student_subjects ss JOIN users u ON u.id=ss.student_id " +
                "WHERE ss.student_id=? AND ss.subject_id=? AND ss.status='ENROLLED' AND u.role='STUDENT' AND u.status='ACTIVE'";
        try (Connection c = Database.getConnection()) {
            requireFacultySubject(c, actorId, subjectId);
            try (PreparedStatement check = c.prepareStatement(enrolled)) {
                check.setLong(1, studentId); check.setLong(2, subjectId);
                try (ResultSet r = check.executeQuery()) {
                    if (!r.next()) throw new SQLException("Marks can only be saved for an active student enrolled in this subject.");
                }
            }
            String q = "INSERT INTO results(student_id,subject_id,marks,grade,exam_name) VALUES(?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE marks=VALUES(marks),grade=VALUES(grade)";
            try (PreparedStatement p = c.prepareStatement(q)) {
                p.setLong(1, studentId); p.setLong(2, subjectId); p.setDouble(3, marks); p.setString(4, normalizedGrade); p.setString(5, exam);
                p.executeUpdate();
            }
        }
    }
    private void requireStudentRecordAccess(long actorId, long studentId) throws SQLException {
        String q = "SELECT actor.role,actor.status,target.role,target.status FROM users actor JOIN users target ON target.id=? WHERE actor.id=?";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, studentId); p.setLong(2, actorId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next() || !"ACTIVE".equals(r.getString(2)) || !"STUDENT".equals(r.getString(3)) || !"ACTIVE".equals(r.getString(4)))
                    throw new SQLException("Active account and student record are required.");
                String role = r.getString(1);
                if ("STUDENT".equals(role) && actorId == studentId) return;
                if ("PARENT".equals(role)) {
                    try (PreparedStatement link = c.prepareStatement("SELECT 1 FROM parent_links WHERE parent_user_id=? AND student_id=?")) {
                        link.setLong(1, actorId); link.setLong(2, studentId);
                        try (ResultSet linked = link.executeQuery()) { if (linked.next()) return; }
                    }
                }
                throw new SQLException("You are not authorized to view this student's results.");
            }
        }
    }

}
