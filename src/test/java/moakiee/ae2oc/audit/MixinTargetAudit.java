package moakiee.ae2oc.audit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Build-time static Mixin target audit, not a substitute for runtime transformation.
 * Checks target classes, injection methods, shadow/invoker/accessor members and selected
 * instruction constraints against dependency jars. It does not prove local-variable capture or injection counts.
 * Named members that
 * carry SRG aliases (m_/f_) are checked against the obfuscated production jars, and
 * official names against the mapped (deobfuscated) jars.
 */
public final class MixinTargetAudit {
    record ClassSource(JarFile jar, String entry) {}
    record MemberSpec(String owner, String name, String desc) {}

    static final Map<String, ClassSource> mappedIndex = new HashMap<>();
    static final Map<String, ClassSource> prodIndex = new HashMap<>();
    static final Map<String, ClassNode> nodeCache = new HashMap<>();
    static final List<String> failures = new ArrayList<>();
    static int checks;
    static String currentMixin;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected mixin directory, mapped manifest, production manifest");
        Path mixinDir = Paths.get(args[0]);
        List<Path> mixins = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mixinDir, "*.class")) {
            ds.forEach(mixins::add);
        }
        mixins.sort(null);
        if (mixins.isEmpty()) throw new IllegalStateException("No compiled mixins to audit");
        try {
            for (String jar : Files.readAllLines(Paths.get(args[1]))) if (!jar.isBlank()) indexJar(Paths.get(jar), mappedIndex);
            for (String jar : Files.readAllLines(Paths.get(args[2]))) if (!jar.isBlank()) indexJar(Paths.get(jar), prodIndex);
            auditAll(mixins);
            int baselineChecks = checks;
            if (checks == 0 || !failures.isEmpty()) {
                failures.forEach(f -> System.err.println("FAIL " + f));
                throw new IllegalStateException("Mixin target audit failed");
            }
            // Prove official aliases cannot conceal missing production SRG targets.
            prodIndex.clear();
            checks = 0;
            failures.clear();
            auditAll(mixins);
            if (failures.isEmpty()) throw new IllegalStateException("Empty-production negative control unexpectedly passed");
            System.out.println("Mixin target audit passed: " + baselineChecks
                    + " checks; empty-production negative control caught " + failures.size() + " failures");
        } finally {
            for (JarFile jar : openedJars) jar.close();
        }
    }

    static void auditAll(List<Path> mixins) throws Exception {
        for (Path file : mixins) {
            if (!file.getFileName().toString().contains("$"))
                auditMixin(Files.readAllBytes(file), file.getFileName().toString());
        }
    }

    static final List<JarFile> openedJars = new ArrayList<>();

    static void indexJar(Path path, Map<String, ClassSource> index) throws Exception {
        JarFile jar = new JarFile(path.toFile());
        openedJars.add(jar);
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            String name = e.getName();
            if (name.endsWith(".class")) index.putIfAbsent(name.substring(0, name.length() - 6), new ClassSource(jar, name));
        }
    }

    static ClassNode lookup(String internalName) {
        if (internalName == null) return null;
        ClassNode cached = nodeCache.get(internalName);
        if (cached != null) return cached;
        ClassSource source = mappedIndex.get(internalName);
        if (source == null) return null;
        try (InputStream in = source.jar().getInputStream(source.jar().getEntry(source.entry()))) {
            byte[] bytes = in.readAllBytes();
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            nodeCache.put(internalName, node);
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("reading " + internalName, e);
        }
    }

    static ClassNode lookupProd(String internalName) {
        ClassSource source = prodIndex.get(internalName);
        if (source == null) return null;
        try (InputStream in = source.jar().getInputStream(source.jar().getEntry(source.entry()))) {
            byte[] bytes = in.readAllBytes();
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("reading prod " + internalName, e);
        }
    }

    static void auditMixin(byte[] bytes, String fileName) {
        ClassNode mixin = new ClassNode();
        new ClassReader(bytes).accept(mixin, ClassReader.SKIP_CODE);
        currentMixin = mixin.name.substring(mixin.name.lastIndexOf('/') + 1);
        AnnotationNode mixinAnn = annotation(mixin, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (mixinAnn == null) return;
        List<String> targets = new ArrayList<>();
        List<Type> values = listValue(mixinAnn, "value");
        if (values != null) for (Type t : values) targets.add(t.getInternalName());
        List<String> named = listValue(mixinAnn, "targets");
        if (named != null) for (String s : named) targets.add(s.replace('.', '/'));
        if (targets.isEmpty()) {
            fail("no target class");
            return;
        }
        for (String target : targets) {
            ClassNode node = lookup(target);
            if (node == null) {
                fail("target class missing: " + target);
                continue;
            }
            checks++;
            auditTarget(mixin, node);
        }
    }

    static void auditTarget(ClassNode mixin, ClassNode target) {
        for (MethodNode method : mixin.methods) {
            auditHandlerMethod(mixin, target, method);
        }
        for (FieldNode field : mixin.fields) {
            AnnotationNode shadow = annotation(field, "Lorg/spongepowered/asm/mixin/Shadow;");
            if (shadow != null) {
                String name = value(shadow, "value") != null ? (String) value(shadow, "value") : stripPrefix(field.name, stringValue(shadow, "prefix"));
                requireMember(target, name, field.desc, true, "@Shadow field");
            }
        }
    }

    static void auditHandlerMethod(ClassNode mixin, ClassNode target, MethodNode method) {
        AnnotationNode shadow = annotation(method, "Lorg/spongepowered/asm/mixin/Shadow;");
        if (shadow != null) {
            String name = value(shadow, "value") != null ? (String) value(shadow, "value") : stripPrefix(method.name, stringValue(shadow, "prefix"));
            requireMember(target, name, method.desc, false, "@Shadow method");
        }
        AnnotationNode invoker = annotation(method, "Lorg/spongepowered/asm/mixin/gen/Invoker;");
        if (invoker != null) {
            String name = value(invoker, "value") != null ? (String) value(invoker, "value") : stripPrefix(method.name, stringValue(invoker, "prefix"));
            requireMember(target, name, method.desc, false, "@Invoker");
        }
        AnnotationNode accessor = annotation(method, "Lorg/spongepowered/asm/mixin/gen/Accessor;");
        if (accessor != null) {
            String name = (String) value(accessor, "value");
            if (name == null) fail("@Accessor without explicit value on " + method.name);
            else {
                Type signature = Type.getMethodType(method.desc);
                String fieldType = signature.getReturnType().getSort() == Type.VOID
                        ? signature.getArgumentTypes()[0].getDescriptor() : signature.getReturnType().getDescriptor();
                requireMember(target, name, fieldType, true, "@Accessor");
            }
        }
        AnnotationNode inject = firstAnnotation(method,
                "Lorg/spongepowered/asm/mixin/injection/Inject;",
                "Lorg/spongepowered/asm/mixin/injection/Redirect;",
                "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
                "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
                "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
                "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;");
        if (inject == null) return;
        List<String> methodSpecs = listValue(inject, "method");
        if (methodSpecs == null || methodSpecs.isEmpty()) {
            fail(inject.desc + " without method targets on " + method.name);
            return;
        }
        List<AnnotationNode> ats = listValue(inject, "at");
        if (ats == null) {
            AnnotationNode single = (AnnotationNode) value(inject, "at");
            ats = single == null ? List.of() : List.of(single);
        }
        boolean srgAliasChecked = false;
        boolean matched = false;
        boolean isModifyConstant = inject.desc.endsWith("ModifyConstant;");
        for (String spec : methodSpecs) {
            MemberSpec ms = parseSpec(spec);
            // Skip alias alternatives once one alias matched the target.
            MethodNode targetMethod = findDeclared(target, ms.name(), ms.desc());
            if (targetMethod != null) {
                matched = true;
                checks++;
                for (AnnotationNode at : ats) auditInjectionPoint(inject, target, targetMethod, at);
                if (isModifyConstant) auditModifyConstant(inject, target, targetMethod);
                continue;
            }
            if (isSrgName(ms.name())) {
                ClassNode prod = lookupProd(target.name);
                if (prod == null) {
                    fail("prod target class missing: " + target.name + " (for SRG alias " + ms.name() + ")");
                    srgAliasChecked = true;
                    continue;
                }
                if (findDeclared(prod, ms.name(), ms.desc()) != null) {
                    checks++;
                    srgAliasChecked = true;
                    continue;
                }
                fail("SRG alias " + ms.name() + ms.desc() + " missing in prod " + target.name);
                srgAliasChecked = true;
                continue;
            }
        }
        if (!matched && !srgAliasChecked) {
            fail("no injection method target of " + methodSpecs + " declared in " + target.name);
        } else if (!matched && srgAliasChecked) {
            // At least one SRG alias must exist in prod AND one official name in mapped.
            boolean officialPresent = false;
            for (String spec : methodSpecs) {
                MemberSpec ms = parseSpec(spec);
                if (!isSrgName(ms.name()) && findDeclared(target, ms.name(), ms.desc()) != null) officialPresent = true;
            }
            if (!officialPresent) fail("no official-named injection target of " + methodSpecs + " in mapped " + target.name);
        }
    }

    static void auditModifyConstant(AnnotationNode inject, ClassNode target, MethodNode method) {
        List<AnnotationNode> constants = listValue(inject, "constant");
        if (constants == null) {
            AnnotationNode single = (AnnotationNode) value(inject, "constant");
            constants = single == null ? List.of() : List.of(single);
        }
        for (AnnotationNode constant : constants) {
            Object intValue = value(constant, "intValue");
            if (intValue instanceof Number number && !containsIntConstant(method, number.intValue())) {
                fail("int constant " + number + " not found in " + target.name + "." + method.name + method.desc);
            }
            checks++;
        }
    }

    static void auditInjectionPoint(AnnotationNode inject, ClassNode target, MethodNode method, AnnotationNode at) {
        String kind = (String) value(at, "value");
        if (kind == null) return;
        String targetSpec = (String) value(at, "target");
        if ("INVOKE".equals(kind)) {
            if (targetSpec == null) { fail("@At(INVOKE) without target on " + target.name + "." + method.name); return; }
            MemberSpec ms = parseInvokeTarget(targetSpec);
            ClassNode owner = lookup(ms.owner());
            if (owner == null) { fail("INVOKE owner missing: " + ms.owner()); return; }
            MethodNode callee = findInHierarchy(owner, ms.name(), ms.desc());
            if (callee == null) { fail("INVOKE callee " + ms.owner() + "." + ms.name() + ms.desc() + " missing"); return; }
            checks++;
            if (!containsCall(method, ms)) fail("no call to " + ms.owner() + "." + ms.name() + ms.desc() + " inside " + target.name + "." + method.name + method.desc);
            checks++;
        } else if ("FIELD".equals(kind) && targetSpec != null) {
            MemberSpec ms = parseFieldTarget(targetSpec);
            ClassNode owner = lookup(ms.owner());
            if (owner == null) { fail("FIELD owner missing: " + ms.owner()); return; }
            if (findFieldInHierarchy(owner, ms.name(), ms.desc()) == null) fail("FIELD " + ms.owner() + "." + ms.name() + " missing");
            checks++;
        }
        if ("STORE".equals(kind) || "LOAD".equals(kind)) {
            Integer ordinal = (Integer) value(at, "ordinal");
            if (ordinal != null) {
                int count = 0;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof VarInsnNode v && ((kind.equals("STORE") && v.getOpcode() >= 54 && v.getOpcode() <= 78)
                            || (kind.equals("LOAD") && v.getOpcode() >= 21 && v.getOpcode() <= 53))) count++;
                }
                if (count <= ordinal) fail("only " + count + " " + kind + " in " + target.name + "." + method.name + ", ordinal " + ordinal + " unreachable");
                checks++;
            }
        }
    }

    static boolean containsCall(MethodNode method, MemberSpec ms) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(ms.owner()) && call.name.equals(ms.name())
                    && (ms.desc() == null || call.desc.equals(ms.desc()))) return true;
        }
        return false;
    }

    static boolean containsIntConstant(MethodNode method, int value) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number n && n.intValue() == value) return true;
            if (insn instanceof IntInsnNode i && i.operand == value) return true;
            int op = insn.getOpcode();
            if (op >= 3 && op <= 8 && value == op - 3) return true; // ICONST_0..5
            if (op == 16 || op == 17) { /* covered by IntInsnNode */ }
        }
        return false;
    }

    static void requireMember(ClassNode target, String name, String desc, boolean field, String kind) {
        Object member = field ? findFieldInHierarchy(target, name, desc) : findInHierarchy(target, name, desc);
        if (member == null) {
            // SRG fallback: official name might not exist in mapped jar if it is itself SRG.
            if (isSrgName(name)) {
                ClassNode prod = lookupProd(target.name);
                Object prodMember = prod == null ? null : (field ? findFieldInHierarchy(prod, name, desc, prodIndex) : findInHierarchy(prod, name, desc, prodIndex));
                if (prodMember != null) { checks++; return; }
            }
            fail(kind + " member " + name + (desc == null ? "" : desc) + " missing in " + target.name + " hierarchy");
            return;
        }
        checks++;
    }

    static String stripPrefix(String name, String prefix) {
        if (prefix == null) prefix = "shadow$";
        if (name.startsWith(prefix)) return name.substring(prefix.length());
        return name;
    }

    static boolean isSrgName(String name) {
        return name.matches("[mf]_\\d+_.*");
    }

    static MethodNode findDeclared(ClassNode node, String name, String desc) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        }
        return null;
    }

    static MethodNode findInHierarchy(ClassNode node, String name, String desc) {
        return findInHierarchy(node, name, desc, mappedIndex);
    }

    static MethodNode findInHierarchy(ClassNode node, String name, String desc, Map<String, ClassSource> index) {
        ClassNode current = node;
        while (current != null) {
            MethodNode m = findDeclared(current, name, desc);
            if (m != null) return m;
            for (String iface : current.interfaces) {
                ClassNode in = lookupFrom(iface, index);
                if (in != null) {
                    MethodNode im = findDeclared(in, name, desc);
                    if (im != null) return im;
                }
            }
            current = lookupFrom(current.superName, index);
        }
        return null;
    }

    static FieldNode findFieldInHierarchy(ClassNode node, String name, String desc) {
        return findFieldInHierarchy(node, name, desc, mappedIndex);
    }

    static FieldNode findFieldInHierarchy(ClassNode node, String name, String desc, Map<String, ClassSource> index) {
        ClassNode current = node;
        while (current != null) {
            for (FieldNode f : current.fields) {
                if (f.name.equals(name) && (desc == null || f.desc.equals(desc))) return f;
            }
            current = lookupFrom(current.superName, index);
        }
        return null;
    }

    static ClassNode lookupFrom(String internalName, Map<String, ClassSource> index) {
        if (internalName == null) return null;
        if (index == mappedIndex) return lookup(internalName);
        return lookupProd(internalName);
    }

    static MemberSpec parseSpec(String spec) {
        int paren = spec.indexOf('(');
        if (paren < 0) return new MemberSpec(null, spec, null);
        return new MemberSpec(null, spec.substring(0, paren), spec.substring(paren));
    }

    static MemberSpec parseInvokeTarget(String target) {
        // Lowner/Class;name()desc  or  Lowner/Class;name:desc
        String stripped = target;
        if (stripped.startsWith("L")) stripped = stripped.substring(1);
        int semi = stripped.indexOf(';');
        String owner = stripped.substring(0, semi);
        String rest = stripped.substring(semi + 1);
        int colon = rest.indexOf(':');
        if (colon >= 0) return new MemberSpec(owner, rest.substring(0, colon), rest.substring(colon + 1));
        return new MemberSpec(owner, rest.substring(0, rest.indexOf('(')), rest.substring(rest.indexOf('(')));
    }

    static MemberSpec parseFieldTarget(String target) {
        return parseInvokeTarget(target);
    }

    static AnnotationNode annotation(MethodNode method, String desc) {
        return firstAnnotation(method, desc);
    }

    static AnnotationNode annotation(FieldNode field, String desc) {
        List<AnnotationNode> all = new ArrayList<>();
        if (field.visibleAnnotations != null) all.addAll(field.visibleAnnotations);
        if (field.invisibleAnnotations != null) all.addAll(field.invisibleAnnotations);
        for (AnnotationNode a : all) if (a.desc.equals(desc)) return a;
        return null;
    }

    static AnnotationNode annotation(ClassNode node, String desc) {
        List<AnnotationNode> all = new ArrayList<>();
        if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);
        for (AnnotationNode a : all) if (a.desc.equals(desc)) return a;
        return null;
    }

    static AnnotationNode firstAnnotation(MethodNode method, String... descs) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        for (AnnotationNode a : all) for (String d : descs) if (a.desc.equals(d)) return a;
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> listValue(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        if (v instanceof List<?> list) return (List<T>) list;
        return null;
    }

    static Object value(AnnotationNode ann, String key) {
        if (ann.values == null) return null;
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (key.equals(ann.values.get(i))) return ann.values.get(i + 1);
        }
        return null;
    }

    static String stringValue(AnnotationNode ann, String key) {
        Object v = value(ann, key);
        return v instanceof String s ? s : null;
    }

    static void fail(String message) {
        failures.add("[" + currentMixin + "] " + message);
    }
}
