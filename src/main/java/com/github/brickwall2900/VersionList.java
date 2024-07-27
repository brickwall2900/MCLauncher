package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

import static com.github.brickwall2900.IOUtilities.*;

public class VersionList implements LauncherProcess {
    public static final VersionList instance = new VersionList();

    public static void main(String[] args) {
        instance.run(args);
    }

    public PrintStream out = System.out;
    public UserReader in = new UserReader.ScannerReader(System.in);

    public void run(String[] args) {
        init(args);
        getVersionJson();
        chooseVersion();
        downloadChosenVersion();
    }

    private String path;
    private boolean confirmAll;

    public void init(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("-p=") || arg.startsWith("--path=")) path = arg.split("=")[1];
            if (arg.equalsIgnoreCase("-y") || arg.equalsIgnoreCase("--confirm-yes")) confirmAll = true;
        }
        if (confirmAll) {
            out.println("Confirming 'yes' to all questions!");
        }
    }

    private JsonObject versionObject;
    private String release, snapshot;
    private JsonArray versionJsonArray;
    private List<JsonElement> versionJsonElementList;

    public static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public void getVersionJson() {
        try {
            out.println("Downloading version list...");
            URL url = new URL(VERSION_MANIFEST);
            versionObject = JsonParser.parseString(downloadToString(url)).getAsJsonObject();

            JsonObject latestVersions = versionObject.getAsJsonObject("latest");
            release = latestVersions.get("release").getAsString();
            snapshot = latestVersions.get("snapshot").getAsString();

            versionJsonArray = versionObject.getAsJsonArray("versions");
            versionJsonElementList = versionJsonArray.asList();
        } catch (IOException e) {
            throw new RuntimeException("Error in parsing/reading version_manifest.json!", e);
        }
    }

    private int page;
    private int versionsPerPage = 10;
    private int chosenVersion = -1;

    public void chooseVersion() {
        out.println("'<' and '>' to navigate between versions.");
        out.println("'+' and '-', and a number increases and decreases the number of versions shown in a page");
        out.println("'=' to jump to a page");
        out.println("':' to specify version with version name");
        out.println("Commands are typed out like this: (expression)(value (optional))");
        out.println("Enter a number to choose the version.");
        while (chosenVersion < 0) {
            readVersionPage();
            readAndExecuteCommand();
        }
    }

    private void readVersionPage() {
        out.printf("=== PAGE %d ===%n", page);
        for (int i = page; i < Math.min(page + versionsPerPage, versionJsonElementList.size()); i++) {
            JsonElement element = versionJsonElementList.get(i);
            JsonObject object = element.getAsJsonObject();

            String version = object.get("id").getAsString();
            String type = object.get("type").getAsString();

            if (version.equalsIgnoreCase(release) || version.equalsIgnoreCase(snapshot)) {
                type += " [latest]";
            }

            out.printf("(%d) - %s [%s] %n", i, version, type);
        }
    }

    private int getInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean checkYesOrNo(String prompt) {
        String next = in.readInput(prompt);
        return !confirmAll && (next.equalsIgnoreCase("yes") || next.equalsIgnoreCase("y"));
    }

    /**
     * Finds the index with the specified version ID
     * @param id Version ID
     * @return index to the version ID, returns -1 if cannot be found
     */
    private int findIndexWithName(String id) {
        return versionJsonElementList.stream()
                .map(e -> {
                    JsonObject object = e.getAsJsonObject();
                    return object.get("id").getAsString();
                }).toList().indexOf(id);
    }

    private void readAndExecuteCommand() {
        int size = versionJsonElementList.size();
        int deltaPage = 0;
        int deltaVersionPerPage = 0;
        boolean changedDirectly = false;
        String command = in.readInput("Command: ");
        // this hurts my head now I'm looking back
        String input = command.substring(1);
        if (command.startsWith("<")) {
            deltaPage = -getInt(input, 10);
        } else if (command.startsWith(">")) {
            deltaPage = getInt(input, 10);
        } else if (command.startsWith("+")) {
            deltaVersionPerPage = getInt(input, 1);
        } else if (command.startsWith("-")) {
            deltaVersionPerPage = -getInt(input, 1);
        } else if (command.startsWith("=")) {
            changedDirectly = true;
            page = getInt(input, 0);
        } else if (command.startsWith(":")) {
            int index = findIndexWithName(input);
            if (index >= 0) {
                changedDirectly = true;
                page = index;
            } else {
                out.println("Version " + input + " not found!");
            }
        } else {
            try {
                chosenVersion = Integer.parseInt(command);
                if (chosenVersion >= 0 && chosenVersion < versionJsonElementList.size()) {
                    JsonElement element = versionJsonElementList.get(chosenVersion);
                    JsonObject object = element.getAsJsonObject();

                    String version = object.get("id").getAsString();
                    if (!checkYesOrNo(String.format("Version %s was chosen, continue? ", version))) {
                        chosenVersion = -1;
                    }
                } else {
                    out.println("Invalid version chosen!");
                }
            } catch (NumberFormatException e) {
                out.println("Invalid number!");
            }
        }
        page = Math.max(0, Math.min(page, versionJsonElementList.size() - 1));
        if (!changedDirectly) {
            page = Math.max(Math.min(page + deltaPage, size), 0);
        }
        versionsPerPage = Math.max(Math.min(versionsPerPage + deltaVersionPerPage, size), 1);

    }

    private void downloadChosenVersion() {
        JsonElement element = versionJsonElementList.get(chosenVersion);
        JsonObject object = element.getAsJsonObject();

        String version = object.get("id").getAsString();
        String urlPath = object.get("url").getAsString();
        String sha1 = object.get("sha1").getAsString();

        out.printf("Version chosen: %s%n", version);
        out.println("Downloading...");
        if (path == null) {
            out.println("--path not specified.");
            out.print("Input directory: ");
            path = in.readInput();
        }

        URL url;
        try {
            url = new URL(urlPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException("URL is somehow malformed: " + urlPath, e);
        }

        File outFile = new File(new File(path), version + ".json");
        out.printf("Target: %s%n", outFile);
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(outFile, sha1, SHA1_ALGORITHM); i++) {
            try {
                downloadToFile(url, outFile);
            } catch (IOException e) {
                throw new RuntimeException("Error in downloading version manifest!", e);
            }
        }
        out.printf("%s downloaded and verified!%n", outFile);
    }
}
