package com.alexselzer.mrjoins;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class DataGenerator {
    public static class Attribute {
        private static final int RANDOM_STRINGS = 100;
        private int length;
        private Random random;
        private static final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz123456789";
        private String[] randomStrings;

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public String generate() {
            return randomStrings[random.nextInt(RANDOM_STRINGS)];
        }

        public Attribute(int length) {
            this.length = length;
            this.random = new Random();

            randomStrings = new String[RANDOM_STRINGS];

            // Generate RANDOM_STRINGS random strings because it is faster
            for (int i = 0; i < randomStrings.length; i++) {
                char[] buf = new char[getLength()];
                for (int j = 0; j < getLength(); j++) {
                    buf[j] = chars.charAt(random.nextInt(chars.length()));
                }

                randomStrings[i] = new String(buf);
            }
        }
    }

    enum KeyType {
        NUMERIC, STRING
    }

    private long nRows;
    private List<Attribute> attributes;
    private KeyType keyType;
    private long uniqueValues;

    public DataGenerator(KeyType keyType, long nRows, List<Attribute> attributes, long uniqueValues) {
        this.keyType = keyType;
        this.nRows = nRows;
        this.attributes = attributes;
        this.uniqueValues = uniqueValues;
    }

    /**
     * Calculate how often the most common value will occur
     * @param N The number of unique values (population)
     * @param s The skew
     * @return
     */
    public static double getMaxZipfRepeats(long N, double s, long count) {
        /**
         * https://en.wikipedia.org/wiki/Zipf%27s_law
         *
         * From the pmf: p(k) = 1 / (k^s * H(N, s))
         * => occurrences of k = 1: count / H(N, s)
         */
        return count / generalizedHarmonic((int) N, s);
    }

    /**
     * Taken from commons-math:
     *
     * 213     * Calculates the Nth generalized harmonic number. See
     * 214     * <a href="http://mathworld.wolfram.com/HarmonicSeries.html">Harmonic
     * 215     * Series</a>.
     * 216     *
     * 217     * @param n Term in the series to calculate (must be larger than 1)
     * 218     * @param m Exponent (special case {@code m = 1} is the harmonic series).
     * 219     * @return the n<sup>th</sup> generalized harmonic number.
     * 220
     */
    private static double generalizedHarmonic(final int n, final double m) {
        double value = 0;
        for (int k = n; k > 0; --k) {
            value += 1.0 / Math.pow(k, m);
        }
        return value;
    }

    public void write(DataOutputStream file1, DataOutputStream file2) {
        PrintWriter t1writer = new PrintWriter(file1);
        PrintWriter t2writer = new PrintWriter(file2);

        long keyModulo = uniqueValues;

        for (int i = 0; i < nRows; i++) {
            String row = "" + (i % keyModulo);

            for (Attribute a : attributes) {
                row += "," + a.generate();
            }

            row += "\n";

            t1writer.write(row);
            t2writer.write(row);
        }

        t1writer.close();
        t2writer.close();
    }

    public void writeZipf(DataOutputStream file1, DataOutputStream file2, double s) {
        PrintWriter t1writer = new PrintWriter(file1);
        PrintWriter t2writer = new PrintWriter(file2);

        Integer[] keys = new Integer[(int)uniqueValues];
        for (int i = 0; i < uniqueValues; i++) {
            keys[i] = i;
        }

        List<Integer> keysList = new ArrayList<Integer>(Arrays.asList(keys));
        Collections.shuffle(keysList);

        for (int i = 0; i < uniqueValues; i++) {
            String row = "" + (keysList.get(i));

            for (Attribute a : attributes) {
                row += "," + a.generate();
            }

            row += "\n";

            t1writer.write(row);
        }

        for (int i = 0; i < nRows; i++) {
            String row = "" + zipfInverseCdf((double)i / (double)nRows, s, (double) uniqueValues);

            for (Attribute a : attributes) {
                row += "," + a.generate();
            }

            row += "\n";

            t2writer.write(row);
        }

        t1writer.close();
        t2writer.close();
    }

    public void writeZipfParallelToHdfs(Path path1, Path path2, final double s, final int nThreads) throws IOException {
        final FileSystem hdfs = FileSystem.get(new Configuration());

        hdfs.mkdirs(path1);
        hdfs.mkdirs(path2);

        final CountDownLatch latch = new CountDownLatch(nThreads);

        for (int thread = 0; thread < nThreads; thread++) {

            final Path f1 = new Path(path1.getName() + "/" + String.format("%04d", thread));
            final Path f2 = new Path(path2.getName() + "/" + String.format("%04d", thread));

            final FSDataOutputStream out1 = hdfs.create(f1, true);
            final FSDataOutputStream out2 = hdfs.create(f2, true);

            final int t = thread;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    PrintWriter t1writer = new PrintWriter(out1);
                    PrintWriter t2writer = new PrintWriter(out2);

                    Long[] keys = new Long[(int) uniqueValues / nThreads];
                    for (int i = 0; i < uniqueValues / nThreads; i++) {
                        keys[i] = t * uniqueValues / nThreads + i;
                    }

                    List<Long> keysList = new ArrayList<>(Arrays.asList(keys));
                    Collections.shuffle(keysList);

                    for (int i = 0; i < uniqueValues / nThreads; i++) {
                        String row = "" + (keysList.get(i));

                        for (Attribute a : attributes) {
                            row += "," + a.generate();
                        }

                        row += "\n";

                        t1writer.write(row);
                    }

                    for (int i = 0; i < nRows / nThreads; i++) {
                        //System.out.printf("p = %f, t = %d\n", (double) (i + t * nRows / nThreads) / (double) nRows, t);

                        String row = "" + zipfInverseCdf((double) (i + t * nRows / nThreads) / (double) nRows,
                                s, (double) uniqueValues);

                        for (Attribute a : attributes) {
                            row += "," + a.generate();
                        }

                        row += "\n";

                        t2writer.write(row);
                    }

                    t1writer.close();
                    t2writer.close();

                    latch.countDown();

                }
            }).run();
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void writeZipfBoth(DataOutputStream file1, DataOutputStream file2, double s) {
        PrintWriter t1writer = new PrintWriter(file1);
        PrintWriter t2writer = new PrintWriter(file2);

        for (int i = 0; i < nRows; i++) {
            String row = "" + zipfInverseCdf((double)i / (double)nRows, s, (double) uniqueValues);

            for (Attribute a : attributes) {
                row += "," + a.generate();
            }

            row += "\n";

            t1writer.write(row);
            t2writer.write(row);
        }

        t1writer.close();
        t2writer.close();
    }

    /**
     * A quick way to test the data generator's performance
     * @param args
     */
    public static void main(String[] args) throws IOException {
        final FileSystem hdfs = FileSystem.get(new Configuration());

        System.out.println("Max: " + (int) (0.8 * 2000000 / DataGenerator.getMaxZipfRepeats(200000, 1.0, 2000000)));

        int rowsStep = 1000000;
        int repetitions = 10;

        for (int i = 1; i <= 1; i++) {
            int nRows = i * rowsStep;

            DataGenerator dg = new DataGenerator(DataGenerator.KeyType.NUMERIC, nRows,
                    Arrays.asList(new DataGenerator.Attribute(20), new DataGenerator.Attribute(100),
                            new DataGenerator.Attribute(80)), nRows / 10);

//            File input1 = new File("t1_" + nRows + ".csv");
//            File input2 = new File("t2_" + nRows + ".csv");

            Path input1 = new Path("t1_" + nRows + ".csv");
            Path input2 = new Path("t2_" + nRows + ".csv");

            //DataOutputStream out1 = new DataOutputStream(new FileOutputStream(input1));
            //DataOutputStream out2 = new DataOutputStream(new FileOutputStream(input2));

            long startTime = System.nanoTime();
            dg.writeZipfParallelToHdfs(input1, input2, 0.5, 8);
            long endTime = System.nanoTime();

            long diff = endTime - startTime;

            //out1.close();
            //out2.close();

            System.out.printf("Data generated(nrows=%d): %.3f ms - file size of t2: %dMB\n",
                    nRows, diff / 1000000.0,  hdfs.getContentSummary(input2).getSpaceConsumed() / 1000000);

            hdfs.delete(input1, true);
            hdfs.delete(input2, true);
        }
    }

    /**
     * An approximation of the inverse CDF of the Zipf distribution
     * Source: https://medium.com/@jasoncrease/zipf-54912d5651cc
     * @param p Probability
     * @param s Skew parameter: 0 = no skew, 1 ~ skew of the English language
     * @param N The number of elements
     * @return The value
     */
    private static long zipfInverseCdf(final double p, final double s, final double N) {
        if (p > 1d || p < 0d)
            throw new IllegalArgumentException("p must be between 0 and 1");

        final double tolerance = 0.01d;
        double x = N / 2;

        final double D = p * (12 * (Math.pow(N, 1 - s) - 1) / (1 - s) + 6 - 6 * Math.pow(N, -s) + s - Math.pow(N, -1 - s) * s);

        while (true) {
            final double m    = Math.pow(x, -2 - s);
            final double mx   = m   * x;
            final double mxx  = mx  * x;
            final double mxxx = mxx * x;

            final double a = 12 * (mxxx - 1) / (1 - s) + 6 * (1 - mxx) + (s - (mx * s)) - D;
            final double b = 12 * mxx + 6 * (s * mx) + (m * s * (s + 1));
            final double newx = Math.max(1, x - a / b);
            if (Math.abs(newx - x) <= tolerance)
                return (long) newx;
            x = newx;
        }
    }
}
