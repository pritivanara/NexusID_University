package com.nexusid.dao;
import com.nexusid.db.Database; import java.sql.*; import java.util.*;
public class NotificationDAO {
 public List<String> latest(long uid)throws SQLException{
  if(uid<1)throw new SQLException("Authorization denied.");
  List<String>x=new ArrayList<>();
  String q="SELECT n.title,n.message FROM notifications n JOIN users u ON u.id=n.user_id WHERE n.user_id=? AND u.status='ACTIVE' ORDER BY n.created_at DESC LIMIT 8";
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){while(r.next())x.add(r.getString(1)+": "+r.getString(2));}}return x;
 }
}
