/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.console;

import grakn.client.Grakn;
import grakn.client.api.GraknClient;
import grakn.client.api.GraknOptions;
import grakn.client.api.GraknSession;
import grakn.client.api.GraknTransaction;
import grakn.client.api.answer.ConceptMap;
import grakn.client.api.answer.ConceptMapGroup;
import grakn.client.api.answer.Numeric;
import grakn.client.api.answer.NumericGroup;
import grakn.client.api.database.Database;
import grakn.client.common.exception.GraknClientException;
import grakn.common.util.Java;
import grakn.console.command.ReplCommand;
import grakn.console.command.TransactionReplCommand;
import grakn.console.common.Printer;
import grakn.console.common.exception.GraknConsoleException;
import graql.lang.Graql;
import graql.lang.common.exception.GraqlException;
import graql.lang.query.GraqlCompute;
import graql.lang.query.GraqlDefine;
import graql.lang.query.GraqlDelete;
import graql.lang.query.GraqlInsert;
import graql.lang.query.GraqlMatch;
import graql.lang.query.GraqlQuery;
import graql.lang.query.GraqlUndefine;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static grakn.common.collection.Collections.set;
import static grakn.console.common.exception.ErrorMessage.Console.INCOMPATIBLE_JAVA_RUNTIME;
import static java.util.stream.Collectors.toList;

public class GraknConsole {
    private static final Logger LOG = LoggerFactory.getLogger(GraknConsole.class);

    private static final String COPYRIGHT = "\n" +
            "Welcome to Grakn Console. You are now in Grakn Wonderland!\n" +
            "Copyright (C) 2021 Grakn Labs\n";
    private final Printer printer;
    private ExecutorService executorService;
    private Terminal terminal;

    public GraknConsole(Printer printer) {
        this.printer = printer;
        try {
            executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            terminal = TerminalBuilder.builder().signalHandler(Terminal.SignalHandler.SIG_IGN).build();
        } catch (IOException e) {
            System.err.println("Failed to initialise terminal: " + e.getMessage());
            System.exit(1);
        }
    }

    private GraknClient createGraknClient(CommandLineOptions options) {
        GraknClient client = null;
        try {
            if (options.server() != null) {
                client = Grakn.coreClient(options.server());
            } else if (options.cluster() != null) {
                client = Grakn.clusterClient(set(options.cluster().split(",")));
            } else {
                client = Grakn.coreClient(Grakn.DEFAULT_ADDRESS);
            }
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            System.exit(1);
        }
        return client;
    }

    public boolean runScript(CommandLineOptions options, String script) {
        String scriptLines;
        try {
            scriptLines = new String(Files.readAllBytes(Paths.get(Objects.requireNonNull(script))), StandardCharsets.UTF_8);
        } catch (IOException e) {
            printer.error("Failed to open file '" + options.script() + "'");
            return false;
        }
        return runCommands(options, Arrays.stream(scriptLines.split("\n")).collect(toList()));
    }

    public boolean runCommands(CommandLineOptions options, List<String> commandStrings) {
        commandStrings = commandStrings.stream().map(x -> x.trim()).filter(x -> !x.isEmpty()).collect(toList());
        boolean[] cancelled = new boolean[]{false};
        terminal.handle(Terminal.Signal.INT, s -> cancelled[0] = true);
        try (GraknClient client = createGraknClient(options)) {
            int i = 0;
            for (; i < commandStrings.size() && !cancelled[0]; i++) {
                String commandString = commandStrings.get(i);
                printer.info("+ " + commandString);
                ReplCommand command = ReplCommand.getCommand(commandString, client.isCluster());
                if (command != null) {
                    if (command.isDatabaseList()) {
                        boolean success = runDatabaseList(client);
                        if (!success) return false;
                    } else if (command.isDatabaseCreate()) {
                        boolean success = runDatabaseCreate(client, command.asDatabaseCreate().database());
                        if (!success) return false;
                    } else if (command.isDatabaseSchema()) {
                        boolean success = runDatabaseSchema(client, command.asDatabaseSchema().database());
                        if (!success) return false;
                    } else if (command.isDatabaseDelete()) {
                        boolean success = runDatabaseDelete(client, command.asDatabaseDelete().database());
                        if (!success) return false;
                    } else if (command.isDatabaseReplicas()) {
                        boolean success = runDatabaseReplicas(client, command.asDatabaseReplicas().database());
                        if (!success) return false;
                    } else if (command.isTransaction()) {
                        String database = command.asTransaction().database();
                        GraknSession.Type sessionType = command.asTransaction().sessionType();
                        GraknTransaction.Type transactionType = command.asTransaction().transactionType();
                        GraknOptions sessionOptions = command.asTransaction().options();
                        if (sessionOptions.isCluster() && !client.isCluster()) {
                            printer.error("The option '--any-replica' is only available in Grakn Cluster.");
                            return false;
                        }
                        try (GraknSession session = client.session(database, sessionType, sessionOptions);
                             GraknTransaction tx = session.transaction(transactionType)) {
                            for (i += 1; i < commandStrings.size() && !cancelled[0]; i++) {
                                String txCommandString = commandStrings.get(i);
                                printer.info("++ " + txCommandString);
                                TransactionReplCommand txCommand = Objects.requireNonNull(TransactionReplCommand.getCommand(txCommandString));
                                if (txCommand.isCommit()) {
                                    runCommit(tx);
                                    break;
                                } else if (txCommand.isRollback()) {
                                    runRollback(tx);
                                } else if (txCommand.isClose()) {
                                    runClose(tx);
                                    break;
                                } else if (txCommand.isSource()) {
                                    boolean success = runSource(tx, txCommand.asSource().file());
                                    if (!success) return false;
                                } else if (txCommand.isQuery()) {
                                    boolean success = runQuery(tx, txCommand.asQuery().query());
                                    if (!success) return false;
                                } else {
                                    printer.error("Command is not available while running console script.");
                                }
                            }
                        } catch (GraknClientException e) {
                            printer.error(e.getMessage());
                            return false;
                        }
                    } else {
                        printer.error("Command is not available while running console script.");
                    }
                } else {
                    printer.error("Unrecognised command, exit console script.");
                    return false;
                }
            }
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            return false;
        } finally {
            executorService.shutdownNow();
        }
        return true;
    }

    public void runInteractive(CommandLineOptions options) {
        printer.info(COPYRIGHT);
        try (GraknClient client = createGraknClient(options)) {
            runRepl(client);
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
        } finally {
            executorService.shutdownNow();
        }
    }

    private void runRepl(GraknClient client) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, Paths.get(System.getProperty("user.home"), ".grakn-console-command-history").toAbsolutePath())
                .build();
        while (true) {
            ReplCommand command;
            try {
                command = ReplCommand.getCommand(reader, printer, "> ", client.isCluster());
            } catch (InterruptedException e) {
                break;
            }
            if (command.isExit()) {
                break;
            } else if (command.isHelp()) {
                printer.info(ReplCommand.getHelpMenu(client));
            } else if (command.isClear()) {
                reader.getTerminal().puts(InfoCmp.Capability.clear_screen);
            } else if (command.isDatabaseList()) {
                runDatabaseList(client);
            } else if (command.isDatabaseCreate()) {
                runDatabaseCreate(client, command.asDatabaseCreate().database());
            } else if (command.isDatabaseDelete()) {
                runDatabaseDelete(client, command.asDatabaseDelete().database());
            } else if (command.isDatabaseSchema()) {
                runDatabaseSchema(client, command.asDatabaseSchema().database());
            } else if (command.isDatabaseReplicas()) {
                runDatabaseReplicas(client, command.asDatabaseReplicas().database());
            } else if (command.isTransaction()) {
                String database = command.asTransaction().database();
                GraknSession.Type sessionType = command.asTransaction().sessionType();
                GraknTransaction.Type transactionType = command.asTransaction().transactionType();
                GraknOptions graknOptions = command.asTransaction().options();
                if (graknOptions.isCluster() && !client.isCluster()) {
                    printer.error("The option '--any-replica' is only available in Grakn Cluster.");
                    continue;
                }
                boolean shouldExit = runTransactionRepl(client, database, sessionType, transactionType, graknOptions);
                if (shouldExit) break;
            }
        }
    }

    private boolean runTransactionRepl(GraknClient client, String database, GraknSession.Type sessionType, GraknTransaction.Type transactionType, GraknOptions options) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, Paths.get(System.getProperty("user.home"), ".grakn-console-transaction-history").toAbsolutePath())
                .build();
        StringBuilder prompt = new StringBuilder(database + "::" + sessionType.name().toLowerCase() + "::" + transactionType.name().toLowerCase());
        if (options.isCluster() && options.asCluster().readAnyReplica().isPresent() && options.asCluster().readAnyReplica().get())
            prompt.append("[any-replica]");
        prompt.append("> ");
        try (GraknSession session = client.session(database, sessionType, options);
             GraknTransaction tx = session.transaction(transactionType, options)) {
            while (true) {
                TransactionReplCommand command;
                try {
                    command = TransactionReplCommand.getCommand(reader, prompt.toString());
                } catch (InterruptedException e) {
                    break;
                }
                if (command.isExit()) {
                    return true;
                } else if (command.isClear()) {
                    reader.getTerminal().puts(InfoCmp.Capability.clear_screen);
                } else if (command.isHelp()) {
                    printer.info(TransactionReplCommand.getHelpMenu());
                } else if (command.isCommit()) {
                    runCommit(tx);
                    break;
                } else if (command.isRollback()) {
                    runRollback(tx);
                } else if (command.isClose()) {
                    runClose(tx);
                    break;
                } else if (command.isSource()) {
                    runSource(tx, command.asSource().file());
                } else if (command.isQuery()) {
                    runQuery(tx, command.asQuery().query());
                }
            }
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
        }
        return false;
    }

    private boolean runDatabaseList(GraknClient client) {
        try {
            if (client.databases().all().size() > 0)
                client.databases().all().forEach(database -> printer.info(database.name()));
            else printer.info("No databases are present on the server.");
            return true;
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            return false;
        }
    }

    private boolean runDatabaseCreate(GraknClient client, String database) {
        try {
            client.databases().create(database);
            printer.info("Database '" + database + "' created");
            return true;
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            return false;
        }
    }

    private boolean runDatabaseSchema(GraknClient client, String database) {
        try {
            String schema = client.databases().get(database).schema();
            printer.info(schema);
            return true;
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            return false;
        }
    }

    private boolean runDatabaseDelete(GraknClient client, String database) {
        try {
            client.databases().get(database).delete();
            printer.info("Database '" + database + "' deleted");
            return true;
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            return false;
        }
    }

    private boolean runDatabaseReplicas(GraknClient client, String database) {
        try {
            if (!client.isCluster()) {
                printer.error("The command 'database replicas' is only available in Grakn Cluster.");
                return false;
            }
            for (Database.Replica replica : client.asCluster().databases().get(database).replicas()) {
                printer.databaseReplica(replica);
            }
            return true;
        } catch (GraknClientException e) {
            printer.error(e.getMessage());
            return false;
        }
    }

    private void runCommit(GraknTransaction tx) {
        tx.commit();
        printer.info("Transaction changes committed");
    }

    private void runRollback(GraknTransaction tx) {
        tx.rollback();
        printer.info("Transaction changes committed");
    }

    private void runClose(GraknTransaction tx) {
        tx.close();
        if (tx.type().isWrite()) printer.info("Transaction closed without committing changes");
        else printer.info("Transaction closed");
    }

    private boolean runSource(GraknTransaction tx, String file) {
        try {
            String queryString = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            return runQuery(tx, queryString);
        } catch (IOException e) {
            printer.error("Failed to open file '" + file + "'");
            return false;
        }
    }

    private boolean runQuery(GraknTransaction tx, String queryString) {
        List<GraqlQuery> queries;
        try {
            queries = Graql.parseQueries(queryString).collect(toList());
        } catch (GraqlException e) {
            printer.error(e.getMessage());
            return false;
        }
        for (GraqlQuery query : queries) {
            if (query instanceof GraqlDefine) {
                tx.query().define(query.asDefine()).get();
                printer.info("Concepts have been defined");
            } else if (query instanceof GraqlUndefine) {
                tx.query().undefine(query.asUndefine()).get();
                printer.info("Concepts have been undefined");
            } else if (query instanceof GraqlInsert) {
                Stream<ConceptMap> result = tx.query().insert(query.asInsert());
                printCancellableResult(result, x -> printer.conceptMap(x, tx));
            } else if (query instanceof GraqlDelete) {
                tx.query().delete(query.asDelete()).get();
                printer.info("Concepts have been deleted");
            } else if (query instanceof GraqlMatch) {
                Stream<ConceptMap> result = tx.query().match(query.asMatch());
                printCancellableResult(result, x -> printer.conceptMap(x, tx));
            } else if (query instanceof GraqlMatch.Aggregate) {
                Numeric answer = tx.query().match(query.asMatchAggregate()).get();
                printer.numeric(answer);
            } else if (query instanceof GraqlMatch.Group) {
                Stream<ConceptMapGroup> result = tx.query().match(query.asMatchGroup());
                printCancellableResult(result, x -> printer.conceptMapGroup(x, tx));
            } else if (query instanceof GraqlMatch.Group.Aggregate) {
                Stream<NumericGroup> result = tx.query().match(query.asMatchGroupAggregate());
                printCancellableResult(result, x -> printer.numericGroup(x, tx));
            } else if (query instanceof GraqlCompute) {
                throw new GraknConsoleException("Compute query is not yet supported");
            }
        }
        return true;
    }

    private <T> void printCancellableResult(Stream<T> results, Consumer<T> printFn) {
        long[] counter = new long[]{0};
        Instant start = Instant.now();
        Terminal.SignalHandler prevHandler = null;
        try {
            Iterator<T> iterator = results.iterator();
            Future<?> answerPrintingJob = executorService.submit(() -> {
                while (iterator.hasNext() && !Thread.interrupted()) {
                    printFn.accept(iterator.next());
                    counter[0]++;
                }
            });
            prevHandler = terminal.handle(Terminal.Signal.INT, s -> answerPrintingJob.cancel(true));
            answerPrintingJob.get();
            Instant end = Instant.now();
            printer.info("answers: " + counter[0] + ", duration: " + Duration.between(start, end).toMillis() + " ms");
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            throw (GraknClientException) e.getCause();
        } catch (CancellationException e) {
            Instant end = Instant.now();
            printer.info("answers: " + counter[0] + ", duration: " + Duration.between(start, end).toMillis() + " ms");
            printer.info("The query has been cancelled. It may take some time for the cancellation to finish on the server side.");
        } finally {
            if (prevHandler != null) terminal.handle(Terminal.Signal.INT, prevHandler);
        }
    }

    public static void main(String[] args) {
        configureAndVerifyJavaVersion();
        CommandLineOptions options = parseCommandLine(args);
        GraknConsole console = new GraknConsole(new Printer(System.out, System.err));
        if (options.script() == null && options.commands() == null) {
            console.runInteractive(options);
        } else if (options.script() != null) {
            boolean success = console.runScript(options, options.script());
            if (!success) System.exit(1);
        } else if (options.commands() != null) {
            boolean success = console.runCommands(options, options.commands());
            if (!success) System.exit(1);
        }
    }

    private static void configureAndVerifyJavaVersion() {
        int majorVersion = Java.getMajorVersion();
        if (majorVersion == Java.UNKNOWN_VERSION) {
            LOG.warn("Could not detect Java version from version string '{}'. Will start Grakn Core Server anyway.", System.getProperty("java.version"));
        } else if (majorVersion < 11) {
            throw GraknConsoleException.of(INCOMPATIBLE_JAVA_RUNTIME, majorVersion);
        }
    }

    private static CommandLineOptions parseCommandLine(String[] args) {
        CommandLineOptions options = new CommandLineOptions();
        CommandLine command = new CommandLine(options);
        try {
            command.parseArgs(args);
            if (command.isUsageHelpRequested()) {
                command.usage(command.getOut());
                System.exit(0);
            } else if (command.isVersionHelpRequested()) {
                command.printVersionHelp(command.getOut());
                System.exit(0);
            } else {
                return options;
            }
        } catch (CommandLine.ParameterException ex) {
            command.getErr().println(ex.getMessage());
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, command.getErr())) {
                ex.getCommandLine().usage(command.getErr());
            }
            System.exit(1);
        }
        return null;
    }

    @CommandLine.Command(name = "grakn console", mixinStandardHelpOptions = true, version = {grakn.console.Version.VERSION})
    public static class CommandLineOptions {

        @CommandLine.Option(names = {"--server"},
                description = "Grakn Core address to which Console will connect to")
        private @Nullable
        String server;

        @Nullable
        public String server() {
            return server;
        }

        @CommandLine.Option(names = {"--cluster"},
                description = "Grakn Cluster address to which Console will connect to")
        private @Nullable
        String cluster;

        @Nullable
        public String cluster() {
            return cluster;
        }

        @CommandLine.Option(names = {"--script"},
                description = "Script with commands to run in the Console, without interactive mode")
        private @Nullable
        String script;

        @Nullable
        public String script() {
            return script;
        }

        @CommandLine.Option(names = {"--command"},
                description = "Commands to run in the Console, without interactive mode")
        private @Nullable
        List<String> commands;

        @Nullable
        public List<String> commands() {
            return commands;
        }
    }
}
