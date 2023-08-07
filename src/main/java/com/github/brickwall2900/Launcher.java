package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

import static com.github.brickwall2900.IOUtilities.getJavaVersion;
import static com.github.brickwall2900.IOUtilities.readFileToString;

public class Launcher {
    public static final Launcher instance = new Launcher();

    public static void main(String[] args) {
        instance.run(args);
    }

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    public void run(String[] args) {
        init(args);
        initFiles();
        readClientJson(clientJsonFile);
        printInfo();
    }

    private String clientJson, username, gameDirectoryPath;
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
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid argument at \"" + lastParsed + '\"');
        }
        if (clientJson == null || username == null || gameDirectoryPath == null) {
            System.err.println("Usage: Launcher [--client-json=<client json file>] [--username=<player name>] [--game-directory=<.minecraft game directory>]");
            System.err.println(" ..or: Launcher [-client=<client json file>] [--name=<player name>] [-game-dir=<.minecraft game directory>]");
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
            JsonObject arguments = object.getAsJsonObject("arguments");
            out.printf("%s: Reading game arguments%n", jsonFile);
            readGameArguments(arguments);
            out.printf("%s: Reading JVM arguments%n", jsonFile);
            readJVMArguments(arguments);
            JsonArray libraries = object.getAsJsonArray("libraries");
            out.printf("%s: Reading classpath%n", jsonFile);
            readClassPath(libraries);
            out.printf("%s: Reading main class%n", jsonFile);
            readMainClass(object);
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

    private void readClassPath(JsonArray libraries) {
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
}
