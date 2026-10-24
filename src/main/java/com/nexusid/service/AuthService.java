package com.nexusid.service;
import com.nexusid.db.Database; import com.nexusid.model.User; import org.mindrot.jbcrypt.BCrypt; import java.sql.*;
public class AuthService {
 public User login(String email,String password) throws SQLException {
  String sql="SELECT id,full_name,email,role,password_hash FROM users WHERE LOWER(email)=? AND status='ACTIVE'";
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT));try(ResultSet r=p.executeQuery()){if(!r.next())return null;String hash=r.getString("password_hash");if(hash==null||!BCrypt.checkpw(password,hash))return null;return new User(r.getLong(1),r.getString(2),r.getString(3),r.getString(4));}}
 }
}
