package de.geolykt.canceltrace.transform;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.plugin.PluginDescriptionFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import de.geolykt.canceltrace.CancelTrace;

import sun.misc.Unsafe;

public class TransformContainer {

    private static final String PACKAGE_NAME = "de/geolykt/canceltrace/transform/";

    public static byte[] transform(byte[] source, PluginDescriptionFile sourceFile,  String filename) {
        if (filename.startsWith(PACKAGE_NAME + "asm") || filename.equals("de/geolykt/canceltrace/CancelTrace")) {
            return source;
        }
        ClassReader reader = new ClassReader(source);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        try {
            AtomicBoolean modified = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {

                String cname;

                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] interfaces) {
                    this.cname = name;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
                            if (name.equals("setCancelled") && name.equals("(Z)V")) {
                                super.visitInsn(Opcodes.DUP2);
                                super.visitIntInsn(Opcodes.BIPUSH, CancelTrace.registerCauser(sourceFile.getName(), cname, name, descriptor));
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "de/geolykt/canceltrace/CancelTrace", "setCancelled", "(Ljava/lang/Object;ZI)V", false);
                                modified.set(true);
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            }, 0);
            if (!modified.get()) {
                return source; // Don't risk too much danger
            }
            return writer.toByteArray();
        } catch (Throwable t) {
            // We probably encountered an ASM-Crasher
            if (t instanceof ThreadDeath) {
                throw (ThreadDeath) t;
            } else if (t instanceof OutOfMemoryError) {
                throw (OutOfMemoryError) t;
            }
            t.printStackTrace();
            return source;
        }
    }

    private static ClassNode nodeFromClass(Class<?> cl) throws IOException {
        ClassNode node = new ClassNode();
        String fileName = cl.getName().replace('.', '/') + ".class";
        ClassLoader classlaoder = cl.getClassLoader();
        InputStream in = classlaoder.getResourceAsStream(fileName);
        if (in == null) {
            throw new IOException("Unable to get \"" + fileName + "\" from classloader \"" + classlaoder.getName() + "\"");
        }
        ClassReader reader = new ClassReader(in);
        reader.accept(node, 0);
        in.close();
        return node;
    }

    @SuppressWarnings("deprecation")
    private static void inject() throws Throwable {

        ClassNode unsafeItf;
        try {
            unsafeItf = nodeFromClass(UnsafeValues.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to get the bytecode of the UnsafeValues interface", e);
        }

        ClassNode hackedUnsafe = new ClassNode();
        hackedUnsafe.superName = "java/lang/Object";
        hackedUnsafe.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        hackedUnsafe.version = Opcodes.V9;
        hackedUnsafe.name = PACKAGE_NAME + "CustomUnsafeValuesImpl";
        hackedUnsafe.interfaces.add(unsafeItf.name);

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(L" + unsafeItf.name + ";)V", null, null);
        hackedUnsafe.methods.add(constructor);
        FieldNode pristineUnsafe = new FieldNode(Opcodes.ACC_PUBLIC, "pristineUnsafe", "L" + unsafeItf.name + ";", null, null);
        hackedUnsafe.fields.add(pristineUnsafe);

        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // ALOAD server
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, hackedUnsafe.name, pristineUnsafe.name, pristineUnsafe.desc));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // ALOAD this
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));

        for (MethodNode unsafeInterfaceMethod : unsafeItf.methods) {
            MethodNode implMethod = new MethodNode(Opcodes.ACC_PUBLIC, unsafeInterfaceMethod.name, unsafeInterfaceMethod.desc, unsafeInterfaceMethod.signature, unsafeInterfaceMethod.exceptions.toArray(new String[0]));

            implMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            implMethod.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, hackedUnsafe.name, pristineUnsafe.name, pristineUnsafe.desc));

            DescString descString = new DescString(unsafeInterfaceMethod.desc);

            int localIndex = 0;
            while (descString.hasNext()) {
                String type = descString.nextType();
                int opcode;
                boolean wide = false;
                switch (type.codePointAt(0)) {
                case 'L':
                case '[':
                    opcode = Opcodes.ALOAD;
                    break;
                case 'I':
                case 'Z':
                case 'S':
                case 'C':
                case 'B':
                    opcode = Opcodes.ILOAD;
                    break;
                case 'J':
                    opcode = Opcodes.LLOAD;
                    wide = true;
                    break;
                case 'F':
                    opcode = Opcodes.FLOAD;
                    break;
                case 'D':
                    opcode = Opcodes.DLOAD;
                    wide = true;
                    break;
                default:
                    throw new InjectionException("Unknown type: " + type.codePointAt(0));
                }
                implMethod.instructions.add(new VarInsnNode(opcode, ++localIndex));
                if (wide) {
                    localIndex++;
                }
            }

            String returnType = unsafeInterfaceMethod.desc.substring(unsafeInterfaceMethod.desc.lastIndexOf(')') + 1);

            int returnOpcode;
            switch (returnType.codePointAt(0)) {
            case 'L':
            case '[':
                returnOpcode = Opcodes.ARETURN;
                break;
            case 'I':
            case 'Z':
            case 'S':
            case 'C':
            case 'B':
                returnOpcode = Opcodes.IRETURN;
                break;
            case 'J':
                returnOpcode = Opcodes.LRETURN;
                break;
            case 'F':
                returnOpcode = Opcodes.FRETURN;
                break;
            case 'D':
                returnOpcode = Opcodes.DRETURN;
                break;
            case 'V':
                returnOpcode = Opcodes.RETURN;
                break;
            default:
                throw new InjectionException("Unknown return type: " + returnType);
            }

            implMethod.maxLocals = localIndex;
            implMethod.maxStack = localIndex;

            implMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, unsafeItf.name, unsafeInterfaceMethod.name, unsafeInterfaceMethod.desc));

            String pdfDesc = Type.getDescriptor(PluginDescriptionFile.class);
            if (implMethod.name.equals("processClass") && implMethod.desc.equals("(" + pdfDesc + "Ljava/lang/String;[B)[B")) {
                implMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                implMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                implMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(TransformContainer.class), "transform", "([B" + pdfDesc + " Ljava/lang/String;)[B"));
            }

            implMethod.instructions.add(new InsnNode(returnOpcode));

            hackedUnsafe.methods.add(implMethod);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        hackedUnsafe.accept(writer);
        byte[] hackedBytes = writer.toByteArray();
        Class<?> hackUnsafeClassInstance;

        try {
            hackUnsafeClassInstance = MethodHandles.lookup().defineClass(hackedBytes);
        } catch (Exception e) {
            throw new InjectionException("Could not define the custom UnsafeValues impl class!", e);
        }

        Object hackUnsafeInstance;
        try {
            hackUnsafeInstance = hackUnsafeClassInstance.getConstructor(UnsafeValues.class).newInstance(Bukkit.getUnsafe());
        } catch (Exception e) {
            throw new InjectionException("Could not obtain an instance of the custom UnsafeValues impl class!", e);
        }

        try {
            // Now we use the big Unsafe to set the little unsafe
            Field bukkitUnsafeInstanceField = Bukkit.getUnsafe().getClass().getDeclaredField("INSTANCE");
            Field jvmUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            jvmUnsafeField.setAccessible(true);
            Unsafe jvmUnsafe = (Unsafe) jvmUnsafeField.get(null);
            jvmUnsafeField.setAccessible(false);
            Object fieldBase = jvmUnsafe.staticFieldBase(bukkitUnsafeInstanceField);
            long fieldOffset = jvmUnsafe.staticFieldOffset(bukkitUnsafeInstanceField);
            jvmUnsafe.putObject(fieldBase, fieldOffset, hackUnsafeInstance);
        } catch (Exception e) {
            throw new InjectionException("Couldn't set the custom UnsafeValues impl as the UnsafeValues impl", e);
        }
    }

    static {
        if (!Boolean.getBoolean(PACKAGE_NAME.replace('/', '.') + ".disabled")) {
            try {
                inject();
            } catch (Throwable t) {
                System.setProperty(PACKAGE_NAME.replace('/', '.') + ".disabled", "true");
                if (t instanceof ThreadDeath) {
                    throw (ThreadDeath) t;
                } else if (t instanceof OutOfMemoryError) {
                    throw (OutOfMemoryError) t;
                }
                t.printStackTrace();
            }
        }
    }
}
