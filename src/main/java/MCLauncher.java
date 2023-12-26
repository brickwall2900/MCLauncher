import com.github.brickwall2900.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class MCLauncher {
    private static final Map<String, LauncherProcess> PROCESSES = new HashMap<>();
    public static final String VERSION = "v1.1";

    public static void main(String[] args) {
        initProcesses();
        if (args.length < 1) {
            System.out.println("Processes: " + PROCESSES.keySet());
            throw new IllegalArgumentException("Usage: MCLauncher [process] [args]");
        }
        String processName = args[0];
        LauncherProcess process = PROCESSES.get(processName);
        if (process == null) {
            throw new IllegalArgumentException("Process " + processName + " not found!");
        }

        String[] split = new String[args.length - 1];
        System.arraycopy(args, 1, split, 0, args.length - 1);

        System.out.println("MCLauncher " + VERSION);
        System.out.println("Process executed: " + processName);
        process.run(split);
    }

    private static void initProcesses() {
        PROCESSES.put("Installer", Installer.instance);
        PROCESSES.put("Launcher", Launcher.instance);
        PROCESSES.put("VersionList", VersionList.instance);
        PROCESSES.put("MrpackDownloader", MrpackDownloader.instance);
    }
}
