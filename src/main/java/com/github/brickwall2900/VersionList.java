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

public class VersionList {
    public static final VersionList instance = new VersionList();

    public static void main(String[] args) {
        instance.run(args);
    }

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    public void run(String[] args) {
        init(args);
        getVersionJson();
        chooseVersion();
        downloadChosenVersion();
    }

    private String path;

    public void init(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("-p=") || arg.startsWith("--path=")) path = arg.split("=")[1];
        }
    }

    private JsonObject versionObject;
    private String release, snapshot;
    private JsonArray versionJsonArray;
    private List<JsonElement> versionJsonElementList;

    public static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
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
        out.println("'=' to set the page directly");
        out.println("Enter a number to choose the version.");
        while (chosenVersion <= 0) {
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

    private void readAndExecuteCommand() {
        int size = versionJsonElementList.size();
        int deltaPage = 0;
        int deltaVersionPerPage = 0;
        boolean changedDirectly = false;
        out.print("Command: ");
        String command = in.nextLine();
        String noExpression = command.substring(1);
        if (command.startsWith("<")) {
            deltaPage = -getInt(noExpression, 10);
        } else if (command.startsWith(">")) {
            deltaPage = getInt(noExpression, 10);
        } else if (command.startsWith("+")) {
            deltaVersionPerPage = getInt(noExpression, 1);
        } else if (command.startsWith("-")) {
            deltaVersionPerPage = -getInt(noExpression, 1);
        } else if (command.startsWith("=")) {
            changedDirectly = true;
            page = getInt(command, 0);
        } else if (command.startsWith(":")) {
            int index = versionJsonElementList.stream()
                    .map(e -> {
                        JsonObject object = e.getAsJsonObject();
                        return object.get("id").getAsString();
                    }).toList().indexOf(noExpression);
            if (index > 0) {
                changedDirectly = true;
                page = index;
            } else {
                out.println("Version " + noExpression + " not found!");
            }
        } else {
            try {
                chosenVersion = Integer.parseInt(command);
            } catch (NumberFormatException e) {
                out.println("Invalid number!");
            }
        }
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

        out.printf("Version chosen: %s%n", version);
        out.println("Downloading...");
        if (path == null) {
            out.println("--path not specified.");
            out.print("Input directory: ");
            path = in.nextLine();
        }

        URL url;
        try {
            url = new URL(urlPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException("URL is somehow malformed: " + urlPath, e);
        }

        File outFile = new File(new File(path), version + ".json");
        out.printf("Target: %s%n", outFile);
        try {
            downloadToFile(url, outFile);
        } catch (IOException e) {
            throw new RuntimeException("Error in downloading version manifest!", e);
        }
    }
}
