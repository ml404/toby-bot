package toby.helpers;

import org.apache.commons.collections4.MapIterator;
import org.apache.commons.collections4.map.LRUMap;

import java.util.ArrayList;

public class Cache <K, T> {

    private final long timeToLiveInMillis;

    private final LRUMap<K, CachedObject> cacheMap;

    protected class CachedObject {
        public long lastAccessed = System.currentTimeMillis();
        public T value;

        protected CachedObject(T value) {
            this.value = value;
        }
    }

    public Cache(long timeToLiveInSeconds, final long timerIntervalInSeconds,
                 int maxItems) {
        this.timeToLiveInMillis = timeToLiveInSeconds * 1000;

        cacheMap = new LRUMap<>(maxItems);

        if (timeToLiveInMillis > 0 && timerIntervalInSeconds > 0) {

            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(timerIntervalInSeconds * 1000);
                    }
                    catch (InterruptedException ignored) {
                    }

                    cleanup();
                }
            });

            t.setDaemon(true);
            t.start();
        }
    }

    public void put(K key, T value) {
        synchronized (cacheMap) {
            cacheMap.put(key, new CachedObject(value));
        }
    }

    public T get(K key) {
        synchronized (cacheMap) {
            CachedObject c = cacheMap.get(key);

            if (c == null)
                return null;
            else {
                c.lastAccessed = System.currentTimeMillis();
                return c.value;
            }
        }
    }

    public void remove(K key) {
        synchronized (cacheMap) {
            cacheMap.remove(key);
        }
    }

    public int size() {
        synchronized (cacheMap) {
            return cacheMap.size();
        }
    }

    public void cleanup() {

        long now = System.currentTimeMillis();
        ArrayList<K> keysToDelete;

        synchronized (cacheMap) {
            MapIterator<K, CachedObject> itr = cacheMap.mapIterator();

            keysToDelete = new ArrayList<>((cacheMap.size() / 2) + 1);
            K key;
            CachedObject c;

            while (itr.hasNext()) {
                key = itr.next();
                c = itr.getValue();

                if (c != null && (now > (timeToLiveInMillis + c.lastAccessed))) {
                    keysToDelete.add(key);
                }
            }
        }

        for (K key : keysToDelete) {
            synchronized (cacheMap) {
                cacheMap.remove(key);
            }

            Thread.yield();
        }
    }
}
