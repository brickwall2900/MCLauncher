package com.github.brickwall2900;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class ModManager implements LauncherProcess {
    public static final ModManager instance = new ModManager();

    public static void main(String[] args) {
        instance.run(args);
    }

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    private ModrinthAPI modrinthAPI;

    @Override
    public void run(String[] args) {
        initAPI();
    }

    private void initAPI() {
        try {
            String token = IOUtilities.readFileToString(new File(".token"));
            modrinthAPI = new ModrinthAPI(token);
        } catch (IOException e) {
            throw new NoSuchElementException("Unable to find token file!", e);
        }
    }
}
