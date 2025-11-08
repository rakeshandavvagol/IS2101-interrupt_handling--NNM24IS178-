import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Interrupt controller implemented with a priority queue and explicit mask handling.
 * This version focuses on readability, safe concurrency, and separation of concerns.
 */
public class InterruptControllerV2 {

    public enum Device {
        PRINTER(10),    // lower number => lower priority
        DISK(20),
        NETWORK(30);

        private final int priority;
        Device(int priority) { this.priority = priority; }
        public int priority() { return priority; }
    }

    /**
     * An interrupt event. Comparable so the PriorityBlockingQueue orders by descending priority.
     */
    public static final class InterruptEvent implements Comparable<InterruptEvent> {
        public final Device device;
        public final String payload;
        public final long timestamp;

        public InterruptEvent(Device device, String payload) {
            this.device = Objects.requireNonNull(device);
            this.payload = payload;
            this.timestamp = System.currentTimeMillis();
        }

        // Higher priority value should come first -> reverse order
        @Override
        public int compareTo(InterruptEvent other) {
            int cmp = Integer.compare(other.device.priority(), this.device.priority());
            if (cmp != 0) return cmp;
            // tie-breaker: older events first
            return Long.compare(this.timestamp, other.timestamp);
        }

        @Override public String toString() {
            return String.format("[%s:%s@%d]", device, payload, timestamp);
        }
    }

    // Core data structures
    private final PriorityBlockingQueue<InterruptEvent> queue = new PriorityBlockingQueue<>();
    private final Map<Device, Boolean> maskMap = new EnumMap<>(Device.class);

    // Lock & condition for mask changes and graceful shutdown signalling.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition maskOrStopChanged = lock.newCondition();

    private volatile boolean running = true;

    public InterruptControllerV2() {
        for (Device d : Device.values()) maskMap.put(d, false); // all unmasked initially
    }

    /** Submit a new interrupt event (thread-safe). */
    public void submit(InterruptEvent ev) {
        queue.offer(ev);
        // Wake up any waiting controller thread if it's waiting due to all top events being masked.
        signalController();
    }

    /** Mask or unmask a device. */
    public void setMask(Device device, boolean masked) {
        lock.lock();
        try {
            maskMap.put(device, masked);
            System.out.printf("Mask changed: %s -> %b%n", device, masked);
            maskOrStopChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Stop the controller gracefully: existing queued events may still be processed if possible. */
    public void stop() {
        lock.lock();
        try {
            running = false;
            maskOrStopChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Ask whether a device is currently masked (snapshot view). */
    private boolean isMasked(Device d) {
        // small optimization: no lock for read because maskMap values are only changed under lock,
        // but to be strictly consistent we read under lock.
        lock.lock();
        try {
            return maskMap.getOrDefault(d, true);
        } finally {
            lock.unlock();
        }
    }

    private void signalController() {
        lock.lock();
        try {
            maskOrStopChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Controller worker class that drains queue and executes ISR-like handlers.
     * It performs lazy skipping of masked events: when the top event is masked it temporarily
     * moves it aside and waits for either unmask or new arrivals.
     */
    public void runControllerLoop() {
        List<InterruptEvent> deferred = new ArrayList<>();

        while (true) {
            if (!running && queue.isEmpty()) {
                // No more events and we should stop
                log("Controller exiting: stopped and queue empty.");
                break;
            }

            try {
                // Try to take the highest-priority event (blocks if queue empty)
                InterruptEvent top = queue.take(); // blocks until an element is available

                // If the event's device is masked, move it to deferred list and wait for change.
                if (isMasked(top.device)) {
                    deferred.add(top);
                    log("Top event deferred because its device is masked: " + top);
                    // Wait for either an unmask / stop signal or new items inserted. Use condition to await.
                    lock.lock();
                    try {
                        // Use a timed wait to periodically re-check deferred vs queue to avoid deadlocks.
                        // The timeout is modest: 200 ms.
                        if (running && allDeferredMasked(deferred)) {
                            maskOrStopChanged.await(200, TimeUnit.MILLISECONDS);
                        }
                    } finally {
                        lock.unlock();
                    }

                    // Before continuing, re-insert any events from deferred back into the queue
                    if (!deferred.isEmpty()) {
                        for (InterruptEvent e : deferred) queue.offer(e);
                        deferred.clear();
                    }
                    continue;
                }

                // If we get here, 'top' is unmasked and can be handled
                log("Dispatching ISR for: " + top);
                // Execute ISR outside of any controller lock; handleIsr may be blocking/slow.
                handleIsr(top);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log("Controller thread interrupted; re-checking running flag.");
            }
        }
    }

    /** Helper: are all events in deferred currently masked? (checked under lock) */
    private boolean allDeferredMasked(List<InterruptEvent> deferred) {
        lock.lock();
        try {
            for (InterruptEvent e : deferred) {
                if (!maskMap.getOrDefault(e.device, true)) return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Simulated ISR work — runs without taking any controller locks. */
    private void handleIsr(InterruptEvent event) {
        // Example: pretend to do some work; in real systems this would signal device drivers, etc.
        log("Handling ISR start: " + event);
        try {
            // Simulate work
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log("Handling ISR done : " + event);
    }

    private static void log(String msg) {
        System.out.printf("[%s] %s%n", Thread.currentThread().getName(), msg);
    }

    // ------------------- Simple demo -------------------
    public static void main(String[] args) throws InterruptedException {
        InterruptControllerV2 controller = new InterruptControllerV2();
        Thread worker = new Thread(controller::runControllerLoop, "ControllerWorker");
        worker.start();

        // producers
        controller.submit(new InterruptEvent(Device.PRINTER, "print job 1"));
        controller.submit(new InterruptEvent(Device.NETWORK, "rx packet A"));
        controller.submit(new InterruptEvent(Device.DISK, "write complete"));

        // mask network for a while
        controller.setMask(Device.NETWORK, true);
        controller.submit(new InterruptEvent(Device.NETWORK, "rx packet B (masked)"));

        Thread.sleep(500);

        // unmask network so deferred network events can be handled
        controller.setMask(Device.NETWORK, false);

        Thread.sleep(700);

        // shutdown
        controller.stop();
        worker.join();
        System.out.println("Demo completed.");
    }
}
