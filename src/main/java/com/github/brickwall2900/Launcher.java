package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

import static com.github.brickwall2900.IOUtilities.*;

public class Launcher {
    public static final Launcher instance = new Launcher();

    public static void main(String[] args) {
        instance.run(args);
    }

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    // wooo time to freeload Minecraft!
    public void run(String[] args) {
        init(args);
        initFiles();
        readClientJson(clientJsonFile);
        printInfo();
        setGameArguments();
        setJVMArguments();
        parseGameArguments();
        parseJVMArguments();
        createProcessBuilder();
        startMinecraft();
    }

    private String clientJson, username, gameDirectoryPath;
    private String extraGameArguments, extraJVMArguments;
    private boolean confirmAll;

    public void init(String[] args) {
        String lastParsed = null;
        try {
            for (String arg : args) {
                lastParsed = arg;
                if (arg.startsWith("--client-json=") || arg.startsWith("-client=")) {
                    clientJson = arg.split("=")[1];
                }
                if (arg.startsWith("--username=") || arg.startsWith("-name=")) {
                    username = arg.split("=")[1];
                }
                if (arg.startsWith("--game-directory=") || arg.startsWith("-game-dir=")) {
                    gameDirectoryPath = arg.split("=")[1];
                }
                if (arg.equalsIgnoreCase("-y") || arg.equalsIgnoreCase("--confirm-yes")) {
                    confirmAll = true;
                }
                if (arg.startsWith("--extra-game-args=") || arg.startsWith("-ega=")) {
                    extraGameArguments = arg.split("=")[1];
                }
                if (arg.startsWith("--extra-java-args=") || arg.startsWith("-eja=")) {
                    extraJVMArguments = arg.split("=")[1];
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid argument at \"" + lastParsed + '\"');
        }
        if (clientJson == null || username == null || gameDirectoryPath == null) {
            System.err.println("Usage: Launcher [--client-json=<client json file>] [--username=<player name>] [--game-directory=<.minecraft game directory>] [--extra-game-args=<extra game arguments>]? [--extra-java-args=<extra JVM arguments>]? --confirm-yes?");
            System.err.println(" ..or: Launcher [-client=<client json file>] [--name=<player name>] [-game-dir=<.minecraft game directory>] [-ega=<extra game arguments>]? [-eja=<extra JVM arguments>]? -y?");
            System.err.println("'?' means this is optional.");
            throw new NullPointerException("One or more arguments are missing!");
        }
        if (confirmAll) {
            out.println("Confirming 'yes' to all questions!");
        }
    }

    private File clientJsonFile, gameDirectory;

    public void initFiles() {
        clientJsonFile = new File(clientJson);
        gameDirectory = new File(gameDirectoryPath);

        out.println("clientJson -> " + clientJsonFile);
        out.println("gameDirectory -> " + gameDirectory);
    }

    public void readClientJson(File jsonFile) {
        out.println("Reading client.json: " + jsonFile);
        try {
            JsonElement element = JsonParser.parseString(readFileToString(jsonFile));
            JsonObject object = element.getAsJsonObject();
            JsonElement inheritsFrom = object.get("inheritsFrom");
            boolean hasParent = inheritsFrom != null;
            if (hasParent) {
                String version = inheritsFrom.getAsString();
                out.println("Inherits from: " + version);
                readClientJson(new File(gameDirectory, "versions" + File.separatorChar + version + File.separatorChar + version + ".json"));
            }
            JsonObject javaVersion = object.getAsJsonObject("javaVersion");
            if (javaVersion != null) {
                checkJavaVersion(javaVersion);
            }
            String version = object.get("id").getAsString();
            JsonObject arguments = object.getAsJsonObject("arguments");
            out.printf("%s: Reading game arguments%n", jsonFile);
            readGameArguments(arguments);
            out.printf("%s: Reading JVM arguments%n", jsonFile);
            readJVMArguments(arguments);
            JsonArray libraries = object.getAsJsonArray("libraries");
            out.printf("%s: Reading classpath%n", jsonFile);
            readClassPath(version, libraries);
            out.printf("%s: Reading main class%n", jsonFile);
            readMainClass(object);
            preGameSetArguments(object);
            preJVMSetArguments(object);
            versionAliases.add(version);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing/reading JSON file: " + jsonFile, e);
        }
    }

    /*
     * I still miss her...
     */

    private Map<String, String> gameArguments, jvmArguments;
    private List<File> classPath;
    private String mainClass;

    private void checkJavaVersion(JsonObject javaVersionJson) {
        int minecraft = javaVersionJson.get("majorVersion").getAsInt();
        int java = getJavaVersion();
        out.printf("You're running on Java %d. Minecraft requires Java %d or higher.%n", java, minecraft);
        if (minecraft > java) {
            throw new UnsupportedClassVersionError("Incompatible Java version for the chosen Minecraft client! (" + minecraft + " > " + java + ")");
        }
    }

    // never nester? Linus Torvalds is going to kill me...
    private void readGameArguments(JsonObject arguments) {
        if (arguments != null) {
            if (gameArguments == null) {
                gameArguments = new HashMap<>();
            }

            JsonArray gameArguments = arguments.getAsJsonArray("game");
            List<JsonElement> elements = gameArguments.asList();
            for (int i = 0; i < elements.size(); i++) {
                JsonElement element = elements.get(i);
                if (element.isJsonObject()) {
                    // rules
                    String[] addedArguments = confirmRule(element.getAsJsonObject());
                    if (addedArguments != null) {
                        // FIXME: check for odd number or check if length == 1?
                        if ((addedArguments.length & 1) == 1) { // there is maybe only 1 argument added
                            this.gameArguments.put(addedArguments[0], null);
                        } else {
                            for (int j = 0; j < addedArguments.length; j += 2) {
                                String name = addedArguments[j];
                                String value = addedArguments[j + 1];
                                this.gameArguments.put(name, value);
                            }
                        }
                    }
                } else { // This MAY work...
                    String name = element.getAsString();
                    element = elements.get(++i);
                    String value = element.getAsString();
                    this.gameArguments.put(name, value);
                }
            }
        }
    }

    private boolean checkYesOrNo() {
        String next = in.nextLine();
        return !confirmAll && (next.equalsIgnoreCase("yes") || next.equalsIgnoreCase("y"));
    }

    private String[] confirmRule(JsonObject object) {
        JsonArray rules = object.getAsJsonArray("rules");

        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (action.equals("allow")) {
                JsonObject featureObject = rule.getAsJsonObject("features");
                Set<String> keys = featureObject.keySet();
                for (String key : keys) {
                    out.printf("Allow feature: %s? (yes/no) ", key);
                    if (!checkYesOrNo()) {
                        return null; // didn't allow!
                    }
                }
            } else {
                throw new IllegalStateException("Unknown action: " + action);
            }
        }

        JsonElement valueObject = object.get("value");
        if (valueObject.isJsonArray()) {
            JsonArray value = valueObject.getAsJsonArray();
            return value.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
        } else {
            return new String[] { valueObject.getAsString() };
        }
    }

    private void readJVMArguments(JsonObject arguments) {
        if (arguments != null) {
            if (jvmArguments == null) {
                jvmArguments = new HashMap<>();
            }

            JsonArray gameArguments = arguments.getAsJsonArray("jvm");
            List<JsonElement> elements = gameArguments.asList();
            for (int i = 0; i < elements.size(); i++) {
                JsonElement element = elements.get(i);
                if (element.isJsonObject()) {
                    JsonObject ruleObject = element.getAsJsonObject();
                    // rules
                    if (checkOsRules(ruleObject.getAsJsonArray("rules"))) {
                        String[] addedArguments;
                        JsonElement valueObject = ruleObject.get("value");
                        if (valueObject.isJsonArray()) {
                            JsonArray value = valueObject.getAsJsonArray();
                            addedArguments = value.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
                        } else {
                            addedArguments = new String[] { valueObject.getAsString() };
                        }
                        // FIXME: check for odd number or check if length == 1?
                        if ((addedArguments.length & 1) == 1) { // there is maybe only 1 argument added
                            this.jvmArguments.put(addedArguments[0], null);
                        } else {
                            for (int j = 0; j < addedArguments.length; j += 2) {
                                String name = addedArguments[j];
                                String value = addedArguments[j + 1];
                                this.jvmArguments.put(name, value);
                            }
                        }
                    }
                } else { // This MAY also work...
                    String name = element.getAsString();
                                                                            // vvvv HACK BELOW vvvvv
                    if (!name.contains("${") && elements.size() - 1 > i + 1 || name.startsWith("-cp")) {
                        element = elements.get(++i);
                        String value = element.getAsString();
                        this.jvmArguments.put(name, value);
                    } else if (!name.contains("${") && elements.size() - 1 <= i + 1) {
                        this.jvmArguments.put(name, null);
                    } else {
                        String[] nameValue = name.split("=");
                        this.jvmArguments.put(nameValue[0], nameValue[1]);
                    }
                }
            }
        }
    }

    private boolean checkOsRules(JsonArray rules) {
        if (rules != null) {
            for (JsonElement element : rules) {
                JsonObject object = element.getAsJsonObject();
                String action = object.get("action").getAsString();
                JsonObject os = object.getAsJsonObject("os");
                JsonElement osName = os.get("name");
                JsonElement osArch = os.get("arch");
                if (action.equalsIgnoreCase("allow")) {
                    boolean osNameAllowed = osName == null;
                    boolean osArchAllowed = osArch == null;
                    if (osName != null) {
                        switch (osName.getAsString()) {
                            case "osx" ->
                                    osNameAllowed = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
                            case "linux" ->
                                    osNameAllowed = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("nux");
                            case "windows" ->
                                    osNameAllowed = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                            default -> throw new IllegalStateException("Unexpected OS name: " + osName);
                        }
                    }
                    if (osArch != null) {
                        osArchAllowed = System.getProperty("os.arch").equalsIgnoreCase(osArch.getAsString());
                    }
                    return osNameAllowed && osArchAllowed;
                } else {
                    throw new IllegalStateException("Unexpected action: " + action);
                }
            }
        }
        // ..?
        return true;
    }

    // okay screw this
    private List<String> versionAliases = new ArrayList<>();

    private void readClassPath(String version, JsonArray libraries) {
        if (classPath == null) {
            classPath = new ArrayList<>();
        }
        for (JsonElement element : libraries) {
            JsonObject object = element.getAsJsonObject();
            if (checkOsRules(object.getAsJsonArray("rules"))) {
                JsonObject downloads = object.getAsJsonObject("downloads");
                if (downloads != null) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    String path = artifact.get("path").getAsString();
                    File jarFile = new File(gameDirectory, "libraries" + File.separatorChar + path);
                    if (!jarFile.exists()) {
                        throw new NullPointerException("Library: " + jarFile + " not found!");
                    }
                    classPath.add(jarFile);
                } else { // iF 'DownloadS' is nULL, TheN thIs MIgHt wORk RiGhT?
                    String name = object.get("name").getAsString();
                    String[] comp = name.split(":");
                    comp[0] = comp[0].replace('.', File.separatorChar); // groupId
                    String path = comp[0] + File.separatorChar + comp[1] + File.separatorChar + comp[2];
                    File directoryJar = new File(gameDirectory, "libraries" + File.separatorChar + path);
                    File[] jars = directoryJar.listFiles();
                    if (jars != null) {
                        for (File jar : jars) {
                            if (!jar.exists()) {
                                throw new NullPointerException("Library: " + jar + " not found!");
                            }
                            classPath.add(jar);
                        }
                    }
                }
            }
        }
    }

    private void readMainClass(JsonObject clientObj) {
        mainClass = clientObj.get("mainClass").getAsString();
    }

    private void printInfo() {
        out.println("Game Arguments: " + gameArguments);
        out.println("JVM Arguments: " + jvmArguments);
        out.println("Classpath: " + classPath);
        out.println("Main Class: " + mainClass);
    }

    private void preGameSetArguments(JsonObject clientJson) {
        out.println("Setting some game arguments...");
        String version = clientJson.get("id").getAsString();
        gameArguments.put("--version", version);
//        // I dont know???
//        if (version.chars().filter(i -> i == '.').count() >= 2) {
//            gameArguments.put("--assetIndex", version.substring(0, version.lastIndexOf('.')));
//        } else {
//            gameArguments.put("--assetIndex", version);
//        }
        gameArguments.put("--assetIndex", "5"); // ..?
        gameArguments.put("--versionType", clientJson.get("type").getAsString());
    }

    private void preJVMSetArguments(JsonObject clientJson) {
        out.println("Setting some JVM arguments...");
        String version = clientJson.get("id").getAsString();
        File natives = new File(gameDirectory, "versions" + File.separatorChar + version + File.separatorChar + "natives");
        String path;
        try {
            path = natives.getCanonicalPath();
        } catch (IOException e) {
            if (!natives.exists()) throw new RuntimeException("'natives' doesn't exist on version directory!");
            path = natives.getAbsolutePath();
        }
        path = '\"' + path + '\"';

        jvmArguments.put("-Djava.library.path", path);
        jvmArguments.put("-Djna.tmpdir", path);
        jvmArguments.put("-Dorg.lwjgl.system.SharedLibraryExtractPath", path);
        jvmArguments.put("-Dio.netty.native.workdir", path);

        // fabric fix
        if (jvmArguments.containsKey("-DFabricMcEmu= net.minecraft.client.main.Main ")) {
            jvmArguments.remove("-DFabricMcEmu= net.minecraft.client.main.Main ");
            jvmArguments.put("-DFabricMcEmu=net.minecraft.client.main.Main", null);
        }
    }

    public void setGameArguments() {
        out.println("Setting more game arguments...");
        gameArguments.put("--username", username);
        gameArguments.put("--gameDir", '\"' + gameDirectory.getAbsolutePath() + '\"');
        gameArguments.put("--assetsDir", '\"' + new File(gameDirectory, "assets").getAbsolutePath() + '\"');
        gameArguments.put("--uuid", getUUIDFromString(username).toString().replace("-", ""));
        gameArguments.put("--accessToken", "null");
        gameArguments.put("--clientId", "0");
        gameArguments.put("--xuid", "0");
        gameArguments.put("--userType", "mojang");

        Map<String, String> copyArgs = new HashMap<>(gameArguments);
        for (Map.Entry<String, String> entry : copyArgs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value.contains("${")) {
                out.printf("%s is uninitialized yet. Enter a value for this argument (%s %s): ", key, key, value);
                gameArguments.put(key, in.nextLine());
            }
        }

        out.println("New Game Arguments: " + gameArguments);
    }

    public void setJVMArguments() {
        out.println("Setting more JVM arguments...");

        // we add the Minecraft jar file here

        // well we're expecting that the version is present on the 'versions' folder
        // and is right next to the client.json file we're parsing
        // let's poke around and see what happens!
        for (String versions : versionAliases) {
            // we'll go loop around the versions here, so we find a client that exists and works.
            File clientJar = new File(gameDirectory, "versions" + File.separatorChar + versions + File.separatorChar + versions + ".jar");
            if (clientJar.exists()) {
                try {
                    if (isFileNotEmpty(clientJar)) {
                        // okay we chose this one I hope this is a JAR file
                        // ... and hopefully not malware... I hope.
                        classPath.add(clientJar);
                        out.println("Found client: " + clientJar);
                        break;
                    }
                } catch (IOException e) {
                    /* we're not choosing this version I guess */
                }
            }
        }

        String classPathList = classPath.stream().map(f -> {
            try {
                return f.getCanonicalFile();
            } catch (IOException e) {
                return f;
            }
        }).map(File::toString).reduce("", (result, file) -> '\"' + file + "\";" + result);
        jvmArguments.put("-cp", classPathList);

        jvmArguments.put("-Dminecraft.launcher.brand", "minecraft-launcher");
        jvmArguments.put("-Dminecraft.launcher.version", "2.3.173");

        out.println("New JVM Arguments: " + jvmArguments);
    }

    private List<String> gameArgumentList, jvmArgumentList;

    public void parseGameArguments() {
        out.println("Parsing game argumnets...");
        gameArgumentList = new ArrayList<>();
        for (Map.Entry<String, String> argEntry : gameArguments.entrySet()) {
            String key = argEntry.getKey();
            String value = argEntry.getValue();
            gameArgumentList.add(key);
            if (value != null) {
                gameArgumentList.add(value);
            }
        }
        gameArgumentList.addAll(extraArgumentsToList(extraGameArguments));
    }

    public void parseJVMArguments() {
        out.println("Parsing JVM argumnets...");
        jvmArgumentList = new ArrayList<>();
        for (Map.Entry<String, String> argEntry : jvmArguments.entrySet()) {
            String key = argEntry.getKey();
            String value = argEntry.getValue();
            if (key.equals("-cp")) {
                jvmArgumentList.add("-cp");
                jvmArgumentList.add(value);
            } else if (value != null) {
                jvmArgumentList.add(key + '=' + value);
            } else {
                jvmArgumentList.add(key);
            }
        }
        jvmArgumentList.addAll(extraArgumentsToList(extraJVMArguments));
    }

    private ProcessBuilder builder;

    public void createProcessBuilder() {
        out.println("Creating builder for process...");
        builder = new ProcessBuilder();
        builder.directory(gameDirectory);
        List<String> allArguments = new ArrayList<>();
        String jvmPath = getJavaVM();
        allArguments.add(jvmPath);
        allArguments.addAll(jvmArgumentList);
        allArguments.add(mainClass);
        allArguments.addAll(gameArgumentList);
        builder.command(allArguments);
        out.println("Final command: ");
        for (String s : allArguments) out.print(s + " ");
        out.println();
        out.print("Are you ready to launch Minecraft? (yes/no) ");
        if (!checkYesOrNo()) {
            out.println("Okay...");
            System.exit(0);
        }
    }

    private List<String> extraArgumentsToList(String extra) {
        if (extra != null) {
            return new ArrayList<>(Arrays.asList(extra.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")));
        } else {
            return Collections.emptyList();
        }
    }

    private Process process;

    public void startMinecraft() {
        out.println("Here we go!");
        out.println();
        try {
            process = builder.inheritIO().start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("An error occured starting Minecraft!");
        }
    }
}
