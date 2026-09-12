package com.claw.mcp;

import com.claw.config.McpProperties;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.W32APIOptions;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class McpProcessResourceLimiter {
    private static final String OS=System.getProperty("os.name","").toLowerCase(Locale.ROOT);

    public Prepared prepare(List<String> original,McpProperties.Server server){
        if(OS.contains("win"))return new Prepared(List.copyOf(original),"windows-job-object+rss-watchdog",process->WindowsJob.attach(process,server));
        long bytes=Math.multiplyExact((long)server.getMaxMemoryMb(),1024L*1024L); int cpu=server.getMaxCpuSeconds();
        if(OS.contains("linux")){List<String> command=new ArrayList<>(List.of("prlimit","--rss="+bytes,"--as="+bytes,"--cpu="+cpu,"--"));command.addAll(original);return new Prepared(command,"linux-prlimit",process->ResourceHandle.NOOP);}
        List<String> command=new ArrayList<>(List.of("/bin/sh","-c","ulimit -m \"$1\"; ulimit -v \"$1\"; ulimit -t \"$2\"; shift 2; exec \"$@\"","mcp-limit",String.valueOf(bytes/1024L),String.valueOf(cpu)));command.addAll(original);return new Prepared(command,"posix-ulimit",process->ResourceHandle.NOOP);
    }

    public record Prepared(List<String> command,String mode,Attacher attacher){public ResourceHandle attach(Process process){return attacher.attach(process);}}
    @FunctionalInterface public interface Attacher{ResourceHandle attach(Process process);}
    public interface ResourceHandle extends AutoCloseable{ResourceHandle NOOP=()->{};void close();}

    private static final class WindowsJob {
        private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION=9,JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION=15;
        private static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY=0x100,JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE=0x2000;
        private static final int JOB_OBJECT_CPU_RATE_CONTROL_ENABLE=0x1,JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP=0x4;
        private static ResourceHandle attach(Process process,McpProperties.Server server){
            WinNT.HANDLE job=JobKernel32.INSTANCE.CreateJobObjectW(null,null);if(job==null)throw failure("CreateJobObject");
            WinNT.HANDLE target=Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_SET_QUOTA|WinNT.PROCESS_TERMINATE,false,(int)process.pid());
            if(target==null){Kernel32.INSTANCE.CloseHandle(job);throw failure("OpenProcess");}
            try{
                long memoryBytes=(long)server.getMaxMemoryMb()*1024L*1024L;ExtendedLimitInformation info=new ExtendedLimitInformation();info.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_PROCESS_MEMORY|JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;info.ProcessMemoryLimit=new BaseTSD.SIZE_T(memoryBytes);info.write();
                if(!JobKernel32.INSTANCE.SetInformationJobObject(job,JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,info.getPointer(),info.size()))throw failure("SetInformationJobObject");
                CpuRateControlInformation cpu=new CpuRateControlInformation();cpu.ControlFlags=JOB_OBJECT_CPU_RATE_CONTROL_ENABLE|JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;cpu.CpuRate=server.getMaxCpuPercent()*100;cpu.write();
                if(!JobKernel32.INSTANCE.SetInformationJobObject(job,JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION,cpu.getPointer(),cpu.size()))throw failure("SetInformationJobObject(CPU)");
                if(!JobKernel32.INSTANCE.AssignProcessToJobObject(job,target))throw failure("AssignProcessToJobObject");
                Thread watcher=startRssWatchdog(process,memoryBytes);
                return ()->{watcher.interrupt();Kernel32.INSTANCE.CloseHandle(job);};
            }catch(RuntimeException e){Kernel32.INSTANCE.CloseHandle(job);process.destroyForcibly();throw e;}finally{Kernel32.INSTANCE.CloseHandle(target);}
        }
        private static IllegalStateException failure(String operation){return new IllegalStateException(operation+" failed, win32Error="+Native.getLastError());}
        private static Thread startRssWatchdog(Process process,long maxBytes){return Thread.ofVirtual().name("mcp-rss-"+process.pid()).start(()->{while(process.isAlive()&&!Thread.currentThread().isInterrupted()){WinNT.HANDLE handle=Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_INFORMATION|WinNT.PROCESS_VM_READ,false,(int)process.pid());if(handle!=null){try{ProcessMemoryCounters counters=new ProcessMemoryCounters();if(PsapiNative.INSTANCE.GetProcessMemoryInfo(handle,counters.getPointer(),counters.size())){counters.read();if(counters.WorkingSetSize.longValue()>maxBytes){process.destroyForcibly();return;}}}finally{Kernel32.INSTANCE.CloseHandle(handle);}}try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}});}
    }

    private interface JobKernel32 extends Library {
        JobKernel32 INSTANCE=Native.load("kernel32",JobKernel32.class,W32APIOptions.UNICODE_OPTIONS);
        WinNT.HANDLE CreateJobObjectW(Pointer securityAttributes,WString name);
        boolean SetInformationJobObject(WinNT.HANDLE job,int infoClass,Pointer info,int size);
        boolean AssignProcessToJobObject(WinNT.HANDLE job,WinNT.HANDLE process);
    }
    private interface PsapiNative extends Library {PsapiNative INSTANCE=Native.load("psapi",PsapiNative.class);boolean GetProcessMemoryInfo(WinNT.HANDLE process,Pointer counters,int size);}
    @Structure.FieldOrder({"PerProcessUserTimeLimit","PerJobUserTimeLimit","LimitFlags","MinimumWorkingSetSize","MaximumWorkingSetSize","ActiveProcessLimit","Affinity","PriorityClass","SchedulingClass"})
    public static class BasicLimitInformation extends Structure {public WinNT.LARGE_INTEGER PerProcessUserTimeLimit=new WinNT.LARGE_INTEGER();public WinNT.LARGE_INTEGER PerJobUserTimeLimit=new WinNT.LARGE_INTEGER();public int LimitFlags;public BaseTSD.SIZE_T MinimumWorkingSetSize=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T MaximumWorkingSetSize=new BaseTSD.SIZE_T();public int ActiveProcessLimit;public BaseTSD.ULONG_PTR Affinity=new BaseTSD.ULONG_PTR();public int PriorityClass;public int SchedulingClass;}
    @Structure.FieldOrder({"ReadOperationCount","WriteOperationCount","OtherOperationCount","ReadTransferCount","WriteTransferCount","OtherTransferCount"})
    public static class IoCounters extends Structure {public long ReadOperationCount,WriteOperationCount,OtherOperationCount,ReadTransferCount,WriteTransferCount,OtherTransferCount;}
    @Structure.FieldOrder({"BasicLimitInformation","IoInfo","ProcessMemoryLimit","JobMemoryLimit","PeakProcessMemoryUsed","PeakJobMemoryUsed"})
    public static class ExtendedLimitInformation extends Structure {public BasicLimitInformation BasicLimitInformation=new BasicLimitInformation();public IoCounters IoInfo=new IoCounters();public BaseTSD.SIZE_T ProcessMemoryLimit=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T JobMemoryLimit=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T PeakProcessMemoryUsed=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T PeakJobMemoryUsed=new BaseTSD.SIZE_T();}
    @Structure.FieldOrder({"ControlFlags","CpuRate"}) public static class CpuRateControlInformation extends Structure {public int ControlFlags;public int CpuRate;}
    @Structure.FieldOrder({"cb","PageFaultCount","PeakWorkingSetSize","WorkingSetSize","QuotaPeakPagedPoolUsage","QuotaPagedPoolUsage","QuotaPeakNonPagedPoolUsage","QuotaNonPagedPoolUsage","PagefileUsage","PeakPagefileUsage"}) public static class ProcessMemoryCounters extends Structure {public int cb=size();public int PageFaultCount;public BaseTSD.SIZE_T PeakWorkingSetSize=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T WorkingSetSize=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T QuotaPeakPagedPoolUsage=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T QuotaPagedPoolUsage=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T QuotaPeakNonPagedPoolUsage=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T QuotaNonPagedPoolUsage=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T PagefileUsage=new BaseTSD.SIZE_T();public BaseTSD.SIZE_T PeakPagefileUsage=new BaseTSD.SIZE_T();}
}
