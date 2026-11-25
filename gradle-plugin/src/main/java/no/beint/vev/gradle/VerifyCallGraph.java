package no.beint.vev.gradle;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Verifies cross-service Vev {@code Db} transaction boundaries across compiled bytecode with no allowlists:
 * <ol>
 *   <li>{@code READ->WRITE}: No read-only transaction ({@code read*}) transitively invokes a write/locking joiner
 *       ({@code write}, {@code tenantWrite}, {@code writeSerializable}, {@code savepoint}, {@code beforeCommit},
 *       {@code requireWriteTransaction}).</li>
 *   <li>{@code ISOLATION-ELEVATION}: No {@code READ COMMITTED} transaction transitively invokes
 *       {@code readRepeatable} or {@code writeSerializable}.</li>
 *   <li>{@code NESTED-ISOLATED}: No active transaction transitively invokes {@code writeIsolated}.</li>
 *   <li>{@code ESCAPED-SCOPE}: No Vev scope ({@code ReadScope}/{@code WriteScope}/{@code TenantReadScope}/
 *       {@code TenantWriteScope}) is captured in an {@code afterCommit}/{@code afterCompletion}/async lambda.</li>
 *   <li>{@code SCHEDULED-NO-TENANT}: No entry point carrying a configured annotation (by default Spring
 *       {@code @Scheduled}) transitively calls ambient {@code Db.tenant*} without binding a tenant explicitly
 *       via {@code Db.read(int)}/{@code write(int)} or a configured tenant binder.</li>
 *   <li>{@code NESTED-EXPLICIT-TENANT}: No active tenant transaction transitively invokes explicit
 *       {@code Db.read(int)}/{@code write(int)}/{@code readRepeatable(int)}/{@code writeSerializable(int)}/
 *       {@code savepoint(int)} (risking cross-tenant switch failure at runtime).</li>
 *   <li>{@code SPRING-TRANSACTIONAL}: When {@code forbidSpringTransactional} is enabled (default), no class or
 *       method carries {@code @Transactional}.</li>
 * </ol>
 */
@CacheableTask
public abstract class VerifyCallGraph extends DefaultTask {
    private static final String DB = "no/beint/vev/Db";
    private static final Set<String> READ_OPENERS = Set.of(
            "read", "tenantRead", "readNew", "tenantReadNew", "readRepeatable", "tenantReadRepeatable");
    private static final Set<String> WRITE_OPENERS = Set.of(
            "write", "tenantWrite", "writeSerializable", "tenantWriteSerializable", "writeIsolated", "tenantWriteIsolated");
    private static final Set<String> WRITE_JOINERS = Set.of(
            "write", "tenantWrite", "writeSerializable", "tenantWriteSerializable",
            "savepoint", "tenantSavepoint", "beforeCommit", "requireWriteTransaction");
    private static final Set<String> ISOLATION_RAISERS = Set.of(
            "readRepeatable", "tenantReadRepeatable", "writeSerializable", "tenantWriteSerializable");
    private static final Set<String> ISOLATED_CALLS = Set.of("writeIsolated", "tenantWriteIsolated");
    private static final Set<String> NEW_TX_OPENERS = Set.of("readNew", "tenantReadNew", "writeNew", "tenantWriteNew");
    private static final Set<String> AMBIENT_TENANT_DB = Set.of(
            "tenantRead", "tenantWrite", "tenantReadNew", "tenantWriteNew",
            "tenantReadRepeatable", "tenantWriteSerializable", "tenantWriteIsolated",
            "tenantSavepoint", "currentTenantId");
    private static final Set<String> EXPLICIT_TENANT_DB = Set.of(
            "read", "write", "readRepeatable", "writeSerializable", "savepoint");
    private static final Set<String> EXPLICIT_TENANT_LABELS = Set.of(
            "read(int)", "write(int)", "readRepeatable(int)", "writeSerializable(int)", "savepoint(int)");
    private static final Set<String> SCOPE_DESCRIPTORS = Set.of(
            "Lno/beint/vev/ReadScope;", "Lno/beint/vev/WriteScope;",
            "Lno/beint/vev/TenantReadScope;", "Lno/beint/vev/TenantWriteScope;");
    private static final Set<String> DEFERRED_OR_ASYNC_SINKS = Set.of(
            "afterCommit", "afterCompletion", "execute", "submit", "supplyAsync", "runAsync", "runConcurrently");
    private static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of(
            "Lorg/springframework/transaction/annotation/Transactional;",
            "Ljakarta/transaction/Transactional;");

    private record MethodKey(String owner, String name, String desc) {
        String shortName() { return owner.replace('/', '.') + "." + name; }
    }

    @InputFiles @IgnoreEmptyDirectories @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /** Package prefixes (dot or slash notation) to analyze. Empty analyzes all classes in {@code classes}. */
    @Input public abstract SetProperty<String> getPackagePrefixes();

    /** Methods ({@code pkg.Class.method}) that bind ambient tenant context for their lambda/body. */
    @Input public abstract SetProperty<String> getTenantBinders();

    /** Entry-point annotations ({@code pkg.Annotation}) that run without ambient tenant context. */
    @Input public abstract SetProperty<String> getEntryPointAnnotations();

    /** Fail when any scanned class or method carries Spring or Jakarta {@code @Transactional}. */
    @Input public abstract Property<Boolean> getForbidSpringTransactional();

    @OutputFile public abstract RegularFileProperty getReport();

    @TaskAction public void verify() throws IOException {
        List<String> prefixes = getPackagePrefixes().get().stream()
                .map(VerifyCallGraph::normalizePrefix)
                .filter(p -> !p.isEmpty())
                .toList();
        Set<String> tenantBinders = new HashSet<>();
        for (String binder : getTenantBinders().get()) {
            tenantBinders.add(normalizeMember(binder));
        }
        Set<String> entryPointAnnotations = new HashSet<>();
        for (String ann : getEntryPointAnnotations().get()) {
            entryPointAnnotations.add(normalizeDescriptor(ann));
        }
        boolean forbidTransactional = getForbidSpringTransactional().get();

        Map<MethodKey, Set<MethodKey>> normalEdges = new HashMap<>();
        Map<MethodKey, Set<MethodKey>> readTraversalEdges = new HashMap<>();
        Map<MethodKey, Set<MethodKey>> noTenantBindEdges = new HashMap<>();
        Map<MethodKey, Set<String>> directDbCalls = new HashMap<>();
        Map<MethodKey, Set<String>> directReadTraversalDbCalls = new HashMap<>();
        Map<MethodKey, Set<String>> directAmbientTenant = new HashMap<>();
        Map<MethodKey, Set<String>> directExplicitTenant = new HashMap<>();
        Map<MethodKey, List<String>> readLambdas = new LinkedHashMap<>();
        Map<MethodKey, List<String>> writeLambdas = new LinkedHashMap<>();
        Map<MethodKey, List<String>> tenantLambdas = new LinkedHashMap<>();
        Set<MethodKey> entryPointMethods = new LinkedHashSet<>();
        Map<String, Set<String>> subtypes = new HashMap<>();
        Set<MethodKey> allMethods = new HashSet<>();
        Set<String> escapedScopes = new TreeSet<>();
        Set<String> transactionalUses = new TreeSet<>();

        int classCount = 0;
        for (File dirFile : getClasses().getFiles()) {
            Path dir = dirFile.toPath();
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    var model = ClassFile.of().parse(Files.readAllBytes(file));
                    String owner = model.thisClass().asInternalName();
                    if (!matchesPrefix(owner, prefixes)) continue;
                    classCount++;

                    if (forbidTransactional) {
                        model.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(anns -> {
                            for (var ann : anns.annotations()) {
                                if (TRANSACTIONAL_ANNOTATIONS.contains(ann.className().stringValue())) {
                                    transactionalUses.add(owner.replace('/', '.'));
                                }
                            }
                        });
                    }

                    model.superclass().ifPresent(sc -> {
                        String sup = sc.asInternalName();
                        if (matchesPrefix(sup, prefixes)) subtypes.computeIfAbsent(sup, k -> new HashSet<>()).add(owner);
                    });
                    for (var iface : model.interfaces()) {
                        String iname = iface.asInternalName();
                        if (matchesPrefix(iname, prefixes)) subtypes.computeIfAbsent(iname, k -> new HashSet<>()).add(owner);
                    }

                    for (var method : model.methods()) {
                        String methodName = method.methodName().stringValue();
                        MethodKey mk = new MethodKey(owner, methodName, method.methodType().stringValue());
                        allMethods.add(mk);

                        method.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(anns -> {
                            for (var ann : anns.annotations()) {
                                String cname = ann.className().stringValue();
                                if (entryPointAnnotations.contains(cname)) {
                                    entryPointMethods.add(mk);
                                } else if (forbidTransactional && TRANSACTIONAL_ANNOTATIONS.contains(cname)) {
                                    transactionalUses.add(mk.shortName());
                                }
                            }
                        });

                        var codeOpt = method.code();
                        if (codeOpt.isEmpty()) continue;
                        List<CodeElement> elements = codeOpt.get().elementList();
                        boolean earlyReturnIfReadOnly = hasEarlyReturnWhenNotInWriteTransaction(elements);

                        MethodKey pendingLambda = null;
                        String pendingLambdaIndyDesc = null;
                        for (var el : elements) {
                            if (el instanceof InvokeDynamicInstruction indy) {
                                pendingLambdaIndyDesc = indy.typeSymbol().descriptorString();
                                for (var arg : indy.bootstrapArgs()) {
                                    if (arg instanceof DirectMethodHandleDesc dmh) {
                                        String own = dmh.owner().descriptorString();
                                        if (own.startsWith("L") && own.endsWith(";")) own = own.substring(1, own.length() - 1);
                                        pendingLambda = new MethodKey(own, dmh.methodName(), dmh.lookupDescriptor());
                                    }
                                }
                            } else if (el instanceof InvokeInstruction inv) {
                                String targetOwner = inv.owner().asInternalName();
                                String targetName = inv.name().stringValue();
                                String targetDesc = inv.type().stringValue();

                                if (pendingLambda != null && pendingLambdaIndyDesc != null && DEFERRED_OR_ASYNC_SINKS.contains(targetName)) {
                                    for (String scopeDesc : SCOPE_DESCRIPTORS) {
                                        if (pendingLambdaIndyDesc.contains(scopeDesc)) {
                                            escapedScopes.add(mk.shortName() + " passes captured Vev Scope to "
                                                    + targetOwner.replace('/', '.') + "." + targetName);
                                            break;
                                        }
                                    }
                                }

                                if (targetOwner.equals(DB)) {
                                    boolean isExplicitTenant = EXPLICIT_TENANT_DB.contains(targetName) && targetDesc.startsWith("(I");
                                    boolean isAmbientTenant = AMBIENT_TENANT_DB.contains(targetName);
                                    if (isAmbientTenant) {
                                        directAmbientTenant.computeIfAbsent(mk, k -> new HashSet<>()).add(targetName);
                                    }
                                    if (isExplicitTenant && !NEW_TX_OPENERS.contains(targetName)) {
                                        directExplicitTenant.computeIfAbsent(mk, k -> new HashSet<>()).add(targetName + "(int)");
                                    }

                                    if (NEW_TX_OPENERS.contains(targetName)) {
                                        // Independent transaction does not inherit enclosing read-only/isolation/tenant state.
                                        pendingLambda = null;
                                        pendingLambdaIndyDesc = null;
                                    } else {
                                        directDbCalls.computeIfAbsent(mk, k -> new HashSet<>()).add(targetName);
                                        if (!earlyReturnIfReadOnly) {
                                            directReadTraversalDbCalls.computeIfAbsent(mk, k -> new HashSet<>()).add(targetName);
                                        }
                                        if (pendingLambda != null) {
                                            if (READ_OPENERS.contains(targetName)) {
                                                readLambdas.computeIfAbsent(pendingLambda, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                            } else if (WRITE_OPENERS.contains(targetName)) {
                                                writeLambdas.computeIfAbsent(pendingLambda, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                            }
                                            if (isExplicitTenant || isAmbientTenant) {
                                                tenantLambdas.computeIfAbsent(pendingLambda, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                            }
                                            if (!targetName.equals("afterCommit") && !targetName.equals("afterCompletion")) {
                                                normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                                if (!earlyReturnIfReadOnly) {
                                                    readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                                }
                                                if (!isExplicitTenant) {
                                                    noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                                }
                                            }
                                            pendingLambda = null;
                                            pendingLambdaIndyDesc = null;
                                        }
                                    }
                                } else if (matchesPrefix(targetOwner, prefixes)) {
                                    boolean bindsTenant = tenantBinders.contains(targetOwner + "." + targetName);
                                    MethodKey targetMk = new MethodKey(targetOwner, targetName, targetDesc);
                                    normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    if (!earlyReturnIfReadOnly) {
                                        readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    }
                                    if (!bindsTenant) {
                                        noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    }
                                    if (pendingLambda != null && !targetName.equals("execute") && !targetName.equals("submit")
                                            && !targetName.equals("afterCommit") && !targetName.equals("afterCompletion")) {
                                        normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                        if (!earlyReturnIfReadOnly) {
                                            readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                        }
                                        if (!bindsTenant) {
                                            noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                        }
                                        pendingLambda = null;
                                        pendingLambdaIndyDesc = null;
                                    }
                                } else if (pendingLambda != null && (targetOwner.equals("java/lang/ScopedValue$Carrier") || targetOwner.startsWith("kotlin/"))) {
                                    normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                    if (!earlyReturnIfReadOnly) {
                                        readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                    }
                                    noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pendingLambda);
                                    pendingLambda = null;
                                    pendingLambdaIndyDesc = null;
                                }
                            }
                        }
                    }
                }
            }
        }

        Map<MethodKey, Set<MethodKey>> expandedEdges = expand(normalEdges, subtypes, allMethods);
        Map<MethodKey, Set<MethodKey>> expandedReadEdges = expand(readTraversalEdges, subtypes, allMethods);
        Map<MethodKey, Set<MethodKey>> expandedNoTenantEdges = expand(noTenantBindEdges, subtypes, allMethods);

        List<String> violations = new ArrayList<>();

        // 1. READ->WRITE
        Map<String, String> readToWrite = new TreeMap<>();
        for (var entry : readLambdas.entrySet()) {
            for (var hit : allReachableDbCalls(entry.getKey(), expandedReadEdges, directReadTraversalDbCalls, WRITE_JOINERS).entrySet()) {
                readToWrite.putIfAbsent(hit.getKey(), entry.getValue().getFirst() + " :: " + hit.getValue());
            }
        }
        readToWrite.forEach((target, trace) -> violations.add("READ->WRITE: " + target + "\n    " + trace));

        // 2. ISOLATION-ELEVATION
        Map<MethodKey, List<String>> rcLambdas = new LinkedHashMap<>();
        readLambdas.forEach((k, v) -> { if (v.stream().anyMatch(s -> !s.contains("Repeatable"))) rcLambdas.put(k, v); });
        writeLambdas.forEach((k, v) -> { if (v.stream().anyMatch(s -> !s.contains("Serializable"))) rcLambdas.putIfAbsent(k, v); });
        Map<String, String> isoElevations = new TreeMap<>();
        for (var entry : rcLambdas.entrySet()) {
            for (var hit : allReachableDbCalls(entry.getKey(), expandedEdges, directDbCalls, ISOLATION_RAISERS).entrySet()) {
                isoElevations.putIfAbsent(hit.getKey(), entry.getValue().getFirst() + " :: " + hit.getValue());
            }
        }
        isoElevations.forEach((target, trace) -> violations.add("ISOLATION-ELEVATION: " + target + "\n    " + trace));

        // 3. NESTED-ISOLATED
        Map<MethodKey, List<String>> allTxLambdas = new LinkedHashMap<>(readLambdas);
        allTxLambdas.putAll(writeLambdas);
        Map<String, String> nestedIsolated = new TreeMap<>();
        for (var entry : allTxLambdas.entrySet()) {
            for (var hit : allReachableDbCalls(entry.getKey(), expandedEdges, directDbCalls, ISOLATED_CALLS).entrySet()) {
                nestedIsolated.putIfAbsent(hit.getKey(), entry.getValue().getFirst() + " :: " + hit.getValue());
            }
        }
        nestedIsolated.forEach((target, trace) -> violations.add("NESTED-ISOLATED: " + target + "\n    " + trace));

        // 4. ESCAPED-SCOPE
        escapedScopes.forEach(s -> violations.add("ESCAPED-SCOPE: " + s));

        // 5. SCHEDULED-NO-TENANT
        Map<String, String> scheduledNoTenant = new TreeMap<>();
        for (MethodKey entry : entryPointMethods) {
            for (var hit : allReachableDbCalls(entry, expandedNoTenantEdges, directAmbientTenant, AMBIENT_TENANT_DB).entrySet()) {
                scheduledNoTenant.putIfAbsent(entry.shortName() + " -> " + hit.getKey(), hit.getValue());
            }
        }
        scheduledNoTenant.forEach((target, trace) -> violations.add("SCHEDULED-NO-TENANT: " + target + "\n    " + trace));

        // 6. NESTED-EXPLICIT-TENANT
        Map<String, String> nestedExplicitTenant = new TreeMap<>();
        for (var entry : tenantLambdas.entrySet()) {
            for (var hit : allReachableDbCalls(entry.getKey(), expandedEdges, directExplicitTenant, EXPLICIT_TENANT_LABELS).entrySet()) {
                nestedExplicitTenant.putIfAbsent(hit.getKey(), entry.getValue().getFirst() + " :: " + hit.getValue());
            }
        }
        nestedExplicitTenant.forEach((target, trace) -> violations.add("NESTED-EXPLICIT-TENANT: " + target + "\n    " + trace));

        // 7. SPRING-TRANSACTIONAL
        transactionalUses.forEach(s -> violations.add("SPRING-TRANSACTIONAL: " + s + " is annotated with @Transactional; use Vev Db instead"));

        Path reportPath = getReport().get().getAsFile().toPath();
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath,
                "classes=" + classCount + "\nmethods=" + allMethods.size() + "\nviolations=" + violations.size() + "\n"
                        + String.join("\n", violations) + "\n",
                StandardCharsets.UTF_8);

        if (!violations.isEmpty()) {
            var message = new StringBuilder("Vev call-graph transaction violations found (" + violations.size() + "):\n");
            for (String v : violations) message.append("  ").append(v).append("\n");
            message.append("Fix the transaction boundary (use read for pure reads, writeNew/readNew for independent tx, "
                    + "bind tenant explicitly in @Scheduled jobs, or move readRepeatable/writeSerializable to the outermost boundary).");
            throw new GradleException(message.toString());
        }
    }

    /**
     * Soundly recognizes methods that start with {@code if (!db.inWriteTransaction()) return} before performing
     * any other call: inside a read-only transaction {@code inWriteTransaction()} is always false, so no code
     * after the guard can execute.
     */
    private static boolean hasEarlyReturnWhenNotInWriteTransaction(List<CodeElement> elements) {
        for (int i = 0; i < elements.size(); i++) {
            CodeElement el = elements.get(i);
            if (el instanceof InvokeInstruction inv) {
                if (inv.owner().asInternalName().equals(DB) && inv.name().stringValue().equals("inWriteTransaction")) {
                    for (int j = i + 1; j < Math.min(elements.size(), i + 6); j++) {
                        if (elements.get(j) instanceof BranchInstruction branch && branch.opcode() == Opcode.IFNE) {
                            for (int k = j + 1; k < Math.min(elements.size(), j + 5); k++) {
                                if (elements.get(k) instanceof ReturnInstruction) return true;
                                if (elements.get(k) instanceof InvokeInstruction) return false;
                            }
                            return false;
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.trim().replace('.', '/');
        if (normalized.isEmpty()) return "";
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static String normalizeMember(String member) {
        String trimmed = member.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot < 0) return trimmed;
        return trimmed.substring(0, lastDot).replace('.', '/') + "." + trimmed.substring(lastDot + 1);
    }

    private static String normalizeDescriptor(String annotation) {
        String trimmed = annotation.trim();
        if (trimmed.startsWith("L") && trimmed.endsWith(";")) return trimmed;
        return "L" + trimmed.replace('.', '/') + ";";
    }

    private static boolean matchesPrefix(String internalName, List<String> prefixes) {
        if (prefixes.isEmpty()) return true;
        for (String prefix : prefixes) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Map<MethodKey, Set<MethodKey>> expand(
            Map<MethodKey, Set<MethodKey>> edges,
            Map<String, Set<String>> subtypes,
            Set<MethodKey> allMethods
    ) {
        Map<MethodKey, Set<MethodKey>> out = new HashMap<>();
        for (var e : edges.entrySet()) {
            Set<MethodKey> targets = new HashSet<>(e.getValue());
            for (MethodKey t : e.getValue()) {
                Deque<String> q = new ArrayDeque<>(subtypes.getOrDefault(t.owner, Set.of()));
                Set<String> seen = new HashSet<>();
                while (!q.isEmpty()) {
                    String sub = q.poll();
                    if (!seen.add(sub)) continue;
                    MethodKey subMk = new MethodKey(sub, t.name, t.desc);
                    if (allMethods.contains(subMk)) targets.add(subMk);
                    q.addAll(subtypes.getOrDefault(sub, Set.of()));
                }
            }
            out.put(e.getKey(), targets);
        }
        return out;
    }

    private static Map<String, String> allReachableDbCalls(
            MethodKey start,
            Map<MethodKey, Set<MethodKey>> edges,
            Map<MethodKey, Set<String>> directDbCalls,
            Set<String> forbidden
    ) {
        Map<String, String> found = new LinkedHashMap<>();
        Deque<MethodKey> q = new ArrayDeque<>();
        Map<MethodKey, MethodKey> parent = new HashMap<>();
        q.add(start);
        parent.put(start, null);
        while (!q.isEmpty()) {
            MethodKey cur = q.poll();
            Set<String> db = directDbCalls.getOrDefault(cur, Set.of());
            if (!Collections.disjoint(db, forbidden)) {
                Set<String> inter = new TreeSet<>(db);
                inter.retainAll(forbidden);
                List<String> path = new ArrayList<>();
                for (MethodKey n = cur; n != null; n = parent.get(n)) path.add(n.shortName());
                Collections.reverse(path);
                found.putIfAbsent(cur.shortName() + " -> Db." + inter, String.join(" -> ", path));
            }
            for (MethodKey next : edges.getOrDefault(cur, Set.of())) {
                if (!parent.containsKey(next)) {
                    parent.put(next, cur);
                    q.add(next);
                }
            }
        }
        return found;
    }
}
