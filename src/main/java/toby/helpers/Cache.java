package toby.helpers;

import org.apache.commons.collections4.MapIterator;
import org.apache.commons.collections4.map.LRUMap;

import java.util.ArrayList;

public class Cache <K, T> {

    private long timeToLiveInMillis;

    private LRUMap cacheMap;

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

        cacheMap = new LRUMap(maxItems);

        if (timeToLiveInMillis > 0 && timerIntervalInSeconds > 0) {

            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(timerIntervalInSeconds * 1000);
                        }
                        catch (InterruptedException ex) {
                        }

                        cleanup();
                    }
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
            CachedObject c = (CachedObject) cacheMap.get(key);

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

    @SuppressWarnings("unchecked")
    public void cleanup() {

        long now = System.currentTimeMillis();
        ArrayList<K> keysToDelete = null;

        synchronized (cacheMap) {
            MapIterator itr = cacheMap.mapIterator();

            keysToDelete = new ArrayList<K>((cacheMap.size() / 2) + 1);
            K key = null;
            CachedObject c = null;

            while (itr.hasNext()) {
                key = (K) itr.next();
                c = (CachedObject) itr.getValue();

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
