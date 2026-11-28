package no.beint.vev.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails when a generated query is never called from the module's compiled main code.
 *
 * <p>It reads bytecode after compilation instead of running inside generation, so a query can be
 * generated before the code that calls it is written; only {@code check} (and so {@code build}) fails.
 */
@CacheableTask
public abstract class VerifyQueryUsage extends DefaultTask {
    private static final Pattern NAME = Pattern.compile("^--\\s*(?:vev\\.)?name:\\s*([A-Za-z_][A-Za-z0-9_]*)", Pattern.MULTILINE);

    @InputFiles @IgnoreEmptyDirectories @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getClasses();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getQueries();
    @Input public abstract Property<String> getPackageName();
    @Input public abstract Property<String> getClassName();
    @OutputFile public abstract RegularFileProperty getReport();
    /** Only shortens diagnostics. */
    @Internal public abstract DirectoryProperty getProjectDirectory();

    @TaskAction public void verify() throws IOException {
        String facade = getPackageName().get().replace('.', '/') + "/" + getClassName().get();
        Set<String> declared = new TreeSet<>();
        Set<String> called = new TreeSet<>();
        for (Path root : getClasses().getFiles().stream().map(java.io.File::toPath).filter(Files::isDirectory).toList()) {
            for (Path file : classFiles(root)) {
                String name = root.relativize(file).toString().replace('\\', '/');
                name = name.substring(0, name.length() - ".class".length());
                var model = ClassFile.of().parse(Files.readAllBytes(file));
                if (name.equals(facade)) {
                    for (var method : model.methods()) {
                        String methodName = method.methodName().stringValue();
                        if (method.flags().has(AccessFlag.STATIC) && !method.flags().has(AccessFlag.SYNTHETIC)
                                && !methodName.startsWith("<") && !methodName.contains("$")) {
                            declared.add(methodName);
                        }
                    }
                } else if (!name.startsWith(facade + "$")) {
                    for (var entry : model.constantPool()) {
                        if ((entry instanceof MethodRefEntry || entry instanceof InterfaceMethodRefEntry)
                                && ((MemberRefEntry) entry).owner().asInternalName().equals(facade)) {
                            called.add(((MemberRefEntry) entry).name().stringValue());
                        }
                    }
                }
            }
        }
        List<String> unused = declared.stream().filter(name -> !called.contains(name)).toList();
        Path report = getReport().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, "declared=" + declared.size() + "\ncalled=" + (declared.size() - unused.size())
                + "\nunused=" + String.join(",", unused) + "\n", StandardCharsets.UTF_8);
        if (unused.isEmpty()) return;
        Map<String, String> files = sqlFiles();
        var message = new StringBuilder("Vev generated queries that main code never calls:\n");
        for (String name : unused) {
            message.append("  ").append(files.getOrDefault(name, name)).append(" (")
                    .append(getClassName().get()).append('.').append(name).append(")\n");
        }
        message.append("Call them or delete their SQL files. Generation and compilation accept unused queries; ")
                .append("this check runs with `check`.");
        throw new GradleException(message.toString());
    }

    private Map<String, String> sqlFiles() throws IOException {
        Path queries = getQueries().get().getAsFile().toPath();
        Path project = getProjectDirectory().get().getAsFile().toPath();
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(queries)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".sql")).toList()) {
                var matcher = NAME.matcher(Files.readString(file));
                if (matcher.find()) files.put(matcher.group(1), project.relativize(file).toString().replace('\\', '/'));
            }
        }
        return files;
    }

    private static List<Path> classFiles(Path root) {
        try (var paths = Files.walk(root)) {
            return new ArrayList<>(paths.filter(path -> path.toString().endsWith(".class")).sorted().toList());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
