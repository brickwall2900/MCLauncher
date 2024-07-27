package com.github.brickwall2900;

import org.jline.reader.LineReader;

import java.io.InputStream;
import java.util.Scanner;

public interface UserReader {
    String readInput();
    String readInput(String prompt);

    class ScannerReader implements UserReader {
        private final Scanner scanner;

        public ScannerReader(InputStream stream) {
            scanner = new Scanner(stream);
        }

        @Override
        public String readInput() {
            return scanner.nextLine();
        }

        @Override
        public String readInput(String prompt) {
            System.out.print(prompt);
            return scanner.nextLine();
        }
    }

    class TerminalReader implements UserReader {
        private final LineReader reader;

        public TerminalReader(LineReader reader) {
            this.reader = reader;
        }

        @Override
        public String readInput() {
            return reader.readLine();
        }

        @Override
        public String readInput(String prompt) {
            return reader.readLine(prompt);
        }
    }
}
