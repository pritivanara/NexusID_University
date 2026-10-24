package com.nexusid.service;

import com.nexusid.db.Database;
import java.sql.*;

public class BiometricService {
    private void requireActiveUser(long uid) throws SQLException {
        if (uid < 1) throw new SQLException("An active account is required.");
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM users WHERE id=? AND status='ACTIVE'")) {
            p.setLong(1, uid);
            try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("An active account is required."); }
        }
    }

    private void requireSecurityActor(long uid) throws SQLException {
        if (uid < 1) throw new SQLException("Security access denied.");
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM users WHERE id=? AND role='SECURITY' AND status='ACTIVE'")) {
            p.setLong(1, uid);
            try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Only an active Security account can search biometric templates."); }
        }
    }

    private String templateKind(String kind) throws SQLException {
        if (kind == null) throw new SQLException("Select FACE or PALM biometric type.");
        if (kind.equalsIgnoreCase("FACE")) return "FACE";
        if (kind.equalsIgnoreCase("PALM")) return "PALM";
        throw new SQLException("Select FACE or PALM biometric type.");
    }

    public boolean hasProfile(long uid) throws SQLException {
        requireActiveUser(uid);
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM biometric_profiles WHERE user_id=? AND status='ACTIVE'")) {
            p.setLong(1, uid); try (ResultSet r = p.executeQuery()) { return r.next(); }
        }
    }
    public String status(long uid) throws SQLException {
        requireActiveUser(uid);
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement("SELECT face_enabled,palm_enabled,liveness_enabled FROM biometric_profiles WHERE user_id=?")) {
            p.setLong(1, uid); try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return "Face: Off   Palm: Off   Liveness: Off";
                return "Face: " + (r.getBoolean(1)?"Enabled":"Off") + "   Palm: " + (r.getBoolean(2)?"Enabled":"Off") + "   Liveness: " + (r.getBoolean(3)?"Enabled":"Off");
            }
        }
    }
    public void saveTemplate(long uid, String kind, String template) throws SQLException {
        requireActiveUser(uid);
        String validatedKind = templateKind(kind);
        if (template == null || template.isBlank() || template.length() > 100000) throw new SQLException("Biometric template is empty or too large.");
        String column=validatedKind.equals("FACE")?"face_template":"palm_template";
        String enabled=validatedKind.equals("FACE")?"face_enabled":"palm_enabled";
        String sql="INSERT INTO biometric_profiles(user_id,"+column+","+enabled+",status) VALUES(?,?,TRUE,'ACTIVE') ON DUPLICATE KEY UPDATE "+column+"=VALUES("+column+"),"+enabled+"=TRUE,status='ACTIVE'";
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,uid);p.setString(2,template);p.executeUpdate();}
    }
    public String template(long uid,String kind)throws SQLException{
        requireActiveUser(uid);
        String validatedKind=templateKind(kind);
        String column=validatedKind.equals("FACE")?"face_template":"palm_template";
        String enabled=validatedKind.equals("FACE")?"face_enabled":"palm_enabled";
        String sql="SELECT "+column+" FROM biometric_profiles WHERE user_id=? AND status='ACTIVE' AND "+enabled+"=TRUE";
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setLong(1,uid);
            try(ResultSet r=p.executeQuery()){
                if(!r.next()) return null;
                String value=r.getString(1);
                return value==null||value.isBlank()?null:value;
            }
        }
    }
    public java.util.Map<Long,String> templates(long actorId,String kind)throws SQLException{
        requireSecurityActor(actorId);
        String validatedKind=templateKind(kind);
        String column=validatedKind.equals("FACE")?"face_template":"palm_template";
        String enabled=validatedKind.equals("FACE")?"face_enabled":"palm_enabled";
        java.util.Map<Long,String> out=new java.util.LinkedHashMap<>();
        String sql="SELECT bp.user_id,bp."+column+" FROM biometric_profiles bp JOIN users u ON u.id=bp.user_id WHERE bp.status='ACTIVE' AND bp."+enabled+"=TRUE AND bp."+column+" IS NOT NULL AND u.status='ACTIVE'";
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){
            while(r.next()) {
                String value=r.getString(2);
                if(value!=null&&!value.isBlank()) out.put(r.getLong(1),value);
            }
        }
        return out;
    }
    public void enableLiveness(long uid)throws SQLException{
        requireActiveUser(uid);
        String sql="INSERT INTO biometric_profiles(user_id,liveness_enabled,status) VALUES(?,TRUE,'ACTIVE') ON DUPLICATE KEY UPDATE liveness_enabled=TRUE,status='ACTIVE'";
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setLong(1,uid);p.executeUpdate();}
    }
}
