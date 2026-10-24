package com.nexusid.db;
import com.nexusid.config.AppConfig;import org.mindrot.jbcrypt.BCrypt;import java.sql.*;
public final class Database{
 private Database(){} static{try{Class.forName("com.mysql.cj.jdbc.Driver");}catch(ClassNotFoundException e){throw new ExceptionInInitializerError(e);}}
 public static Connection getConnection()throws SQLException{return DriverManager.getConnection(AppConfig.DB_URL,AppConfig.DB_USER,AppConfig.DB_PASSWORD);}
 public static void ensureDevUsers()throws SQLException{
  // Demo accounts are created only when missing. Never reset an existing password
  // or reactivate an account on application startup.
  String sql="INSERT IGNORE INTO users(full_name,email,phone,password_hash,role,status) VALUES "+
   "('Aarav Student','student@nexusid.edu','9000000001',?,'STUDENT','ACTIVE'),"+
   "('Ananya Faculty','faculty@nexusid.edu','9000000002',?,'FACULTY','ACTIVE'),"+
   "('University Admin','admin@nexusid.edu','9000000003',?,'ADMIN','ACTIVE'),"+
   "('Campus Merchant','merchant@nexusid.edu','9000000004',?,'MERCHANT','ACTIVE'),"+
   "('Rahul HOD','hod@nexusid.edu','9000000005',?,'HOD','ACTIVE'),"+
   "('Aarav Parent','parent@nexusid.edu','9000000006',?,'PARENT','ACTIVE'),"+
   "('Campus Security','security@nexusid.edu','9000000007',?,'SECURITY','ACTIVE')";
  String hash=BCrypt.hashpw("password",BCrypt.gensalt(10));
  try(Connection c=getConnection();PreparedStatement p=c.prepareStatement(sql)){
   for(int i=1;i<=7;i++)p.setString(i,hash);
   p.executeUpdate();
  }
 }
}
