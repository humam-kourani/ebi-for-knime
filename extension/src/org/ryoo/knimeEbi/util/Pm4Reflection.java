package org.ryoo.knimeEbi.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.pm4knime.portobject.XLogPortObject;

/** Accesses ProM objects using their owning PM4KNIME OSGi class loader. */
final class Pm4Reflection {
    private static final Map<Class<?>, Class<?>> BOX = Map.of(int.class, Integer.class,
        long.class, Long.class, boolean.class, Boolean.class, double.class, Double.class);
    private Pm4Reflection() { }
    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, XLogPortObject.class.getClassLoader());
    }
    static Object call(Object receiver, String name, Object... args) throws Exception {
        Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] params = method.getParameterTypes(); boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null ? params[i].isPrimitive() : !BOX.getOrDefault(params[i], params[i]).isInstance(args[i])) { matches = false; break; }
            }
            if (!matches) continue;
            try { return method.invoke(receiver instanceof Class<?> ? null : receiver, args); }
            catch (InvocationTargetException ex) {
                if (ex.getCause() instanceof Exception cause) throw cause;
                if (ex.getCause() instanceof Error cause) throw cause;
                throw ex;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + args.length + " compatible arguments");
    }
    static Object create(String className, Object... args) throws Exception {
        Class<?> type = type(className);
        for (var ctor : type.getConstructors()) {
            if (ctor.getParameterCount() != args.length) continue;
            Class<?>[] params = ctor.getParameterTypes(); boolean match = true;
            for (int i = 0; i < args.length; i++) if (!BOX.getOrDefault(params[i], params[i]).isInstance(args[i])) { match = false; break; }
            if (match) return ctor.newInstance(args);
        }
        throw new NoSuchMethodException("No compatible constructor: " + className);
    }
}
