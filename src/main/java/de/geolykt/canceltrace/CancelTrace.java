package de.geolykt.canceltrace;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import org.bukkit.event.Cancellable;

public class CancelTrace {

    public static class CancelTraceCauser {
        public final String callerPlugin;
        public final String callerClass;
        public final String callerMethod;
        public final String callerMethodDesc;

        public CancelTraceCauser(String callerPlugin, String callerClass, String callerMethod, String callerMethodDesc) {
            this.callerPlugin = callerPlugin;
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.callerMethodDesc = callerMethodDesc;
        }
    }

    private static Map<Cancellable, Integer> canceller = Collections.synchronizedMap(new WeakHashMap<>());
    private static List<CancelTraceCauser> causers = new CopyOnWriteArrayList<>();

    public static void setCancelled(Object o, boolean z, int i) {
        if (z && o instanceof Cancellable) {
            canceller.put((Cancellable) o, i);
        }
    }

    public static synchronized int registerCauser(String callerPlugin, String callerClass, String callerMethod, String callerMethodDesc) {
        causers.add(new CancelTraceCauser(callerPlugin, callerClass, callerMethod, callerMethodDesc));
        return causers.size();
    }

    @Nullable
    public static CancelTraceCauser getCauser(Cancellable cancellable) {
        Integer x = canceller.get(cancellable);
        if (x == null) {
            return null;
        }
        return causers.get(x.intValue());
    }
}
