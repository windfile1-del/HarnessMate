import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Arrays;

public final class AndroidApiGate {
    private static final int JAR_COUNT = 7;
    private static final int SEPARATOR_INDEX = 2 + JAR_COUNT;

    private static boolean isDenied(String name) {
        return name.startsWith("java.lang.invoke.MethodHandle")
            || name.equals("java.lang.invoke.MethodType")
            || name.startsWith("java.awt.")
            || (name.startsWith("javax.swing.") && !name.equals("javax.swing.Icon"))
            || name.startsWith("javax.tools.")
            || name.startsWith("javax.lang.model.")
            || name.startsWith("com.sun.source.")
            || name.startsWith("com.sun.tools.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < SEPARATOR_INDEX + 2 || !"--".equals(args[SEPARATOR_INDEX])) {
            throw new IllegalArgumentException("expected overlay, icon root, seven pinned jars, --, and compiler arguments");
        }
        URL[] urls = new URL[2 + JAR_COUNT];
        urls[0] = Paths.get(args[0]).toAbsolutePath().toUri().toURL();
        urls[1] = Paths.get(args[1]).toAbsolutePath().toUri().toURL();
        for (int index = 0; index < JAR_COUNT; index++) {
            urls[index + 2] = Paths.get(args[index + 2]).toAbsolutePath().toUri().toURL();
        }
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (isDenied(name)) {
                    throw new ClassNotFoundException("ANDROID_API_GATE: " + name);
                }
                if (name.equals("javax.swing.Icon")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> compiler = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
                compiler.getMethod("main", String[].class).invoke(null,
                    (Object) Arrays.copyOfRange(args, SEPARATOR_INDEX + 1, args.length));
            } catch (InvocationTargetException exception) {
                throw new RuntimeException(exception.getCause());
            }
        }
    }
}
