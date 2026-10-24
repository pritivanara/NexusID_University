package com.nexusid.dao;
import com.nexusid.db.Database; import com.nexusid.model.*; import java.sql.*; import java.util.*;
public class CampusDAO {
 public List<Outlet> outlets()throws SQLException{List<Outlet>x=new ArrayList<>();try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT id,name,business_type FROM merchant_outlets WHERE status='ACTIVE' ORDER BY name");ResultSet r=p.executeQuery()){while(r.next())x.add(new Outlet(r.getLong(1),r.getString(2),r.getString(3)));}return x;}
 public List<Product> products(long oid)throws SQLException{List<Product>x=new ArrayList<>();try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT id,name,price,stock FROM merchant_products WHERE outlet_id=? AND status='ACTIVE' ORDER BY name")){p.setLong(1,oid);try(ResultSet r=p.executeQuery()){while(r.next())x.add(new Product(r.getLong(1),r.getString(2),r.getBigDecimal(3),r.getInt(4)));}}return x;}
}
