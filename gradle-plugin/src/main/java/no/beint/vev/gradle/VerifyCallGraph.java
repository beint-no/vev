package no.beint.vev.gradle;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.AccessFlag;
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
 *   <li>{@code EXTERNAL-IO-IN-TX}: When {@code forbidExternalIoInTransaction} is enabled (default), no active
 *       transaction ({@code read*}/{@code write*}/{@code readNew}/{@code writeNew}) transitively invokes outbound
 *       HTTP, GraphQL, socket/TLS, DNS, or mail I/O (Spring {@code RestClient}/{@code RestTemplate}/{@code WebClient}/
 *       {@code @HttpExchange}, JDK {@code HttpClient}/{@code URL.openConnection}/{@code HttpURLConnection}/
 *       {@code Socket}/{@code SSLSocket}/{@code SSLSocketFactory}, OkHttp, Apache HttpClient, Apollo GraphQL,
 *       dnsjava {@code Lookup}, Jakarta Mail) or any method configured in {@code forbiddenInTransaction}.</li>
 * </ol>
 */
@CacheableTask
public abstract class VerifyCallGraph extends DefaultTask {
    private static final String DB = "no/beint/vev/Db";
    private static final Set<String> READ_OPENERS = Set.of(
            "read", "tenantRead", "readNew", "tenantReadNew", "readRepeatable", "tenantReadRepeatable");
    private static final Set<String> WRITE_OPENERS = Set.of(
            "write", "tenantWrite", "writeNew", "tenantWriteNew", "writeSerializable", "tenantWriteSerializable",
            "writeIsolated", "tenantWriteIsolated");
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
    private static final String ASYNC_ANNOTATION = "Lorg/springframework/scheduling/annotation/Async;";
    private static final String EVENT_LISTENER_ANNOTATION = "Lorg/springframework/context/event/EventListener;";
    private static final String TRANSACTIONAL_EVENT_LISTENER_ANNOTATION =
            "Lorg/springframework/transaction/event/TransactionalEventListener;";
    private static final Set<String> APPLICATION_EVENT_PUBLISHERS = Set.of(
            "org/springframework/context/ApplicationEventPublisher",
            "org/springframework/context/ApplicationContext",
            "org/springframework/context/ConfigurableApplicationContext",
            "org/springframework/web/context/WebApplicationContext");
    private static final Set<String> SYNC_JAVA_UTIL_LAMBDA_METHODS = Set.of(
            "forEach", "removeIf", "compute", "computeIfAbsent", "computeIfPresent", "merge",
            "ifPresent", "ifPresentOrElse");
    private static final String SPRING_HTTP_EXCHANGE_PREFIX = "Lorg/springframework/web/service/annotation/";
    private static final Map<String, Set<String>> BUILTIN_EXTERNAL_IO_SINKS = Map.ofEntries(
            Map.entry("org/springframework/web/client/RestClient$ResponseSpec", Set.of("body", "toEntity", "toBodilessEntity")),
            Map.entry("org/springframework/web/client/RestClient$RequestHeadersSpec", Set.of("exchange")),
            Map.entry("org/springframework/web/client/RestClient$RequestBodySpec", Set.of("exchange")),
            Map.entry("org/springframework/web/client/RestClient$RequestHeadersUriSpec", Set.of("exchange")),
            Map.entry("org/springframework/web/client/RestClient$RequestBodyUriSpec", Set.of("exchange")),
            Map.entry("org/springframework/web/client/RestTemplate", Set.of(
                    "exchange", "execute", "getForObject", "getForEntity", "postForObject", "postForEntity",
                    "postForLocation", "put", "delete", "patchForObject", "headForHeaders", "optionsForAllow")),
            Map.entry("org/springframework/web/reactive/function/client/WebClient$RequestHeadersSpec",
                    Set.of("retrieve", "exchange", "exchangeToMono", "exchangeToFlux")),
            Map.entry("java/net/http/HttpClient", Set.of("send", "sendAsync")),
            Map.entry("java/net/URL", Set.of("openConnection", "openStream")),
            Map.entry("java/net/HttpURLConnection", Set.of("connect", "getResponseCode", "getInputStream", "getOutputStream")),
            Map.entry("javax/net/ssl/HttpsURLConnection", Set.of("connect", "getResponseCode", "getInputStream", "getOutputStream")),
            Map.entry("okhttp3/Call", Set.of("execute", "enqueue")),
            Map.entry("org/apache/hc/client5/http/classic/HttpClient", Set.of("execute")),
            Map.entry("org/apache/http/client/HttpClient", Set.of("execute")),
            Map.entry("jakarta/mail/Transport", Set.of("send", "sendMessage")),
            Map.entry("com/apollographql/apollo/ApolloCall", Set.of("execute", "executeV3")),
            Map.entry("java/net/Socket", Set.of("connect")),
            Map.entry("javax/net/ssl/SSLSocket", Set.of("startHandshake")),
            Map.entry("javax/net/ssl/SSLSocketFactory", Set.of("createSocket")),
            Map.entry("org/xbill/DNS/Lookup", Set.of("run"))
    );

    private record MethodKey(String owner, String name, String desc) {
        String shortName() { return owner.replace('/', '.') + "." + name; }
    }

    private record PendingLambda(MethodKey target, String indyDesc) {}
    private record PublishedEvent(MethodKey caller, String eventType, boolean earlyReturnIfReadOnly) {}
    private record RunBlockingCall(MethodKey caller, String suspendLambdaOwner, boolean earlyReturnIfReadOnly) {}

    @InputFiles @IgnoreEmptyDirectories @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /** Package prefixes (dot or slash notation) to analyze. Empty analyzes all classes in {@code classes}. */
    @org.gradle.api.tasks.Optional @Input public abstract SetProperty<String> getPackagePrefixes();

    /** Methods ({@code pkg.Class.method}) that bind ambient tenant context for their lambda/body. */
    @org.gradle.api.tasks.Optional @Input public abstract SetProperty<String> getTenantBinders();

    /** Entry-point annotations ({@code pkg.Annotation}) that run without ambient tenant context. */
    @org.gradle.api.tasks.Optional @Input public abstract SetProperty<String> getEntryPointAnnotations();

    /** Fail when any scanned class or method carries Spring or Jakarta {@code @Transactional}. */
    @org.gradle.api.tasks.Optional @Input public abstract Property<Boolean> getForbidSpringTransactional();

    /** Fail when any Vev transaction transitively invokes outbound HTTP or mail I/O. */
    @org.gradle.api.tasks.Optional @Input public abstract Property<Boolean> getForbidExternalIoInTransaction();

    /** Additional methods ({@code pkg.Class.method}) forbidden inside any Vev transaction. */
    @org.gradle.api.tasks.Optional @Input public abstract SetProperty<String> getForbiddenInTransaction();

    @OutputFile public abstract RegularFileProperty getReport();

    @TaskAction public void verify() throws IOException {
        List<String> prefixes = getPackagePrefixes().getOrElse(Set.of()).stream()
                .map(VerifyCallGraph::normalizePrefix)
                .filter(p -> !p.isEmpty())
                .toList();
        Set<String> tenantBinders = new HashSet<>();
        for (String binder : getTenantBinders().getOrElse(Set.of())) {
            tenantBinders.add(normalizeMember(binder));
        }
        Set<String> entryPointAnnotations = new HashSet<>();
        for (String ann : getEntryPointAnnotations().getOrElse(Set.of("org.springframework.scheduling.annotation.Scheduled"))) {
            entryPointAnnotations.add(normalizeDescriptor(ann));
        }
        boolean forbidTransactional = getForbidSpringTransactional().getOrElse(true);
        boolean forbidExternalIo = getForbidExternalIoInTransaction().getOrElse(true);
        Set<String> customForbiddenSinks = new HashSet<>();
        for (String sink : getForbiddenInTransaction().getOrElse(Set.of())) {
            customForbiddenSinks.add(normalizeMember(sink));
        }

        Map<MethodKey, Set<MethodKey>> normalEdges = new HashMap<>();
        Map<MethodKey, Set<MethodKey>> ioEdges = new HashMap<>();
        Map<MethodKey, Set<MethodKey>> readTraversalEdges = new HashMap<>();
        Map<MethodKey, Set<MethodKey>> noTenantBindEdges = new HashMap<>();
        Map<MethodKey, Set<String>> directDbCalls = new HashMap<>();
        Map<MethodKey, Set<String>> directReadTraversalDbCalls = new HashMap<>();
        Map<MethodKey, Set<String>> directAmbientTenant = new HashMap<>();
        Map<MethodKey, Set<String>> directExplicitTenant = new HashMap<>();
        Map<MethodKey, Set<String>> directExternalIo = new HashMap<>();
        Map<MethodKey, List<String>> readLambdas = new LinkedHashMap<>();
        Map<MethodKey, List<String>> writeLambdas = new LinkedHashMap<>();
        Map<MethodKey, List<String>> ioTxLambdas = new LinkedHashMap<>();
        Map<MethodKey, List<String>> tenantLambdas = new LinkedHashMap<>();
        Set<MethodKey> entryPointMethods = new LinkedHashSet<>();
        Set<MethodKey> asyncMethods = new HashSet<>();
        Set<MethodKey> transactionalEventListeners = new HashSet<>();
        Map<String, Set<MethodKey>> eventListeners = new HashMap<>();
        List<PublishedEvent> publishedEvents = new ArrayList<>();
        List<RunBlockingCall> runBlockingCalls = new ArrayList<>();
        Map<String, Set<String>> subtypes = new HashMap<>();
        Map<String, Set<String>> supertypes = new HashMap<>();
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

                    boolean[] classFlags = new boolean[1]; // [0] = class @Async
                    model.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(anns -> {
                        for (var ann : anns.annotations()) {
                            String cname = ann.className().stringValue();
                            if (forbidTransactional && TRANSACTIONAL_ANNOTATIONS.contains(cname)) {
                                transactionalUses.add(owner.replace('/', '.'));
                            }
                            if (ASYNC_ANNOTATION.equals(cname)) {
                                classFlags[0] = true;
                            }
                        }
                    });

                    model.superclass().ifPresent(sc -> {
                        String sup = sc.asInternalName();
                        if (!sup.equals("java/lang/Object")) {
                            supertypes.computeIfAbsent(owner, k -> new HashSet<>()).add(sup);
                        }
                        if (matchesPrefix(sup, prefixes)) subtypes.computeIfAbsent(sup, k -> new HashSet<>()).add(owner);
                    });
                    for (var iface : model.interfaces()) {
                        String iname = iface.asInternalName();
                        supertypes.computeIfAbsent(owner, k -> new HashSet<>()).add(iname);
                        if (matchesPrefix(iname, prefixes)) subtypes.computeIfAbsent(iname, k -> new HashSet<>()).add(owner);
                    }

                    for (var method : model.methods()) {
                        String methodName = method.methodName().stringValue();
                        MethodKey mk = new MethodKey(owner, methodName, method.methodType().stringValue());
                        allMethods.add(mk);
                        if (classFlags[0]) asyncMethods.add(mk);

                        method.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(anns -> {
                            for (var ann : anns.annotations()) {
                                String cname = ann.className().stringValue();
                                if (entryPointAnnotations.contains(cname)) {
                                    entryPointMethods.add(mk);
                                } else if (forbidTransactional && TRANSACTIONAL_ANNOTATIONS.contains(cname)) {
                                    transactionalUses.add(mk.shortName());
                                } else if (ASYNC_ANNOTATION.equals(cname)) {
                                    asyncMethods.add(mk);
                                } else if (TRANSACTIONAL_EVENT_LISTENER_ANNOTATION.equals(cname)) {
                                    transactionalEventListeners.add(mk);
                                } else if (EVENT_LISTENER_ANNOTATION.equals(cname)) {
                                    for (String eventType : extractEventListenerTypes(method.methodTypeSymbol(), ann.elements())) {
                                        eventListeners.computeIfAbsent(eventType, k -> new HashSet<>()).add(mk);
                                    }
                                } else if (forbidExternalIo && cname.startsWith(SPRING_HTTP_EXCHANGE_PREFIX) && cname.endsWith("Exchange;")) {
                                    directExternalIo.computeIfAbsent(mk, k -> new HashSet<>()).add("@HttpExchange(" + mk.shortName() + ")");
                                }
                            }
                        });

                        var codeOpt = method.code();
                        if (codeOpt.isEmpty()) continue;
                        List<CodeElement> elements = codeOpt.get().elementList();
                        boolean earlyReturnIfReadOnly = hasEarlyReturnWhenNotInWriteTransaction(elements);

                        Map<Integer, String> localTypes = initialLocalTypes(owner, method.flags().has(AccessFlag.STATIC), method.methodTypeSymbol());
                        String lastRefType = null;
                        List<PendingLambda> pendingLambdas = new ArrayList<>();
                        String pendingSuspendLambdaOwner = null;
                        for (int idx = 0; idx < elements.size(); idx++) {
                            var el = elements.get(idx);
                            if (el instanceof LocalVariable lv) {
                                String lvType = descriptorToInternalName(lv.type().stringValue());
                                if (lvType != null) localTypes.putIfAbsent(lv.slot(), lvType);
                                continue;
                            }
                            if (el instanceof LabelTarget) {
                                lastRefType = null;
                                continue;
                            }
                            if (el instanceof NewObjectInstruction newObj) {
                                lastRefType = newObj.className().asInternalName();
                                continue;
                            }
                            if (el instanceof TypeCheckInstruction tc) {
                                lastRefType = tc.opcode() == Opcode.CHECKCAST ? tc.type().asInternalName() : null;
                                continue;
                            }
                            if (el instanceof FieldInstruction fi) {
                                Opcode op = fi.opcode();
                                if (op == Opcode.GETFIELD || op == Opcode.GETSTATIC) {
                                    lastRefType = descriptorToInternalName(fi.type().stringValue());
                                }
                                continue;
                            }
                            if (el instanceof LoadInstruction li) {
                                if (li.opcode() == Opcode.ALOAD || li.opcode() == Opcode.ALOAD_0 || li.opcode() == Opcode.ALOAD_1
                                        || li.opcode() == Opcode.ALOAD_2 || li.opcode() == Opcode.ALOAD_3 || li.opcode() == Opcode.ALOAD_W) {
                                    lastRefType = localTypes.get(li.slot());
                                }
                                continue;
                            }
                            if (el instanceof StoreInstruction si) {
                                if (si.opcode() == Opcode.ASTORE || si.opcode() == Opcode.ASTORE_0 || si.opcode() == Opcode.ASTORE_1
                                        || si.opcode() == Opcode.ASTORE_2 || si.opcode() == Opcode.ASTORE_3 || si.opcode() == Opcode.ASTORE_W) {
                                    if (lastRefType != null) localTypes.put(si.slot(), lastRefType);
                                    else localTypes.remove(si.slot());
                                }
                                continue;
                            }
                            if (el instanceof ConstantInstruction ci) {
                                if (ci.opcode() == Opcode.ACONST_NULL) {
                                    lastRefType = null;
                                } else if (!isNextKotlinNullCheck(elements, idx)) {
                                    lastRefType = null;
                                }
                                continue;
                            }
                            if (el instanceof InvokeDynamicInstruction indy) {
                                String indyDesc = indy.typeSymbol().descriptorString();
                                for (var arg : indy.bootstrapArgs()) {
                                    if (arg instanceof DirectMethodHandleDesc dmh) {
                                        String own = dmh.owner().descriptorString();
                                        if (own.startsWith("L") && own.endsWith(";")) own = own.substring(1, own.length() - 1);
                                        pendingLambdas.add(new PendingLambda(new MethodKey(own, dmh.methodName(), dmh.lookupDescriptor()), indyDesc));
                                    }
                                }
                                lastRefType = returnInternalName(indyDesc);
                            } else if (el instanceof InvokeInstruction inv) {
                                String targetOwner = inv.owner().asInternalName();
                                String targetName = inv.name().stringValue();
                                String targetDesc = inv.type().stringValue();

                                if (APPLICATION_EVENT_PUBLISHERS.contains(targetOwner) && targetName.equals("publishEvent")) {
                                    if (lastRefType != null && !lastRefType.equals("java/lang/Object")) {
                                        publishedEvents.add(new PublishedEvent(mk, lastRefType, earlyReturnIfReadOnly));
                                    }
                                }
                                if (targetOwner.equals("kotlin/jvm/internal/Intrinsics")
                                        && targetName.startsWith("checkNotNull")
                                        && targetDesc.endsWith(")V")) {
                                    // Preserves the checked reference on top of the operand stack.
                                } else if (targetName.equals("<init>")) {
                                    lastRefType = targetOwner;
                                } else {
                                    lastRefType = returnInternalName(targetDesc);
                                }

                                if (forbidExternalIo && BUILTIN_EXTERNAL_IO_SINKS.getOrDefault(targetOwner, Set.of()).contains(targetName)) {
                                    String simpleOwner = targetOwner.substring(targetOwner.lastIndexOf('/') + 1);
                                    directExternalIo.computeIfAbsent(mk, k -> new HashSet<>()).add(simpleOwner + "." + targetName);
                                }
                                if (customForbiddenSinks.contains(targetOwner + "." + targetName)) {
                                    directExternalIo.computeIfAbsent(mk, k -> new HashSet<>()).add(targetOwner.replace('/', '.') + "." + targetName);
                                }

                                if (!pendingLambdas.isEmpty() && DEFERRED_OR_ASYNC_SINKS.contains(targetName)) {
                                    for (PendingLambda pl : pendingLambdas) {
                                        for (String scopeDesc : SCOPE_DESCRIPTORS) {
                                            if (pl.indyDesc.contains(scopeDesc)) {
                                                escapedScopes.add(mk.shortName() + " passes captured Vev Scope to "
                                                        + targetOwner.replace('/', '.') + "." + targetName);
                                                break;
                                            }
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
                                        // Independent transaction does not inherit enclosing read-only/isolation/tenant state,
                                        // but still holds an open DB connection while running.
                                        for (PendingLambda pl : pendingLambdas) {
                                            ioTxLambdas.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                            ioEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                        }
                                        pendingLambdas.clear();
                                    } else {
                                        directDbCalls.computeIfAbsent(mk, k -> new HashSet<>()).add(targetName);
                                        if (!earlyReturnIfReadOnly) {
                                            directReadTraversalDbCalls.computeIfAbsent(mk, k -> new HashSet<>()).add(targetName);
                                        }
                                        if (!pendingLambdas.isEmpty()) {
                                            for (PendingLambda pl : pendingLambdas) {
                                                if (READ_OPENERS.contains(targetName)) {
                                                    readLambdas.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                                    ioTxLambdas.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                                } else if (WRITE_OPENERS.contains(targetName)) {
                                                    writeLambdas.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                                    ioTxLambdas.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                                }
                                                if (isExplicitTenant || isAmbientTenant) {
                                                    tenantLambdas.computeIfAbsent(pl.target, k -> new ArrayList<>()).add(mk.shortName() + " [" + targetName + "]");
                                                }
                                                if (!targetName.equals("afterCommit") && !targetName.equals("afterCompletion")) {
                                                    normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                                    ioEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                                    if (!earlyReturnIfReadOnly) {
                                                        readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                                    }
                                                    if (!isExplicitTenant) {
                                                        noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                                    }
                                                }
                                            }
                                            pendingLambdas.clear();
                                        }
                                    }
                                } else if (matchesPrefix(targetOwner, prefixes)) {
                                    boolean bindsTenant = tenantBinders.contains(targetOwner + "." + targetName);
                                    MethodKey targetMk = new MethodKey(targetOwner, targetName, targetDesc);
                                    normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    ioEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    if (!earlyReturnIfReadOnly) {
                                        readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    }
                                    if (!bindsTenant) {
                                        noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(targetMk);
                                    }
                                    if (targetName.equals("<init>")) {
                                        pendingSuspendLambdaOwner = targetOwner;
                                    }
                                    if (!pendingLambdas.isEmpty() && !targetName.equals("execute") && !targetName.equals("submit")
                                            && !targetName.equals("afterCommit") && !targetName.equals("afterCompletion")) {
                                        for (PendingLambda pl : pendingLambdas) {
                                            normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                            ioEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                            if (!earlyReturnIfReadOnly) {
                                                readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                            }
                                            if (!bindsTenant) {
                                                noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                            }
                                        }
                                        pendingLambdas.clear();
                                    }
                                } else if (isSynchronousLambdaInvoker(targetOwner, targetName)) {
                                    for (PendingLambda pl : pendingLambdas) {
                                        normalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                        ioEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                        if (!earlyReturnIfReadOnly) {
                                            readTraversalEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                        }
                                        noTenantBindEdges.computeIfAbsent(mk, k -> new HashSet<>()).add(pl.target);
                                    }
                                    pendingLambdas.clear();
                                    if (targetOwner.equals("kotlinx/coroutines/BuildersKt") && pendingSuspendLambdaOwner != null) {
                                        runBlockingCalls.add(new RunBlockingCall(mk, pendingSuspendLambdaOwner, earlyReturnIfReadOnly));
                                        pendingSuspendLambdaOwner = null;
                                    }
                                } else if (targetOwner.startsWith("kotlinx/coroutines/")) {
                                    pendingSuspendLambdaOwner = null;
                                    pendingLambdas.clear();
                                } else if (targetOwner.startsWith("java/util/concurrent/") && DEFERRED_OR_ASYNC_SINKS.contains(targetName)) {
                                    pendingLambdas.clear();
                                }
                            }
                        }
                    }
                }
            }
        }

        for (RunBlockingCall call : runBlockingCalls) {
            MethodKey invokeSuspend = new MethodKey(call.suspendLambdaOwner, "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;");
            if (allMethods.contains(invokeSuspend)) {
                normalEdges.computeIfAbsent(call.caller, k -> new HashSet<>()).add(invokeSuspend);
                ioEdges.computeIfAbsent(call.caller, k -> new HashSet<>()).add(invokeSuspend);
                if (!call.earlyReturnIfReadOnly) {
                    readTraversalEdges.computeIfAbsent(call.caller, k -> new HashSet<>()).add(invokeSuspend);
                }
                noTenantBindEdges.computeIfAbsent(call.caller, k -> new HashSet<>()).add(invokeSuspend);
            }
        }

        for (PublishedEvent published : publishedEvents) {
            for (MethodKey listener : matchingEventListeners(
                    published.eventType, eventListeners, supertypes, subtypes, prefixes, asyncMethods, transactionalEventListeners)) {
                normalEdges.computeIfAbsent(published.caller, k -> new HashSet<>()).add(listener);
                ioEdges.computeIfAbsent(published.caller, k -> new HashSet<>()).add(listener);
                if (!published.earlyReturnIfReadOnly) {
                    readTraversalEdges.computeIfAbsent(published.caller, k -> new HashSet<>()).add(listener);
                }
                noTenantBindEdges.computeIfAbsent(published.caller, k -> new HashSet<>()).add(listener);
            }
        }

        Map<MethodKey, Set<MethodKey>> expandedEdges = expand(normalEdges, subtypes, allMethods, asyncMethods);
        Map<MethodKey, Set<MethodKey>> expandedIoEdges = expand(ioEdges, subtypes, allMethods, asyncMethods);
        Map<MethodKey, Set<MethodKey>> expandedReadEdges = expand(readTraversalEdges, subtypes, allMethods, asyncMethods);
        Map<MethodKey, Set<MethodKey>> expandedNoTenantEdges = expand(noTenantBindEdges, subtypes, allMethods, asyncMethods);

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

        // 8. EXTERNAL-IO-IN-TX
        if (!directExternalIo.isEmpty()) {
            Map<String, String> externalIoInTx = new TreeMap<>();
            for (var entry : ioTxLambdas.entrySet()) {
                for (var hit : allReachableSinks(entry.getKey(), expandedIoEdges, directExternalIo).entrySet()) {
                    externalIoInTx.putIfAbsent(entry.getValue().getFirst() + " -> " + hit.getKey(), hit.getValue());
                }
            }
            externalIoInTx.forEach((target, trace) -> violations.add("EXTERNAL-IO-IN-TX: " + target + "\n    " + trace));
        }

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
                    + "move external HTTP/mail calls outside transactions or into afterCommit, "
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

    private static boolean isSynchronousLambdaInvoker(String targetOwner, String targetName) {
        if (targetOwner.equals("java/lang/ScopedValue$Carrier") || targetOwner.startsWith("kotlin/")) return true;
        if (targetOwner.equals("kotlinx/coroutines/BuildersKt")
                && (targetName.equals("runBlocking") || targetName.equals("runBlocking$default"))) return true;
        if (targetOwner.equals("java/lang/Iterable") && targetName.equals("forEach")) return true;
        return targetOwner.startsWith("java/util/")
                && !targetOwner.startsWith("java/util/concurrent/")
                && SYNC_JAVA_UTIL_LAMBDA_METHODS.contains(targetName);
    }

    private static Map<Integer, String> initialLocalTypes(String owner, boolean isStatic, java.lang.constant.MethodTypeDesc type) {
        Map<Integer, String> locals = new HashMap<>();
        int slot = 0;
        if (!isStatic) {
            locals.put(0, owner);
            slot = 1;
        }
        for (ClassDesc param : type.parameterList()) {
            String desc = param.descriptorString();
            String internal = descriptorToInternalName(desc);
            if (internal != null) locals.put(slot, internal);
            slot += (desc.equals("J") || desc.equals("D")) ? 2 : 1;
        }
        return locals;
    }

    private static String descriptorToInternalName(String desc) {
        if (desc != null && desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1);
        }
        return null;
    }

    private static String returnInternalName(String methodDesc) {
        int close = methodDesc.lastIndexOf(')');
        if (close < 0 || close + 1 >= methodDesc.length()) return null;
        return descriptorToInternalName(methodDesc.substring(close + 1));
    }

    private static boolean isNextKotlinNullCheck(List<CodeElement> elements, int idx) {
        for (int j = idx + 1; j < Math.min(elements.size(), idx + 3); j++) {
            if (elements.get(j) instanceof InvokeInstruction inv) {
                return inv.owner().asInternalName().equals("kotlin/jvm/internal/Intrinsics")
                        && inv.name().stringValue().startsWith("checkNotNull");
            }
        }
        return false;
    }

    private static Set<String> extractEventListenerTypes(
            java.lang.constant.MethodTypeDesc methodType,
            List<AnnotationElement> elements) {
        Set<String> types = new HashSet<>();
        for (AnnotationElement elem : elements) {
            String name = elem.name().stringValue();
            if ((name.equals("value") || name.equals("classes")) && elem.value() instanceof AnnotationValue.OfArray arr) {
                for (AnnotationValue v : arr.values()) {
                    if (v instanceof AnnotationValue.OfClass cls) {
                        String internal = descriptorToInternalName(cls.className().stringValue());
                        if (internal != null && !internal.equals("java/lang/Object")) types.add(internal);
                    }
                }
            }
        }
        if (types.isEmpty() && methodType.parameterCount() == 1) {
            String param = descriptorToInternalName(methodType.parameterType(0).descriptorString());
            if (param != null && !param.equals("java/lang/Object")) types.add(param);
        }
        return types;
    }

    private static Set<MethodKey> matchingEventListeners(
            String eventType,
            Map<String, Set<MethodKey>> eventListeners,
            Map<String, Set<String>> supertypes,
            Map<String, Set<String>> subtypes,
            List<String> prefixes,
            Set<MethodKey> asyncMethods,
            Set<MethodKey> transactionalEventListeners) {
        Set<String> matchedTypes = new HashSet<>();
        Deque<String> up = new ArrayDeque<>();
        up.add(eventType);
        while (!up.isEmpty()) {
            String cur = up.poll();
            if (!matchedTypes.add(cur)) continue;
            for (String sup : supertypes.getOrDefault(cur, Set.of())) {
                if (!sup.equals("java/lang/Object")) up.add(sup);
            }
        }
        if (matchesPrefix(eventType, prefixes)) {
            Deque<String> down = new ArrayDeque<>(subtypes.getOrDefault(eventType, Set.of()));
            while (!down.isEmpty()) {
                String sub = down.poll();
                if (!matchedTypes.add(sub)) continue;
                down.addAll(subtypes.getOrDefault(sub, Set.of()));
            }
        }
        Set<MethodKey> listeners = new HashSet<>();
        for (String type : matchedTypes) {
            for (MethodKey listener : eventListeners.getOrDefault(type, Set.of())) {
                if (!asyncMethods.contains(listener) && !transactionalEventListeners.contains(listener)) {
                    listeners.add(listener);
                }
            }
        }
        return listeners;
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
            Set<MethodKey> allMethods,
            Set<MethodKey> asyncMethods
    ) {
        Map<MethodKey, Set<MethodKey>> out = new HashMap<>();
        for (var e : edges.entrySet()) {
            String callerOwner = e.getKey().owner;
            Set<MethodKey> targets = new HashSet<>();
            for (MethodKey t : e.getValue()) {
                // Cross-class calls to @Async methods dispatch to another thread and do not inherit the active transaction.
                if (callerOwner.equals(t.owner) || !asyncMethods.contains(t)) {
                    targets.add(t);
                }
                Deque<String> q = new ArrayDeque<>(subtypes.getOrDefault(t.owner, Set.of()));
                Set<String> seen = new HashSet<>();
                while (!q.isEmpty()) {
                    String sub = q.poll();
                    if (!seen.add(sub)) continue;
                    MethodKey subMk = new MethodKey(sub, t.name, t.desc);
                    if (allMethods.contains(subMk) && (callerOwner.equals(sub) || !asyncMethods.contains(subMk))) {
                        targets.add(subMk);
                    }
                    q.addAll(subtypes.getOrDefault(sub, Set.of()));
                }
            }
            out.put(e.getKey(), targets);
        }
        return out;
    }

    private static Map<String, String> allReachableSinks(
            MethodKey start,
            Map<MethodKey, Set<MethodKey>> edges,
            Map<MethodKey, Set<String>> directSinks
    ) {
        Map<String, String> found = new LinkedHashMap<>();
        Deque<MethodKey> q = new ArrayDeque<>();
        Map<MethodKey, MethodKey> parent = new HashMap<>();
        q.add(start);
        parent.put(start, null);
        while (!q.isEmpty()) {
            MethodKey cur = q.poll();
            Set<String> sinks = directSinks.getOrDefault(cur, Set.of());
            if (!sinks.isEmpty()) {
                List<String> path = new ArrayList<>();
                for (MethodKey n = cur; n != null; n = parent.get(n)) path.add(n.shortName());
                Collections.reverse(path);
                found.putIfAbsent(cur.shortName() + " " + new TreeSet<>(sinks), String.join(" -> ", path));
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
