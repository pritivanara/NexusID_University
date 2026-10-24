package com.nexusid.dao;
import com.nexusid.db.Database;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
public class MerchantOrdersDAO{
 public List<String> latest(long merchantUserId)throws Exception{requireActiveRole(merchantUserId,"MERCHANT");List<String>o=new ArrayList<>();String q="SELECT mo.reference_id,mo.created_at,u.full_name,o.name,mp.name,moi.quantity,mo.total_amount,mo.status,mo.note FROM merchant_orders mo JOIN merchants m ON m.id=mo.merchant_id JOIN users mu ON mu.id=m.user_id AND mu.role='MERCHANT' AND mu.status='ACTIVE' JOIN users u ON u.id=mo.payer_user_id JOIN merchant_order_items moi ON moi.order_id=mo.id JOIN merchant_products mp ON mp.id=moi.product_id JOIN merchant_outlets o ON o.id=mp.outlet_id WHERE m.user_id=? ORDER BY mo.created_at DESC,mo.id DESC LIMIT 100";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,merchantUserId);try(ResultSet r=p.executeQuery()){while(r.next())o.add(r.getString(1)+" | "+r.getTimestamp(2)+" | "+r.getString(3)+" | "+r.getString(5)+" x"+r.getInt(6)+" | ₹"+r.getBigDecimal(7)+" | "+r.getString(4)+" | "+r.getString(8)+(r.getString(9)==null?"":" | Note: "+r.getString(9)));}}return o;}
 public List<String> studentOrders(long studentId)throws Exception{requireActiveRole(studentId,"STUDENT");List<String>o=new ArrayList<>();String q="SELECT mo.reference_id,mo.created_at,mp.name,moi.quantity,mo.total_amount,o.name,mo.status FROM merchant_orders mo JOIN merchant_order_items moi ON moi.order_id=mo.id JOIN merchant_products mp ON mp.id=moi.product_id JOIN merchant_outlets o ON o.id=mp.outlet_id WHERE mo.payer_user_id=? ORDER BY mo.created_at DESC,mo.id DESC LIMIT 100";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,studentId);try(ResultSet r=p.executeQuery()){while(r.next())o.add(r.getString(1)+" | "+r.getTimestamp(2)+" | "+r.getString(3)+" x"+r.getInt(4)+" | ₹"+r.getBigDecimal(5)+" | "+r.getString(6)+" | "+r.getString(7));}}return o;}
 public void updateStatus(long merchantUserId,String ref,String newStatus)throws Exception{
  if(ref==null||ref.isBlank())throw new IllegalArgumentException("Order reference is required.");
  if(!Set.of("PAID","PROCESSING","READY","COLLECTED","CANCELLED","REFUNDED").contains(newStatus))throw new IllegalArgumentException("Invalid order status.");
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    long orderId,payerUserId;BigDecimal total;String current;
    String q="SELECT mo.id,mo.payer_user_id,mo.total_amount,mo.status FROM merchant_orders mo JOIN merchants m ON m.id=mo.merchant_id JOIN users u ON u.id=m.user_id AND u.role='MERCHANT' AND u.status='ACTIVE' WHERE m.user_id=? AND mo.reference_id=? FOR UPDATE";
    try(PreparedStatement p=c.prepareStatement(q)){p.setLong(1,merchantUserId);p.setString(2,ref.trim());try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Order not found for this merchant.");orderId=r.getLong(1);payerUserId=r.getLong(2);total=r.getBigDecimal(3);current=r.getString(4);}}
    if(current.equals(newStatus))throw new SQLException("Order is already " + newStatus + ".");
    boolean refund=newStatus.equals("CANCELLED")||newStatus.equals("REFUNDED");
    if(current.equals("COLLECTED"))throw new SQLException("A collected order cannot be changed.");
    if(current.equals("CANCELLED")||current.equals("REFUNDED"))throw new SQLException("This order is already closed.");
    if(newStatus.equals("PAID"))throw new SQLException("PAID is assigned automatically when a purchase completes; an order cannot be moved back to PAID.");
    if(newStatus.equals("PROCESSING")&&!current.equals("PAID"))throw new SQLException("PROCESSING is allowed only after PAID.");
    if(newStatus.equals("READY")&&!current.equals("PROCESSING"))throw new SQLException("READY is allowed only after PROCESSING.");
    if(newStatus.equals("COLLECTED")&&!current.equals("READY"))throw new SQLException("COLLECTED is allowed only after READY.");
    if(refund){
     long first=Math.min(payerUserId,merchantUserId),second=Math.max(payerUserId,merchantUserId);
     for(long uid:new long[]{first,second}){
      try(PreparedStatement p=c.prepareStatement("SELECT balance FROM wallets WHERE user_id=? AND status='ACTIVE' FOR UPDATE")){p.setLong(1,uid);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Active wallet not found for refund.");}}
     }
     BigDecimal merchantBalance;
     try(PreparedStatement p=c.prepareStatement("SELECT balance FROM wallets WHERE user_id=? AND status='ACTIVE' FOR UPDATE")){p.setLong(1,merchantUserId);try(ResultSet r=p.executeQuery()){r.next();merchantBalance=r.getBigDecimal(1);}}
     if(merchantBalance.compareTo(total)<0)throw new SQLException("Merchant wallet does not have enough balance to refund this order.");
     try(PreparedStatement p=c.prepareStatement("UPDATE wallets SET balance=balance-? WHERE user_id=? AND status='ACTIVE' AND balance>=?")){p.setBigDecimal(1,total);p.setLong(2,merchantUserId);p.setBigDecimal(3,total);if(p.executeUpdate()!=1)throw new SQLException("Merchant wallet changed or has insufficient funds; refund was cancelled.");}
     try(PreparedStatement p=c.prepareStatement("UPDATE wallets SET balance=balance+? WHERE user_id=? AND status='ACTIVE'")){p.setBigDecimal(1,total);p.setLong(2,payerUserId);if(p.executeUpdate()!=1)throw new SQLException("Customer wallet is unavailable; refund was cancelled.");}
     try(PreparedStatement p=c.prepareStatement("INSERT INTO transactions(user_id,amount,transaction_type,description,reference_id,related_user_id,payment_method,status) VALUES(?,?,?,?,?,?,?,'SUCCESS')")){p.setLong(1,merchantUserId);p.setBigDecimal(2,total);p.setString(3,"DEBIT");p.setString(4,"Refund: "+ref);p.setString(5,ref);p.setLong(6,payerUserId);p.setString(7,"CAMPUS_REFUND");p.executeUpdate();}
     try(PreparedStatement p=c.prepareStatement("INSERT INTO transactions(user_id,amount,transaction_type,description,reference_id,related_user_id,payment_method,status) VALUES(?,?,?,?,?,?,?,'SUCCESS')")){p.setLong(1,payerUserId);p.setBigDecimal(2,total);p.setString(3,"CREDIT");p.setString(4,"Campus order refund: "+ref);p.setString(5,ref);p.setLong(6,merchantUserId);p.setString(7,"CAMPUS_REFUND");p.executeUpdate();}
     try(PreparedStatement p=c.prepareStatement("UPDATE merchant_products mp JOIN merchant_order_items moi ON moi.product_id=mp.id SET mp.stock=mp.stock+moi.quantity WHERE moi.order_id=?")){p.setLong(1,orderId);if(p.executeUpdate()<1)throw new SQLException("Order items were not found; refund was cancelled.");}
    }
    try(PreparedStatement p=c.prepareStatement("UPDATE merchant_orders SET status=? WHERE id=? AND status=?")){p.setString(1,newStatus);p.setLong(2,orderId);p.setString(3,current);if(p.executeUpdate()!=1)throw new SQLException("Order status changed unexpectedly; update was cancelled.");}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")){p.setLong(1,payerUserId);p.setString(2,refund?"Campus Order Refund":"Campus Order Update");p.setString(3,refund?"Order "+ref+" was "+newStatus.toLowerCase()+" and ₹"+total+" was refunded to your wallet.":"Order "+ref+" status changed to "+newStatus+".");p.executeUpdate();}
    c.commit();
   }catch(Exception e){c.rollback();if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 private void requireActiveRole(long userId,String role)throws SQLException{
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT 1 FROM users WHERE id=? AND role=? AND status='ACTIVE'")){
   p.setLong(1,userId);p.setString(2,role);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Authorization denied: an active "+role+" account is required.");}
  }
 }

}
