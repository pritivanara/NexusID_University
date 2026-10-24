package com.nexusid.dao;
import com.nexusid.db.Database;import com.nexusid.model.Wallet;import java.math.BigDecimal;import java.sql.*;import java.util.UUID;
public class WalletDAO{
 public Wallet get(long uid)throws SQLException{try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT balance,daily_spend_limit FROM wallets WHERE user_id=? AND status='ACTIVE'")){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){if(r.next())return new Wallet(r.getBigDecimal(1),r.getBigDecimal(2));return new Wallet(BigDecimal.ZERO,new BigDecimal("5000"));}}}
 public String addDemoFunds(long uid,BigDecimal amount)throws SQLException{
  if(uid<1)throw new SQLException("An active account is required.");
  if(amount==null||amount.scale()>2||amount.compareTo(new BigDecimal("1.00"))<0||amount.compareTo(new BigDecimal("10000.00"))>0)throw new SQLException("Demo top-up must be between ₹1.00 and ₹10,000.00, with at most two decimal places.");
  amount=amount.setScale(2,java.math.RoundingMode.UNNECESSARY);
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    try(PreparedStatement p=c.prepareStatement("SELECT role FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE")){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){if(!r.next()||!java.util.Set.of("STUDENT","FACULTY","HOD","ADMIN").contains(r.getString(1)))throw new SQLException("Demo wallet funding is not enabled for this active account.");}}
    try(PreparedStatement p=c.prepareStatement("UPDATE wallets SET balance=balance+? WHERE user_id=? AND status='ACTIVE'")){p.setBigDecimal(1,amount);p.setLong(2,uid);if(p.executeUpdate()!=1)throw new SQLException("Active wallet not found.");}
    String ref="NX-DEMO-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(java.util.Locale.ROOT);
    try(PreparedStatement p=c.prepareStatement("INSERT INTO transactions(user_id,amount,transaction_type,description,reference_id,payment_method,status) VALUES(?,?,'CREDIT',?,?,?,'SUCCESS')")){p.setLong(1,uid);p.setBigDecimal(2,amount);p.setString(3,"DEMO WALLET TOP-UP — SIMULATED FUNDS, NOT REAL MONEY");p.setString(4,ref);p.setString(5,"DEMO_ONLY");p.executeUpdate();}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")){p.setLong(1,uid);p.setString(2,"Demo wallet top-up");p.setString(3,"Simulated demo funds of ₹"+amount+" were added. Reference: "+ref+". This is not a real payment.");p.executeUpdate();}
    c.commit();return "Demo top-up successful: ₹"+amount+" added. Reference: "+ref+". SIMULATED FUNDS — NOT REAL MONEY.";
   }catch(Exception e){c.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public String purchase(long uid,long productId,int qty,String note)throws SQLException{if(uid<1)throw new SQLException("An active account is required.");if(productId<1)throw new SQLException("Select a valid product.");if(qty<1)throw new SQLException("Quantity must be at least 1.");try(Connection c=Database.getConnection()){c.setAutoCommit(false);try{
  try(PreparedStatement actor=c.prepareStatement("SELECT role FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE")){actor.setLong(1,uid);try(ResultSet r=actor.executeQuery()){if(!r.next()||!java.util.Set.of("STUDENT","FACULTY","HOD","ADMIN").contains(r.getString(1)))throw new SQLException("Campus Pay is not enabled for this active account.");}}
  String q="SELECT p.name,p.price,p.stock,o.merchant_id,m.user_id FROM merchant_products p JOIN merchant_outlets o ON o.id=p.outlet_id JOIN merchants m ON m.id=o.merchant_id JOIN users mu ON mu.id=m.user_id AND mu.role='MERCHANT' AND mu.status='ACTIVE' WHERE p.id=? AND p.status='ACTIVE' AND o.status='ACTIVE' AND m.status='ACTIVE' FOR UPDATE";
  String name;BigDecimal total;int stock;long merchantId,merchantUserId;try(PreparedStatement p=c.prepareStatement(q)){p.setLong(1,productId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Product unavailable.");name=r.getString(1);BigDecimal price=r.getBigDecimal(2);stock=r.getInt(3);merchantId=r.getLong(4);merchantUserId=r.getLong(5);if(qty>stock)throw new SQLException("Only "+stock+" item(s) available.");total=price.multiply(BigDecimal.valueOf(qty));}}
  if(merchantUserId==uid)throw new SQLException("A merchant cannot purchase from its own merchant account.");
  BigDecimal bal,limit;try(PreparedStatement p=c.prepareStatement("SELECT balance,daily_spend_limit FROM wallets WHERE user_id=? AND status='ACTIVE' FOR UPDATE")){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Active wallet not found.");bal=r.getBigDecimal(1);limit=r.getBigDecimal(2);}}
  if(bal.compareTo(total)<0)throw new SQLException("Insufficient wallet balance.");
  BigDecimal spent=BigDecimal.ZERO;try(PreparedStatement p=c.prepareStatement("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE user_id=? AND transaction_type='DEBIT' AND status='SUCCESS' AND DATE(created_at)=CURDATE()")){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){r.next();spent=r.getBigDecimal(1);}}
  if(spent.add(total).compareTo(limit)>0)throw new SQLException("Daily spending limit exceeded.");
  try(PreparedStatement p=c.prepareStatement("UPDATE wallets SET balance=balance-? WHERE user_id=? AND status='ACTIVE' AND balance>=?")){p.setBigDecimal(1,total);p.setLong(2,uid);p.setBigDecimal(3,total);if(p.executeUpdate()!=1)throw new SQLException("Unable to debit the active wallet safely.");}
  try(PreparedStatement p=c.prepareStatement("UPDATE wallets SET balance=balance+? WHERE user_id=? AND status='ACTIVE'")){p.setBigDecimal(1,total);p.setLong(2,merchantUserId);if(p.executeUpdate()!=1)throw new SQLException("Merchant active wallet not found.");}
  String ref="NX-ORD-"+UUID.randomUUID().toString().replace("-", "").substring(0,20).toUpperCase(java.util.Locale.ROOT);
  try(PreparedStatement p=c.prepareStatement("INSERT INTO transactions(user_id,amount,transaction_type,description,reference_id,related_user_id,payment_method,status) VALUES(?,?,?,?,?,?,?,'SUCCESS')")){p.setLong(1,uid);p.setBigDecimal(2,total);p.setString(3,"DEBIT");p.setString(4,note==null||note.isBlank()?name:note);p.setString(5,ref);p.setLong(6,merchantUserId);p.setString(7,"CAMPUS_WALLET");p.executeUpdate();}
  try(PreparedStatement p=c.prepareStatement("INSERT INTO transactions(user_id,amount,transaction_type,description,reference_id,related_user_id,payment_method,status) VALUES(?,?,?,?,?,?,?,'SUCCESS')")){p.setLong(1,merchantUserId);p.setBigDecimal(2,total);p.setString(3,"CREDIT");p.setString(4,"Campus sale: "+name);p.setString(5,ref);p.setLong(6,uid);p.setString(7,"CAMPUS_WALLET");p.executeUpdate();}
  long orderId;try(PreparedStatement p=c.prepareStatement("INSERT INTO merchant_orders(payer_user_id,merchant_id,total_amount,status,reference_id,note) VALUES(?,?,?,'PAID',?,?)",Statement.RETURN_GENERATED_KEYS)){p.setLong(1,uid);p.setLong(2,merchantId);p.setBigDecimal(3,total);p.setString(4,ref);p.setString(5,note);p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();orderId=r.getLong(1);}}
  try(PreparedStatement p=c.prepareStatement("INSERT INTO merchant_order_items(order_id,product_id,quantity,unit_price) VALUES(?,?,?,(SELECT price FROM merchant_products WHERE id=?))")){p.setLong(1,orderId);p.setLong(2,productId);p.setInt(3,qty);p.setLong(4,productId);p.executeUpdate();}
  try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")){p.setLong(1,merchantUserId);p.setString(2,"New Campus Order");p.setString(3,"Order "+ref+" received from "+getUserName(c,uid)+" for "+name+" x"+qty+" (₹"+total+").");p.executeUpdate();}
  try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")){p.setLong(1,uid);p.setString(2,"Campus Pay");p.setString(3,"Payment successful for "+name+" x"+qty+". Order "+ref+" for ₹"+total+".");p.executeUpdate();}
  try(PreparedStatement p=c.prepareStatement("UPDATE merchant_products SET stock=stock-? WHERE id=? AND status='ACTIVE' AND stock>=?")){p.setInt(1,qty);p.setLong(2,productId);p.setInt(3,qty);if(p.executeUpdate()!=1)throw new SQLException("Product stock changed; payment was cancelled.");}
  c.commit();return "Payment successful. Order "+ref+" created.";
 }catch(Exception e){c.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}}
 }
 private String getUserName(Connection c,long uid)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT full_name FROM users WHERE id=?")){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){if(r.next())return r.getString(1);return "User";}}}
}
