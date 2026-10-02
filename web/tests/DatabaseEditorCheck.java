import java.nio.file.*;
import java.util.*;
public class DatabaseEditorCheck {
    static DataStore store; static Path dir; static ActivityLog log;
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    static String rev() throws java.io.IOException { return (String)store.adminRecords("TankReturns").get("revision"); }
    static void write(String table, String text) throws Exception { Files.writeString(dir.resolve(table + ".csv"), text); }
    static void refuse(Runnable action, String message) { try { action.run(); throw new AssertionError(message); } catch(Http.Error expected) {} }
    static void change(String table, String mode, String id, String[] row) {
        try { store.correctRecord(table, mode, id, rev(), row, "Fake-data correction", log); }
        catch(java.io.IOException e) { throw new RuntimeException(e); }
    }
    public static void main(String[] args) throws Exception {
        dir=Files.createTempDirectory("lpg-editor-check-");
        String time="2026-10-02 10:00:00";
        write("Customers","1,Test,Customer,,\n"); write("LPGs","1,Fake,100,11\n");
        write("Transactions","1,1,1,"+time+",2,100,test,0,1,,,1,\n");
        write("Receipts","1,"+time+",1,test,0,0,\n");
        write("StockMovements","1,"+time+",1,count,2,0,test,\n");
        write("TankReturns","1,"+time+",1,1,2,test,Test return\n");
        store=new DataStore(dir);log=new ActivityLog(dir);
        check(Json.write(store.adminRecords("TankReturns")).contains("\"rows\":[[\"1\""),"records serialize as arrays");
        check(store.owedCount("1")==0 && store.stock().get("1").empty()==2,"return initial state");
        String stale=rev();
        change("TankReturns","edit","1",new String[]{"1",time,"1","1","1","test","Partial test"});
        check(store.owedCount("1")==1 && store.stock().get("1").empty()==1,"partial undo");
        refuse(()->{try{store.correctRecord("TankReturns","delete","1",stale,new String[0],"stale",log);}catch(java.io.IOException e){throw new RuntimeException(e);}},"stale edit rejected");
        change("TankReturns","delete","1",new String[0]);
        check(store.owedCount("1")==2 && store.stock().get("1").empty()==0,"full undo restores debt and stock");
        check(new DataStore(dir).owedCount("1")==2,"reload persistence");
        try(var backups=Files.list(dir.resolve("admin-backups"))){check(backups.count()==2,"backup for every save");}
        refuse(()->change("TankReturns","add","",new String[]{"2",time,"1","1","3","test","Too many"}),"excess returns rejected");
        refuse(()->change("Payments","add","",new String[]{"1",time,"1","1","201","test",""}),"overpayment rejected");
        change("Payments","add","",new String[]{"1",time,"1","1","100","test",""});
        check(store.paidByReceipt(store.receipts()).get("1")==100,"payment add");
        change("Payments","edit","1",new String[]{"1",time,"1","1","50","test","Edited"});
        check(store.paidByReceipt(store.receipts()).get("1")==50,"payment edit");
        refuse(()->change("Transactions","edit","1",new String[]{"1","1","1",time,"2","20","test","0","1","","","1",""}),"sale cannot fall below payments");
        refuse(()->change("Customers","delete","1",new String[0]),"referenced customer deletion rejected");
        refuse(()->change("Payments","add","",new String[]{"2",time,"missing","1","1","test",""}),"orphan payment rejected");
        refuse(()->change("Payments","edit","1",new String[]{"1",time,"1","1","NaN","test",""}),"invalid money rejected");
        change("TankReturns","add","",new String[]{"2",time,"1","1","1","test",""});
        change("StockMovements","add","",new String[]{"2",time,"1","refill-send","0","-1","test","","0","1","",""});
        refuse(()->change("TankReturns","delete","2",new String[0]),"undo after empty used is rejected");
        refuse(()->{try{store.adminRecords("../WebUsers");}catch(java.io.IOException e){throw new RuntimeException(e);}},"unknown tables rejected");
        check(log.latest(60).stream().anyMatch(e->e.message().contains("correction saved")),"audit entry");
        System.out.println("PASS: partial/full return undo, persistence, backups, add/edit payments, stale edits, references, overpayment, invalid values, reused stock, table whitelist and audit");
    }
}
