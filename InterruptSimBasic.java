import java.util.*;

public class InterruptSim {

    // Priority: bigger number = higher priority
    enum Peripheral {
        KEYBOARD("Keyboard", 3),
        MOUSE("Mouse", 2),
        PRINTER("Printer", 1);

        final String label;
        final int priority;
        Peripheral(String label, int priority) {
            this.label = label;
            this.priority = priority;
        }
    }

    // Small record of which peripheral raised an interrupt this cycle
    static class InterruptEvent {
        Peripheral peripheral;
        long sequence;
        InterruptEvent(Peripheral p, long seq) {
            this.peripheral = p;
            this.sequence = seq;
        }
    }

    // Mask flags (true = masked / disabled)
    static boolean keyboardMasked = false;
    static boolean mouseMasked = false;
    static boolean printerMasked = false;

    // RNG for generating interrupts
    static Random random = new Random();

    // Helper to check mask by peripheral
    static boolean isPeripheralMasked(Peripheral p) {
        switch (p) {
            case KEYBOARD: return keyboardMasked;
            case MOUSE:    return mouseMasked;
            case PRINTER:  return printerMasked;
            default:       return false;
        }
    }

    // Simulated ISR: process the interrupt
    static void processInterrupt(Peripheral p) {
        System.out.println(p.label + " Interrupt received → processing handler...");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        System.out.println(p.label + " handler finished.");
    }

    // Run one simulation cycle (tick)
    static void runCycle(long cycleNumber) {
        System.out.println("\n=== CYCLE " + cycleNumber + " ===");

        // Randomly decide which peripherals triggered this cycle
        boolean keyboardFired = random.nextDouble() < 0.50; // 50% chance
        boolean mouseFired    = random.nextDouble() < 0.40; // 40% chance
        boolean printerFired  = random.nextDouble() < 0.30; // 30% chance

        List<InterruptEvent> events = new ArrayList<>();
        long seq = 1;
        if (keyboardFired) events.add(new InterruptEvent(Peripheral.KEYBOARD, seq++));
        if (mouseFired)    events.add(new InterruptEvent(Peripheral.MOUSE,    seq++));
        if (printerFired)  events.add(new InterruptEvent(Peripheral.PRINTER,  seq++));

        if (events.isEmpty()) {
            System.out.println("No interrupts this cycle.");
            return;
        }

        // Report masked interrupts and find the highest-priority unmasked event
        for (InterruptEvent ev : events) {
            if (isPeripheralMasked(ev.peripheral)) {
                System.out.println(ev.peripheral.label + " interrupt ignored (masked).");
            }
        }

        InterruptEvent chosen = null;
        for (InterruptEvent ev : events) {
            if (isPeripheralMasked(ev.peripheral)) continue;
            if (chosen == null || ev.peripheral.priority > chosen.peripheral.priority) {
                chosen = ev;
            }
        }

        if (chosen == null) {
            System.out.println("All interrupts were masked; nothing processed this cycle.");
        } else {
            processInterrupt(chosen.peripheral);
        }
    }

    // Display current mask configuration
    static void displayMaskStatus() {
        System.out.println("\nMask Configuration:");
        System.out.println("  Keyboard masked: " + keyboardMasked);
        System.out.println("  Mouse masked:    " + mouseMasked);
        System.out.println("  Printer masked:  " + printerMasked);
    }

    // Update a peripheral's mask by name
    static void updateMask(String name, boolean state) {
        String lower = name.toLowerCase();
        if (lower.startsWith("key")) {
            keyboardMasked = state;
            System.out.println("Keyboard masked -> " + state);
        } else if (lower.startsWith("mou")) {
            mouseMasked = state;
            System.out.println("Mouse masked -> " + state);
        } else if (lower.startsWith("pri")) {
            printerMasked = state;
            System.out.println("Printer masked -> " + state);
        } else {
            System.out.println("Unknown peripheral. Use: keyboard, mouse, printer");
        }
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long cycle = 1;

        System.out.println("=== Interrupt Simulator (Refactor) ===");
        System.out.println("Priority: Keyboard > Mouse > Printer");
        System.out.println("Commands:");
        System.out.println("  step           → run one cycle");
        System.out.println("  step n         → run n cycles");
        System.out.println("  mask <name>    → mask keyboard|mouse|printer");
        System.out.println("  unmask <name>  → unmask peripheral");
        System.out.println("  status         → show mask configuration");
        System.out.println("  reset          → unmask all peripherals");
        System.out.println("  quit           → exit");
        displayMaskStatus();

        while (true) {
            System.out.print("\n> ");
            String line = sc.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "quit":
                    System.out.println("Exiting simulator. Bye!");
                    sc.close();
                    return;
                case "status":
                    displayMaskStatus();
                    break;
                case "reset":
                    keyboardMasked = mouseMasked = printerMasked = false;
                    System.out.println("All peripherals unmasked.");
                    displayMaskStatus();
                    break;
                case "mask":
                    if (parts.length >= 2) updateMask(parts[1], true);
                    else System.out.println("Usage: mask <keyboard|mouse|printer>");
                    break;
                case "unmask":
                    if (parts.length >= 2) updateMask(parts[1], false);
                    else System.out.println("Usage: unmask <keyboard|mouse|printer>");
                    break;
                case "step":
                    int times = 1;
                    if (parts.length >= 2) {
                        try { times = Math.max(1, Integer.parseInt(parts[1])); }
                        catch (NumberFormatException e) { System.out.println("Invalid number; running 1 cycle."); }
                    }
                    for (int i = 0; i < times; i++) runCycle(cycle++);
                    break;
                default:
                    System.out.println("Unknown command. Try: step | step 5 | mask keyboard | unmask mouse | status | reset | quit");
            }
        }
    }
}

