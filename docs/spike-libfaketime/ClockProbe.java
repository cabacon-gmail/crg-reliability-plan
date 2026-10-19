public class ClockProbe {
    public static void main(String[] a) throws Exception {
        long w0 = System.currentTimeMillis(), n0 = System.nanoTime();
        for (int i = 0; i < 16; i++) {
            Thread.sleep(500);
            long wall = System.currentTimeMillis() - w0, mono = (System.nanoTime() - n0) / 1_000_000;
            System.out.printf("t=%5.1fs  wall=%+9d ms  mono=%+7d ms  wall-mono=%+9d ms%n", mono / 1000.0, wall, mono, wall - mono);
        }
    }
}
