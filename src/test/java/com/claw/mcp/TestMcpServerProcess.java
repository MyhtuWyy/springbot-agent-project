package com.claw.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

public final class TestMcpServerProcess {
    private static final Pattern ID=Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");
    public static void main(String[] args)throws Exception{
        System.err.println("test-mcp-ready-"+System.getenv().getOrDefault("TEST_USER","unknown"));
        BufferedInputStream input=new BufferedInputStream(System.in); OutputStream output=System.out;
        while(true){int length=readLength(input);if(length<0)return;String request=new String(input.readNBytes(length),StandardCharsets.UTF_8);Matcher matcher=ID.matcher(request);if(!matcher.find())continue;String id=matcher.group(1);String user=System.getenv().getOrDefault("TEST_USER","unknown");String result;
            if(request.contains("\"method\":\"initialize\""))result="{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}}}";
            else if(request.contains("\"method\":\"tools/list\""))result="{\"tools\":[{\"name\":\"echo\",\"description\":\"integration echo\",\"inputSchema\":{\"type\":\"object\"}}]}";
            else if(request.contains("\"large\":true"))result="{\"content\":[{\"type\":\"text\",\"text\":\""+"x".repeat(1000)+"\"}]}";
            else result="{\"content\":[{\"type\":\"text\",\"text\":\""+user+"\"}]}";
            byte[] body=("{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"result\":"+result+"}").getBytes(StandardCharsets.UTF_8);output.write(("Content-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
        }
    }
    private static int readLength(InputStream input)throws IOException{int length=-1;while(true){String line=readLine(input);if(line==null)return -1;if(line.isBlank())return length;if(line.regionMatches(true,0,"Content-Length:",0,15))length=Integer.parseInt(line.substring(15).trim());}}
    private static String readLine(InputStream input)throws IOException{StringBuilder value=new StringBuilder();while(true){int ch=input.read();if(ch<0)return value.isEmpty()?null:value.toString();if(ch=='\n'){if(!value.isEmpty()&&value.charAt(value.length()-1)=='\r')value.setLength(value.length()-1);return value.toString();}value.append((char)ch);}}
}
