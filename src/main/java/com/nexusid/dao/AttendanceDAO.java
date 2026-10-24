package com.nexusid.dao;

import com.nexusid.db.Database;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class AttendanceDAO {
    public record Subject(long id, String code, String name) {
        @Override public String toString() { return code + " - " + name; }
    }

    public record Student(long id, String name, String email, String status, String method) {}

    public List<Subject> facultySubjects(long actorId) throws SQLException {
        requireActiveRole(actorId, "FACULTY");
        List<Subject> out = new ArrayList<>();
        String q = "SELECT s.id,s.code,s.name FROM faculty_subjects fs JOIN subjects s ON s.id=fs.subject_id " +
                   "WHERE fs.faculty_id=? ORDER BY s.code";
        try (Connection c=Database.getConnection(); PreparedStatement p=c.prepareStatement(q)) {
            p.setLong(1, actorId);
            try (ResultSet r=p.executeQuery()) {
                while(r.next()) out.add(new Subject(r.getLong(1),r.getString(2),r.getString(3)));
            }
        }
        return out;
    }

    public List<Student> studentsFor(long actorId, long subjectId, LocalDate date) throws SQLException {
        if (date == null) throw new IllegalArgumentException("Select a class date.");
        requireFacultySubject(actorId, subjectId);
        List<Student> out = new ArrayList<>();
        String q = "SELECT u.id,u.full_name,u.email,COALESCE(a.status,'UNMARKED'),COALESCE(a.method,'MANUAL') " +
                   "FROM student_subjects ss JOIN users u ON u.id=ss.student_id " +
                   "LEFT JOIN attendance a ON a.student_id=u.id AND a.subject_id=? AND a.class_date=? " +
                   "WHERE ss.subject_id=? AND ss.status='ENROLLED' AND u.status='ACTIVE' ORDER BY u.full_name";
        try (Connection c=Database.getConnection(); PreparedStatement p=c.prepareStatement(q)) {
            p.setLong(1,subjectId); p.setDate(2,java.sql.Date.valueOf(date)); p.setLong(3,subjectId);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) out.add(new Student(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)));
            }
        }
        return out;
    }

    public void save(long actorId, long studentId, long subjectId, LocalDate date, String status, String method) throws SQLException {
        if (studentId <= 0 || subjectId <= 0 || date == null) throw new IllegalArgumentException("Student, subject, and class date are required.");
        if (!Set.of("PRESENT", "ABSENT", "LATE").contains(status)) throw new IllegalArgumentException("Invalid attendance status.");
        if (!Set.of("MANUAL", "FACE", "PALM").contains(method)) throw new IllegalArgumentException("Invalid attendance method.");
        String enrolled = "SELECT 1 FROM student_subjects ss JOIN users u ON u.id=ss.student_id " +
                "WHERE ss.student_id=? AND ss.subject_id=? AND ss.status='ENROLLED' AND u.role='STUDENT' AND u.status='ACTIVE'";
        try (Connection c=Database.getConnection()) {
            requireFacultySubject(c, actorId, subjectId);
            try (PreparedStatement check=c.prepareStatement(enrolled)) {
                check.setLong(1,studentId); check.setLong(2,subjectId);
                try (ResultSet r=check.executeQuery()) { if(!r.next()) throw new SQLException("Attendance can only be saved for an active student enrolled in this subject."); }
            }
            String q = "INSERT INTO attendance(student_id,subject_id,class_date,status,method) VALUES(?,?,?,?,?) " +
                       "ON DUPLICATE KEY UPDATE status=VALUES(status),method=VALUES(method)";
            try(PreparedStatement p=c.prepareStatement(q)) {
                p.setLong(1,studentId); p.setLong(2,subjectId); p.setDate(3,java.sql.Date.valueOf(date));
                p.setString(4,status); p.setString(5,method); p.executeUpdate();
            }
        }
    }

    private void requireFacultySubject(long actorId, long subjectId) throws SQLException {
        try (Connection c=Database.getConnection()) { requireFacultySubject(c, actorId, subjectId); }
    }

    private void requireFacultySubject(Connection c, long actorId, long subjectId) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(
                "SELECT 1 FROM users u JOIN faculty_subjects fs ON fs.faculty_id=u.id " +
                "WHERE u.id=? AND u.role='FACULTY' AND u.status='ACTIVE' AND fs.subject_id=?")) {
            p.setLong(1,actorId); p.setLong(2,subjectId);
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) throw new SQLException("Authorization denied: an active faculty member assigned to this subject is required.");
            }
        }
    }

    private void requireActiveRole(long actorId, String role) throws SQLException {
        try (Connection c=Database.getConnection(); PreparedStatement p=c.prepareStatement(
                "SELECT 1 FROM users WHERE id=? AND role=? AND status='ACTIVE'")) {
            p.setLong(1,actorId); p.setString(2,role);
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) throw new SQLException("Authorization denied: active " + role + " access is required.");
            }
        }
    }

    public List<String> studentSummary(long actorId, long studentId) throws SQLException {
        requireStudentRecordAccess(actorId, studentId);
        List<String> out=new ArrayList<>();
        String q="SELECT s.code,s.name, " +
                  "SUM(CASE WHEN a.status='PRESENT' THEN 1 ELSE 0 END), " +
                  "SUM(CASE WHEN a.status='LATE' THEN 1 ELSE 0 END), " +
                  "SUM(CASE WHEN a.status='ABSENT' THEN 1 ELSE 0 END), COUNT(a.id) " +
                  "FROM student_subjects ss JOIN subjects s ON s.id=ss.subject_id " +
                  "LEFT JOIN attendance a ON a.student_id=ss.student_id AND a.subject_id=ss.subject_id " +
                  "WHERE ss.student_id=? AND ss.status='ENROLLED' GROUP BY s.id,s.code,s.name ORDER BY s.code";
        try(Connection c=Database.getConnection(); PreparedStatement p=c.prepareStatement(q)) {
            p.setLong(1,studentId);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    int present=r.getInt(3), late=r.getInt(4), absent=r.getInt(5), total=r.getInt(6);
                    double pct=total==0?0:((present+late)*100.0/total);
                    out.add(String.format("%s  |  %s  |  Present: %d  |  Late: %d  |  Absent: %d  |  Attendance: %.1f%%",r.getString(1),r.getString(2),present,late,absent,pct));
                }
            }
        }
        return out;
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
                throw new SQLException("You are not authorized to view this student's attendance.");
            }
        }
    }

}
