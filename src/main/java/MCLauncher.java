import com.github.brickwall2900.Installer;
import com.github.brickwall2900.Launcher;
import com.github.brickwall2900.MrpackDownloader;
import com.github.brickwall2900.VersionList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class MCLauncher {
    private static final Map<String, Class<?>> PROCESSES = new HashMap<>();

    public static void main(String[] args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        initProcesses();
        if (args.length < 1) {
            System.out.println("Processes: " + PROCESSES.keySet());
            throw new IllegalArgumentException("Usage: MCLauncher [process] [args]");
        }
        String process = args[0];
        Class<?> processClass = PROCESSES.get(process);
        if (processClass == null) {
            throw new IllegalArgumentException("Process " + process + " is not valid!");
        }

        String[] split = new String[args.length - 1];
        System.arraycopy(args, 1, split, 0, args.length - 1);

        System.out.println("Process executed: " + process);
        Method runMethod = processClass.getDeclaredMethod("main", String[].class);
        runMethod.invoke(null, (Object) split);
    }

    private static void initProcesses() {
        PROCESSES.put("Installer", Installer.class);
        PROCESSES.put("Launcher", Launcher.class);
        PROCESSES.put("VersionList", VersionList.class);
        PROCESSES.put("MrpackDownloader", MrpackDownloader.class);
    }
}
