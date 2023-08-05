package com.github.brickwall2900;

import java.io.PrintStream;
import java.util.Scanner;

public class Launcher {
    public static final Launcher instance = new Launcher();

    public static void main(String[] args) {
        instance.run(args);
    }

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    public void run(String[] args) {

    }
}
