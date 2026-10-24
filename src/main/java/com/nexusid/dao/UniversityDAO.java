package com.nexusid.dao;
import com.nexusid.db.Database;
import java.sql.*;import java.util.*;import org.mindrot.jbcrypt.BCrypt;
public class UniversityDAO {
 private void requireRole(long actorId,String... allowed)throws SQLException{
  if(actorId<1)throw new SQLException("Authorization denied.");
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT role,status FROM users WHERE id=?")){
   p.setLong(1,actorId);
   try(ResultSet r=p.executeQuery()){
    if(!r.next()||!"ACTIVE".equals(r.getString("status")))throw new SQLException("Authorization denied: active account required.");
    String role=r.getString("role");
    for(String a:allowed)if(a.equals(role))return;
    throw new SQLException("Authorization denied: insufficient role.");
   }
  }
 }
 private void requireActiveAccount(long userId)throws SQLException{
  if(userId<1)throw new SQLException("Authorization denied.");
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT 1 FROM users WHERE id=? AND status='ACTIVE'")){
   p.setLong(1,userId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Authorization denied: active account required.");}
  }
 }

 public List<String> events(long userId)throws SQLException{requireActiveAccount(userId);List<String>o=new ArrayList<>();String q="SELECT e.id,e.title,e.event_date,e.location,e.status,(SELECT COUNT(*) FROM event_registrations er WHERE er.event_id=e.id),EXISTS(SELECT 1 FROM event_registrations er2 WHERE er2.event_id=e.id AND er2.user_id=?),e.capacity,e.description FROM events e WHERE e.status<>'CANCELLED' ORDER BY e.event_date";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next())o.add(r.getLong(1)+" | "+r.getString(2)+" | "+r.getDate(3)+" | "+r.getString(4)+" | "+r.getString(5)+" | Registered: "+r.getBoolean(7)+" | Seats used: "+r.getInt(6)+"/"+r.getInt(8)+(r.getString(9)==null||r.getString(9).isBlank()?"":" | "+r.getString(9)));}}return o;}
 public List<String> eventsForManagement(long actorId)throws SQLException{
  requireRole(actorId,"ADMIN","HOD");
  List<String> out=new ArrayList<>();
  String q="SELECT e.id,e.title,e.event_date,e.location,e.status,e.capacity,e.description,(SELECT COUNT(*) FROM event_registrations er WHERE er.event_id=e.id) FROM events e ORDER BY e.event_date,e.id";
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q);ResultSet r=p.executeQuery()){
   while(r.next()){java.time.LocalDate eventDate=r.getDate(3).toLocalDate();String lifecycle="CANCELLED".equals(r.getString(5))?"CANCELLED":"CLOSED".equals(r.getString(5))?"CLOSED":eventDate.isBefore(java.time.LocalDate.now())?"COMPLETED":eventDate.equals(java.time.LocalDate.now())?"ONGOING":"UPCOMING";out.add(r.getLong(1)+" | "+r.getString(2)+" | "+eventDate+" | "+r.getString(4)+" | "+r.getString(5)+" | Lifecycle: "+lifecycle+" | Seats used: "+r.getInt(8)+"/"+r.getInt(6)+(r.getString(7)==null||r.getString(7).isBlank()?"":" | "+r.getString(7)));}
  }
  return out;
 }
 public List<String> eventRegistrations(long actorId,long eventId)throws SQLException{
  requireRole(actorId,"ADMIN","HOD");
  if(eventId<1)throw new SQLException("Enter a valid event ID.");
  List<String> out=new ArrayList<>();
  String q="SELECT er.id,u.id,u.full_name,u.email,er.registered_at FROM event_registrations er JOIN users u ON u.id=er.user_id WHERE er.event_id=? ORDER BY er.registered_at";
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,eventId);try(ResultSet r=p.executeQuery()){while(r.next())out.add("Registration #"+r.getLong(1)+" | Student #"+r.getLong(2)+" | "+r.getString(3)+" | "+r.getString(4)+" | "+r.getTimestamp(5));}}
  return out;
 }
 public void createEvent(long actorId,String title,String description,java.time.LocalDate eventDate,String location,int capacity)throws SQLException{
  requireRole(actorId,"ADMIN","HOD");
  title=title==null?"":title.trim();description=description==null?"":description.trim();location=location==null?"":location.trim();
  if(title.isEmpty()||title.length()>150)throw new SQLException("Event title is required (maximum 150 characters).");
  if(description.length()>500)throw new SQLException("Description must be 500 characters or fewer.");
  if(location.isEmpty()||location.length()>150)throw new SQLException("Location is required (maximum 150 characters).");
  if(eventDate==null||eventDate.isBefore(java.time.LocalDate.now()))throw new SQLException("Choose today or a future event date.");
  if(capacity<1||capacity>100000)throw new SQLException("Capacity must be between 1 and 100000.");
  String q="INSERT INTO events(title,description,event_date,location,capacity,status) VALUES(?,?,?,?,?,'OPEN')";
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setString(1,title);p.setString(2,description);p.setDate(3,java.sql.Date.valueOf(eventDate));p.setString(4,location);p.setInt(5,capacity);if(p.executeUpdate()!=1)throw new SQLException("Event was not created.");}
 }
 public void updateEvent(long actorId,long eventId,String title,String description,java.time.LocalDate eventDate,String location,int capacity,String status)throws SQLException{
  requireRole(actorId,"ADMIN","HOD");
  title=title==null?"":title.trim();description=description==null?"":description.trim();location=location==null?"":location.trim();status=status==null?"":status.trim();
  if(eventId<1)throw new SQLException("Enter a valid event ID.");
  if(title.isEmpty()||title.length()>150)throw new SQLException("Event title is required (maximum 150 characters).");
  if(description.length()>500)throw new SQLException("Description must be 500 characters or fewer.");
  if(location.isEmpty()||location.length()>150)throw new SQLException("Location is required (maximum 150 characters).");
  if(eventDate==null||eventDate.isBefore(java.time.LocalDate.now()))throw new SQLException("Choose today or a future event date.");
  if(capacity<1||capacity>100000)throw new SQLException("Capacity must be between 1 and 100000.");
  if(!java.util.Set.of("OPEN","CLOSED","CANCELLED").contains(status))throw new SQLException("Choose a valid event status.");
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    try(PreparedStatement lock=c.prepareStatement("SELECT status FROM events WHERE id=? FOR UPDATE")){lock.setLong(1,eventId);try(ResultSet r=lock.executeQuery()){if(!r.next())throw new SQLException("Event not found.");}}
    try(PreparedStatement count=c.prepareStatement("SELECT COUNT(*) FROM event_registrations WHERE event_id=?")){count.setLong(1,eventId);try(ResultSet r=count.executeQuery()){r.next();if(capacity<r.getInt(1))throw new SQLException("Capacity cannot be lower than the current registration count ("+r.getInt(1)+").");}}
    try(PreparedStatement p=c.prepareStatement("UPDATE events SET title=?,description=?,event_date=?,location=?,capacity=?,status=? WHERE id=?")){p.setString(1,title);p.setString(2,description);p.setDate(3,java.sql.Date.valueOf(eventDate));p.setString(4,location);p.setInt(5,capacity);p.setString(6,status);p.setLong(7,eventId);if(p.executeUpdate()!=1)throw new SQLException("Event was not updated.");}
    c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public void registerEvent(long userId,long eventId)throws SQLException{
  requireRole(userId,"STUDENT");
  if(eventId<1)throw new SQLException("Select a valid event.");
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    int capacity;String status;java.sql.Date eventDate;
    try(PreparedStatement p=c.prepareStatement("SELECT capacity,status,event_date FROM events WHERE id=? FOR UPDATE")){
     p.setLong(1,eventId);try(ResultSet r=p.executeQuery()){
      if(!r.next())throw new SQLException("Event not found.");
      capacity=r.getInt(1);status=r.getString(2);eventDate=r.getDate(3);
     }
    }
    if(!"OPEN".equals(status))throw new SQLException("Event is closed or unavailable.");
    if(eventDate.toLocalDate().isBefore(java.time.LocalDate.now()))throw new SQLException("Past events cannot be registered.");
    try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM event_registrations WHERE event_id=?")){
     p.setLong(1,eventId);try(ResultSet r=p.executeQuery()){r.next();if(r.getInt(1)>=capacity)throw new SQLException("Event is full.");}
    }
    try(PreparedStatement p=c.prepareStatement("INSERT INTO event_registrations(event_id,user_id) VALUES(?,?)")){
     p.setLong(1,eventId);p.setLong(2,userId);p.executeUpdate();
    }
    String title;
    try(PreparedStatement p=c.prepareStatement("SELECT title FROM events WHERE id=?")){
     p.setLong(1,eventId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Event not found after registration.");title=r.getString(1);}
    }
    try(PreparedStatement n=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")){
     n.setLong(1,userId);n.setString(2,"Event Update");n.setString(3,"You have successfully registered for '"+title+"' (Event #"+eventId+").");n.executeUpdate();
    }
    c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public void cancelEventRegistration(long userId,long registrationId)throws SQLException{
  requireRole(userId,"STUDENT");
  String find="SELECT er.event_id,e.title,e.status,e.event_date FROM event_registrations er JOIN events e ON e.id=er.event_id WHERE er.id=? AND er.user_id=? FOR UPDATE";
  String del="DELETE FROM event_registrations WHERE id=? AND user_id=?";
  String note="INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)";
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
   try(PreparedStatement p=c.prepareStatement(find)){
    p.setLong(1,registrationId); p.setLong(2,userId);
    try(ResultSet r=p.executeQuery()){
     if(!r.next()) throw new SQLException("Registration not found or does not belong to you.");
     long eventId=r.getLong(1); String title=r.getString(2); String status=r.getString(3); java.sql.Date eventDate=r.getDate(4);
     if(!"OPEN".equals(status)) throw new SQLException("Registration cannot be cancelled because the event is no longer open.");
     if(eventDate.toLocalDate().isBefore(java.time.LocalDate.now())) throw new SQLException("Registration cannot be cancelled after the event date.");
     try(PreparedStatement d=c.prepareStatement(del)){d.setLong(1,registrationId);d.setLong(2,userId);if(d.executeUpdate()==0)throw new SQLException("Registration could not be cancelled.");}
     try(PreparedStatement n=c.prepareStatement(note)){n.setLong(1,userId);n.setString(2,"Event Update");n.setString(3,"Your registration for '"+title+"' (Event #"+eventId+") has been cancelled successfully.");n.executeUpdate();}
    }
   }
   c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public List<String> myEventRegistrations(long userId)throws SQLException{requireRole(userId,"STUDENT","PARENT");List<String>o=new ArrayList<>();String q="SELECT er.id,e.id,e.title,e.event_date,e.location,e.status,er.registered_at FROM event_registrations er JOIN events e ON e.id=er.event_id WHERE er.user_id=? ORDER BY e.event_date DESC,er.id DESC";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next())o.add("Registration #"+r.getLong(1)+" | Event #"+r.getLong(2)+" | "+r.getString(3)+" | "+r.getDate(4)+" | "+r.getString(5)+" | "+r.getString(6)+" | Registered: "+r.getTimestamp(7));}}return o;}
 public List<String> library(long userId)throws SQLException{requireRole(userId,"STUDENT");List<String>o=new ArrayList<>();String q="SELECT b.name,b.author,b.category,b.available_copies,(SELECT li.status FROM library_issues li WHERE li.book_id=b.id AND li.user_id=? ORDER BY li.id DESC LIMIT 1) FROM library_books b WHERE b.status='AVAILABLE' ORDER BY b.name";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next())o.add(r.getString(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | Available: "+r.getInt(4)+(r.getString(5)==null?"":" | Your latest: "+r.getString(5)));}}return o;}
 public void issueBook(long userId,String bookName)throws SQLException{requireRole(userId,"STUDENT");String q="INSERT INTO library_issues(book_id,user_id,due_at) SELECT b.id,?,DATE_ADD(CURDATE(),INTERVAL 14 DAY) FROM library_books b WHERE b.name=? AND b.status=\'AVAILABLE\' AND b.available_copies>0 AND NOT EXISTS(SELECT 1 FROM library_issues li WHERE li.book_id=b.id AND li.user_id=? AND li.status=\'ISSUED\')";Connection c=Database.getConnection();try{c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement(q)){p.setLong(1,userId);p.setString(2,bookName);p.setLong(3,userId);if(p.executeUpdate()==0)throw new SQLException("Book unavailable or already issued to you.");}try(PreparedStatement p=c.prepareStatement("UPDATE library_books SET available_copies=available_copies-1 WHERE name=?")){p.setString(1,bookName);p.executeUpdate();}c.commit();}catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}finally{c.close();}} public List<String> maintenance(long userId,boolean all)throws SQLException{if(all)requireRole(userId,"ADMIN","HOD");else requireActiveAccount(userId);ensureMaintenanceHistoryTable();List<String>o=new ArrayList<>();String q="SELECT mr.id,u.full_name,mr.category,mr.description,mr.location,mr.status,mr.created_at FROM maintenance_requests mr JOIN users u ON u.id=mr.user_id "+(all?"":"WHERE mr.user_id=? ")+"ORDER BY mr.created_at DESC LIMIT 100";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){if(!all)p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next())o.add("#"+r.getLong(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | "+r.getString(4)+" | "+r.getString(5)+" | "+r.getString(6));}}return o;}
 public List<String> maintenanceHistory(long userId,boolean all)throws SQLException{if(all)requireRole(userId,"ADMIN","HOD");else requireActiveAccount(userId);ensureMaintenanceHistoryTable();List<String>o=new ArrayList<>();String q="SELECT h.request_id,COALESCE(u.full_name,'System'),h.old_status,h.new_status,h.changed_at,h.note FROM maintenance_status_history h JOIN maintenance_requests mr ON mr.id=h.request_id LEFT JOIN users u ON u.id=h.changed_by "+(all?"":"WHERE mr.user_id=? ")+"ORDER BY h.changed_at DESC,h.id DESC LIMIT 200";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){if(!all)p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next()){String old=r.getString(3);o.add("Request #"+r.getLong(1)+" | "+(old==null?"CREATED":old)+" → "+r.getString(4)+" | By: "+r.getString(2)+" | "+r.getTimestamp(5)+" | "+r.getString(6));}}}return o;}
 private void ensureMaintenanceHistoryTable()throws SQLException{
  String q="CREATE TABLE IF NOT EXISTS maintenance_status_history(id BIGINT AUTO_INCREMENT PRIMARY KEY,request_id BIGINT NOT NULL,changed_by BIGINT NOT NULL,old_status VARCHAR(20) NULL,new_status VARCHAR(20) NOT NULL,note VARCHAR(500) NOT NULL,changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_maintenance_history_request(request_id),INDEX idx_maintenance_history_changed_at(changed_at),CONSTRAINT fk_maintenance_history_request FOREIGN KEY(request_id) REFERENCES maintenance_requests(id) ON DELETE CASCADE,CONSTRAINT fk_maintenance_history_user FOREIGN KEY(changed_by) REFERENCES users(id)) ENGINE=InnoDB";
  try(Connection c=Database.getConnection();Statement st=c.createStatement()){
   st.executeUpdate(q);
   String backfill="INSERT INTO maintenance_status_history(request_id,changed_by,old_status,new_status,note,changed_at) SELECT mr.id,mr.user_id,NULL,mr.status,'Existing request imported into maintenance history.',mr.created_at FROM maintenance_requests mr WHERE NOT EXISTS (SELECT 1 FROM maintenance_status_history h WHERE h.request_id=mr.id)";
   st.executeUpdate(backfill);
  }
 }
 public void createMaintenance(long userId,String category,String description,String location)throws SQLException{requireActiveAccount(userId);if(category==null||category.isBlank()||description==null||description.isBlank()||location==null||location.isBlank())throw new SQLException("Enter a category, description, and location.");ensureMaintenanceHistoryTable();try(Connection c=Database.getConnection()){c.setAutoCommit(false);try{long requestId;try(PreparedStatement p=c.prepareStatement("INSERT INTO maintenance_requests(user_id,category,description,location) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){p.setLong(1,userId);p.setString(2,category.trim());p.setString(3,description.trim());p.setString(4,location.trim());p.executeUpdate();try(ResultSet keys=p.getGeneratedKeys()){if(!keys.next())throw new SQLException("Could not create maintenance request.");requestId=keys.getLong(1);}}try(PreparedStatement p=c.prepareStatement("INSERT INTO maintenance_status_history(request_id,changed_by,old_status,new_status,note) VALUES(?,?,NULL,'OPEN','Maintenance request submitted.')")){p.setLong(1,requestId);p.setLong(2,userId);p.executeUpdate();}try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,'Maintenance Update',?)")){p.setLong(1,userId);p.setString(2,"Your maintenance request #"+requestId+" has been submitted and is OPEN.");p.executeUpdate();}c.commit();}catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}}}
 public void updateMaintenance(long adminId,long requestId,String status)throws SQLException{requireRole(adminId,"ADMIN","HOD");if(!Set.of("OPEN","IN_PROGRESS","RESOLVED","CANCELLED").contains(status))throw new IllegalArgumentException("Invalid status.");ensureMaintenanceHistoryTable();try(Connection c=Database.getConnection()){c.setAutoCommit(false);try{long ownerId;String oldStatus;try(PreparedStatement p=c.prepareStatement("SELECT user_id,status FROM maintenance_requests WHERE id=? FOR UPDATE")){p.setLong(1,requestId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Request not found.");ownerId=r.getLong(1);oldStatus=r.getString(2);}}if(status.equals(oldStatus))throw new SQLException("Request #"+requestId+" is already "+status+".");try(PreparedStatement p=c.prepareStatement("UPDATE maintenance_requests SET status=? WHERE id=?")){p.setString(1,status);p.setLong(2,requestId);p.executeUpdate();}String note="Status changed from "+oldStatus+" to "+status+".";try(PreparedStatement p=c.prepareStatement("INSERT INTO maintenance_status_history(request_id,changed_by,old_status,new_status,note) VALUES(?,?,?,?,?)")){p.setLong(1,requestId);p.setLong(2,adminId);p.setString(3,oldStatus);p.setString(4,status);p.setString(5,note);p.executeUpdate();}try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,'Maintenance Update',?)")){p.setLong(1,ownerId);p.setString(2,"Your maintenance request #"+requestId+" status changed from "+oldStatus+" to "+status+".");p.executeUpdate();}c.commit();}catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}}}
 public List<String> accessLogs(long userId,boolean all)throws SQLException{if(all)requireRole(userId,"ADMIN","SECURITY");else requireActiveAccount(userId);List<String>o=new ArrayList<>();String q="SELECT al.created_at,COALESCE(u.full_name,'Unknown'),al.method,al.entry_type,al.location,al.status FROM access_logs al LEFT JOIN users u ON u.id=al.user_id "+(all?"":"WHERE al.user_id=? ")+"ORDER BY al.created_at DESC LIMIT 100";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){if(!all)p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next())o.add(r.getTimestamp(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | "+r.getString(4)+" | "+r.getString(5)+" | "+r.getString(6));}}return o;}
 public void logAccess(long actorId,Long targetUserId,String method,String entry,String location,String status)throws SQLException{
  requireRole(actorId,"SECURITY");
  if(method==null||!Set.of("CARD","FACE","PALM","MANUAL").contains(method))throw new SQLException("Invalid access method.");
  if(entry==null||!Set.of("ENTRY","EXIT").contains(entry))throw new SQLException("Invalid entry type.");
  if(status==null||!Set.of("GRANTED","DENIED").contains(status))throw new SQLException("Invalid access status.");
  if(location==null||location.isBlank()||location.trim().length()>150)throw new SQLException("Enter a valid access location.");
  if(targetUserId!=null){try(Connection check=Database.getConnection();PreparedStatement p=check.prepareStatement("SELECT 1 FROM users WHERE id=? AND status='ACTIVE'")){p.setLong(1,targetUserId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Target account is missing or inactive.");}}}
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    try(PreparedStatement p=c.prepareStatement("INSERT INTO access_logs(user_id,method,entry_type,location,status) VALUES(?,?,?,?,?)")){
     if(targetUserId==null)p.setNull(1,java.sql.Types.BIGINT);else p.setLong(1,targetUserId);
     p.setString(2,method);p.setString(3,entry);p.setString(4,location.trim());p.setString(5,status);p.executeUpdate();
    }
    long notificationUser=targetUserId==null?actorId:targetUserId;
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,?,?)")){
     p.setLong(1,notificationUser);p.setString(2,"Campus Access "+status);
     p.setString(3,"Campus access event recorded by security: "+method+" "+entry+" at "+location.trim()+" — "+status+".");p.executeUpdate();
    }
    c.commit();
   }catch(Exception ex){try{c.rollback();}catch(SQLException ignored){}if(ex instanceof SQLException se)throw se;throw new SQLException("Unable to save campus access notification.",ex);}
  }
 }
 public List<String> parentStudents(long parentId)throws SQLException{requireRole(parentId,"PARENT");List<String>o=new ArrayList<>();String q="SELECT u.id,u.full_name,u.email FROM parent_links pl JOIN users u ON u.id=pl.student_id WHERE pl.parent_user_id=? AND u.role='STUDENT' ORDER BY u.full_name";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q)){p.setLong(1,parentId);try(ResultSet r=p.executeQuery()){while(r.next())o.add(r.getLong(1)+" | "+r.getString(2)+" | "+r.getString(3));}}return o;}
 public List<String> activeParentOptions(long actorId)throws SQLException{requireRole(actorId,"ADMIN");List<String>o=new ArrayList<>();try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT id,full_name,email FROM users WHERE role='PARENT' AND status='ACTIVE' ORDER BY full_name");ResultSet r=p.executeQuery()){while(r.next())o.add(r.getLong(1)+" | "+r.getString(2)+" | "+r.getString(3));}return o;}
 public List<String> parentLinks(long actorId)throws SQLException{requireRole(actorId,"ADMIN");List<String>o=new ArrayList<>();String q="SELECT p.id,p.full_name,p.email,s.id,s.full_name,s.email FROM parent_links pl JOIN users p ON p.id=pl.parent_user_id JOIN users s ON s.id=pl.student_id ORDER BY p.full_name,s.full_name";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q);ResultSet r=p.executeQuery()){while(r.next())o.add("Parent #"+r.getLong(1)+" | "+r.getString(2)+" | "+r.getString(3)+"  →  Student #"+r.getLong(4)+" | "+r.getString(5)+" | "+r.getString(6));}return o;}
 public void linkParentStudent(long actorId,long parentId,long studentId)throws SQLException{requireRole(actorId,"ADMIN");if(parentId<=0||studentId<=0)throw new IllegalArgumentException("Select a parent and student.");try(Connection c=Database.getConnection()){c.setAutoCommit(false);try{try(PreparedStatement p=c.prepareStatement("SELECT id FROM users WHERE id=? AND role='PARENT' AND status='ACTIVE' FOR UPDATE")){p.setLong(1,parentId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Selected parent account is missing or inactive.");}}try(PreparedStatement p=c.prepareStatement("SELECT id FROM users WHERE id=? AND role='STUDENT' AND status='ACTIVE' FOR UPDATE")){p.setLong(1,studentId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Selected student account is missing or inactive.");}}try(PreparedStatement p=c.prepareStatement("INSERT INTO parent_links(parent_user_id,student_id) VALUES(?,?)")){p.setLong(1,parentId);p.setLong(2,studentId);p.executeUpdate();}c.commit();}catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se){if("23000".equals(se.getSQLState()))throw new SQLException("This student is already linked to a parent, or this parent–student link already exists.",se);throw se;}throw new SQLException("Unable to link parent and student.",e);}}}
 public void unlinkParentStudent(long actorId,long parentId,long studentId)throws SQLException{requireRole(actorId,"ADMIN");try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("DELETE FROM parent_links WHERE parent_user_id=? AND student_id=?")){p.setLong(1,parentId);p.setLong(2,studentId);if(p.executeUpdate()==0)throw new SQLException("Parent–student link not found.");}}
 public String[] ownProfile(long userId)throws SQLException{
  requireActiveAccount(userId);
  try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT full_name,email,phone,role,status FROM users WHERE id=?")){
   p.setLong(1,userId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Account not found.");return new String[]{r.getString("full_name"),r.getString("email"),r.getString("phone")==null?"":r.getString("phone"),r.getString("role"),r.getString("status")};}
  }
 }
 public void updateOwnProfile(long userId,String name,String email,String phone,String currentPassword,String newPassword)throws SQLException{
  if(name==null||name.isBlank())throw new SQLException("Enter your full name.");
  if(email==null||!email.trim().matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$"))throw new SQLException("Enter a valid email address.");
  boolean changePassword=newPassword!=null&&!newPassword.isBlank();
  if(changePassword&&(currentPassword==null||currentPassword.isBlank()))throw new SQLException("Enter your current password to change it.");
  if(changePassword&&newPassword.length()<8)throw new SQLException("New password must be at least 8 characters.");
  if(changePassword&&newPassword.equals(currentPassword))throw new SQLException("Choose a new password different from your current password.");
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    String oldHash;
    try(PreparedStatement p=c.prepareStatement("SELECT password_hash FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE")){p.setLong(1,userId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Active account not found.");oldHash=r.getString(1);}}
    if(changePassword&&!BCrypt.checkpw(currentPassword,oldHash))throw new SQLException("Current password is incorrect.");
    String sql=changePassword?"UPDATE users SET full_name=?,email=?,phone=?,password_hash=? WHERE id=?":"UPDATE users SET full_name=?,email=?,phone=? WHERE id=?";
    try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,name.trim());p.setString(2,email.trim().toLowerCase(Locale.ROOT));p.setString(3,phone==null||phone.isBlank()?null:phone.trim());if(changePassword){p.setString(4,BCrypt.hashpw(newPassword,BCrypt.gensalt(10)));p.setLong(5,userId);}else p.setLong(4,userId);if(p.executeUpdate()!=1)throw new SQLException("Profile was not updated.");}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,'Profile Updated',?)")){p.setLong(1,userId);p.setString(2,changePassword?"Your profile and password were updated.":"Your profile details were updated.");p.executeUpdate();}
    c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException("Unable to update profile.",e);}
  }
 }

 public List<String> adminUsers(long actorId)throws SQLException{requireRole(actorId,"ADMIN");List<String>o=new ArrayList<>();String q="SELECT id,full_name,email,role,status FROM users ORDER BY role,full_name";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q);ResultSet r=p.executeQuery()){while(r.next())o.add("#"+r.getLong(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | "+r.getString(4)+" | "+r.getString(5));}return o;}
 public String[] adminUserDetails(long actorId,long targetId)throws SQLException{requireRole(actorId,"ADMIN");try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement("SELECT full_name,email,phone FROM users WHERE id=?")){p.setLong(1,targetId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("User not found.");return new String[]{r.getString(1),r.getString(2),r.getString(3)==null?"":r.getString(3)};}}}
 public void createUser(long adminId,String name,String email,String phone,String role,String password)throws SQLException{
  requireRole(adminId,"ADMIN");
  if(name==null||name.isBlank())throw new SQLException("Enter the user's full name.");
  if(email==null||!email.trim().matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$"))throw new SQLException("Enter a valid email address.");
  if(role==null||!Set.of("STUDENT","FACULTY","HOD","ADMIN","PARENT","SECURITY","MERCHANT").contains(role))throw new SQLException("Select a valid role.");
  if(password==null||password.length()<8)throw new SQLException("Initial password must be at least 8 characters.");
  String hash=BCrypt.hashpw(password,BCrypt.gensalt(10));
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    long id;
    try(PreparedStatement p=c.prepareStatement("INSERT INTO users(full_name,email,phone,password_hash,role,status) VALUES(?,?,?,?,?,'ACTIVE')",Statement.RETURN_GENERATED_KEYS)){
     p.setString(1,name.trim());p.setString(2,email.trim().toLowerCase(Locale.ROOT));p.setString(3,phone==null||phone.isBlank()?null:phone.trim());p.setString(4,hash);p.setString(5,role);p.executeUpdate();
     try(ResultSet r=p.getGeneratedKeys()){if(!r.next())throw new SQLException("Could not create the account.");id=r.getLong(1);}
    }
    try(PreparedStatement p=c.prepareStatement("INSERT INTO wallets(user_id,balance,daily_spend_limit,status) VALUES(?,0,5000,'ACTIVE')")){p.setLong(1,id);p.executeUpdate();}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO biometric_profiles(user_id,status) VALUES(?,'ACTIVE')")){p.setLong(1,id);p.executeUpdate();}
    if("MERCHANT".equals(role))try(PreparedStatement p=c.prepareStatement("INSERT INTO merchants(user_id,merchant_name,business_type,status) VALUES(?,?,'Campus Commerce','ACTIVE')")){p.setLong(1,id);p.setString(2,name.trim()+" Services");p.executeUpdate();}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,'NexusID Account','Your NexusID account has been created. Contact university administration if you need help with access.')")){p.setLong(1,id);p.executeUpdate();}
    c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public void updateUser(long adminId,long targetId,String name,String email,String phone,String newPassword)throws SQLException{
  requireRole(adminId,"ADMIN");
  if(targetId<1)throw new SQLException("Enter a valid user ID.");if(name==null||name.isBlank())throw new SQLException("Enter the user's full name.");
  if(email==null||!email.trim().matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$"))throw new SQLException("Enter a valid email address.");
  if(newPassword!=null&&!newPassword.isBlank()&&newPassword.length()<8)throw new SQLException("New password must be at least 8 characters.");
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    try(PreparedStatement exists=c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){exists.setLong(1,targetId);try(ResultSet r=exists.executeQuery()){if(!r.next())throw new SQLException("User not found.");}}
    String q=newPassword==null||newPassword.isBlank()?"UPDATE users SET full_name=?,email=?,phone=? WHERE id=?":"UPDATE users SET full_name=?,email=?,phone=?,password_hash=? WHERE id=?";
    try(PreparedStatement p=c.prepareStatement(q)){p.setString(1,name.trim());p.setString(2,email.trim().toLowerCase(Locale.ROOT));p.setString(3,phone==null||phone.isBlank()?null:phone.trim());if(newPassword==null||newPassword.isBlank())p.setLong(4,targetId);else{p.setString(4,BCrypt.hashpw(newPassword,BCrypt.gensalt(10)));p.setLong(5,targetId);}if(p.executeUpdate()==0)throw new SQLException("User not found.");}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,'Account Updated',?)")){p.setLong(1,targetId);p.setString(2,"Your NexusID account details were updated by university administration."+(newPassword!=null&&!newPassword.isBlank()?" Your password was also reset.":""));p.executeUpdate();}
    c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public void setUserStatus(long adminId,long targetId,String status)throws SQLException{
  requireRole(adminId,"ADMIN");
  if(!Set.of("ACTIVE","INACTIVE").contains(status))throw new SQLException("Select ACTIVE or INACTIVE.");
  if(adminId==targetId&&"INACTIVE".equals(status))throw new SQLException("You cannot deactivate your own administrator account.");
  try(Connection c=Database.getConnection()){
   c.setAutoCommit(false);
   try{
    try(PreparedStatement p=c.prepareStatement("SELECT role,status FROM users WHERE id=? FOR UPDATE")){p.setLong(1,targetId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("User not found.");String currentStatus=r.getString(2);if(currentStatus.equals(status))throw new SQLException("Account is already "+status+".");if("ADMIN".equals(r.getString(1))&&"INACTIVE".equals(status)){try(PreparedStatement a=c.prepareStatement("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE' AND id<>?")){a.setLong(1,targetId);try(ResultSet x=a.executeQuery()){x.next();if(x.getInt(1)==0)throw new SQLException("At least one active administrator must remain.");}}}}}
    try(PreparedStatement p=c.prepareStatement("UPDATE users SET status=? WHERE id=?")){p.setString(1,status);p.setLong(2,targetId);if(p.executeUpdate()==0)throw new SQLException("User not found.");}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(user_id,title,message) VALUES(?,'Account Status',?)")){p.setLong(1,targetId);p.setString(2,"Your NexusID account status is now "+status+".");p.executeUpdate();}
    c.commit();
   }catch(Exception e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof SQLException se)throw se;throw new SQLException(e.getMessage(),e);}
  }
 }
 public String stats(long actorId)throws SQLException{requireRole(actorId,"ADMIN","HOD");String[] qs={"SELECT COUNT(*) FROM users WHERE role='STUDENT' AND status='ACTIVE'","SELECT COUNT(*) FROM users WHERE role IN ('FACULTY','HOD') AND status='ACTIVE'","SELECT COUNT(*) FROM subjects","SELECT COUNT(*) FROM events WHERE status='OPEN'","SELECT COUNT(*) FROM merchant_orders WHERE status IN ('PAID','PROCESSING','READY')","SELECT COUNT(*) FROM maintenance_requests WHERE status IN ('OPEN','IN_PROGRESS')"};StringBuilder s=new StringBuilder();try(Connection c=Database.getConnection()){for(String q:qs)try(PreparedStatement p=c.prepareStatement(q);ResultSet r=p.executeQuery()){r.next();s.append(r.getInt(1)).append("\n");}}return s.toString();}
 public List<String> resultsReport(long actorId)throws SQLException{requireRole(actorId,"HOD");List<String>o=new ArrayList<>();String q="SELECT s.code,s.name,COUNT(r.id),ROUND(AVG(r.marks),2),MAX(r.marks),MIN(r.marks) FROM results r JOIN subjects s ON s.id=r.subject_id GROUP BY s.id,s.code,s.name ORDER BY s.code";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q);ResultSet r=p.executeQuery()){while(r.next())o.add(r.getString(1)+" | "+r.getString(2)+" | Published: "+r.getInt(3)+" | Average: "+r.getBigDecimal(4)+" | Highest: "+r.getBigDecimal(5)+" | Lowest: "+r.getBigDecimal(6));}return o;}

 public List<String> attendanceReport(long actorId)throws SQLException{requireRole(actorId,"HOD");List<String>o=new ArrayList<>();String q="SELECT s.code,s.name,COUNT(a.id),SUM(a.status='PRESENT'),SUM(a.status='LATE'),SUM(a.status='ABSENT') FROM attendance a JOIN subjects s ON s.id=a.subject_id GROUP BY s.id,s.code,s.name ORDER BY s.code";try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(q);ResultSet r=p.executeQuery()){while(r.next()){int total=r.getInt(3),p1=r.getInt(4),late=r.getInt(5),abs=r.getInt(6);double pct=total==0?0:(p1+late)*100.0/total;o.add(r.getString(1)+" | "+r.getString(2)+" | Sessions: "+total+" | Present: "+p1+" | Late: "+late+" | Absent: "+abs+" | Attendance: "+String.format("%.1f",pct)+"%");}}return o;}

 public List<String> studentAttendance(long actorId,long studentId)throws SQLException{ return new AttendanceDAO().studentSummary(actorId,studentId); }
}
