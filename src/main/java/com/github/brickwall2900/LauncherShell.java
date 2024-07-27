package com.github.brickwall2900;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.jline.builtins.Completers;
import org.jline.jansi.Ansi;
import org.jline.jansi.AnsiConsole;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.*;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

// i just wanted to be loved
public class LauncherShell implements LauncherProcess {
    public static final LauncherShell INSTANCE = new LauncherShell();

    public static void main(String[] args) {
        INSTANCE.run();
    }

    private Terminal terminal;
    private LineReader reader;
    private boolean running = true;
    private DefaultParser parser;

    public void run() {
        System.out.println("Starting...");
        try {
            terminal = TerminalBuilder.builder()
                    .jansi(true)
                    .build();
            AnsiConsole.setTerminal(terminal);
            parser = new DefaultParser().eofOnUnclosedQuote(true);
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new Completers.FilesCompleter(cdSupplider))
                    .parser(parser)
                    .option(LineReader.Option.USE_FORWARD_SLASH, true)
                    .appName("Spaghetti")
                    .build();
            // Using Brigadier with JLine
            // Very Human
            initCommands();
            print("You are in the interactive shell.");
            // HELP DOESN'T EXIST YTE!??!!?
            print("Type 'help' for... help :p");
            while (running) {
                try {
                    String line = reader.readLine("> ");
                    onNewLine(line);
                    print("");
                } catch (Exception e) {
                    print(e);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void printf(String string, Object... objects) {
        terminal.writer().printf(string, objects);
    }

    private void print(Object o) {
        terminal.writer().println(o);
    }

    private static final int CMD_SUCCESS = 0;
    private static final int CMD_DEFEAT = 1;

    private Path currentDirectory = Paths.get(System.getProperty("user.dir", "."));
    private final Supplier<Path> cdSupplider = () -> currentDirectory;

    // command source is our console, might as well set it to null
    private CommandDispatcher<Object> dispatcher;

    private IOCommands ioCommands;
    private InstallerCommand installerCommand;
    private VersionListCommand versionListCommand;
    private LauncherCommand launcherCommand;

    private void initCommands() {
        ioCommands = new IOCommands();
        installerCommand = new InstallerCommand();
        versionListCommand = new VersionListCommand();
        launcherCommand = new LauncherCommand();

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(literal("exit").executes(this::exit));
        dispatcher.register(literal("echo").then(argument("args", greedyString()).executes(this::echo)));
        dispatcher.register(literal("dir").executes(ioCommands::dir).then(argument("path", string()).executes(ioCommands::dir)));
        dispatcher.register(literal("cd").then(argument("path", greedyString()).executes(ioCommands::cd)));
        dispatcher.register(literal("pwd").executes(ioCommands::pwd));

        dispatcher.register(literal("get-version")
                .executes(versionListCommand)
                .then(argument("path", string()).executes(versionListCommand)));
        dispatcher.register(literal("install")
                .executes(installerCommand)
                .then(argument("client", string()).executes(installerCommand)
                        .then(argument("path", string()).executes(installerCommand))));
        dispatcher.register(literal("launch")
                .executes(launcherCommand)
                .then(argument("client", string()).executes(launcherCommand)
                        .then(argument("path", string()).executes(launcherCommand)
                                .then(argument("username", string()).executes(launcherCommand)))));
    }

    private void onNewLine(String line) {
        try {
            if (dispatcher.execute(line.trim(), null) != CMD_SUCCESS) {
                print("Unsuccessful!");
            }
        } catch (CommandSyntaxException e) {
            print(e);
        }
    }

    private int exit(CommandContext<Object> context) {
        running = false;
        return CMD_SUCCESS;
    }

    private int echo(CommandContext<Object> context) {
        print(getString(context, "args"));
        return CMD_SUCCESS;
    }

    @Override
    public void run(String[] args) {
        INSTANCE.run();
    }

    private class IOCommands {
        private int dir(CommandContext<Object> context) {
            Path where = getPath(context, "path");

            try (Stream<Path> stream = Files.walk(where, 1)) {
                List<Path> files = stream.toList();
                StringBuilder stringBuilder = new StringBuilder();
                for (Path path : files) {
                    if (Files.isRegularFile(path)) {
                        stringBuilder.append(Ansi.ansi().fgBrightDefault());
                    } else if (Files.isDirectory(path)) {
                        stringBuilder.append(Ansi.ansi().fgBrightGreen());
                    }
                    stringBuilder.append(path).append(Ansi.ansi().reset()).append("\n");
                }
                print(stringBuilder);
                printf("%d objects listed%n", files.size());
            } catch (IOException e) {
                print(e);
                return CMD_DEFEAT;
            }

            return CMD_SUCCESS;
        }

        private int cd(CommandContext<Object> context) {
            String fileLocation = getString(context, "path");
            Path path = currentDirectory.resolve(fileLocation);

            if (Files.exists(path) && Files.isDirectory(path)) {
                currentDirectory = path.normalize();
                print(currentDirectory);
                return CMD_SUCCESS;
            } else {
                printf("No such directory: %s%n", fileLocation);
                return CMD_DEFEAT;
            }
        }

        private int pwd(CommandContext<Object> context) {
            print(currentDirectory);
            return CMD_SUCCESS;
        }
    }

    private Path getPath(CommandContext<Object> context, String name) {
        Path path;
        try {
            path = Paths.get(getString(context, name));
        } catch (IllegalArgumentException e) {
            path = currentDirectory;
        }
        return path;
    }

    private Path getPath(CommandContext<Object> context, String name, String promptIfNull) {
        Path path;
        try {
            path = Paths.get(getString(context, name));
        } catch (IllegalArgumentException e) {
            path = Paths.get(reader.readLine(promptIfNull));
        }
        return path;
    }

    private String getStringOrAsk(CommandContext<Object> context, String name, String promptIfNull) {
        String string;
        try {
            string = getString(context, name);
        } catch (IllegalArgumentException e) {
            string = reader.readLine(promptIfNull);
        }
        return string;
    }

    private class InstallerCommand implements Command<Object> {
        private final Installer installer = Installer.instance;

        public InstallerCommand() {
            installer.in = new UserReader.TerminalReader(reader);
            installer.out = new PrintStream(new WriterOutputStream(terminal.writer(), Charset.defaultCharset()));
        }

        public int run(CommandContext<Object> context) {
            Path directory = getPath(context, "path");
            if (!Files.exists(directory)) {
                printf("%s doesn't exist!%n", directory);
                return CMD_DEFEAT;
            }
            printf("Installer will install at directory: %s%n", directory);

            Path clientFile = getPath(context, "client", "Client to install? ");
            if (!(Files.exists(clientFile) && Files.isRegularFile(clientFile))) {
                printf("%s doesn't exist or isn't a file!%n", clientFile);
                return CMD_DEFEAT;
            }

            List<String> argsList = new ArrayList<>();
            argsList.add("-dir=" + directory);
            argsList.add("-client=" + clientFile);

            try {
                installer.run(argsList.toArray(new String[0]));
            } catch (Exception e) {
                print(e);
                return CMD_DEFEAT;
            }
            return CMD_SUCCESS;
        }
    }

    private class VersionListCommand implements Command<Object> {
        private final VersionList versionList = VersionList.instance;

        public VersionListCommand() {
            versionList.in = new UserReader.TerminalReader(reader);
            versionList.out = new PrintStream(new WriterOutputStream(terminal.writer(), Charset.defaultCharset()));
        }

        public int run(CommandContext<Object> context) {
            Path directory = getPath(context, "path");
            if (!Files.exists(directory)) {
                printf("%s doesn't exist!%n", directory);
                return CMD_DEFEAT;
            }

            printf("VersionList will run at directory: %s%n", directory);
            try {
                versionList.run(new String[] { "--path=" + directory });
            } catch (Exception e) {
                print(e);
                return CMD_DEFEAT;
            }
            return CMD_SUCCESS;
        }
    }

    private class LauncherCommand implements Command<Object> {
        private final Launcher launcher = Launcher.instance;;

        public LauncherCommand() {
            launcher.in = new UserReader.TerminalReader(reader);
            launcher.out = new PrintStream(new WriterOutputStream(terminal.writer(), Charset.defaultCharset()));
        }

        public int run(CommandContext<Object> context) {
            Path directory = getPath(context, "path");
            if (!Files.exists(directory)) {
                printf("%s doesn't exist!%n", directory);
                return CMD_DEFEAT;
            }

            printf("VersionList will run at directory: %s%n", directory);

            Path clientFile = getPath(context, "client", "Client to launch? ");
            if (!(Files.exists(clientFile) && Files.isRegularFile(clientFile))) {
                printf("%s doesn't exist or isn't a file!%n", clientFile);
                return CMD_DEFEAT;
            }

            String username = getStringOrAsk(context, "username", "Username? ");

            List<String> argsList = new ArrayList<>();
            argsList.add("-game-dir=" + directory);
            argsList.add("-client=" + clientFile);
            argsList.add("-name=" + username);

            try {
                launcher.run(argsList.toArray(new String[0]));
            } catch (Exception e) {
                print(e);
                return CMD_DEFEAT;
            }

            return CMD_SUCCESS;
        }
    }
}
