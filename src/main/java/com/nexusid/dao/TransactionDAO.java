package com.nexusid.dao;
import com.nexusid.db.Database;import java.sql.*;import java.util.*;
public class TransactionDAO{
 public List<String> latest(long userId)throws SQLException{
  if(userId<1)throw new SQLException("Authorization denied.");
  List<String>o=new ArrayList<>();
  String q="SELECT t.created_at,t.amount,t.transaction_type,t.description,t.reference_id,t.status FROM transactions t JOIN users u ON u.id=t.user_id WHERE t.user_id=? AND u.status='ACTIVE' ORDER BY t.created_at DESC,t.id DESC LIMIT 100";
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next()){String sign="DEBIT".equals(r.getString(3))?"-":"+";o.add(r.getTimestamp(1)+" | "+sign+"₹"+r.getBigDecimal(2)+" | "+r.getString(4)+" | "+(r.getString(5)==null?"":r.getString(5))+" | "+r.getString(6));}}}return o;
 }
}
