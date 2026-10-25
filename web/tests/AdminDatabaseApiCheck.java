import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class AdminDatabaseApiCheck {
    static final class Exchange extends HttpExchange {
        Headers req=new Headers(),res=new Headers(); ByteArrayOutputStream out=new ByteArrayOutputStream();
        String method,path,body=""; int status; InetSocketAddress remote=new InetSocketAddress("127.0.0.1",1234);
        Exchange(String method,String path){this.method=method;this.path=path;req.set("Host","localhost:8090");}
        public Headers getRequestHeaders(){return req;} public Headers getResponseHeaders(){return res;}
        public URI getRequestURI(){return URI.create(path);} public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return null;} public void close(){}
        public InputStream getRequestBody(){return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public OutputStream getResponseBody(){return out;} public void sendResponseHeaders(int code,long size){status=code;}
        public InetSocketAddress getRemoteAddress(){return remote;} public int getResponseCode(){return status;}
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",8090);}
        public String getProtocol(){return "HTTP/1.1";} public Object getAttribute(String n){return null;}
        public void setAttribute(String n,Object v){} public void setStreams(InputStream i,OutputStream o){}
        public HttpPrincipal getPrincipal(){return null;}
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("lpg-admin-api-check-");
        Platform platform=new Platform(dir.resolve("platform.db"));Tenants tenants=new Tenants(dir,platform);
        DataStore store=tenants.get(platform.createActive("Check store","check")).store();
        Auth auth=new Auth();
        AdminApi api=new AdminApi(platform,tenants,new PosGate(platform,tenants,auth,dir,dir,8080,null),auth,new Tunnel(8080,u->{}),dir,dir,8080,8090);
        Exchange setup=new Exchange("POST","/api/admin/setup");setup.req.set("X-LPG","1");setup.body="password=check-admin-pass";
        api.handle(new Http.Req(setup));String cookie=setup.res.getFirst("Set-Cookie").split(";")[0];
        Exchange anon=new Exchange("GET","/api/database/Customers");
        try{api.handle(new Http.Req(anon));throw new AssertionError("admin sign-in required");}catch(Http.Error e){check(e.status==401,"sign-in code");}
        Exchange get=new Exchange("GET","/api/database/Customers");get.req.set("Cookie",cookie);api.handle(new Http.Req(get));
        check(get.status==200 && get.out.toString().contains("\"fields\""),"local records API");
        Exchange bad=new Exchange("GET","/api/database/Customers");bad.req.set("Host","evil.example:8090");
        try{api.handle(new Http.Req(bad));throw new AssertionError("host restriction");}catch(Http.Error e){check(e.status==403,"host code");}
        bad=new Exchange("GET","/api/database/Customers");bad.remote=new InetSocketAddress("192.0.2.1",1234);
        try{api.handle(new Http.Req(bad));throw new AssertionError("remote restriction");}catch(Http.Error e){check(e.status==403,"remote code");}
        bad=new Exchange("PUT","/api/database/Customers");
        try{api.handle(new Http.Req(bad));throw new AssertionError("CSRF restriction");}catch(Http.Error e){check(e.status==403,"CSRF code");}
        Exchange put=new Exchange("PUT","/api/database/Customers");put.req.set("X-LPG","1");put.req.set("Cookie",cookie);
        String rev=(String)store.adminRecords("Customers").get("revision");
        put.body="mode=add&reason=Fake+check&revision="+rev+"&field0=1&field1=Fake&field2=Customer&field3=&field4=";
        api.handle(new Http.Req(put));check(put.status==200 && store.customer("1")!=null,"admin write route");
        Exchange hidden=new Exchange("GET","/api/database/WebUsers");hidden.req.set("Cookie",cookie);
        try{api.handle(new Http.Req(hidden));throw new AssertionError("credential table hidden");}catch(Http.Error e){check(e.status==404,"table code");}
        System.out.println("PASS: API read/write, admin sign-in required, loopback-only access, Host restriction, CSRF header and credential-table exclusion");
    }
}
