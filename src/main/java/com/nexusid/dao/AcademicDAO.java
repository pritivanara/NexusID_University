package com.nexusid.dao;

import com.nexusid.db.Database;
import java.sql.*;
import java.util.*;

public class AcademicDAO {

    public List<String> listDepartments() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT id,code,name FROM departments ORDER BY code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add("#" + r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3));
        }
        return out;
    }

    public List<String> departmentOptions() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT id,code,name FROM departments ORDER BY code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add(r.getLong(1) + " | " + r.getString(2) + " - " + r.getString(3));
        }
        return out;
    }

    public List<String> listSubjects() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT s.id,s.code,s.name,d.code,s.semester,s.credits FROM subjects s LEFT JOIN departments d ON d.id=s.department_id ORDER BY s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add("#" + r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3) + " | Department: " + Objects.toString(r.getString(4), "Unassigned") + " | Semester: " + r.getInt(5) + " | Credits: " + r.getInt(6));
        }
        return out;
    }

    public List<String> activeStudentOptions() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT id,full_name,email FROM users WHERE role='STUDENT' AND status='ACTIVE' ORDER BY full_name,email";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add(r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3));
        }
        return out;
    }

    public List<String> listStudentEnrollments() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT u.id,u.full_name,u.email,d.code,s.code,s.name,ss.semester,ss.status " +
                "FROM student_subjects ss JOIN users u ON u.id=ss.student_id " +
                "JOIN subjects s ON s.id=ss.subject_id LEFT JOIN departments d ON d.id=s.department_id " +
                "WHERE u.role='STUDENT' ORDER BY u.full_name,ss.semester,s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add("Student #" + r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3) +
                    " | " + Objects.toString(r.getString(4), "N/A") + " | " + r.getString(5) + " - " + r.getString(6) +
                    " | Semester: " + r.getInt(7) + " | " + r.getString(8));
        }
        return out;
    }

    public int enrollStudentInDepartmentSemester(long actorId, long studentId, long departmentId, int semester) throws SQLException {
        if (studentId <= 0 || departmentId <= 0 || semester < 1 || semester > 12)
            throw new IllegalArgumentException("Select a student, department and valid semester.");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                requireAcademicManager(c, actorId);
                try (PreparedStatement p = c.prepareStatement("SELECT id FROM users WHERE id=? AND role='STUDENT' AND status='ACTIVE' FOR UPDATE")) {
                    p.setLong(1, studentId);
                    try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("The selected student account is missing or inactive."); }
                }
                List<Long> subjects = new ArrayList<>();
                try (PreparedStatement p = c.prepareStatement("SELECT id FROM subjects WHERE department_id=? AND semester=? ORDER BY code")) {
                    p.setLong(1, departmentId); p.setInt(2, semester);
                    try (ResultSet r = p.executeQuery()) { while (r.next()) subjects.add(r.getLong(1)); }
                }
                if (subjects.isEmpty()) throw new IllegalArgumentException("No subjects exist for this department and semester. Add a subject first.");
                Set<Long> alreadyEnrolled = new HashSet<>();
                try (PreparedStatement p = c.prepareStatement("SELECT subject_id FROM student_subjects WHERE student_id=? AND semester=? AND status='ENROLLED' FOR UPDATE")) {
                    p.setLong(1, studentId); p.setInt(2, semester);
                    try (ResultSet r = p.executeQuery()) { while (r.next()) alreadyEnrolled.add(r.getLong(1)); }
                }
                int newEnrollments = 0;
                String insert = "INSERT INTO student_subjects(student_id,subject_id,semester,status) VALUES(?,?,?,'ENROLLED') ON DUPLICATE KEY UPDATE semester=VALUES(semester),status='ENROLLED'";
                try (PreparedStatement p = c.prepareStatement(insert)) {
                    for (Long subjectId : subjects) {
                        if (alreadyEnrolled.contains(subjectId)) continue;
                        p.setLong(1, studentId); p.setLong(2, subjectId); p.setInt(3, semester); p.addBatch(); newEnrollments++;
                    }
                    if (newEnrollments == 0) throw new IllegalArgumentException("Student is already enrolled in all " + subjects.size() + " subject(s) for this department and semester.");
                    p.executeBatch();
                }
                c.commit();
                return newEnrollments;
            } catch (Exception ex) {
                c.rollback();
                if (ex instanceof SQLException sql) throw sql;
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Unable to enroll student.", ex);
            } finally { c.setAutoCommit(true); }
        }
    }

    public void createDepartment(long actorId, String name, String code) throws SQLException {
        String n = name == null ? "" : name.trim();
        String cd = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (n.isEmpty() || cd.isEmpty()) throw new IllegalArgumentException("Department name and code are required.");
        if (n.length() > 100 || cd.length() > 20 || !cd.matches("[A-Z0-9_-]+")) throw new IllegalArgumentException("Use a department code containing only letters, numbers, hyphens or underscores.");
        String q = "INSERT INTO departments(name,code) VALUES(?,?)";
        try (Connection c = Database.getConnection()) {
            requireAcademicManager(c, actorId);
            try (PreparedStatement p = c.prepareStatement(q)) { p.setString(1,n); p.setString(2,cd); p.executeUpdate(); }
        }
    }

    public void createSubject(long actorId, long departmentId, String code, String name, int semester, int credits) throws SQLException {
        String cd = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String n = name == null ? "" : name.trim();
        if (departmentId <= 0 || cd.isEmpty() || n.isEmpty()) throw new IllegalArgumentException("Department, subject code and subject name are required.");
        if (cd.length() > 30 || !cd.matches("[A-Z0-9_-]+")) throw new IllegalArgumentException("Use a subject code containing only letters, numbers, hyphens or underscores.");
        if (n.length() > 150 || semester < 1 || semester > 12 || credits < 1 || credits > 10) throw new IllegalArgumentException("Semester must be 1–12 and credits must be 1–10.");
        String q = "INSERT INTO subjects(department_id,code,name,credits,semester) VALUES(?,?,?,?,?)";
        try (Connection c = Database.getConnection()) {
            requireAcademicManager(c, actorId);
            try (PreparedStatement p = c.prepareStatement(q)) { p.setLong(1,departmentId); p.setString(2,cd); p.setString(3,n); p.setInt(4,semester); p.setInt(5,credits); p.executeUpdate(); }
        }
    }
    public List<String> activeFacultyOptions() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT id,full_name,email FROM users WHERE role='FACULTY' AND status='ACTIVE' ORDER BY full_name,email";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add(r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3));
        }
        return out;
    }

    public List<String> listFacultyAssignments() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT u.id,u.full_name,u.email,s.id,s.code,s.name,d.code,s.semester " +
                "FROM faculty_subjects fs JOIN users u ON u.id=fs.faculty_id " +
                "JOIN subjects s ON s.id=fs.subject_id LEFT JOIN departments d ON d.id=s.department_id " +
                "ORDER BY u.full_name,s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add("Faculty #" + r.getLong(1) + " | " + r.getString(2) + " | " + r.getString(3) +
                    " | Subject #" + r.getLong(4) + " | " + r.getString(5) + " - " + r.getString(6) +
                    " | Department: " + Objects.toString(r.getString(7), "N/A") + " | Semester: " + r.getInt(8));
        }
        return out;
    }

    public void assignFacultyToSubject(long actorId, long facultyId, long subjectId) throws SQLException {
        if (facultyId <= 0 || subjectId <= 0) throw new IllegalArgumentException("Select an active faculty member and a subject.");
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                requireAcademicManager(c, actorId);
                try (PreparedStatement p = c.prepareStatement("SELECT id FROM users WHERE id=? AND role='FACULTY' AND status='ACTIVE' FOR UPDATE")) {
                    p.setLong(1, facultyId);
                    try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("The selected faculty account is missing or inactive."); }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT id FROM subjects WHERE id=? FOR UPDATE")) {
                    p.setLong(1, subjectId);
                    try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("The selected subject no longer exists."); }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM faculty_subjects WHERE faculty_id=? AND subject_id=?")) {
                    p.setLong(1, facultyId); p.setLong(2, subjectId);
                    try (ResultSet r = p.executeQuery()) { if (r.next()) throw new IllegalArgumentException("This faculty member is already assigned to the selected subject."); }
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO faculty_subjects(faculty_id,subject_id) VALUES(?,?)")) {
                    p.setLong(1, facultyId); p.setLong(2, subjectId); p.executeUpdate();
                }
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                if (ex instanceof SQLException sql) throw sql;
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Unable to assign faculty to subject.", ex);
            } finally { c.setAutoCommit(true); }
        }
    }

    public List<String> studentSummary(long actorId, long studentId) throws SQLException {
        requireStudentRecordAccess(actorId, studentId);
        List<String> x = new ArrayList<>();
        String q = "SELECT s.code,s.name,COALESCE(r.marks,'-'),COALESCE(r.grade,'-') " +
                "FROM student_subjects ss JOIN subjects s ON s.id=ss.subject_id " +
                "LEFT JOIN results r ON r.id=(SELECT r2.id FROM results r2 WHERE r2.student_id=ss.student_id AND r2.subject_id=s.id ORDER BY r2.id DESC LIMIT 1) " +
                "WHERE ss.student_id=? ORDER BY s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, studentId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) x.add(r.getString(1)+"  |  "+r.getString(2)+"  |  Marks: "+r.getString(3)+"  |  Grade: "+r.getString(4));
            }
        }
        return x;
    }

    public List<String> studentTimetable(long actorId, long studentId) throws SQLException {
        requireStudentRecordAccess(actorId, studentId);
        return timetableFor("JOIN student_subjects ss ON ss.subject_id=t.subject_id WHERE ss.student_id=?", studentId);
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
                throw new SQLException("You are not authorized to view this student's academic records.");
            }
        }
    }

    public List<String> facultySummary(long uid) throws SQLException {
        List<String> x = new ArrayList<>();
        String q = "SELECT s.code,s.name,s.credits,s.semester " +
                "FROM faculty_subjects fs JOIN subjects s ON s.id=fs.subject_id " +
                "WHERE fs.faculty_id=? ORDER BY s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, uid);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) x.add(r.getString(1)+"  |  "+r.getString(2)+"  |  Credits: "+r.getInt(3)+"  |  Semester: "+r.getInt(4));
            }
        }
        return x;
    }

    public List<String> facultyTimetable(long uid) throws SQLException {
        return timetableFor("JOIN faculty_subjects fs ON fs.subject_id=t.subject_id WHERE fs.faculty_id=?", uid);
    }


    public List<String> facultyClasses(long facultyId) throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT s.code,s.name,s.semester,COUNT(CASE WHEN ss.status='ENROLLED' AND u.status='ACTIVE' THEN ss.student_id END) " +
                "FROM faculty_subjects fs JOIN subjects s ON s.id=fs.subject_id " +
                "LEFT JOIN student_subjects ss ON ss.subject_id=s.id " +
                "LEFT JOIN users u ON u.id=ss.student_id " +
                "WHERE fs.faculty_id=? GROUP BY s.id,s.code,s.name,s.semester ORDER BY s.code";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, facultyId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(r.getString(1) + " | " + r.getString(2) +
                        " | Semester: " + r.getInt(3) + " | Enrolled students: " + r.getInt(4));
            }
        }
        return out;
    }

    public List<String> facultyStudents(long facultyId, long subjectId) throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT u.id,u.full_name,u.email,ss.semester FROM faculty_subjects fs " +
                "JOIN student_subjects ss ON ss.subject_id=fs.subject_id " +
                "JOIN users u ON u.id=ss.student_id " +
                "WHERE fs.faculty_id=? AND fs.subject_id=? AND ss.status='ENROLLED' AND u.status='ACTIVE' " +
                "ORDER BY u.full_name";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, facultyId);
            p.setLong(2, subjectId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add("Student ID: " + r.getLong(1) + " | " + r.getString(2) +
                        " | " + r.getString(3) + " | Semester: " + r.getInt(4));
            }
        }
        return out;
    }

    private List<String> timetableFor(String relation, long uid) throws SQLException {
        List<String> x = new ArrayList<>();
        String q = "SELECT t.day_name,t.start_time,t.end_time,s.code,s.name,c.room_no " +
                "FROM timetable t JOIN subjects s ON s.id=t.subject_id " +
                "LEFT JOIN classrooms c ON c.id=t.classroom_id " + relation +
                " ORDER BY FIELD(t.day_name,'MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY'),t.start_time";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q)) {
            p.setLong(1, uid);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) x.add(r.getString(1)+"  "+r.getTime(2)+"–"+r.getTime(3)+"  "+r.getString(4)+" — "+r.getString(5)+"  ["+r.getString(6)+"]");
            }
        }
        return x;
    }
    public List<String> classroomOptions() throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT id,room_no,building FROM classrooms ORDER BY building,room_no";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
            while (r.next()) out.add(r.getLong(1) + " | " + r.getString(2) + " | " + Objects.toString(r.getString(3), ""));
        }
        return out;
    }

    public List<String> listTimetableEntries(long actorId) throws SQLException {
        List<String> out = new ArrayList<>();
        String q = "SELECT t.id,t.day_name,t.start_time,t.end_time,s.code,s.name,c.room_no FROM timetable t JOIN subjects s ON s.id=t.subject_id LEFT JOIN classrooms c ON c.id=t.classroom_id ORDER BY FIELD(t.day_name,'MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY'),t.start_time,s.code";
        try (Connection c = Database.getConnection()) {
            requireAcademicManager(c, actorId);
            try (PreparedStatement p = c.prepareStatement(q); ResultSet r = p.executeQuery()) {
                while (r.next()) out.add("#" + r.getLong(1) + " | " + r.getString(2) + " | " + r.getTime(3).toString() + "–" + r.getTime(4).toString() + " | " + r.getString(5) + " - " + r.getString(6) + " | Room: " + Objects.toString(r.getString(7), "Unassigned"));
            }
        }
        return out;
    }

    public String[] timetableDetails(long actorId, long id) throws SQLException {
        String q = "SELECT subject_id,classroom_id,day_name,start_time,end_time FROM timetable WHERE id=?";
        try (Connection c = Database.getConnection()) {
            requireAcademicManager(c, actorId);
            try (PreparedStatement p = c.prepareStatement(q)) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new SQLException("Timetable entry not found.");
                    return new String[]{Long.toString(r.getLong(1)), r.getObject(2) == null ? "" : Long.toString(r.getLong(2)), r.getString(3), r.getTime(4).toString(), r.getTime(5).toString()};
                }
            }
        }
    }

    public void createTimetableEntry(long actorId, long subjectId, Long classroomId, String day, String start, String end) throws SQLException {
        saveTimetableEntry(actorId, 0, subjectId, classroomId, day, start, end);
    }

    public void updateTimetableEntry(long actorId, long id, long subjectId, Long classroomId, String day, String start, String end) throws SQLException {
        if (id <= 0) throw new IllegalArgumentException("Select a timetable entry to update.");
        saveTimetableEntry(actorId, id, subjectId, classroomId, day, start, end);
    }

    private void saveTimetableEntry(long actorId, long id, long subjectId, Long classroomId, String day, String start, String end) throws SQLException {
        Set<String> days = Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY");
        if (subjectId <= 0 || (classroomId != null && classroomId <= 0) || day == null || !days.contains(day))
            throw new IllegalArgumentException("Select a valid subject, day and classroom.");
        java.time.LocalTime begin, finish;
        try { begin = java.time.LocalTime.parse(start); finish = java.time.LocalTime.parse(end); }
        catch (Exception ex) { throw new IllegalArgumentException("Enter start and end times in HH:mm format."); }
        if (!begin.isBefore(finish)) throw new IllegalArgumentException("End time must be later than start time.");
        try (Connection c = Database.getConnection()) {
            requireAcademicManager(c, actorId);
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("SELECT id FROM subjects WHERE id=?")) {
                    p.setLong(1, subjectId); try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Selected subject does not exist."); }
                }
                if (classroomId != null) try (PreparedStatement p = c.prepareStatement("SELECT id FROM classrooms WHERE id=?")) {
                    p.setLong(1, classroomId); try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Selected classroom does not exist."); }
                }
                String conflict = "SELECT t.id FROM timetable t WHERE t.day_name=? AND t.id<>? AND t.start_time < ? AND t.end_time > ? AND ((? IS NOT NULL AND t.classroom_id=?) OR EXISTS (SELECT 1 FROM faculty_subjects a JOIN faculty_subjects b ON a.faculty_id=b.faculty_id WHERE a.subject_id=t.subject_id AND b.subject_id=?)) LIMIT 1";
                try (PreparedStatement p = c.prepareStatement(conflict)) {
                    p.setString(1, day); p.setLong(2, id); p.setTime(3, Time.valueOf(finish)); p.setTime(4, Time.valueOf(begin));
                    if (classroomId == null) p.setNull(5, Types.BIGINT); else p.setLong(5, classroomId);
                    if (classroomId == null) p.setNull(6, Types.BIGINT); else p.setLong(6, classroomId);
                    p.setLong(7, subjectId);
                    try (ResultSet r = p.executeQuery()) { if (r.next()) throw new IllegalArgumentException("Timetable conflict: the classroom or an assigned faculty member is already scheduled during this time."); }
                }
                String sql = id == 0 ? "INSERT INTO timetable(subject_id,classroom_id,day_name,start_time,end_time) VALUES(?,?,?,?,?)" : "UPDATE timetable SET subject_id=?,classroom_id=?,day_name=?,start_time=?,end_time=? WHERE id=?";
                try (PreparedStatement p = c.prepareStatement(sql)) {
                    p.setLong(1, subjectId); if (classroomId == null) p.setNull(2, Types.BIGINT); else p.setLong(2, classroomId);
                    p.setString(3, day); p.setTime(4, Time.valueOf(begin)); p.setTime(5, Time.valueOf(finish)); if (id != 0) p.setLong(6, id);
                    if (p.executeUpdate() == 0 && id != 0) throw new SQLException("Timetable entry not found.");
                }
                c.commit();
            } catch (Exception ex) {
                try { c.rollback(); } catch (SQLException ignored) { }
                if (ex instanceof SQLException sql) throw sql;
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Unable to save timetable entry.", ex);
            } finally { c.setAutoCommit(true); }
        }
    }

    public void deleteTimetableEntry(long actorId, long id) throws SQLException {
        if (id <= 0) throw new IllegalArgumentException("Select a timetable entry to delete.");
        try (Connection c = Database.getConnection()) {
            requireAcademicManager(c, actorId);
            try (PreparedStatement p = c.prepareStatement("DELETE FROM timetable WHERE id=?")) {
                p.setLong(1, id); if (p.executeUpdate() == 0) throw new SQLException("Timetable entry not found.");
            }
        }
    }

    private void requireAcademicManager(Connection c, long actorId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT role FROM users WHERE id=? AND status='ACTIVE'")) {
            p.setLong(1, actorId); try (ResultSet r = p.executeQuery()) {
                if (!r.next() || !("ADMIN".equals(r.getString(1)) || "HOD".equals(r.getString(1))))
                    throw new SQLException("Active Admin or HOD access is required for timetable management.");
            }
        }
    }

}
